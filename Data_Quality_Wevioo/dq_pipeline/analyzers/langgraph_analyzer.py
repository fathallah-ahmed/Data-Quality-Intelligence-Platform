"""Controlled LangGraph workflow for table-level LLM analysis.

The graph keeps source data read-only. It receives already extracted profiling
metrics, builds a compact evidence payload, asks the LLM for an analysis,
validates the answer deterministically, repairs invalid answers when possible,
and falls back to a deterministic analysis when the LLM remains unreliable.
"""

from __future__ import annotations

from copy import deepcopy
import json
import re
from typing import Any, Callable, Dict, List, Optional, Tuple, TypedDict

try:
    from langgraph.graph import END, START, StateGraph

    LANGGRAPH_AVAILABLE = True
except Exception:  # pragma: no cover - exercised only before dependency install
    END = START = StateGraph = None
    LANGGRAPH_AVAILABLE = False


LlmCaller = Callable[..., Optional[str]]
ResponseParser = Callable[[str], Optional[Dict[str, Any]]]


class LlmUnavailableError(RuntimeError):
    """Raised by an ``LlmCaller`` when the LLM service cannot be reached (not a bad answer)."""


# Values of the ``llm_status`` field returned with every analysis.
LLM_STATUS_OK = "ok"                    # validated LLM answer
LLM_STATUS_UNAVAILABLE = "unavailable"  # Ollama unreachable / HTTP error / timeout
LLM_STATUS_INVALID = "invalid"          # LLM answered but the answer was rejected
LLM_STATUS_DISABLED = "disabled"        # LLM not called (empty table, feature off)

ALLOWED_TABLE_FIELDS = {
    "table",
    "dataset",
    "schema",
    "all",
    "all_columns",
    "all columns",
    "rows",
    "columns",
    "global",
}
PLACEHOLDER_FIELDS = {
    "",
    "...",
    "field",
    "column",
    "columns",
    "critical",
    "unknown",
    "n/a",
    "na",
    "none",
    "missingness_col1",
    "missingness_col2",
    "missingness col1",
    "missingness col2",
    "col1",
    "col2",
    "example",
}
FIELD_KEYS = {
    "field",
    "fields",
    "column",
    "columns",
    "feature",
    "features",
    "key_field",
    "source_field",
    "target_field",
}
MISSINGNESS_RE = re.compile(
    r"\b(missing|missingness|null|nulls|empty|blank|vide|vides|manquant|manquante|manquantes)\b",
    re.IGNORECASE,
)
COLUMN_CLAIM_RE = re.compile(
    r"(?:^|[.;:,\n]\s*|[\[`'\"])\s*([A-Za-z_][A-Za-z0-9_.\- ]{1,80}?)\s*"
    r"(?:[\]`'\"])?\s+"
    r"(?:has|have|contains|contain|shows|show|is|are|presente|contient|contiennent|a|ont)\b"
    r"[^.;\n]{0,160}?"
    r"(?:missing|missingness|null|nulls|empty|blank|vide|vides|manquant|manquante|manquantes|\d+(?:[\.,]\d+)?\s*%)",
    re.IGNORECASE,
)
MISSINGNESS_FOR_COLUMN_RE = re.compile(
    r"(?:missing|missingness|null|nulls|empty|blank|vide|vides|manquant|manquante|manquantes)"
    r"[^.;\n]{0,120}?\b(?:in|for|on|dans|pour|sur)\s+[`'\"]?([A-Za-z_][A-Za-z0-9_.\- ]{1,80})[`'\"]?",
    re.IGNORECASE,
)
HIGH_SEVERITY_RE = re.compile(
    r"\b(high|critical|severe|major|elevated|important|fort|forte|critique|elevee|eleve)\b",
    re.IGNORECASE,
)
PERCENT_RE = re.compile(r"(\d+(?:[\.,]\d+)?)\s*%")
POSITIVE_PERCENT_RE = re.compile(
    r"\s*(?:of\s+\w+\s+)?(?:complete|completeness|populated|filled|present|unique|distinct|valid|non[- ]?missing|non[- ]?null)",
    re.IGNORECASE,
)
DANGEROUS_DROP_RE = re.compile(
    r"\b(drop|delete|remove|supprimer|effacer)\b.{0,35}\b(column|field|champ|colonne)\b|"
    r"\b(column|field|champ|colonne)\b.{0,35}\b(drop|delete|remove|supprimer|effacer)\b",
    re.IGNORECASE,
)
GENERIC_METRIC_SUBJECTS = {
    "it",
    "it includes",
    "this table",
    "the table",
    "table",
    "dataset",
    "evidence",
    "the evidence",
    "both columns",
    "all columns",
    "most columns",
    "many columns",
    "some columns",
    "each column",
    "every column",
    "no column",
    "no columns",
    "these columns",
    "the columns",
}
CONNECTOR_WORDS = {
    "and",
    "or",
    "both",
    "the",
    "this",
    "these",
    "columns",
    "column",
    "fields",
    "field",
    "for",
    "only",
    "top",
    "values",
    "top values",
    "top_values",
}


ANALYSIS_SYSTEM_PROMPT = """You are a strict data quality analyst.
Use only the provided evidence and exact allowed column names.
Do not invent columns, relationships, percentages, business facts, or joins.
Do not recalculate missingness, duplicate counts, type counts, or row counts; those facts are computed deterministically.
Return a single JSON object that matches the provided schema."""


ANALYSIS_RESPONSE_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": [
        "interpretation",
        "quality_assessment",
        "improvement_areas",
        "resolution_steps",
        "custom_analysis",
        "semantic_analysis",
    ],
    "properties": {
        "interpretation": {
            "type": "object",
            "additionalProperties": False,
            "required": ["summary", "key_entities", "relationships"],
            "properties": {
                "summary": {"type": "string"},
                "key_entities": {"type": "array", "items": {"type": "string"}},
                "relationships": {"type": "array", "items": {"type": "string"}},
            },
        },
        "quality_assessment": {
            "type": "object",
            "additionalProperties": False,
            "required": ["issues", "completeness", "consistency"],
            "properties": {
                "issues": {"type": "array", "items": {"type": "string"}},
                "completeness": {"type": "string"},
                "consistency": {"type": "string"},
            },
        },
        "improvement_areas": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["field", "issue", "impact", "priority"],
                "properties": {
                    "field": {"type": "string"},
                    "issue": {"type": "string"},
                    "impact": {"type": "string", "enum": ["high", "medium", "low"]},
                    "priority": {"type": "integer"},
                },
            },
        },
        "resolution_steps": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["field", "issue", "steps", "effort"],
                "properties": {
                    "field": {"type": "string"},
                    "issue": {"type": "string"},
                    "steps": {"type": "array", "items": {"type": "string"}},
                    "effort": {"type": "string", "enum": ["low", "medium", "high"]},
                },
            },
        },
        "custom_analysis": {
            "type": "object",
            "additionalProperties": False,
            "required": ["question", "answer", "reasoning", "supporting_evidence"],
            "properties": {
                "question": {"type": "string"},
                "answer": {"type": "string"},
                "reasoning": {"type": "string"},
                "supporting_evidence": {"type": "array", "items": {"type": "string"}},
            },
        },
        "semantic_analysis": {
            "type": "object",
            "additionalProperties": False,
            "required": [
                "executive_summary",
                "column_roles",
                "relationship_hypotheses",
                "root_cause_hypotheses",
                "downstream_impacts",
                "data_contract_rules",
                "monitoring_rules",
                "data_owner_questions",
                "use_case_readiness",
            ],
            "properties": {
                "executive_summary": {"type": "string"},
                "column_roles": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["column", "role", "description", "business_meaning", "usage_notes"],
                        "properties": {
                            "column": {"type": "string"},
                            "role": {"type": "string"},
                            "description": {"type": "string"},
                            "business_meaning": {"type": "string"},
                            "usage_notes": {"type": "string"},
                        },
                    },
                },
                "relationship_hypotheses": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["source_column", "target_table", "target_column", "confidence", "reason"],
                        "properties": {
                            "source_column": {"type": "string"},
                            "target_table": {"type": "string"},
                            "target_column": {"type": "string"},
                            "confidence": {"type": "string", "enum": ["high", "medium", "low"]},
                            "reason": {"type": "string"},
                        },
                    },
                },
                "root_cause_hypotheses": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["field", "hypothesis", "evidence", "priority"],
                        "properties": {
                            "field": {"type": "string"},
                            "hypothesis": {"type": "string"},
                            "evidence": {"type": "string"},
                            "priority": {"type": "integer"},
                        },
                    },
                },
                "downstream_impacts": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["area", "impact", "severity"],
                        "properties": {
                            "area": {"type": "string"},
                            "impact": {"type": "string"},
                            "severity": {"type": "string", "enum": ["high", "medium", "low"]},
                        },
                    },
                },
                "data_contract_rules": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["field", "rule", "severity"],
                        "properties": {
                            "field": {"type": "string"},
                            "rule": {"type": "string"},
                            "severity": {"type": "string", "enum": ["high", "medium", "low"]},
                        },
                    },
                },
                "monitoring_rules": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["field", "check", "threshold", "cadence"],
                        "properties": {
                            "field": {"type": "string"},
                            "check": {"type": "string"},
                            "threshold": {"type": "string"},
                            "cadence": {"type": "string"},
                        },
                    },
                },
                "data_owner_questions": {"type": "array", "items": {"type": "string"}},
                "use_case_readiness": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["use_case", "status", "rationale"],
                        "properties": {
                            "use_case": {"type": "string"},
                            "status": {"type": "string", "enum": ["ready", "review", "not_ready"]},
                            "rationale": {"type": "string"},
                        },
                    },
                },
            },
        },
    },
}


