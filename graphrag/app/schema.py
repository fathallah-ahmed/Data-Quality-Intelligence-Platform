"""What the model is told about the graph: its shape, and example queries written with the real labels."""
import json
from typing import Any, Dict, List

MAX_FACTS_CHARS = 7000


def graph_schema(facts: Dict[str, Any]) -> Dict[str, Any]:
    return (facts.get("graph") or {}).get("schema") or {}


def schema_text(facts: Dict[str, Any]) -> str:
    schema = graph_schema(facts)
    built = (facts.get("graph") or {}).get("built") or {}
    counts = built.get("nodes") or {}
    lines: List[str] = [
        "Every node has: key (the value that identifies it), name, _scope and _rows (how many table rows it merges).",
        "When the rows that share a key disagree about a property, that property is NOT set on the node: "
        "`<property>__candidates` holds every value, `_conflict` is true and `_conflicts` lists the properties.",
        "",
        "Nodes:",
    ]
    for node in schema.get("nodes", []):
        properties = ", ".join(p["column"] for p in node.get("properties", [])) or "(none besides key)"
        count = f" [{counts[node['label']]} nodes]" if node["label"] in counts else ""
        origin = f" - {node['origin']}" if node.get("origin") else ""
        lines.append(f"(:{node['label']}) key={node.get('keyColumn') or 'row number'}; properties: {properties}{count}{origin}")
    lines += ["", "Relationships:"]
    for relationship in schema.get("relationships", []):
        lines.append(f"(:{relationship['fromLabel']})-[:{relationship['type']}]->(:{relationship['toLabel']})")
    return "\n".join(lines)


def examples(facts: Dict[str, Any], scope: str) -> List[str]:
    """Queries in the shape that is allowed, written with labels that exist in this graph."""
    schema = graph_schema(facts)
    nodes = schema.get("nodes", [])
    relationships = schema.get("relationships", [])
    found: List[str] = []
    if nodes:
        label = nodes[0]["label"]
        found.append(f"# How many {label} are there?\nMATCH (n:{label} {{_scope: '{scope}'}}) RETURN count(n) AS total")
        found.append(f"# Which {label} have a conflict, and on which properties?\n"
                     f"MATCH (n:{label} {{_scope: '{scope}'}}) WHERE n._conflict = true RETURN n.key AS key, n._conflicts AS properties LIMIT 20")
    if relationships:
        r = relationships[0]
        found.append(f"# Which {r['fromLabel']} have the most {r['toLabel']}?\n"
                     f"MATCH (a:{r['fromLabel']} {{_scope: '{scope}'}})-[:{r['type']}]->(b:{r['toLabel']} {{_scope: '{scope}'}}) "
                     f"RETURN a.key AS {r['fromLabel'].lower()}, count(b) AS total ORDER BY total DESC LIMIT 20")
        found.append(f"# Which {r['fromLabel']} have no {r['toLabel']}?\n"
                     f"MATCH (a:{r['fromLabel']} {{_scope: '{scope}'}}) WHERE NOT (a)-[:{r['type']}]->(:{r['toLabel']} {{_scope: '{scope}'}}) "
                     f"RETURN a.key AS {r['fromLabel'].lower()} LIMIT 20")
    return found


def facts_text(facts: Dict[str, Any]) -> str:
    """The quality facts the backend gathered (rules, dimensions, findings), short enough for the prompt."""
    keep = {key: facts[key] for key in ("dataset", "version", "context", "rules", "dimensions", "findings", "columns") if key in facts}
    text = json.dumps(keep, ensure_ascii=False, default=str)
    return text if len(text) <= MAX_FACTS_CHARS else text[:MAX_FACTS_CHARS] + " …(shortened)"
