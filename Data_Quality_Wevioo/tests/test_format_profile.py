"""Format and date-order measurements that the backend rules rely on."""
import pandas as pd

from dq_pipeline.extractors import format_profile as fp


def test_email_share_counts_only_well_formed_addresses():
    emails = pd.Series(["a@mail.com", "b@mail.com", "c.mail.com", None, "d@mail", "  e@x.org  "])

    stats = fp.profile_formats(emails)["EMAIL"]

    assert stats["checked"] == 5
    assert stats["valid"] == 3
    assert set(stats["invalid_examples"]) == {"c.mail.com", "d@mail"}


def test_phone_formats_ignore_separators():
    phones = pd.Series(["+33 6 12 34 56 78", "06.12.34.56.78", "(555) 123-4567", "12", "call me"])

    assert fp.profile_formats(phones)["PHONE"]["valid"] == 3


def test_urls_and_numbers():
    assert fp.profile_formats(pd.Series(["https://a.com/x", "www.b.org", "nope"]))["URL"]["valid"] == 2
    assert fp.profile_formats(pd.Series(["12", "3,5", "7.25", "x1"]))["NUMBER"]["valid"] == 3


def test_numeric_columns_get_no_format_entry():
    assert fp.profile_formats(pd.Series([1, 2, 3])) == {}
    assert fp.profile_formats(pd.Series([None, None], dtype="object")) == {}


def test_plain_numbers_are_not_dates_but_real_dates_are():
    values = pd.Series(["2024-01-05", "05/02/2024", "12 Mar 2023", "20240105", "12345", "hello"])

    assert fp.profile_formats(values)["DATE"]["valid"] == 3


def _frame():
    return pd.DataFrame({
        "opened": ["2020-01-01", "2020-06-01", "2021-01-01", None],
        "closed": ["2020-12-31", "2020-05-01", None, "2022-01-01"],
        "name": ["a", "b", "c", "d"],
    })


def test_date_columns_are_found_among_text_columns():
    assert fp.date_columns(_frame()) == ["opened", "closed"]


def test_date_order_counts_both_directions_on_rows_where_both_are_filled():
    checks = fp.date_order_checks(_frame())

    assert len(checks) == 1
    check = checks[0]
    assert (check["first"], check["second"]) == ("opened", "closed")
    assert check["compared"] == 2
    assert check["first_after_second"] == 1
    assert check["second_after_first"] == 1
    assert check["first_after_second_examples"] == ["opened=2020-06-01 | closed=2020-05-01"]


def test_a_single_date_column_has_nothing_to_compare():
    frame = pd.DataFrame({"opened": ["2020-01-01", "2020-02-01"], "x": ["a", "b"]})

    assert fp.date_order_checks(frame) == []
