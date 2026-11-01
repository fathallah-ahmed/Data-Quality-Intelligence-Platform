"""Airflow phase callables.

Each ``phase_*`` function is one visible task of the ``data_quality_analysis_pipeline``
DAG. Phases hand work to each other through small JSON manifests stored in the
pipeline-run folder, so a phase can be retried on its own.
"""
from pathlib import Path
import json
import time

from dq_pipeline.analyzers.langgraph_analyzer import LLM_STATUS_DISABLED, LLM_STATUS_OK
from dq_pipeline.backend_client import close_backend_client
from dq_pipeline.config.settings import settings
from dq_pipeline.models import (
    ColumnMetrics,
    DatasetMetrics,
    ResultBuilder,
    TableAnalysisResult,
)
from dq_pipeline.pipeline_runner import PipelineRunner

EMPTY_TABLE_ERROR = "Header-only CSV: the file has columns but no data rows to analyse"

# Manifests in the order the phases write them; the last one present holds the latest failures.
MANIFESTS = ["tables_manifest.json", "profile_manifest.json", "analysis_manifest.json", "publish_manifest.json"]


def _phase_runner(dataset_id, version_id, pipeline_run_id, file_path, backend_url=None, debug=False):
    return PipelineRunner(
        dataset_id=str(dataset_id),
        version_id=str(version_id),
        pipeline_run_id=str(pipeline_run_id),
        file_path=str(file_path),
        backend_url=backend_url,
        debug=debug,
    )


def _run_dir(runner):
    return runner.storage.get_pipeline_run_path(runner.pipeline_run_id)


def _manifest_path(runner, name):
    path = _run_dir(runner) / name
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def _write_manifest(runner, name, payload):
    path = _manifest_path(runner, name)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, ensure_ascii=False)
    return str(path)


def _read_manifest(runner, name, default=None):
    path = _manifest_path(runner, name)
    if not path.exists():
        return default
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def phase_start_pipeline(dataset_id, version_id, pipeline_run_id, file_path, backend_url=None, debug=False):
    """Notify the backend that this pipeline run has started."""
    runner = _phase_runner(dataset_id, version_id, pipeline_run_id, file_path, backend_url, debug)
    try:
        if not runner.backend_client.health_check(debug=debug):
            runner._warn("Backend health check failed (will continue anyway)")
        if not runner.backend_client.post_pipeline_run_started(
            runner.pipeline_run_id,
            runner.dataset_id,
            runner.version_id,
            debug=debug,
        ):
            raise RuntimeError("Backend pipeline-started POST failed")
        _write_manifest(runner, "run_context.json", {
            "dataset_id": runner.dataset_id,
            "version_id": runner.version_id,
            "pipeline_run_id": runner.pipeline_run_id,
            "file_path": str(runner.file_path),
            "started_at": time.time(),
        })
        return True
    finally:
        close_backend_client()


def phase_discover_tables(dataset_id, version_id, pipeline_run_id, file_path, backend_url=None, debug=False):
    """Discover CSV table files from the uploaded dataset path."""
    runner = _phase_runner(dataset_id, version_id, pipeline_run_id, file_path, backend_url, debug)
    try:
        table_files = runner._discover_table_files()
        tables = [{"table_name": path.stem, "file_path": str(path)} for path in table_files]
        _write_manifest(runner, "tables_manifest.json", {"tables": tables, "failed": []})
        if not tables:
            raise RuntimeError("No CSV tables found for this pipeline run")
        return tables
    finally:
        close_backend_client()


def phase_profile_tables(dataset_id, version_id, pipeline_run_id, file_path, backend_url=None, debug=False):
    """Load each table and create structured SweetViz metrics JSON."""
    runner = _phase_runner(dataset_id, version_id, pipeline_run_id, file_path, backend_url, debug)
    profiled = []
    failed = []
    try:
        manifest = _read_manifest(runner, "tables_manifest.json", {"tables": []})
        for table in manifest.get("tables", []):
            table_name = table["table_name"]
            table_path = Path(table["file_path"])
            runner._log(f"Profiling table {table_name}")
            df = runner._load_dataset(table_path)
            if df is None:
                failed.append({"table_name": table_name, "error": "The CSV file could not be read (encoding or separator problem)"})
                continue
            metrics_path = runner.storage.get_metrics_json_path(runner.dataset_id, runner.version_id, table_name)
            if len(df) == 0:
                # Sweetviz cannot profile an empty frame; keep a minimal metrics file so the
                # table is still published (0 rows, its columns) and reported as failed.
                runner._write_json(str(metrics_path), runner._empty_table_metrics(table_name, df))
                profiled.append({
                    "table_name": table_name,
                    "file_path": str(table_path),
                    "metrics_json_path": str(metrics_path),
                    "empty": True,
                })
                continue
            sweetviz_data = runner._run_sweetviz(table_name, df)
            if sweetviz_data is None:
                failed.append({"table_name": table_name, "error": "Profiling (Sweetviz) failed for this table"})
                continue
            profiled.append({
                "table_name": table_name,
                "file_path": str(table_path),
                "metrics_json_path": str(metrics_path),
            })

        _write_manifest(runner, "profile_manifest.json", {"profiled": profiled, "failed": failed})
        if not profiled:
            raise RuntimeError("All table profiling failed")
        return profiled
    finally:
        close_backend_client()


