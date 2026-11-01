"""
What each column is for: identifier, email, amount, date...

A role is scored from three families of clues, and the clues that led to it are kept so the answer
can be explained: the words in the column name, the statistics of its values, and the formats its
values follow. No single clue decides (a unique number may be an amount, a zip code can be digits);
they add up. Only the columns where the clues stay unclear are put to the model, together with the
user's goal and what one row represents, and the model must pick one role from the same list.
"""
import re
from typing import Any, Dict, List, Optional, Tuple

import pandas as pd

ROLES = (
    "IDENTIFIER", "REFERENCE", "EMAIL", "PHONE", "URL", "DATE", "AMOUNT", "QUANTITY", "CATEGORY", "STATUS",
    "BOOLEAN", "NAME", "LOCATION", "POSTAL_CODE", "FREE_TEXT", "UNKNOWN",
)

SAMPLE_SIZE = 5000
MAX_EXAMPLES = 5
MAX_EVIDENCE = 4
UNCLEAR_BELOW = 0.65
CLOSE_MARGIN = 0.15
MIN_ROLE_SCORE = 0.3
FORMAT_SHARE = 0.8

# Words in a column name that point to a role. A word matches a name token exactly, or (for words of
# four letters or more) as the start of a token, so "telephone" and "telephone_number" both match.
LEXICON: Dict[str, Tuple[str, ...]] = {
    "IDENTIFIER": ("id", "identifier", "key", "uuid", "guid", "ref", "reference", "code"),
    "EMAIL": ("email", "mail", "courriel"),
    "PHONE": ("phone", "tel", "telephone", "mobile", "gsm", "fax"),
    "URL": ("url", "website", "link", "site"),
    "DATE": ("date", "dt", "time", "timestamp", "created", "updated", "birth", "dob", "naissance", "year"),
    "AMOUNT": ("amount", "balance", "price", "cost", "total", "revenue", "salary", "payment", "fee", "montant", "solde", "prix"),
    "QUANTITY": ("qty", "quantity", "count", "nb", "nombre", "units", "stock"),
    "STATUS": ("status", "statut", "flag", "active", "state"),
    "BOOLEAN": ("is", "has", "can", "bool", "boolean"),
    "NAME": ("name", "nom", "prenom", "firstname", "lastname", "surname", "fullname", "title", "label"),
    "LOCATION": ("country", "city", "region", "address", "street", "pays", "ville", "adresse", "province", "continent"),
    "POSTAL_CODE": ("zip", "postal", "postcode", "cp"),
    "FREE_TEXT": ("comment", "description", "note", "notes", "remark", "message", "text", "details"),
    "CATEGORY": ("type", "category", "segment", "class", "gender", "sex", "group", "kind", "genre"),
}
BOOLEAN_TOKENS = {"0", "1", "true", "false", "yes", "no", "y", "n", "oui", "non", "t", "f"}


def _tokens(name: str) -> List[str]:
    spaced = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", str(name))
    return [token for token in re.split(r"[^A-Za-z0-9]+", spaced.lower()) if token]


def _named(tokens: List[str], role: str) -> Optional[str]:
    for word in LEXICON.get(role, ()):
        for token in tokens:
            if token == word or (len(word) >= 4 and token.startswith(word)):
                return word
    return None


# ----------------------------------------------------------------------------- value traits

