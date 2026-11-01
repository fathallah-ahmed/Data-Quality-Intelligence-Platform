"""
Per-table LLM analysis: context loading, the Ollama call and response parsing.

The controlled generate -> validate -> repair -> fallback workflow lives in
``langgraph_analyzer``; this module wires it to Ollama and the dataset context.
"""
from datetime import datetime
import json
import os
from pathlib import Path
import re
import sys
import time

import pandas as pd
import requests

from dq_pipeline.analyzers.langgraph_analyzer import (
    LLM_STATUS_DISABLED,
    LlmUnavailableError,
    analyze_table_with_langgraph,
)


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


def describe_business_context(business_context):
    """One paragraph for the LLM prompt from the domain, objective and row grain the user gave."""
    context = business_context or {}
    parts = []
    if context.get("domain"):
        parts.append(f"Business domain: {context['domain']}.")
    if context.get("objective"):
        parts.append(f"Goal of the analysis: {context['objective']}.")
    if context.get("row_grain"):
        parts.append(
            f"One row represents: {context['row_grain']}. "
            "A value that repeats across rows is only a problem when it contradicts this."
        )
    return " ".join(parts)


def analyze_table_metrics(
    table_name: str,
    sweetviz_data: dict,
    ollama_url: str = None,
    model: str = None,
    temperature: float = 0.2,
    top_p: float = 0.9,
    num_predict: int = None,
    debug: bool = False,
    dataset_config_path: str = None,
    business_context: dict = None,
) -> dict:
    """
    Analyze a single table from its profiling metrics.

    Returns a dict with ``analysis`` (always present, deterministic when the LLM
    cannot help), ``model`` and ``llm_status``: ``ok`` (validated LLM answer),
    ``unavailable`` (Ollama unreachable) or ``invalid`` (answer rejected).
    """
    if sweetviz_data.get("status") == "failed":
        return {"status": "skipped", "reason": "sweetviz_failed", "llm_status": LLM_STATUS_DISABLED}

    if num_predict is None:
        from dq_pipeline.config.settings import settings

        num_predict = settings.TABLE_LLM_NUM_PREDICT

    config = load_dataset_config(dataset_config_path)
    description, custom_question = _context_for_table(config, table_name)
    business = describe_business_context(business_context)
    if business:
        description = f"{business}\n{description}" if description else business
    _debug_log(debug, f"{table_name}: running controlled LLM workflow")
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


def call_ollama(
    prompt,
    model=None,
    temperature=0.7,
    top_p=0.95,
    num_predict=None,
    debug=False,
    ollama_url=None,
    system=None,
    format_schema=None,
):
    """Call the Ollama generate API (non-streaming) and return the response text.

    Raises ``LlmUnavailableError`` when Ollama cannot be reached or answers with an
    HTTP error, so callers can tell "LLM down" apart from "LLM answered badly".
    """
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
    api_url = f"{base_url}/api/generate"
    model = model or settings.OLLAMA_MODEL
    _debug_log(debug, f"Calling Ollama: model={model}, temperature={temperature}, top_p={top_p}, num_predict={num_predict}, structured={format_schema is not None}")
    request_payload = {
        "model": model,
        "prompt": prompt,
        "stream": False,
        "format": format_schema or "json",
        "think": bool(settings.LLM_THINK),
        "options": options,
    }
    if system:
        request_payload["system"] = system

    request_start = time.monotonic()
    try:
        response = requests.post(api_url, json=request_payload, timeout=(15, settings.TABLE_LLM_TIMEOUT_SECONDS))
        if response.status_code != 200 and format_schema is not None:
            _debug_log(debug, "Structured schema format failed; retrying with plain JSON mode")
            request_payload["format"] = "json"
            response = requests.post(api_url, json=request_payload, timeout=(15, settings.TABLE_LLM_TIMEOUT_SECONDS))
    except requests.exceptions.ConnectionError as exc:
        raise LlmUnavailableError(f"Ollama is not reachable at {base_url}") from exc
    except requests.exceptions.Timeout as exc:
        raise LlmUnavailableError(f"Ollama did not answer in time (model {model})") from exc
    except requests.exceptions.RequestException as exc:
        raise LlmUnavailableError(f"Ollama request failed: {exc}") from exc

    _debug_log(debug, f"HTTP response received in {time.monotonic() - request_start:.2f}s with status={response.status_code}")
    if response.status_code != 200:
        detail = response.text[:300] if response.text else "(no detail)"
        raise LlmUnavailableError(f"Ollama returned HTTP {response.status_code}: {detail}")

    payload = response.json()
    return str(payload.get("response") or payload.get("thinking") or "").strip()


def _extract_json_from_markdown(text):
    """Try to extract JSON block from Markdown response (e.g., ```json {...} ```)."""
    match = re.search(r'```json\s*\n?({.*?})\s*\n?```', text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except json.JSONDecodeError:
            pass
    match = re.search(r'{.*}', text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(0))
        except json.JSONDecodeError:
            pass
    return None


def parse_llm_response(response_text):
    """Try to extract JSON from LLM response, handling Markdown blocks."""
    try:
        return json.loads(response_text)
    except (json.JSONDecodeError, ValueError, TypeError):
        pass

    extracted = _extract_json_from_markdown(response_text or "")
    if extracted:
        return extracted

    try:
        start = response_text.find('{')
        end = response_text.rfind('}') + 1
        if start >= 0 and end > start:
            return json.loads(response_text[start:end])
    except (json.JSONDecodeError, ValueError, AttributeError):
        pass

    return None
