# Spring Boot backend

REST backend of the Data Quality platform. It receives uploads, versions datasets, triggers Airflow, stores results, evaluates quality rules, corrects tables, builds the graph in Neo4j, forwards questions to the question service, and protects access with an admin role.

Stack: Spring Boot 4.1, Java 17, Spring Data JPA (H2 file by default, MySQL possible), Spring Security with JWT tokens, Neo4j driver (Bolt). No external messaging layer: real-time events go through SSE.

## Role

- Upload of one or more CSV files; detection of an upload that already exists (file names + SHA-256 of the content).
- Creation of datasets and versions; a corrected version is a **derived version** of the one it corrects.
- Triggering of the Airflow DAG `data_quality_analysis_pipeline` and reception of its callbacks (start, tables, suggested rules, global analysis, end).
- `COMPLETED` / `PARTIAL` / `FAILED` statuses, failed tables and their reasons exposed.
- A watchdog that marks stuck runs `FAILED`, and recovery at startup (`RunRecovery`).
- Per-dataset quality rules (manual or suggested), evaluated at the end of each run; quality dimensions with numerator and denominator; comparison of two versions.
- Corrections of a table (found in the data or written in plain language), applied to a copy.
- Graph model deduced from the data and build in Neo4j.
- Plain-language questions, relayed to the `graphrag` service with the version's quality facts.
- Real-time publication through SSE; full deletion of a dataset (files, results, graphs).

## Prerequisites

- Java 17 (`winget install --id EclipseAdoptium.Temurin.17.JDK -e`, then `java -version`). Maven comes with the wrapper (`mvnw`, `mvnw.cmd`).
- Airflow on `http://localhost:8080` to run the pipeline.
- Optional: Neo4j (Graph tab), the question service (Ask tab), Ollama with `OLLAMA_MODEL` (corrections in plain language, graph names). Each feature says clearly when its service is missing.
- Frontend allowed by CORS on `http://localhost:4200`.

## Run locally

From `backend`:

```powershell
.\run-backend.ps1
```

The script reads `..\Data_Quality_Wevioo\.env` and injects `AIRFLOW_USERNAME`, `AIRFLOW_PASSWORD` (= `AIRFLOW_ADMIN_PASSWORD`), `PIPELINE_API_TOKEN` and `OLLAMA_MODEL`, as well as `JAVA_HOME` if the session has not reloaded it, then runs `mvnw.cmd spring-boot:run`. If `JAVA_HOME` is not defined anywhere, set it before running the script.

Equivalent manual command:

```powershell
$airflowEnv = Get-Content ..\Data_Quality_Wevioo\.env | Where-Object { $_ -match '^AIRFLOW_ADMIN_PASSWORD=' } | Select-Object -First 1
$pipelineEnv = Get-Content ..\Data_Quality_Wevioo\.env | Where-Object { $_ -match '^PIPELINE_API_TOKEN=' } | Select-Object -First 1
$env:AIRFLOW_USERNAME='admin'
$env:AIRFLOW_PASSWORD=$airflowEnv.Substring($airflowEnv.IndexOf('=') + 1)
$env:PIPELINE_API_TOKEN=$pipelineEnv.Substring($pipelineEnv.IndexOf('=') + 1)
.\mvnw.cmd spring-boot:run
```

The backend listens on `http://localhost:8081`. Test: `Invoke-RestMethod http://localhost:8081/health` returns `{"status": "ok"}`. Stopping Maven does not always stop the application's JVM: if port 8081 stays busy, stop the backend's Java process.

With Docker, the image is built with `backend/Dockerfile` (Maven build, then a JRE 17) and started from the root `docker-compose.yml`, which provides all the configuration below.

## Configuration

Defaults are in `src/main/resources/application.properties`; each property can be overridden by an environment variable.

