# Wevioo Data Quality Platform

A data-quality analysis platform for CSV databases. You drop files and get, for each table, a profile, explained scores, quality rules, corrections you can apply to a copy, a graph of its entities in Neo4j, and an assistant that answers questions about the data, its quality and how to use the tool.

| Block | Folder | Role |
| --- | --- | --- |
| **frontend** (Angular) | `frontend` | upload, real-time run tracking, results in tabs |
| **backend** (Spring Boot) | `backend` | datasets and versions, security, rules, corrections, graph, questions, triggering and watching Airflow, SSE |
| **worker** (Python / Airflow) | `Data_Quality_Wevioo` | table profiling, column roles, metrics, relationships between tables, language-model analysis (Ollama), suggested rules |
| **question service** (Python) | `graphrag` | answers the Ask tab's questions, with guardrails on the generated Cypher |
| **Neo4j** | `docker-compose.neo4j.yml` | graph database built by the Graph tab |

Each folder has its own `README.md` (start-up, configuration, code layout, tests).

## One-command start

Development on Windows: one window per service (Ollama, Airflow, question service, backend, frontend); Neo4j starts in the background in Docker. The browser opens when everything answers.

```powershell
.\start-dev.ps1
```

Everything in Docker (Airflow, Neo4j, question service, backend, frontend; Ollama stays on the host):

```powershell
docker compose --env-file Data_Quality_Wevioo/.env up --build
```

Application: `http://localhost:4200` (public landing page; "Sign in" leads to the dashboard of your datasets) · API: `http://localhost:8081` · Airflow: `http://localhost:8080` · Neo4j Browser: `http://localhost:7474` · Questions: `http://localhost:8090`. The file `Data_Quality_Wevioo\.env` must exist (copy `.env.example`): it holds the secrets and the Ollama model (`OLLAMA_MODEL`), hence the `--env-file` option.

The root `docker-compose.yml` includes the worker's and Neo4j's compose files: all services share one network and the same secrets, and the backend reaches Airflow, Neo4j and the question service by their service names. The development ports (8081, 4200, 8090, 7687) are the same as Docker's: stop one before starting the other.

| Container | Role | Port |
| --- | --- | --- |
| `frontend` | Angular interface served by nginx | 4200 |
| `backend` | Spring Boot API, H2 database (volume `backend_data`) | 8081 |
| `airflow-webserver` | Airflow web interface | 8080 |
| `airflow-scheduler` | runs the pipeline steps | internal |
| `airflow-init` | migrates Airflow's database and creates the admin user, then exits | none |
| `postgres` | Airflow's database (not the application's) | internal |
| `neo4j` | graph database (volume `neo4j_data`) | 7474, 7687 |
| `graphrag` | question service | 8090 |

Containers are named after the Compose project set by `COMPOSE_PROJECT_NAME` in `.env` (`data-quality-pipeline-*` by default). To go back to development mode: `docker compose --env-file Data_Quality_Wevioo/.env down`, then `.\start-dev.ps1`. The Neo4j graphs of Docker and of development mode live in separate volumes.

## Manual start

Without the script: one PowerShell terminal per service, from the project root, in this order.

### 1. Ollama (optional)

```powershell
ollama serve
ollama pull qwen2.5:14b-instruct      # or any other model, to be set in OLLAMA_MODEL
```

Without Ollama the pipeline still works: insights are computed from the metrics and the interface says so ("Deterministic only – Ollama unavailable"). Corrections written in plain language, the AI-proposed graph names and the Ask tab need a model. If `bind: Only one usage of each socket address` appears, Ollama is already running.

### 2. Airflow

```powershell
cd .\Data_Quality_Wevioo
Copy-Item .\.env.example .\.env -ErrorAction SilentlyContinue
docker compose up --build
```

- Airflow UI: `http://localhost:8080`, login `admin` and the value of `AIRFLOW_ADMIN_PASSWORD` in `Data_Quality_Wevioo\.env`.
- `PIPELINE_API_TOKEN` in `.env` must be identical on the backend side (the backend start script reads it automatically).
- `OLLAMA_MODEL` in `.env` is also read by the backend script.

