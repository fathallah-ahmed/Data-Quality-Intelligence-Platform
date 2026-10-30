"""Global LLM analyzer across all SweetViz datasets.

Builds one combined prompt from all llm_dq_report.json files and asks the LLM
for a portfolio-level interpretation (not table-by-table only).
"""

import argparse
import json
import math
import os
import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
import requests


class _NoopSpan:
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        return False

    def set_inputs(self, *_args, **_kwargs):
        return None

    def set_outputs(self, *_args, **_kwargs):
        return None

    def set_attribute(self, *_args, **_kwargs):
        return None


def _start_span(*_args, **_kwargs):
    return _NoopSpan()


def _trace_output_limit():
    return 4000


def _clip_for_trace(text, max_chars):
    return text[:max_chars] if max_chars and len(text) > max_chars else text


def _as_float(value, default=0.0):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    if math.isnan(number):
        return default
    return number


def _safe_text(value):
    if value is None:
        return "-"
    return str(value)


def _safe_read_json(path_obj):
    if not path_obj.exists():
        return None
    with open(path_obj, "r", encoding="utf-8") as handle:
        return json.load(handle)


def _extract_top_values(feature, limit=8):
    detail = feature.get("detail") or {}
    candidates = detail.get("frequent_values") or detail.get("min_values") or []

    rows = []
    for item in candidates:
        if not isinstance(item, list) or len(item) < 2:
            continue
        value = item[0]
        freq = item[1] if isinstance(item[1], dict) else {}
        rows.append({
            "value": _safe_text(value),
            "count": freq.get("number"),
            "pct": round(_as_float(freq.get("perc"), 0.0), 3),
        })
        if limit > 0 and len(rows) >= limit:
            break
    return rows


def _semantic_tags(name, data_type, is_id_like, is_date_like):
    name_l = _safe_text(name).lower()
    tokens = set(re.findall(r"[a-z0-9]+", name_l))

    tag_rules = {
        "identifier": {"id", "idp", "idpv", "idpolizza", "numero", "num", "code", "cod", "cle", "key", "matricule"},
        "date_time": {"date", "dt", "timestamp", "effet", "emission", "naissance", "debut", "fin", "jour", "mois", "annee"},
        "monetary": {"montant", "prime", "capital", "tarif", "cout", "cost", "price", "amount", "somme", "paye"},
        "status_flag": {"statut", "status", "etat", "flag", "actif", "inactive", "suspendu", "resilie", "type"},
        "actor_party": {"client", "assure", "benef", "agence", "courtier", "garage", "conducteur", "personne"},
        "policy_contract": {"contrat", "police", "avenant", "garantie", "sinistre", "risque", "vehicule", "branche"},
        "location": {"ville", "region", "pays", "postal", "adresse", "zone"},
    }

    tags = []
    for label, vocab in tag_rules.items():
        if tokens.intersection(vocab):
            tags.append(label)

    if is_id_like and "identifier" not in tags:
        tags.append("identifier")
    if is_date_like and "date_time" not in tags:
        tags.append("date_time")
    if data_type == "TYPE_NUM" and "monetary" in tags:
        tags.append("numeric_measure")

    if not tags:
        tags.append("unknown_semantics")

    return sorted(set(tags))


def _build_semantic_column(feature):
    base = feature.get("base_stats") or {}
    stats = feature.get("stats") or {}
    feature_name = _safe_text(feature.get("feature_name"))
    data_type = _safe_text(feature.get("data_type"))
    id_like = bool(feature.get("is_id_like"))
    date_like = bool(feature.get("is_date_like"))

    return {
        "name": feature_name,
        "type": data_type,
        "semantic_tags": _semantic_tags(feature_name, data_type, id_like, date_like),
        "id_like": id_like,
        "date_like": date_like,
        "quality": {
            "missing_pct": round(_as_float(((base.get("num_missing") or {}).get("perc")), 0.0), 3),
            "distinct_pct": round(_as_float(((base.get("num_distinct") or {}).get("perc")), 0.0), 3),
            "zero_pct": round(_as_float(((base.get("num_zeroes") or {}).get("perc")), 0.0), 3),
        },
        "distribution": {
            "min": stats.get("min"),
            "max": stats.get("max"),
            "mean": stats.get("mean"),
            "std": stats.get("std"),
            "skewness": stats.get("skewness"),
            "kurtosis": stats.get("kurtosis"),
        },
        "top_values": _extract_top_values(feature, limit=8),
    }


