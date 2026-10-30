import os
import json
import html
from enum import Enum

import matplotlib

# Sweetviz draws its association graph with matplotlib. The worker never shows plots, so use the
# headless backend: a GUI backend (Tk) fails intermittently on machines with no or a broken Tk.
matplotlib.use("Agg")

import pandas as pd
import sweetviz as sv
import sweetviz.dataframe_report as df_report
import numpy as np

# Compatibility patch for sweetviz
if not hasattr(np, "VisibleDeprecationWarning"):
    np.VisibleDeprecationWarning = DeprecationWarning

def fast_summarize_dataframe(self, source, name, target_dict, skip):
    target_dict["name"] = name
    target_dict["num_rows"] = len(source)
    target_dict["num_columns"] = len(source.columns)
    target_dict["num_skipped_columns"] = len(source.columns) - len([x for x in source.columns if x not in skip])
    
    # Use deep=False for instantly fast memory calculation
    target_dict["memory_total"] = source.memory_usage(index=True, deep=False).sum()
    target_dict["memory_single_row"] = float(target_dict["memory_total"]) / target_dict["num_rows"] if target_dict["num_rows"] > 0 else 0
    
    duplicate_count = int(source.duplicated().sum()) if len(source) else 0
    target_dict["duplicates"] = sv.sv_types.NumWithPercent(duplicate_count, len(source))
    target_dict["num_cmp_not_in_source"] = 0 

df_report.DataframeReport.summarize_dataframe = fast_summarize_dataframe

def custom_serializer(obj):
    try:
        if pd.isna(obj) or obj is pd.NaT:
            return None
    except (ValueError, TypeError):
        pass

    if isinstance(obj, pd.Series):
        try:
            return obj.dropna().to_dict()
        except:
            return str(obj)
    if isinstance(obj, pd.DataFrame):
        return obj.to_dict(orient="records")

    if isinstance(obj, (np.integer, np.int64, np.int32)):
        return int(obj)
    if isinstance(obj, (np.floating, np.float64, np.float32)):
        if np.isnan(obj) or np.isinf(obj):
            return None
        return float(obj)
    if isinstance(obj, np.ndarray):
        return obj.tolist()
    if isinstance(obj, np.bool_):
        return bool(obj)
        
    if isinstance(obj, pd.Timestamp):
        return obj.isoformat()
        
    if isinstance(obj, Enum):
        return obj.name
        
    if hasattr(obj, '__dict__'):
        return vars(obj)
        
    return str(obj)

def convert_to_serializable(o, seen=None):
    if seen is None:
        seen = set()

    obj_id = id(o)
    if obj_id in seen:
        return "<circular_reference>"

    if hasattr(o, '__dict__') or isinstance(o, (dict, list, tuple, set)):
        seen.add(obj_id)

    if isinstance(o, dict):
        res = {}
        for k, v in o.items():
            k_str = str(k).lower()
            if "html" in k_str or "graph" in k_str:
                continue
            res[str(k)] = convert_to_serializable(v, seen)
        seen.remove(obj_id)
        return res
    elif isinstance(o, list):
        res = [convert_to_serializable(v, seen) for v in o]
        seen.remove(obj_id)
        return res
    elif isinstance(o, tuple):
        res = [convert_to_serializable(v, seen) for v in o]
        seen.remove(obj_id)
        return res
    elif isinstance(o, set):
        res = [convert_to_serializable(v, seen) for v in list(o)]
        seen.remove(obj_id)
        return res
    
    if isinstance(o, (int, float, str, bool, type(None))):
        if isinstance(o, float) and (np.isnan(o) or np.isinf(o)):
            return None
        return o
        
    serialized_o = custom_serializer(o)
    if isinstance(serialized_o, (dict, list, tuple, set)):
        res = convert_to_serializable(serialized_o, seen)
        if obj_id in seen:
            seen.remove(obj_id)
        return res
        
    if obj_id in seen:
        seen.remove(obj_id)
    return serialized_o

