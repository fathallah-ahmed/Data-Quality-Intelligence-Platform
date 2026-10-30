"""
Enhanced LLM analyzer with comprehensive data quality insights.
"""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import sys
import time

import pandas as pd
import requests
from concurrent.futures import ThreadPoolExecutor, as_completed

try:
    from dq_pipeline.analyzers.langgraph_analyzer import (
        analyze_table_with_langgraph,
        _build_analysis_prompt,
        _build_table_evidence,
    )
except Exception:
    analyze_table_with_langgraph = None
    _build_analysis_prompt = None
    _build_table_evidence = None

def process_one_dataset(
    dataset_dir,
    skip_existing=True,
    model="qwen2.5:14b-instruct",
    temperature=0.2,
    top_p=0.9,
    num_predict=1400,
    debug=False,
):
    current_dataset = dataset_dir.name
    json_path = dataset_dir / "llm_dq_report.json"
    output_path = dataset_dir / "llm_analysis.json"

    if not json_path.exists():
        return current_dataset, "missing", False

    if skip_existing and output_path.exists():
        print(f"[SKIP] Skipping {current_dataset} (already processed)")
        return current_dataset, "skipped", True

    analysis = generate_enhanced_analysis(
        current_dataset,
        json_path,
        model=model,
        temperature=temperature,
        top_p=top_p,
        num_predict=num_predict,
        debug=debug,
    )

    if analysis:
        _debug_log(debug, f"Writing output JSON to {output_path}")
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(analysis, f, indent=2, ensure_ascii=False)

        print(f"[OK] Saved to {output_path}")
        return current_dataset, "done", True

    print(f"[WARN] Failed to generate analysis for {current_dataset}")
    return current_dataset, "failed", False




def _debug_log(enabled, message):
    if not enabled:
        return
    timestamp = datetime.now().strftime("%H:%M:%S")
    print(f"[{timestamp}] [debug] {message}", file=sys.stderr)

def load_dataset_config(config_path=None):
    """Load dataset descriptions and custom questions from CSV."""
    candidates = []
    if config_path:
        candidates.append(Path(config_path))
    env_path = os.environ.get("DQ_DATASET_CONFIG")
    if env_path:
        candidates.append(Path(env_path))
    candidates.append(Path("dataset_config.csv"))

    seen = set()
    for candidate in candidates:
        resolved = candidate.expanduser()
        marker = str(resolved)
        if marker in seen or not resolved.exists():
            continue
        seen.add(marker)
        df = pd.read_csv(resolved)
        config = {}
        for _, row in df.iterrows():
            name = _clean_config_value(row.get("dataset_name", ""))
            if not name:
                continue
            config[name] = {
                "description": _clean_config_value(row.get("description", "")),
                "custom_question": _clean_config_value(row.get("custom_question", "")),
            }
        return config
    return {}


def _clean_config_value(value):
    if value is None:
        return ""
    try:
        if pd.isna(value):
            return ""
    except (TypeError, ValueError):
        pass
    text = str(value).strip()
    return "" if text.lower() == "nan" else text


def _context_for_table(config, table_name):
    global_config = config.get("__global__", {})
    table_config = config.get(table_name, {})
    global_description = global_config.get("description", "")
    table_description = table_config.get("description", "")
    if global_description and table_description:
        description = (
            f"Global dataset context: {global_description}\n"
            f"Table context for {table_name}: {table_description}"
        )
    else:
        description = table_description or global_description
    custom_question = table_config.get("custom_question") or global_config.get("custom_question") or ""
    return description, custom_question

def _mini_payload(cleaned_json):
    """Build a compact payload for LLM to avoid long generation latency."""
    payload = {
        "dataset_overview": cleaned_json.get("dataset_overview", {}),
        "metadata": cleaned_json.get("metadata", {}),
        "features": [],
        "feature_inventory": [],
        "full_report": cleaned_json,
        "associations": (cleaned_json.get("associations") or {}).get("source", {}),
    }

    for feature in _iter_features(cleaned_json):
        detail = feature.get("detail", {}) or {}
        missing_pct = ((feature.get("base_stats") or {}).get("num_missing") or {}).get("perc")
        distinct_pct = ((feature.get("base_stats") or {}).get("num_distinct") or {}).get("perc")
        top_values = (detail.get("full_count") or detail.get("summary_count") or [])[:8]
        compact_feature = {
            "feature_name": feature.get("feature_name"),
            "data_type": feature.get("data_type"),
            "is_id_like": feature.get("is_id_like"),
            "is_date_like": feature.get("is_date_like"),
            "base_stats": feature.get("base_stats"),
            "stats": feature.get("stats"),
            "top_values": top_values,
        }
        payload["features"].append(
            compact_feature
        )
        payload["feature_inventory"].append(
            {
                "feature_name": feature.get("feature_name"),
                "data_type": feature.get("data_type"),
                "missing_pct": missing_pct,
                "distinct_pct": distinct_pct,
                "is_id_like": feature.get("is_id_like"),
                "is_date_like": feature.get("is_date_like"),
                "top_values": top_values,
            }
        )
    return payload