class TableGraphState(TypedDict, total=False):
    table_name: str
    sweetviz_data: Dict[str, Any]
    description: str
    custom_question: str
    model: str
    temperature: float
    top_p: float
    num_predict: Optional[int]
    ollama_url: Optional[str]
    debug: bool
    max_attempts: int
    attempts: int
    evidence: Dict[str, Any]
    prompt: str
    raw_response: Optional[str]
    parsed_response: Optional[Dict[str, Any]]
    analysis: Dict[str, Any]
    validation_errors: List[str]
    validation_warnings: List[str]
    llm_response_valid: bool
    llm_unavailable: bool
    fallback_used: bool
    fallback_reason: str


def analyze_table_with_langgraph(
    *,
    table_name: str,
    sweetviz_data: Dict[str, Any],
    llm_call: LlmCaller,
    response_parser: ResponseParser,
    ollama_url: Optional[str] = None,
    model: Optional[str] = None,
    temperature: float = 0.2,
    top_p: float = 0.9,
    num_predict: Optional[int] = 1400,
    description: str = "",
    custom_question: str = "",
    debug: bool = False,
    max_attempts: int = 2,
) -> Dict[str, Any]:
    """Run the controlled table-analysis graph and return pipeline result shape."""

    initial: TableGraphState = {
        "table_name": table_name,
        "sweetviz_data": sweetviz_data or {},
        "description": description or "",
        "custom_question": custom_question or "",
        "model": model,
        "temperature": _bounded_float(temperature, default=0.2, maximum=0.25),
        "top_p": _bounded_float(top_p, default=0.9, maximum=0.9),
        "num_predict": num_predict,
        "ollama_url": ollama_url,
        "debug": bool(debug),
        "max_attempts": max(1, int(max_attempts or 2)),
        "attempts": 0,
        "validation_errors": [],
        "validation_warnings": [],
        "fallback_used": False,
        "llm_response_valid": False,
        "llm_unavailable": False,
    }

    if LANGGRAPH_AVAILABLE:
        graph = _build_graph(llm_call, response_parser)
        final_state = graph.invoke(initial)
    else:
        final_state = _run_controlled_workflow_without_langgraph(
            initial,
            llm_call=llm_call,
            response_parser=response_parser,
        )

    analysis = final_state.get("analysis") or _fallback_analysis_from_evidence(
        final_state.get("evidence") or _build_table_evidence(table_name, sweetviz_data or {}),
        description=description,
        custom_question=custom_question,
        reason="analysis_missing",
    )

    if final_state.get("llm_unavailable"):
        llm_status = LLM_STATUS_UNAVAILABLE
    elif final_state.get("llm_response_valid"):
        llm_status = LLM_STATUS_OK
    else:
        llm_status = LLM_STATUS_INVALID

    return {
        "status": "completed",
        "table_name": table_name,
        "model": model,
        "analysis": analysis,
        "llm_status": llm_status,
    }


def _build_graph(llm_call: LlmCaller, response_parser: ResponseParser):
    builder = StateGraph(TableGraphState)
    builder.add_node("build_context", _build_context_node)
    builder.add_node("generate", lambda state: _generate_node(state, llm_call))
    builder.add_node("validate", lambda state: _validate_node(state, response_parser))
    builder.add_node("repair", lambda state: _repair_node(state, llm_call))
    builder.add_node("fallback", _fallback_node)
    builder.add_node("finalize", _finalize_node)

    builder.add_edge(START, "build_context")
    builder.add_edge("build_context", "generate")
    builder.add_edge("generate", "validate")
    builder.add_conditional_edges(
        "validate",
        _route_after_validation,
        {
            "finalize": "finalize",
            "repair": "repair",
            "fallback": "fallback",
        },
    )
    builder.add_edge("repair", "validate")
    builder.add_edge("fallback", "finalize")
    builder.add_edge("finalize", END)
    return builder.compile()


def _run_controlled_workflow_without_langgraph(
    state: TableGraphState,
    *,
    llm_call: LlmCaller,
    response_parser: ResponseParser,
) -> TableGraphState:
    """Keep the same workflow available before the Docker image is rebuilt."""

    state = dict(state)
    state.update(_build_context_node(state))
    state.update(_generate_node(state, llm_call))

    while True:
        state.update(_validate_node(state, response_parser))
        route = _route_after_validation(state)
        if route == "repair":
            state.update(_repair_node(state, llm_call))
            continue
        if route == "fallback":
            state.update(_fallback_node(state))
        break

    state.update(_finalize_node(state))
    return state


def _build_context_node(state: TableGraphState) -> Dict[str, Any]:
    evidence = _build_table_evidence(state["table_name"], state.get("sweetviz_data") or {})
    prompt = _build_analysis_prompt(
        table_name=state["table_name"],
        evidence=evidence,
        description=state.get("description") or "",
        custom_question=state.get("custom_question") or "",
    )
    return {
        "evidence": evidence,
        "prompt": prompt,
        "validation_errors": [],
        "validation_warnings": [],
    }


def _call_llm(llm_call: LlmCaller, prompt: str, **kwargs: Any) -> Dict[str, Any]:
    """Call the LLM and record whether the service itself was unavailable."""
    try:
        return {"raw_response": llm_call(prompt, **kwargs), "llm_unavailable": False}
    except LlmUnavailableError as exc:
        return {"raw_response": None, "llm_unavailable": True, "fallback_reason": str(exc)}


def _generate_node(state: TableGraphState, llm_call: LlmCaller) -> Dict[str, Any]:
    result = _call_llm(
        llm_call,
        state["prompt"],
        model=state["model"],
        temperature=state.get("temperature", 0.2),
        top_p=state.get("top_p", 0.9),
        num_predict=state.get("num_predict"),
        debug=state.get("debug", False),
        ollama_url=state.get("ollama_url"),
        system=ANALYSIS_SYSTEM_PROMPT,
        format_schema=ANALYSIS_RESPONSE_SCHEMA,
    )
    result["attempts"] = int(state.get("attempts") or 0) + 1
    return result


def _validate_node(
    state: TableGraphState,
    response_parser: ResponseParser,
) -> Dict[str, Any]:
    raw_response = state.get("raw_response")
    if not raw_response:
        return {
            "parsed_response": None,
            "validation_errors": ["LLM returned an empty response"],
            "validation_warnings": [],
            "llm_response_valid": False,
        }

    parsed = response_parser(raw_response)
    if not isinstance(parsed, dict):
        return {
            "parsed_response": None,
            "validation_errors": ["LLM response is not valid JSON"],
            "validation_warnings": [],
            "llm_response_valid": False,
        }

    normalized, errors, warnings = _validate_and_normalize_analysis(
        parsed,
        state["evidence"],
    )
    if state.get("debug") and errors:
        table_name = state.get("table_name", "table")
        preview = "; ".join(errors[:5])
        print(f"[DEBUG] {table_name}: LLM validation failed: {preview}")
    elif state.get("debug"):
        table_name = state.get("table_name", "table")
        print(f"[DEBUG] {table_name}: LLM validation passed")
    return {
        "parsed_response": parsed,
        "analysis": normalized if not errors else parsed,
        "validation_errors": errors,
        "validation_warnings": warnings,
        "llm_response_valid": not errors,
    }


def _repair_node(state: TableGraphState, llm_call: LlmCaller) -> Dict[str, Any]:
    repair_prompt = _build_repair_prompt(state)
    result = _call_llm(
        llm_call,
        repair_prompt,
        model=state["model"],
        temperature=0.1,
        top_p=0.85,
        num_predict=state.get("num_predict"),
        debug=state.get("debug", False),
        ollama_url=state.get("ollama_url"),
        system=ANALYSIS_SYSTEM_PROMPT,
        format_schema=ANALYSIS_RESPONSE_SCHEMA,
    )
    result["attempts"] = int(state.get("attempts") or 0) + 1
    return result


def _fallback_node(state: TableGraphState) -> Dict[str, Any]:
    if state.get("llm_unavailable"):
        reason = state.get("fallback_reason") or "LLM service unavailable"
    else:
        reason = "; ".join(state.get("validation_errors") or ["validation_failed"])
    analysis = _fallback_analysis_from_evidence(
        state["evidence"],
        description=state.get("description") or "",
        custom_question=state.get("custom_question") or "",
        reason=reason,
        llm_unavailable=bool(state.get("llm_unavailable")),
    )
    return {
        "analysis": analysis,
        "fallback_used": True,
        "fallback_reason": reason,
        "llm_response_valid": False,
    }


def _finalize_node(state: TableGraphState) -> Dict[str, Any]:
    analysis = deepcopy(state.get("analysis") or {})
    analysis["_validation"] = {
        "workflow": "langgraph" if LANGGRAPH_AVAILABLE else "python_controlled_graph",
        "llm_response_valid": bool(state.get("llm_response_valid")),
        "llm_unavailable": bool(state.get("llm_unavailable")),
        "fallback_used": bool(state.get("fallback_used")),
        "attempts": int(state.get("attempts") or 0),
        "errors": state.get("validation_errors") or [],
        "warnings": state.get("validation_warnings") or [],
        "allowed_columns": (state.get("evidence") or {}).get("allowed_columns", []),
    }
    if state.get("fallback_reason"):
        analysis["_validation"]["fallback_reason"] = state["fallback_reason"]
    return {"analysis": analysis}


def _route_after_validation(state: TableGraphState) -> str:
    if state.get("llm_unavailable"):
        # Retrying a repair prompt against a service that is down only adds latency.
        return "fallback"
    if not state.get("validation_errors"):
        return "finalize"
    if int(state.get("attempts") or 0) < int(state.get("max_attempts") or 2):
        return "repair"
    return "fallback"


