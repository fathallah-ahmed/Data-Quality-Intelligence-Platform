"""
Replays a fixed set of questions against a running backend and reports what the question service got right.

    python eval/run_eval.py                       # eval/cases.json against http://localhost:8081
    python eval/run_eval.py my_cases.json --model gemma4:cloud

Each case names a dataset (by its name in the application), a question, the kind of answer expected (graph, quality
or help) and words the answer must contain (all of `contains`, at least one of `any_of`) or must not contain. The
cases that ship are examples written for the test datasets of this project: edit them for yours. Only the standard
library is used, so it runs anywhere Python does. The exit code is 1 when a case fails.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

DEFAULT_CASES = __file__.rsplit("run_eval.py", 1)[0] + "cases.json"


def call(base, path, token=None, body=None):
    request = urllib.request.Request(
        base + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})},
    )
    try:
        with urllib.request.urlopen(request, timeout=600) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        try:
            detail = json.load(error).get("error", str(error))
        except Exception:  # not JSON
            detail = str(error)
        return {"error": detail}


def check(case, answer):
    """The reasons a case failed (empty when it passed)."""
    if "error" in answer:
        return [f"the service answered an error: {answer['error']}"]
    problems = []
    text = answer.get("answer", "")
    lowered = text.lower()
    if not text.strip():
        problems.append("empty answer")
    if case.get("mode") and answer.get("mode") != case["mode"]:
        problems.append(f"expected a {case['mode']} answer, got {answer.get('mode')}")
    if answer.get("mode") == "graph" and not answer.get("cypher"):
        problems.append("a graph answer without the query that supports it")
    for word in case.get("contains", []):
        if word.lower() not in lowered:
            problems.append(f"missing {word!r}")
    any_of = case.get("any_of", [])
    if any_of and not any(word.lower() in lowered for word in any_of):
        problems.append(f"none of {any_of} found")
    for word in case.get("not_contains", []):
        if word.lower() in lowered:
            problems.append(f"must not say {word!r}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("cases", nargs="?", default=DEFAULT_CASES)
    parser.add_argument("--base", default="http://localhost:8081/api")
    parser.add_argument("--user", default="admin")
    parser.add_argument("--password", default="admin123", help="development default of this project")
    parser.add_argument("--model", default="", help="only a label for the report (the service uses its own OLLAMA_MODEL)")
    parser.add_argument("--only", default="", help="run only the cases whose dataset name or question contains this text")
    args = parser.parse_args()

    with open(args.cases, encoding="utf-8") as handle:
        cases = json.load(handle)
    token = call(args.base, "/auth/login", body={"username": args.user, "password": args.password}).get("token")
    if not token:
        sys.exit("Could not sign in: check --user / --password and that the backend runs.")
    datasets = {d["name"]: d for d in call(args.base, "/datasets", token)}

    passed = 0
    ran = 0
    by_mode = {}
    for case in cases:
        if args.only and args.only.lower() not in (case["dataset"] + " " + case["question"]).lower():
            continue
        dataset = datasets.get(case["dataset"])
        if dataset is None:
            print(f"SKIP  {case['dataset']}: no such dataset in the application")
            continue
        ran += 1
        version = dataset["latestVersion"]["id"]
        started = time.time()
        answer = call(args.base, f"/datasets/{dataset['id']}/versions/{version}/ask", token, {"question": case["question"]})
        problems = check(case, answer)
        seconds = time.time() - started
        mode = answer.get("mode", "-")
        stats = by_mode.setdefault(case.get("mode", "any"), [0, 0])
        stats[1] += 1
        if not problems:
            passed += 1
            stats[0] += 1
        print(f"{'PASS' if not problems else 'FAIL'}  [{mode:<7}] {seconds:4.0f}s  {case['dataset']}: {case['question']}")
        for problem in problems:
            print(f"        - {problem}")
        if problems and "answer" in answer:
            print(f"        answer: {answer['answer'][:240].replace(chr(10), ' ')}")
    label = f" (model {args.model})" if args.model else ""
    print(f"\n{passed}/{ran} cases passed{label}")
    for mode, (ok, total) in sorted(by_mode.items()):
        print(f"  {mode:<8} {ok}/{total}")
    sys.exit(0 if passed == ran else 1)


if __name__ == "__main__":
    main()
