import pytest
import requests

from dq_pipeline import backend_client
from dq_pipeline.backend_client import BackendClient
from dq_pipeline.models import DatasetMetrics, ResultBuilder


class FakeResponse:
    def __init__(self, status_code):
        self.status_code = status_code
        self.text = ""


@pytest.fixture
def client(monkeypatch):
    sleeps = []
    monkeypatch.setattr(backend_client.time, "sleep", lambda seconds: sleeps.append(seconds))
    backend = BackendClient(backend_url="http://backend.test")
    backend.sleeps = sleeps
    return backend


def _script_session(client, outcomes):
    """Make session.post fail or answer as scripted: exceptions are raised, ints are HTTP statuses."""
    calls = []

    def post(url, **_kwargs):
        calls.append(url)
        outcome = outcomes[min(len(calls), len(outcomes)) - 1]
        if isinstance(outcome, Exception):
            raise outcome
        return FakeResponse(outcome)

    client.session.post = post
    return calls


def _table_result():
    metrics = DatasetMetrics(row_count=1, column_count=1, num_categorical=0, num_numerical=1, num_text=0, num_date=0,
                             duplicate_rows=0, duplicate_percent=0.0)
    return ResultBuilder.table_analysis("1", "1", "run-1", "orders", metrics, [], 0.1)


def test_result_callbacks_keep_retrying_while_the_backend_restarts(client):
    down = requests.exceptions.ConnectionError("backend is restarting")
    calls = _script_session(client, [down, down, down, down, down, 200])

    assert client.post_table_analysis("run-1", _table_result()) is True

    assert len(calls) == 6
    assert client.sleeps == [2, 4, 8, 16, 32]  # about a minute of waiting


def test_completion_callback_gives_up_after_the_retry_budget(client):
    calls = _script_session(client, [requests.exceptions.ConnectionError("down")])

    assert client.post_pipeline_run_completed("run-1", status="success") is False

    assert len(calls) == backend_client.CALLBACK_ATTEMPTS
    assert sum(client.sleeps) == 2 + 4 + 8 + 16 + 32 + 64


def test_start_callback_retries_while_the_run_is_not_committed_yet(client):
    calls = _script_session(client, [404, 404, 200])

    assert client.post_pipeline_run_started("run-1", "1", "1") is True

    assert len(calls) == 3


def test_authentication_errors_are_not_retried(client):
    calls = _script_session(client, [401])

    assert client.post_table_analysis("run-1", _table_result()) is False

    assert len(calls) == 1