def _build_table_evidence(table_name: str, sweetviz_data: Dict[str, Any]) -> Dict[str, Any]:
    overview = _extract_overview(sweetviz_data)
    columns = []
    for feature in _iter_features(sweetviz_data):
        column = _column_evidence(feature, overview)
        if column["name"]:
            columns.append(column)

    allowed_columns = [column["name"] for column in columns]
    column_map = {_normalize_name(column["name"]): column for column in columns}
    type_counts: Dict[str, int] = {}
    for column in columns:
        dtype = str(column.get("data_type") or "unknown")
        type_counts[dtype] = type_counts.get(dtype, 0) + 1

    high_missing = [
        {
            "column": column["name"],
            "missing_percent": column["missing_percent"],
            "missing_count": column["missing_count"],
        }
        for column in columns
        if (column.get("missing_percent") or 0) >= 50
    ]
    high_missing.sort(key=lambda item: item["missing_percent"], reverse=True)

    missing_columns = [
        {
            "column": column["name"],
            "missing_percent": column["missing_percent"],
            "missing_count": column["missing_count"],
        }
        for column in columns
        if (column.get("missing_count") or 0) > 0
    ]
    missing_columns.sort(key=lambda item: item["missing_percent"], reverse=True)

    id_like_columns = [
        column["name"]
        for column in columns
        if bool(column.get("is_id_like"))
    ]

    associations = _extract_top_associations(sweetviz_data, allowed_columns)

    evidence = {
        "table_name": table_name,
        "overview": overview,
        "parser_info": sweetviz_data.get("csv_reader") or {},
        "allowed_columns": allowed_columns,
        "column_map": column_map,
        "columns": columns,
        "type_counts": type_counts,
        "missing_columns": missing_columns[:30],
        "high_missing_columns": high_missing[:20],
        "id_like_columns": id_like_columns[:30],
        "top_associations": associations[:20],
    }
    evidence["deterministic_quality"] = _deterministic_quality_from_evidence(evidence)
    return evidence


def _extract_overview(sweetviz_data: Dict[str, Any]) -> Dict[str, Any]:
    source = sweetviz_data.get("summary_source")
    if not source:
        source = (sweetviz_data.get("dataset_overview") or {}).get("source") or {}

    duplicates_number, duplicates_percent = _extract_number_percent(
        source.get("duplicates"),
        total=source.get("num_rows"),
    )

    return {
        "rows": _safe_int(source.get("num_rows")),
        "columns": _safe_int(source.get("num_columns")),
        "duplicates": duplicates_number,
        "duplicate_percent": duplicates_percent,
        "num_categorical": _safe_int(source.get("num_cat")),
        "num_numerical": _safe_int(source.get("num_numerical")),
        "num_text": _safe_int(source.get("num_text")),
    }


def _iter_features(sweetviz_data: Dict[str, Any]):
    features = sweetviz_data.get("features") or []
    if isinstance(features, dict):
        for feature_name, feature in features.items():
            if isinstance(feature, dict):
                payload = dict(feature)
                payload.setdefault("feature_name", feature_name)
                payload.setdefault("name", feature_name)
                yield payload
        return

    if isinstance(features, list):
        for feature in features:
            if isinstance(feature, dict):
                payload = dict(feature)
                payload.setdefault("name", payload.get("feature_name"))
                yield payload


def _column_evidence(feature: Dict[str, Any], overview: Dict[str, Any]) -> Dict[str, Any]:
    name = str(feature.get("feature_name") or feature.get("name") or "").strip()
    base_stats = feature.get("base_stats") or {}
    total_rows = _safe_int(base_stats.get("total_rows")) or _safe_int(overview.get("rows"))
    missing_count, missing_percent = _extract_number_percent(
        base_stats.get("num_missing"),
        total=total_rows,
    )
    distinct_count, distinct_percent = _extract_number_percent(
        base_stats.get("num_distinct"),
        total=total_rows,
    )

    data_type = feature.get("data_type") or feature.get("type") or "unknown"
    is_id_like = feature.get("is_id_like")
    if is_id_like is None:
        is_id_like = _guess_id_like(name, distinct_count, total_rows)

    return {
        "name": name,
        "data_type": data_type,
        "missing_count": missing_count,
        "missing_percent": missing_percent,
        "distinct_count": distinct_count,
        "distinct_percent": distinct_percent,
        "is_id_like": bool(is_id_like),
        "is_date_like": bool(feature.get("is_date_like") or _looks_date_like(name, data_type)),
        "numeric_stats": _numeric_stats(feature.get("stats") or {}),
        "top_values": _top_values(feature.get("detail") or {}),
    }


def _extract_number_percent(value: Any, total: Optional[int] = None) -> Tuple[int, float]:
    if isinstance(value, dict):
        number = _safe_int(value.get("number"))
        percent = _safe_float(value.get("perc"))
    else:
        number = _safe_int(value)
        percent = None

    if percent is None and total not in (None, 0):
        percent = (float(number or 0) / float(total)) * 100.0
    return int(number or 0), round(float(percent or 0.0), 4)


def _numeric_stats(stats: Dict[str, Any]) -> Dict[str, Any]:
    wanted = ["min", "max", "mean", "std", "perc25", "perc50", "perc75"]
    return {
        key: _safe_float(stats.get(key))
        for key in wanted
        if _safe_float(stats.get(key)) is not None
    }


def _top_values(detail: Dict[str, Any], limit: int = 5) -> List[Dict[str, Any]]:
    raw_values = detail.get("full_count") or detail.get("summary_count") or detail.get("frequent_values") or []
    values = []
    for item in raw_values[:limit]:
        if isinstance(item, dict):
            count = item.get("count")
            count_number, count_percent = _extract_number_percent(count)
            values.append(
                {
                    "value": _short_value(item.get("name")),
                    "count": count_number,
                    "percent": count_percent,
                }
            )
        elif isinstance(item, (list, tuple)) and item:
            count_number, count_percent = _extract_number_percent(item[1] if len(item) > 1 else None)
            values.append(
                {
                    "value": _short_value(item[0]),
                    "count": count_number,
                    "percent": count_percent,
                }
            )
    return values


def _extract_top_associations(
    sweetviz_data: Dict[str, Any],
    allowed_columns: List[str],
) -> List[Dict[str, Any]]:
    associations = sweetviz_data.get("associations")
    if isinstance(associations, dict) and "source" in associations:
        associations = associations.get("source")
    if not isinstance(associations, dict):
        return []

    allowed = {_normalize_name(column): column for column in allowed_columns}
    pairs = []
    seen = set()
    for left, targets in associations.items():
        if not isinstance(targets, dict):
            continue
        left_name = _canonical_column_name(str(left), allowed)
        if not left_name:
            continue
        for right, value in targets.items():
            right_name = _canonical_column_name(str(right), allowed)
            if not right_name or right_name == left_name:
                continue
            try:
                score = abs(float(value))
            except (TypeError, ValueError):
                continue
            key = tuple(sorted([left_name, right_name]))
            if key in seen:
                continue
            seen.add(key)
            pairs.append({"left": left_name, "right": right_name, "score": round(score, 4)})

    pairs.sort(key=lambda item: item["score"], reverse=True)
    return pairs


def _build_analysis_prompt(
    *,
    table_name: str,
    evidence: Dict[str, Any],
    description: str,
    custom_question: str,
) -> str:
    evidence_for_prompt = {
        key: value
        for key, value in evidence.items()
        if key != "column_map"
    }
    return f"""Analyze exactly one profiled table using only the evidence below.

TABLE: {table_name}
DOMAIN DESCRIPTION: {description or "Not provided"}
CUSTOM QUESTION: {custom_question or "Describe the table structure, risks, and next actions."}

HARD RULES:
- Use only these exact column names: {", ".join(evidence.get("allowed_columns") or [])}
- Do not invent columns, entities, percentages, joins, or business facts.
- Do not recalculate missingness, duplicates, row counts, type counts, or distinct counts.
- Treat EVIDENCE JSON.deterministic_quality as the source of truth for data-quality issues and remediation.
- Treat top_values as observed frequent values from profiling, not an exhaustive list of possible categories.
- If a recommendation is table-level, use field "table".
- If you cite a missingness percentage, it must match the evidence.
- Do not recommend dropping ID/key columns unless the evidence shows severe missingness or parsing artifacts.
- If the domain description says what one row represents, judge uniqueness against it: repeated identifiers are
  normal when a row is not the entity the identifier names. Rate every risk by the stated goal of the analysis.
- Return only the JSON object required by the API schema. No markdown or prose outside JSON.

RESPONSE SHAPE (use exactly these keys; the semantic keys go INSIDE "semantic_analysis"):
- interpretation: {{summary, key_entities[], relationships[]}}
- quality_assessment: {{issues[], completeness, consistency}}
- improvement_areas[]: {{field, issue, impact(high|medium|low), priority(int)}}
- resolution_steps[]: {{field, issue, steps[], effort(low|medium|high)}}
- custom_analysis: {{question, answer, reasoning, supporting_evidence[]}}
- semantic_analysis: {{executive_summary, column_roles[{{column, role, description, business_meaning, usage_notes}}],
  relationship_hypotheses[{{source_column, target_table, target_column, confidence, reason}}],
  root_cause_hypotheses[{{field, hypothesis, evidence, priority}}], downstream_impacts[{{area, impact, severity}}],
  data_contract_rules[{{field, rule, severity}}], monitoring_rules[{{field, check, threshold, cadence}}],
  data_owner_questions[strings], use_case_readiness[{{use_case, status(ready|review|not_ready), rationale}}]}}

SPECIFICITY RULES (the answer is rejected as useless if it reads like generic advice):
- Every sentence must name at least one exact column and quote a number or observed value from the evidence
  (row count, distinct count, min/max/mean, a top value with its share, an association score).
- Never write filler such as "ensure data quality", "validate the data" or "monitor regularly" without saying
  which column, which check and which threshold.
- column_roles: one entry per column. "description" says what the column holds using its data_type, distinct_count
  and top_values or numeric range; "business_meaning" says which decision or report uses it; "usage_notes" gives one
  concrete check (for example "10 distinct values, 62% in 'active': treat as a status code").
- data_contract_rules and monitoring_rules: give the column, an exact condition and a numeric threshold derived from
  the evidence (for example "customer_id: distinct_count must equal rows (1,445)"; "phone: alert above 90% missing").
- root_cause_hypotheses: link each one to a column with real missingness or an anomaly in the evidence, say what
  produces it in the source system, and mark it as a hypothesis.
- data_owner_questions: ask what only a person can answer (meaning of a code, why a column is empty, expected range),
  naming the column and the observed fact that triggered the question.
- use_case_readiness: name a concrete use case (join to a named table, monthly KPI, model training) and the exact
  columns that block or allow it.
- Answer the custom question in 2-4 sentences using exact figures; put the figures in supporting_evidence.
- If the evidence shows nothing wrong, say so with the numbers instead of inventing a risk.

TASKS:
- Explain what the table likely represents, based on table and column names.
- Add business context to the deterministic quality facts; do not create new metric findings.
- Describe every column's likely semantic role and practical usage.
- For categorical/text columns, mention observed top values only when useful; do not invent possible categories.
- Propose relationship hypotheses only when column names support them.
- Suggest root causes, downstream impacts, data contract rules, monitoring rules, data-owner questions, and use-case readiness.
- Recommend concrete, safe actions aligned with deterministic_quality.
- Answer the custom question using exact evidence.

EVIDENCE JSON:
{json.dumps(evidence_for_prompt, ensure_ascii=True, indent=2)}"""


