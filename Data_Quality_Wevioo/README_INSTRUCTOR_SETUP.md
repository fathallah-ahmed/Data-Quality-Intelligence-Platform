# Instructor Setup Guide

This folder is the Airflow/Python worker of the full platform (see `README.md`
for how it works, and the root `README.md` for the whole platform).

Expected local services:

- Angular frontend: `http://localhost:4200`
- Spring Boot backend: `http://localhost:8081`
- Airflow webserver: `http://localhost:8080`
- Ollama: `http://localhost:11434` (runs on the host, not in Docker)
- Neo4j (Graph tab): `http://localhost:7474`, Bolt `7687` (optional)
- Question service (Ask tab): `http://localhost:8090` (optional)
- Airflow metadata Postgres: internal to Docker Compose (not published on the host)

## First setup

```powershell
cd <project path>\Data_Quality_Wevioo
Copy-Item .\.env.example .\.env -ErrorAction SilentlyContinue
docker compose up --build
```

Update `.env` before sharing or deploying:

- `AIRFLOW_SECRET_KEY`
- `AIRFLOW_ADMIN_PASSWORD`
- `POSTGRES_PASSWORD`
- `PIPELINE_API_TOKEN` (must match the backend)
- `BACKEND_URL` (the backend as seen from Docker, normally `http://host.docker.internal:8081`)
- `OLLAMA_URL` and `OLLAMA_MODEL` (the model must exist in Ollama: `ollama pull <model>`)

The backend, the question service and Neo4j have their own development defaults
(`QA_API_TOKEN`, `NEO4J_PASSWORD`, `ADMIN_PASSWORD`, `JWT_SECRET`): change them
too before sharing an instance. The root `README.md` lists them.

To start everything at once, use `start-dev.ps1` from the project root
(development, one window per service) or
`docker compose --env-file Data_Quality_Wevioo/.env up --build` from the root
(everything in containers, Ollama stays on the host).

## Airflow DAG

DAG id: `data_quality_analysis_pipeline`

Visible phases:

1. Validate backend request
2. Notify backend started
3. Discover uploaded CSV tables
4. Profile tables (Sweetviz) and write structured metrics
5. Extract metrics, settle column roles, run the per-table LLM, publish table results early and enriched
6. Retry table publishes if needed
7. Suggest quality rules from the user's goal (best effort)
8. Build the global portfolio analysis
9. Mark pipeline completed (always runs last, with the final status)

## Smoke test

Start the backend and frontend, sign in, and upload one or two CSV files with
**New analysis** (optionally fill in the domain, the goal and what one row
represents). The run should go from Queued to Completed, with tables appearing as
they finish, and rules suggested from the goal appearing in the **Rules** tab.
Unit tests: `.\venv\Scripts\python.exe -m pytest`.

## LLM behavior

Per table, the worker posts deterministic metrics first so the frontend can show
partial results immediately. The LLM-enriched result is posted later for the same
table. The model's answer is validated against the measured evidence; an answer
that fails validation is replaced by a deterministic analysis and the table is
marked `invalid`, so the interface says that the figures come from the metrics
only. If Ollama cannot be reached the table is marked `unavailable`.

Globally, the worker sends one compact portfolio prompt after all table results
are ready. If Ollama is unavailable or returns invalid JSON, the backend still
receives a deterministic global fallback marked with `_analysis_source`.

To disable the global LLM call:

```env
GLOBAL_LLM_ENABLED=false
```

To disable the rule suggestions or the model's help with column roles:

```env
RULE_SUGGESTION_ENABLED=false
COLUMN_ROLE_LLM_ENABLED=false
```

## CSV handling

The reader tries strict parsing first across common encodings and separators.
If strict parsing fails, it falls back to a forgiving parser and records a
warning in the metrics payload. This lets the UI show usable results while still
flagging that malformed rows may have been skipped (so the row count can be lower
than the number of lines in the file).
