import json

import pandas as pd
import pytest

from dq_pipeline import runners
from dq_pipeline.analyzers.langgraph_analyzer import _format_type_mix
from dq_pipeline.pipeline_runner import PipelineRunner


def _signature(df):
    runner = PipelineRunner.__new__(PipelineRunner)  # pure helpers only, no storage needed
    dataset_metrics, column_metrics = PipelineRunner._extract_metrics(runner, "t", df, {})
    return PipelineRunner._build_data_signature(runner, dataset_metrics, column_metrics, df)


# ------------------------------------------------------------------ change signature

def test_value_edit_changes_content_hash_but_not_counts():
    original = pd.DataFrame({"id": [1, 2, 3], "amount": [10.0, 20.0, 30.0]})
    edited = original.copy()
    edited.loc[1, "amount"] = 21.0

    before, after = _signature(original), _signature(edited)

    assert before["row_count"] == after["row_count"]
    assert before["columns"] == after["columns"]  # the old counts-only signature would say UNCHANGED
    assert before["content_hash"] != after["content_hash"]
    assert before["schema_hash"] == after["schema_hash"]


def test_identical_tables_have_identical_signatures():
    left = pd.DataFrame({"id": [1, 2], "name": ["a", "b"]})
    right = pd.DataFrame({"id": [1, 2], "name": ["a", "b"]})
    assert _signature(left) == _signature(right)


def test_renamed_column_changes_schema_hash():
    left = pd.DataFrame({"id": [1, 2]})
    right = pd.DataFrame({"key": [1, 2]})
    assert _signature(left)["schema_hash"] != _signature(right)["schema_hash"]


def test_empty_table_is_not_id_like():
    df = pd.DataFrame({"id": pd.Series([], dtype="int64")})
    runner = PipelineRunner.__new__(PipelineRunner)
    _, columns = PipelineRunner._extract_metrics(runner, "t", df, {})
    assert columns[0].is_id_like is False


def test_unique_floats_are_not_id_like():
    df = pd.DataFrame({"order_id": [1, 2, 3], "amount": [9.5, 12.0, 7.25]})
    runner = PipelineRunner.__new__(PipelineRunner)
    _, columns = PipelineRunner._extract_metrics(runner, "t", df, {})
    assert {column.name: column.is_id_like for column in columns} == {"order_id": True, "amount": False}


def test_type_mix_is_human_readable():
    assert _format_type_mix({"TYPE_NUM": 3, "TYPE_CAT": 1}) == "3 numerical, 1 categorical"
    assert "{" not in _format_type_mix({"TYPE_TEXT": 2})


# ------------------------------------------------------------------ completion status

@pytest.mark.parametrize(
    "published, failed, forced, expected",
    [
        (3, 0, None, "success"),
        (2, 1, None, "partial"),
        (0, 2, None, "failed"),
        (0, 0, None, "failed"),
        (3, 0, "failed", "failed"),
    ],
)
def test_completion_status(published, failed, forced, expected):
    assert runners.completion_status(published, failed, forced) == expected


# ------------------------------------------------------------------ phases end to end

def _run_all_phases(folder, run_id="run-test"):
    args = {"dataset_id": "1", "version_id": "1", "pipeline_run_id": run_id, "file_path": str(folder)}
    runners.phase_start_pipeline(**args)
    runners.phase_discover_tables(**args)
    runners.phase_profile_tables(**args)
    runners.phase_analyze_tables(**args)
    runners.phase_publish_table_results(**args)
    runners.phase_global_analysis(**args)
    return runners.phase_complete_pipeline(**args)


def test_header_only_table_is_published_and_reported_as_partial(pipeline_env):
    tmp_path, backend = pipeline_env
    folder = tmp_path / "upload"
    folder.mkdir()
    (folder / "orders.csv").write_text("id,amount\n1,10\n2,20\n3,30\n", encoding="utf-8")
    (folder / "empty.csv").write_text("id,name\n", encoding="utf-8")

    outcome = _run_all_phases(folder)

    assert outcome["status"] == "partial"
    completed = backend.completed[-1]
    assert completed["status"] == "partial"
    assert completed["failed_tables"] == [{"table_name": "empty", "error": runners.EMPTY_TABLE_ERROR}]
    assert "empty (Header-only CSV" in completed["error_message"]

    published = {table["table_name"]: table for table in backend.tables}
    assert published["empty"]["dataset_metrics"]["row_count"] == 0
    assert published["empty"]["dataset_metrics"]["column_count"] == 2
    assert published["empty"]["llm_status"] == "disabled"
    # Ollama is unreachable in tests: the deterministic fallback is used and labelled as such.
    assert published["orders"]["llm_status"] == "unavailable"
    summary = published["orders"]["llm_interpretation"]["interpretation"]["summary"]
    assert "unavailable" in summary
    assert backend.globals[-1]["llm_status"] == "unavailable"


def test_all_tables_ok_reports_success(pipeline_env):
    tmp_path, backend = pipeline_env
    folder = tmp_path / "upload"
    folder.mkdir()
    (folder / "customers.csv").write_text("customer_id,name\n1,Ann\n2,Bob\n3,Cid\n", encoding="utf-8")
    (folder / "orders.csv").write_text("order_id,customer_id\n10,1\n11,2\n12,3\n13,1\n", encoding="utf-8")

    outcome = _run_all_phases(folder)

    assert outcome == {"status": "success", "published": 2, "failed": 0}
    orders = next(table for table in backend.tables if table["table_name"] == "orders" and table["llm_status"] != "pending")
    relation = orders["deterministic_relationships"][0]
    assert (relation["source_column"], relation["target_table"]) == ("customer_id", "customers")
    assert orders["data_signature"]["content_hash"]


def test_latest_failed_tables_uses_the_most_advanced_manifest(pipeline_env):
    tmp_path, _backend = pipeline_env
    runner = runners._phase_runner("1", "1", "run-manifest", str(tmp_path))
    runners._write_manifest(runner, "profile_manifest.json", {"failed": [{"table_name": "a", "error": "x"}]})
    runners._write_manifest(runner, "publish_manifest.json", {"failed": [
        {"table_name": "a", "error": "x"},
        {"table_name": "a", "error": "x"},
        {"table_name": "b", "error": "y"},
    ]})
    assert runners.latest_failed_tables(runner) == [
        {"table_name": "a", "error": "x"},
        {"table_name": "b", "error": "y"},
    ]
    assert json.loads(json.dumps(runners.latest_failed_tables(runner)))
