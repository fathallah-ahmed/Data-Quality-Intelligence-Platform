"""
Storage path management for versioned datasets and results.

Constructs and manages file paths for:
- Raw data files (input CSVs)
- Structured metrics JSON files
- Analysis results
"""

from pathlib import Path
from typing import Optional
from dq_pipeline.config.settings import settings


class StorageManager:
    """Manages versioned storage structure for datasets and results."""

    def __init__(self, base_storage_path: Optional[str] = None):
        """
        Initialize storage manager.
        
        Args:
            base_storage_path: Root storage directory. Defaults to settings.STORAGE_BASE_PATH.
        """
        self.base_path = Path(base_storage_path or settings.STORAGE_BASE_PATH or "./storage")
        self.base_path.mkdir(parents=True, exist_ok=True)

    # ========== Dataset-level paths ==========

    def get_dataset_version_path(self, dataset_id: str, version_id: str) -> Path:
        """
        Get the versioned dataset directory.
        
        Path: storage/datasets/{dataset_id}/v{version_id}/
        
        Args:
            dataset_id: Dataset identifier.
            version_id: Version identifier (e.g., "1", "v1", "2024-06-30").
        
        Returns:
            Path to versioned dataset directory.
        """
        path = self.base_path / "datasets" / dataset_id / f"v{version_id}"
        path.mkdir(parents=True, exist_ok=True)
        return path

    def get_raw_data_path(self, dataset_id: str, version_id: str) -> Path:
        """
        Get the raw data (CSV) directory for a versioned dataset.
        
        Path: storage/datasets/{dataset_id}/v{version_id}/raw_files/
        
        Args:
            dataset_id: Dataset identifier.
            version_id: Version identifier.
        
        Returns:
            Path to raw data directory.
        """
        path = self.get_dataset_version_path(dataset_id, version_id) / "raw_files"
        path.mkdir(parents=True, exist_ok=True)
        return path

    def get_raw_data_file_path(
        self,
        dataset_id: str,
        version_id: str,
        table_name: str,
        extension: str = ".csv",
    ) -> Path:
        """
        Get the path to a specific raw data file.
        
        Path: storage/datasets/{dataset_id}/v{version_id}/raw_files/{table_name}.csv
        
        Args:
            dataset_id: Dataset identifier.
            version_id: Version identifier.
            table_name: Table/file name (without extension).
            extension: File extension (default: .csv).
        
        Returns:
            Path to raw data file.
        """
        raw_dir = self.get_raw_data_path(dataset_id, version_id)
        return raw_dir / f"{table_name}{extension}"

    # ========== Legacy SweetViz HTML paths ==========

    def get_sweetviz_path(self, dataset_id: str, version_id: str) -> Path:
        """
        Get the legacy SweetViz HTML output directory for a versioned dataset.

        The current platform does not create SweetViz HTML from the Airflow
        pipeline. Angular renders dynamic pages from metrics/analysis JSON.
        
        Path: storage/sweetviz/{dataset_id}/v{version_id}/
        
        Args:
            dataset_id: Dataset identifier.
            version_id: Version identifier.
        
        Returns:
            Path to SweetViz directory.
        """
        path = self.base_path / "sweetviz" / dataset_id / f"v{version_id}"
        path.mkdir(parents=True, exist_ok=True)
        return path

    def get_sweetviz_html_path(
        self,
        dataset_id: str,
        version_id: str,
        table_name: str,
    ) -> Path:
        """
        Get the path to a legacy SweetViz HTML report.
        
        Path: storage/sweetviz/{dataset_id}/v{version_id}/{table_name}.html
        
        Args:
            dataset_id: Dataset identifier.
            version_id: Version identifier.
            table_name: Table name.
        
        Returns:
            Path to SweetViz HTML file.
        """
        sweetviz_dir = self.get_sweetviz_path(dataset_id, version_id)
        return sweetviz_dir / f"{table_name}.html"

    # ========== JSON metrics paths ==========

    def get_json_path(self, dataset_id: str, version_id: str) -> Path:
        """
        Get the JSON metrics output directory for a versioned dataset.
        
        Path: storage/json/{dataset_id}/v{version_id}/
        
        Args:
            dataset_id: Dataset identifier.
            version_id: Version identifier.
        
        Returns:
            Path to JSON directory.
        """
        path = self.base_path / "json" / dataset_id / f"v{version_id}"
        path.mkdir(parents=True, exist_ok=True)
        return path

    def get_metrics_json_path(
        self,
        dataset_id: str,
        version_id: str,
        table_name: str,
    ) -> Path:
        """
        Get the path to a metrics JSON file (backup).
        
        Path: storage/json/{dataset_id}/v{version_id}/{table_name}_metrics.json
        
        Args:
            dataset_id: Dataset identifier.
            version_id: Version identifier.
            table_name: Table name.
        
        Returns:
            Path to metrics JSON file.
        """
        json_dir = self.get_json_path(dataset_id, version_id)
        return json_dir / f"{table_name}_metrics.json"

    def get_analysis_json_path(
        self,
        dataset_id: str,
        version_id: str,
        table_name: str,
    ) -> Path:
        """
        Get the path to an analysis JSON file.
        
        Path: storage/json/{dataset_id}/v{version_id}/{table_name}_analysis.json
        
        Args:
            dataset_id: Dataset identifier.
            version_id: Version identifier.
            table_name: Table name.
        
        Returns:
            Path to analysis JSON file.
        """
        json_dir = self.get_json_path(dataset_id, version_id)
        return json_dir / f"{table_name}_analysis.json"

    # ========== Pipeline run paths ==========

    def get_pipeline_run_path(self, pipeline_run_id: str) -> Path:
        """
        Get the directory for a specific pipeline run.
        
        Path: storage/pipeline_runs/{pipeline_run_id}/
        
        Args:
            pipeline_run_id: Pipeline run identifier.
        
        Returns:
            Path to pipeline run directory.
        """
        path = self.base_path / "pipeline_runs" / pipeline_run_id
        path.mkdir(parents=True, exist_ok=True)
        return path

    def get_pipeline_run_log_path(self, pipeline_run_id: str) -> Path:
        """
        Get the log file path for a pipeline run.
        
        Path: storage/pipeline_runs/{pipeline_run_id}/execution.log
        
        Args:
            pipeline_run_id: Pipeline run identifier.
        
        Returns:
            Path to pipeline run log file.
        """
        run_dir = self.get_pipeline_run_path(pipeline_run_id)
        return run_dir / "execution.log"

    # ========== Utility methods ==========

    def get_all_table_names(self, dataset_id: str, version_id: str) -> list:
        """
        Get all table names for a dataset version from legacy SweetViz HTML files.
        
        Args:
            dataset_id: Dataset identifier.
            version_id: Version identifier.
        
        Returns:
            List of table names (without .html extension).
        """
        sweetviz_dir = self.get_sweetviz_path(dataset_id, version_id)
        if not sweetviz_dir.exists():
            return []
        
        return [
            f.stem for f in sweetviz_dir.glob("*.html")
            if f.is_file() and not f.name.startswith(".")
        ]

    def ensure_version_exists(self, dataset_id: str, version_id: str):
        """
        Ensure all necessary directories exist for a dataset version.
        
        Args:
            dataset_id: Dataset identifier.
            version_id: Version identifier.
        """
        self.get_dataset_version_path(dataset_id, version_id)
        self.get_raw_data_path(dataset_id, version_id)
        self.get_json_path(dataset_id, version_id)

    def cleanup_old_versions(
        self,
        dataset_id: str,
        keep_versions: int = 3,
        dry_run: bool = False,
    ) -> list:
        """
        Remove old versions of a dataset, keeping only the N most recent.
        
        Args:
            dataset_id: Dataset identifier.
            keep_versions: Number of recent versions to keep (default: 3).
            dry_run: If True, only report what would be deleted without actually deleting.
        
        Returns:
            List of deleted version IDs.
        """
        import shutil
        
        dataset_dir = self.base_path / "datasets" / dataset_id
        if not dataset_dir.exists():
            return []
        
        # Get all version directories sorted by name (descending)
        versions = sorted(
            [d.name for d in dataset_dir.iterdir() if d.is_dir() and d.name.startswith("v")],
            reverse=True,
        )
        
        deleted = []
        for version in versions[keep_versions:]:
            version_path = dataset_dir / version
            if dry_run:
                print(f"[DRY-RUN] Would delete: {version_path}")
                deleted.append(version)
            else:
                try:
                    shutil.rmtree(version_path)
                    deleted.append(version)
                    print(f"[CLEANUP] Deleted: {version_path}")
                except Exception as e:
                    print(f"[WARN] Failed to delete {version_path}: {e}")
        
        return deleted

    def get_storage_stats(self, dataset_id: Optional[str] = None) -> dict:
        """
        Get storage statistics.
        
        Args:
            dataset_id: Optional dataset ID to get stats for specific dataset.
        
        Returns:
            Dictionary with storage information.
        """
        import os
        
        def get_size(path):
            total = 0
            try:
                for entry in os.scandir(path):
                    if entry.is_file():
                        total += entry.stat().st_size
                    elif entry.is_dir():
                        total += get_size(entry.path)
            except Exception:
                pass
            return total
        
        stats = {}
        
        if dataset_id:
            dataset_path = self.base_path / "datasets" / dataset_id
            if dataset_path.exists():
                stats['dataset_size_bytes'] = get_size(dataset_path)
        else:
            stats['total_size_bytes'] = get_size(self.base_path)
            stats['datasets_size_bytes'] = get_size(self.base_path / "datasets") if (self.base_path / "datasets").exists() else 0
            stats['sweetviz_size_bytes'] = get_size(self.base_path / "sweetviz") if (self.base_path / "sweetviz").exists() else 0
            stats['json_size_bytes'] = get_size(self.base_path / "json") if (self.base_path / "json").exists() else 0
        
        return stats


# Convenience singleton-like functions
_storage_manager: Optional[StorageManager] = None


def get_storage_manager(base_path: Optional[str] = None) -> StorageManager:
    """Get or create a storage manager instance."""
    global _storage_manager
    if _storage_manager is None:
        _storage_manager = StorageManager(base_storage_path=base_path)
    return _storage_manager