def _iter_features(cleaned_json):
    """Yield feature dictionaries from either formatter JSON or raw Sweetviz JSON."""
    features = cleaned_json.get("features") or []
    if isinstance(features, dict):
        iterable = features.items()
        for feature_name, feature in iterable:
            if not isinstance(feature, dict):
                continue
            feature = dict(feature)
            feature.setdefault("feature_name", feature_name)
            yield feature
        return

    for feature in features:
        if isinstance(feature, dict):
            yield feature


def analyze_table_metrics(
    table_name: str,
    sweetviz_data: dict,
    ollama_url: str = None,
    model: str = "qwen2.5:14b-instruct",
    temperature: float = 0.2,
    top_p: float = 0.9,
    num_predict: int = 1400,
    debug: bool = False,
    dataset_config_path: str = None,
) -> dict:
    """
    Analyze a single table using Sweetviz metrics and LLM.
    
    This is the refactored per-table analysis method suitable for pipeline_runner.
    
    Args:
        table_name: Name of the table
        sweetviz_data: Extracted Sweetviz metrics dict
        ollama_url: Ollama server URL (optional, defaults to settings.OLLAMA_URL)
        model: LLM model name
        temperature: Generation temperature
        top_p: Nucleus sampling parameter
        num_predict: Max tokens to generate
        debug: Enable debug logging
    
    Returns:
        Dictionary with LLM interpretation or fallback
    """
    try:
        if debug:
            print(f"[DEBUG] {table_name}: Starting LLM analysis...")
        
        # Check if Sweetviz analysis succeeded
        if sweetviz_data.get("status") == "failed":
            if debug:
                print(f"[DEBUG] {table_name}: Skipping LLM (Sweetviz failed)")
            return {"status": "skipped", "reason": "sweetviz_failed"}
        
        # Load dataset config if available
        config = load_dataset_config(dataset_config_path)
        description, custom_question = _context_for_table(config, table_name)

        if analyze_table_with_langgraph is not None:
            if debug:
                print(f"[DEBUG] {table_name}: Running controlled LangGraph LLM workflow")
            return analyze_table_with_langgraph(
                table_name=table_name,
                sweetviz_data=sweetviz_data,
                llm_call=call_ollama,
                response_parser=parse_llm_response,
                ollama_url=ollama_url,
                model=model,
                temperature=temperature,
                top_p=top_p,
                num_predict=num_predict,
                description=description,
                custom_question=custom_question,
                debug=debug,
            )
        
        # Build prompt from Sweetviz data
        prompt = build_enhanced_prompt(table_name, sweetviz_data, description, custom_question)
        
        if debug:
            print(f"[DEBUG] {table_name}: Prompt length={len(prompt)} chars")
        
        # Call Ollama
        response = call_ollama(
            prompt,
            model=model,
            temperature=temperature,
            top_p=top_p,
            num_predict=num_predict,
            debug=debug,
            ollama_url=ollama_url,
        )
        
        if not response:
            if debug:
                print(f"[DEBUG] {table_name}: LLM call failed, using fallback")
            return _fallback_analysis(table_name, description, custom_question, "no_response")
        
        # Parse response
        analysis = parse_llm_response(response)
        
        if not analysis:
            if debug:
                print(f"[DEBUG] {table_name}: Response parse failed, using fallback")
            analysis = _fallback_analysis(table_name, description, custom_question, "invalid_json")
            analysis["raw_response"] = response
        
        if debug:
            print(f"[DEBUG] {table_name}: LLM analysis complete")
        
        return {
            "status": "completed",
            "table_name": table_name,
            "model": model,
            "analysis": analysis,
        }
        
    except Exception as e:
        error_msg = f"LLM analysis failed for {table_name}: {e}"
        if debug:
            print(f"[DEBUG] {error_msg}")
            import traceback
            traceback.print_exc()
        return {
            "status": "failed",
            "table_name": table_name,
            "error": str(e),
        }