def extract_report_data(report):
    extracted = {
        "report_metadata": {
            "source_name": getattr(report, "source_name", None),
            "compare_name": getattr(report, "compare_name", None),
            "test_mode": getattr(report, "test_mode", None),
            "corr_warning": getattr(report, "corr_warning", []),
        },
        "summary_source": getattr(report, "summary_source", {}),
        "summary_compare": getattr(report, "summary_compare", {}),
        "target": getattr(report, "_target", None),
        "associations": getattr(report, "_associations", {}),
        "associations_compare": getattr(report, "_associations_compare", {}),
        "features": getattr(report, "_features", {}),
    }
    extra_fields = {}
    for attr_name in vars(report):
        if attr_name not in ["source_name", "compare_name", "test_mode", "corr_warning", 
                             "summary_source", "summary_compare", "_target", 
                             "_associations", "_associations_compare", "_features",
                             "page_layout", "scale", "run_id", "verbosity_level",
                             "_page_html", "_jupyter_html", "dataframe_summary_html",
                             "associations_html_source", "associations_html_compare",
                             "graph_legend", "_association_graphs", "_association_graphs_compare",
                             "progress_bar", "_comet_ml_logger"]:
            val = getattr(report, attr_name)
            extra_fields[attr_name] = val
    if extra_fields:
        extracted["extra_internal_fields"] = extra_fields
    serializable_dict = convert_to_serializable(extracted)
    return serializable_dict

def flatten_dataset_summary(extracted):
    rows = []
    def add_summary_row(summary_dict, dataset_type):
        if not summary_dict:
            return
        row = {"dataset_type": dataset_type}
        for k, v in summary_dict.items():
            if isinstance(v, dict) and "number" in v and "perc" in v:
                row[f"{k}_number"] = v["number"]
                row[f"{k}_perc"] = v["perc"]
            else:
                row[k] = v
        rows.append(row)
    add_summary_row(extracted.get("summary_source"), "source")
    add_summary_row(extracted.get("summary_compare"), "compare")
    import pandas as pd
    return pd.DataFrame(rows)

def flatten_feature_summary(extracted):
    rows = []
    for feat_name, feat in extracted.get("features", {}).items():
        row = {
            "feature_name": feat_name,
            "type": feat.get("type"),
            "order_index": feat.get("order_index"),
            "is_target": feat.get("is_target"),
            "dataset_type": "source"
        }
        for k, v in feat.get("base_stats", {}).items():
            if isinstance(v, dict) and "number" in v and "perc" in v:
                row[f"base_{k}_number"] = v.get("number")
                row[f"base_{k}_perc"] = v.get("perc")
            else:
                row[f"base_{k}"] = v
        for k, v in feat.get("stats", {}).items():
            row[f"stat_{k}"] = v
        rows.append(row)
        compare_dict = feat.get("compare")
        if compare_dict:
            comp_row = {
                "feature_name": feat_name,
                "type": compare_dict.get("type", feat.get("type")),
                "order_index": feat.get("order_index"),
                "is_target": feat.get("is_target"),
                "dataset_type": "compare"
            }
            for k, v in compare_dict.get("base_stats", {}).items():
                if isinstance(v, dict) and "number" in v and "perc" in v:
                    comp_row[f"base_{k}_number"] = v.get("number")
                    comp_row[f"base_{k}_perc"] = v.get("perc")
                else:
                    comp_row[f"base_{k}"] = v
            for k, v in compare_dict.get("stats", {}).items():
                comp_row[f"stat_{k}"] = v
            rows.append(comp_row)
    import pandas as pd
    return pd.DataFrame(rows)