### 3. Neo4j (Graph tab)

```powershell
docker compose -f docker-compose.neo4j.yml up -d
```

Neo4j Browser: `http://localhost:7474`, user `neo4j`, development password `dq-neo4j-dev` (change it with `NEO4J_PASSWORD` before sharing).

### 4. Question service (Ask tab)

```powershell
cd .\graphrag
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
$env:OLLAMA_MODEL = 'qwen2.5:14b-instruct'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8090
```

### 5. Backend

```powershell
cd .\backend
.\run-backend.ps1
```

- API: `http://localhost:8081`; test: `Invoke-RestMethod http://localhost:8081/health`.
- Java 17 required: `winget install --id EclipseAdoptium.Temurin.17.JDK -e`.

### 6. Frontend

```powershell
cd .\frontend
npm install
npm start
```

Open `http://localhost:4200` and sign in with `admin / admin123` (development values, see *Security*).

## Architecture

```text
Browser -> Angular (4200) -> Spring Boot (8081, /api)
Spring Boot -> Airflow REST (8080): triggers the DAG once the version is saved
Airflow -> Spring Boot /api/pipeline/{runId}/...: start, table-analysis, rule-suggestions, global-analysis, complete
Spring Boot -> Angular: SSE /api/events/stream (automatic fallback to polling)
Spring Boot -> Neo4j (7687): builds and deletes the graph of a version
Spring Boot -> question service (8090): question + quality facts; the service reads Neo4j
Airflow, Spring Boot, question service -> Ollama (11434): local language model
```

| Item | Where | Detail |
| --- | --- | --- |
| Backend database | `backend/data` | H2 file (MySQL possible): datasets, versions, rules, results |
| Uploaded files | `Data_Quality_Wevioo/data/uploads` | `datasets/dataset_<id>/v<n>/`: CSVs, context, corrections, graph |
| Pipeline results | `Data_Quality_Wevioo/storage` | metrics and analysis JSON, run manifests |
| Graphs | Neo4j | one graph per version, identified by the `_scope` property (`d<dataset>v<version>`) |

## How it works

1. The admin signs in (`POST /api/auth/login`, JWT valid for 8 h, kept in `sessionStorage`).
2. They drop one or more CSVs in the **New analysis** window (one table per file), optionally with the domain, the goal and what one row represents.
3. The backend validates all files before writing anything, computes a SHA-256 per file, creates the version, stores the files, then triggers Airflow once the transaction is committed.
4. Airflow runs the visible phases: validation, notification, table discovery, profiling, metrics and model analysis, publishing, rule suggestion, global analysis, completion.
5. Each table appears as soon as its metrics are ready, then is enriched by the model.
6. The final status is `COMPLETED`, `PARTIAL` (at least one table failed, with the reason) or `FAILED`. A **Retry** button reruns a failed or partial version.
7. The dataset's quality rules are evaluated on the finished version; a failure raises an alert (live toast, badge in the dataset list, banner in the overview).

### Tabs of a version

| Tab | Content |
| --- | --- |
| Overview | Run status, score and A–D grade, quality dimensions with how they are computed, health of each table, columns to review, global insights |
| Tables | Profile of one table: columns and their role (with the clues), frequent values, missing values, associations, insights |
| Data model | Diagram of the tables and relationships (value overlap) |
| Insights | Semantic analysis (column roles, data contract, monitoring, causes, impacts) |
| Compare | Differences with an older version: score, rows, added/removed columns, type changes, missing values |
| Rules | The dataset's quality rules and their result for this version; rules proposed from the goal given at upload (to approve) |
| Corrections | Corrections found in the data or written in plain language, counted preview, then applied to a copy (new version) |
| Graph | Proposed graph model (entities, properties, relationships), conflicts, build and delete in Neo4j |
| Ask | Plain-language questions: about the data (through the graph), about quality (rules, scores, findings) and about how to use the application; every answer says where it comes from |

The **Report** button opens a printable page of the version (Print / Save as PDF, or Markdown download), with the definition of every score.

Shortcuts: `Ctrl/⌘ K` or `/` (command palette), `N` (new analysis), `1`–`9` (switch tab).

