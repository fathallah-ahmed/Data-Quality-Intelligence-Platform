import argparse
from datetime import datetime, timezone
import json
from pathlib import Path

from airflow.api.common.trigger_dag import trigger_dag


def main():
    parser = argparse.ArgumentParser(description="Trigger the data-quality Airflow DAG with a JSON conf file.")
    parser.add_argument(
        "--conf",
        default="/opt/airflow/airflow_smoke_conf.json",
        help="Path to dag_run.conf JSON. Defaults to the container smoke config path.",
    )
    parser.add_argument(
        "--dag-id",
        default="data_quality_analysis_pipeline",
        help="Airflow DAG id to trigger.",
    )
    parser.add_argument(
        "--run-id",
        default=None,
        help="Optional explicit run id. If omitted, a unique smoke run id is generated.",
    )
    args = parser.parse_args()

    conf_path = Path(args.conf)
    with open(conf_path, "r", encoding="utf-8") as handle:
        conf = json.load(handle)

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_id = args.run_id or f"smoke_{timestamp}"
    dag_run = trigger_dag(
        dag_id=args.dag_id,
        run_id=run_id,
        conf=conf,
        replace_microseconds=False,
    )

    print(dag_run.run_id)


if __name__ == "__main__":
    main()