def _build_repair_prompt(state: TableGraphState) -> str:
    evidence = state.get("evidence") or {}
    evidence_for_prompt = {
        key: value
        for key, value in evidence.items()
        if key != "column_map"
    }
    previous = state.get("parsed_response") or state.get("raw_response") or ""
    return f"""Your previous table analysis failed deterministic validation.

TABLE: {state.get("table_name")}
VALIDATION ERRORS:
{json.dumps(state.get("validation_errors") or [], ensure_ascii=True, indent=2)}

VALIDATION WARNINGS:
{json.dumps(state.get("validation_warnings") or [], ensure_ascii=True, indent=2)}

ALLOWED EVIDENCE:
{json.dumps(evidence_for_prompt, ensure_ascii=True, indent=2)}

PREVIOUS RESPONSE:
{json.dumps(previous, ensure_ascii=True, indent=2)}

Rewrite the complete answer as the JSON object required by the API schema.
Rules:
- Use only allowed columns.
- Remove placeholders such as CRITICAL, field, column, missingness_col1, or "...".
- Correct every percentage to match the evidence.
- Keep quality_assessment aligned with deterministic_quality; focus semantic_analysis on meaning, causes, impact, rules, questions, and readiness.
- Keep recommendations concrete and safe.
- No markdown, no prose outside JSON."""


def _validate_and_normalize_analysis(
    analysis: Dict[str, Any],
    evidence: Dict[str, Any],
) -> Tuple[Dict[str, Any], List[str], List[str]]:
    errors: List[str] = []
    warnings: List[str] = []
    normalized = _normalize_analysis_shape(_lift_flat_answer(analysis), errors, warnings)
    normalized = _apply_deterministic_quality(normalized, evidence, warnings)

    _validate_placeholders(normalized, errors)
    _validate_explicit_field_references(normalized, evidence, errors)
    _validate_free_text_claims(normalized, evidence, errors, warnings)
    _validate_issue_items(normalized.get("improvement_areas") or [], evidence, errors, warnings)
    _validate_resolution_items(normalized.get("resolution_steps") or [], evidence, errors, warnings)

    return normalized, _dedupe(errors), _dedupe(warnings)


SEMANTIC_KEYS = (
    "column_roles",
    "relationship_hypotheses",
    "root_cause_hypotheses",
    "downstream_impacts",
    "data_contract_rules",
    "monitoring_rules",
    "data_owner_questions",
    "use_case_readiness",
)


def _lift_flat_answer(analysis: Dict[str, Any]) -> Dict[str, Any]:
    """Accept answers whose keys sit at the top level instead of under ``semantic_analysis``.

    Hosted models often ignore the response schema and return the right content in a flatter
    shape (``table_summary``, ``custom_answer``, ``column_roles`` ...). Re-homing it keeps the
    specific content instead of rejecting the answer and falling back to template text.
    """
    lifted = dict(analysis)
    semantic = lifted.get("semantic_analysis")
    semantic = dict(semantic) if isinstance(semantic, dict) else {}

    for key in SEMANTIC_KEYS:
        if key in lifted:
            value = lifted.pop(key)
            if value and not semantic.get(key):
                semantic[key] = value

    summary = lifted.get("table_summary") or lifted.get("summary")
    if summary and not semantic.get("executive_summary"):
        semantic["executive_summary"] = summary
    if summary and "interpretation" not in lifted:
        lifted["interpretation"] = {"summary": summary, "key_entities": [], "relationships": []}

    if "custom_analysis" not in lifted and lifted.get("custom_answer"):
        lifted["custom_analysis"] = {
            "question": "",
            "answer": lifted.get("custom_answer"),
            "reasoning": "",
            "supporting_evidence": lifted.get("supporting_evidence") or [],
        }

    lifted["semantic_analysis"] = semantic
    return lifted


def _normalize_rule_items(value: Any, *, monitoring: bool) -> List[Dict[str, Any]]:
    """Map the near-miss shapes models produce for contract/monitoring rules onto the schema."""
    items = _normalize_items(value, default_field="table")
    for item in items:
        condition = _as_text(item.pop("condition", ""))
        threshold = item.get("threshold")
        if monitoring:
            item["check"] = _as_text(item.get("check") or condition or item.get("rule"))
            item["threshold"] = _as_text(threshold)
            item["cadence"] = _as_text(item.get("cadence") or "Each dataset version")
        else:
            rule = _as_text(item.get("rule"))
            if not rule and condition:
                rule = f"{condition} {_as_text(threshold)}".strip()
            item["rule"] = rule or _as_text(item.get("issue"))
            item["severity"] = _as_text(item.get("severity") or "medium").lower()
    return items


def _question_texts(value: Any) -> List[str]:
    if value is None:
        return []
    texts = []
    for item in value if isinstance(value, list) else [value]:
        if isinstance(item, dict):
            question = _as_text(item.get("question") or item.get("text"))
            column = _as_text(item.get("column") or item.get("field"))
            if question:
                texts.append(f"{column}: {question}" if column and column.lower() not in question.lower() else question)
        elif _as_text(item):
            texts.append(_as_text(item))
    return texts


def _readiness_items(value: Any) -> List[Dict[str, Any]]:
    if isinstance(value, dict):
        converted = []
        for use_case, detail in value.items():
            if isinstance(detail, dict):
                status = _as_text(detail.get("status") or "review").lower()
                rationale = _as_text(detail.get("rationale") or detail.get("reason"))
            else:
                status, rationale = "review", _as_text(detail)
            converted.append({"use_case": str(use_case).replace("_", " "), "status": status, "rationale": rationale})
        return converted
    return _normalize_items(value, default_field="table")


def _normalize_analysis_shape(
    analysis: Dict[str, Any],
    errors: List[str],
    warnings: List[str],
) -> Dict[str, Any]:
    # quality_assessment, improvement_areas and resolution_steps are replaced by the
    # deterministic metrics afterwards, so a model that omits them is not at fault.
    required = [
        "interpretation",
        "custom_analysis",
    ]
    for key in required:
        if key not in analysis:
            errors.append(f"Missing required key: {key}")

    interpretation = analysis.get("interpretation") or {}
    if isinstance(interpretation, str):
        warnings.append("interpretation was returned as text and was normalized")
        interpretation = {"summary": interpretation, "key_entities": [], "relationships": []}
    if not isinstance(interpretation, dict):
        errors.append("interpretation must be an object")
        interpretation = {"summary": "", "key_entities": [], "relationships": []}

    quality = analysis.get("quality_assessment") or {}
    if isinstance(quality, str):
        warnings.append("quality_assessment was returned as text and was normalized")
        quality = {"issues": [quality], "completeness": quality, "consistency": ""}
    if not isinstance(quality, dict):
        errors.append("quality_assessment must be an object")
        quality = {"issues": [], "completeness": "", "consistency": ""}

    custom = analysis.get("custom_analysis") or {}
    if isinstance(custom, str):
        warnings.append("custom_analysis was returned as text and was normalized")
        custom = {"question": "", "answer": custom, "reasoning": "", "supporting_evidence": []}
    if not isinstance(custom, dict):
        errors.append("custom_analysis must be an object")
        custom = {"question": "", "answer": "", "reasoning": "", "supporting_evidence": []}

    semantic = analysis.get("semantic_analysis") or {}
    if not isinstance(semantic, dict):
        warnings.append("semantic_analysis was returned as text and was normalized")
        semantic = {"executive_summary": _as_text(semantic)}

    improvement_areas = _normalize_items(analysis.get("improvement_areas"), default_field="table")
    resolution_steps = _normalize_items(analysis.get("resolution_steps"), default_field="table")

    return {
        "interpretation": {
            "summary": _as_text(interpretation.get("summary")),
            "key_entities": _as_text_list(interpretation.get("key_entities")),
            "relationships": _as_text_list(interpretation.get("relationships")),
        },
        "quality_assessment": {
            "issues": _as_text_list(quality.get("issues")),
            "completeness": _as_text(quality.get("completeness")),
            "consistency": _as_text(quality.get("consistency")),
        },
        "improvement_areas": improvement_areas,
        "resolution_steps": resolution_steps,
        "custom_analysis": {
            "question": _as_text(custom.get("question")),
            "answer": _as_text(custom.get("answer")),
            "reasoning": _as_text(custom.get("reasoning")),
            "supporting_evidence": _as_text_list(custom.get("supporting_evidence")),
        },
        "semantic_analysis": _normalize_semantic_analysis(semantic),
    }


