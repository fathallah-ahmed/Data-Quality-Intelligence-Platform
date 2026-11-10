"""Rejoue le jeu de questions du service pour un modèle donné et enregistre le détail de chaque cas."""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, "C:/Users/ahmed/Desktop/DataQalityWevio/graphrag/eval")
import run_eval  # noqa: E402

model = sys.argv[1]
base = "http://localhost:8081/api"
cases = json.loads(Path("C:/Users/ahmed/Desktop/DataQalityWevio/graphrag/eval/cases.json").read_text(encoding="utf-8"))
token = run_eval.call(base, "/auth/login", body={"username": "admin", "password": "admin123"})["token"]
datasets = {d["name"]: d for d in run_eval.call(base, "/datasets", token)}

records = []
for case in cases:
    dataset = datasets[case["dataset"]]
    version = dataset["latestVersion"]["id"]
    started = time.time()
    try:
        answer = run_eval.call(base, f"/datasets/{dataset['id']}/versions/{version}/ask", token, {"question": case["question"]})
        problems = run_eval.check(case, answer)
        error = None
    except BaseException as exc:  # noqa: BLE001
        answer, problems, error = {}, ["erreur"], str(exc)[:160]
    record = {
        "model": model, "dataset": case["dataset"], "question": case["question"], "expected_mode": case.get("mode", "any"),
        "mode": answer.get("mode"), "ok": not problems, "problems": problems, "error": error,
        "seconds": round(time.time() - started, 1), "has_cypher": bool(answer.get("cypher")),
        "refused_attempts": len(answer.get("warnings") or []), "rows": answer.get("rowCount"),
        "answer": (answer.get("answer") or "")[:200],
    }
    records.append(record)
    print(json.dumps({k: record[k] for k in ("question", "expected_mode", "mode", "ok", "seconds", "has_cypher", "refused_attempts", "error")}, ensure_ascii=False), flush=True)

out = Path(__file__).parent / f"qresults_{model.replace(':', '_').replace('/', '_')}.json"
out.write_text(json.dumps(records, ensure_ascii=False, indent=1), encoding="utf-8")
print("écrit", out.name)