def _summarize_local_llm_analysis(local_analysis):
    if not isinstance(local_analysis, dict):
        return None

    interpretation = local_analysis.get("interpretation") or {}
    quality = local_analysis.get("quality_assessment") or {}
    custom = local_analysis.get("custom_analysis") or {}

    return {
        "summary": interpretation.get("summary"),
        "key_entities": interpretation.get("key_entities") or [],
        "relationships": interpretation.get("relationships") or [],
        "quality": {
            "completeness": quality.get("completeness"),
            "consistency": quality.get("consistency"),
            "issues": quality.get("issues") or [],
        },
        "custom_analysis": {
            "question": custom.get("question"),
            "answer": custom.get("answer"),
            "reasoning": custom.get("reasoning"),
            "supporting_evidence": custom.get("supporting_evidence") or [],
        } if custom else None,
    }


def _slice_detail_values(values, max_values=20):
    if not isinstance(values, list):
        return []
    if max_values <= 0:
        return values
    return values[:max_values]


def _build_compact_feature(feature, max_detail_values=20):
    detail = feature.get("detail") or {}
    return {
        "feature_name": _safe_text(feature.get("feature_name")),
        "data_type": _safe_text(feature.get("data_type")),
        "is_id_like": bool(feature.get("is_id_like")),
        "is_date_like": bool(feature.get("is_date_like")),
        "base_stats": feature.get("base_stats") or {},
        "stats": feature.get("stats") or {},
        "detail": {
            "frequent_values": _slice_detail_values(detail.get("frequent_values"), max_detail_values),
            "min_values": _slice_detail_values(detail.get("min_values"), max_detail_values),
            "max_values": _slice_detail_values(detail.get("max_values"), max_detail_values),
        },
    }


def _build_report_rich_slice(report_data, max_features=0, max_detail_values=20):
    features = report_data.get("features") or []
    compact_features = [_build_compact_feature(f, max_detail_values=max_detail_values) for f in features]
    if max_features > 0:
        compact_features = compact_features[:max_features]

    return {
        "dataset_overview": report_data.get("dataset_overview") or {},
        "associations": report_data.get("associations") or {},
        "features": compact_features,
    }


def _json_char_length(value):
    return len(json.dumps(value, ensure_ascii=True))


def _trim_json_to_char_limit(value, char_limit=0):
    if char_limit <= 0:
        return value

    serialized = json.dumps(value, ensure_ascii=True)
    if len(serialized) <= char_limit:
        return value

    # Keep a deterministic, parseable excerpt while preserving context.
    return {
        "_truncated": True,
        "_original_chars": len(serialized),
        "_char_limit": char_limit,
        "_excerpt": serialized[:char_limit],
    }


def discover_dataset_dirs(root_path):
    root = Path(root_path)
    if not root.exists():
        return []
    return sorted(
        d for d in root.iterdir()
        if d.is_dir() and (d / "llm_dq_report.json").exists()
    )


def top_associations(report_data, limit=0):
    source = ((report_data.get("associations") or {}).get("source") or {})
    pairs = []
    for left, targets in source.items():
        if not isinstance(targets, dict):
            continue
        for right, score in targets.items():
            if left == right:
                continue
            try:
                score_num = float(score)
            except (TypeError, ValueError):
                continue
            key = tuple(sorted((left, right)))
            pairs.append((key, left, right, score_num))

    pairs.sort(key=lambda item: abs(item[3]), reverse=True)
    seen = set()
    deduped = []
    for key, left, right, score_num in pairs:
        if key in seen:
            continue
        seen.add(key)
        deduped.append({
            "left": left,
            "right": right,
            "score": round(score_num, 6),
            "strength_pct": round(abs(score_num) * 100, 2),
        })
        if limit > 0 and len(deduped) >= limit:
            break
    return deduped


