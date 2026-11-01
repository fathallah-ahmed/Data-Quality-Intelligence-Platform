"""Column roles: clues add up, the evidence is kept, and only unclear columns go to the model."""
import pandas as pd

from dq_pipeline.analyzers import column_roles as cr
from dq_pipeline.extractors.format_profile import profile_formats
from dq_pipeline.models import ColumnMetrics


def _metric(series, name, data_type=None):
    series = series.copy()
    rows = len(series)
    distinct = int(series.nunique())
    return ColumnMetrics(
        name=name, data_type=data_type or str(series.dtype), missing_count=int(series.isna().sum()),
        missing_percent=float(series.isna().mean() * 100), distinct_count=distinct,
        distinct_percent=distinct / rows * 100 if rows else 0.0,
        format_stats=profile_formats(series), traits=cr.value_traits(series),
    )


def _roles(frame):
    metrics = [_metric(frame[column], column) for column in frame.columns]
    cr.assign_roles(metrics, len(frame))
    return {metric.name: metric for metric in metrics}


def _accounts(rows=60):
    return pd.DataFrame({
        "account_id": [f"A{i:05d}" for i in range(rows)],
        "customer_id": [f"C{i % 20:04d}" for i in range(rows)],
        "email": [f"c{i}@mail.com" for i in range(rows)],
        "balance": [i * 10.5 for i in range(rows)],
        "country": ["FR", "DE", "ES"] * (rows // 3),
        "zip": [f"{i:05d}" for i in range(rows)],
        "opened_on": [f"2020-01-{i % 28 + 1:02d}" for i in range(rows)],
        "notes": ["called the customer and left a long message about the account " * 2] * rows,
        "mystery": [f"{i}" for i in range(rows)],
    })


def test_a_unique_id_and_a_repeating_id_get_different_roles():
    roles = _roles(_accounts())

    assert roles["account_id"].role == "IDENTIFIER"
    assert roles["customer_id"].role == "REFERENCE"
    assert any("repeat" in clue for clue in roles["customer_id"].role_evidence)


def test_name_values_and_format_agree_on_an_email():
    email = _roles(_accounts())["email"]

    assert email.role == "EMAIL"
    assert email.role_confidence == 1.0
    assert any("contains 'email'" in clue for clue in email.role_evidence)
    assert any("look like email" in clue for clue in email.role_evidence)


def test_decimal_numbers_with_an_amount_name_are_an_amount():
    assert _roles(_accounts())["balance"].role == "AMOUNT"


def test_a_postal_code_made_of_digits_is_not_taken_for_a_number():
    zipcode = _roles(_accounts())["zip"]

    assert zipcode.role == "POSTAL_CODE"
    assert any("leading zeros" in clue for clue in zipcode.role_evidence)


def test_dates_categories_and_long_text():
    roles = _roles(_accounts())

    assert roles["opened_on"].role == "DATE"
    assert roles["country"].role in ("LOCATION", "CATEGORY")
    assert roles["notes"].role == "FREE_TEXT"


def test_a_column_with_no_clue_is_unknown_and_unclear():
    mystery = _roles(_accounts())["mystery"]

    assert mystery.role in ("UNKNOWN", "IDENTIFIER")
    if mystery.role == "UNKNOWN":
        assert mystery.role_confidence == 0.0
        assert mystery in cr.unclear_columns([mystery])


def test_two_close_roles_make_a_column_unclear():
    assert cr.is_unclear({"confidence": 0.5, "alternatives": []})
    assert cr.is_unclear({"confidence": 0.7, "alternatives": [{"role": "CATEGORY", "score": 0.65}]})
    assert not cr.is_unclear({"confidence": 0.9, "alternatives": [{"role": "CATEGORY", "score": 0.3}]})


def _unclear_column():
    column = _metric(pd.Series([str(i) for i in range(40)]), "mystery")
    column.role, column.role_confidence, column.role_alternatives = "UNKNOWN", 0.0, [{"role": "IDENTIFIER", "score": 0.3}]
    column.role_evidence = ["no clue"]
    return column


def test_the_model_settles_an_unclear_column_with_a_known_role_and_a_reason():
    column = _unclear_column()

    notes = cr.refine_with_model(
        [column], "accounts", 40, {"objective": "Contact customers", "row_grain": "one account"},
        llm_call=lambda prompt, **options: "answer",
        parse=lambda text: {"roles": [{"column": "mystery", "role": "identifier", "reason": "mystery holds 40 distinct whole numbers"}]},
    )

    assert notes == []
    assert column.role == "IDENTIFIER"
    assert column.role_source == "llm"
    assert column.role_confidence == 0.8
    assert column.role_evidence[0].startswith("the model chose it")


def test_a_role_the_model_invents_or_gives_no_reason_for_is_ignored():
    column = _unclear_column()

    notes = cr.refine_with_model(
        [column], "accounts", 40, {}, llm_call=lambda prompt, **options: "answer",
        parse=lambda text: {"roles": [
            {"column": "mystery", "role": "WIZARD", "reason": "because"},
            {"column": "mystery", "role": "NAME", "reason": ""},
            {"column": "ghost", "role": "NAME", "reason": "x"},
        ]},
    )

    assert column.role == "UNKNOWN" and column.role_source == "rules"
    assert len(notes) == 3


def test_a_clear_column_is_never_sent_to_the_model_and_a_failing_model_changes_nothing():
    clear = _roles(_accounts())["email"]
    calls = []
    assert cr.refine_with_model([clear], "t", 60, {}, llm_call=lambda p, **o: calls.append(p), parse=lambda t: None) == []
    assert calls == []

    column = _unclear_column()

    def broken(prompt, **options):
        raise RuntimeError("down")

    notes = cr.refine_with_model([column], "t", 40, {}, llm_call=broken, parse=lambda t: None)
    assert column.role == "UNKNOWN" and "could not be used" in notes[0]


def test_prompt_lists_the_candidates_and_the_goal():
    prompt = cr.build_prompt("accounts", [_unclear_column()], {"objective": "Contact customers", "row_grain": "one account"}, 40)

    assert "Contact customers" in prompt and "one account" in prompt
    assert '"column": "mystery"' in prompt and "IDENTIFIER" in prompt


def test_roles_are_summarised_for_the_table_prompt():
    metrics = list(_roles(_accounts()).values())

    assert "email=EMAIL" in cr.describe_roles(metrics)
    assert cr.describe_roles([]) == ""
