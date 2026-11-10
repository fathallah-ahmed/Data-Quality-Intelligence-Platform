"""
Answering a question about one dataset version.

A question is first sorted: about the data itself (answered from the graph), about quality (rules, scores, findings:
answered from the facts the backend gathered) or about how to use the application (answered from its guide). Only the
first kind needs Neo4j, so the other two work before a graph is built.

For a question about the data the model is used twice and trusted never. First it turns the question into a Cypher
query (prompt template and Cypher extraction come from neo4j-graphrag); the query is checked by :mod:`app.guard`,
confirmed read-only by Neo4j, and run inside a time limit. Then the model writes the answer from what came back, from
the quality facts and from the guide, and from nothing else. The query and its rows are returned with the answer so a
person can check it.
"""
import json
import logging
from typing import Any, Dict, List, Optional, Tuple

import neo4j
from neo4j.exceptions import AuthError, CypherSyntaxError, DatabaseError, ClientError, ServiceUnavailable
from neo4j_graphrag.generation.prompts import Text2CypherTemplate
from neo4j_graphrag.retrievers.text2cypher import extract_cypher

from app.guard import SCOPE_PATTERN, CypherRejected, validate_cypher
from app.schema import examples, facts_text, graph_schema, guide_text, schema_text

log = logging.getLogger("qa")

READ_ONLY_QUERY_TYPE = "r"
MAX_ROWS = 50
ROWS_IN_ANSWER_PROMPT = 30
ROWS_RETURNED = 15
MAX_ATTEMPTS = 2

GRAPH, QUALITY, HELP = "graph", "quality", "help"

ROUTE_PROMPT = """ROUTING. Sort the question into exactly one of these words and reply with that word only.

graph   - about the content of the data: counts, which records or people, relationships between entities, rankings, a specific value
quality - about data quality: rules and why one fails, scores, completeness, uniqueness, validity, missing values, findings, columns to fix, what to do about a problem
help    - about the application itself: how to do something (run, correct, apply, build, export...), what a tab, button, score, grade, severity or term means, how a figure is computed, where to find something
          (a question about what a score or a term MEANS or how it is computed is help; a question about the value of a score in this dataset is quality)

Question: {question}

Word: """

CYPHER_PROMPT = """Task: write ONE Cypher query that answers the question about this graph. The graph of this dataset version is
identified by the scope '{scope}'.

Schema:
{schema}

Examples of queries in the allowed shape:
{examples}

RULES:
- Read only: MATCH / WHERE / WITH / RETURN / ORDER BY / LIMIT. No CALL, no writes.
- Every node you match must have the scope: (n:Label {{_scope: '{scope}'}}). Never mention another scope.
- Use only the labels, relationship types and properties of the schema. A property that has a space or a capital letter is written with backticks.
- Return readable values (n.key AS customer, n.email AS email, count(x) AS total), not whole nodes.
- A key such as row-17 only says which line of the file a node is. When the node has properties that name it (a name, a surname, a title), return them next to the key so the answer can say who it is.
- Compare text without caring about case: toLower(n.country) = 'fr'.
- End with LIMIT 50 unless the question asks for a count or a single value.
- Do not include ``` or any text except the Cypher statement.

Question: {query_text}

Cypher query: """

ANSWER_PROMPT = """You answer a question about a dataset and about the data quality application, using ONLY what is below.

QUESTION: {question}

QUALITY FACTS (rules and their results, quality dimensions, findings of the graph build, column roles):
{facts}

APPLICATION GUIDE (how to use the application):
{guide}

GRAPH QUERY RUN (read-only, limited to this dataset version):
{cypher}

QUERY RESULT ({row_count} row(s){truncated}):
{rows}

RULES:
- Answer in the language of the question, in a few sentences.
- Quote figures and names exactly as they appear above. Never invent a value, a column, a customer, a rule or a button.
- If the result is empty, say that nothing was found; do not guess why unless a quality fact explains it.
- If the question asks why something is invalid or failing, use the matching rule, its message and its examples, and say which rule it is. If the facts list no rules, say that no rule is defined or evaluated for this version, which is not the same as everything being fine.
- If the question is about how to use the application, answer from the guide with the steps and the tab names, and say so when the guide does not cover it.
- {graph_note}
- Mention a conflict (a property with several candidate values) when it is relevant: it means the data disagrees with itself.
"""