def build_enhanced_prompt(dataset_name, cleaned_json, description=None, custom_question=None):
    """Build the strict fallback prompt used if the LangGraph wrapper is unavailable."""

    if _build_table_evidence is not None and _build_analysis_prompt is not None:
        evidence = _build_table_evidence(dataset_name, cleaned_json or {})
        return _build_analysis_prompt(
            table_name=dataset_name,
            evidence=evidence,
            description=description or "",
            custom_question=custom_question or "",
        )
    
    # Extract key stats from cleaned JSON
    features = list(_iter_features(cleaned_json))
    feature_count = len(features)
    row_count = ((cleaned_json.get("dataset_overview") or {}).get("source") or {}).get("num_rows", "unknown")
    
    # Count feature types
    feature_types = {}
    critical_missingness = []  # Columns with >95% missing - likely artifacts
    
    for feature in features:
        ftype = feature.get("data_type", "unknown")
        feature_types[ftype] = feature_types.get(ftype, 0) + 1
        
        # Identify columns with extreme missingness (>95%)
        missing_pct = feature.get("base_stats", {}).get("num_missing", {}).get("perc", 0)
        if missing_pct > 95:
            critical_missingness.append({
                "name": feature.get("feature_name"),
                "missing_pct": round(missing_pct, 2),
                "reason": "Input shifting/parsing artifact" if "Unnamed" in feature.get("feature_name", "") else "Sparse column"
            })
    
    type_summary = ", ".join([f"{count} {ftype}" for ftype, count in feature_types.items()])
    compact_payload = _mini_payload(cleaned_json)
    feature_inventory = compact_payload.get("feature_inventory", [])
    inventory_lines = []
    for feature in feature_inventory:
        missing_pct = feature.get("missing_pct")
        distinct_pct = feature.get("distinct_pct")
        missing_text = "unknown" if missing_pct is None else f"{missing_pct:.2f}%"
        distinct_text = "unknown" if distinct_pct is None else f"{distinct_pct:.2f}%"
        top_values = feature.get("top_values") or []
        top_text = json.dumps(top_values[:5], ensure_ascii=True) if top_values else "[]"
        inventory_lines.append(
            f"- {feature.get('feature_name')} | type={feature.get('data_type')} | missing={missing_text} | distinct={distinct_text} | id_like={feature.get('is_id_like')} | date_like={feature.get('is_date_like')} | observed_top_values={top_text}"
        )
    inventory_block = "\n".join(inventory_lines)
    full_report_block = json.dumps(compact_payload, ensure_ascii=True, indent=2)
    
    # Format critical issues for the prompt
    critical_section = ""
    if critical_missingness:
        critical_section = "\nHIGH-MISSINGNESS EVIDENCE (review first):\n"
        for issue in critical_missingness:
            critical_section += f"   - {issue['name']}: {issue['missing_pct']}% missing ({issue['reason']})\n"
        critical_section += "   These columns may be sparse fields or ingestion artifacts; verify with the source system.\n"
    
    prompt = f"""You are a data quality analyst. Analyze exactly one table using only the evidence below: {dataset_name}

DATASET OVERVIEW:
- Name: {dataset_name}
- Rows: {row_count}
- Features: {feature_count} ({type_summary})
- Domain Context: {description or 'Not provided'}
{critical_section}
STRUCTURED DATA (compact):
{json.dumps(compact_payload, ensure_ascii=True)[:4500]}

FULL FEATURE INVENTORY:
{inventory_block}

COMPACT EVIDENCE JSON:
{full_report_block}

YOUR ANALYSIS SHOULD INCLUDE:

1. DATA INTERPRETATION:
   - What does this dataset represent in business terms?
   - What is the primary purpose of each key field?
   - How do the key entities/relationships fit together?
    - Review every column in the inventory, not just the obvious ones.
    - Explicitly call out sparse columns, shifted input artifacts, duplicate-looking fields, and columns that appear to be noise.
    - Use only the compact evidence JSON, feature inventory, associations, metadata, and field statistics shown here.

2. DATA QUALITY ASSESSMENT:
   PRIORITY: Address verified high-missingness columns first.
   - Identify columns with >90% missing data as parsing artifacts or data quality failures
   - Identify missing data patterns (high nulls, sparse columns)
   - Identify data type inconsistencies or anomalies
   - Identify duplicate or redundant fields (especially those with high correlation to other columns)
   - Assess cardinality and uniqueness patterns
   - Flag any data completeness concerns
    - Incorporate all report evidence, even if it points to non-obvious columns or relationships.

3. FIELDS FOR IMPROVEMENT:
   - List columns with >90% missingness first when the evidence supports it.
   - Then list 3-5 other specific data quality issues found
   - For each issue, explain why it matters for analysis
   - Estimate impact (high/medium/low)

4. RESOLUTION RECOMMENDATIONS:
   - For each issue, suggest concrete improvement steps
   - Prioritize by impact and feasibility
   - Include specific data cleaning/transformation techniques

5. CUSTOM QUESTION ANALYSIS:
   Question: {custom_question or 'Describe the dataset structure and key patterns'}
    
   Answer this question with:
   - Direct answer/assessment
   - Detailed reasoning based on the data
   - Evidence from the dataset (specific columns, distributions, correlations)
   - Practical implications

MANDATORY OUTPUT RULES:
- Return ONLY valid JSON
- Do NOT add markdown fences (no ```json blocks)
- Do NOT add any prose, explanation, or preamble
- Keep each text field concise but concrete
- Treat top_values/observed_top_values as observed frequent values from profiling, not an exhaustive list of possible categories
- For categorical or text fields, mention observed top values only when they are useful; do not invent possible categories
- Output must be pure JSON that can be parsed by json.loads()

RESPOND IN RAW JSON FORMAT (no markdown, no code fences):
{{
  "interpretation": {{ "summary": "business meaning based only on table and column evidence", "key_entities": ["exact column names or table-level entities"], "relationships": ["relationships supported by evidence"] }},
  "quality_assessment": {{ "issues": ["specific issue supported by evidence"], "completeness": "missingness assessment with exact columns", "consistency": "type and cardinality assessment" }},
  "improvement_areas": [
    {{ "field": "exact column name or table", "issue": "specific evidence-backed issue", "impact": "high|medium|low", "priority": 1 }}
  ],
  "resolution_steps": [
    {{ "field": "exact column name or table", "issue": "same issue addressed", "steps": ["concrete action"], "effort": "low|medium|high" }}
  ],
  "custom_analysis": {{
    "question": "{custom_question}",
    "answer": "direct answer",
    "reasoning": "brief reasoning grounded in evidence",
    "supporting_evidence": ["exact metric or column evidence"]
  }}
}}
"""
    return prompt