def phase_analyze_tables(dataset_id, version_id, pipeline_run_id, file_path, backend_url=None, debug=False):
    """Extract metrics, run per-table LLM analysis, and stream each table result."""
    runner = _phase_runner(dataset_id, version_id, pipeline_run_id, file_path, backend_url, debug)
    analyzed = []
    published = []
    failed = []
    try:
        manifest = _read_manifest(runner, "profile_manifest.json", {"profiled": []})
        profiled_tables = manifest.get("profiled", [])
        deterministic_relationships = runner._discover_value_relationships_from_files(profiled_tables)
        for table in profiled_tables:
            table_start = time.time()
            table_name = table["table_name"]
            table_path = Path(table["file_path"])
            empty_table = bool(table.get("empty"))
            runner._log(f"Analyzing table {table_name}")
            try:
                df = runner._load_dataset(table_path)
                if df is None:
                    raise RuntimeError("The CSV file could not be read")
                metrics_path = runner.storage.get_metrics_json_path(runner.dataset_id, runner.version_id, table_name)
                with open(metrics_path, "r", encoding="utf-8") as handle:
                    sweetviz_data = json.load(handle)

                dataset_metrics, column_metrics = runner._extract_metrics(table_name, df, sweetviz_data)
                runner._apply_runtime_quality_overrides(sweetviz_data, dataset_metrics, df)
                runner._write_json(str(metrics_path), sweetviz_data)
                result = ResultBuilder.table_analysis(
                    dataset_id=runner.dataset_id,
                    version_id=runner.version_id,
                    pipeline_run_id=runner.pipeline_run_id,
                    table_name=table_name,
                    dataset_metrics=dataset_metrics,
                    column_metrics=column_metrics,
                    processing_duration_seconds=time.time() - table_start,
                )
                result.metrics_json_path = str(metrics_path)
                result.analysis_json_path = str(
                    runner.storage.get_analysis_json_path(runner.dataset_id, runner.version_id, table_name)
                )
                result.data_signature = runner._build_data_signature(dataset_metrics, column_metrics, df)
                try:
                    result.data_signature["source_file_size_bytes"] = table_path.stat().st_size
                except OSError:
                    pass
                if df.attrs.get("csv_reader_warning"):
                    result.data_signature["csv_reader_warning"] = df.attrs.get("csv_reader_warning")
                    result.data_signature["csv_bad_line_policy"] = df.attrs.get("csv_bad_line_policy")
                result.cross_checks = runner._cross_checks(df, column_metrics)
                result.change_status = "UNKNOWN"
                result.deterministic_relationships = runner._relationships_for_table(
                    deterministic_relationships,
                    table_name,
                )
                analyzed_table = {
                    "table_name": table_name,
                    "analysis_json_path": result.analysis_json_path,
                    "metrics_json_path": result.metrics_json_path,
                }

                if empty_table:
                    result.status = "empty"
                    result.error_message = EMPTY_TABLE_ERROR
                    result.llm_status = LLM_STATUS_DISABLED
                    failed.append({"table_name": table_name, "error": EMPTY_TABLE_ERROR})
                else:
                    result.llm_status = "pending"
                runner._write_json(result.analysis_json_path, result.to_dict())
                analyzed.append(analyzed_table)
                if runner.backend_client.post_table_analysis(runner.pipeline_run_id, result, debug=debug):
                    runner._log(f"Published metrics for {table_name} before LLM enrichment")
                    if empty_table:
                        published.append(analyzed_table)
                else:
                    runner._warn(f"Metrics publish failed for {table_name}; final publish will retry")
                if empty_table:
                    continue

                llm_result = runner._run_llm_analysis(table_name, sweetviz_data)
                result.llm_status = llm_result.get("llm_status") or LLM_STATUS_DISABLED
                if llm_result.get("analysis"):
                    result.llm_interpretation = llm_result.get("analysis")
                result.llm_model_used = settings.OLLAMA_MODEL if result.llm_status == LLM_STATUS_OK else None
                result.processing_duration_seconds = time.time() - table_start
                runner._write_json(result.analysis_json_path, result.to_dict())
                if runner.backend_client.post_table_analysis(runner.pipeline_run_id, result, debug=debug):
                    runner._log(f"Published LLM-enriched result for {table_name} (llm_status={result.llm_status})")
                    published.append(analyzed_table)
                else:
                    runner._warn(f"LLM-enriched publish failed for {table_name}; publish phase will retry")
            except Exception as exc:
                runner._error(f"Analysis failed for {table_name}: {exc}")
                failed.append({"table_name": table_name, "error": str(exc)})

        existing_failed = manifest.get("failed", [])
        _write_manifest(runner, "analysis_manifest.json", {
            "analyzed": analyzed,
            "published": published,
            "failed": existing_failed + failed,
        })
        if not analyzed:
            raise RuntimeError("All table analysis failed")
        return analyzed
    finally:
        close_backend_client()