EVIDENCE_PROMPT = """Task: write ONE Cypher query that returns the nodes and relationships behind the answer to a question, so they can be drawn as a graph.
The graph of this dataset version is identified by the scope '{scope}'.

Schema:
{schema}

QUESTION: {question}

QUERY ALREADY RUN FOR THE ANSWER (it returns values, not nodes):
{cypher}

RULES:
- Read only: MATCH / WHERE / WITH / RETURN / LIMIT. No CALL, no writes.
- Every node you match must have the scope: (n:Label {{_scope: '{scope}'}}). Never mention another scope.
- Use the same labels, relationship types and filters as the query above, but RETURN the nodes and relationships themselves
  (for example RETURN a, r, b), never properties or aggregates.
- Name every relationship ([r:TYPE]) and return it, so the edges can be drawn.
- If the query above counts or ranks, first pick the nodes (WITH ... ORDER BY ... LIMIT 3), then MATCH their relationships again and RETURN a, r, b.
  Do not UNWIND several lists together: that multiplies the rows.
- End with LIMIT 25.
- Do not include ``` or any text except the Cypher statement.

Cypher query: """

MAX_NODES = 80
MAX_EDGES = 160
EXPAND_LIMIT = 40


class GraphUnavailable(RuntimeError):
    """Neo4j cannot be reached or refuses the credentials."""


def _kind(value: Any) -> str:
    """What a value of a Neo4j record is. Checked by shape so the driver's own classes are not needed."""
    if hasattr(value, "labels") and hasattr(value, "element_id"):
        return "node"
    if hasattr(value, "start_node") and hasattr(value, "end_node") and hasattr(value, "element_id"):
        return "relationship"
    if hasattr(value, "nodes") and hasattr(value, "relationships"):
        return "path"
    if isinstance(value, (list, tuple)):
        return "list"
    return "other"


def _caption(properties: Dict[str, Any]) -> str:
    """What names a node to a person: its name when it has one (first and last name together), else nothing.

    A key such as 703 only says which line of the file a node is. A property whose name says it is a name or a title
    (first_name, product_name, title...) reads better. A "name" that only repeats the key does not count.
    """
    key = str(properties.get("key", ""))
    found = []
    for column, value in properties.items():
        lowered = column.lower()
        if column.startswith("_") or not any(word in lowered for word in ("name", "title", "label")):
            continue
        text = str(value).strip() if isinstance(value, (str, int, float)) and not isinstance(value, bool) else ""
        if text and text != key:
            order = 0 if "first" in lowered or "given" in lowered else 2 if "last" in lowered or "sur" in lowered else 1
            found.append((order, text))
    return " ".join(text for _, text in sorted(found, key=lambda item: item[0])[:2])


class GraphCollector:
    """Gathers the nodes and relationships found in query results, inside one scope and within a size limit."""

    def __init__(self, scope: str):
        self.scope = scope
        self.nodes: Dict[str, Dict[str, Any]] = {}
        self.edges: Dict[str, Dict[str, Any]] = {}
        self.truncated = False

    def add(self, value: Any) -> None:
        kind = _kind(value)
        if kind == "node":
            self._node(value)
        elif kind == "relationship":
            self._node(value.start_node)
            self._node(value.end_node)
            self._edge(value)
        elif kind == "path":
            for node in value.nodes:
                self._node(node)
            for relationship in value.relationships:
                self._edge(relationship)
        elif kind == "list":
            for item in value:
                self.add(item)

    def _node(self, node: Any) -> None:
        properties = {str(key): to_plain(item) for key, item in dict(node.items()).items()}
        if properties.get("_scope") != self.scope:
            return  # a node of another version is never shown
        node_id = str(node.element_id)
        if node_id in self.nodes:
            return
        if len(self.nodes) >= MAX_NODES:
            self.truncated = True
            return
        properties.pop("_scope", None)
        labels = sorted(str(label) for label in node.labels)
        key = properties.get("key")
        label = labels[0] if labels else ""
        self.nodes[node_id] = {
            "id": node_id,
            "label": label,
            "caption": _caption(properties) or (str(key) if key is not None else (label or node_id)),
            "conflict": bool(properties.get("_conflict")),
            "properties": properties,
        }

    def _edge(self, relationship: Any) -> None:
        start, end = str(relationship.start_node.element_id), str(relationship.end_node.element_id)
        edge_id = str(relationship.element_id)
        if edge_id in self.edges:
            return
        if len(self.edges) >= MAX_EDGES:
            self.truncated = True
            return
        self.edges[edge_id] = {"id": edge_id, "from": start, "to": end, "type": str(relationship.type)}

    def result(self) -> Dict[str, Any]:
        """Nodes and the edges whose two ends are both kept."""
        edges = [edge for edge in self.edges.values() if edge["from"] in self.nodes and edge["to"] in self.nodes]
        return {"nodes": list(self.nodes.values()), "edges": edges, "truncated": self.truncated}


