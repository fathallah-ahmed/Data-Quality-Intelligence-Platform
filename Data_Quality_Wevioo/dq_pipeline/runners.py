"""Runners: importable callables for pipeline orchestration.

These wrappers expose clean Python functions suitable for Airflow `PythonOperator`.
"""
from pathlib import Path
import os
import json
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dq_pipeline.config.settings import settings
from dq_pipeline.pipeline_runner import PipelineRunner, run_pipeline
from dq_pipeline.backend_client import close_backend_client
from dq_pipeline.models import (
    ColumnMetrics,
    DatasetMetrics,
    GlobalAnalysisResult,
    ResultBuilder,
    TableAnalysisResult,
)
try:
    from dq_pipeline.analyzers import llm_analyzer
except Exception:
    try:
        import llm_analyzer as llm_analyzer
    except Exception:
        llm_analyzer = None

try:
    from dq_pipeline.analyzers import llm_global_analyzer as global_analyzer
except Exception:
    try:
        import llm_global_analyzer as global_analyzer
    except Exception:
        global_analyzer = None


def run_local_analysis(skip_existing=True, model="qwen2.5:14b-instruct", dataset_name=None, temperature=0.2, top_p=0.9, num_predict=1400, debug=False, max_workers=None):
    """Run per-dataset LLM analysis by calling `process_all_datasets` in `llm_analyzer`.

    This function is intended to be invoked by Airflow PythonOperator.
    """
    if llm_analyzer is None or not hasattr(llm_analyzer, 'process_all_datasets'):
        raise RuntimeError("llm_analyzer.process_all_datasets is not importable")

    return llm_analyzer.process_all_datasets(
        skip_existing=skip_existing,
        model=model,
        dataset_name=dataset_name,
        temperature=temperature,
        top_p=top_p,
        num_predict=num_predict,
        debug=debug,
        max_workers=max_workers,
    )


