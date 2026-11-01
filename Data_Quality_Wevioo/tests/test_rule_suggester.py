"""Rules proposed from the user's goal: only checkable ones survive, and the pipeline works without a model."""
from dq_pipeline.analyzers import rule_suggester as rs
from dq_pipeline.models import ColumnMetrics, DatasetMetrics, TableAnalysisResult


def _column(name, data_type="object", missing=0.0, formats=None, id_like=False):
    return ColumnMetrics(
        name=name, data_type=data_type, missing_count=0, missing_percent=missing,
        distinct_count=10, distinct_percent=50.0, is_id_like=id_like,
        format_stats={
            fmt: {"checked": 100, "valid": valid, "invalid_examples": []} for fmt, valid in (formats or {}).items()
        },
    )


def _table(columns, pairs=None, name="accounts"):
    return TableAnalysisResult(
        dataset_id="1", version_id="1", pipeline_run_id="run-1", table_name=name,
        analysis_timestamp="t", processing_duration_seconds=0.0,
        dataset_metrics=DatasetMetrics(100, len(columns), 0, 0, 0, 0, 0, 0.0),
        column_metrics=columns,
        cross_checks={"date_order": pairs or []},
    )


def _tables():
    return [_table(
        [
            _column("account_id", id_like=True),
            _column("email", missing=12.0, formats={"EMAIL": 95, "URL": 0}),
            _column("opened_on", formats={"DATE": 100}),
            _column("closed_on", formats={"DATE": 100}),
            _column("balance", data_type="int64"),
        ],
        pairs=[{"first": "opened_on", "second": "closed_on", "compared": 80}],
    )]


def _rule(**changes):
    rule = {"table": "accounts", "column": "email", "type": "MIN_FORMAT_VALID_PERCENT", "format": "EMAIL",
            "threshold": 99, "severity": "CRITICAL", "rationale": "email is needed to contact customers"}
    rule.update(changes)
    return rule


def _validate(*rules):
    return rs.validate_suggestions({"rules": list(rules)}, rs.build_evidence(_tables()))


def test_a_checkable_rule_is_kept_with_its_values():
    rules, dropped = _validate(_rule())

    assert dropped == []
    assert rules == [{
        "table_name": "accounts", "column_name": "email", "type": "MIN_FORMAT_VALID_PERCENT", "format": "EMAIL",
        "threshold": 99.0, "severity": "CRITICAL", "rationale": "email is needed to contact customers",
    }]


def test_invented_tables_columns_types_and_formats_are_dropped():
    rules, dropped = _validate(
        _rule(table="ghost"),
        _rule(column="loyalty_tier"),
        _rule(type="CHECK_SOMETHING"),
        _rule(format="IBAN"),
        _rule(column="balance"),  # no format was measured on a numeric column
    )

    assert rules == []
    assert len(dropped) == 5


def test_date_order_needs_a_measured_pair_in_either_direction():
    forward, _ = _validate(_rule(column="opened_on", type="DATE_ORDER", second_column="closed_on", format=None, threshold=None))
    backward, _ = _validate(_rule(column="closed_on", type="DATE_ORDER", second_column="opened_on", format=None, threshold=None))
    unrelated, dropped = _validate(_rule(column="opened_on", type="DATE_ORDER", second_column="email", format=None))

    assert forward[0]["threshold"] == 0.0 and forward[0]["second_column"] == "closed_on"
    assert len(backward) == 1
    assert unrelated == [] and "not a measured date pair" in dropped[0]


def test_thresholds_default_and_must_stay_between_0_and_100():
    defaulted, _ = _validate(_rule(type="MAX_MISSING_PERCENT", format=None, threshold=None))
    too_big, dropped = _validate(_rule(threshold=150))

    assert defaulted[0]["threshold"] == 5.0
    assert too_big == [] and "outside 0-100" in dropped[0]


def test_severity_words_are_normalised_and_a_rationale_is_required():
    high, _ = _validate(_rule(severity="high"))
    low, _ = _validate(_rule(severity="minor"))
    none, dropped = _validate(_rule(rationale=" "))

    assert high[0]["severity"] == "CRITICAL" and low[0]["severity"] == "WARNING"
    assert none == [] and "no rationale" in dropped[0]


def test_repeated_rules_collapse_and_table_level_rules_need_no_column():
    rules, _ = _validate(_rule(), _rule(), _rule(type="MAX_DUPLICATE_PERCENT", column=None, format=None, threshold=1))

    assert [rule["type"] for rule in rules] == ["MIN_FORMAT_VALID_PERCENT", "MAX_DUPLICATE_PERCENT"]
    assert rules[1]["column_name"] is None


def test_a_bare_list_is_accepted_like_the_wrapped_answer():
    rules, _ = rs.validate_suggestions([_rule()], rs.build_evidence(_tables()))

    assert len(rules) == 1


def test_measurements_alone_suggest_formats_the_column_already_follows_and_named_date_pairs():
    rules = rs.deterministic_suggestions(rs.build_evidence(_tables()))

    kinds = {(rule["type"], rule["column_name"]) for rule in rules}
    assert ("MIN_FORMAT_VALID_PERCENT", "email") in kinds
    assert ("DATE_ORDER", "opened_on") in kinds
    date_rule = next(rule for rule in rules if rule["type"] == "DATE_ORDER")
    assert date_rule["second_column"] == "closed_on"
    assert all(rule["severity"] == "WARNING" for rule in rules)


def test_dates_without_a_start_and_end_in_their_names_get_no_guessed_order():
    tables = [_table(
        [_column("date_a", formats={"DATE": 100}), _column("date_b", formats={"DATE": 100})],
        pairs=[{"first": "date_a", "second": "date_b", "compared": 10}],
    )]

    assert rs.deterministic_suggestions(rs.build_evidence(tables)) == []


def test_model_rules_come_first_and_the_baseline_fills_in():
    outcome = rs.suggest_rules(
        _tables(), {"objective": "Contact customers"},
        llm_call=lambda prompt, **options: "answer",
        parse=lambda text: {"rules": [_rule(column="email", type="NOT_NULL", format=None, threshold=None)]},
    )

    assert outcome["source"] == "llm"
    assert outcome["rules"][0]["type"] == "NOT_NULL"
    assert any(rule["type"] == "DATE_ORDER" for rule in outcome["rules"])


def test_prompt_carries_the_goal_the_grain_and_the_measured_figures():
    prompt = rs.build_prompt(
        {"objective": "Contact customers", "row_grain": "one account"}, rs.build_evidence(_tables())
    )

    assert "Contact customers" in prompt and "one account" in prompt
    assert '"missing_percent": 12.0' in prompt


def test_an_unreachable_model_falls_back_to_the_measurements_without_failing():
    def down(prompt, **options):
        raise RuntimeError("Ollama is not reachable")

    outcome = rs.suggest_rules(_tables(), {"objective": "Contact customers"}, llm_call=down, parse=lambda t: None)

    assert outcome["source"] == "measurements"
    assert outcome["rules"]
    assert any("could not be used" in note for note in outcome["notes"])


def test_without_a_goal_the_model_is_not_asked():
    calls = []
    outcome = rs.suggest_rules(
        _tables(), {}, llm_call=lambda prompt, **options: calls.append(prompt), parse=lambda t: None
    )

    assert calls == []
    assert outcome["source"] == "measurements"
    assert any("No goal" in note for note in outcome["notes"])
