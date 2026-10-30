import importlib
import json
import os
import sys
import traceback
from pathlib import Path


sys.path.append(os.getcwd())


def _status(callable_):
    try:
        return callable_()
    except Exception:
        return {"status": "error", "error": traceback.format_exc()}


def check_dag():
    module = importlib.import_module("dags.data_quality_pipeline")
    dag_obj = getattr(module, "dag", None)
    if dag_obj is None:
        return {"status": "error", "error": "DAG object named 'dag' was not found"}
    task_ids = [task.task_id for task in getattr(dag_obj, "tasks", [])]
    return {
        "status": "ok",
        "dag_id": getattr(dag_obj, "dag_id", None),
        "task_count": len(task_ids),
        "tasks": task_ids,
    }


def check_pipeline_imports():
    modules = [
        "dq_pipeline.pipeline_runner",
        "dq_pipeline.runners",
        "dq_pipeline.backend_client",
        "dq_pipeline.storage",
        "dq_pipeline.analyzers.llm_analyzer",
        "dq_pipeline.analyzers.langgraph_analyzer",
        "dq_pipeline.analyzers.llm_global_analyzer",
        "dq_pipeline.extractors.comprehensive_sweetviz_extractor",
    ]
    loaded = []
    missing_dependencies = []
    for module_name in modules:
        try:
            importlib.import_module(module_name)
            loaded.append(module_name)
        except ModuleNotFoundError as exc:
            missing_dependencies.append({
                "module": module_name,
                "missing_dependency": exc.name,
            })
    return {
        "status": "warning" if missing_dependencies else "ok",
        "modules": loaded,
        "missing_dependencies": missing_dependencies,
    }


def check_outputs():
    storage_root = Path(os.getenv("STORAGE_BASE_PATH", "storage"))
    pipeline_runs = storage_root / "pipeline_runs"
    json_root = storage_root / "json"
    return {
        "status": "ok",
        "storage_root": str(storage_root),
        "pipeline_run_dirs": len(list(pipeline_runs.glob("*"))) if pipeline_runs.exists() else 0,
        "analysis_json_files": len(list(json_root.glob("**/*_analysis.json"))) if json_root.exists() else 0,
        "metrics_json_files": len(list(json_root.glob("**/*_metrics.json"))) if json_root.exists() else 0,
    }


def main():
    result = {
        "dag": _status(check_dag),
        "imports": _status(check_pipeline_imports),
        "outputs": _status(check_outputs),
    }
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
