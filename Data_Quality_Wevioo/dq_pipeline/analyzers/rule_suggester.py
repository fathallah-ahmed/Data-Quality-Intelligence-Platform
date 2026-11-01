"""
Turn the user's goal into quality rules the platform can check.

The model proposes rules for the goal ("contact customers" -> email filled and well formed); the
code then keeps only the rules that can really be checked on this data: real tables and columns,
known rule types, sensible thresholds. A rule the model invented never reaches the user. Without a
goal, or when the model is unavailable, a small set of rules is derived from the measurements alone.
"""
import json
import re
from typing import Any, Dict, List, Optional, Tuple

from dq_pipeline.extractors.format_profile import FORMATS

SUGGESTIBLE_TYPES = (
    "NOT_NULL",
    "MAX_MISSING_PERCENT",
    "UNIQUE",
    "MIN_FORMAT_VALID_PERCENT",
    "DATE_ORDER",
    "MAX_DUPLICATE_PERCENT",
)
MAX_RULES = 15
MAX_RATIONALE_CHARS = 400

DEFAULT_THRESHOLDS = {
    "MAX_MISSING_PERCENT": 5.0,
    "MIN_FORMAT_VALID_PERCENT": 95.0,
    "DATE_ORDER": 0.0,
    "MAX_DUPLICATE_PERCENT": 0.0,
}
NEEDS_THRESHOLD = set(DEFAULT_THRESHOLDS)

# A format is worth a rule on a column when most of its values already look like it.
MIN_FORMAT_SHARE = 0.8
FORMAT_NAME_HINTS = {
    "EMAIL": ("email", "e_mail", "mail", "courriel"),
    "PHONE": ("phone", "tel", "mobile", "gsm"),
    "URL": ("url", "website", "site_web", "link"),
}
START_HINTS = ("start", "open", "begin", "created", "from", "debut", "ouverture", "creation", "issue")
END_HINTS = ("end", "close", "closing", "finish", "to_", "fin", "fermeture", "expir", "update")

RULE_SCHEMA = {
    "type": "object",
    "properties": {
        "rules": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "table": {"type": "string"},
                    "column": {"type": "string"},
                    "type": {"type": "string", "enum": list(SUGGESTIBLE_TYPES)},
                    "format": {"type": "string"},
                    "second_column": {"type": "string"},
                    "threshold": {"type": "number"},
                    "severity": {"type": "string", "enum": ["WARNING", "CRITICAL"]},
                    "rationale": {"type": "string"},
                },
                "required": ["table", "type", "severity", "rationale"],
            },
        }
    },
    "required": ["rules"],
}


# ----------------------------------------------------------------------------- evidence

def build_evidence(table_results: List[Any]) -> List[Dict[str, Any]]:
    """What the model may use: per table its columns, measured formats and date pairs."""
    tables = []
    for result in table_results:
        columns = []
        for column in result.column_metrics:
            formats = {}
            for name, stats in (column.format_stats or {}).items():
                checked = stats.get("checked") or 0
                if checked and stats.get("valid", 0) / checked >= 0.2:
                    formats[name] = round(stats["valid"] * 100.0 / checked, 1)
            columns.append({
                "name": column.name,
                "type": column.data_type,
                "missing_percent": round(column.missing_percent, 2),
                "distinct_percent": round(column.distinct_percent, 2),
                "is_id_like": bool(column.is_id_like),
                "format_valid_percent": formats,
            })
        date_pairs = [
            {"first": pair["first"], "second": pair["second"], "rows_compared": pair["compared"]}
            for pair in ((result.cross_checks or {}).get("date_order") or [])
        ]
        tables.append({
            "table": result.table_name,
            "rows": result.dataset_metrics.row_count,
            "duplicate_percent": round(result.dataset_metrics.duplicate_percent, 2),
            "columns": columns,
            "date_pairs": date_pairs,
        })
    return tables


