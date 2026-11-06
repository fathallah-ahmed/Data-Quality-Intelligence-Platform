# Verify Before Sharing

## Git Hygiene

- [ ] `.env` is not tracked (it holds secrets; only `.env.example` is).
- [ ] `storage/`, `logs/`, `data/`, `venv/`, local DB files, `airflow.cfg`, and `*.pid` are not tracked.
- [ ] `git status --short` contains only intentional source/config/doc changes.
- [ ] No local virtual environment (`venv/`, `graphrag/.venv/`) or build output (`target/`, `dist/`) is staged.

## Configuration

- [ ] `.env` exists locally and was created from `.env.example`.
- [ ] `PIPELINE_API_TOKEN` matches the Spring Boot backend.
- [ ] `BACKEND_URL` points to the backend reachable from Docker, normally `http://host.docker.internal:8081`.
- [ ] `OLLAMA_URL` points to a running Ollama instance, normally `http://host.docker.internal:11434`, and `OLLAMA_MODEL` is a model that exists there.
- [ ] Default secret placeholders were changed before any non-local demo: `AIRFLOW_SECRET_KEY`, `AIRFLOW_ADMIN_PASSWORD`, `POSTGRES_PASSWORD`, `PIPELINE_API_TOKEN`, and on the backend side `ADMIN_PASSWORD`, `JWT_SECRET`, `QA_API_TOKEN`, `NEO4J_PASSWORD` (the backend logs a warning for each development default it still uses, and refuses to start with `STRICT_SECRETS=true`).

## Static Checks

```powershell
.\venv\Scripts\python.exe -m pytest                  # worker: 67 tests
cd ..\backend; .\mvnw.cmd test                        # backend: 110 tests
cd ..\frontend; npm test                              # frontend: 69 tests
cd ..\graphrag; .\.venv\Scripts\python.exe -m pytest  # question service: 39 tests
```

## Docker/Airflow Checks

```powershell
docker compose config
docker compose up --build
docker compose ps
```

Open Airflow at `http://localhost:8080` and confirm the DAG
`data_quality_analysis_pipeline` contains these visible phases, in this order:

- `validate_backend_request`
- `notify_backend_started`
- `discover_uploaded_tables`
- `profile_tables_with_sweetviz`
- `extract_metrics_and_run_llm`
- `publish_table_results`
- `suggest_quality_rules`
- `build_global_portfolio_analysis`
- `mark_pipeline_completed`

For the whole platform, from the project root:

```powershell
docker compose --env-file Data_Quality_Wevioo/.env up --build
```

and check that the backend, frontend, Airflow, Neo4j and the question service
(`http://localhost:8090/health`) are all up.

## End-to-End Check

Upload a multi-table CSV dataset from the Angular frontend, with a goal in the
upload form. Confirm:

- Table metrics appear before every LLM response is finished.
- LLM-enriched table cards replace deterministic-only cards progressively.
- Each column of the Tables tab shows a role and why.
- Global analysis appears after table publishing.
- Rules suggested from the goal appear in the Rules tab, waiting for approval.
- Malformed CSV fallback warnings, if any, are visible in logs/results.
- Final status is `Completed`, `Partial` (failed tables listed with their reason) or `Failed`, never stuck in Running.
- The Graph tab builds a graph (with Neo4j running) and the Ask tab answers a question about quality and one about the data.
