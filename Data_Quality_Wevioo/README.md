# Data Quality Wevioo - Airflow Worker

This folder contains the Python/Airflow worker of the data-quality platform.
The Spring Boot backend owns uploads, dataset/version records, authentication,
rules, corrections, the graph and the frontend-facing APIs. Airflow receives a
`dag_run.conf`, profiles the uploaded CSV tables, works out what each column is
for, runs LLM enrichment, proposes quality rules from the user's goal, and
streams progressive results back to the backend.

The worker never decides alone what is published: every payload goes to the
backend through token-protected callbacks, and the LLM's answers are validated
against measured evidence before they are shown.

## The DAG

DAG id: `data_quality_analysis_pipeline` (no schedule: it only runs when the
backend triggers it through Airflow's REST API; `retries=1`, retried after 5
minutes). The phases are a straight line, one visible task each:

| # | Task id | Phase function (`dq_pipeline/runners.py`) | What it does |
| --- | --- | --- | --- |
| 1 | `validate_backend_request` | - | Validates `dataset_id`, `version_id`, `pipeline_run_id` and `file_path` from `dag_run.conf`. |
| 2 | `notify_backend_started` | `phase_start_pipeline` | Health-checks the backend and calls `/start`. |
| 3 | `discover_uploaded_tables` | `phase_discover_tables` | Finds the CSV files of the version folder. `dataset_config.csv` is context, not a table. |
| 4 | `profile_tables_with_sweetviz` | `phase_profile_tables` | Reads each CSV with encoding/separator detection and writes Sweetviz profiling JSON. A header-only CSV is kept with 0 rows and reported as a failed table. |
| 5 | `extract_metrics_and_run_llm` | `phase_analyze_tables` | Per table: deterministic metrics, column roles, format checks, cross-column checks, change signature; posts a first result; asks the model to settle unclear column roles and to analyse the table; posts the enriched result. |
| 6 | `publish_table_results` | `phase_publish_table_results` | Retries the publish of results the backend did not accept. |
| 7 | `suggest_quality_rules` | `phase_suggest_rules` | Proposes quality rules from the user's goal (best effort: never fails the run) and posts them to `/rule-suggestions`. |
| 8 | `build_global_portfolio_analysis` | `phase_global_analysis` | Builds one compact payload from the table results, runs one global LLM call when enabled, falls back to a deterministic analysis. |
| 9 | `mark_pipeline_completed` | `phase_complete_pipeline` | Always runs last (`ALL_DONE`): posts the final status `success`, `partial` or `failed`, the failed tables and a readable message. |

Phases hand work to each other through small JSON manifests in the run folder
(`tables_manifest.json`, `profile_manifest.json`, `analysis_manifest.json`,
`publish_manifest.json`), so a phase can be retried on its own.
Tables are processed one after the other inside each phase.

The completion status is `success` when every table was published, `partial`
when some failed, `failed` when none made it (or when a phase failed and the
last task says so).

## What is measured

- **Table and column metrics**: rows, columns, missing values, distinct values,
  duplicate rows, quartiles, mean and standard deviation for numbers.
- **Column roles** (`analyzers/column_roles.py`): `IDENTIFIER`, `REFERENCE`,
  `EMAIL`, `PHONE`, `URL`, `DATE`, `AMOUNT`, `QUANTITY`, `CATEGORY`, `STATUS`,
  `BOOLEAN`, `NAME`, `LOCATION`, `POSTAL_CODE`, `FREE_TEXT`, `UNKNOWN`. A role is
  scored from the words of the column name, the statistics of its values and the
  formats they follow; the clues are kept (`role_evidence`) so the role can be
  explained. Only the columns that stay unclear are put to the model, with the
  user's goal and what one row represents (`role_source` says who decided).
- **Format checks** (`extractors/format_profile.py`): how many values of a text
  column are an email, a phone number, a URL, a date or a number, with examples
  of invalid ones. The backend judges them against the rules the user approved
  without reading the files again.
- **Cross-column checks**: for each pair of date columns, on rows where both are
  filled, how often the first comes after the second (`cross_checks.date_order`).
- **Relationships between tables**: value overlap between columns of different
  tables. A column is a source when it is mostly filled and not the table's own
  identifier; a target is a column that is (almost) unique. A relationship needs
  at least 85% of the source values to exist in the target; ambiguous matches
  without a name clue are dropped. Results carry `confidence`, `coverage` and
  counts.
- **Change signature**: `content_hash` (values) and `schema_hash` (names and
  types), so the backend can tell NEW, MODIFIED, UNCHANGED and REMOVED tables.
- **Table LLM analysis** (`analyzers/langgraph_analyzer.py`): a controlled
  generate, validate, repair, fallback workflow. The answer is checked against
  the measured evidence (a percentage attributed to the wrong column is
  rejected); if it stays unreliable, a deterministic analysis is used.
  `llm_status` is `ok`, `unavailable` (Ollama could not be reached), `invalid`
  (answer rejected by validation), `disabled` or `pending`.
- **Global analysis** (`analyzers/llm_global_analyzer.py`): one portfolio-level
  interpretation; `GLOBAL_LLM_ENABLED=false` keeps only the deterministic one.
- **Rule suggestions** (`analyzers/rule_suggester.py`): the model proposes rules
  for the goal ("contact customers" gives an email that is filled and well
  formed); the code keeps only rules that can really be checked on this data
  (real tables and columns, known rule types, sensible thresholds). Without a
  goal, or without a model, a small set of rules is derived from the measurements.

The quality score of a table (100 minus the average missing percentage minus the
duplicate percentage) is computed by the backend from these metrics.

## Context given by the user

