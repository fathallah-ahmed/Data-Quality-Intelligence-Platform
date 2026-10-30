import pandas as pd
from profiler.models.schemas import TextFeatureSummary
from profiler.summarizers.common import summarize_common
from profiler.core.config import config
from dataclasses import asdict

def summarize_text(series: pd.Series) -> TextFeatureSummary:
    # 1. Evaluate baseline schemas sequentially via Common routines safely!
    common_stats = summarize_common(series, inferred_type="text")
    
    non_missing_series = series.dropna()
    n_non_null = len(non_missing_series)
    
    if n_non_null == 0:
        return TextFeatureSummary(**asdict(common_stats))
        
    # Safely treat all items as standard strings
    str_series = non_missing_series.astype(str)
    
    lengths = str_series.str.len()
    avg_length = float(lengths.mean()) if n_non_null > 0 else 0.0
    min_length = int(lengths.min()) if n_non_null > 0 else 0
    max_length = int(lengths.max()) if n_non_null > 0 else 0
    
    empty_string_count = int((str_series == "").sum())
    # Identify whitespaces like " " or "\n" but strictly not empty strings
    whitespace_only_count = int(str_series.str.isspace().sum())

    val_counts = non_missing_series.value_counts(dropna=True)
    top_k = config.top_categories_k
    
    most_frequent = []
    for val, count in val_counts.head(top_k).items():
        most_frequent.append({
            "value": str(val),
            "count": int(count),
            "percentage": (count / n_non_null) * 100 if n_non_null > 0 else 0.0
        })

    return TextFeatureSummary(
        **asdict(common_stats),
        average_length=avg_length,
        min_length=min_length,
        max_length=max_length,
        empty_string_count=empty_string_count,
        whitespace_only_count=whitespace_only_count,
        most_frequent=most_frequent
    )