def value_traits(series: pd.Series) -> Dict[str, Any]:
    """Statistics about the shape of a column's values (on a sample when the column is large)."""
    values = series.dropna()
    if len(values) > SAMPLE_SIZE:
        values = values.sample(SAMPLE_SIZE, random_state=0)
    numeric = pd.api.types.is_numeric_dtype(series) and not pd.api.types.is_bool_dtype(series)
    traits: Dict[str, Any] = {"numeric": bool(numeric), "examples": []}
    if values.empty:
        return traits
    texts = values.astype(str).str.strip()
    texts = texts[texts != ""]
    if texts.empty:
        return traits
    lengths = texts.str.len()
    traits.update({
        "avg_length": round(float(lengths.mean()), 1),
        "fixed_length": bool(lengths.nunique() == 1),
        "digits_only_share": round(float(texts.str.fullmatch(r"\d+").mean()), 3),
        "leading_zero_share": round(float(texts.str.fullmatch(r"0\d+").mean()), 3),
        "space_share": round(float(texts.str.contains(" ").mean()), 3),
        "title_share": round(float(texts.str.match(r"^[A-Z][a-z]").mean()), 3),
        "bool_like": bool(0 < texts.str.lower().nunique() <= 2 and set(texts.str.lower().unique()) <= BOOLEAN_TOKENS),
        "examples": [item if len(item) <= 40 else item[:39] + "…" for item in texts.drop_duplicates().head(MAX_EXAMPLES)],
    })
    if numeric:
        numbers = pd.to_numeric(values, errors="coerce").dropna()
        if not numbers.empty:
            traits["integer_like"] = bool((numbers % 1 == 0).all())
            traits["non_negative"] = bool((numbers >= 0).all())
    return traits


# ----------------------------------------------------------------------------- scoring

class _Scores:
    def __init__(self) -> None:
        self.points: Dict[str, float] = {}
        self.evidence: Dict[str, List[str]] = {}

    def add(self, role: str, weight: float, why: str) -> None:
        self.points[role] = self.points.get(role, 0.0) + weight
        self.evidence.setdefault(role, []).append(why)


def _share(stats: Dict[str, Any], name: str) -> float:
    entry = (stats or {}).get(name) or {}
    checked = entry.get("checked") or 0
    return entry.get("valid", 0) / checked if checked else 0.0


