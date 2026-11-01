"""The table analysis must keep specific model answers even when their shape drifts from the schema."""
from dq_pipeline.analyzers import langgraph_analyzer as lg


def _evidence():
    features = [
        {
            "feature_name": name,
            "data_type": "TYPE_NUM",
            "base_stats": {
                "total_rows": 100,
                "num_missing": {"number": missing, "perc": float(missing)},
                "num_distinct": {"number": 100, "perc": 100.0},
            },
        }
        for name, missing in (("customer_id", 0), ("phone", 88))
    ]
    data = {"summary_source": {"num_rows": 100, "num_columns": 2, "duplicates": 0}, "features": features}
    return lg._build_table_evidence("customers", data)


def _flat_answer():
    return {
        "table_summary": "customers holds 100 rows; phone is 88% missing.",
        "column_roles": [
            {
                "column": "phone",
                "description": "88% of values are missing",
                "business_meaning": "contact number",
                "usage_notes": "not usable for campaigns",
            }
        ],
        "data_contract_rules": [{"column": "customer_id", "condition": "distinct_count must equal", "threshold": 100, "severity": "critical"}],
        "monitoring_rules": [{"column": "phone", "condition": "alert if missing_percent exceeds", "threshold": 90}],
        "data_owner_questions": [{"column": "phone", "question": "Why is phone 88% empty?"}],
        "use_case_readiness": {"telemarketing": {"status": "not_ready", "rationale": "phone is 88% missing"}},
        "custom_answer": "phone is the main risk at 88% missing.",
        "supporting_evidence": ["phone: 88 of 100 rows missing"],
    }


def test_flat_answer_is_accepted_and_keeps_its_content():
    analysis, errors, _ = lg._validate_and_normalize_analysis(_flat_answer(), _evidence())

    assert errors == []
    semantic = analysis["semantic_analysis"]
    assert semantic["executive_summary"] == "customers holds 100 rows; phone is 88% missing."
    phone = next(role for role in semantic["column_roles"] if role["column"] == "phone")
    assert phone["description"] == "88% of values are missing"
    assert semantic["data_contract_rules"][0]["severity"] == "high"
    assert semantic["data_contract_rules"][0]["rule"].startswith("distinct_count must equal 100")
    assert semantic["monitoring_rules"][0]["check"] == "alert if missing_percent exceeds"
    assert semantic["monitoring_rules"][0]["cadence"]
    assert semantic["data_owner_questions"] == ["Why is phone 88% empty?"]
    assert semantic["use_case_readiness"][0]["status"] == "not_ready"
    assert analysis["custom_analysis"]["answer"].startswith("phone is the main risk")


def test_ordinary_words_are_not_mistaken_for_columns():
    answer = _flat_answer()
    answer["custom_answer"] = "There are 100 rows. The main risk is phone, which is 88% missing."
    _, errors, warnings = lg._validate_and_normalize_analysis(answer, _evidence())

    assert errors == []
    assert any("Unrecognised column reference" in warning for warning in warnings)


def test_completeness_percentages_are_not_read_as_missingness():
    answer = _flat_answer()
    answer["column_roles"].append(
        {"column": "customer_id", "description": "customer_id is 100% complete and has 0 missing values",
         "business_meaning": "key", "usage_notes": "join key"}
    )
    _, errors, _ = lg._validate_and_normalize_analysis(answer, _evidence())

    assert errors == []


def test_wrong_missingness_percentage_is_still_rejected():
    answer = _flat_answer()
    answer["column_roles"][0]["description"] = "phone has 10% missing values"
    _, errors, _ = lg._validate_and_normalize_analysis(answer, _evidence())

    assert any("phone" in error and "10.00%" in error for error in errors)


def test_invented_field_is_still_rejected():
    answer = _flat_answer()
    answer["data_contract_rules"][0]["column"] = "loyalty_tier"
    _, errors, _ = lg._validate_and_normalize_analysis(answer, _evidence())

    assert any("loyalty_tier" in error for error in errors)


def test_other_percentages_in_the_same_sentence_are_not_missingness_claims():
    answer = _flat_answer()
    answer["column_roles"][0]["description"] = "phone is 88% missing, and 61% of rows share one prefix"
    _, errors, _ = lg._validate_and_normalize_analysis(answer, _evidence())

    assert errors == []


def test_shortened_values_with_ellipsis_are_allowed_but_bare_placeholders_are_not():
    answer = _flat_answer()
    answer["data_owner_questions"] = [{"column": "phone", "question": "Is 'call back after the quarterly rev...' a valid phone value?"}]
    _, errors, _ = lg._validate_and_normalize_analysis(answer, _evidence())
    assert errors == []

    answer["custom_answer"] = "..."
    _, errors, _ = lg._validate_and_normalize_analysis(answer, _evidence())
    assert any("Ellipsis" in error or "Placeholder" in error for error in errors)


def test_each_percentage_in_a_multi_column_sentence_is_checked_against_its_own_column():
    answer = _flat_answer()
    answer["column_roles"][0]["description"] = (
        "Missing values: customer_id has 0% missing values (0 missing); phone has 88% missing values (88 missing)"
    )
    _, errors, _ = lg._validate_and_normalize_analysis(answer, _evidence())
    assert errors == []

    answer["column_roles"][0]["description"] = (
        "Missing values: customer_id has 0% missing values; phone has 40% missing values"
    )
    _, errors, _ = lg._validate_and_normalize_analysis(answer, _evidence())
    assert any("phone" in error and "40.00%" in error for error in errors)
