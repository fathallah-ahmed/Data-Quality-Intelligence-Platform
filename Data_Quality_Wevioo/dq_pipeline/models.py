"""
Data models for pipeline processing results.

Defines structures for table-level and global-level analysis results
that are sent to the backend via REST API.
"""

from dataclasses import dataclass, asdict, field
from typing import Any, Dict, List, Optional
from datetime import datetime, timezone
import json


def _utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


@dataclass
class ColumnMetrics:
    """Metrics extracted for a single column."""
    name: str
    data_type: str
    missing_count: int
    missing_percent: float
    distinct_count: int
    distinct_percent: float
    is_id_like: bool = False
    is_date_like: bool = False

    # Numeric stats (if applicable)
    min_value: Optional[float] = None
    q1_value: Optional[float] = None
    median_value: Optional[float] = None
    q3_value: Optional[float] = None
    max_value: Optional[float] = None
    mean_value: Optional[float] = None
    std_value: Optional[float] = None

    # Top values for categorical columns
    top_values: List[Dict[str, Any]] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return asdict(self)


@dataclass
class DatasetMetrics:
    """High-level metrics for a dataset."""
    row_count: int
    column_count: int
    num_categorical: int
    num_numerical: int
    num_text: int
    num_date: int
    duplicate_rows: int
    duplicate_percent: float

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return asdict(self)


@dataclass
class TableAnalysisResult:
    """Result from analyzing a single table."""
    dataset_id: str
    version_id: str
    pipeline_run_id: str
    table_name: str

    # Timing
    analysis_timestamp: str  # ISO format datetime
    processing_duration_seconds: float

    # Metrics extracted from SweetViz
    dataset_metrics: DatasetMetrics
    column_metrics: List[ColumnMetrics]

    # LLM interpretation
    llm_interpretation: Optional[Dict[str, Any]] = None
    llm_model_used: Optional[str] = None
    # pending (before enrichment), ok, unavailable (Ollama down), invalid (answer rejected), disabled
    llm_status: Optional[str] = None

    # File paths (populated by storage module)
    metrics_json_path: Optional[str] = None
    analysis_json_path: Optional[str] = None

    # Version/change detection support: counts plus content/schema hashes the
    # backend compares with the previous version.
    data_signature: Optional[Dict[str, Any]] = None
    change_status: Optional[str] = None  # NEW, MODIFIED, UNCHANGED, REMOVED, UNKNOWN

    # Deterministic relationship hypotheses discovered from column value overlap.
    # This is produced before LLM enrichment so the UI can draw a class diagram early.
    deterministic_relationships: List[Dict[str, Any]] = field(default_factory=list)

    # Metadata
    status: str = "completed"  # completed, empty, failed
    error_message: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        data = asdict(self)
        # Convert nested dataclasses
        data['dataset_metrics'] = self.dataset_metrics.to_dict()
        data['column_metrics'] = [cm.to_dict() for cm in self.column_metrics]
        return data

    def to_json(self) -> str:
        """Convert to JSON string."""
        return json.dumps(self.to_dict(), indent=2, ensure_ascii=False)


@dataclass
class GlobalAnalysisResult:
    """Result from global (portfolio-level) analysis."""
    pipeline_run_id: str

    # Timing
    analysis_timestamp: str  # ISO format datetime
    processing_duration_seconds: float

    # Portfolio overview
    dataset_count: int
    total_row_count: int
    total_column_count: int
    avg_row_count: float
    avg_column_count: float

    # Table names analyzed
    tables_analyzed: List[str]

    # LLM interpretation (portfolio-level insights)
    llm_interpretation: Optional[Dict[str, Any]] = None
    llm_model_used: Optional[str] = None
    llm_status: Optional[str] = None

    # Associations and relationships discovered
    key_entities: List[str] = field(default_factory=list)
    relationships: List[str] = field(default_factory=list)
    semantic_concepts: List[Dict[str, Any]] = field(default_factory=list)

    # File paths
    analysis_json_path: Optional[str] = None

    # Metadata
    status: str = "completed"  # completed, failed, partial
    error_message: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return asdict(self)

    def to_json(self) -> str:
        """Convert to JSON string."""
        return json.dumps(self.to_dict(), indent=2, ensure_ascii=False)


class ResultBuilder:
    """Helper to build results with default values."""

    @staticmethod
    def table_analysis(
        dataset_id: str,
        version_id: str,
        pipeline_run_id: str,
        table_name: str,
        dataset_metrics: DatasetMetrics,
        column_metrics: List[ColumnMetrics],
        processing_duration_seconds: float,
    ) -> TableAnalysisResult:
        """Build a table analysis result."""
        return TableAnalysisResult(
            dataset_id=dataset_id,
            version_id=version_id,
            pipeline_run_id=pipeline_run_id,
            table_name=table_name,
            analysis_timestamp=_utc_timestamp(),
            processing_duration_seconds=processing_duration_seconds,
            dataset_metrics=dataset_metrics,
            column_metrics=column_metrics,
        )

    @staticmethod
    def global_analysis(
        pipeline_run_id: str,
        tables_analyzed: List[str],
        total_rows: int,
        total_cols: int,
        processing_duration_seconds: float,
    ) -> GlobalAnalysisResult:
        """Build a global analysis result."""
        avg_rows = total_rows / len(tables_analyzed) if tables_analyzed else 0
        avg_cols = total_cols / len(tables_analyzed) if tables_analyzed else 0

        return GlobalAnalysisResult(
            pipeline_run_id=pipeline_run_id,
            analysis_timestamp=_utc_timestamp(),
            processing_duration_seconds=processing_duration_seconds,
            dataset_count=len(tables_analyzed),
            total_row_count=total_rows,
            total_column_count=total_cols,
            avg_row_count=avg_rows,
            avg_column_count=avg_cols,
            tables_analyzed=tables_analyzed,
        )
