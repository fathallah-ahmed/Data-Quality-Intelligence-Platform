"""
What a generated Cypher query may do.

The model writes the query, so nothing it writes is trusted. A query runs only if it reads (never writes), is a
single statement, uses no procedure, and stays inside the one graph the user asked about: every node it
matches must carry the scope of that version (``_scope: 'd150v155'``) and no other scope may appear.
Neo4j is asked once more, with EXPLAIN, whether the query is read-only before it is run for real.
"""
import re

SCOPE_PATTERN = re.compile(r"^d\d+v\d+$")
_STRING = re.compile(r"'(?:\\.|[^'\\])*'|\"(?:\\.|[^\"\\])*\"|`[^`]*`")
_COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/", re.DOTALL)
_FORBIDDEN = re.compile(
    r"\b(CREATE|MERGE|DELETE|DETACH|SET|REMOVE|DROP|FOREACH|CALL|LOAD|USING|GRANT|DENY|REVOKE|START|STOP|"
    r"TERMINATE|USE|SHOW|ALTER|RENAME|APOC|DBMS)\b",
    re.IGNORECASE,
)
_LABELLED_NODE = re.compile(r"\(\s*[A-Za-z_]\w*\s*:\s*[A-Za-z]|\(\s*:\s*[A-Za-z]")
_SCOPE_TOKEN = re.compile(r"\bd\d+v\d+\b")


class CypherRejected(ValueError):
    """The query is not allowed to run; the message says why, so the model can write a better one."""


def validate_cypher(cypher: str, scope: str) -> str:
    """Return the query ready to run, or raise :class:`CypherRejected`."""
    if not SCOPE_PATTERN.match(scope or ""):
        raise CypherRejected(f"'{scope}' is not a valid graph scope")
    query = (cypher or "").strip().rstrip(";").strip()
    if not query:
        raise CypherRejected("The query is empty")

    # Words inside strings and comments (a customer called 'Set', a comment) must not count.
    code = _STRING.sub("''", _COMMENT.sub(" ", query))
    if ";" in code:
        raise CypherRejected("Only one statement is allowed")
    forbidden = _FORBIDDEN.search(code)
    if forbidden:
        raise CypherRejected(f"{forbidden.group(1).upper()} is not allowed: the query may only read")
    if not re.search(r"\b(MATCH|RETURN|WITH|UNWIND)\b", code, re.IGNORECASE) or not re.search(r"\bRETURN\b", code, re.IGNORECASE):
        raise CypherRejected("The query must MATCH something and RETURN a result")

    literals = [text[1:-1] for text in _STRING.findall(query) if text[0] in "'\""]
    scopes = {token for text in literals for token in _SCOPE_TOKEN.findall(text)}
    if scopes - {scope}:
        raise CypherRejected(f"Only the graph '{scope}' may be read, not {sorted(scopes - {scope})[0]}")
    if scope not in literals:
        raise CypherRejected(f"The query must filter every node on _scope: '{scope}'")
    labelled = len(_LABELLED_NODE.findall(code))
    scoped = len(re.findall(r"\b_scope\b", code))
    if scoped < labelled:
        raise CypherRejected(f"Every node must have _scope: '{scope}' ({labelled} node patterns, {scoped} scope filters)")
    return query
