# Verify Before Sharing

## Git Hygiene

- [ ] `.env` is not tracked.
- [ ] `storage/`, `logs/`, `data/`, local DB files, `airflow.cfg`, and `*.pid` are not tracked.
- [ ] `git status --short` contains only intentional source/config/doc changes.
- [ ] Deleted legacy dashboard files are expected and README no longer references them.

## Configuration

- [ ] `.env` exists locally and was created from `.env.example`.
- [ ] `PIPELINE_API_TOKEN` matches the Spring Boot backend.
- [ ] `BACKEND_URL` points to the backend reachable from Docker, normally `http://host.docker.internal:8081`.
- [ ] `OLLAMA_URL` points to a running Ollama instance, normally `http://host.docker.internal:11434`.
- [ ] Default secret placeholders were changed before any non-local demo.

## Static Checks

```powershell
python verify_status.py
$files = git ls-files --cached --others --exclude-standard -- '*.py' | Where-Object { Test-Path $_ }
python -m py_compile @files
```

## Docker/Airflow Checks

```powershell
docker compose config
docker compose up --build
docker compose ps
```

Open Airflow at `http://localhost:8080` and confirm the DAG
`data_quality_analysis_pipeline` contains these visible phases:

- `validate_backend_request`
- `notify_backend_started`
- `discover_uploaded_tables`
- `profile_tables_with_sweetviz`
- `extract_metrics_and_run_llm`
- `publish_table_results`
- `build_global_portfolio_analysis`
- `mark_pipeline_completed`

## End-to-End Check

Upload a multi-table CSV dataset from the Angular frontend. Confirm:

- Table metrics appear before every LLM response is finished.
- LLM-enriched table cards replace deterministic-only cards progressively.
- Global analysis appears after table publishing.
- Malformed CSV fallback warnings, if any, are visible in logs/results.
- Final backend status is `success`, `partial`, or `failed`, not silently lost.
