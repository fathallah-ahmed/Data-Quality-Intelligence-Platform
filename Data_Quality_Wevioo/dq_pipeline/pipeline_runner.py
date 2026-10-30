"""
Shared logic for the data quality pipeline phases (see ``runners.py``).

``PipelineRunner`` holds the context of one pipeline run (dataset, version, run id,
input path) and the steps the Airflow phases call: table discovery and loading,
Sweetviz profiling, metric extraction, change signatures, deterministic
relationship discovery and the table / global LLM analysis.
"""

import csv
import hashlib
import json
import sys
import time
from pathlib import Path
from typing import Optional, List, Dict, Any, Set, Tuple
import pandas as pd

from dq_pipeline.config.settings import settings
from dq_pipeline.storage import get_storage_manager
from dq_pipeline.backend_client import get_backend_client
from dq_pipeline.models import (
    ColumnMetrics,
    DatasetMetrics,
    TableAnalysisResult,
)
from dq_pipeline.analyzers.langgraph_analyzer import (
    LLM_STATUS_DISABLED,
    LLM_STATUS_INVALID,
    LLM_STATUS_OK,
    LLM_STATUS_UNAVAILABLE,
    LlmUnavailableError,
)
from dq_pipeline.analyzers.llm_analyzer import analyze_table_metrics
from dq_pipeline.analyzers import llm_global_analyzer
from dq_pipeline.extractors.comprehensive_sweetviz_extractor import extract_sweetviz_metrics, read_csv_flex


