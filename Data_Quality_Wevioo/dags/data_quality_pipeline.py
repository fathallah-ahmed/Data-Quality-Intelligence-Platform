"""
Backend-triggered data quality pipeline DAG.

Spring Boot owns upload validation, dataset/version records, and filesystem
storage. This DAG receives identifiers and a file path from dag_run.conf, then
runs the pipeline as visible Airflow phases instead of one opaque task.
"""

from datetime import datetime, timedelta

from airflow import DAG
try:
    from airflow.providers.standard.operators.python import PythonOperator
    from airflow.sdk import get_current_context
    from airflow.task.trigger_rule import TriggerRule
except ImportError:
    from airflow.operators.python import PythonOperator, get_current_context
    from airflow.utils.trigger_rule import TriggerRule

from dq_pipeline.config.settings import settings
from dq_pipeline.runners import (
    phase_analyze_tables,
    phase_complete_pipeline,
    phase_discover_tables,
    phase_global_analysis,
    phase_profile_tables,
    phase_publish_table_results,
    phase_start_pipeline,
    phase_suggest_rules,
)


default_args = {
    "owner": "ahmed",
    "retries": 1,
    "retry_delay": timedelta(minutes=5),
    "email_on_failure": False,
    "email_on_retry": False,
}


dag = DAG(
    dag_id="data_quality_analysis_pipeline",
    default_args=default_args,
    description="Backend-triggered data quality worker pipeline",
    schedule=None,
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["data-quality", "backend-triggered", "phased"],
)


# Phase task id -> label shown to users when the phase fails.
PHASE_LABELS = {
    "validate_backend_request": "Validate request",
    "notify_backend_started": "Notify backend",
    "discover_uploaded_tables": "Discover tables",
    "profile_tables_with_sweetviz": "Profile tables",
    "extract_metrics_and_run_llm": "Analyse tables",
    "publish_table_results": "Publish results",
    "suggest_quality_rules": "Suggest rules",
    "build_global_portfolio_analysis": "Global analysis",
}
PHASE_TASK_IDS = list(PHASE_LABELS)


def _require_conf_value(conf, key):
    value = conf.get(key)
    if value is None or str(value).strip() == "":
        raise ValueError(f"Missing required dag_run.conf value: {key}")
    return str(value)


def _pipeline_args():
    context = get_current_context()
    dag_run = context.get("dag_run")
    conf = (dag_run.conf or {}) if dag_run else {}
    return {
        "dataset_id": _require_conf_value(conf, "dataset_id"),
        "version_id": _require_conf_value(conf, "version_id"),
        "pipeline_run_id": _require_conf_value(conf, "pipeline_run_id"),
        "file_path": _require_conf_value(conf, "file_path"),
        "backend_url": conf.get("backend_url") or settings.BACKEND_URL,
        "debug": bool(conf.get("debug", False)),
    }


def validate_backend_request(**_kwargs):
    args = _pipeline_args()
    return {
        "dataset_id": args["dataset_id"],
        "version_id": args["version_id"],
        "pipeline_run_id": args["pipeline_run_id"],
        "file_path": args["file_path"],
    }


def notify_backend_started(**_kwargs):
    return phase_start_pipeline(**_pipeline_args())


def discover_uploaded_tables(**_kwargs):
    return phase_discover_tables(**_pipeline_args())


def profile_tables_with_sweetviz(**_kwargs):
    return phase_profile_tables(**_pipeline_args())


def extract_metrics_and_run_llm(**_kwargs):
    return phase_analyze_tables(**_pipeline_args())


def publish_table_results(**_kwargs):
    return phase_publish_table_results(**_pipeline_args())


def suggest_quality_rules(**_kwargs):
    return phase_suggest_rules(**_pipeline_args())


def build_global_portfolio_analysis(**_kwargs):
    return phase_global_analysis(**_pipeline_args())


def mark_pipeline_completed(**_kwargs):
    context = get_current_context()
    dag_run = context.get("dag_run")
    failed = []
    if dag_run:
        for task_instance in dag_run.get_task_instances():
            if task_instance.task_id in PHASE_TASK_IDS and task_instance.state == "failed":
                failed.append(PHASE_LABELS[task_instance.task_id])
    forced_status = "failed" if failed else None
    error_message = (
        f"Pipeline step failed: {', '.join(failed)}. Open the task logs in the Airflow UI for details."
        if failed
        else None
    )
    return phase_complete_pipeline(
        **_pipeline_args(),
        forced_status=forced_status,
        error_message=error_message,
    )


validate_request = PythonOperator(
    task_id="validate_backend_request",
    python_callable=validate_backend_request,
    dag=dag,
)

notify_started = PythonOperator(
    task_id="notify_backend_started",
    python_callable=notify_backend_started,
    dag=dag,
)

discover_tables = PythonOperator(
    task_id="discover_uploaded_tables",
    python_callable=discover_uploaded_tables,
    dag=dag,
)

profile_tables = PythonOperator(
    task_id="profile_tables_with_sweetviz",
    python_callable=profile_tables_with_sweetviz,
    dag=dag,
)

analyze_tables = PythonOperator(
    task_id="extract_metrics_and_run_llm",
    python_callable=extract_metrics_and_run_llm,
    dag=dag,
)

publish_results = PythonOperator(
    task_id="publish_table_results",
    python_callable=publish_table_results,
    dag=dag,
)

suggest_rules = PythonOperator(
    task_id="suggest_quality_rules",
    python_callable=suggest_quality_rules,
    dag=dag,
)

global_analysis = PythonOperator(
    task_id="build_global_portfolio_analysis",
    python_callable=build_global_portfolio_analysis,
    dag=dag,
)

complete_pipeline = PythonOperator(
    task_id="mark_pipeline_completed",
    python_callable=mark_pipeline_completed,
    trigger_rule=TriggerRule.ALL_DONE,
    dag=dag,
)

validate_request >> notify_started >> discover_tables >> profile_tables >> analyze_tables >> publish_results >> suggest_rules >> global_analysis
[
    validate_request,
    notify_started,
    discover_tables,
    profile_tables,
    analyze_tables,
    publish_results,
    suggest_rules,
    global_analysis,
] >> complete_pipeline
