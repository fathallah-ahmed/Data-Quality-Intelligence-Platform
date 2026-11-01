"""
Format and cross-column checks measured on the real values of a table.

The backend only stores metrics, so the checks that need the values (is this an email? does one
date come before another?) are measured here once per run. The backend then judges them against
the rules the user approved, without having to read the files again.
"""
import re
import warnings
from typing import Any, Dict, List, Optional

import pandas as pd

# Large columns are measured on a fixed sample so a run stays fast and gives the same answer twice.
MAX_VALUES = 20_000
MAX_EXAMPLES = 3
MAX_EXAMPLE_CHARS = 60
MAX_DATE_COLUMNS = 6
DATE_COLUMN_SHARE = 0.8

FORMATS = ("EMAIL", "PHONE", "URL", "DATE", "NUMBER")

_EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s.]{2,}$")
_PHONE = re.compile(r"^\+?\d{7,15}$")
_URL = re.compile(r"^(https?://|www\.)\S+\.\S+$", re.IGNORECASE)
# A date has digits and a separator or a month name; this keeps plain numbers out of the date parser.
_DATE_CANDIDATE = re.compile(r"\d")
_DATE_SEPARATOR = re.compile(r"[-/.: ]|[A-Za-z]{3}")


def _is_text(series: pd.Series) -> bool:
    return pd.api.types.is_object_dtype(series) or pd.api.types.is_string_dtype(series)


def _text_values(series: pd.Series) -> pd.Series:
    """The non-empty values as trimmed text, sampled when the column is large."""
    values = series.dropna().astype(str).str.strip()
    values = values[values != ""]
    if len(values) > MAX_VALUES:
        values = values.sample(MAX_VALUES, random_state=0)
    return values


def _clip(value: str) -> str:
    return value if len(value) <= MAX_EXAMPLE_CHARS else value[: MAX_EXAMPLE_CHARS - 1] + "…"


def parse_dates(values: pd.Series) -> pd.Series:
    """Datetimes for the values that really look like dates, NaT for everything else."""
    text = values.astype(str).str.strip()
    candidate = text.map(lambda item: bool(_DATE_CANDIDATE.search(item) and _DATE_SEPARATOR.search(item)))
    parsed = pd.Series(pd.NaT, index=values.index, dtype="datetime64[ns]")
    if not candidate.any():
        return parsed
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        try:
            parsed.loc[candidate] = pd.to_datetime(text[candidate], errors="coerce", format="mixed", utc=False)
        except (TypeError, ValueError):
            parsed.loc[candidate] = pd.to_datetime(text[candidate], errors="coerce")
    return parsed


def _valid_mask(name: str, values: pd.Series) -> pd.Series:
    if name == "EMAIL":
        return values.map(lambda item: bool(_EMAIL.match(item)))
    if name == "PHONE":
        return values.str.replace(r"[\s.\-()]", "", regex=True).map(lambda item: bool(_PHONE.match(item)))
    if name == "URL":
        return values.map(lambda item: bool(_URL.match(item)))
    if name == "DATE":
        return parse_dates(values).notna()
    if name == "NUMBER":
        return pd.to_numeric(values.str.replace(",", ".", regex=False), errors="coerce").notna()
    raise ValueError(f"Unknown format {name}")


def profile_formats(series: pd.Series) -> Dict[str, Dict[str, Any]]:
    """
    For a text column: how many of its values match each known format, with a few that do not.

    Numeric and date columns are not text, so they get no entry (an empty dict).
    """
    if not _is_text(series):
        return {}
    values = _text_values(series)
    if values.empty:
        return {}
    profile: Dict[str, Dict[str, Any]] = {}
    for name in FORMATS:
        valid = _valid_mask(name, values)
        invalid_values = values[~valid]
        profile[name] = {
            "checked": int(len(values)),
            "valid": int(valid.sum()),
            "invalid_examples": [_clip(item) for item in invalid_values.drop_duplicates().head(MAX_EXAMPLES)],
        }
    return profile


def date_columns(df: pd.DataFrame, profiles: Optional[Dict[str, Dict[str, Any]]] = None) -> List[str]:
    """Columns that hold dates: real datetimes, or text where most values parse as dates."""
    found: List[str] = []
    for column in df.columns:
        series = df[column]
        if pd.api.types.is_datetime64_any_dtype(series):
            found.append(str(column))
            continue
        stats = ((profiles or {}).get(str(column)) or profile_formats(series)).get("DATE")
        if stats and stats["checked"] and stats["valid"] / stats["checked"] >= DATE_COLUMN_SHARE:
            found.append(str(column))
    return found[:MAX_DATE_COLUMNS]


def _as_dates(series: pd.Series) -> pd.Series:
    if pd.api.types.is_datetime64_any_dtype(series):
        return series
    return parse_dates(series.where(series.notna(), ""))


def date_order_checks(df: pd.DataFrame, profiles: Optional[Dict[str, Dict[str, Any]]] = None) -> List[Dict[str, Any]]:
    """
    For every pair of date columns: on the rows where both are filled, how often the first is
    after the second and the other way round. A rule "first must not come after second" reads it.
    """
    columns = date_columns(df, profiles)
    if len(columns) < 2:
        return []
    frame = df if len(df) <= MAX_VALUES else df.sample(MAX_VALUES, random_state=0)
    dates = {column: _as_dates(frame[column]) for column in columns}
    checks: List[Dict[str, Any]] = []
    for index, first in enumerate(columns):
        for second in columns[index + 1:]:
            both = dates[first].notna() & dates[second].notna()
            compared = int(both.sum())
            if compared == 0:
                continue
            first_after = both & (dates[first] > dates[second])
            second_after = both & (dates[second] > dates[first])
            checks.append({
                "first": first,
                "second": second,
                "compared": compared,
                "first_after_second": int(first_after.sum()),
                "second_after_first": int(second_after.sum()),
                "first_after_second_examples": _pair_examples(frame, first, second, first_after),
                "second_after_first_examples": _pair_examples(frame, first, second, second_after),
            })
    return checks


def _pair_examples(frame: pd.DataFrame, first: str, second: str, mask: pd.Series) -> List[str]:
    rows = frame.loc[mask, [first, second]].head(MAX_EXAMPLES)
    return [_clip(f"{first}={row[first]} | {second}={row[second]}") for _, row in rows.iterrows()]