def _normalize_semantic_analysis(value: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "executive_summary": _as_text(value.get("executive_summary")),
        "column_roles": _normalize_column_roles(value.get("column_roles")),
        "relationship_hypotheses": _normalize_items(value.get("relationship_hypotheses"), default_field="table"),
        "root_cause_hypotheses": _normalize_items(value.get("root_cause_hypotheses"), default_field="table"),
        "downstream_impacts": _normalize_items(value.get("downstream_impacts"), default_field="table"),
        "data_contract_rules": _normalize_rule_items(value.get("data_contract_rules"), monitoring=False),
        "monitoring_rules": _normalize_rule_items(value.get("monitoring_rules"), monitoring=True),
        "data_owner_questions": _question_texts(value.get("data_owner_questions")),
        "use_case_readiness": _readiness_items(value.get("use_case_readiness")),
    }


def _normalize_column_roles(value: Any) -> List[Dict[str, Any]]:
    if value is None:
        return []
    if not isinstance(value, list):
        value = [value]

    roles = []
    for item in value:
        if isinstance(item, dict):
            column = item.get("column") or item.get("field") or item.get("name") or "table"
            roles.append(
                {
                    "column": _as_text(column),
                    "role": _as_text(item.get("role")),
                    "description": _as_text(item.get("description") or item.get("summary")),
                    "business_meaning": _as_text(item.get("business_meaning") or item.get("meaning")),
                    "usage_notes": _as_text(item.get("usage_notes") or item.get("usage")),
                }
            )
        else:
            roles.append(
                {
                    "column": "table",
                    "role": "context",
                    "description": _as_text(item),
                    "business_meaning": "",
                    "usage_notes": "",
                }
            )
    return roles


def _level(value: str) -> str:
    """Map severity words models like to use onto the high/medium/low scale of the schema."""
    word = value.strip().lower()
    if word in {"critical", "severe", "major", "blocker", "urgent", "high"}:
        return "high"
    if word in {"minor", "info", "informational", "trivial", "low"}:
        return "low"
    if word in {"warning", "moderate", "med", "normal", "medium"}:
        return "medium"
    return word


def _normalize_items(value: Any, default_field: str) -> List[Dict[str, Any]]:
    if value is None:
        return []
    if not isinstance(value, list):
        value = [value]

    normalized = []
    for index, item in enumerate(value, start=1):
        if isinstance(item, dict):
            copied = dict(item)
            for level_key in ("impact", "severity"):
                if isinstance(copied.get(level_key), str):
                    copied[level_key] = _level(copied[level_key])
            copied.setdefault("field", copied.get("column") or copied.get("feature") or default_field)
            if "priority" not in copied:
                copied["priority"] = index
            normalized.append(copied)
        else:
            normalized.append(
                {
                    "field": default_field,
                    "issue": _as_text(item),
                    "impact": "medium",
                    "priority": index,
                }
            )
    return normalized


def _validate_placeholders(value: Any, errors: List[str], path: str = "") -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            _validate_placeholders(nested, errors, f"{path}.{key}" if path else key)
        return
    if isinstance(value, list):
        for index, nested in enumerate(value):
            _validate_placeholders(nested, errors, f"{path}[{index}]")
        return
    if isinstance(value, str):
        text = value.strip()
        lowered = text.lower()
        if not lowered:
            return
        if lowered in PLACEHOLDER_FIELDS or "missingness_col" in lowered:
            errors.append(f"Placeholder value is not allowed at {path}: {text}")
        # A bare ellipsis is a template placeholder; "..." inside a sentence is usually a long
        # data value the profiler shortened, which a messy dataset is full of.
        if text.strip(" .…") == "" and ("..." in text or "…" in text):
            errors.append(f"Ellipsis placeholder is not allowed at {path}")


def _validate_explicit_field_references(
    value: Any,
    evidence: Dict[str, Any],
    errors: List[str],
    path: str = "",
) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            current_path = f"{path}.{key}" if path else key
            if key in FIELD_KEYS:
                _validate_field_value(nested, evidence, errors, current_path)
            else:
                _validate_explicit_field_references(nested, evidence, errors, current_path)
        return
    if isinstance(value, list):
        for index, nested in enumerate(value):
            _validate_explicit_field_references(nested, evidence, errors, f"{path}[{index}]")


def _validate_issue_items(
    items: List[Dict[str, Any]],
    evidence: Dict[str, Any],
    errors: List[str],
    warnings: List[str],
) -> None:
    for item in items:
        field_refs = _field_refs(item.get("field"))
        text = _compact_json_text(item)
        for field_ref in field_refs:
            column = _resolve_column(field_ref, evidence)
            if column:
                _validate_missingness_claims(text, column, errors, warnings, evidence.get("allowed_columns"))
                _validate_impact(text, item.get("impact"), column, warnings)


def _validate_resolution_items(
    items: List[Dict[str, Any]],
    evidence: Dict[str, Any],
    errors: List[str],
    warnings: List[str],
) -> None:
    for item in items:
        field_refs = _field_refs(item.get("field"))
        text = _compact_json_text(item)
        for field_ref in field_refs:
            column = _resolve_column(field_ref, evidence)
            if column:
                _validate_missingness_claims(text, column, errors, warnings, evidence.get("allowed_columns"))
                if _is_dangerous_drop(text, column):
                    errors.append(
                        f"Dangerous recommendation for ID/key-like column {column['name']}: do not drop it without severe evidence"
                    )


def _validate_free_text_claims(
    analysis: Dict[str, Any],
    evidence: Dict[str, Any],
    errors: List[str],
    warnings: List[str],
) -> None:
    """Validate narrative metric claims without treating every noun as a column."""

    for path, text in _iter_text_values(analysis):
        if not _looks_like_metric_claim(text):
            continue

        candidates = _extract_column_claim_candidates(text)
        if candidates:
            unresolved_candidates = []
            for candidate in candidates:
                normalized = _normalize_name(candidate)
                if normalized in ALLOWED_TABLE_FIELDS or normalized in PLACEHOLDER_FIELDS:
                    continue
                if _is_generic_metric_subject(candidate):
                    continue
                mentioned_columns = _mentioned_allowed_columns(candidate, evidence)
                if mentioned_columns:
                    for column in mentioned_columns:
                        _validate_missingness_claims(text, column, errors, warnings, evidence.get("allowed_columns"))
                    continue
                column = _resolve_column(candidate, evidence)
                if column:
                    _validate_missingness_claims(text, column, errors, warnings, evidence.get("allowed_columns"))
                    continue
                unresolved_candidates.append(candidate)
            # The sentence parser also picks up ordinary words ("There", "main risk"), so a
            # name that is not a column is only a warning here. Invented columns in explicit
            # ``field`` keys are still rejected by _validate_explicit_field_references.
            for candidate in unresolved_candidates:
                warnings.append(f"Unrecognised column reference in text at {path}: {candidate}")
            continue

        if MISSINGNESS_RE.search(text) and PERCENT_RE.search(text):
            warnings.append(f"Metric percentage at {path} does not cite a specific column")


def _iter_text_values(value: Any, path: str = ""):
    if isinstance(value, dict):
        for key, nested in value.items():
            current_path = f"{path}.{key}" if path else key
            yield from _iter_text_values(nested, current_path)
        return
    if isinstance(value, list):
        for index, nested in enumerate(value):
            yield from _iter_text_values(nested, f"{path}[{index}]")
        return
    if isinstance(value, str):
        text = value.strip()
        if text:
            yield path, text


def _looks_like_metric_claim(text: str) -> bool:
    return bool(MISSINGNESS_RE.search(text) or PERCENT_RE.search(text))


def _extract_column_claim_candidates(text: str) -> List[str]:
    candidates: List[str] = []
    for pattern in (COLUMN_CLAIM_RE, MISSINGNESS_FOR_COLUMN_RE):
        for match in pattern.finditer(text):
            candidate = _clean_column_candidate(match.group(1))
            if candidate:
                candidates.append(candidate)
    return _dedupe(candidates)


def _clean_column_candidate(value: str) -> str:
    candidate = str(value or "").strip().strip("`'\"[](){}")
    candidate = re.sub(
        r"\b(the|this|that|a|an|le|la|les|un|une|des|only|mostly|mainly)\s+",
        "",
        candidate,
        flags=re.IGNORECASE,
    )
    candidate = re.split(r"\s+(?:column|field|champ|colonne)\b", candidate, flags=re.IGNORECASE)[0].strip()
    if "." in candidate:
        candidate = candidate.split(".")[-1].strip()
    return candidate


def _is_generic_metric_subject(candidate: str) -> bool:
    normalized = _normalize_name(candidate)
    if normalized in GENERIC_METRIC_SUBJECTS:
        return True
    if normalized.startswith("the ") and normalized[4:] in GENERIC_METRIC_SUBJECTS:
        return True
    if normalized.startswith(("it ", "table ", "this table ", "the table ", "evidence ", "the evidence ")):
        return True
    return False