def score_column(
    name: str,
    data_type: str,
    distinct_count: int,
    distinct_percent: float,
    format_stats: Dict[str, Any],
    traits: Dict[str, Any],
    rows: int,
) -> Dict[str, Any]:
    """Role, confidence, the clues behind it and the next best roles for one column."""
    tokens = _tokens(name)
    scores = _Scores()
    is_float = "float" in (data_type or "").lower()
    is_datetime = "datetime" in (data_type or "").lower()

    # --- words in the name
    for role in LEXICON:
        word = _named(tokens, role)
        if word:
            scores.add(role, 0.5, f"the name contains '{word}'")
    if "IDENTIFIER" in scores.points:
        # An identifier that repeats points at another entity (a customer id on an account row).
        if distinct_percent < 90:
            scores.points.pop("IDENTIFIER", None)
            scores.evidence.pop("IDENTIFIER", None)
            scores.add("IDENTIFIER", 0.5, "the name looks like an identifier")
            scores.add("REFERENCE", 0.5, "the name looks like an identifier")
            scores.add("REFERENCE", 0.2, f"its values repeat ({distinct_count} distinct values over {rows} rows)")

    named = set(scores.points)

    # --- statistics of the values
    if distinct_percent >= 99 and not is_float and rows > 1:
        scores.add("IDENTIFIER", 0.3, f"{distinct_percent:.1f}% of its values are distinct")
        if traits.get("fixed_length") and not traits.get("numeric"):
            scores.add("IDENTIFIER", 0.1, "every value has the same length")
    if traits.get("bool_like"):
        scores.add("BOOLEAN", 0.6, "it only holds two values that read as yes/no")
    if rows >= 20 and (distinct_count <= 20 or distinct_percent <= 5) and not is_float and not traits.get("bool_like"):
        scores.add("CATEGORY", 0.4, f"only {distinct_count} distinct values over {rows} rows")
        if "STATUS" in scores.points:
            scores.add("STATUS", 0.2, "few distinct values, like a status")
        if "LOCATION" in scores.points:
            scores.add("LOCATION", 0.2, "few distinct values, like a country or region")
    if traits.get("numeric"):
        if traits.get("integer_like") is False:
            scores.add("AMOUNT", 0.3, "it holds decimal numbers")
        elif traits.get("integer_like") and traits.get("non_negative") and "QUANTITY" in named:
            scores.add("QUANTITY", 0.2, "it holds whole non-negative numbers")
        if "AMOUNT" in named:
            scores.add("AMOUNT", 0.2, "it holds numbers")
    if traits.get("avg_length", 0) > 40:
        scores.add("FREE_TEXT", 0.4, f"values are long ({traits['avg_length']:.0f} characters on average)")
    if traits.get("space_share", 0) > 0.5 and distinct_percent > 50:
        scores.add("FREE_TEXT", 0.1, "most values hold several words and few repeat")
    if (traits.get("title_share", 0) >= 0.6 and traits.get("avg_length", 99) < 40 and distinct_percent >= 50
            and not traits.get("numeric")):
        scores.add("NAME", 0.2, "values are capitalised words that rarely repeat")
    if traits.get("digits_only_share", 0) >= 0.8 and traits.get("fixed_length") and "POSTAL_CODE" in scores.points:
        scores.add("POSTAL_CODE", 0.3, "digits of a fixed length: keep it as text so leading zeros survive")
    if traits.get("leading_zero_share", 0) > 0.05 and "POSTAL_CODE" in scores.points:
        scores.add("POSTAL_CODE", 0.1, "some values start with zeros")

    # --- formats of the values
    for role, format_name in (("EMAIL", "EMAIL"), ("URL", "URL"), ("DATE", "DATE")):
        share = _share(format_stats, format_name)
        if share >= FORMAT_SHARE:
            scores.add(role, 0.6, f"{share * 100:.0f}% of its values look like {format_name.lower()} values")
    if is_datetime:
        scores.add("DATE", 0.6, "its type is a date and time")
    phone_share = _share(format_stats, "PHONE")
    if phone_share >= FORMAT_SHARE:
        # Long digit strings are also identifiers: the format only counts for much when the name agrees.
        scores.add("PHONE", 0.4 if "PHONE" in scores.points else 0.1, f"{phone_share * 100:.0f}% of its values look like phone numbers")
    if _share(format_stats, "NUMBER") >= 0.9 and not traits.get("numeric"):
        for role in ("AMOUNT", "QUANTITY"):
            if role in scores.points:
                scores.add(role, 0.1, "numbers stored as text")

    ranked = sorted(((min(1.0, points), role) for role, points in scores.points.items()), reverse=True)
    if not ranked or ranked[0][0] < MIN_ROLE_SCORE:
        return {"role": "UNKNOWN", "confidence": 0.0, "evidence": ["no name, value or format clue points to a role"],
                "alternatives": [{"role": role, "score": round(score, 2)} for score, role in ranked[:3]], "source": "rules"}
    top_score, top_role = ranked[0]
    return {
        "role": top_role,
        "confidence": round(top_score, 2),
        "evidence": scores.evidence[top_role][:MAX_EVIDENCE],
        "alternatives": [{"role": role, "score": round(score, 2)} for score, role in ranked[1:4]],
        "source": "rules",
    }


def is_unclear(result: Dict[str, Any]) -> bool:
    """The clues did not settle it: low confidence, or the second role is nearly as likely."""
    if result["confidence"] < UNCLEAR_BELOW:
        return True
    alternatives = result.get("alternatives") or []
    return bool(alternatives and alternatives[0]["score"] >= 0.4 and result["confidence"] - alternatives[0]["score"] < CLOSE_MARGIN)


def assign_roles(column_metrics: List[Any], rows: int) -> None:
    """Set role, confidence, evidence and source on every ColumnMetrics from its own measurements."""
    for column in column_metrics:
        result = score_column(
            column.name, column.data_type, column.distinct_count, column.distinct_percent,
            column.format_stats or {}, column.traits or {}, rows,
        )
        _apply(column, result)
        column.role_alternatives = result["alternatives"]


def _apply(column: Any, result: Dict[str, Any]) -> None:
    column.role = result["role"]
    column.role_confidence = result["confidence"]
    column.role_evidence = list(result["evidence"])
    column.role_source = result["source"]


# ----------------------------------------------------------------------------- the model decides the unclear ones