def summarize_dataset(report_data, dataset_name, max_features=0, assoc_limit=0):
    overview = ((report_data.get("dataset_overview") or {}).get("source") or {})
    features = report_data.get("features") or []

    type_counts = {}
    high_missing = []
    feature_inventory = []
    semantic_columns = []
    semantic_tag_counts = {}

    for feature in features:
        data_type = _safe_text(feature.get("data_type"))
        type_counts[data_type] = type_counts.get(data_type, 0) + 1

        base = feature.get("base_stats") or {}
        missing_pct = _as_float(((base.get("num_missing") or {}).get("perc")), 0.0)
        distinct_pct = _as_float(((base.get("num_distinct") or {}).get("perc")), 0.0)

        feature_inventory.append({
            "name": _safe_text(feature.get("feature_name")),
            "type": data_type,
            "missing_pct": round(missing_pct, 3),
            "distinct_pct": round(distinct_pct, 3),
            "id_like": bool(feature.get("is_id_like")),
            "date_like": bool(feature.get("is_date_like")),
        })

        semantic_row = _build_semantic_column(feature)
        semantic_columns.append(semantic_row)
        for tag in semantic_row["semantic_tags"]:
            semantic_tag_counts[tag] = semantic_tag_counts.get(tag, 0) + 1

        if missing_pct >= 70:
            high_missing.append({
                "name": _safe_text(feature.get("feature_name")),
                "missing_pct": round(missing_pct, 3),
            })

    high_missing.sort(key=lambda row: row["missing_pct"], reverse=True)
    feature_inventory.sort(key=lambda row: row["missing_pct"], reverse=True)
    semantic_columns.sort(key=lambda row: row["quality"]["missing_pct"], reverse=True)

    feature_rows = feature_inventory if max_features <= 0 else feature_inventory[:max_features]
    semantic_rows = semantic_columns if max_features <= 0 else semantic_columns[:max_features]
    assoc_rows = top_associations(report_data, limit=assoc_limit)

    semantic_concepts = sorted(
        ({
            "concept": tag,
            "count": count,
        } for tag, count in semantic_tag_counts.items()),
        key=lambda item: item["count"],
        reverse=True,
    )

    top_semantic_columns = sorted(
        semantic_rows,
        key=lambda row: (len(row.get("semantic_tags") or []), row.get("quality", {}).get("missing_pct", 0)),
        reverse=True,
    )[:12]

    return {
        "dataset": dataset_name,
        "overview": {
            "rows": overview.get("num_rows"),
            "columns": overview.get("num_columns"),
            "num_cat": overview.get("num_cat"),
            "num_numerical": overview.get("num_numerical"),
            "num_text": overview.get("num_text"),
            "num_date": overview.get("num_date"),
        },
        "feature_type_counts": type_counts,
        "semantic_tag_counts": semantic_tag_counts,
        "semantic_concepts": semantic_concepts,
        "high_missing_columns": high_missing,
        "feature_inventory": feature_rows,
        "semantic_columns": semantic_rows,
        "top_semantic_columns": top_semantic_columns,
        "top_associations": assoc_rows,
    }


def _build_dataset_entry(
    dataset_dir,
    max_features_per_dataset=0,
    assoc_limit=0,
    include_full_report=False,
    report_char_limit=0,
    max_detail_values=20,
):
    report_path = dataset_dir / "llm_dq_report.json"
    report_data = _safe_read_json(report_path) or {}
    local_analysis = _safe_read_json(dataset_dir / "llm_analysis.json")
    local_analysis_brief = _summarize_local_llm_analysis(local_analysis)
    rich_report = _build_report_rich_slice(
        report_data,
        max_features=max_features_per_dataset,
        max_detail_values=max_detail_values,
    )
    rich_report = _trim_json_to_char_limit(rich_report, char_limit=report_char_limit)

    full_report_value = None
    if include_full_report:
        full_report_value = _trim_json_to_char_limit(report_data, char_limit=report_char_limit)

    summary = summarize_dataset(
        report_data,
        dataset_name=dataset_dir.name,
        max_features=max_features_per_dataset,
        assoc_limit=assoc_limit,
    )
    rows = summary["overview"].get("rows")
    cols = summary["overview"].get("columns")

    return {
        "dataset": dataset_dir.name,
        "summary": summary,
        "llm_analysis_brief": local_analysis_brief,
        "per_table_llm_analysis": local_analysis,
        "llm_dq_report_rich_slice": rich_report,
        "full_llm_dq_report": full_report_value,
    }, rows, cols