def flatten_feature_details(extracted):
    rows = []
    for feat_name, feat in extracted.get("features", {}).items():
        detail = feat.get("detail", {})
        full_count = detail.get("full_count", [])
        if isinstance(full_count, list):
            for item in full_count:
                if not isinstance(item, dict):
                    continue
                val = item.get("name")
                if isinstance(val, str):
                    val = html.unescape(val)
                row = {
                    "feature_name": feat_name,
                    "detail_type": "full_count",
                    "value": val,
                    "is_total": item.get("is_total"),
                }
                count = item.get("count")
                if isinstance(count, dict):
                    row["count_number"] = count.get("number")
                    row["count_perc"] = count.get("perc")
                count_compare = item.get("count_compare")
                if isinstance(count_compare, dict):
                    row["count_compare_number"] = count_compare.get("number")
                    row["count_compare_perc"] = count_compare.get("perc")
                target_stats = item.get("target_stats")
                if isinstance(target_stats, dict):
                    row["target_stat_number"] = target_stats.get("number")
                    row["target_stat_perc"] = target_stats.get("perc")
                else:
                    row["target_stat_number"] = target_stats
                target_stats_compare = item.get("target_stats_compare")
                if isinstance(target_stats_compare, dict):
                    row["target_stat_compare_number"] = target_stats_compare.get("number")
                    row["target_stat_compare_perc"] = target_stats_compare.get("perc")
                else:
                    row["target_stat_compare_number"] = target_stats_compare
                rows.append(row)
        for detail_type in ["frequent_values", "min_values", "max_values"]:
            num_details = detail.get(detail_type, [])
            if isinstance(num_details, list):
                for item in num_details:
                    if not isinstance(item, (list, tuple)) or len(item) < 2:
                        continue
                    value = item[0]
                    count = item[1]
                    count_compare = item[2] if len(item) > 2 else None
                    row = {
                        "feature_name": feat_name,
                        "detail_type": detail_type,
                        "value": value,
                    }
                    if isinstance(count, dict):
                        row["count_number"] = count.get("number")
                        row["count_perc"] = count.get("perc")
                    if isinstance(count_compare, dict):
                        row["count_compare_number"] = count_compare.get("number")
                        row["count_compare_perc"] = count_compare.get("perc")
                    rows.append(row)
    import pandas as pd
    return pd.DataFrame(rows)

def flatten_associations(extracted):
    rows = []
    def extract_assoc(associations_dict, dataset_type):
        if not isinstance(associations_dict, dict):
            return
        for source_feature, targets in associations_dict.items():
            if not isinstance(targets, dict):
                continue
            for target_feature, value in targets.items():
                rows.append({
                    "dataset_type": dataset_type,
                    "source_feature": source_feature,
                    "target_feature": target_feature,
                    "association_value": value
                })
    extract_assoc(extracted.get("associations", {}), "source")
    extract_assoc(extracted.get("associations_compare", {}), "compare")
    import pandas as pd
    return pd.DataFrame(rows)

def read_csv_flex(file_path):
    encodings = ["utf-8-sig", "utf-8", "cp1252", "windows-1252", "iso-8859-1", "latin1"]
    separators = [";", ",", "\t", "|"]
    sample = open(file_path, "rb").read(1024 * 1024)
    strict_errors = []
    fallback_errors = []

    detected_encoding = encodings[-1]
    sample_text = ""
    for enc in encodings:
        try:
            sample_text = sample.decode(enc)
            detected_encoding = enc
            break
        except UnicodeDecodeError:
            continue

    lines = [line for line in sample_text.splitlines()[:50] if line.strip()]
    separator_scores = []
    for sep in separators:
        counts = [line.count(sep) for line in lines]
        positive = [count for count in counts if count > 0]
        separator_scores.append((len(positive), sum(positive), sep))
    detected_separator = max(separator_scores)[2] if separator_scores else ","

    attempts = [
        (detected_encoding, detected_separator, "c"),
        (detected_encoding, detected_separator, "python"),
    ]
    attempts.extend((detected_encoding, sep, "c") for sep in separators if sep != detected_separator)
    attempts.append((detected_encoding, None, "python"))
    attempts.extend((enc, detected_separator, "c") for enc in encodings if enc != detected_encoding)
    attempts.extend((enc, detected_separator, "python") for enc in encodings if enc != detected_encoding)

    def attempt_reads(allow_bad_line_skip=False):
        best_single_column = None
        seen = set()
        errors = fallback_errors if allow_bad_line_skip else strict_errors
        for enc, sep, engine in attempts:
            key = (enc, sep, engine, allow_bad_line_skip)
            if key in seen:
                continue
            seen.add(key)
            try:
                kwargs = {
                    "encoding": enc,
                    "sep": sep,
                    "engine": engine,
                }
                if allow_bad_line_skip:
                    kwargs["on_bad_lines"] = "skip"
                if engine == "c":
                    kwargs["low_memory"] = False
                df = pd.read_csv(file_path, **kwargs)
                if df.shape[1] <= 0:
                    continue
                df.attrs["csv_encoding"] = enc
                df.attrs["csv_separator"] = sep if sep is not None else "auto"
                df.attrs["csv_bad_line_policy"] = "skip" if allow_bad_line_skip else "error"
                if allow_bad_line_skip:
                    df.attrs["csv_reader_warning"] = (
                        "Strict CSV parsing failed; malformed rows were skipped so usable rows could be profiled."
                    )
                    df.attrs["csv_strict_errors"] = strict_errors[-5:]
                if df.shape[1] > 1:
                    return df
                if best_single_column is None or len(df) > len(best_single_column):
                    best_single_column = df
            except Exception as exc:
                errors.append(f"{enc}/{sep or 'auto'}/{engine}: {exc}")
        return best_single_column

    df = attempt_reads(allow_bad_line_skip=False)
    if df is not None:
        return df

    df = attempt_reads(allow_bad_line_skip=True)
    if df is not None:
        return df

    detail = "; ".join((fallback_errors or strict_errors)[-5:])
    raise ValueError(f"Could not read file: {file_path}. Last errors: {detail}")


