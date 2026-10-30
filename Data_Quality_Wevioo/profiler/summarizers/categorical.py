import pandas as pd
from typing import List, Dict, Any
from dataclasses import asdict
from profiler.models.schemas import CategoricalFeatureSummary
from profiler.summarizers.common import summarize_common
from profiler.core.config import config

def summarize_categorical(series: pd.Series, inferred_type: str = "categorical") -> CategoricalFeatureSummary:
    """
    Computes purely categorical logic (class frequencies, percentages, limits).
    Built organically upon the common statistical inference baseline to guarantee dataset-wide properties.
    """
    common = summarize_common(series, inferred_type=inferred_type)
    
    non_missing = series.dropna()
    n_non_null = len(non_missing)
    
    if n_non_null == 0:
        return CategoricalFeatureSummary(
            **asdict(common),
            top_categories=[],
            other_bucket_count=0,
            other_bucket_percentage=0.0
        )
        
    value_counts = non_missing.value_counts(dropna=True)
    top_k = config.top_categories_k
    
    top_categories_raw = value_counts.head(top_k)
    
    top_categories = []
    # Force the key bounds to strings natively so JSON export does not crash identically with tuples/dict keys
    for k, v in top_categories_raw.items():
        top_categories.append({
            "value": str(k),
            "count": int(v),
            "percentage": float((v / n_non_null) * 100)
        })
        
    # Pool remaining classes safely
    if len(value_counts) > top_k:
        other_bucket_count = int(value_counts.iloc[top_k:].sum())
    else:
        other_bucket_count = 0
        
    other_bucket_percentage = float((other_bucket_count / n_non_null) * 100) if n_non_null > 0 else 0.0
    
    common_dict = asdict(common)
    
    return CategoricalFeatureSummary(
        **common_dict,
        top_categories=top_categories,
        other_bucket_count=other_bucket_count,
        other_bucket_percentage=other_bucket_percentage
    )