def build_global_payload(
    root_path,
    max_features_per_dataset=0,
    assoc_limit=0,
    include_full_report=False,
    report_char_limit=0,
    max_detail_values=20,
    max_workers=None,
):
    dataset_dirs = discover_dataset_dirs(root_path)
    datasets = []
    total_rows = 0
    total_columns = 0

    if max_workers is None:
        from dq_pipeline.config.settings import settings
        max_workers = settings.GLOBAL_PAYLOAD_MAX_WORKERS
    max_workers = max(1, int(max_workers))

    if max_workers == 1:
        for dataset_dir in dataset_dirs:
            dataset_entry, rows, cols = _build_dataset_entry(
                dataset_dir,
                max_features_per_dataset=max_features_per_dataset,
                assoc_limit=assoc_limit,
                include_full_report=include_full_report,
                report_char_limit=report_char_limit,
                max_detail_values=max_detail_values,
            )
            datasets.append(dataset_entry)
            if isinstance(rows, (int, float)):
                total_rows += int(rows)
            if isinstance(cols, (int, float)):
                total_columns += int(cols)
    else:
        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = {
                executor.submit(
                    _build_dataset_entry,
                    dataset_dir,
                    max_features_per_dataset,
                    assoc_limit,
                    include_full_report,
                    report_char_limit,
                    max_detail_values,
                ): dataset_dir
                for dataset_dir in dataset_dirs
            }

            for future in as_completed(futures):
                dataset_entry, rows, cols = future.result()
                datasets.append(dataset_entry)
                if isinstance(rows, (int, float)):
                    total_rows += int(rows)
                if isinstance(cols, (int, float)):
                    total_columns += int(cols)

    datasets.sort(key=lambda item: item.get("dataset", ""))

    payload = {
        "portfolio_overview": {
            "dataset_count": len(datasets),
            "estimated_total_rows": total_rows,
            "sum_of_columns_across_datasets": total_columns,
            "root_path": str(Path(root_path).resolve()),
        },
        "datasets": datasets,
    }
    return payload


def build_global_prompt(payload):
    compact = json.dumps(payload, ensure_ascii=True)
    return f"""You are a senior data-quality and insurance-data consultant.

You receive one portfolio payload that combines ALL datasets at once.
The payload includes, for each dataset:
- a structured summary,
- rich semantic column profiles,
- semantic concept counts,
- existing per-table LLM analysis,
- a compact per-table LLM brief,
- full llm_dq_report content.

Your task is to produce a global interpretation across the full dataset collection,
not isolated table-by-table summaries.

When reasoning, actively leverage semantic column meaning from names, data patterns,
top values, and existing per-table LLM interpretations. Build cross-dataset mappings
of equivalent concepts (for example: client identifiers, contract lifecycle dates,
policy references, claim/sinister concepts, agency dimensions).

Prioritize information richness:
- cite concrete evidence from multiple datasets and columns,
- explain business implications,
- detect shared data-quality root causes,
- propose harmonization standards and governance controls.

Make the result operational and exploitable by including:
- a cross-dataset entity map,
- semantic clusters of columns that should be standardized,
- a prioritized backlog of remediation actions,
- questions that should be answered by business owners or source-system teams,
- short-term, medium-term, and long-term recommendations.

PORTFOLIO PAYLOAD:
{compact}

REQUIRED OUTPUT (valid JSON only, no markdown, no prose outside JSON):
{{
  "portfolio_summary": {{
    "business_view": "...",
    "data_quality_posture": "...",
    "key_portfolio_patterns": ["..."],
    "critical_risks": ["..."]
  }},
  "cross_dataset_findings": [
    {{
      "finding": "...",
      "evidence": ["dataset/field-level evidence"],
      "impact": "high/medium/low"
    }}
  ],
  "dataset_specific_highlights": [
    {{
      "dataset": "...",
      "strengths": ["..."],
      "issues": ["..."],
      "priority": 1
    }}
  ],
    "entity_map": [
        {{
            "entity": "...",
            "candidate_columns": ["dataset.column"],
            "standardization_note": "..."
        }}
    ],
    "semantic_clusters": [
        {{
            "cluster_name": "...",
            "columns": ["dataset.column"],
            "why_it_matters": "...",
            "standardize_as": "..."
        }}
    ],
  "portfolio_action_plan": [
    {{
      "action": "...",
      "scope": "portfolio/dataset",
      "target_datasets": ["..."],
      "expected_benefit": "...",
      "effort": "low/medium/high",
      "priority": 1
    }}
  ],
    "recommendations_by_time_horizon": {{
        "short_term": ["..."],
        "medium_term": ["..."],
        "long_term": ["..."]
    }},
    "questions_for_stakeholders": ["..."],
  "governance_recommendations": ["..."],
  "final_assessment": "..."
}}
"""


