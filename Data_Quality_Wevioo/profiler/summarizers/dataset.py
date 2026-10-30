import pandas as pd
from typing import Dict, Any, Optional
from profiler.core.datatypes import infer_feature_type
from profiler.models.schemas import DatasetSummary

def summarize_dataset(df: pd.DataFrame, dataset_name: str = "dataset") -> DatasetSummary:
    """
    Computes a global dataset-level summary summarizing total counts and feature distributions.
    Reuses the type inference layer to calculate the breakdown of column types safely.
    """
    n_rows = len(df)
    n_cols = len(df.columns)
    
    # Calculate duplicate rows
    # It drops unhashable types if any exist in try-except block, or just skips it
    try:
        if n_cols == 0:
            duplicate_rows = 0
        else:
            duplicate_rows = int(df.duplicated().sum())
    except TypeError:
        # Fallback if unhashable elements (e.g., dicts, lists) prevent simple duplication checks
        duplicate_rows = 0

    # Determine memory usage 
    if n_cols == 0:
        memory_usage_bytes = 0
    else:
        # deep=True to accurately account for Python objects overhead inside Pandas
        memory_usage_bytes = int(df.memory_usage(deep=True).sum())

    # Pre-populate all expected vocabulary literals dynamically
    feature_type_counts = {
        "numerical": 0,
        "categorical": 0,
        "text": 0,
        "boolean": 0,
        "datetime": 0,
        "id_like": 0,
        "unsupported_or_mixed": 0
    }

    # Iterate logic across inferred types
    for col in df.columns:
        infer_result = infer_feature_type(df[col])
        feature_type_counts[infer_result.feature_type] += 1

    return DatasetSummary(
        dataset_name=dataset_name,
        n_rows=n_rows,
        n_cols=n_cols,
        memory_usage_bytes=memory_usage_bytes,
        duplicate_rows=duplicate_rows,
        feature_type_counts=feature_type_counts
    )