def _mentioned_allowed_columns(candidate: str, evidence: Dict[str, Any]) -> List[Dict[str, Any]]:
    found = []
    remaining = _normalize_name(candidate)
    for column_name in evidence.get("allowed_columns") or []:
        normalized_column = _normalize_name(column_name)
        if _contains_column_reference(candidate, column_name):
            column = _resolve_column(column_name, evidence)
            if column:
                found.append(column)
            remaining = remaining.replace(normalized_column, " ")

    leftover_words = {
        word
        for word in re.split(r"[^A-Za-z0-9_]+", remaining)
        if word
    }
    if found and leftover_words.issubset(CONNECTOR_WORDS):
        return found
    return []


def _contains_column_reference(text: str, column_name: str) -> bool:
    escaped = re.escape(str(column_name))
    return bool(re.search(rf"(?<![A-Za-z0-9_]){escaped}(?![A-Za-z0-9_])", text, re.IGNORECASE))


def _validate_field_value(
    value: Any,
    evidence: Dict[str, Any],
    errors: List[str],
    path: str,
) -> None:
    for field_ref in _field_refs(value):
        normalized = _normalize_name(field_ref)
        if normalized in PLACEHOLDER_FIELDS:
            errors.append(f"Invalid placeholder field at {path}: {field_ref}")
            continue
        if normalized in ALLOWED_TABLE_FIELDS:
            continue
        if not _resolve_column(field_ref, evidence):
            errors.append(f"Unknown column reference at {path}: {field_ref}")


def _nearest_column_before(text: str, position: int, columns: Optional[List[str]]) -> Optional[str]:
    """The allowed column whose name ends closest before ``position`` in ``text``."""
    best_name, best_end = None, -1
    for name in columns or []:
        for found in re.finditer(rf"(?<![A-Za-z0-9_]){re.escape(str(name))}(?![A-Za-z0-9_])", text, re.IGNORECASE):
            if found.end() <= position and found.end() > best_end:
                best_name, best_end = str(name), found.end()
    return best_name


def _validate_missingness_claims(
    text: str,
    column: Dict[str, Any],
    errors: List[str],
    warnings: List[str],
    all_columns: Optional[List[str]] = None,
) -> None:
    if not MISSINGNESS_RE.search(text):
        return

    actual = float(column.get("missing_percent") or 0.0)
    for match in PERCENT_RE.finditer(text):
        # "100% complete" or "100% unique" describes the opposite of missingness.
        if POSITIVE_PERCENT_RE.match(text[match.end():match.end() + 24]):
            continue
        # "A has 10% missing; B has 60% missing": a percentage belongs to the column named just before it.
        owner = _nearest_column_before(text, match.start(), all_columns)
        if owner and _normalize_name(owner) != _normalize_name(column["name"]):
            continue
        # Only a percentage stated next to a missing-value word is a missingness claim;
        # "86.76% missing, 61.03% distinct" makes two different claims.
        near = text[match.end():match.end() + 25]
        before = text[max(0, match.start() - 45):match.start()]
        if not (MISSINGNESS_RE.search(near) or MISSINGNESS_RE.search(before.split(",")[-1].split(";")[-1])):
            continue
        if re.search(r"\b(distinct|unique|duplicate|duplicates|of rows|share|concentrat)", near, re.IGNORECASE) and not MISSINGNESS_RE.search(near):
            continue
        claimed = _safe_float(match.group(1).replace(",", "."))
        if claimed is None:
            continue
        if abs(claimed - actual) > max(2.0, actual * 0.15):
            errors.append(
                f"Missingness claim for {column['name']} says {claimed:.2f}% but evidence is {actual:.2f}%"
            )

    if HIGH_SEVERITY_RE.search(text) and actual < 20.0:
        warnings.append(
            f"High-severity missingness wording for {column['name']} is weakly supported because missingness is {actual:.2f}%"
        )


def _validate_impact(
    text: str,
    impact: Any,
    column: Dict[str, Any],
    warnings: List[str],
) -> None:
    if not MISSINGNESS_RE.search(text):
        return
    actual = float(column.get("missing_percent") or 0.0)
    impact_text = str(impact or "").lower()
    if actual >= 80.0 and impact_text == "low":
        warnings.append(f"Low impact for {column['name']} conflicts with {actual:.2f}% missingness")
    if actual < 10.0 and impact_text == "high":
        warnings.append(f"High impact for {column['name']} should be justified because missingness is {actual:.2f}%")


def _is_dangerous_drop(text: str, column: Dict[str, Any]) -> bool:
    if not DANGEROUS_DROP_RE.search(text):
        return False
    missing_percent = float(column.get("missing_percent") or 0.0)
    return bool(column.get("is_id_like")) and missing_percent < 50.0


def _apply_deterministic_quality(
    analysis: Dict[str, Any],
    evidence: Dict[str, Any],
    warnings: List[str],
) -> Dict[str, Any]:
    deterministic = evidence.get("deterministic_quality") or _deterministic_quality_from_evidence(evidence)
    if not deterministic:
        return analysis

    merged = deepcopy(analysis)
    merged["quality_assessment"] = deepcopy(deterministic.get("quality_assessment") or {})
    merged["improvement_areas"] = deepcopy(deterministic.get("improvement_areas") or [])
    merged["resolution_steps"] = deepcopy(deterministic.get("resolution_steps") or [])

    custom = merged.setdefault("custom_analysis", {})
    support = _as_text_list(custom.get("supporting_evidence"))
    for item in deterministic.get("supporting_evidence") or []:
        if item not in support:
            support.append(item)
    custom["supporting_evidence"] = support

    merged["semantic_analysis"] = _merge_semantic_analysis(
        merged.get("semantic_analysis") or {},
        _deterministic_semantic_analysis(evidence),
    )

    warnings.append("quality metrics were computed deterministically from profiling evidence")
    return merged


def _merge_semantic_analysis(
    llm_semantic: Dict[str, Any],
    deterministic_semantic: Dict[str, Any],
) -> Dict[str, Any]:
    merged = deepcopy(llm_semantic or {})
    for key, value in deterministic_semantic.items():
        current = merged.get(key)
        if isinstance(value, list):
            if key == "column_roles":
                merged[key] = _merge_column_roles(current if isinstance(current, list) else [], value)
            elif not current:
                merged[key] = deepcopy(value)
        elif not current:
            merged[key] = value
    return merged


def _merge_column_roles(
    llm_roles: List[Dict[str, Any]],
    deterministic_roles: List[Dict[str, Any]],
) -> List[Dict[str, Any]]:
    by_column = {
        _normalize_name(role.get("column")): deepcopy(role)
        for role in llm_roles
        if isinstance(role, dict) and role.get("column")
    }
    for role in deterministic_roles:
        normalized = _normalize_name(role.get("column"))
        if normalized not in by_column:
            by_column[normalized] = deepcopy(role)
            continue
        existing = by_column[normalized]
        for key, value in role.items():
            if not existing.get(key):
                existing[key] = value
    return list(by_column.values())


def _deterministic_semantic_analysis(evidence: Dict[str, Any]) -> Dict[str, Any]:
    table_name = evidence.get("table_name") or "table"
    overview = evidence.get("overview") or {}
    parser_info = evidence.get("parser_info") or {}
    columns = evidence.get("columns") or []
    missing_columns = evidence.get("missing_columns") or []
    duplicate_rows = int(overview.get("duplicates") or 0)

    column_roles = [_column_semantic_role(table_name, column) for column in columns]
    relationships = _relationship_hypotheses_from_columns(table_name, columns)
    root_causes = _root_cause_hypotheses_from_missingness(missing_columns)
    impacts = _downstream_impacts_from_evidence(evidence)
    contract_rules = _data_contract_rules_from_columns(columns)
    monitoring_rules = _monitoring_rules_from_evidence(evidence)
    questions = _data_owner_questions_from_evidence(evidence)
    readiness = _use_case_readiness_from_evidence(evidence)

    row_count = overview.get("rows") or 0
    column_count = len(columns)
    missing_text = (
        f"{len(missing_columns)} column(s) contain missing values"
        if missing_columns
        else "no missing values were detected"
    )
    duplicate_text = (
        f"{duplicate_rows} duplicate row(s) were detected"
        if duplicate_rows
        else "no duplicate rows were detected"
    )
    parser_text = (
        f" Parser warning: {parser_info.get('warning')}"
        if parser_info.get("warning")
        else ""
    )

    return {
        "executive_summary": (
            f"{table_name} contains {row_count} rows and {column_count} columns; "
            f"{missing_text}, and {duplicate_text}.{parser_text}"
        ),
        "column_roles": column_roles,
        "relationship_hypotheses": relationships,
        "root_cause_hypotheses": root_causes,
        "downstream_impacts": impacts,
        "data_contract_rules": contract_rules,
        "monitoring_rules": monitoring_rules,
        "data_owner_questions": questions,
        "use_case_readiness": readiness,
    }