| Variable | Default | Description |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | Backend port |
| `DB_URL` | H2 file `./data/dq-platform` | Metadata database |
| `DB_USERNAME` / `DB_PASSWORD` | `sa` / empty | Database access |
| `DB_DRIVER` | `org.h2.Driver` | JDBC driver |
| `JPA_DDL_AUTO` | `update` | Hibernate schema management |
| `MAX_UPLOAD_SIZE` | `500MB` | Maximum upload size |
| `APP_STORAGE_ROOT` | `../Data_Quality_Wevioo/data/uploads` | Where the backend writes the CSVs for Airflow |
| `AIRFLOW_BASE_URL` | `http://localhost:8080` | Airflow REST API |
| `AIRFLOW_USERNAME` / `AIRFLOW_PASSWORD` | `admin` / `admin` | Airflow account; locally, the value from `Data_Quality_Wevioo\.env` |
| `AIRFLOW_DAG_ID` | `data_quality_analysis_pipeline` | DAG to trigger |
| `AIRFLOW_DEBUG` | `false` | Detailed worker logs |
| `AIRFLOW_CONNECT_TIMEOUT_MS` / `AIRFLOW_READ_TIMEOUT_MS` | `5000` / `15000` | Timeouts of the calls to Airflow |
| `PIPELINE_BACKEND_URL` | `http://host.docker.internal:8081` | Backend address as seen from Airflow |
| `PIPELINE_API_TOKEN` | `dev-pipeline-token` | Token shared with Airflow (`X-Pipeline-Token`) |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama (corrections in plain language, graph names) |
| `OLLAMA_MODEL` | empty | Model to use; without it, these features answer 503 |
| `OLLAMA_TIMEOUT_MS` | `120000` | Time allowed for a model answer |
| `NEO4J_URI` | `bolt://localhost:7687` | Neo4j (Graph tab) |
| `NEO4J_USER` / `NEO4J_PASSWORD` | `neo4j` / `dq-neo4j-dev` | Development credentials |
| `NEO4J_BROWSER_URL` | empty | Address of Neo4j Browser for the user; empty = host of `NEO4J_URI`, port 7474. Behind Docker: `http://localhost:7474` |
| `QA_URL` | `http://localhost:8090` | Question service |
| `QA_API_TOKEN` | `dev-qa-token` | Token shared with the question service (`X-QA-Token`) |
| `QA_TIMEOUT_MS` | `180000` | Time allowed for an answer from the question service |
| `WATCHDOG_INTERVAL_MS` | `60000` | How often stuck runs are checked |
| `WATCHDOG_STALE_AFTER_MINUTES` | `10` | Age from which a run is checked with Airflow |
| `WATCHDOG_MAX_RUN_MINUTES` | `180` | Beyond this, a run still in progress becomes `FAILED` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://127.0.0.1:4200` | Allowed origins |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `admin` / `admin123` | Administrator account (development) |
| `JWT_SECRET` | development value | Signing key of the login tokens |
| `JWT_TTL_MINUTES` | `480` | Token lifetime (8 h) |
| `STRICT_SECRETS` | `false` | `true`: refuses to start with the default secrets |

MySQL example:

```powershell
$env:DB_URL='jdbc:mysql://localhost:3306/dq_platform?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC'
$env:DB_USERNAME='root'
$env:DB_PASSWORD='your-password'
$env:DB_DRIVER='com.mysql.cj.jdbc.Driver'
.\mvnw.cmd spring-boot:run
```

At startup `SchemaMaintenance` repairs databases created by older versions (enum columns turned into `VARCHAR`, stale `CHECK` constraints), which `ddl-auto=update` cannot do.

## Security

Token login: `POST /api/auth/login` with `{"username", "password"}` returns a signed JWT (HS256, 8 h). Later calls send `Authorization: Bearer <token>`. Default account (development only): `admin / admin123`.

- All `/api/**` endpoints require `ROLE_ADMIN`, except `POST /api/auth/login` and `/api/pipeline/**`.
- The `/api/pipeline/**` callbacks have no session: they require the `X-Pipeline-Token` header, compared in constant time.
- At startup, `SecretsGuard` logs a **WARN** if a development secret is used (`ADMIN_PASSWORD`, `PIPELINE_API_TOKEN`, `JWT_SECRET`, `AIRFLOW_PASSWORD`); with `STRICT_SECRETS=true` it refuses to start.
- The question service and Neo4j are only reached by the backend. `QA_API_TOKEN` and `NEO4J_PASSWORD` also have development defaults: change them before sharing.

