import pandas as pd
from typing import Literal, Dict, Any
from dataclasses import dataclass
from profiler.core.config import config
from profiler.utils.validation import safe_nunique

FeatureType = Literal[
    "numerical", "categorical", "text", "boolean", 
    "datetime", "id_like", "unsupported_or_mixed"
]

@dataclass
class InferenceResult:
    feature_type: FeatureType
    metadata: Dict[str, Any]

def infer_feature_type(series: pd.Series) -> InferenceResult:
    """
    Infers the conceptual feature type of a Pandas Series based on dtypes and statistical heuristics.
    
    Args:
        series (pd.Series): The pandas column to inspect.
        
    Returns:
        InferenceResult: Contains the `feature_type` literal and a `metadata` dictionary 
        explaining the heuristics and counts.
    """
    n_total = len(series)
    non_missing = series.dropna()
    n_non_null = len(non_missing)
    n_unique = safe_nunique(non_missing, dropna=True)
    
    # Check uniquely unhashable limits functionally elegantly!
    if n_unique == 0 and n_non_null > 0:
        base_metadata = {
            "original_dtype": str(series.dtype),
            "n_total": n_total,
            "n_non_null": n_non_null,
            "n_unique": 0,
            "reasoning": "Unhashable collection types completely unsupported natively."
        }
        return InferenceResult(feature_type="unsupported_or_mixed", metadata=base_metadata)
    
    # Base metadata capturing properties at inference time
    base_metadata = {
        "original_dtype": str(series.dtype),
        "n_total": n_total,
        "n_non_null": n_non_null,
        "n_unique": n_unique
    }
    
    # 1. Empty Check
    if n_non_null == 0:
        base_metadata["reasoning"] = "Column is entirely empty or null."
        return InferenceResult(feature_type="unsupported_or_mixed", metadata=base_metadata)
    
    unique_ratio = n_unique / n_non_null
    base_metadata["unique_ratio"] = unique_ratio

    # 2. Boolean Check
    if pd.api.types.is_bool_dtype(series):
        base_metadata["reasoning"] = "Native boolean dtype."
        return InferenceResult(feature_type="boolean", metadata=base_metadata)
        
    # Exact 0/1 integers often conceptually map to booleans
    if n_unique <= 2:
        if pd.api.types.is_numeric_dtype(series) and set(non_missing.unique()).issubset({0, 1}):
            base_metadata["reasoning"] = "Numeric containing strictly {0, 1} mapped as boolean."
            return InferenceResult(feature_type="boolean", metadata=base_metadata)

    # 3. Datetime Check
    if pd.api.types.is_datetime64_any_dtype(series):
        base_metadata["reasoning"] = "Native datetime dtype."
        return InferenceResult(feature_type="datetime", metadata=base_metadata)
        
    # Check strings for datetimes dynamically using pandas parser wrapper
    if config.datetime_parsing and (pd.api.types.is_object_dtype(series) or pd.api.types.is_string_dtype(series)):
        # Check a sample first before forcing the entire column to parse to avoid catastrophic slow-downs
        sample = non_missing.head(100)
        
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            try:
                # Tolerate natively parsing dynamically with coercion. If > 80% parse correctly into datetimes, explicitly flag organically!
                parsed_us = pd.to_datetime(sample, errors='coerce')
                parsed_eu = pd.to_datetime(sample, errors='coerce', dayfirst=True)
                
                max_valid = max(parsed_us.notna().sum(), parsed_eu.notna().sum())
                valid_ratio = max_valid / len(sample) if len(sample) > 0 else 0.0
                
                if valid_ratio >= 0.8:
                    base_metadata["reasoning"] = f"String sample resolves natively to Datetime with {valid_ratio*100:.1f}% confidence."
                    return InferenceResult(feature_type="datetime", metadata=base_metadata)
            except (ValueError, TypeError):
                pass 

    # 4. Numerical Properties Check
    if pd.api.types.is_numeric_dtype(series):
        
        # ID-Like: Almost all unique integer sequences
        if pd.api.types.is_integer_dtype(series) and unique_ratio >= config.id_like_unique_ratio:
            base_metadata["reasoning"] = f"Integer column with high uniqueness ratio ({unique_ratio:.2f}) mapped as ID."
            return InferenceResult(feature_type="id_like", metadata=base_metadata)
            
        # Low Cardinality Numerical -> Categorical
        if n_unique <= config.max_categorical_unique and n_non_null > config.max_categorical_unique:
             base_metadata["reasoning"] = f"Numeric dtype but low cardinality (unique_counts={n_unique} <= {config.max_categorical_unique}). Treated as categorical."
             return InferenceResult(feature_type="categorical", metadata=base_metadata)
             
        base_metadata["reasoning"] = "Evaluated safely as continuous numerical feature."
        return InferenceResult(feature_type="numerical", metadata=base_metadata)
        
    # 5. Mixed Type or Object strings Check
    if pd.api.types.is_object_dtype(series) or pd.api.types.is_string_dtype(series):
        
        # Numbers stored purely as strings
        try:
            sample = non_missing.head(100)
            pd.to_numeric(sample, errors='raise')
            # Evaluates whole subset
            pd.to_numeric(non_missing, errors='raise')
            
            base_metadata["reasoning"] = "Object column consists entirely of numeric string representations."
            # Re-evaluate cardinality for categorical vs numeric vs id_like
            if unique_ratio >= config.id_like_unique_ratio:
                 return InferenceResult(feature_type="id_like", metadata=base_metadata)
            if n_unique <= config.max_categorical_unique:
                 return InferenceResult(feature_type="categorical", metadata=base_metadata)
                 
            return InferenceResult(feature_type="numerical", metadata=base_metadata)

        except (ValueError, TypeError):
             pass # Not purely numerics mapped to strings
             
        # String/Categorical branching
        if unique_ratio >= config.id_like_unique_ratio:
             base_metadata["reasoning"] = f"High uniqueness ratio ({unique_ratio:.2f}) on text mapped as ID."
             return InferenceResult(feature_type="id_like", metadata=base_metadata)
             
        if unique_ratio > config.max_categorical_ratio or n_unique > config.max_categorical_unique:
             base_metadata["reasoning"] = f"High unique ratio ({unique_ratio:.2f}) and high counts ({n_unique}). Unstructured Text."
             return InferenceResult(feature_type="text", metadata=base_metadata)
             
        base_metadata["reasoning"] = "Low cardinality string mapped as Categorical."
        return InferenceResult(feature_type="categorical", metadata=base_metadata)
        
    # 6. Unsupported / Mixed Edge Cases
    base_metadata["reasoning"] = "Failed to cleanly match known types or contains irreconcilable mixed types natively."
    return InferenceResult(feature_type="unsupported_or_mixed", metadata=base_metadata)
