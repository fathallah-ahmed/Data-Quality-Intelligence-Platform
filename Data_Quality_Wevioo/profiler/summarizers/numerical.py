import pandas as pd
import numpy as np
from typing import List, Dict, Any
from dataclasses import asdict
from profiler.models.schemas import NumericalFeatureSummary
from profiler.summarizers.common import summarize_common
from profiler.utils.binning import generate_histogram_data

def _get_top_values(series: pd.Series, n: int = 5, n_total: int = 1, smallest: bool = False, largest: bool = False, frequency: bool = False) -> List[Dict[str, Any]]:
    if len(series) == 0:
        return []
    
    val_counts = series.value_counts(dropna=True)
    
    if frequency:
        target = val_counts.head(n)
    elif smallest:
        target = val_counts.sort_index(ascending=True).head(n)
    elif largest:
        target = val_counts.sort_index(ascending=False).head(n)
    else:
        target = val_counts.head(n)
        
    return [
        {
            "value": float(k), 
            "count": int(v), 
            "percentage": float((v / max(n_total, 1)) * 100) # explicit mapping based on non null values
        } 
        for k, v in target.items()
    ]

def summarize_numerical(series: pd.Series) -> NumericalFeatureSummary:
    common = summarize_common(series, inferred_type="numerical")
    
    non_missing = series.dropna()
    n_non_null = len(non_missing)
    n_total = len(series)
    
    if n_non_null == 0:
        # Relies accurately on None bounds mapped previously
        return NumericalFeatureSummary(**asdict(common))
        
    num_series = non_missing.astype(float)
    
    min_val = float(num_series.min())
    max_val = float(num_series.max())
    range_val = max_val - min_val
    mean_val = float(num_series.mean())
    median_val = float(num_series.median())
    
    q1 = float(num_series.quantile(0.25))
    q3 = float(num_series.quantile(0.75))
    p5 = float(num_series.quantile(0.05))
    p95 = float(num_series.quantile(0.95))
    iqr = q3 - q1
    
    std_val = float(num_series.std()) if n_non_null > 1 else 0.0
    var_val = float(num_series.var()) if n_non_null > 1 else 0.0
    skew_val = float(num_series.skew()) if n_non_null > 2 else 0.0
    kurt_val = float(num_series.kurtosis()) if n_non_null > 3 else 0.0
    sum_val = float(num_series.sum())
    
    most_frequent = _get_top_values(num_series, n=5, n_total=n_non_null, frequency=True)
    smallest = _get_top_values(num_series, n=5, n_total=n_non_null, smallest=True)
    largest = _get_top_values(num_series, n=5, n_total=n_non_null, largest=True)
    
    hist_auto = generate_histogram_data(num_series, bins='auto')
    hist_5 = generate_histogram_data(num_series, bins=5)
    hist_15 = generate_histogram_data(num_series, bins=15)
    hist_30 = generate_histogram_data(num_series, bins=30)
    
    common_dict = asdict(common)
    
    return NumericalFeatureSummary(
        **common_dict,
        min=min_val,
        max=max_val,
        range=range_val,
        mean=mean_val,
        median=median_val,
        q1=q1,
        q3=q3,
        p5=p5,
        p95=p95,
        iqr=iqr,
        std=std_val,
        variance=var_val,
        skewness=skew_val,
        kurtosis=kurt_val,
        sum=sum_val,
        most_frequent=most_frequent,
        smallest=smallest,
        largest=largest,
        histogram_auto=hist_auto,
        histogram_5=hist_5,
        histogram_15=hist_15,
        histogram_30=hist_30
    )