def _table_result_from_dict(payload):
    dataset_metrics = DatasetMetrics(**payload["dataset_metrics"])
    column_metrics = [ColumnMetrics(**column) for column in payload.get("column_metrics", [])]
    payload = dict(payload)
    payload["dataset_metrics"] = dataset_metrics
    payload["column_metrics"] = column_metrics
    return TableAnalysisResult(**payload)


def phase_publish_table_results(dataset_id, version_id, pipeline_run_id, file_path, backend_url=None, debug=False):
    """Retry POSTs for completed table analysis files not already streamed."""
    runner = _phase_runner(dataset_id, version_id, pipeline_run_id, file_path, backend_url, debug)
    published = []
    failed = []
    try:
        manifest = _read_manifest(runner, "analysis_manifest.json", {"analyzed": [], "failed": []})
        already_published = {table["table_name"] for table in manifest.get("published", [])}
        published.extend(manifest.get("published", []))
        for table in manifest.get("analyzed", []):
            table_name = table["table_name"]
            if table_name in already_published:
                continue
            try:
                with open(table["analysis_json_path"], "r", encoding="utf-8") as handle:
                    result = _table_result_from_dict(json.load(handle))
                if runner.backend_client.post_table_analysis(runner.pipeline_run_id, result, debug=debug):
                    published.append(table)
                else:
                    failed.append({"table_name": table_name, "error": "The result could not be sent to the backend"})
            except Exception as exc:
                runner._error(f"Publish failed for {table_name}: {exc}")
                failed.append({"table_name": table_name, "error": str(exc)})

        all_failed = manifest.get("failed", []) + failed
        _write_manifest(runner, "publish_manifest.json", {
            "published": published,
            "failed": all_failed,
        })
        if not published:
            raise RuntimeError("No table results were published")
        return published
    finally:
        close_backend_client()


def phase_suggest_rules(dataset_id, version_id, pipeline_run_id, file_path, backend_url=None, debug=False):
    """
    Propose quality rules from the user's goal and send them to the backend for approval.

    A best-effort phase: it reports what went wrong in its result and never raises, so the run is
    not marked failed because no rule could be suggested.
    """
    from dq_pipeline.analyzers import rule_suggester
    from dq_pipeline.analyzers.llm_analyzer import call_ollama, parse_llm_response

    runner = _phase_runner(dataset_id, version_id, pipeline_run_id, file_path, backend_url, debug)
    try:
        if not settings.RULE_SUGGESTION_ENABLED:
            return {"suggested": 0, "skipped": "disabled"}
        manifest = _read_manifest(runner, "publish_manifest.json", {"published": []})
        table_results = []
        for table in manifest.get("published", []):
            with open(table["analysis_json_path"], "r", encoding="utf-8") as handle:
                table_results.append(_table_result_from_dict(json.load(handle)))
        if not table_results:
            return {"suggested": 0, "skipped": "no tables"}

        outcome = rule_suggester.suggest_rules(
            table_results,
            runner._business_context(),
            llm_call=lambda prompt, **options: call_ollama(
                prompt,
                model=settings.OLLAMA_MODEL,
                ollama_url=str(settings.OLLAMA_URL) if settings.OLLAMA_URL else None,
                temperature=0.1,
                top_p=0.9,
                num_predict=settings.RULE_LLM_NUM_PREDICT,
                **options,
            ),
            parse=parse_llm_response,
        )
        for note in outcome["notes"]:
            runner._warn(f"Rule suggestions: {note}")
        sent = False
        if outcome["rules"]:
            payload = [
                {
                    "table_name": rule["table_name"],
                    "column_name": rule.get("column_name"),
                    "type": rule["type"],
                    "format": rule.get("format"),
                    "second_column": rule.get("second_column"),
                    "threshold": rule.get("threshold"),
                    "severity": rule["severity"],
                    "rationale": rule["rationale"],
                }
                for rule in outcome["rules"]
            ]
            sent = runner.backend_client.post_rule_suggestions(runner.pipeline_run_id, payload, debug=debug)
            if not sent:
                runner._warn("Rule suggestions could not be sent to the backend")
        runner._log(f"Suggested {len(outcome['rules'])} rule(s) from {outcome['source']}")
        return {"suggested": len(outcome["rules"]), "source": outcome["source"], "sent": sent}
    except Exception as exc:
        runner._warn(f"Rule suggestions skipped: {exc}")
        return {"suggested": 0, "error": str(exc)}
    finally:
        close_backend_client()


