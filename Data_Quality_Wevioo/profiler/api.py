import pandas as pd
from typing import Optional
from profiler.core.pipeline import run_pipeline
from profiler.exporters.json_exporter import export_to_json
from profiler.models.schemas import DataReport
from profiler.core.config import config

class Report:
    """
    API Interface natively abstracting rendering pipelines for public execution.
    """
    def __init__(self, data: DataReport):
        self._data = data
        
    def to_json(self, filepath: Optional[str] = None) -> str:
        """
        Dumps the entire pipeline exactly conforming to the target JSON payloads limits structurally explicitly.
        """
        return export_to_json(self._data, filepath=filepath)

def analyze(df: pd.DataFrame, dataset_name: str = "dataset", target_feat: Optional[str] = None) -> Report:
    """
    Mimics generic Sweetviz API parameters providing end-user cleanly accessible abstractions.
    """
    # Extract report directly via orchestrator 
    report_data = run_pipeline(df, dataset_name=dataset_name, target_feat=target_feat)
    
    # Encapsulate natively 
    return Report(report_data)