def build_prompt(business_context: Dict[str, str], evidence: List[Dict[str, Any]]) -> str:
    context = business_context or {}
    return f"""You are a data-quality engineer. Decide which checks tell whether THIS data fits THIS goal.

USER GOAL: {context.get("objective") or "Not provided"}
DOMAIN: {context.get("domain") or "Not provided"}
ONE ROW REPRESENTS: {context.get("row_grain") or "Not provided"}

Propose between 3 and 12 rules that matter for the goal. Allowed rule types:
- NOT_NULL: the column must never be empty.
- MAX_MISSING_PERCENT: at most threshold (0-100) percent of the column may be empty.
- UNIQUE: no value repeats. Only for the column that identifies what ONE ROW represents.
- MIN_FORMAT_VALID_PERCENT: at least threshold (0-100) percent of filled values must match "format"
  (EMAIL, PHONE, URL, DATE or NUMBER). Only for columns that have format_valid_percent in the evidence.
- DATE_ORDER: "column" must not be after "second_column". Only for pairs listed in date_pairs. threshold is the
  percent of rows allowed to break it (0 by default).
- MAX_DUPLICATE_PERCENT: table-level, at most threshold percent of rows may be exact duplicates.

HARD RULES:
- Use only the exact table and column names of the evidence. Never invent a column or a format.
- A column that repeats on purpose (for example a customer id when one row is an account) must not be UNIQUE.
- severity is CRITICAL when the goal cannot be reached if the rule fails, otherwise WARNING.
- Choose thresholds a person would accept for the goal, not the value that is observed today.
- rationale is one sentence: name the column, quote the observed figure from the evidence and say why the goal needs it.
- Return only JSON: {{"rules": [{{"table", "column", "type", "format", "second_column", "threshold", "severity", "rationale"}}]}}.
  Leave "format" and "second_column" out when the type does not use them.

EVIDENCE JSON:
{json.dumps(evidence, ensure_ascii=True, indent=1)}"""


# ----------------------------------------------------------------------------- validation

def _text(value: Any) -> str:
    return "" if value is None else str(value).strip()


def _number(value: Any) -> Optional[float]:
    if isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if number == number and number not in (float("inf"), float("-inf")) else None


def _severity(value: Any) -> str:
    return "CRITICAL" if _text(value).upper() in ("CRITICAL", "HIGH", "BLOCKING", "SEVERE") else "WARNING"


def _rule_key(rule: Dict[str, Any]) -> Tuple:
    return (rule["table_name"], rule["column_name"], rule["type"], rule.get("format"), rule.get("second_column"))


def validate_suggestions(raw: Any, evidence: List[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], List[str]]:
    """Keep only the rules that can be checked on this data. Returns (rules, reasons dropped)."""
    items = raw.get("rules") if isinstance(raw, dict) else raw
    if not isinstance(items, list):
        return [], ["The answer has no list of rules"]
    tables = {table["table"]: table for table in evidence}
    rules: List[Dict[str, Any]] = []
    dropped: List[str] = []
    seen = set()
    for item in items:
        if not isinstance(item, dict):
            dropped.append("An entry is not an object")
            continue
        rule, problem = _validate_one(item, tables)
        if problem:
            dropped.append(problem)
            continue
        key = _rule_key(rule)
        if key in seen:
            continue
        seen.add(key)
        rules.append(rule)
        if len(rules) >= MAX_RULES:
            break
    return rules, dropped


def _validate_one(item: Dict[str, Any], tables: Dict[str, Dict[str, Any]]) -> Tuple[Dict[str, Any], Optional[str]]:
    rule_type = _text(item.get("type")).upper()
    table_name = _text(item.get("table") or item.get("table_name"))
    column_name = _text(item.get("column") or item.get("column_name"))
    label = f"{rule_type or '?'} on {table_name or '?'}.{column_name or '*'}"
    if rule_type not in SUGGESTIBLE_TYPES:
        return {}, f"{label}: unknown rule type"
    table = tables.get(table_name)
    if table is None:
        return {}, f"{label}: unknown table"
    columns = {column["name"]: column for column in table["columns"]}

    rule: Dict[str, Any] = {
        "table_name": table_name,
        "column_name": None,
        "type": rule_type,
        "severity": _severity(item.get("severity")),
        "rationale": _text(item.get("rationale"))[:MAX_RATIONALE_CHARS],
    }
    if not rule["rationale"]:
        return {}, f"{label}: no rationale"

    if rule_type != "MAX_DUPLICATE_PERCENT":
        if column_name not in columns:
            return {}, f"{label}: unknown column"
        rule["column_name"] = column_name

    if rule_type == "MIN_FORMAT_VALID_PERCENT":
        format_name = _text(item.get("format")).upper()
        if format_name not in FORMATS:
            return {}, f"{label}: unknown format"
        if format_name not in columns[column_name]["format_valid_percent"]:
            return {}, f"{label}: {format_name} was not measured on this column"
        rule["format"] = format_name

    if rule_type == "DATE_ORDER":
        second = _text(item.get("second_column") or item.get("second"))
        pairs = {(pair["first"], pair["second"]) for pair in table["date_pairs"]}
        pairs |= {(second_name, first_name) for first_name, second_name in pairs}
        if (column_name, second) not in pairs:
            return {}, f"{label}: {column_name} and {second} are not a measured date pair"
        rule["second_column"] = second

    if rule_type in NEEDS_THRESHOLD:
        threshold = _number(item.get("threshold"))
        if threshold is None:
            threshold = DEFAULT_THRESHOLDS[rule_type]
        if not 0 <= threshold <= 100:
            return {}, f"{label}: threshold {threshold} is outside 0-100"
        rule["threshold"] = round(threshold, 2)
    return rule, None