def phase_global_analysis(dataset_id, version_id, pipeline_run_id, file_path, backend_url=None, debug=False):
    """Build and POST the global portfolio analysis summary."""
    runner = _phase_runner(dataset_id, version_id, pipeline_run_id, file_path, backend_url, debug)
    try:
        manifest = _read_manifest(runner, "publish_manifest.json", {"published": [], "failed": []})
        table_results = []
        for table in manifest.get("published", []):
            with open(table["analysis_json_path"], "r", encoding="utf-8") as handle:
                table_results.append(_table_result_from_dict(json.load(handle)))

        total_rows = sum(result.dataset_metrics.row_count for result in table_results)
        total_cols = sum(result.dataset_metrics.column_count for result in table_results)
        tables = [result.table_name for result in table_results]
        result = ResultBuilder.global_analysis(
            pipeline_run_id=runner.pipeline_run_id,
            tables_analyzed=tables,
            total_rows=total_rows,
            total_cols=total_cols,
            processing_duration_seconds=0,
        )
        failed_count = len(manifest.get("failed", []))
        deterministic_interpretation = runner._build_portfolio_interpretation(table_results, failed_count=failed_count)
        result.llm_interpretation, result.llm_status = runner._run_global_llm_analysis(
            table_results,
            deterministic_interpretation,
            failed_count=failed_count,
        )
        if result.llm_status == LLM_STATUS_OK:
            result.llm_model_used = settings.OLLAMA_MODEL
        result.analysis_json_path = str(
            runner.storage.get_analysis_json_path(runner.dataset_id, runner.version_id, "global")
        )
        runner._write_json(result.analysis_json_path, result.to_dict())
        if not runner.backend_client.post_global_analysis(runner.pipeline_run_id, result, debug=debug):
            raise RuntimeError("Backend global-analysis POST failed")
        _write_manifest(runner, "global_manifest.json", result.to_dict())
        return result.to_dict()
    finally:
        close_backend_client()


def latest_failed_tables(runner):
    """Failed tables from the most advanced manifest written so far (deduplicated by table)."""
    failed = []
    for name in MANIFESTS:
        manifest = _read_manifest(runner, name)
        if manifest is not None:
            failed = manifest.get("failed", []) or []
    seen = {}
    for item in failed:
        table_name = str(item.get("table_name") or "")
        if table_name and table_name not in seen:
            seen[table_name] = {"table_name": table_name, "error": str(item.get("error") or "unknown error")}
    return list(seen.values())


def completion_status(published_count, failed_count, forced_status=None):
    """success when every table was published, partial when some failed, failed when none made it."""
    if forced_status:
        return forced_status
    if published_count == 0:
        return "failed"
    if failed_count:
        return "partial"
    return "success"


def phase_complete_pipeline(dataset_id, version_id, pipeline_run_id, file_path, backend_url=None, debug=False, forced_status=None, error_message=None):
    """Mark the backend pipeline run complete with its final status and failed tables."""
    runner = _phase_runner(dataset_id, version_id, pipeline_run_id, file_path, backend_url, debug)
    try:
        manifest = _read_manifest(runner, "publish_manifest.json", {"published": [], "failed": []})
        published_count = len(manifest.get("published", []))
        failed_tables = latest_failed_tables(runner)
        status = completion_status(published_count, len(failed_tables), forced_status)

        detail = error_message
        if detail is None and failed_tables:
            detail = f"{len(failed_tables)} table(s) could not be analysed: " + "; ".join(
                f"{item['table_name']} ({item['error']})" for item in failed_tables
            )
        if not runner.backend_client.post_pipeline_run_completed(
            runner.pipeline_run_id,
            status=status,
            error_message=detail,
            failed_tables=failed_tables,
            debug=debug,
        ):
            raise RuntimeError("Backend pipeline-completed POST failed")
        return {"status": status, "published": published_count, "failed": len(failed_tables)}
    finally:
        close_backend_client()
