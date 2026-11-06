# Question service (`graphrag`)

Python (FastAPI) service that answers, in plain language, the questions asked in the application's **Ask** tab. It only talks to the backend: the browser never calls it.

## What it does

A question is first **sorted** by the local model (Ollama) into three types:

| Type | Examples | Source of the answer | Neo4j? |
| --- | --- | --- | --- |
| `graph` | "How many orders per customer?", "Who has the most friends?" | a Cypher query written by the model, checked, then run on the version's graph | yes |
| `quality` | "Which rules fail and why?", "Which column has the most missing values?" | the quality facts attached by the backend (rules and their message, dimensions, findings, column roles) | no |
| `help` | "How do I apply a correction?", "What does Critical mean?" | the usage guide (`backend/src/main/resources/help/app-guide.md`) | no |

Only the `graph` type needs Neo4j: the other two answer even before the graph is built. If a question about the content of the data arrives without a graph, the answer says to build it in the Graph tab. An uncertain sorting falls back to `quality`.

In every case the answer is written **only** from what is provided (rows obtained, quality facts, guide), with the instruction to invent nothing. The query that was run and its rows are returned with the answer so it can be checked.

## Guardrails on the Cypher (`app/guard.py`)

The model writes the query, so nothing it writes is trusted. A query only runs if:

- it is **a single statement**, **read-only** (`CREATE`, `MERGE`, `DELETE`, `SET`, `CALL`, `LOAD`, `USING`, `APOC`, `DBMS`… are refused, outside strings and comments);
- it contains `MATCH` and `RETURN`;
- **every node** is filtered on `_scope: 'd<dataset>v<version>'`, that of the version being asked about, and no other scope appears;
- Neo4j confirms, with `EXPLAIN`, that it only reads, then it runs with a time limit (15 s by default);
- at most 50 rows are read and 15 are returned to the interface (`truncated` says so); the model gets **two attempts** and receives the reason for a refusal to correct itself.

If no admissible query is found, the answer relies on the quality facts alone and says so.

## API

All routes except `/health` require the `X-QA-Token` header (value of `QA_API_TOKEN`).

| Method | Route | Description |
| --- | --- | --- |
| `GET` | `/health` | `{"status": "ok", "model": "<model>"}` |
| `POST` | `/ask` | `{"question": "...", "scope": "d150v155", "facts": {...}}` → answer |

Response of `/ask`:

```json
{
  "answer": "…",
  "mode": "graph | quality | help",
  "cypher": "MATCH … RETURN …",
  "columns": ["…"],
  "rows": [["…"]],
  "rowCount": 12,
  "truncated": false,
  "warnings": ["Attempt 1: DELETE is not allowed: the query may only read"]
}
```

`facts` is built by the backend (`QaService`): `dataset`, `version`, `context`, `rules`, `dimensions`, `findings`, `columns`, `guide` and, if the graph is built, `graph` (schema and counts). Error codes: 401 token refused, 422 invalid input (empty question or one over 1000 characters, malformed scope), 400 question refused, 503 Neo4j or model unreachable (the message is meant for the user).

## Configuration

Environment variables (same names as the backend and the worker):

| Variable | Default | Role |
| --- | --- | --- |
| `OLLAMA_MODEL` | *(required)* | Ollama model used to sort, write the Cypher and write the answer |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama address |
| `NEO4J_URI` | `bolt://localhost:7687` | Neo4j Bolt address |
| `NEO4J_USER` / `NEO4J_PASSWORD` | `neo4j` / `dq-neo4j-dev` | Credentials (development value, change before sharing) |
| `QA_API_TOKEN` | `dev-qa-token` | Token shared with the backend (development value) |
| `QA_QUERY_TIMEOUT_SECONDS` | `15` | Maximum duration of a Cypher query |

Without `OLLAMA_MODEL`, the service answers 503 with an explicit message.

## Run

With `start-dev.ps1` ("Questions" window), with Docker (`docker compose --env-file Data_Quality_Wevioo/.env up --build`, service `graphrag`, port 8090), or by hand:

```powershell
cd .\graphrag
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
$env:OLLAMA_MODEL = 'gemma4:cloud'     # any model present in Ollama
.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8090
```

## Tests and evaluation

```powershell
.\.venv\Scripts\python.exe -m pip install pytest httpx
.\.venv\Scripts\python.exe -m pytest                      # 39 tests: guardrails, sorting, answers, HTTP service
.\.venv\Scripts\python.exe eval\run_eval.py --model gemma4:cloud
```

The tests use a scripted model and a fake Neo4j: they need neither Ollama nor Neo4j. `eval/run_eval.py` does something else: it replays the questions of `eval/cases.json` (data, quality, usage, refused write requests) against a backend, a service and an Ollama that are **running**, and checks the answer type and the expected words. The cases provided are written for the project's test datasets: adapt them to yours. Exit code 1 if a case fails; handy for comparing two models.

## Layout

| File | Role |
| --- | --- |
| `app/main.py` | FastAPI application, token authentication, errors |
| `app/qa.py` | sorting, generation and execution of the Cypher (`neo4j-graphrag` components), writing of the answer |
| `app/guard.py` | validation of the queries |
| `app/schema.py` | what the model knows about the graph (schema, examples), the quality facts and the guide |
| `app/config.py` | reading of the environment |
| `eval/` | replayable set of questions |
| `tests/` | unit and service tests |