### Analysis context

At upload you can give a **domain**, a **goal** and what **one row represents** (a customer, an account, a transaction). They are saved in `dataset_context.json` next to the CSVs, kept on Retry, and read by the pipeline to guide the analysis (a repeated identifier is normal if a row is not the entity it names) and to propose rules. A free context, global or per table, can also be given (`dataset_config.csv`, a reserved name).

### Column roles and quality dimensions

Each column gets a role (identifier, reference, email, date, amount…) computed from its name, its values and their formats; the clues are kept. Only the columns that stay unclear are settled by the model. The overview shows completeness, uniqueness, validity and consistency with their numerator and denominator, or "not measured" with the reason.

### Quality rules

Types: column with no missing values, at most X% missing values, unique values, at least N rows, at most X% duplicates, minimum score, maximum drop in row count compared with the previous version, **values in the right format** (email, phone, URL, date, number) and **order of two dates**. Each rule is `WARNING` or `CRITICAL` (a failed rule raises the alert either way; `CRITICAL` failures are counted separately), can be paused, and targets a table (and a column). They are evaluated at the end of each run and on demand (**Check now**). The pipeline proposes rules from the goal: they stay **suggestions** (unverified) until approved, and a dismissed suggestion does not come back.

### Corrections

A table can be corrected from what the application finds (spaces, `N/A` markers, dates in several formats, one value written several ways, duplicates) or from a request written in plain language, read by the local model (`OLLAMA_MODEL` must be set for the backend). The model only chooses among 12 operations checked against the columns. The preview gives the number of values touched, examples and warnings; an ambiguous date (`01/02/2024`) is never guessed. The application creates a **new version** (analysed like an upload) with a log; the original version is never modified. Corrected files are written as UTF-8 with commas, header unchanged.

### Graph (Neo4j)

The model is deduced from the roles and the data: a row of accounts is an *Account*, and the *Customer* it references becomes a node of its own whose columns that are constant for a customer are its properties. Relationships come from the links found by the analysis. Some common cases are handled without a declared identifier:

- a table with no identifier column whose rows are told apart by a **combination of references** (an order and a line number) is identified by that combination;
- a table with a column that holds the **line numbers** of another file that has no identifier is linked to those lines;
- a **table of two references** (friends, product-store) becomes a relationship, without a node of its own;
- a column of identifiers of the **same table** (a `manager_id`) links two rows of that table.

When the rows of one key contradict each other (two emails for one customer), the graph keeps **all** the values (`email__candidates`), marks the node `_conflict` and chooses none; references to a missing node, tables tied to nothing and uncertain links are reported. `NULL`, `N/A`, `NaN`… count as "no value", as in the analysis.

Every node carries `_scope` (`d<dataset>v<version>`): versions do not mix. In Neo4j Browser, keep the `_scope` filter of the query the tab proposes, otherwise the graphs of several versions are shown mixed together. The **Delete this graph** button removes a version's graph, and the tab says when Neo4j holds others. Behind Docker, `NEO4J_BROWSER_URL` (set to `http://localhost:7474` in `docker-compose.yml`) gives the address the user's browser must open.

### Questions (Ask tab)

