import pandas as pd
from profiler.models.schemas import IdLikeFeatureSummary
from profiler.summarizers.common import summarize_common
from dataclasses import asdict
import random

def summarize_id_like(series: pd.Series, inferred_type: str = "id_like", num_samples: int = 5) -> IdLikeFeatureSummary:
    common_stats = summarize_common(series, inferred_type=inferred_type)
    
    non_missing = series.dropna()
    n_non_null = len(non_missing)
    if n_non_null == 0:
        return IdLikeFeatureSummary(**asdict(common_stats))
        
    try:
        unique_vals = non_missing.unique().tolist()
        sampled = random.sample(unique_vals, min(num_samples, len(unique_vals)))
    except TypeError:
        sampled = []
        
    cleaned_samples = []
    for val in sampled:
        if isinstance(val, (int, float, str, bool)):
            cleaned_samples.append(val)
        else:
            cleaned_samples.append(str(val))
            
    uniqueness_ratio = common_stats.distinct_count / common_stats.non_null_count if common_stats.non_null_count > 0 else 0.0
            
    return IdLikeFeatureSummary(
        **asdict(common_stats),
        uniqueness_ratio=uniqueness_ratio,
        sample_values=cleaned_samples
    )
