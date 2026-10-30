import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from dq_pipeline import backend_client, storage  # noqa: E402
from dq_pipeline.config.settings import settings  # noqa: E402


class RecordingBackend:
    """Stands in for the Spring Boot callbacks and records what the pipeline sends."""

    def __init__(self):
        self.tables = []
        self.globals = []
        self.completed = []

    def install(self, monkeypatch):
        client = backend_client.BackendClient

        monkeypatch.setattr(client, "health_check", lambda self, debug=False: True)
        monkeypatch.setattr(client, "post_pipeline_run_started", lambda self, *args, **kwargs: True)
        monkeypatch.setattr(client, "post_table_analysis", lambda self, run_id, result, debug=False: self_record(self, "tables", result))
        monkeypatch.setattr(client, "post_global_analysis", lambda self, run_id, result, debug=False: self_record(self, "globals", result))

        def completed(self_, run_id, status="success", error_message=None, failed_tables=None, debug=False):
            self.completed.append({"status": status, "error_message": error_message, "failed_tables": failed_tables or []})
            return True

        monkeypatch.setattr(client, "post_pipeline_run_completed", completed)

        def self_record(_client, kind, result):
            getattr(self, kind).append(result.to_dict())
            return True

        return self


@pytest.fixture
def pipeline_env(tmp_path, monkeypatch):
    """Isolated storage, a recording backend and an unreachable Ollama."""
    monkeypatch.setattr(settings, "STORAGE_BASE_PATH", str(tmp_path / "storage"))
    monkeypatch.setattr(settings, "OLLAMA_URL", "http://127.0.0.1:9")
    monkeypatch.setattr(storage, "_storage_manager", None)
    monkeypatch.setattr(backend_client, "_client", None)
    backend = RecordingBackend().install(monkeypatch)
    return tmp_path, backend