The `graphrag/` service starts by **sorting the question**: about the content of the data (*graph*), about quality (*quality*: rules and why they fail, scores, findings) or about how to use the application (*help*). Only the first uses Neo4j; the other two answer even before the graph is built. For a *graph* question, the local model writes a Cypher query that goes through guardrails (limited to the version's `_scope`, read-only, `EXPLAIN` before running, time limit, two attempts). The answer is written **only** from the rows obtained, the facts the backend attaches to the question (rules and their message, dimensions, findings, column roles) and the usage guide (`backend/src/main/resources/help/app-guide.md`, to keep up to date when the interface changes). The query and its rows are returned with the answer so it can be checked. Details, API and evaluation: `graphrag/README.md`.

### Recovery after a restart

- At startup, the backend asks Airflow about every `PENDING`/`RUNNING` run: a run unknown to Airflow is restarted (if its files are still there), a run that failed in Airflow is marked `FAILED`, a run in progress is left alone.
- The worker's callbacks (tables, global analysis, end of run) are retried for about two minutes: a backend restart does not interrupt a run in progress.
- The watchdog keeps watching stuck runs.

### Statuses

| Object | Values |
| --- | --- |
| Run / version | `PENDING` (queued), `RUNNING`, `COMPLETED`, `PARTIAL`, `FAILED` |
| Table compared with the previous version | `NEW`, `MODIFIED`, `UNCHANGED`, `REMOVED` |
| Model status (`llmStatus`) | `ok`, `unavailable` (Ollama unreachable), `invalid` (answer rejected by validation), `disabled`, `pending` |
| Rule | state `ACTIVE`, `SUGGESTED`, `DISMISSED`; result `PASS`, `FAIL`, `SKIPPED` |

- **Duplicates**: an identical upload (same file names and same SHA-256 content) offers to open the existing version or to rerun it. A modified file of the same size does create a new version.
- **Table changes**: compared by content fingerprint (`content_hash`) and schema; a modified value gives `MODIFIED`. Tables missing from the new version are listed `REMOVED`.
- **Quality score**: per table, 100 − average missing-value % − duplicated-row %; for the version, average weighted by row count. Grades: A from 90, B from 75, C from 60, D below.
- **Watchdog**: every minute, runs `PENDING`/`RUNNING` for more than 10 min are checked with Airflow; a run unknown to or failed in Airflow becomes `FAILED` with a clear message, and so does a run that has not ended after 180 min.

## API

All `/api/**` endpoints (except login and the pipeline callbacks) require `Authorization: Bearer <token>` and the `ADMIN` role. The full list is in `backend/README.md`; in short:

| Resource | Endpoints |
| --- | --- |
| Health and session | `GET /health`, `POST /api/auth/login`, `GET /api/auth/me` |
| Datasets and versions | `POST /api/datasets/upload`, `GET /api/datasets`, `GET .../{id}/versions[/{versionId}[/tables/{tableVersionId}]]`, `POST .../rerun`, `GET .../diff`, `DELETE /api/datasets/{id}` |
| Rules | `GET` `POST` `PUT` `DELETE /api/datasets/{id}/rules[/{ruleId}]`, `.../approve`, `.../dismiss`, `GET .../rule-results`, `POST .../rules/evaluate` |
| Corrections | `GET .../corrections/suggestions`, `POST .../corrections/interpret` `preview` `apply`, `GET .../corrections` |
| Graph | `GET .../graph/schema`, `POST .../graph/build`, `GET .../graph`, `GET .../graph/status`, `DELETE .../graph` |
| Questions | `POST .../versions/{versionId}/ask` |
| Real time | `GET /api/events/stream` (SSE) |
| Airflow callbacks | `POST /api/pipeline/{runId}/start` `table-analysis` `rule-suggestions` `global-analysis` `complete` (`X-Pipeline-Token`) |

Error codes: 400 invalid request, 401 missing or invalid token (pipeline token included), 404 unknown resource or route, 409 conflict, 413 upload too large, 503 external service unavailable (Neo4j, model, question service), 500 internal error (generic message, details in the logs).

## Security

- The defaults (`admin123`, `dev-pipeline-token`, the development `JWT_SECRET`, Airflow password `admin`) are for development only: the backend logs a **WARN** at startup if it uses them, and `STRICT_SECRETS=true` refuses to start with them.
- Variables to set before any sharing: `ADMIN_PASSWORD`, `JWT_SECRET`, `PIPELINE_API_TOKEN`, `AIRFLOW_PASSWORD` (backend), `NEO4J_PASSWORD`, `QA_API_TOKEN` and the secrets of `Data_Quality_Wevioo\.env`.
- The question service is only called by the backend (`X-QA-Token` token); the browser never reaches it. The Cypher queries it generates can only read, and only the graph of the version being asked about.
- Airflow's PostgreSQL port is not published on the host.
- Never commit `Data_Quality_Wevioo\.env`.

## Continuous integration

`.github/workflows/ci.yml` runs, on every push and pull request: the backend tests (`./mvnw test`), the frontend build and tests, the pipeline's Python tests (with Airflow 2.10.0 / Python 3.12 constraints), the question service's tests and the validation of `docker-compose.yml`.

## Tests

```powershell
cd .\backend; .\mvnw.cmd test                                                   # 110 tests
cd .\frontend; npm run build; npm test                                         # 69 tests
cd .\Data_Quality_Wevioo; .\venv\Scripts\python.exe -m pip install -r requirements-dev.txt; .\venv\Scripts\python.exe -m pytest   # 67 tests
cd .\graphrag; .\.venv\Scripts\python.exe -m pytest                            # 39 tests
```

The tests need neither Airflow, nor Neo4j, nor Ollama (they are simulated). To measure the quality of the Ask tab's answers on real datasets, `graphrag/eval/run_eval.py` replays about twenty questions against the running services (see `graphrag/README.md`).

## Git

The root `.gitignore` excludes `.env`, `node_modules`, `dist`, `.angular`, `target`, `backend/data`, `Data_Quality_Wevioo/data`, `storage`, `logs`, `airflow.cfg`, `*.pid`, `*.db`, `graphrag/.venv`. Check `git status --short` before a push.

Detailed READMEs: `frontend\README.md`, `backend\README.md`, `Data_Quality_Wevioo\README.md` (with `README_INSTRUCTOR_SETUP.md` and `VERIFY_BEFORE_SHARING.md`), `graphrag\README.md`.

## Troubleshooting

**`JAVA_HOME` not set** — install JDK 17, reopen PowerShell, then if needed:

```powershell
$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory | Where-Object { $_.Name -like 'jdk-17*' } | Select-Object -First 1 -ExpandProperty FullName
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
[Environment]::SetEnvironmentVariable('Path', "$jdk\bin;" + [Environment]::GetEnvironmentVariable('Path', 'User'), 'User')
```

**Port 8081 stays busy after stopping Maven** — stopping Maven does not always stop the backend's JVM: stop the Java process that runs `wevioo.backend.BackendApplication`.

**Airflow: invalid login** — the password comes from `AIRFLOW_ADMIN_PASSWORD`. If the Postgres volume keeps an old password:

```powershell
cd .\Data_Quality_Wevioo
$line = Get-Content .\.env | Where-Object { $_ -match '^AIRFLOW_ADMIN_PASSWORD=' } | Select-Object -First 1
docker compose exec airflow-webserver airflow users reset-password -u admin -p $line.Substring($line.IndexOf('=') + 1)
```

**Run failing right at upload ("Could not start the analysis in Airflow")** — Airflow is unreachable or refuses the credentials (403). Restart the backend with `.\run-backend.ps1` (it reads the Airflow password in `.env`), then if needed `docker compose exec airflow-webserver airflow sync-perm`. Once Airflow is available, click **Retry**.

**Run stuck on "Queued"** — after 2 minutes the interface shows a link to Airflow. Check that the DAG is not paused:

```powershell
docker compose exec airflow-webserver airflow dags unpause data_quality_analysis_pipeline
```

**Graph tab: "Neo4j is not running"** — run `docker compose -f docker-compose.neo4j.yml up -d` (or `start-dev.ps1`). The message gives the address that was tried; behind Docker it is `bolt://neo4j:7687`.

**Ask tab: "service unreachable", 503 or token error** — the question service must run on port 8090; it needs `OLLAMA_MODEL` to answer; `QA_API_TOKEN` must be identical on the backend and the service. A question about the data without a built graph answers that the graph must be built in the Graph tab; questions about quality and usage do not need it.

**Neo4j Browser shows "duplicate" customers or nodes** — several versions (or several datasets) have a graph in the same database and the query has no `_scope` filter. Reuse the query from the Graph tab, or delete the graphs you no longer need.

**`npm ci` fails on Linux (Docker or CI) with "Missing: @emnapi/core"** — `package-lock.json` was regenerated on Windows without the optional dependencies of other platforms. Regenerate it in a Linux container:

```powershell
docker run --rm -v "${PWD}/frontend:/app" -w /app node:22-alpine sh -c "npm install --package-lock-only"
```
