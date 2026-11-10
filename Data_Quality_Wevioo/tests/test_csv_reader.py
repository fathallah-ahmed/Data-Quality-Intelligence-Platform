from dq_pipeline.extractors.comprehensive_sweetviz_extractor import read_csv_flex


def _write(tmp_path, name, text, encoding="utf-8"):
    path = tmp_path / name
    path.write_bytes(text.encode(encoding))
    return str(path)


def test_semicolon_cp1252_file_is_read_with_its_columns(tmp_path):
    path = _write(tmp_path, "products.csv", "id;name;category\n1;Café;Maison\n2;Thé;Jardin\n", "cp1252")
    df = read_csv_flex(path)
    assert list(df.columns) == ["id", "name", "category"]
    assert df["name"].tolist() == ["Café", "Thé"]


def test_one_broken_line_is_skipped_not_the_whole_file(tmp_path):
    # The broken line has more fields than the header. It must be dropped with a warning,
    # and the file must keep all its columns (it used to come back as a single column).
    path = _write(tmp_path, "products.csv", "id;name;price\n1;A;5\n2;B;6\n3;C;7;extra;fields\n4;D;8\n")
    df = read_csv_flex(path)
    assert list(df.columns) == ["id", "name", "price"]
    assert df["id"].tolist() == [1, 2, 4]
    assert "malformed rows were skipped" in df.attrs["csv_reader_warning"]


def test_comma_file_with_a_broken_line_keeps_its_columns(tmp_path):
    path = _write(tmp_path, "orders.csv", "a,b,c\n1,2,3\n4,5,6,7,8\n9,10,11\n")
    df = read_csv_flex(path)
    assert list(df.columns) == ["a", "b", "c"]
    assert len(df) == 2