def extract_sweetviz_metrics(df, table_name, output_html_path=None, output_json_path=None, debug=False):
    """
    Extract Sweetviz metrics from a dataframe.
    
    This is the refactored per-table analysis method suitable for pipeline_runner.
    
    Args:
        df: Input pandas DataFrame
        table_name: Name of the table being analyzed
        output_html_path: Deprecated. Kept for legacy callers; the new platform
            builds dynamic Angular pages from JSON metrics instead of storing
            Sweetviz HTML as a UI artifact.
        output_json_path: Optional path to save JSON metrics
        debug: Enable debug logging
    
    Returns:
        Dictionary with extracted metrics and report data
    """
    try:
        if df.empty:
            if debug:
                print(f"[DEBUG] {table_name}: Dataframe is empty")
            return {"status": "skipped", "reason": "empty_dataframe"}
        
        if debug:
            print(f"[DEBUG] {table_name}: Running Sweetviz analysis...")
        
        # Run Sweetviz analysis
        report = sv.analyze(df)
        
        # Extract all report data
        extracted = extract_report_data(report)
        
        # Add table metadata
        extracted["table_name"] = table_name
        extracted["status"] = "completed"
        extracted["row_count"] = len(df)
        extracted["column_count"] = len(df.columns)
        csv_reader = {
            "encoding": df.attrs.get("csv_encoding"),
            "separator": df.attrs.get("csv_separator"),
            "bad_line_policy": df.attrs.get("csv_bad_line_policy"),
            "warning": df.attrs.get("csv_reader_warning"),
            "strict_errors": df.attrs.get("csv_strict_errors"),
        }
        extracted["csv_reader"] = {
            key: value for key, value in csv_reader.items()
            if value not in (None, "", [])
        }
        
        # Save HTML only for legacy/manual usage. The Airflow runner intentionally
        # does not pass this path because the frontend renders dynamic pages.
        if output_html_path:
            os.makedirs(os.path.dirname(output_html_path) or ".", exist_ok=True)
            report.show_html(filepath=output_html_path, open_browser=False)
            extracted["html_path"] = str(output_html_path)
            if debug:
                print(f"[DEBUG] {table_name}: Saved HTML to {output_html_path}")
        
        # Save JSON if requested
        if output_json_path:
            os.makedirs(os.path.dirname(output_json_path) or ".", exist_ok=True)
            with open(output_json_path, "w", encoding="utf-8") as f:
                json.dump(extracted, f, ensure_ascii=False, indent=2, default=str)
            extracted["json_path"] = str(output_json_path)
            if debug:
                print(f"[DEBUG] {table_name}: Saved JSON to {output_json_path}")
        
        return extracted
        
    except Exception as e:
        error_msg = f"Sweetviz analysis failed for {table_name}: {e}"
        if debug:
            print(f"[DEBUG] {error_msg}")
            import traceback
            traceback.print_exc()
        return {
            "status": "failed",
            "table_name": table_name,
            "error": str(e),
        }
