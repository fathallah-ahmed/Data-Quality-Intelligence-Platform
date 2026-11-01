"""The domain, objective and row grain given at upload must reach the LLM prompt."""
import json

from dq_pipeline.analyzers import langgraph_analyzer as lg
from dq_pipeline.analyzers.llm_analyzer import describe_business_context
from dq_pipeline.pipeline_runner import PipelineRunner


def test_the_three_answers_become_one_paragraph():
    text = describe_business_context(
        {"domain": "Retail banking", "objective": "Contact customers", "row_grain": "one account"}
    )

    assert "Business domain: Retail banking." in text
    assert "Goal of the analysis: Contact customers." in text
    assert "One row represents: one account." in text


def test_nothing_given_gives_nothing():
    assert describe_business_context(None) == ""
    assert describe_business_context({}) == ""
    assert describe_business_context({"domain": ""}) == ""


def test_prompt_carries_the_context_and_the_grain_rule():
    evidence = {"allowed_columns": ["customer_id"], "column_map": {}}
    prompt = lg._build_analysis_prompt(
        table_name="accounts",
        evidence=evidence,
        description=describe_business_context({"row_grain": "one account"}),
        custom_question="",
    )

    assert "One row represents: one account." in prompt
    assert "repeated identifiers are" in prompt


def _runner(tmp_path):
    runner = PipelineRunner.__new__(PipelineRunner)
    runner.file_path = tmp_path
    runner.errors = []
    return runner


def test_runner_reads_the_context_file_next_to_the_tables(tmp_path):
    (tmp_path / "dataset_context.json").write_text(
        json.dumps({"domain": " Banking ", "objective": "", "row_grain": "one account"}), encoding="utf-8"
    )

    assert _runner(tmp_path)._business_context() == {"domain": "Banking", "row_grain": "one account"}


def test_runner_tolerates_a_missing_or_broken_context_file(tmp_path):
    runner = _runner(tmp_path)
    assert runner._business_context() == {}

    (tmp_path / "dataset_context.json").write_text("{not json", encoding="utf-8")
    assert runner._business_context() == {}
