"""The question flow with a scripted model and a fake Neo4j: what is asked, what is refused, what is answered."""
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from neo4j.exceptions import ServiceUnavailable

from app.config import Settings
from app.main import create_app
from app.qa import Answerer, GraphUnavailable, to_plain
from app.schema import examples, schema_text

SCOPE = "d214v220"
GOOD = f"MATCH (c:Customer {{_scope: '{SCOPE}'}}) WHERE c._conflict = true RETURN c.key AS customer LIMIT 50"

FACTS = {
    "dataset": "graph-shop",
    "rules": [{"description": "customers.email has at least 95% of its values as an email address", "status": "FAIL",
               "message": "92.8% of the filled values are an email address (minimum 95%), for example 'c12.mail.com'"}],
    "graph": {
        "built": {"nodes": {"Customer": 40, "Order": 120}},
        "schema": {
            "nodes": [
                {"label": "Customer", "keyColumn": "customer_id", "origin": "One row of customers is one Customer",
                 "properties": [{"column": "customer_name"}, {"column": "email"}]},
                {"label": "Order", "keyColumn": "order_id", "properties": [{"column": "total"}]},
            ],
            "relationships": [{"type": "HAS_ORDER", "fromLabel": "Customer", "toLabel": "Order"}],
        },
    },
}


class FakeLlm:
    """Answers with the next scripted text and remembers every prompt it was given."""

    def __init__(self, *replies):
        self.replies = list(replies)
        self.prompts = []

    def invoke(self, prompt):
        self.prompts.append(prompt)
        return SimpleNamespace(content=self.replies.pop(0))


class Record:
    def __init__(self, data):
        self._data = data

    def data(self):
        return self._data


class FakeDriver:
    def __init__(self, rows=None, query_type="r", error=None):
        self.rows = rows or []
        self.query_type = query_type
        self.error = error
        self.queries = []

    def execute_query(self, query_, routing_=None, **kwargs):
        text = getattr(query_, "text", query_)
        self.queries.append(text)
        if self.error:
            raise self.error
        summary = SimpleNamespace(query_type=self.query_type)
        return [Record(row) for row in self.rows], summary, ["customer"]


def ask(llm, driver, question="Which customers are in conflict?"):
    return Answerer(driver, llm).ask(question, SCOPE, FACTS)


def test_the_question_becomes_a_checked_query_and_an_answer_built_from_its_rows():
    llm = FakeLlm(GOOD, "Two customers are in conflict: C0004 and C0012.")
    driver = FakeDriver(rows=[{"customer": "C0004"}, {"customer": "C0012"}])

    result = ask(llm, driver)

    assert result["answer"] == "Two customers are in conflict: C0004 and C0012."
    assert result["cypher"] == GOOD
    assert result["rows"] == [{"customer": "C0004"}, {"customer": "C0012"}]
    assert result["rowCount"] == 2 and result["warnings"] == []
    # Neo4j was asked to EXPLAIN first, then to run it.
    assert driver.queries == [f"EXPLAIN {GOOD}", GOOD]
    # The answer is written from the rows and from the quality facts, and the model is told to invent nothing.
    answer_prompt = llm.prompts[1]
    assert "C0004" in answer_prompt and "c12.mail.com" in answer_prompt and "Never invent" in answer_prompt


def test_the_query_prompt_knows_the_real_schema_the_scope_and_the_allowed_shape():
    llm = FakeLlm(GOOD, "ok")

    ask(llm, FakeDriver())

    prompt = llm.prompts[0]
    assert "(:Customer) key=customer_id; properties: customer_name, email [40 nodes]" in prompt
    assert "(:Customer)-[:HAS_ORDER]->(:Order)" in prompt
    assert f"(n:Label {{_scope: '{SCOPE}'}})" in prompt          # the rule, with the scope of this version filled in
    assert "<property>__candidates" in prompt and "_conflict" in prompt
    assert "Which Customer have no Order?" in prompt


def test_a_query_that_writes_is_refused_and_the_model_is_told_why_for_its_second_try():
    llm = FakeLlm(f"MATCH (c:Customer {{_scope: '{SCOPE}'}}) DETACH DELETE c RETURN 1", GOOD, "Done.")
    driver = FakeDriver(rows=[{"customer": "C0004"}])

    result = ask(llm, driver)

    assert result["cypher"] == GOOD
    assert "DETACH is not allowed" in result["warnings"][0]
    assert "previous query was refused: DETACH is not allowed" in llm.prompts[1]
    assert all("DELETE" not in query for query in driver.queries)   # the refused query never reached Neo4j