class PipelineRunner:
    """Main orchestrator for pipeline execution."""

    def __init__(
        self,
        dataset_id: str,
        version_id: str,
        pipeline_run_id: str,
        file_path: str,
        backend_url: Optional[str] = None,
        debug: bool = False,
    ):
        """
        Initialize pipeline runner.

        Args:
            dataset_id: Unique dataset identifier.
            version_id: Version identifier for this dataset.
            pipeline_run_id: Unique identifier for this pipeline execution.
            file_path: Path to input CSV file.
            backend_url: Backend API URL (optional, uses settings.BACKEND_URL if not provided).
            debug: Enable debug logging.
        """
        self.dataset_id = dataset_id
        self.version_id = version_id
        self.pipeline_run_id = pipeline_run_id
        self.file_path = Path(file_path)
        self.debug = debug

        # Initialize infrastructure
        self.storage = get_storage_manager()
        self.backend_client = get_backend_client(backend_url=backend_url)

        # Ensure storage directories exist
        self.storage.ensure_version_exists(dataset_id, version_id)

        self.errors: List[str] = []


    def _discover_table_files(self) -> List[Path]:
        """Return CSV files to process from a single file or a version directory."""
        if not self.file_path.exists():
            self._error(f"Input path not found: {self.file_path}")
            return []

        if self.file_path.is_dir():
            csv_files = sorted(
                path for path in self.file_path.iterdir()
                if path.is_file()
                and path.suffix.lower() == ".csv"
                and path.name.lower() != "dataset_config.csv"
            )
            if not csv_files:
                self._error(f"No CSV tables found in directory: {self.file_path}")
            else:
                self._log(f"Discovered {len(csv_files)} CSV table(s)")
            return csv_files

        if self.file_path.suffix.lower() != ".csv":
            self._error(f"Unsupported file type for pipeline: {self.file_path}")
            return []

        return [self.file_path]

    def _load_dataset(self, table_path: Path) -> Optional[pd.DataFrame]:
        """
        Load dataset from file.

        Returns:
            DataFrame if successful, None otherwise.
        """
        try:
            if not table_path.exists():
                self._error(f"File not found: {table_path}")
                return None

            df = read_csv_flex(table_path)
            encoding = df.attrs.get("csv_encoding")
            separator = df.attrs.get("csv_separator")
            csv_hint = f" using encoding={encoding}, separator={separator!r}" if encoding or separator else ""
            self._log(f"Loaded dataset: {len(df)} rows x {len(df.columns)} columns{csv_hint}")
            if df.attrs.get("csv_reader_warning"):
                self._warn(f"CSV parser warning for {table_path.name}: {df.attrs['csv_reader_warning']}")
            return df

        except Exception as e:
            self._error(f"Failed to load dataset: {e}")
            self.errors.append(f"Load failed: {table_path}: {e}")
            return None


    def _run_sweetviz(self, table_name: str, df: pd.DataFrame) -> Optional[Dict[str, Any]]:
        """
        Run Sweetviz profiling on the dataframe.

        Args:
            table_name: Table name.
            df: DataFrame to profile.

        Returns:
            Sweetviz report data if successful, None otherwise.
        """
        try:
            # Set up output paths. We save JSON only; Angular will render its own
            # dynamic pages from metrics and interpretations.
            json_path = self.storage.get_metrics_json_path(self.dataset_id, self.version_id, table_name)

            # Run Sweetviz extraction
            result = extract_sweetviz_metrics(
                df,
                table_name,
                output_html_path=None,
                output_json_path=str(json_path),
                debug=self.debug,
            )

            if result.get("status") == "failed":
                self._error(f"Sweetviz profiling failed: {result.get('error')}")
                return None

            return result

        except Exception as e:
            self._error(f"Sweetviz profiling failed: {e}")
            import traceback
            traceback.print_exc()
            return None

    def _extract_metrics(
        self,
        table_name: str,
        df: pd.DataFrame,
        sweetviz_data: Dict[str, Any],
    ) -> tuple[DatasetMetrics, List[ColumnMetrics]]:
        """
        Extract metrics from the dataframe.

        Args:
            table_name: Table name.
            df: DataFrame.
            sweetviz_data: SweetViz profiling output.

        Returns:
            Tuple of (DatasetMetrics, List[ColumnMetrics]).
        """
        try:
            # Dataset-level metrics
            row_count = len(df)
            col_count = len(df.columns)
            num_date = sum(1 for col in df.columns if pd.api.types.is_datetime64_any_dtype(df[col]))
            num_numerical = sum(
                1 for col in df.columns
                if pd.api.types.is_numeric_dtype(df[col])
                and not pd.api.types.is_bool_dtype(df[col])
            )
            num_text = sum(
                1 for col in df.columns
                if pd.api.types.is_object_dtype(df[col]) or pd.api.types.is_string_dtype(df[col])
            )
            num_categorical = sum(
                1 for col in df.columns
                if pd.api.types.is_object_dtype(df[col])
                or pd.api.types.is_string_dtype(df[col])
                or pd.api.types.is_categorical_dtype(df[col])
                or pd.api.types.is_bool_dtype(df[col])
            )
            duplicate_rows = df.duplicated().sum()
            duplicate_percent = (duplicate_rows / row_count * 100) if row_count > 0 else 0

            dataset_metrics = DatasetMetrics(
                row_count=row_count,
                column_count=col_count,
                num_categorical=num_categorical,
                num_numerical=num_numerical,
                num_text=num_text,
                num_date=num_date,
                duplicate_rows=int(duplicate_rows),
                duplicate_percent=float(duplicate_percent),
            )

            # Column-level metrics
            column_metrics = []
            for col in df.columns:
                col_data = df[col]
                missing_count = col_data.isnull().sum()
                missing_percent = (missing_count / len(df) * 100) if len(df) > 0 else 0
                distinct_count = col_data.nunique()
                distinct_percent = (distinct_count / len(df) * 100) if len(df) > 0 else 0

                metric = ColumnMetrics(
                    name=str(col),
                    data_type=str(col_data.dtype),
                    missing_count=int(missing_count),
                    missing_percent=float(missing_percent),
                    distinct_count=int(distinct_count),
                    distinct_percent=float(distinct_percent),
                    is_id_like=len(df) > 0 and distinct_count == len(df),
                    is_date_like=False,  # Simplified
                )

                # Numeric stats. Boolean columns are intentionally excluded:
                # pandas/numpy quantile interpolates with subtraction, which is
                # invalid for bool arrays.
                if pd.api.types.is_numeric_dtype(col_data) and not pd.api.types.is_bool_dtype(col_data):
                    non_null = pd.to_numeric(col_data.dropna(), errors="coerce").dropna()
                    if not non_null.empty:
                        metric.min_value = self._safe_float(non_null.min())
                        metric.q1_value = self._safe_float(non_null.quantile(0.25))
                        metric.median_value = self._safe_float(non_null.median())
                        metric.q3_value = self._safe_float(non_null.quantile(0.75))
                        metric.max_value = self._safe_float(non_null.max())
                        metric.mean_value = self._safe_float(non_null.mean())
                        metric.std_value = self._safe_float(non_null.std())

                column_metrics.append(metric)

            return dataset_metrics, column_metrics

        except Exception as e:
            self._error(f"Metric extraction failed: {e}")
            raise

    def _apply_runtime_quality_overrides(
        self,
        sweetviz_data: Dict[str, Any],
        dataset_metrics: DatasetMetrics,
        df: pd.DataFrame,
    ) -> None:
        """Keep LLM evidence aligned with metrics computed from the loaded DataFrame."""
        source = sweetviz_data.setdefault("summary_source", {})
        source["num_rows"] = dataset_metrics.row_count
        source["num_columns"] = dataset_metrics.column_count
        source["duplicates"] = {
            "number": dataset_metrics.duplicate_rows,
            "perc": dataset_metrics.duplicate_percent,
        }

        csv_reader = {
            "encoding": df.attrs.get("csv_encoding"),
            "separator": df.attrs.get("csv_separator"),
            "bad_line_policy": df.attrs.get("csv_bad_line_policy"),
            "warning": df.attrs.get("csv_reader_warning"),
            "strict_errors": df.attrs.get("csv_strict_errors"),
        }
        cleaned_reader = {
            key: value for key, value in csv_reader.items()
            if value not in (None, "", [])
        }
        if cleaned_reader:
            sweetviz_data["csv_reader"] = cleaned_reader

    def _discover_value_relationships_from_files(self, table_sources: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Infer cross-table relationships from value overlap without calling the LLM."""
        profiles: List[Dict[str, Any]] = []
        for table in table_sources:
            table_name = str(table.get("table_name") or "").strip()
            file_path = table.get("file_path")
            if not table_name or not file_path:
                continue
            df = self._load_dataset(Path(str(file_path)))
            if df is None:
                continue
            profiles.extend(self._relationship_profiles_from_frame(table_name, df))

        relationships = self._score_relationship_profiles(profiles)
        if relationships:
            self._log(f"Discovered {len(relationships)} deterministic relationship candidate(s)")
        else:
            self._log("No deterministic relationships discovered from value overlap")
        return relationships

    def _relationships_for_table(
        self,
        relationships: List[Dict[str, Any]],
        table_name: str,
    ) -> List[Dict[str, Any]]:
        return [
            relation
            for relation in relationships
            if relation.get("source_table") == table_name
        ]

    def _relationship_profiles_from_frame(self, table_name: str, df: pd.DataFrame) -> List[Dict[str, Any]]:
        max_values = 5000
        row_count = len(df)
        profiles = []
        for column in df.columns:
            series = df[column]
            non_null = series.dropna()
            if non_null.empty:
                continue

            distinct_count = int(non_null.nunique(dropna=True))
            if distinct_count < 2:
                continue

            values = self._normalized_value_sample(non_null, max_values)
            if len(values) < 2:
                continue

            column_name = str(column)
            missing_percent = float((series.isnull().sum() / row_count * 100) if row_count > 0 else 0)
            distinct_ratio = float((distinct_count / row_count) if row_count > 0 else 0)
            identifier_name = self._is_relationship_identifier_name(column_name)
            own_identifier = self._is_own_relationship_identifier(table_name, column_name)

            profiles.append({
                "table": table_name,
                "column": column_name,
                "data_type": str(series.dtype),
                "values": values,
                "value_count": len(values),
                "distinct_count": distinct_count,
                "distinct_ratio": distinct_ratio,
                "missing_percent": missing_percent,
                "sampled": distinct_count > len(values),
                "identifier_name": identifier_name,
                "own_identifier": own_identifier,
                "target_candidate": (
                    (distinct_ratio >= 0.95 and missing_percent <= 10)
                    or (identifier_name and distinct_ratio >= 0.50 and missing_percent <= 25)
                ),
                "source_candidate": missing_percent <= 95 and not own_identifier,
            })
        return profiles

    def _score_relationship_profiles(self, profiles: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        targets = [profile for profile in profiles if profile["target_candidate"]]
        sources = [profile for profile in profiles if profile["source_candidate"]]
        relationships = []

        for source in sources:
            candidates = []
            source_values = source["values"]
            if len(source_values) < 3:
                continue

            for target in targets:
                if source["table"] == target["table"]:
                    continue
                if not self._compatible_relationship_types(source["data_type"], target["data_type"]):
                    continue

                target_values = target["values"]
                overlap = len(source_values.intersection(target_values))
                if overlap < 2:
                    continue

                coverage = overlap / len(source_values)
                if coverage < 0.85:
                    continue

                name_score = self._relationship_name_score(source, target)
                strong_key_overlap = (
                    source["identifier_name"]
                    and target["own_identifier"]
                    and coverage >= 0.98
                )
                if len(source_values) < 5 and name_score <= 0 and not strong_key_overlap:
                    continue

                confidence = "high" if coverage >= 0.98 and (name_score > 0 or target["own_identifier"]) else "medium"
                score = (
                    coverage * 0.72
                    + min(1.0, overlap / 20) * 0.12
                    + name_score * 0.12
                    + (0.04 if target["own_identifier"] else 0)
                )
                candidates.append({
                    "source_table": source["table"],
                    "source_column": source["column"],
                    "target_table": target["table"],
                    "target_column": target["column"],
                    "confidence": confidence,
                    "score": round(score, 4),
                    "coverage": round(coverage, 4),
                    "overlap_count": overlap,
                    "source_distinct_count": source["distinct_count"],
                    "target_distinct_count": target["distinct_count"],
                    "sampled": bool(source["sampled"] or target["sampled"]),
                    "reason": (
                        f"{coverage * 100:.1f}% of sampled non-null source values exist "
                        f"in the target candidate key."
                    ),
                    "_name_score": name_score,
                })

            if not candidates:
                continue

            candidates.sort(key=lambda item: item["score"], reverse=True)
            best = candidates[0]
            runner_up = candidates[1] if len(candidates) > 1 else None
            value_only = best["_name_score"] <= 0
            ambiguous = runner_up is not None and abs(best["score"] - runner_up["score"]) < 0.04
            if value_only and ambiguous:
                continue

            best.pop("_name_score", None)
            relationships.append(best)

        relationships.sort(key=lambda item: (
            item["source_table"],
            item["source_column"],
            item["target_table"],
            item["target_column"],
        ))
        return relationships

    def _normalized_value_sample(self, series: pd.Series, max_values: int) -> Set[str]:
        values: Set[str] = set()
        for value in series.drop_duplicates().head(max_values):
            normalized = self._normalize_relationship_value(value)
            if normalized:
                values.add(normalized)
        return values

    def _normalize_relationship_value(self, value: Any) -> str:
        if pd.isna(value):
            return ""
        if isinstance(value, float) and value.is_integer():
            return str(int(value))
        text = str(value).strip().lower()
        if text.endswith(".0"):
            prefix = text[:-2]
            if prefix.lstrip("-").isdigit():
                return prefix
        return " ".join(text.split())

    def _compatible_relationship_types(self, source_type: str, target_type: str) -> bool:
        source_lower = source_type.lower()
        target_lower = target_type.lower()
        source_numeric = any(token in source_lower for token in ("int", "float", "double", "decimal", "number"))
        target_numeric = any(token in target_lower for token in ("int", "float", "double", "decimal", "number"))
        return source_numeric == target_numeric or not source_numeric or not target_numeric

    def _relationship_name_score(self, source: Dict[str, Any], target: Dict[str, Any]) -> float:
        source_column = self._normalize_relationship_identifier(str(source["column"]))
        target_column = self._normalize_relationship_identifier(str(target["column"]))
        source_entity = self._relationship_column_entity_token(str(source["column"]))
        target_entity = self._relationship_entity_token(str(target["table"]))
        target_column_entity = self._relationship_column_entity_token(str(target["column"]))

        if source_column == target_column:
            return 1.0
        if source_entity and source_entity in {target_entity, target_column_entity}:
            return 0.85
        if source_column.endswith(target_column) or target_column.endswith(source_column):
            return 0.55
        return 0.0

    def _is_relationship_identifier_name(self, column_name: str) -> bool:
        normalized = self._normalize_relationship_identifier(column_name)
        return (
            normalized == "id"
            or normalized.endswith("_id")
            or normalized.endswith("_key")
            or normalized.endswith("_code")
            or normalized.endswith("_ref")
            or normalized.endswith("_reference")
            or "identifier" in normalized
        )

    def _is_own_relationship_identifier(self, table_name: str, column_name: str) -> bool:
        column_entity = self._relationship_column_entity_token(column_name)
        table_entity = self._relationship_entity_token(table_name)
        normalized_column = self._normalize_relationship_identifier(column_name)
        return normalized_column == "id" or bool(column_entity and column_entity == table_entity)

    def _relationship_entity_token(self, table_name: str) -> str:
        return self._singular_relationship_identifier(self._normalize_relationship_identifier(table_name))

    def _relationship_column_entity_token(self, column_name: str) -> str:
        normalized = self._normalize_relationship_identifier(column_name)
        normalized = (
            normalized
            .replace("_identifier", "")
            .replace("_reference", "")
            .replace("_ref", "")
            .replace("_id", "")
            .replace("_key", "")
            .replace("_code", "")
        )
        return self._singular_relationship_identifier(normalized)

    def _normalize_relationship_identifier(self, value: str) -> str:
        import unicodedata

        normalized = unicodedata.normalize("NFD", value)
        normalized = "".join(char for char in normalized if unicodedata.category(char) != "Mn")
        normalized = "".join(
            f"_{char.lower()}" if char.isupper() else char.lower()
            for char in normalized
        )
        normalized = "".join(char if char.isalnum() else "_" for char in normalized)
        while "__" in normalized:
            normalized = normalized.replace("__", "_")
        return normalized.strip("_")

    def _singular_relationship_identifier(self, identifier: str) -> str:
        parts = []
        for part in identifier.split("_"):
            if len(part) > 3 and part.endswith("ies"):
                parts.append(f"{part[:-3]}y")
            elif len(part) > 3 and part.endswith("s") and not part.endswith("ss"):
                parts.append(part[:-1])
            else:
                parts.append(part)
        return "_".join(parts)

    def _run_llm_analysis(self, table_name: str, sweetviz_data: Dict[str, Any]) -> Dict[str, Any]:
        """
        Run the table LLM analysis on the profiling data.

        Always returns a dict with ``llm_status`` (ok / unavailable / invalid / disabled)
        and, when available, ``analysis`` (an LLM or deterministic interpretation).
        """
        if not settings.TABLE_LLM_ENABLED:
            return {"llm_status": LLM_STATUS_DISABLED}
        try:
            dataset_config_path = self._dataset_config_path()
            return analyze_table_metrics(
                table_name=table_name,
                sweetviz_data=sweetviz_data,
                ollama_url=str(settings.OLLAMA_URL) if settings.OLLAMA_URL else None,
                model=settings.OLLAMA_MODEL,
                debug=self.debug,
                dataset_config_path=str(dataset_config_path) if dataset_config_path else None,
            )

        except LlmUnavailableError as e:
            self._warn(f"LLM unavailable for {table_name}: {e}")
            return {"llm_status": LLM_STATUS_UNAVAILABLE}
        except Exception as e:
            self._error(f"LLM analysis failed for {table_name}: {e}")
            self.errors.append(f"LLM analysis error: {e}")
            return {"llm_status": LLM_STATUS_INVALID}

    def _dataset_config_path(self) -> Optional[Path]:
        """Return the optional context config stored with uploaded table files."""
        base_path = self.file_path if self.file_path.is_dir() else self.file_path.parent
        config_path = base_path / "dataset_config.csv"
        return config_path if config_path.exists() else None

    @staticmethod
    def _safe_float(value):
        """Return a JSON-safe float, or None for NaN/inf/non-numeric values."""
        try:
            value = float(value)
        except (TypeError, ValueError):
            return None
        if pd.isna(value):
            return None
        if value in (float("inf"), float("-inf")):
            return None
        return value

    def _empty_table_metrics(self, table_name: str, df: pd.DataFrame) -> Dict[str, Any]:
        """Minimal metrics document for a header-only table (Sweetviz cannot profile 0 rows)."""
        return {
            "status": "empty",
            "table_name": table_name,
            "summary_source": {
                "num_rows": 0,
                "num_columns": len(df.columns),
                "duplicates": {"number": 0, "perc": 0.0},
            },
            "features": {
                str(column): {"name": str(column), "type": str(df[column].dtype), "base_stats": {}}
                for column in df.columns
            },
        }

    @staticmethod
    def _content_hash(df: pd.DataFrame) -> str:
        """SHA-256 over the table values (row order included), independent of the file encoding."""
        digest = hashlib.sha256()
        if len(df) == 0:
            return digest.hexdigest()
        try:
            row_hashes = pd.util.hash_pandas_object(df, index=False)
            digest.update(row_hashes.to_numpy().tobytes())
        except TypeError:
            # Unhashable cell values (lists, dicts): fall back to the CSV text of the frame.
            digest.update(df.to_csv(index=False).encode("utf-8"))
        return digest.hexdigest()

    @staticmethod
    def _schema_hash(df: pd.DataFrame) -> str:
        schema = [[str(column), str(dtype)] for column, dtype in df.dtypes.items()]
        return hashlib.sha256(json.dumps(schema).encode("utf-8")).hexdigest()

    def _build_data_signature(
        self,
        dataset_metrics: DatasetMetrics,
        column_metrics: List[ColumnMetrics],
        df: Optional[pd.DataFrame] = None,
    ) -> Dict[str, Any]:
        """
        Build the change-detection signature the backend compares with the previous
        version to decide whether a table is NEW, MODIFIED or UNCHANGED.

        ``content_hash`` covers every value, so editing a single cell is detected;
        ``schema_hash`` covers column names and types. The count fields are kept so
        signatures from older versions (counts only) can still be compared.
        """
        signature = {
            "row_count": dataset_metrics.row_count,
            "column_count": dataset_metrics.column_count,
            "duplicate_rows": dataset_metrics.duplicate_rows,
            "columns": [
                {
                    "name": column.name,
                    "data_type": column.data_type,
                    "missing_count": column.missing_count,
                    "distinct_count": column.distinct_count,
                }
                for column in column_metrics
            ],
        }
        if df is not None:
            signature["content_hash"] = self._content_hash(df)
            signature["schema_hash"] = self._schema_hash(df)
        return signature

    def _build_portfolio_interpretation(
        self,
        table_results: List[TableAnalysisResult],
        failed_count: int = 0,
    ) -> Dict[str, Any]:
        """Build a useful global interpretation from table-level results."""
        table_names = [result.table_name for result in table_results]
        dataset_config = self._load_dataset_context_config()
        global_context = dataset_config.get("__global__", {})
        global_description = global_context.get("description") or ""
        global_question = global_context.get("custom_question") or ""
        total_rows = sum(result.dataset_metrics.row_count for result in table_results)
        total_cols = sum(result.dataset_metrics.column_count for result in table_results)
        total_missing = sum(
            column.missing_count
            for result in table_results
            for column in result.column_metrics
        )
        total_duplicates = sum(result.dataset_metrics.duplicate_rows for result in table_results)
        high_missing_columns = [
            {
                "table": result.table_name,
                "column": column.name,
                "missing_percent": column.missing_percent,
            }
            for result in table_results
            for column in result.column_metrics
            if column.missing_percent >= 50
        ]
        id_like_columns = [
            f"{result.table_name}.{column.name}"
            for result in table_results
            for column in result.column_metrics
            if column.is_id_like
        ]

        quality_notes = []
        if total_missing:
            quality_notes.append(f"{total_missing} missing cell(s)")
        if total_duplicates:
            quality_notes.append(f"{total_duplicates} duplicate row(s)")
        if high_missing_columns:
            quality_notes.append(f"{len(high_missing_columns)} high-missing column(s)")
        if not quality_notes:
            quality_notes.append("no major completeness or duplicate issues detected")
        business_context = (
            f" User-provided business context: {global_description}"
            if global_description
            else ""
        )
        stakeholder_questions = [
            "Which columns are mandatory for reporting?",
            "Which ID columns define relationships between these tables?",
            "Are high-missing columns optional, deprecated, or ingestion errors?",
        ]
        if global_question:
            stakeholder_questions.insert(0, global_question)

        return {
            "_analysis_source": "deterministic",
            "summary": f"Processed {len(table_results)} table(s) for dataset {self.dataset_id} version {self.version_id}.",
            "tables_analyzed": table_names,
            "table_status": {
                "succeeded": len(table_results),
                "failed": failed_count,
            },
            "portfolio_summary": {
                "business_view": (
                    f"The dataset version contains {len(table_results)} table(s): {', '.join(table_names) or 'none'}. "
                    f"Together they provide {total_rows} rows and {total_cols} columns for portfolio-level analysis."
                    f"{business_context}"
                ),
                "data_quality_posture": (
                    f"Global quality review found {', '.join(quality_notes)} across the uploaded tables."
                ),
                "key_portfolio_patterns": [
                    f"Detected {len(id_like_columns)} ID-like column(s)." if id_like_columns else "No ID-like columns were detected.",
                    f"Analyzed tables: {', '.join(table_names) or 'none'}.",
                ],
                "critical_risks": [
                    f"{item['table']}.{item['column']} has {item['missing_percent']:.2f}% missing values."
                    for item in high_missing_columns[:10]
                ],
            },
            "cross_dataset_findings": [
                "Review ID-like columns as potential join keys before cross-table reporting.",
                "Compare common business columns across tables for naming and type consistency.",
            ],
            "portfolio_action_plan": [
                "Prioritize high-missing columns and confirm whether they are required business fields.",
                "Validate ID-like columns and relationships between uploaded tables.",
                "Standardize column naming and data types before downstream analytics.",
            ],
            "recommendations_by_time_horizon": {
                "short_term": [
                    "Fix critical missingness and duplicate records.",
                    "Confirm table ownership and business definitions.",
                ],
                "medium_term": [
                    "Add validation rules for required fields and ID columns.",
                    "Track table-level quality trends by dataset version.",
                ],
                "long_term": [
                    "Create governance checks for schema drift and cross-table consistency.",
                ],
            },
            "questions_for_stakeholders": stakeholder_questions,
            "final_assessment": (
                "The portfolio is ready for exploratory review. Resolve highlighted completeness, duplicate, "
                "and key-column issues before using it for operational reporting or model training."
            ),
        }

    def _run_global_llm_analysis(
        self,
        table_results: List[TableAnalysisResult],
        fallback_interpretation: Dict[str, Any],
        failed_count: int = 0,
    ) -> Tuple[Dict[str, Any], str]:
        """Return (interpretation, llm_status) for the global analysis."""
        if not settings.GLOBAL_LLM_ENABLED:
            return fallback_interpretation, LLM_STATUS_DISABLED

        try:
            dataset_config = self._load_dataset_context_config()
            payload = self._build_global_llm_payload(table_results, dataset_config, failed_count)
            analysis = llm_global_analyzer.analyze_pipeline_global_payload(
                payload,
                ollama_url=str(settings.OLLAMA_URL) if settings.OLLAMA_URL else None,
                model=settings.OLLAMA_MODEL,
                temperature=settings.GLOBAL_LLM_TEMPERATURE,
                top_p=settings.GLOBAL_LLM_TOP_P,
                num_predict=settings.GLOBAL_LLM_NUM_PREDICT,
                timeout_seconds=settings.GLOBAL_LLM_TIMEOUT_SECONDS,
            )
            if not isinstance(analysis, dict):
                raise ValueError("The global LLM answer is not a JSON object")
            analysis["_analysis_source"] = "llm"
            return analysis, LLM_STATUS_OK
        except Exception as exc:
            unavailable = isinstance(exc, LlmUnavailableError)
            self._warn(f"Global LLM analysis {'unavailable' if unavailable else 'rejected'}; using deterministic fallback: {exc}")
            fallback = dict(fallback_interpretation)
            fallback["_analysis_source"] = "deterministic"
            fallback["_fallback_reason"] = str(exc)
            return fallback, (LLM_STATUS_UNAVAILABLE if unavailable else LLM_STATUS_INVALID)

    def _build_global_llm_payload(
        self,
        table_results: List[TableAnalysisResult],
        dataset_config: Dict[str, Dict[str, str]],
        failed_count: int,
    ) -> Dict[str, Any]:
        global_context = dataset_config.get("__global__", {})
        tables = []
        relationships = []

        for result in table_results:
            interpretation = result.llm_interpretation or {}
            quality = interpretation.get("quality_assessment") or {}
            issues = quality.get("issues") if isinstance(quality.get("issues"), list) else []
            high_missing = [
                {
                    "column": column.name,
                    "missing_percent": round(column.missing_percent, 4),
                    "missing_count": column.missing_count,
                }
                for column in result.column_metrics
                if column.missing_count > 0
            ]
            high_missing.sort(key=lambda item: item["missing_percent"], reverse=True)
            table_relationships = result.deterministic_relationships or []
            relationships.extend(table_relationships)
            table_context = dataset_config.get(result.table_name, {})

            tables.append({
                "table": result.table_name,
                "description": table_context.get("description") or "",
                "custom_question": table_context.get("custom_question") or "",
                "rows": result.dataset_metrics.row_count,
                "columns": result.dataset_metrics.column_count,
                "duplicates": result.dataset_metrics.duplicate_rows,
                "duplicate_percent": round(result.dataset_metrics.duplicate_percent, 4),
                "column_inventory": [
                    {
                        "name": column.name,
                        "type": column.data_type,
                        "missing_percent": round(column.missing_percent, 4),
                        "distinct_percent": round(column.distinct_percent, 4),
                        "is_id_like": column.is_id_like,
                        "is_date_like": column.is_date_like,
                    }
                    for column in result.column_metrics
                ],
                "top_missing_columns": high_missing[:12],
                "deterministic_relationships": table_relationships,
                "table_llm_summary": (interpretation.get("interpretation") or {}).get("summary"),
                "table_quality_issues": [str(item) for item in issues[:8]],
                "parser_warning": (result.data_signature or {}).get("csv_reader_warning"),
            })

        return {
            "dataset_id": self.dataset_id,
            "version_id": self.version_id,
            "pipeline_run_id": self.pipeline_run_id,
            "global_description": global_context.get("description") or "",
            "global_question": global_context.get("custom_question") or "",
            "table_status": {
                "succeeded": len(table_results),
                "failed": failed_count,
            },
            "tables": tables,
            "deterministic_relationships": relationships,
        }

    def _load_dataset_context_config(self) -> Dict[str, Dict[str, str]]:
        config_path = self._dataset_config_path()
        if config_path is None:
            return {}
        try:
            with open(config_path, "r", encoding="utf-8-sig", newline="") as handle:
                reader = csv.DictReader(handle)
                return {
                    str(row.get("dataset_name") or "").strip(): {
                        "description": str(row.get("description") or "").strip(),
                        "custom_question": str(row.get("custom_question") or "").strip(),
                    }
                    for row in reader
                    if str(row.get("dataset_name") or "").strip()
                }
        except Exception as exc:
            self._warn(f"Could not read dataset context config: {exc}")
            return {}

    def _write_json(self, path: str, payload: Dict[str, Any]) -> None:
        """Write a local JSON copy of a result before backend streaming."""
        output_path = Path(path)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with open(output_path, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2, ensure_ascii=False)

    def _log(self, message: str):
        """Log informational message."""
        timestamp = time.strftime("%H:%M:%S")
        print(f"[{timestamp}] [INFO] {message}", file=sys.stdout)

    def _warn(self, message: str):
        """Log warning message."""
        timestamp = time.strftime("%H:%M:%S")
        print(f"[{timestamp}] [WARN] {message}", file=sys.stderr)

    def _error(self, message: str):
        """Log error message."""
        timestamp = time.strftime("%H:%M:%S")
        print(f"[{timestamp}] [ERROR] {message}", file=sys.stderr)