def _column_semantic_role(table_name: str, column: Dict[str, Any]) -> Dict[str, Any]:
    name = column.get("name") or "column"
    normalized = _normalize_name(name)
    role = "attribute"
    description = f"{name} describes an attribute in the {table_name} table."
    business_meaning = "Use this field with business validation before making operational decisions."
    usage_notes = "Monitor missingness, type stability, and distinct-count drift across versions."

    if normalized.endswith(" id") or normalized == "id":
        role = "identifier"
        entity = normalized[:-3].strip() or table_name
        description = f"{name} is likely an identifier for {entity} records or relationships."
        business_meaning = "Identifiers support joins, deduplication, lineage, and entity tracking."
        usage_notes = "Keep this field stable and non-null; confirm whether it is a primary key or foreign key."
    elif "name" in normalized:
        role = "label"
        description = f"{name} is likely a human-readable label or descriptive name."
        business_meaning = "Labels make reports and dashboards understandable to business users."
        usage_notes = "Standardize casing, spelling, and duplicate-looking labels."
    elif "date" in normalized or "time" in normalized:
        role = "date"
        description = f"{name} is likely a lifecycle or event date."
        business_meaning = "Date fields support latency, freshness, trend, and process monitoring."
        usage_notes = "Validate date format, timezone assumptions, and chronological order with related dates."
    elif "email" in normalized:
        role = "contact"
        description = f"{name} is likely an email contact field."
        business_meaning = "Email fields support communication, CRM, matching, and customer operations."
        usage_notes = "Validate format and privacy handling; avoid exposing raw values unnecessarily."
    elif "phone" in normalized or "mobile" in normalized:
        role = "contact"
        description = f"{name} is likely a phone contact field."
        business_meaning = "Phone fields support contactability and customer-service workflows."
        usage_notes = "Validate country/format rules and whether capture is mandatory."
    elif any(token in normalized for token in ["street", "city", "state", "zip", "postal", "address"]):
        role = "location"
        description = f"{name} is likely part of a physical address or geography."
        business_meaning = "Location fields support segmentation, delivery, branch operations, and regional reporting."
        usage_notes = "Standardize address formats and validate allowed geographic values."
    elif "status" in normalized:
        role = "status"
        description = f"{name} is likely a lifecycle, workflow, or state indicator."
        business_meaning = "Status fields help explain process progress and operational exceptions."
        usage_notes = "Confirm allowed status values and transitions with the data owner."
    elif any(token in normalized for token in ["price", "amount", "cost", "revenue", "total"]):
        role = "measure"
        description = f"{name} is likely a financial or numeric measure."
        business_meaning = "Measures support reporting, financial analysis, and KPI calculations."
        usage_notes = "Validate units, currency, numeric range, and outliers."
    elif "quantity" in normalized or normalized in {"qty", "count"}:
        role = "measure"
        description = f"{name} is likely a quantity or count measure."
        business_meaning = "Quantity fields support demand, inventory, and transaction analysis."
        usage_notes = "Validate numeric type, valid range, and whether zero values are meaningful."
    elif "discount" in normalized:
        role = "measure"
        description = f"{name} is likely a discount or adjustment measure."
        business_meaning = "Discount fields support pricing, promotion, and margin analysis."
        usage_notes = "Validate scale conventions such as 0.10 versus 10%."

    return {
        "column": name,
        "role": role,
        "description": description,
        "business_meaning": business_meaning,
        "usage_notes": usage_notes,
    }


def _relationship_hypotheses_from_columns(
    table_name: str,
    columns: List[Dict[str, Any]],
) -> List[Dict[str, Any]]:
    table_root = _singularize(_normalize_name(table_name))
    relationships = []
    for column in columns:
        name = str(column.get("name") or "")
        normalized = _normalize_name(name)
        if not normalized.endswith(" id"):
            continue
        entity = normalized[:-3].strip()
        if not entity:
            continue
        if entity == table_root:
            relationships.append(
                {
                    "source_column": name,
                    "target_table": table_name,
                    "target_column": name,
                    "confidence": "high",
                    "reason": "The column name matches the table entity and is likely the table identifier.",
                }
            )
        else:
            relationships.append(
                {
                    "source_column": name,
                    "target_table": _pluralize(entity),
                    "target_column": name,
                    "confidence": "medium",
                    "reason": "The *_id naming pattern suggests a possible foreign-key relationship.",
                }
            )
    return relationships[:12]


def _root_cause_hypotheses_from_missingness(
    missing_columns: List[Dict[str, Any]],
) -> List[Dict[str, Any]]:
    hypotheses = []
    for priority, item in enumerate(missing_columns[:8], start=1):
        column_name = item["column"]
        missing_percent = float(item.get("missing_percent") or 0.0)
        hypotheses.append(
            {
                "field": column_name,
                "hypothesis": _missingness_root_cause(column_name, missing_percent),
                "evidence": f"{column_name} has {missing_percent:.2f}% missing values.",
                "priority": priority,
            }
        )
    return hypotheses


def _missingness_root_cause(column_name: str, missing_percent: float) -> str:
    normalized = _normalize_name(column_name)
    if "phone" in normalized or "email" in normalized:
        return "Contact details may be optional, privacy-filtered, or missing from older records."
    if "date" in normalized and ("ship" in normalized or "end" in normalized or "close" in normalized):
        return "The date may be legitimately empty when the process step has not happened yet."
    if missing_percent >= 80.0:
        return "The field may be optional, sparsely populated, or affected by an upstream mapping issue."
    return "The field may be conditionally populated depending on business process or source-system rules."


def _downstream_impacts_from_evidence(evidence: Dict[str, Any]) -> List[Dict[str, Any]]:
    impacts = []
    missing_columns = evidence.get("missing_columns") or []
    if missing_columns:
        impacts.append(
            {
                "area": "Reporting",
                "impact": "Metrics using incomplete fields may need filters, caveats, or completeness checks.",
                "severity": "medium",
            }
        )
    if evidence.get("id_like_columns"):
        impacts.append(
            {
                "area": "Joins",
                "impact": "Identifier fields are important for joins and should be validated before cross-table analysis.",
                "severity": "high",
            }
        )
    if float((evidence.get("overview") or {}).get("duplicate_percent") or 0.0) > 0:
        impacts.append(
            {
                "area": "Aggregation",
                "impact": "Duplicate rows can inflate counts, totals, and KPI calculations.",
                "severity": "high",
            }
        )
    if not impacts:
        impacts.append(
            {
                "area": "General analytics",
                "impact": "The table is structurally usable from the current profiling evidence, pending business validation.",
                "severity": "low",
            }
        )
    return impacts


