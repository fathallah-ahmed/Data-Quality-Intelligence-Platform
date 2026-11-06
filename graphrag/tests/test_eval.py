"""The checks of the question set (eval/run_eval.py): what makes a case pass or fail."""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("run_eval", Path(__file__).resolve().parent.parent / "eval" / "run_eval.py")
run_eval = importlib.util.module_from_spec(spec)
spec.loader.exec_module(run_eval)


def test_a_case_passes_when_the_kind_and_the_words_match():
    case = {"mode": "graph", "contains": ["1615"], "any_of": ["orders", "order"], "not_contains": ["unknown"]}
    answer = {"answer": "There are 1615 orders.", "mode": "graph", "cypher": "MATCH (n) RETURN count(n)"}

    assert run_eval.check(case, answer) == []


def test_each_way_to_fail_is_reported_apart():
    case = {"mode": "help", "contains": ["Rules"], "any_of": ["Check now"], "not_contains": ["deleted"]}
    answer = {"answer": "Everything was deleted.", "mode": "quality"}

    problems = run_eval.check(case, answer)

    assert len(problems) == 4
    assert any("expected a help answer" in problem for problem in problems)
    assert any("missing 'Rules'" in problem for problem in problems)
    assert any("none of" in problem for problem in problems)
    assert any("must not say 'deleted'" in problem for problem in problems)


def test_a_graph_answer_must_show_its_query_and_an_error_is_a_failure():
    assert run_eval.check({"mode": "graph"}, {"answer": "ok", "mode": "graph", "cypher": None}) == ["a graph answer without the query that supports it"]
    assert "the service answered an error" in run_eval.check({}, {"error": "down"})[0]
    assert run_eval.check({}, {"answer": "  ", "mode": "quality"}) == ["empty answer"]