def call_ollama(
    prompt,
    model="qwen2.5:14b-instruct",
    temperature=0.7,
    top_p=0.95,
    num_predict=None,
    debug=False,
    ollama_url=None,
    system=None,
    format_schema=None,
):
    """Call Ollama API to generate enhanced analysis (non-streaming)."""
    try:
        from dq_pipeline.config.settings import settings
        options = {
            "temperature": temperature,
            "top_p": top_p,
        }
        if num_predict is not None:
            options["num_predict"] = num_predict

        request_start = time.monotonic()
        
        # Use provided ollama_url or fall back to settings
        if ollama_url:
            base_url = str(ollama_url).rstrip("/")
        else:
            base_url = (str(settings.OLLAMA_URL) if settings.OLLAMA_URL else "http://localhost:11434").rstrip("/")
        
        api_url = f"{base_url}/api/generate"
        _debug_log(debug, f"Calling Ollama: model={model}, temperature={temperature}, top_p={top_p}, num_predict={num_predict}, structured={format_schema is not None}")
        request_payload = {
            "model": model,
            "prompt": prompt,
            "stream": False,
            "format": format_schema or "json",
            "options": options,
        }
        if system:
            request_payload["system"] = system

        response = requests.post(
            api_url,
            json=request_payload,
            stream=False,
            timeout=(15, 180),
        )
        if response.status_code != 200 and format_schema is not None:
            _debug_log(debug, "Structured schema format failed; retrying with plain JSON mode")
            request_payload["format"] = "json"
            response = requests.post(
                api_url,
                json=request_payload,
                stream=False,
                timeout=(15, 180),
            )
        elapsed = time.monotonic() - request_start
        _debug_log(debug, f"HTTP response received in {elapsed:.2f}s with status={response.status_code}")
        
        if response.status_code != 200:
            error_detail = response.text[:500] if response.text else "(no detail)"
            print(f"Ollama error: HTTP {response.status_code}")
            print(f"  Error detail: {error_detail}")
            return None

        payload = response.json()
        text = str(payload.get("response") or payload.get("thinking") or "").strip()
        _debug_log(debug, f"Response parsed: {len(text)} chars")
        return text
        
    except requests.exceptions.ConnectionError:
        print("Error: Ollama is not running. Start it with: ollama serve")
        return None
    except requests.exceptions.ReadTimeout:
        print("Error calling Ollama: request timed out while waiting for a response. Try a smaller model, a shorter prompt, or a longer timeout.")
        return None
    except Exception as e:
        print(f"Error calling Ollama: {e}")
        return None