def call_ollama(
    prompt,
    model="qwen2.5:14b-instruct",
    temperature=0.6,
    top_p=0.9,
    num_predict=None,
    timeout_seconds=420,
    ollama_url=None,
):
    options = {
        "temperature": temperature,
        "top_p": top_p,
    }
    if num_predict is not None:
        options["num_predict"] = num_predict

    from dq_pipeline.config.settings import settings
    base_url = (ollama_url or (str(settings.OLLAMA_URL) if settings.OLLAMA_URL else None) or "http://localhost:11434").rstrip("/")
    api_url = f"{base_url}/api/generate"
    with _start_span(
        name="ollama.generate.global",
        span_type="LLM",
        attributes={
            "llm.provider": "ollama",
            "llm.model": model,
            "llm.endpoint": api_url,
            "llm.temperature": temperature,
            "llm.top_p": top_p,
            "llm.timeout_seconds": timeout_seconds,
        },
    ) as span:
        span.set_inputs({"prompt": prompt, "model": model, "options": options})
        response = requests.post(
            api_url,
            json={
                "model": model,
                "prompt": prompt,
                "stream": False,
                "format": "json",
                "options": options,
            },
            timeout=(20, timeout_seconds),
        )
        span.set_attribute("llm.http_status", response.status_code)

    if response.status_code != 200:
        detail = response.text[:800] if response.text else "(no detail)"
        raise RuntimeError(f"Ollama error HTTP {response.status_code}: {detail}")

    payload = response.json()
    text = str(payload.get("response") or payload.get("thinking") or "").strip()
    with _start_span(name="global.response_parse", span_type="PARSER") as parser_span:
        max_chars = _trace_output_limit()
        clipped = _clip_for_trace(text, max_chars)
        parser_span.set_inputs({"payload_keys": list(payload.keys())})
        parser_span.set_outputs({
            "response_chars": len(text),
            "response_text": clipped,
            "response_truncated": len(clipped) < len(text),
            "trace_output_max_chars": max_chars,
        })
    if not text:
        raise RuntimeError("Ollama returned an empty response.")
    return text


def parse_json_response(text):
    try:
        return json.loads(text)
    except (json.JSONDecodeError, ValueError):
        pass

    start = text.find("{")
    end = text.rfind("}") + 1
    if start >= 0 and end > start:
        return json.loads(text[start:end])

    raise RuntimeError("Model response is not valid JSON.")


def build_pipeline_global_prompt(payload):
    compact = json.dumps(payload, ensure_ascii=True)
    return f"""You are a senior data-quality architect.

You receive one compact portfolio payload produced after table profiling and
per-table analysis. The payload contains table metrics, column inventories,
deterministic relationship candidates, parser warnings, and existing table LLM
summaries when available.

Use deterministic metrics as the source of truth. Do not recalculate row counts,
missingness, duplicate counts, or relationships. Do not invent columns.

Your job is to produce a concise global analysis across the uploaded dataset
version. Focus on cross-table meaning, business risks, relationship consistency,
governance actions, and questions for business/source-system owners.

PORTFOLIO PAYLOAD:
{compact}

Return valid JSON only with this shape:
{{
  "summary": "...",
  "portfolio_summary": {{
    "business_view": "...",
    "data_quality_posture": "...",
    "key_portfolio_patterns": ["..."],
    "critical_risks": ["..."]
  }},
  "cross_dataset_findings": [
    {{"finding": "...", "evidence": ["..."], "impact": "high|medium|low"}}
  ],
  "dataset_specific_highlights": [
    {{"dataset": "...", "strengths": ["..."], "issues": ["..."], "priority": 1}}
  ],
  "entity_map": [
    {{"entity": "...", "candidate_columns": ["table.column"], "standardization_note": "..."}}
  ],
  "semantic_clusters": [
    {{"cluster_name": "...", "columns": ["table.column"], "why_it_matters": "...", "standardize_as": "..."}}
  ],
  "portfolio_action_plan": [
    {{"action": "...", "scope": "portfolio|table", "target_datasets": ["..."], "expected_benefit": "...", "effort": "low|medium|high", "priority": 1}}
  ],
  "recommendations_by_time_horizon": {{
    "short_term": ["..."],
    "medium_term": ["..."],
    "long_term": ["..."]
  }},
  "questions_for_stakeholders": ["..."],
  "governance_recommendations": ["..."],
  "final_assessment": "..."
}}
"""