# ----------------------------------------------------------------------------- without the model

def _hinted(name: str, hints: Tuple[str, ...]) -> bool:
    lowered = re.sub(r"[^a-z]+", "_", name.lower())
    return any(hint in lowered for hint in hints)


def deterministic_suggestions(evidence: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Rules read straight from the measurements: formats columns already follow, and date pairs by name."""
    rules: List[Dict[str, Any]] = []
    for table in evidence:
        for column in table["columns"]:
            for format_name, valid_percent in column["format_valid_percent"].items():
                named = _hinted(column["name"], FORMAT_NAME_HINTS.get(format_name, ()))
                if valid_percent >= MIN_FORMAT_SHARE * 100 and (named or format_name in ("EMAIL", "URL")):
                    rules.append({
                        "table_name": table["table"],
                        "column_name": column["name"],
                        "type": "MIN_FORMAT_VALID_PERCENT",
                        "format": format_name,
                        "threshold": DEFAULT_THRESHOLDS["MIN_FORMAT_VALID_PERCENT"],
                        "severity": "WARNING",
                        "rationale": (
                            f"{valid_percent}% of the filled values in {column['name']} already look like "
                            f"{format_name.lower()} values; this keeps the rest from drifting."
                        ),
                    })
        for pair in table["date_pairs"]:
            ordered = _ordered_pair(pair["first"], pair["second"])
            if ordered:
                rules.append({
                    "table_name": table["table"],
                    "column_name": ordered[0],
                    "type": "DATE_ORDER",
                    "second_column": ordered[1],
                    "threshold": DEFAULT_THRESHOLDS["DATE_ORDER"],
                    "severity": "WARNING",
                    "rationale": f"{ordered[0]} should not come after {ordered[1]} on the {pair['rows_compared']} rows where both are filled.",
                })
    return rules[:MAX_RULES]


def _ordered_pair(first: str, second: str) -> Optional[Tuple[str, str]]:
    if _hinted(first, START_HINTS) and _hinted(second, END_HINTS):
        return first, second
    if _hinted(second, START_HINTS) and _hinted(first, END_HINTS):
        return second, first
    return None


# ----------------------------------------------------------------------------- orchestration

def suggest_rules(
    table_results: List[Any],
    business_context: Dict[str, str],
    llm_call=None,
    parse=None,
    **llm_options: Any,
) -> Dict[str, Any]:
    """
    Rules for the user's goal. Returns {"rules": [...], "source": "llm"|"measurements", "notes": [...]}.

    ``llm_call(prompt, format_schema=..., **llm_options)`` returns the model's text and ``parse`` turns
    it into JSON; both are injected so the logic can be tested without a model.
    """
    evidence = build_evidence(table_results)
    notes: List[str] = []
    baseline = deterministic_suggestions(evidence)
    has_goal = bool((business_context or {}).get("objective"))

    llm_rules: List[Dict[str, Any]] = []
    if has_goal and llm_call is not None and parse is not None:
        try:
            text = llm_call(build_prompt(business_context, evidence), format_schema=RULE_SCHEMA, **llm_options)
            answer = parse(text)
            llm_rules, dropped = validate_suggestions(answer, evidence)
            notes.extend(f"Dropped: {reason}" for reason in dropped)
            if answer is None:
                notes.append("The model did not return JSON")
        except Exception as exc:  # the model being unreachable or slow must never fail the run
            notes.append(f"The model could not be used: {exc}")
    elif not has_goal:
        notes.append("No goal was given, so only rules read from the measurements are proposed")

    merged: List[Dict[str, Any]] = []
    seen = set()
    for rule in llm_rules + baseline:
        key = _rule_key({**rule, "format": rule.get("format"), "second_column": rule.get("second_column")})
        if key not in seen:
            seen.add(key)
            merged.append(rule)
    return {"rules": merged[:MAX_RULES], "source": "llm" if llm_rules else "measurements", "notes": notes}