The backend writes two optional files beside the uploaded CSVs:

- `dataset_context.json`: `{"domain", "objective", "row_grain"}`, read by the
  pipeline to guide roles, the LLM analysis and the rule suggestions. A repeated
  identifier is only a problem when it contradicts what one row represents.
- `dataset_config.csv` (a reserved file name, never a table):

```csv
dataset_name,description,custom_question
__global__,Global business description,Question about the full base
customers,Description for this table,Question for this table
```

The `__global__` row applies to the whole version; a row named like a table
applies only to that table.

## Required DAG conf

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

The backend triggers the DAG with `dag_run_id` equal to its own
`pipeline_run_id`, so a run in Airflow and a version in the backend always
match. `file_path` is the version folder as the Airflow containers see it (this
project folder is mounted as `/opt/airflow`).

## Callbacks to the backend

All use the header `X-Pipeline-Token` (`PIPELINE_API_TOKEN`) and are retried for
about two minutes (2 + 4 + ... + 64 seconds), so a backend restart does not
interrupt a run:

`POST /api/pipeline/{run}/start`, `/table-analysis` (sent twice per table: first
the metrics, then the LLM-enriched result), `/rule-suggestions`,
`/global-analysis`, `/complete`.

## Settings

Environment variables read by `dq_pipeline/config/settings.py`. A real
environment variable always wins; a `.env` file found in the working directory
fills in the ones that are not set (`os.environ.setdefault`, handy for CLI use).
Docker Compose reads `.env` too and passes the main variables explicitly (URLs,
token, model, storage path and the `GLOBAL_LLM_*` ones, with the defaults shown
in `docker-compose.yml`, for example `GLOBAL_LLM_NUM_PREDICT=1800`); the others
use the defaults below unless the process runs from this folder, where the
mounted `.env` is found:

| Variable | Default | Role |
| --- | --- | --- |
| `BACKEND_URL` | - | Backend as seen from Airflow (normally `http://host.docker.internal:8081`) |
| `PIPELINE_API_TOKEN` | - | Shared secret with the backend (required) |
| `OLLAMA_URL` | - | Ollama, normally `http://host.docker.internal:11434` |
| `OLLAMA_MODEL` | `qwen2.5:14b-instruct` | Any model pulled in Ollama (`ollama pull <model>`) |
| `TABLE_LLM_ENABLED` | `true` | Per-table LLM analysis |
| `GLOBAL_LLM_ENABLED` | `true` | The one global LLM call |
| `COLUMN_ROLE_LLM_ENABLED` | `true` | Let the model settle unclear column roles |
| `RULE_SUGGESTION_ENABLED` | `true` | The `suggest_quality_rules` phase |
| `TABLE_LLM_NUM_PREDICT` / `GLOBAL_LLM_NUM_PREDICT` | `4096` | Output budget (a small one truncates the JSON and the answer is rejected) |
| `RULE_LLM_NUM_PREDICT` / `COLUMN_ROLE_NUM_PREDICT` | `3072` / `2048` | Output budget of the rule and role calls |
| `LLM_NUM_CTX` | `16384` | Context window |
| `LLM_THINK` | `false` | Keep off: reasoning models can spend the whole budget thinking |
| `TABLE_LLM_TIMEOUT_SECONDS` / `GLOBAL_LLM_TIMEOUT_SECONDS` | `300` / `420` | Read timeouts (slow local models may need more) |
| `GLOBAL_LLM_TEMPERATURE` / `GLOBAL_LLM_TOP_P` | `0.2` / `0.9` | Sampling of the global call |
| `STORAGE_BASE_PATH` | `./storage` | Where results are written (`/opt/airflow/storage` in Docker) |
| `ENV`, `LOG_LEVEL` | `development`, `INFO` | Runtime |

## Storage

- Uploaded CSVs (written by the backend): `data/uploads/datasets/dataset_<id>/v<n>/`.
- Results: `storage/json/<dataset_id>/v<n>/<table>_metrics.json` and
  `<table>_analysis.json` (and `global_analysis.json`), run manifests and
  `execution.log` in `storage/pipeline_runs/<run_id>/`.
- The worker does not generate Sweetviz HTML: the frontend renders its pages from
  the JSON results.

## Run locally

```powershell
cd <project path>
Copy-Item .\.env.example .\.env -ErrorAction SilentlyContinue
# edit .env secrets once if needed
docker compose up --build
```

Airflow UI: `http://localhost:8080`, login `admin` and the value of
`AIRFLOW_ADMIN_PASSWORD` in `.env`. The compose file starts Postgres (Airflow's
database, not published on the host), `airflow-init` (migrates the database and
creates the admin user, then exits), the webserver and the scheduler
(`LocalExecutor`).

The backend triggers the DAG when a CSV is uploaded from the frontend; that is
also the easiest end-to-end test. To run the whole platform in containers, use
the root `docker-compose.yml`, which includes this one.

## Verification

```powershell
.\venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\venv\Scripts\python.exe -m pytest
```

67 tests: backend client, column roles, format profile, rule suggester, business
context, LLM answer shapes and validation, and the phases end to end with a
fake backend. See also `VERIFY_BEFORE_SHARING.md`.

## Notes

- `requirements.txt` is pinned to the Airflow 2.10.0 / Python 3.12 constraints,
  and the `Dockerfile` installs it with that constraints file.
- The code reads the CSV with tolerant encoding and separator detection; if
  strict parsing fails, a forgiving parser skips malformed rows and records a
  warning in the metrics, so a row count here can be lower than the number of
  lines in the file. Strings such as `NULL`, `N/A` or `NaN` are read as missing
  values, like the rest of the platform does.