def analyze_pipeline_global_payload(
    payload,
    ollama_url=None,
    model="qwen2.5:14b-instruct",
    temperature=0.2,
    top_p=0.9,
    num_predict=1800,
    timeout_seconds=420,
):
    prompt = build_pipeline_global_prompt(payload)
    raw_text = call_ollama(
        prompt,
        ollama_url=ollama_url,
        model=model,
        temperature=temperature,
        top_p=top_p,
        num_predict=num_predict,
        timeout_seconds=timeout_seconds,
    )
    return parse_json_response(raw_text)


def main():
    from dq_pipeline.config.settings import settings
    parser = argparse.ArgumentParser(description="Analyze ALL datasets together with one global LLM call.")
    parser.add_argument("--root", default="sweetviz_extracted", help="Root folder containing dataset directories")
    parser.add_argument("--ollama-url", default=(str(settings.OLLAMA_URL) if settings.OLLAMA_URL else "http://localhost:11434"), help="Ollama base URL")
    parser.add_argument("--model", default="qwen2.5:14b-instruct", help="Ollama model name")
    parser.add_argument("--max-features-per-dataset", type=int, default=0, help="Max features per dataset summary (0 means all features)")
    parser.add_argument("--assoc-limit", type=int, default=0, help="Max association pairs per dataset (0 means all pairs)")
    parser.add_argument("--temperature", type=float, default=0.6, help="Generation temperature")
    parser.add_argument("--top-p", type=float, default=0.9, help="Nucleus sampling")
    parser.add_argument("--num-predict", type=int, default=None, help="Optional generation token cap")
    parser.add_argument("--timeout-seconds", type=int, default=420, help="Read timeout for Ollama response")
    parser.add_argument("--include-full-llm-dq-report", action="store_true", help="Embed full llm_dq_report.json per dataset (can become very large)")
    parser.add_argument("--report-char-limit", type=int, default=0, help="Optional char cap for each embedded report object (0 means unlimited)")
    parser.add_argument("--max-detail-values", type=int, default=20, help="Max detail values per feature inside rich report slice")
    parser.add_argument("--max-workers", type=int, default=None, help="Parallel workers for dataset payload building (defaults to settings.GLOBAL_PAYLOAD_MAX_WORKERS)")
    parser.add_argument("--output", default="sweetviz_extracted/llm_global_analysis.json", help="Output JSON path")
    args = parser.parse_args()

    payload = build_global_payload(
        args.root,
        max_features_per_dataset=args.max_features_per_dataset,
        assoc_limit=args.assoc_limit,
        include_full_report=args.include_full_llm_dq_report,
        report_char_limit=args.report_char_limit,
        max_detail_values=args.max_detail_values,
        max_workers=args.max_workers,
    )
    dataset_count = (payload.get("portfolio_overview") or {}).get("dataset_count", 0)

    if dataset_count == 0:
        raise SystemExit("No llm_dq_report.json files found.")

    print(f"[GLOBAL] Datasets discovered: {dataset_count}")
    prompt = build_global_prompt(payload)

    print(f"[GLOBAL] Prompt size: {len(prompt):,} chars")
    print(f"[GLOBAL] include_full_report={args.include_full_llm_dq_report}, report_char_limit={args.report_char_limit}")
    print(f"[GLOBAL] Calling model: {args.model}")

    raw_text = call_ollama(
        prompt,
        ollama_url=args.ollama_url,
        model=args.model,
        temperature=args.temperature,
        top_p=args.top_p,
        num_predict=args.num_predict,
        timeout_seconds=args.timeout_seconds,
    )
    parsed = parse_json_response(raw_text)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as handle:
        json.dump(parsed, handle, indent=2, ensure_ascii=False)

    print(f"[GLOBAL] Saved: {output_path}")


if __name__ == "__main__":
    main()
