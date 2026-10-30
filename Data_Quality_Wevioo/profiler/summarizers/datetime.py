import pandas as pd
from typing import List, Dict, Any
from dataclasses import asdict
from profiler.models.schemas import DatetimeFeatureSummary, DatetimeSuspiciousFlag
from profiler.summarizers.common import summarize_common
from profiler.core.config import config

def summarize_datetime(series: pd.Series, inferred_type: str = "datetime") -> DatetimeFeatureSummary:
    """
    Computes purely datetime logic safely stacked natively on the common statistical basis.
    """
    common = summarize_common(series, inferred_type=inferred_type)
    
    non_missing = series.dropna()
    n_non_null = len(non_missing)
    
    if n_non_null == 0:
        return DatetimeFeatureSummary(**asdict(common))
        
    # Safely convert to uniform datetime
    if not pd.api.types.is_datetime64_any_dtype(non_missing):
        # We rely on pandas native parsing, explicitly coercing formatting issues safely
        dt_series = pd.to_datetime(non_missing, errors='coerce').dropna()
    else:
        dt_series = non_missing
        
    n_non_null_dt = len(dt_series)
    
    if n_non_null_dt == 0:
        # Fails parsing completely causing 100% loss. Safely fall back.
        return DatetimeFeatureSummary(**asdict(common))

    # Serializing cleanly to isolated strings ensures structural JSON integration won't die
    min_val = str(dt_series.min())
    max_val = str(dt_series.max())
    
    # Top frequencies extraction 
    value_counts = dt_series.value_counts(dropna=True)
    top_k = config.top_categories_k
    top_categories_raw = value_counts.head(top_k)
    
    most_frequent = []
    for k, v in top_categories_raw.items():
        most_frequent.append({
            "value": str(k),
            "count": int(v),
            "percentage": float((v / n_non_null_dt) * 100) # explicitly based on non_null parsing count natively
        })
        
    # Suspicious Flags evaluation natively via direct string mapping
    suspicious_targets = ["1900-01-01", "1970-01-01"]
    suspicious_flags = []
    
    # Normalize our timestamps to the target string level dynamically
    # dt_series.dt.strftime('%Y-%m-%d') handles standardization reliably but is slow
    # Direct parsing from string format is faster
    for st in suspicious_targets:
        try:
            st_dt = pd.to_datetime(st)
            count = int((dt_series == st_dt).sum())
            if count > 0:
                suspicious_flags.append(DatetimeSuspiciousFlag(
                    value=st,
                    count=count,
                    percentage=float((count / n_non_null_dt) * 100)
                ))
        except Exception:
            pass 
            
    return DatetimeFeatureSummary(
        **asdict(common),
        min=min_val,
        max=max_val,
        most_frequent=most_frequent,
        suspicious_flags=suspicious_flags
    )