def to_plain(value: Any) -> Any:
    """Make a value from a Neo4j record safe to turn into JSON."""
    if isinstance(value, dict):
        return {str(key): to_plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [to_plain(item) for item in value]
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


class Answerer:
    def __init__(self, driver: neo4j.Driver, llm: Any, query_timeout: float = 15.0):
        self.driver = driver
        self.llm = llm
        self.query_timeout = query_timeout

    # ------------------------------------------------------------------ which kind of question

    def _route(self, question: str, graph_built: bool) -> str:
        """The model sorts the question; anything it says that is not one of the three words counts as quality."""
        reply = self.llm.invoke(ROUTE_PROMPT.format(question=question)).content.strip().lower()
        words = reply.replace(".", " ").replace(",", " ").split()
        choice = next((word for word in words if word in (GRAPH, QUALITY, HELP)), QUALITY)
        # Without a graph there is nothing to query: the question is answered from the quality facts.
        return QUALITY if choice == GRAPH and not graph_built else choice

    # ------------------------------------------------------------------ the query

    def _execute(self, cypher: str, parameters: Optional[Dict[str, Any]] = None) -> Tuple[List[Any], List[str]]:
        """Confirm with Neo4j that the query only reads, then run it with a time limit."""
        try:
            _, summary, _ = self.driver.execute_query(
                neo4j.Query(f"EXPLAIN {cypher}", timeout=self.query_timeout), parameters_=parameters,
                routing_=neo4j.RoutingControl.READ)
            if summary.query_type != READ_ONLY_QUERY_TYPE:
                raise CypherRejected(f"Neo4j says the query is not read-only (type {summary.query_type!r})")
            records, _, keys = self.driver.execute_query(
                neo4j.Query(cypher, timeout=self.query_timeout), parameters_=parameters, routing_=neo4j.RoutingControl.READ)
        except (ServiceUnavailable, AuthError) as error:
            raise GraphUnavailable(f"Neo4j cannot be used: {error}") from error
        return records, list(keys)

    def _run(self, cypher: str) -> Tuple[List[str], List[Dict[str, Any]], int]:
        records, keys = self._execute(cypher)
        rows = [to_plain(record.data()) for record in records[:MAX_ROWS]]
        return keys, rows, len(records)

    # ------------------------------------------------------------------ the sub-graph behind an answer

    def subgraph(self, question: str, cypher: str, scope: str, facts: Dict[str, Any]) -> Dict[str, Any]:
        """The nodes and relationships behind an answer, to be drawn.

        The query of the answer returns values (names, counts), which say nothing about which nodes they came from. So the
        model is asked once more for a query that returns the nodes and relationships themselves. That query goes through the
        same checks as the first one, and any node of another scope is dropped from the result.
        """
        question = (question or "").strip()
        cypher = (cypher or "").strip()
        if not question or not cypher:
            raise ValueError("A question and the query of its answer are needed")
        schema = schema_text(facts)
        warnings: List[str] = []
        feedback = ""
        for attempt in range(MAX_ATTEMPTS):
            prompt = EVIDENCE_PROMPT.format(scope=scope, schema=schema, question=question + feedback, cypher=cypher)
            try:
                evidence = validate_cypher(extract_cypher(self.llm.invoke(prompt).content), scope)
                records, _ = self._execute(evidence)
                collector = GraphCollector(scope)
                for record in records:
                    collector.add(record.values())
                if not collector.nodes:
                    raise CypherRejected("The query returned no node: return the nodes themselves, not their properties")
                self._connect(collector, scope)
                return {**collector.result(), "cypher": evidence, "warnings": warnings}
            except (CypherRejected, CypherSyntaxError, ClientError, DatabaseError) as error:
                reason = getattr(error, "message", None) or str(error)
                log.info("Evidence attempt %d refused: %s", attempt + 1, reason)
                warnings.append(f"Attempt {attempt + 1}: {reason}")
                feedback = f"\n(Your previous query was refused: {reason}. Write a corrected query.)"
        return {"nodes": [], "edges": [], "truncated": False, "cypher": None, "warnings": warnings}

    def _connect(self, collector: GraphCollector, scope: str) -> None:
        """Add the relationships that exist between the nodes already kept, so the drawing is not a heap of points."""
        ids = list(collector.nodes)
        records, _ = self._execute(
            "MATCH (a)-[r]->(b) WHERE elementId(a) IN $ids AND elementId(b) IN $ids AND a._scope = $scope AND b._scope = $scope "
            f"RETURN r LIMIT {MAX_EDGES}", {"ids": ids, "scope": scope})
        for record in records:
            collector.add(record.values())

    def expand(self, scope: str, node_id: str) -> Dict[str, Any]:
        """The neighbours of one node of the graph (both directions), for exploring from a node to the next."""
        if not SCOPE_PATTERN.match(scope or ""):
            raise ValueError(f"'{scope}' is not a valid graph scope")
        records, _ = self._execute(
            "MATCH (n)-[r]-(m) WHERE elementId(n) = $id AND n._scope = $scope AND m._scope = $scope "
            f"RETURN n, r, m LIMIT {EXPAND_LIMIT + 1}", {"id": node_id, "scope": scope})
        collector = GraphCollector(scope)
        for record in records[:EXPAND_LIMIT]:
            collector.add(record.values())
        result = collector.result()
        result["truncated"] = result["truncated"] or len(records) > EXPAND_LIMIT
        return result

    def _query(self, question: str, scope: str, facts: Dict[str, Any]) -> Dict[str, Any]:
        """Ask the model for a query until one is allowed and runs, at most twice."""
        template = Text2CypherTemplate(template=CYPHER_PROMPT)
        schema = schema_text(facts)
        samples = "\n\n".join(examples(facts, scope)) or "(none)"
        feedback = ""
        warnings: List[str] = []
        for attempt in range(MAX_ATTEMPTS):
            prompt = template.format(schema=schema, examples=samples, query_text=question + feedback, scope=scope)
            cypher = ""
            try:
                cypher = extract_cypher(self.llm.invoke(prompt).content)
                cypher = validate_cypher(cypher, scope)
                columns, rows, count = self._run(cypher)
                return {"cypher": cypher, "columns": columns, "rows": rows, "count": count, "warnings": warnings}
            except (CypherRejected, CypherSyntaxError, ClientError, DatabaseError) as error:
                reason = getattr(error, "message", None) or str(error)
                log.info("Query attempt %d refused: %s", attempt + 1, reason)
                warnings.append(f"Attempt {attempt + 1}: {reason}")
                feedback = f"\n(Your previous query was refused: {reason}. Write a corrected query.)"
        return {"cypher": None, "columns": [], "rows": [], "count": 0, "warnings": warnings}

    # ------------------------------------------------------------------ the answer

    def ask(self, question: str, scope: str, facts: Dict[str, Any]) -> Dict[str, Any]:
        question = (question or "").strip()
        if not question:
            raise ValueError("Write a question")
        graph_built = bool(graph_schema(facts).get("nodes"))
        mode = self._route(question, graph_built)
        result: Optional[Dict[str, Any]] = self._query(question, scope, facts) if mode == GRAPH else None
        ran = bool(result and result["cypher"])
        if ran:
            graph_note = "The graph query above was run: base the answer on its rows."
        elif mode == GRAPH:
            graph_note = "No allowed query could be written, so the graph was not consulted: say so and answer from the quality facts alone."
        elif graph_built:
            graph_note = "No graph query was needed for this question."
        else:
            graph_note = ("The graph of this version has not been built, so questions about the data itself cannot be answered yet: "
                          "if the question needs the content of the data, say that the graph must be built in the Graph tab first.")
        result = result or {"cypher": None, "columns": [], "rows": [], "count": 0, "warnings": []}
        rows_text = json.dumps(result["rows"][:ROWS_IN_ANSWER_PROMPT], ensure_ascii=False, default=str) if ran else "(no query was run)"
        answer = self.llm.invoke(ANSWER_PROMPT.format(
            question=question,
            facts=facts_text(facts),
            guide=guide_text(facts),
            cypher=result["cypher"] or "(none)",
            row_count=result["count"],
            truncated=f", only the first {MAX_ROWS} kept" if result["count"] > MAX_ROWS else "",
            rows=rows_text,
            graph_note=graph_note,
        )).content.strip()
        return {
            "answer": answer,
            "mode": mode,
            "cypher": result["cypher"],
            "columns": result["columns"],
            "rows": result["rows"][:ROWS_RETURNED],
            "rowCount": result["count"],
            "truncated": result["count"] > ROWS_RETURNED,
            "warnings": result["warnings"],
        }