def run_global_analysis(root="sweetviz_extracted", ollama_url=None, model="qwen3.5:35b", max_features_per_dataset=0, assoc_limit=0, temperature=0.6, top_p=0.9, num_predict=None, timeout_seconds=420, include_full_llm_dq_report=False, report_char_limit=0, max_detail_values=20, output="sweetviz_extracted/llm_global_analysis.json", max_workers=None):
    """Run global analysis across all datasets using `llm_global_analyzer` helpers.

    Writes output JSON to `output` path.
    """
    if global_analyzer is None:
        raise RuntimeError("llm_global_analyzer module not importable")

    payload = global_analyzer.build_global_payload(
        root,
        max_features_per_dataset=max_features_per_dataset,
        assoc_limit=assoc_limit,
        include_full_report=include_full_llm_dq_report,
        report_char_limit=report_char_limit,
        max_detail_values=max_detail_values,
        max_workers=max_workers,
    )

    dataset_count = (payload.get("portfolio_overview") or {}).get("dataset_count", 0)
    if dataset_count == 0:
        raise RuntimeError("No llm_dq_report.json files found under root")

    prompt = global_analyzer.build_global_prompt(payload)

    raw_text = global_analyzer.call_ollama(
        prompt,
        ollama_url=ollama_url or (settings.OLLAMA_URL if settings.OLLAMA_URL else "http://host.docker.internal:11434"),
        model=model,
        temperature=temperature,
        top_p=top_p,
        num_predict=num_predict,
        timeout_seconds=timeout_seconds,
    )

    parsed = global_analyzer.parse_json_response(raw_text)

    output_path = Path(output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as handle:
        json.dump(parsed, handle, indent=2, ensure_ascii=False)

    return str(output_path)


def run_data_quality_pipeline(
    dataset_id: str,
    version_id: str,
    pipeline_run_id: str,
    file_path: str,
    backend_url: str = None,
    debug: bool = False,
) -> bool:
    """
    Run the unified data quality analysis pipeline.

    This function orchestrates the complete analysis flow:
    1. Load dataset
    2. Run Sweetviz profiling
    3. Extract metrics and run LLM analysis
    4. Stream results to backend

    Designed to be invoked from Airflow PythonOperator.

    Args:
        dataset_id: Unique dataset identifier.
        version_id: Version identifier for this dataset.
        pipeline_run_id: Unique pipeline execution ID.
        file_path: Path to input CSV file.
        backend_url: Backend API URL (optional).
        debug: Enable debug logging.

    Returns:
        True if successful, False otherwise.
    """
    return run_pipeline(
        dataset_id=dataset_id,
        version_id=version_id,
        pipeline_run_id=pipeline_run_id,
        file_path=file_path,
        backend_url=backend_url,
        debug=debug,
    )


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
        _write_manifest(runner, "tables_manifest.json", {"tables": tables})
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
                failed.append({"table_name": table_name, "error": "load_failed"})
                continue
            sweetviz_data = runner._run_sweetviz(table_name, df)
            if sweetviz_data is None:
                failed.append({"table_name": table_name, "error": "sweetviz_failed"})
                continue
            profiled.append({
                "table_name": table_name,
                "file_path": str(table_path),
                "metrics_json_path": str(runner.storage.get_metrics_json_path(runner.dataset_id, runner.version_id, table_name)),
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
            runner._log(f"Analyzing table {table_name}")
            try:
                df = runner._load_dataset(table_path)
                if df is None:
                    raise RuntimeError("load_failed")
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
                result.data_signature = runner._build_data_signature(dataset_metrics, column_metrics)
                try:
                    result.data_signature["source_file_size_bytes"] = table_path.stat().st_size
                except OSError:
                    pass
                if df.attrs.get("csv_reader_warning"):
                    result.data_signature["csv_reader_warning"] = df.attrs.get("csv_reader_warning")
                    result.data_signature["csv_bad_line_policy"] = df.attrs.get("csv_bad_line_policy")
                result.change_status = "UNKNOWN"
                result.deterministic_relationships = runner._relationships_for_table(
                    deterministic_relationships,
                    table_name,
                )
                runner._write_json(result.analysis_json_path, result.to_dict())
                analyzed_table = {
                    "table_name": table_name,
                    "analysis_json_path": result.analysis_json_path,
                    "metrics_json_path": result.metrics_json_path,
                }
                analyzed.append(analyzed_table)
                if runner.backend_client.post_table_analysis(runner.pipeline_run_id, result, debug=debug):
                    runner._log(f"Published metrics for {table_name} before LLM enrichment")
                else:
                    runner._warn(f"Metrics publish failed for {table_name}; final publish will retry")

                llm_result = runner._run_llm_analysis(table_name, sweetviz_data)
                if llm_result:
                    result.llm_interpretation = llm_result.get("analysis")
                    result.llm_model_used = llm_result.get("model")
                result.processing_duration_seconds = time.time() - table_start
                runner._write_json(result.analysis_json_path, result.to_dict())
                if runner.backend_client.post_table_analysis(runner.pipeline_run_id, result, debug=debug):
                    runner._log(f"Published LLM-enriched result for {table_name}")
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
                    failed.append({"table_name": table_name, "error": "backend_post_failed"})
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
        deterministic_interpretation = runner._build_portfolio_interpretation(
            table_results,
            failed_count=len(manifest.get("failed", [])),
        )
        result.llm_interpretation = runner._run_global_llm_analysis(
            table_results,
            deterministic_interpretation,
            failed_count=len(manifest.get("failed", [])),
        )
        if result.llm_interpretation.get("_analysis_source") == "llm":
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


def phase_complete_pipeline(dataset_id, version_id, pipeline_run_id, file_path, backend_url=None, debug=False, forced_status=None, error_message=None):
    """Mark the backend pipeline run complete."""
    runner = _phase_runner(dataset_id, version_id, pipeline_run_id, file_path, backend_url, debug)
    try:
        manifest = _read_manifest(runner, "publish_manifest.json", {"published": [], "failed": []})
        published_count = len(manifest.get("published", []))
        failed_count = len(manifest.get("failed", []))
        if forced_status:
            status = forced_status
        elif published_count == 0:
            status = "failed"
        elif failed_count:
            status = "partial"
        else:
            status = "success"
        detail = error_message
        if detail is None and failed_count:
            detail = json.dumps(manifest.get("failed", []), ensure_ascii=False)
        if not runner.backend_client.post_pipeline_run_completed(
            runner.pipeline_run_id,
            status=status,
            error_message=detail,
            debug=debug,
        ):
            raise RuntimeError("Backend pipeline-completed POST failed")
        return {"status": status, "published": published_count, "failed": failed_count}
    finally:
        close_backend_client()


def run_sweetviz_extraction(data_dir="data", output_dir="sweetviz_extracted", save_html=True, max_workers=None):
    """Run comprehensive SweetViz extraction using the existing script's function."""
    try:
        # Prefer package-local implementation when available
        from dq_pipeline.extractors import comprehensive_sweetviz_extractor as swe
    except Exception:
        try:
            import comprehensive_sweetviz_extractor as swe
        except Exception as e:
            raise RuntimeError("comprehensive_sweetviz_extractor is not importable") from e

    swe.process_folder(data_dir, output_dir, save_html=save_html, max_workers=max_workers)
    return True


