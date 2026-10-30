import pandas as pd
from profiler.models.schemas import FeatureSummaryBase
from profiler.utils.validation import safe_nunique

def summarize_common(series: pd.Series, inferred_type: str) -> FeatureSummaryBase:
    name = str(series.name)
    n_total = len(series)
    non_missing_series = series.dropna()
    n_non_null = len(non_missing_series)
    
    n_missing = n_total - n_non_null
    missing_percentage = (n_missing / n_total) * 100 if n_total > 0 else 0.0
    
    distinct_count = safe_nunique(non_missing_series)
    distinct_percentage = (distinct_count / n_non_null) * 100 if n_non_null > 0 else 0.0
    
    duplicate_count = n_non_null - distinct_count
    
    is_zero_relevant = False
    zero_count = 0
    zero_percentage = 0.0
    
    if pd.api.types.is_numeric_dtype(series) or inferred_type in ["numerical", "boolean"]:
        is_zero_relevant = True
        try:
            zero_count = int((non_missing_series == 0).sum())
            zero_percentage = (zero_count / n_total) * 100 if n_total > 0 else 0.0
        except Exception:
            is_zero_relevant = False
            zero_count = 0
            zero_percentage = 0.0
            
    return FeatureSummaryBase(
        feature_type=inferred_type,
        name=name,
        n_total=n_total,
        non_null_count=n_non_null,
        missing_count=n_missing,
        missing_percentage=missing_percentage,
        distinct_count=distinct_count,
        distinct_percentage=distinct_percentage,
        duplicate_count=duplicate_count,
        is_zero_relevant=is_zero_relevant,
        zero_count=zero_count,
        zero_percentage=zero_percentage
    )