def _extract_json_from_markdown(text):
    """Try to extract JSON block from Markdown response (e.g., ```json {...} ```)."""
    import re
    # Look for ```json ... ``` blocks
    match = re.search(r'```json\s*\n?({.*?})\s*\n?```', text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except json.JSONDecodeError:
            pass
    # Also try plain JSON blocks
    match = re.search(r'{.*}', text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(0))
        except json.JSONDecodeError:
            pass
    return None


def parse_llm_response(response_text):
    """Try to extract JSON from LLM response, handling Markdown blocks."""
    # Try direct JSON parsing first
    try:
        return json.loads(response_text)
    except (json.JSONDecodeError, ValueError):
        pass
    
    # Try to extract JSON from Markdown blocks
    extracted = _extract_json_from_markdown(response_text)
    if extracted:
        return extracted
    
    # Fallback: try to extract the first JSON object if the model wrapped it in prose
    try:
        start = response_text.find('{')
        end = response_text.rfind('}') + 1
        if start >= 0 and end > start:
            json_str = response_text[start:end]
            return json.loads(json_str)
    except (json.JSONDecodeError, ValueError):
        pass
    
    return None


def _fallback_analysis(dataset_name, description, custom_question, error_message):
    return {
        "interpretation": {
            "summary": f"Automatic fallback: LLM response failed for {dataset_name}.",
            "key_entities": [],
            "relationships": [],
        },
        "quality_assessment": {
            "issues": [f"LLM generation failed: {error_message}"],
            "completeness": "Could not assess",
            "consistency": "Could not assess",
        },
        "improvement_areas": [],
        "resolution_steps": [
            {
                "issue": "LLM response unavailable",
                "steps": [
                    "Check Ollama service status",
                    "Retry with a smaller model like qwen2.5:7b",
                    "Re-run llm_analyzer.py for the failed dataset",
                ],
                "effort": "low",
            }
        ],
        "custom_analysis": {
            "question": custom_question or "No custom question provided",
            "answer": "No answer generated because the LLM call failed.",
            "reasoning": "The generation timed out or returned an invalid response.",
            "supporting_evidence": [],
        },
        "dataset": dataset_name,
        "description": description,
    }

def generate_enhanced_analysis(
    dataset_name,
    json_path,
    model="qwen2.5:14b-instruct",
    max_retries=1,
    temperature=0.2,
    top_p=0.9,
    num_predict=1400,
    debug=False,
    dataset_config_path=None,
):
    """Generate enhanced analysis for a dataset."""
    _debug_log(debug, "Stage 1/6: loading dataset config")
    config = load_dataset_config(dataset_config_path)
    description, custom_question = _context_for_table(config, dataset_name)

    _debug_log(debug, f"Stage 2/6: loading input JSON from {json_path}")
    if not os.path.exists(json_path):
        print(f"JSON file not found: {json_path}")
        return None

    with open(json_path, "r", encoding="utf-8") as f:
        cleaned_json = json.load(f)

    print(f"\n[ANALYZE] Analyzing {dataset_name}...")
    print(f"   Context: {description or 'N/A'}")
    print(f"   Question: {custom_question or 'N/A'}")
    print(f"   Generation: temperature={temperature}, top_p={top_p}, num_predict={num_predict}")

    if analyze_table_with_langgraph is not None:
        _debug_log(debug, "Stage 3/6: running controlled LangGraph workflow")
        result = analyze_table_with_langgraph(
            table_name=dataset_name,
            sweetviz_data=cleaned_json,
            llm_call=call_ollama,
            response_parser=parse_llm_response,
            model=model,
            temperature=temperature,
            top_p=top_p,
            num_predict=num_predict,
            description=description or "",
            custom_question=custom_question or "",
            debug=debug,
            max_attempts=max_retries + 1,
        )
        print(f"   [OK] Analysis complete")
        return result.get("analysis")

    _debug_log(debug, "Stage 3/6: building prompt payload")
    prompt = build_enhanced_prompt(dataset_name, cleaned_json, description, custom_question)
    _debug_log(debug, f"Prompt length={len(prompt)} chars")

    _debug_log(debug, "Stage 4/6: calling Ollama generate endpoint")
    print(f"   Calling LLM...")
    response = None
    for attempt in range(1, max_retries + 2):
        response = call_ollama(
            prompt,
            model=model,
            temperature=temperature,
            top_p=top_p,
            num_predict=num_predict,
            debug=debug,
        )
        if response:
            break
        print(f"   Retry {attempt}/{max_retries + 1} failed")

    if not response:
        print("   [FAIL] LLM analysis failed")
        return _fallback_analysis(dataset_name, description, custom_question, "no_response")

    _debug_log(debug, "Stage 5/6: parsing model response")
    analysis = parse_llm_response(response)

    if not analysis:
        _debug_log(debug, "Response parse failed, using fallback invalid_json payload")
        analysis = _fallback_analysis(dataset_name, description, custom_question, "invalid_json")
        analysis["raw_response"] = response

    _debug_log(debug, "Stage 6/6: analysis object ready")
    print(f"   [OK] Analysis complete")
    return analysis

def process_all_datasets(
    skip_existing=True,
    model="qwen2.5:14b-instruct",
    dataset_name=None,
    temperature=0.2,
    top_p=0.9,
    num_predict=1400,
    debug=False,
    max_workers=None,
):
    """Process all datasets in sweetviz_extracted with controlled parallel LLM calls."""

    extracted_dir = Path("sweetviz_extracted")
    _debug_log(debug, f"Scanning datasets under {extracted_dir}")

    if not extracted_dir.exists():
        print("sweetviz_extracted directory not found")
        return

    dataset_dirs = []

    for dataset_dir in sorted(extracted_dir.iterdir()):
        if not dataset_dir.is_dir():
            continue

        current_dataset = dataset_dir.name

        if dataset_name and current_dataset != dataset_name:
            continue

        json_path = dataset_dir / "llm_dq_report.json"

        if not json_path.exists():
            continue

        dataset_dirs.append(dataset_dir)

    total = len(dataset_dirs)
    successful = 0
    skipped = 0
    failed = 0

    if max_workers is None:
        from dq_pipeline.config.settings import settings
        max_workers = settings.LLM_ANALYZER_MAX_WORKERS
    max_workers = max(1, int(max_workers))

    print(f"\nFound {total} dataset(s) to analyze")
    print(f"Parallel workers: {max_workers}")

    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = [
            executor.submit(
                process_one_dataset,
                dataset_dir,
                skip_existing,
                model,
                temperature,
                top_p,
                num_predict,
                debug,
            )
            for dataset_dir in dataset_dirs
        ]

        for future in as_completed(futures):
            try:
                dataset, status, ok = future.result()

                if status == "skipped":
                    skipped += 1
                elif ok:
                    successful += 1
                else:
                    failed += 1

                print(f"[{status.upper()}] {dataset}")

            except Exception as exc:
                failed += 1
                print(f"[ERROR] Dataset task failed: {exc}")

    print(f"\n{'='*60}")
    print(f"Enhancement complete: {successful}/{total} datasets analyzed")
    print(f"Skipped: {skipped}")
    print(f"Failed: {failed}")
    print(f"{'='*60}")  

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate enhanced LLM analysis JSON files.")
    parser.add_argument("--model", default="qwen2.5:14b-instruct", help="Ollama model name")
    parser.add_argument("--dataset", default=None, help="Optional dataset folder name under sweetviz_extracted")
    parser.add_argument("--no-skip-existing", action="store_true", help="Rebuild analysis even if output file already exists")
    parser.add_argument("--temperature", type=float, default=0.2, help="Generation temperature for more factual analysis")
    parser.add_argument("--top-p", type=float, default=0.9, help="Nucleus sampling top-p")
    parser.add_argument("--num-predict", type=int, default=1400, help="Maximum tokens to generate")
    parser.add_argument("--max-workers", type=int, default=2, help="Number of parallel LLM requests")
    args = parser.parse_args()

    process_all_datasets(
        skip_existing=not args.no_skip_existing,
        model=args.model,
        dataset_name=args.dataset,
        temperature=args.temperature,
        top_p=args.top_p,
        num_predict=args.num_predict,
        max_workers=args.max_workers,
    )
