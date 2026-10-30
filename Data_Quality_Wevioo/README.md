# Data Quality Wevioo - Airflow Worker

This repository contains the Python/Airflow worker for the data-quality platform.
The Spring Boot backend owns uploads, dataset/version records, authentication,
and frontend-facing APIs. Airflow receives a `dag_run.conf`, profiles uploaded
CSV tables, runs LLM enrichment, and streams progressive results back to the
backend.

## Current Workflow

1. `validate_backend_request`
   Validates `dataset_id`, `version_id`, `pipeline_run_id`, and `file_path`.

2. `notify_backend_started`
   Calls the backend pipeline start endpoint.

3. `discover_uploaded_tables`
   Finds all CSV files in the uploaded dataset folder. `dataset_config.csv` is
   treated as context metadata, not as a data table.

4. `profile_tables_with_sweetviz`
   Reads each CSV with flexible encoding/separator detection and produces JSON
   profiling metrics. If strict CSV parsing fails, the fallback parser records a
   warning instead of hiding the issue.

5. `extract_metrics_and_run_llm`
   Calculates deterministic row, column, missingness, duplicate, and relationship
   metrics. A first table result is posted before LLM enrichment, then a second
   enriched result is posted when the table LLM response is ready.

6. `publish_table_results`
   Retries backend publishing for completed table results that were not already
   accepted.

7. `build_global_portfolio_analysis`
   Builds one compact global payload from table results, runs one global LLM call
   when enabled, and falls back to deterministic global analysis if the LLM fails.

8. `mark_pipeline_completed`
   Posts final `success`, `partial`, or `failed` status to the backend, with the
   list of failed tables (`failed_tables`) and a readable error message.

Each table result carries `llm_status`: `ok`, `unavailable` (Ollama could not be
reached), `invalid` (answer rejected by validation), `disabled` or `pending`.
A header-only CSV is published with 0 rows and reported as a failed table.

## Run Locally

```powershell
cd <project path>
Copy-Item .\.env.example .\.env -ErrorAction SilentlyContinue
# edit .env secrets once if needed
docker compose up --build
```

Airflow UI: `http://localhost:8080`

Airflow login:

```text
username: admin
password: value of AIRFLOW_ADMIN_PASSWORD in .env
```

The backend triggers the DAG through Airflow's REST API when a CSV is uploaded
from the frontend; that is also the easiest end-to-end test.

## Required DAG Conf

```json
{
  "dataset_id": "67",
  "version_id": "1",
  "pipeline_run_id": "run-uuid",
  "file_path": "/opt/airflow/data/uploads/datasets/dataset_67/v1",
  "backend_url": "http://host.docker.internal:8081",
  "debug": false
}
```

## Dataset Context

Optional file beside uploaded CSVs:

```csv
dataset_name,description,custom_question
__global__,Global business description,Question about the full base
customers,Description for this table,Question for this table
```

The global row applies to portfolio analysis. A row matching a table name applies
only to that table.

## Verification

```powershell
.\venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\venv\Scripts\python.exe -m pytest
```

## Notes

- The worker does not generate SweetViz HTML for the Angular app. The frontend
  renders dynamic pages from backend JSON results.
- Change detection uses a `content_hash` of the table values and a `schema_hash`
  of column names/types; counts are kept for older versions.
- `requirements.txt` is pinned to the Airflow 2.10.0 / Python 3.12 constraints,
  and the Dockerfile installs it with that constraints file.
- `GLOBAL_LLM_ENABLED=false` disables the extra global LLM call while keeping the
  deterministic global analysis.
