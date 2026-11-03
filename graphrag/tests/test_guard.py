"""A generated query runs only if it reads, is one statement and stays inside the graph of one version."""
import pytest

from app.guard import CypherRejected, validate_cypher

SCOPE = "d150v155"


def ok(query):
    return validate_cypher(query, SCOPE)


def test_a_scoped_read_is_accepted_and_trimmed():
    query = f"MATCH (c:Customer {{_scope: '{SCOPE}'}}) RETURN count(c) AS total;  "

    assert ok(query) == f"MATCH (c:Customer {{_scope: '{SCOPE}'}}) RETURN count(c) AS total"


def test_a_relationship_between_two_scoped_nodes_is_accepted():
    ok(f"MATCH (a:Customer {{_scope: '{SCOPE}'}})-[:HAS_ORDER]->(b:Order {{_scope: '{SCOPE}'}}) RETURN a.key, count(b) AS n ORDER BY n DESC LIMIT 20")


def test_a_where_filter_on_the_scope_is_accepted_too():
    ok(f"MATCH (c:Customer) WHERE c._scope = '{SCOPE}' AND c._conflict = true RETURN c.key LIMIT 20")


def test_a_pattern_without_a_label_needs_no_scope_of_its_own():
    ok(f"MATCH (a:Customer {{_scope: '{SCOPE}'}}) WHERE NOT (a)-[:HAS_ORDER]->() RETURN a.key LIMIT 20")


@pytest.mark.parametrize("query", [
    f"MATCH (c:Customer {{_scope: '{SCOPE}'}}) SET c.email = 'x' RETURN c",
    f"MATCH (c:Customer {{_scope: '{SCOPE}'}}) DETACH DELETE c RETURN 1",
    f"CREATE (c:Customer {{_scope: '{SCOPE}'}}) RETURN c",
    f"MATCH (c:Customer {{_scope: '{SCOPE}'}}) REMOVE c.email RETURN c",
    f"MERGE (c:Customer {{_scope: '{SCOPE}'}}) RETURN c",
    f"CALL dbms.components() YIELD name RETURN name, '{SCOPE}'",
    f"CALL apoc.cypher.run('MATCH (n) DETACH DELETE n', {{}}) YIELD value RETURN value, '{SCOPE}'",
    f"LOAD CSV FROM 'file:///x.csv' AS row RETURN row, '{SCOPE}'",
    f"MATCH (c:Customer {{_scope: '{SCOPE}'}}) RETURN c; MATCH (n) DETACH DELETE n",
    f"MATCH (c:Customer {{_scope: '{SCOPE}'}}) RETURN c /* harmless */ ; DROP INDEX x",
])
def test_nothing_that_writes_or_calls_a_procedure_runs(query):
    with pytest.raises(CypherRejected):
        ok(query)


def test_words_inside_strings_and_backticks_are_not_commands():
    ok(f"MATCH (c:Customer {{_scope: '{SCOPE}'}}) WHERE c.name = 'Set Delete Create' OR c.`set` = 1 RETURN c.key LIMIT 5")


def test_the_whole_graph_cannot_be_read():
    with pytest.raises(CypherRejected, match="filter every node"):
        ok("MATCH (n) RETURN n LIMIT 10")
    with pytest.raises(CypherRejected, match="_scope"):
        ok("MATCH (n:Customer) RETURN n.key LIMIT 10")


def test_another_version_cannot_be_read():
    with pytest.raises(CypherRejected, match="d1v2"):
        ok(f"MATCH (a:Customer {{_scope: '{SCOPE}'}}), (b:Customer {{_scope: 'd1v2'}}) RETURN a.key, b.key LIMIT 5")


def test_every_labelled_node_needs_its_scope():
    with pytest.raises(CypherRejected, match="Every node"):
        ok(f"MATCH (a:Customer {{_scope: '{SCOPE}'}})-[:HAS_ORDER]->(b:Order) RETURN a.key, b.key LIMIT 5")


def test_an_empty_query_or_a_bad_scope_is_refused():
    with pytest.raises(CypherRejected):
        ok("   ")
    with pytest.raises(CypherRejected, match="not a valid graph scope"):
        validate_cypher("MATCH (n) RETURN n", "everything")