## Endpoints

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/health` | The backend answers (no authentication) |
| `POST` | `/api/auth/login` | Returns a JWT |
| `GET` | `/api/auth/me` | Current user and roles |
| `POST` | `/api/datasets/upload` | CSV upload and Airflow trigger |
| `GET` | `/api/datasets` | Datasets (with `versionCount`, latest version, failed rules, `latestScore` (row-weighted score of the latest version) and `tableCount`) |
| `GET` | `/api/datasets/{id}/versions` | Versions of a dataset |
| `GET` | `/api/datasets/{id}/versions/{versionId}` | Version details: tables, `failedTables`, `qualityScore`, `ruleSummary`, `dimensions` |
| `GET` | `/api/datasets/{id}/versions/{versionId}/tables/{tableVersionId}` | One table with its raw metrics |
| `POST` | `/api/datasets/{id}/versions/{versionId}/rerun` | Reruns a version (409 if it is already running) |
| `GET` | `/api/datasets/{id}/versions/{versionId}/diff?against=` | Differences with the previous version (or `against`) |
| `GET` `POST` | `/api/datasets/{id}/rules` | List / create rules |
| `PUT` `DELETE` | `/api/datasets/{id}/rules/{ruleId}` | Edit (pause included) / delete |
| `POST` | `/api/datasets/{id}/rules/{ruleId}/approve` \| `dismiss` | Approve / dismiss a suggested rule |
| `GET` | `/api/datasets/{id}/versions/{versionId}/rule-results` | Rule results for a version |
| `POST` | `/api/datasets/{id}/versions/{versionId}/rules/evaluate` | Re-evaluates the rules |
| `GET` | `/api/datasets/{id}/versions/{versionId}/corrections/suggestions?table=` | Corrections found in a table |
| `POST` | `/api/datasets/{id}/versions/{versionId}/corrections/interpret` \| `preview` \| `apply` | Reads a written request / counted preview / creates the corrected version |
| `GET` | `/api/datasets/{id}/versions/{versionId}/corrections` | Log of a corrected version |
| `GET` | `/api/datasets/{id}/versions/{versionId}/graph/schema?promote=&refine=` | Proposed graph model |
| `POST` | `/api/datasets/{id}/versions/{versionId}/graph/build` | Builds the graph in Neo4j (`promote`, `renames`) |
| `GET` | `/api/datasets/{id}/versions/{versionId}/graph` \| `graph/status` | Last build / state of Neo4j (`otherGraphs`) |
| `DELETE` | `/api/datasets/{id}/versions/{versionId}/graph` | Removes this version's graph from Neo4j |
| `POST` | `/api/datasets/{id}/versions/{versionId}/ask` | Asks a question (`{"question": "..."}`, at most 1000 characters) |
| `DELETE` | `/api/datasets/{id}` | Deletes dataset, versions, files and graphs (409 during a run) |
| `GET` | `/api/events/stream` | Real-time SSE stream |
| `POST` | `/api/pipeline/{runId}/start` \| `table-analysis` \| `rule-suggestions` \| `global-analysis` \| `complete` | Airflow callbacks (`X-Pipeline-Token`) |

Error codes: 400 invalid request, 401 missing or invalid token, 404 unknown resource or route, 409 conflict, 413 upload too large, 503 external service unavailable (Neo4j, model, question service; the message is meant for the user), 500 internal error (generic message, details in the logs).

SSE events: `PIPELINE_STARTED`, `TABLE_READY`, `GLOBAL_READY`, `PIPELINE_COMPLETED`, `PIPELINE_FAILED`, `RULES_FAILED`, `RULES_SUGGESTED` (and `CONNECTED` when the stream opens).

## Multipart upload

`POST /api/datasets/upload`, parameters:

- `files`: one or more CSVs (`file`: old single-file mode). Only `.csv` is accepted; `dataset_config.csv` is a reserved name; empty files are skipped (`skippedFiles`); the same name twice is refused.
- `name`: dataset name (the first file's name by default).
- `rerunExisting`: `true` to rerun a version whose files are identical.
- `domain`, `objective`, `rowGrain`: the domain, the goal of the analysis and what one row represents. Saved in `dataset_context.json`; they guide the analysis, the column roles and the suggested rules.
- `contextScope` (`none`, `global`, `per_table`, `both`), `globalDescription`, `globalQuestion`, `tableDescriptionsJson`: free description and question, written to `dataset_config.csv` (columns `dataset_name,description,custom_question`) for each table's model.

The backend validates all files and computes their SHA-256 before writing anything, records the version in a transaction, then triggers Airflow once the transaction is committed, so the `/start` callback always finds its run.

## Storage

A version's files are in `<APP_STORAGE_ROOT>/datasets/dataset_<id>/v<n>/`: the CSVs, `dataset_context.json`, `dataset_config.csv` and, depending on the features used, `corrections_log.json` (corrected version) and `graph_build.json` (last graph build). This folder is shared with Airflow (mounted under `/opt/airflow`). The pipeline writes its results to `Data_Quality_Wevioo/storage`. Deleting a dataset removes these folders.

## Code layout (`src/main/java/wevioo/backend`)

| Package | Content |
| --- | --- |
| `config` | properties, security (JWT, CORS), secrets check, schema fix-ups |
| `controller` | one class per resource: auth, datasets, rules and comparison, corrections, graph, questions, events, pipeline callbacks |
| `service` | upload and versions (`DatasetService`), callback ingestion, rules (`RuleService`), quality dimensions, comparison, Airflow client, watchdog, recovery, SSE, graph, corrections |
| `domain`, `repository`, `dto` | JPA entities (dataset, version, run, table, column, rule, result), data access, API objects |
| `correction` | streaming CSV reading (encoding and separator detected), 12 allowed operations, counted preview, application to a copy, date normalisation, Ollama client |
| `graph` | graph model deduced from the data (`GraphModeler`), build (`GraphBuilder`), model-proposed names (`GraphNamer`), Neo4j access (`Neo4jGraphStore`) |
| `qa` | client of the question service and gathering of the facts (`QaService`) |

A few design rules:

- **Corrections never modify the original version.** Only 12 operations exist (spaces, case, replacing values or text, `N/A` markers, default values, dates, digits only, left padding, removing empty or duplicate rows); an ambiguous date (`01/02/2024`) is never guessed; the file's header is kept as is; a row with data beyond the header makes the correction refuse rather than lose it.
- **The graph comes from the data.** Column roles and what the values do decide the entities, their keys and their relationships; the language model can only rename and describe. A key can be a column, a combination of references, or the position of the line in the file; a table of two references becomes a relationship; a column of identifiers of the same table links two rows of that table. Contradicting values are kept (`<property>__candidates`, node `_conflict`) and never settled; references to a missing node are reported; `NULL`, `N/A`, `NaN`… count as "no value", as in the analysis. Every node carries `_scope` (`d<dataset>v<version>`). A build is limited to 500,000 nodes.
- **Questions are relayed, not interpreted here.** `QaService` gathers rules (with their message), dimensions, findings, columns, the graph schema if there is one and the guide `src/main/resources/help/app-guide.md`, then passes the question to the service.
- **The pipeline can be rerun safely.** Callbacks can be replayed (the worker retries them for about two minutes: a table already received is updated, not duplicated); `RunRecovery` reconciles runs in progress with Airflow at startup (restarted if it does not know them, failed if it reports them failed); the watchdog fails runs left without news.

## Tests

```powershell
.\mvnw.cmd test
```

110 tests: API (upload, versions, rules, corrections, graph, questions), security, correction engine, graph model and build, watchdog, schema fix-ups. Neo4j, Airflow, Ollama and the question service are simulated; `surefire` points `NEO4J_URI` at a closed port so tests never touch the graph of your datasets.

## Checks before a push

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
git status --short
```

Do not commit: `target/`, `data/`, secrets or local variables.