LLM_SCHEMA = {
    "type": "object",
    "properties": {
        "roles": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "column": {"type": "string"},
                    "role": {"type": "string", "enum": list(ROLES)},
                    "reason": {"type": "string"},
                },
                "required": ["column", "role", "reason"],
            },
        }
    },
    "required": ["roles"],
}


def unclear_columns(column_metrics: List[Any]) -> List[Any]:
    return [
        column for column in column_metrics
        if is_unclear({"confidence": column.role_confidence, "alternatives": getattr(column, "role_alternatives", [])})
    ]


def build_prompt(table_name: str, columns: List[Any], business_context: Dict[str, str], rows: int) -> str:
    context = business_context or {}
    entries = [
        {
            "column": column.name,
            "type": column.data_type,
            "distinct_values": column.distinct_count,
            "missing_percent": round(column.missing_percent, 2),
            "examples": (column.traits or {}).get("examples", []),
            "candidates": [{"role": column.role, "score": column.role_confidence, "because": column.role_evidence}]
            + [{"role": alt["role"], "score": alt["score"]} for alt in getattr(column, "role_alternatives", [])],
        }
        for column in columns
    ]
    import json

    return f"""Decide what each of these columns is for. The clues from the names, values and formats did not settle it.

TABLE: {table_name} ({rows} rows)
DOMAIN: {context.get("domain") or "Not provided"}
GOAL: {context.get("objective") or "Not provided"}
ONE ROW REPRESENTS: {context.get("row_grain") or "Not provided"}

ROLES you may choose from: {", ".join(ROLES)}.
- IDENTIFIER names the entity one row represents; REFERENCE points to another entity and may repeat on purpose.
- Pick UNKNOWN when the clues really do not say.
- reason: one sentence that names the column and the observed fact (an example value, a count).
- Return only JSON: {{"roles": [{{"column", "role", "reason"}}]}}, one entry per column listed below.

COLUMNS:
{json.dumps(entries, ensure_ascii=True, indent=1)}"""


def refine_with_model(
    column_metrics: List[Any],
    table_name: str,
    rows: int,
    business_context: Dict[str, str],
    llm_call,
    parse,
) -> List[str]:
    """
    Let the model settle the unclear columns. Its choice must be a known role for a column that was
    asked about, with a reason. Returns notes about what could not be used; never raises.
    """
    unclear = unclear_columns(column_metrics)
    if not unclear:
        return []
    notes: List[str] = []
    try:
        answer = parse(llm_call(build_prompt(table_name, unclear, business_context, rows), format_schema=LLM_SCHEMA))
    except Exception as exc:
        return [f"The model could not be used for column roles: {exc}"]
    items = answer.get("roles") if isinstance(answer, dict) else answer
    if not isinstance(items, list):
        return ["The model did not return a list of roles"]
    by_name = {column.name: column for column in unclear}
    for item in items:
        if not isinstance(item, dict):
            continue
        column = by_name.get(str(item.get("column") or "").strip())
        role = str(item.get("role") or "").strip().upper()
        reason = str(item.get("reason") or "").strip()
        if column is None:
            notes.append(f"Ignored a role for an unknown column: {item.get('column')}")
        elif role not in ROLES:
            notes.append(f"Ignored an unknown role for {column.name}: {role}")
        elif not reason:
            notes.append(f"Ignored the role of {column.name}: no reason given")
        else:
            candidates = [column.role] + [alt["role"] for alt in getattr(column, "role_alternatives", [])[:1]]
            column.role = role
            column.role_confidence = 0.8 if role in candidates else 0.6
            column.role_evidence = [f"the model chose it: {reason[:300]}"] + column.role_evidence[: MAX_EVIDENCE - 1]
            column.role_source = "llm"
    return notes


def describe_roles(column_metrics: List[Any]) -> str:
    """One line for the table prompt, so the written analysis agrees with the detected roles."""
    known = [f"{column.name}={column.role}" for column in column_metrics if column.role and column.role != "UNKNOWN"]
    return ("Detected column roles (from names, values and formats): " + ", ".join(known) + ".") if known else ""