def test_when_no_allowed_query_can_be_written_the_answer_comes_from_the_facts_alone():
    bad = "MATCH (n) RETURN n"
    llm = FakeLlm(bad, bad, "The graph was not consulted. Rule X fails.")
    driver = FakeDriver()

    result = ask(llm, driver, "Why is this email invalid?")

    assert result["cypher"] is None and result["rows"] == []
    assert len(result["warnings"]) == 2
    assert driver.queries == []
    assert "no allowed query could be written" in llm.prompts[2]
    assert result["answer"].startswith("The graph was not consulted")


def test_neo4j_saying_the_query_is_not_read_only_stops_it_before_it_runs():
    llm = FakeLlm(GOOD, GOOD, "Nothing.")
    driver = FakeDriver(query_type="rw")

    result = ask(llm, driver)

    assert result["cypher"] is None
    assert "not read-only" in result["warnings"][0]
    assert driver.queries == [f"EXPLAIN {GOOD}", f"EXPLAIN {GOOD}"]


def test_neo4j_being_down_is_an_error_not_an_empty_answer():
    llm = FakeLlm(GOOD)

    with pytest.raises(GraphUnavailable):
        ask(llm, FakeDriver(error=ServiceUnavailable("connection refused")))


def test_a_long_result_is_cut_and_says_so():
    rows = [{"customer": f"C{i}"} for i in range(80)]
    llm = FakeLlm(GOOD, "Many.")

    result = ask(llm, FakeDriver(rows=rows))

    assert result["rowCount"] == 80 and result["truncated"] is True and len(result["rows"]) == 15
    assert "only the first 50 kept" in llm.prompts[1]


def test_values_from_neo4j_become_plain_json():
    class Odd:
        def __str__(self):
            return "2024-01-05"

    assert to_plain({"a": [1, None, Odd()], "b": ("x",)}) == {"a": [1, None, "2024-01-05"], "b": ["x"]}


def test_schema_text_and_examples_use_the_labels_of_this_graph():
    assert "(:Order) key=order_id; properties: total [120 nodes]" in schema_text(FACTS)
    assert any("Which Customer have the most Order?" in example for example in examples(FACTS, SCOPE))
    assert examples({}, SCOPE) == []


# ------------------------------------------------------------------ the HTTP service

def client(answerer):
    settings = Settings("bolt://x", "neo4j", "x", "http://x", "model", "secret-token", 5.0)
    return TestClient(create_app(settings, answerer))


BODY = {"question": "Which customers are in conflict?", "scope": SCOPE, "facts": FACTS}


def test_the_service_needs_its_token():
    http = client(lambda: Answerer(FakeDriver(), FakeLlm(GOOD, "x")))

    assert http.post("/ask", json=BODY).status_code == 401
    assert http.post("/ask", json=BODY, headers={"X-QA-Token": "wrong"}).status_code == 401
    assert http.get("/health").json()["status"] == "ok"


def test_the_service_answers_and_validates_its_input():
    http = client(lambda: Answerer(FakeDriver(rows=[{"customer": "C1"}]), FakeLlm(GOOD, "One customer.")))
    headers = {"X-QA-Token": "secret-token"}

    ok = http.post("/ask", json=BODY, headers=headers)
    assert ok.status_code == 200 and ok.json()["answer"] == "One customer."
    assert http.post("/ask", json={**BODY, "scope": "everything"}, headers=headers).status_code == 422
    assert http.post("/ask", json={**BODY, "question": ""}, headers=headers).status_code == 422


def test_the_service_says_503_when_neo4j_or_the_model_is_unavailable():
    headers = {"X-QA-Token": "secret-token"}

    down = client(lambda: Answerer(FakeDriver(error=ServiceUnavailable("refused")), FakeLlm(GOOD)))
    assert down.post("/ask", json=BODY, headers=headers).status_code == 503

    class BrokenLlm:
        def invoke(self, prompt):
            raise ConnectionError("Ollama is not running")

    broken = client(lambda: Answerer(FakeDriver(), BrokenLlm()))
    response = broken.post("/ask", json=BODY, headers=headers)
    assert response.status_code == 503 and "Ollama is not running" in response.json()["detail"]