def _data_contract_rules_from_columns(columns: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    rules = []
    for column in columns[:20]:
        name = column.get("name") or "column"
        normalized = _normalize_name(name)
        missing_percent = float(column.get("missing_percent") or 0.0)
        if normalized.endswith(" id") or bool(column.get("is_id_like")):
            rules.append(
                {
                    "field": name,
                    "rule": "Must remain stable, non-null when required, and valid for expected joins.",
                    "severity": "high",
                }
            )
        elif "email" in normalized:
            rules.append({"field": name, "rule": "Must match a valid email format when present.", "severity": "medium"})
        elif "phone" in normalized:
            rules.append({"field": name, "rule": "Must match the expected phone format when present.", "severity": "medium"})
        elif "date" in normalized or "time" in normalized:
            rules.append({"field": name, "rule": "Must use a valid date format and respect process chronology.", "severity": "medium"})
        elif missing_percent > 0:
            rules.append({"field": name, "rule": "Missingness should remain within the agreed business threshold.", "severity": _impact_for_missingness(missing_percent)})
    return rules[:15]


def _monitoring_rules_from_evidence(evidence: Dict[str, Any]) -> List[Dict[str, Any]]:
    rules = [
        {
            "field": "table",
            "check": "Schema drift",
            "threshold": "Alert when columns are added, removed, or renamed.",
            "cadence": "Each dataset version",
        },
        {
            "field": "table",
            "check": "Duplicate rows",
            "threshold": "Alert when duplicate rows increase versus the previous version.",
            "cadence": "Each dataset version",
        },
    ]
    for item in (evidence.get("missing_columns") or [])[:8]:
        rules.append(
            {
                "field": item["column"],
                "check": "Missingness drift",
                "threshold": "Alert if missingness rises above the current profiled baseline.",
                "cadence": "Each dataset version",
            }
        )
    return rules


def _data_owner_questions_from_evidence(evidence: Dict[str, Any]) -> List[str]:
    questions = []
    for item in (evidence.get("missing_columns") or [])[:6]:
        questions.append(f"Is {item['column']} mandatory, optional, or conditionally populated?")
    if evidence.get("id_like_columns"):
        questions.append("Which ID-like columns are primary keys, foreign keys, or business identifiers?")
    if not questions:
        questions.append("Are the detected column roles and expected joins aligned with the source-system design?")
    return questions


def _use_case_readiness_from_evidence(evidence: Dict[str, Any]) -> List[Dict[str, Any]]:
    missing_columns = evidence.get("missing_columns") or []
    high_missing = evidence.get("high_missing_columns") or []
    duplicate_rows = int((evidence.get("overview") or {}).get("duplicates") or 0)
    has_quality_risk = bool(high_missing or duplicate_rows)
    has_missing = bool(missing_columns)
    return [
        {
            "use_case": "Exploratory analysis",
            "status": "ready" if not has_quality_risk else "review",
            "rationale": "Exploration can proceed, but quality caveats should be visible when issues exist.",
        },
        {
            "use_case": "Business reporting",
            "status": "review" if has_missing or duplicate_rows else "ready",
            "rationale": "Reporting needs confirmed definitions, completeness expectations, and key fields.",
        },
        {
            "use_case": "Machine learning",
            "status": "review" if has_missing else "ready",
            "rationale": "Model training requires explicit handling of missing values, leakage, and stable features.",
        },
    ]


def _deterministic_quality_from_evidence(evidence: Dict[str, Any]) -> Dict[str, Any]:
    overview = evidence.get("overview") or {}
    missing_columns = evidence.get("missing_columns") or []
    high_missing = evidence.get("high_missing_columns") or []

    issues: List[str] = []
    improvement_areas: List[Dict[str, Any]] = []
    resolution_steps: List[Dict[str, Any]] = []

    for item in missing_columns[:10]:
        column_name = item["column"]
        missing_percent = float(item.get("missing_percent") or 0.0)
        missing_count = int(item.get("missing_count") or 0)
        issue = f"{column_name} has {missing_percent:.2f}% missing values ({missing_count} missing)."
        issues.append(issue)
        improvement_areas.append(
            {
                "field": column_name,
                "issue": issue,
                "impact": _impact_for_missingness(missing_percent),
                "priority": len(improvement_areas) + 1,
            }
        )
        resolution_steps.append(
            {
                "field": column_name,
                "issue": issue,
                "steps": [
                    "Confirm whether the field is mandatory for the business process.",
                    "Check the upstream source mapping and ingestion rules.",
                    "Document whether missing values are expected, optional, or a data defect.",
                ],
                "effort": "medium" if missing_percent >= 10 else "low",
            }
        )

    duplicate_percent = float(overview.get("duplicate_percent") or 0.0)
    duplicate_rows = int(overview.get("duplicates") or 0)
    if duplicate_rows > 0:
        issue = f"The table has {duplicate_percent:.2f}% duplicate rows ({duplicate_rows} duplicates)."
        issues.append(issue)
        improvement_areas.append(
            {
                "field": "table",
                "issue": issue,
                "impact": "medium" if duplicate_percent < 10 else "high",
                "priority": len(improvement_areas) + 1,
            }
        )
        resolution_steps.append(
            {
                "field": "table",
                "issue": issue,
                "steps": [
                    "Identify the natural key or primary-key candidate for this table.",
                    "Review duplicate rows with the data owner before removing records.",
                ],
                "effort": "medium",
            }
        )

    if not issues:
        issues.append("No missing-value or duplicate-row issue was detected from the available metrics.")
        improvement_areas.append(
            {
                "field": "table",
                "issue": "Continue monitoring schema, missingness, and duplicate-row drift across versions.",
                "impact": "low",
                "priority": 1,
            }
        )
        resolution_steps.append(
            {
                "field": "table",
                "issue": "Ongoing quality monitoring",
                "steps": [
                    "Keep version-level comparisons active.",
                    "Validate expected key columns with business owners.",
                ],
                "effort": "low",
            }
        )

    return {
        "quality_assessment": {
            "issues": issues,
            "completeness": _fallback_completeness_text(missing_columns),
            "consistency": _deterministic_consistency_text(evidence),
        },
        "improvement_areas": improvement_areas,
        "resolution_steps": resolution_steps,
        "supporting_evidence": _deterministic_supporting_evidence(evidence, high_missing),
    }


def _impact_for_missingness(missing_percent: float) -> str:
    if missing_percent >= 50.0:
        return "high"
    if missing_percent >= 10.0:
        return "medium"
    return "low"


def _deterministic_consistency_text(evidence: Dict[str, Any]) -> str:
    overview = evidence.get("overview") or {}
    duplicate_percent = float(overview.get("duplicate_percent") or 0.0)
    duplicate_text = (
        f"Duplicate rows detected at {duplicate_percent:.2f}%."
        if duplicate_percent > 0
        else "No duplicate rows detected."
    )
    type_mix = _format_type_mix(evidence.get("type_counts") or {})
    type_text = f" Column types: {type_mix}." if type_mix else ""
    return (
        f"{duplicate_text}{type_text} "
        "Confirm ID-like and date-like columns before downstream joins or reporting."
    )


_TYPE_LABELS = {
    "TYPE_NUM": "numerical",
    "TYPE_CAT": "categorical",
    "TYPE_TEXT": "text",
    "TYPE_BOOL": "boolean",
    "TYPE_DATE": "date",
}


def _format_type_mix(type_counts: Dict[str, Any]) -> str:
    """Human-readable type mix, e.g. "3 numerical, 2 categorical" (never a raw dict)."""
    parts = []
    for raw_type, count in sorted(type_counts.items(), key=lambda item: (-_safe_int(item[1]), str(item[0]))):
        key = str(raw_type)
        label = _TYPE_LABELS.get(key.upper(), key.replace("TYPE_", "").replace("_", " ").lower())
        parts.append(f"{_safe_int(count)} {label}")
    return ", ".join(parts)


def _deterministic_supporting_evidence(
    evidence: Dict[str, Any],
    high_missing: List[Dict[str, Any]],
) -> List[str]:
    overview = evidence.get("overview") or {}
    return [
        f"Rows: {overview.get('rows') or 0}",
        f"Columns: {len(evidence.get('columns') or [])}",
        f"Missing columns: {len(evidence.get('missing_columns') or [])}",
        f"High-missing columns: {len(high_missing)}",
        f"Duplicate rows: {overview.get('duplicates') or 0}",
    ]


def _fallback_analysis_from_evidence(
    evidence: Dict[str, Any],
    *,
    description: str,
    custom_question: str,
    reason: str,
    llm_unavailable: bool = False,
) -> Dict[str, Any]:
    table_name = evidence.get("table_name") or "table"
    why = (
        "the LLM service (Ollama) was unavailable"
        if llm_unavailable
        else "the LLM answer did not pass validation"
    )
    overview = evidence.get("overview") or {}
    columns = evidence.get("columns") or []
    id_like = evidence.get("id_like_columns") or []
    associations = evidence.get("top_associations") or []
    deterministic = evidence.get("deterministic_quality") or _deterministic_quality_from_evidence(evidence)

    return {
        "interpretation": {
            "summary": (
                f"{table_name} contains {overview.get('rows') or 0} rows and "
                f"{len(columns)} columns. The interpretation is generated from deterministic metrics "
                f"because {why}."
            ),
            "key_entities": id_like[:8] or [column["name"] for column in columns[:5]],
            "relationships": [
                f"{item['left']} is strongly associated with {item['right']} (score {item['score']})."
                for item in associations[:5]
            ],
        },
        "quality_assessment": deepcopy(deterministic.get("quality_assessment") or {}),
        "improvement_areas": deepcopy(deterministic.get("improvement_areas") or []),
        "resolution_steps": deepcopy(deterministic.get("resolution_steps") or []),
        "custom_analysis": {
            "question": custom_question or "Describe the table structure, risks, and next actions.",
            "answer": f"This answer is based on the extracted metrics only, because {why}.",
            "reasoning": f"Fallback reason: {reason}",
            "supporting_evidence": deepcopy(deterministic.get("supporting_evidence") or []),
        },
        "semantic_analysis": _deterministic_semantic_analysis(evidence),
    }


def _fallback_completeness_text(missing_columns: List[Dict[str, Any]]) -> str:
    if not missing_columns:
        return "No missing values were detected in the profiled columns."
    parts = [
        (
            f"{item['column']} has {float(item.get('missing_percent') or 0.0):.2f}% "
            f"missing values ({int(item.get('missing_count') or 0)} missing)"
        )
        for item in missing_columns[:8]
    ]
    return "Missing values detected: " + "; ".join(parts) + "."


def _field_refs(value: Any) -> List[str]:
    if value is None:
        return []
    if isinstance(value, list):
        refs: List[str] = []
        for item in value:
            refs.extend(_field_refs(item))
        return refs
    if isinstance(value, dict):
        refs = []
        for nested in value.values():
            refs.extend(_field_refs(nested))
        return refs
    text = str(value).strip()
    if not text:
        return []
    pieces = re.split(r"[,;/]\s*", text)
    return [piece.strip().strip("`'\"") for piece in pieces if piece.strip()]


def _resolve_column(field_ref: str, evidence: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    column_map = evidence.get("column_map") or {}
    return column_map.get(_normalize_name(field_ref)) or column_map.get(
        _normalize_name(str(field_ref).split(".")[-1])
    )


def _canonical_column_name(field_ref: str, allowed: Dict[str, str]) -> Optional[str]:
    normalized = _normalize_name(field_ref)
    if normalized in allowed:
        return allowed[normalized]
    tail = _normalize_name(str(field_ref).split(".")[-1])
    return allowed.get(tail)


def _guess_id_like(name: str, distinct_count: Optional[int], total_rows: Optional[int]) -> bool:
    normalized = name.lower()
    has_id_token = any(token in normalized for token in ["id", "code", "key", "ref", "num"])
    if not has_id_token:
        return False
    if total_rows:
        return float(distinct_count or 0) / float(total_rows) >= 0.5
    return True


def _looks_date_like(name: str, data_type: Any) -> bool:
    normalized = str(name).lower()
    return "date" in normalized or "time" in normalized or str(data_type).upper() == "TYPE_DATE"


def _singularize(value: str) -> str:
    text = str(value or "").strip()
    if text.endswith("ies") and len(text) > 3:
        return text[:-3] + "y"
    if text.endswith("s") and len(text) > 1:
        return text[:-1]
    return text


def _pluralize(value: str) -> str:
    text = str(value or "").strip()
    if not text:
        return "unknown"
    if text.endswith("y"):
        return text[:-1] + "ies"
    if text.endswith("s"):
        return text
    return text + "s"


def _normalize_name(value: Any) -> str:
    text = str(value or "").strip().strip("`'\"").lower()
    text = re.sub(r"[_\-]+", " ", text)
    text = re.sub(r"\s+", " ", text)
    return text


def _compact_json_text(value: Any) -> str:
    try:
        return json.dumps(value, ensure_ascii=False)
    except Exception:
        return str(value)


def _as_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False)
    return str(value)


def _as_text_list(value: Any) -> List[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [_as_text(item) for item in value if _as_text(item)]
    return [_as_text(value)]


def _short_value(value: Any, limit: int = 80) -> str:
    text = "" if value is None else str(value)
    return text if len(text) <= limit else text[: limit - 3] + "..."


def _safe_float(value: Any) -> Optional[float]:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _safe_int(value: Any) -> int:
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return 0


def _bounded_float(value: Any, *, default: float, maximum: float) -> float:
    parsed = _safe_float(value)
    if parsed is None:
        return default
    return min(parsed, maximum)


def _dedupe(items: List[str]) -> List[str]:
    seen = set()
    deduped = []
    for item in items:
        if item not in seen:
            seen.add(item)
            deduped.append(item)
    return deduped
