import argparse
import csv
from pathlib import Path
import re

from bs4 import BeautifulSoup


def parse_sweetviz_html(filepath):
    with open(filepath, "r", encoding="utf-8") as handle:
        soup = BeautifulSoup(handle.read(), "html.parser")

    summary_items = soup.find_all(class_="container-feature-summary")
    metrics = []
    for feature_summary in summary_items[1:]:
        feature_name_elem = feature_summary.find(class_="text-title-tab color-normal")
        if not feature_name_elem:
            continue
        feature_name = feature_name_elem.get_text(strip=True)
        full_text = feature_summary.get_text(separator=" ", strip=True)
        missing_match = re.search(r"MISSING:\s*([\d.]+[KMBkmb%]?|---)", full_text)
        distinct_match = re.search(r"DISTINCT:\s*([\d.]+[KMBkmb%]?|---)", full_text)
        max_match = re.search(r"MAX\s+([\d.\-]+[KMBkmb]?)", full_text)
        min_match = re.search(r"MIN\s+([\d.\-]+[KMBkmb]?)", full_text)
        avg_match = re.search(r"AVG\s+([\d.\-]+[KMBkmb]?)", full_text)
        metrics.append({
            "report_file": filepath.name,
            "feature": feature_name,
            "missing": missing_match.group(1) if missing_match else "0",
            "distinct": distinct_match.group(1) if distinct_match else "0",
            "max": max_match.group(1) if max_match else "",
            "min": min_match.group(1) if min_match else "",
            "avg": avg_match.group(1) if avg_match else "",
        })
    return metrics


def main():
    parser = argparse.ArgumentParser(description="Extract basic metrics from legacy SweetViz HTML files.")
    parser.add_argument("--input-dir", default="sweetviz_reports", help="Folder containing SweetViz HTML files.")
    parser.add_argument("--output-csv", default="sweetviz_extracted_metrics.csv", help="CSV file to write.")
    args = parser.parse_args()

    input_dir = Path(args.input_dir)
    html_files = sorted(input_dir.glob("**/*.html"))
    all_metrics = []
    for html_file in html_files:
        print(f"Parsing: {html_file}")
        try:
            all_metrics.extend(parse_sweetviz_html(html_file))
        except Exception as exc:
            print(f"Error parsing {html_file}: {exc}")

    if not all_metrics:
        print("No metrics found.")
        return

    output_path = Path(args.output_csv)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=all_metrics[0].keys())
        writer.writeheader()
        writer.writerows(all_metrics)
    print(f"Successfully wrote {len(all_metrics)} metrics to {output_path}")


if __name__ == "__main__":
    main()
