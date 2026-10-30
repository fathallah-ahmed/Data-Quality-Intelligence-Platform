# Instructor Setup Guide

This folder is the Airflow/Python worker of the full platform.

Expected local services:

- Angular frontend: `http://localhost:4200`
- Spring Boot backend: `http://localhost:8081`
- Airflow webserver: `http://localhost:8080`
- Ollama: `http://localhost:11434`
- Airflow metadata Postgres: internal to Docker Compose (not published on the host)

## First Setup

```powershell
cd <project path>\Data_Quality_Wevioo
Copy-Item .\.env.example .\.env -ErrorAction SilentlyContinue
docker compose up --build
```

Update `.env` before sharing or deploying:

- `AIRFLOW_SECRET_KEY`
- `AIRFLOW_ADMIN_PASSWORD`
- `POSTGRES_PASSWORD`
- `PIPELINE_API_TOKEN`
- `BACKEND_URL`
- `OLLAMA_URL`

The `PIPELINE_API_TOKEN` must match the backend configuration.

## Airflow DAG

DAG id: `data_quality_analysis_pipeline`

Visible phases:

1. Validate backend request
2. Notify backend started
3. Discover uploaded CSV tables
4. Profile tables and extract structured metrics
5. Publish table metrics early, run per-table LLM, publish enriched results
6. Retry table publishes if needed
7. Build global portfolio analysis
8. Mark pipeline completed

## Smoke Test

Start the backend and frontend, sign in, and upload one or two CSV files with
**New analysis**. The run should go from Queued to Completed, with tables
appearing as they finish. Unit tests: `.\venv\Scripts\python.exe -m pytest`.

## LLM Behavior

Per table, the worker posts deterministic metrics first so the frontend can show
partial results immediately. The LLM-enriched result is posted later for the same
table.

Globally, the worker sends one compact portfolio prompt after all table results
are ready. If Ollama is unavailable or returns invalid JSON, the backend still
receives a deterministic global fallback marked with `_analysis_source`.

To disable the global LLM call:

```env
GLOBAL_LLM_ENABLED=false
```

## CSV Handling

The reader tries strict parsing first across common encodings and separators.
If strict parsing fails, it falls back to a forgiving parser and records a
warning in the metrics payload. This lets the UI show usable results while still
flagging that malformed rows may have been skipped.
