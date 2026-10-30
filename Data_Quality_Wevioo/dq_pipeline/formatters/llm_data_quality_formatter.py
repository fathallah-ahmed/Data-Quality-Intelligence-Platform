import os
import json
import glob
import traceback
from concurrent.futures import ThreadPoolExecutor, as_completed
from dq_pipeline.config.settings import settings


def _safe_ratio(numerator, denominator):
    if denominator in (None, 0):
        return 0.0
    try:
        return float(numerator) / float(denominator)
    except (TypeError, ValueError, ZeroDivisionError):
        return 0.0


def _is_id_like(feature_name, feature_type, base_stats):
    """Heuristic only for readability optimization; does not alter source data."""
    name = (feature_name or "").upper()
    id_tokens = ["ID", "CODE", "NUM", "KEY", "REF", "MATRICULE", "CLIENT"]
    has_id_token = any(token in name for token in id_tokens)

    total_rows = base_stats.get("total_rows")
    num_distinct = (base_stats.get("num_distinct") or {}).get("number")
    distinct_ratio = _safe_ratio(num_distinct, total_rows)

    return (
        feature_type in ("TYPE_NUM", "TYPE_TEXT")
        and has_id_token
        and distinct_ratio >= 0.50
    )


def _is_date_like(feature_name, feature_type):
    name = (feature_name or "").upper()
    date_tokens = ["DATE", "DAT", "TIME", "TIMESTAMP", "DT_"]
    has_date_token = any(token in name for token in date_tokens)
    return feature_type == "TYPE_DATE" or has_date_token


def format_for_llm(extracted_json_path, output_json_path):
    """
    Extract ALL raw SweetViz data without calculations.
    Purpose: Proper feature type identification (ID-like, Date, Category, etc.)
    """
    print(f"Reading {extracted_json_path}...")
    try:
        with open(extracted_json_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
    except Exception as e:
        print(f"ERROR reading JSON: {e}")
        return

    llm_report = {}

    llm_report["dataset_overview"] = {}

    summary_source = data.get("summary_source", {})
    if summary_source:
        llm_report["dataset_overview"]["source"] = {
            "num_rows": summary_source.get("num_rows"),
            "num_columns": summary_source.get("num_columns"),
            "num_cat": summary_source.get("num_cat"),
            "num_numerical": summary_source.get("num_numerical"),
            "num_text": summary_source.get("num_text"),
            "memory_total": summary_source.get("memory_total"),
            "duplicates": summary_source.get("duplicates")
        }

    summary_compare = data.get("summary_compare", {})
    if summary_compare:
        llm_report["dataset_overview"]["compare"] = {
            "num_rows": summary_compare.get("num_rows"),
            "num_columns": summary_compare.get("num_columns"),
            "num_cat": summary_compare.get("num_cat"),
            "num_numerical": summary_compare.get("num_numerical"),
            "num_text": summary_compare.get("num_text"),
            "duplicates": summary_compare.get("duplicates")
        }

    llm_report["features"] = []
    features = data.get("features", {})

    for feat_name, feat in features.items():
        feature_data = {
            "feature_name": feat_name,
            "data_type": feat.get("type"),
            "order_index": feat.get("order_index"),
            "is_target": feat.get("is_target", False),
        }

        base_stats = feat.get("base_stats", {})
        if base_stats:
            feature_data["base_stats"] = {
                "total_rows": base_stats.get("total_rows"),
                "num_values": base_stats.get("num_values"),
                "num_missing": base_stats.get("num_missing"),
                "num_zeroes": base_stats.get("num_zeroes"),
                "num_distinct": base_stats.get("num_distinct")
            }

        feat_type = feat.get("type")
        is_id_like = _is_id_like(feat_name, feat_type, base_stats)
        is_date_like = _is_date_like(feat_name, feat_type)
        feature_data["is_id_like"] = is_id_like
        feature_data["is_date_like"] = is_date_like

        stats = feat.get("stats", {})
        if stats:
            feature_data["stats"] = stats

        detail = feat.get("detail", {})
        if detail:
            detail_for_llm = detail.copy()
            removed_keys = []
            optimization_notes = []

            if is_id_like and "frequent_values" in detail_for_llm:
                detail_for_llm.pop("frequent_values", None)
                removed_keys.append("frequent_values")
                optimization_notes.append("id_like_high_cardinality")

            if is_date_like:
                for key in ("frequent_values", "full_count"):
                    if key in detail_for_llm:
                        detail_for_llm.pop(key, None)
                        removed_keys.append(key)
                optimization_notes.append("date_high_cardinality")

            if is_id_like or is_date_like:
                feature_data["detail_optimization"] = {
                    "removed_keys": sorted(list(set(removed_keys))),
                    "reason": "+".join(optimization_notes)
                }

            feature_data["detail"] = detail_for_llm

        compare = feat.get("compare")
        if compare:
            compare_data = {
                "data_type": compare.get("type"),
                "base_stats": compare.get("base_stats"),
                "stats": compare.get("stats"),
                "detail": compare.get("detail")
            }
            feature_data["compare"] = compare_data

        llm_report["features"].append(feature_data)

    llm_report["associations"] = {
        "source": data.get("associations", {})
    }

    assoc_compare = data.get("associations_compare", {})
    if assoc_compare:
        llm_report["associations"]["compare"] = assoc_compare

    llm_report["metadata"] = {
        "source_name": data.get("report_metadata", {}).get("source_name"),
        "compare_name": data.get("report_metadata", {}).get("compare_name"),
        "target_feature": data.get("target")
    }

    try:
        with open(output_json_path, 'w', encoding='utf-8') as f:
            json.dump(llm_report, f, indent=2, ensure_ascii=False)

        print(f"✅ LLM Report saved to {output_json_path}")
    except Exception as e:
        print(f"ERROR saving JSON: {e}")


def process_all_extracted_folders(extracted_dir, max_workers=None):
    """Process all extracted SweetViz folders and generate LLM reports."""
    json_files = glob.glob(os.path.join(extracted_dir, "**", "dataset_summary.json"), recursive=True)
    if not json_files:
        print(f"❌ No dataset_summary.json files found in {extracted_dir}")
        return

    print(f"Found {len(json_files)} dataset(s) to process\n")

    if max_workers is None:
        max_workers = settings.FORMATTER_MAX_WORKERS
    max_workers = max(1, int(max_workers))

    if max_workers == 1:
        for file_path in json_files:
            dir_name = os.path.dirname(file_path)
            output_path = os.path.join(dir_name, "llm_dq_report.json")
            try:
                format_for_llm(file_path, output_path)
            except Exception as e:
                print(f"❌ Error processing {file_path}: {e}")
                traceback.print_exc()
        return

    print(f"Parallel formatter workers: {max_workers}")
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {}
        for file_path in json_files:
            dir_name = os.path.dirname(file_path)
            output_path = os.path.join(dir_name, "llm_dq_report.json")
            futures[executor.submit(format_for_llm, file_path, output_path)] = file_path

        for future in as_completed(futures):
            file_path = futures[future]
            try:
                future.result()
            except Exception as e:
                print(f"❌ Error processing {file_path}: {e}")
                traceback.print_exc()


if __name__ == "__main__":
    base_dir = os.getcwd()
    extracted_directory = os.path.join(base_dir, "sweetviz_extracted")

    print("=" * 60)
    print("🔄 SweetViz Data Extractor (Pure Data - No Calculations)")
    print("=" * 60)
    process_all_extracted_folders(extracted_directory, max_workers=settings.FORMATTER_MAX_WORKERS)
    print("✅ Done!")
