"""Global (portfolio-level) LLM analysis across all tables of a dataset version.

Sends one compact payload built by the pipeline from the table results and asks the
LLM for a cross-table interpretation.
"""

import json

import requests

from dq_pipeline.analyzers.langgraph_analyzer import LlmUnavailableError


def call_ollama(
    prompt,
    model=None,
    temperature=0.6,
    top_p=0.9,
    num_predict=None,
    timeout_seconds=420,
    ollama_url=None,
):
    """Return the raw response text. Raises LlmUnavailableError when Ollama cannot answer."""
    from dq_pipeline.config.settings import settings

    options = {
        "temperature": temperature,
        "top_p": top_p,
    }
    if num_predict is not None:
        options["num_predict"] = num_predict
    if settings.LLM_NUM_CTX:
        options["num_ctx"] = settings.LLM_NUM_CTX

    base_url = str(ollama_url or settings.OLLAMA_URL or "http://localhost:11434").rstrip("/")
    model = model or settings.OLLAMA_MODEL
    try:
        response = requests.post(
            f"{base_url}/api/generate",
            json={
                "model": model,
                "prompt": prompt,
                "stream": False,
                "format": "json",
                "think": bool(settings.LLM_THINK),
                "options": options,
            },
            timeout=(20, timeout_seconds),
        )
    except requests.exceptions.ConnectionError as exc:
        raise LlmUnavailableError(f"Ollama is not reachable at {base_url}") from exc
    except requests.exceptions.Timeout as exc:
        raise LlmUnavailableError(f"Ollama did not answer within {timeout_seconds}s") from exc
    except requests.exceptions.RequestException as exc:
        raise LlmUnavailableError(f"Ollama request failed: {exc}") from exc

    if response.status_code != 200:
        detail = response.text[:300] if response.text else "(no detail)"
        raise LlmUnavailableError(f"Ollama returned HTTP {response.status_code}: {detail}")

    payload = response.json()
    text = str(payload.get("response") or payload.get("thinking") or "").strip()
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

When the payload has business_context (domain, objective, row_grain), rate every
risk by that objective and read repeated identifiers against what one row stands for.

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
    model=None,
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
