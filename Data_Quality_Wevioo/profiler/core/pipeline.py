import pandas as pd
from typing import Optional, Dict
from profiler.core.datatypes import infer_feature_type
from profiler.summarizers.dataset import summarize_dataset
from profiler.summarizers.numerical import summarize_numerical
from profiler.summarizers.categorical import summarize_categorical
from profiler.summarizers.datetime import summarize_datetime
from profiler.summarizers.text import summarize_text
from profiler.summarizers.id_like import summarize_id_like
from profiler.summarizers.common import summarize_common
from profiler.associations.matrix import build_association_matrix
from profiler.associations.summaries import compute_feature_associations
from profiler.models.schemas import DataReport, FeatureSummaryBase

def _route_column_summary(series: pd.Series, inferred_type: str) -> FeatureSummaryBase:
    """
    Routs column processing inherently securely to the specific underlying mathematical layer cleanly.
    """
    if inferred_type == "numerical":
        return summarize_numerical(series)
    elif inferred_type == "categorical":
        return summarize_categorical(series, inferred_type="categorical")
    elif inferred_type == "boolean":
        # Evaluated categorically explicitly structurally preventing math breakage on bounds
        return summarize_categorical(series, inferred_type="boolean")
    elif inferred_type == "text":
        return summarize_text(series)
    elif inferred_type == "datetime":
        return summarize_datetime(series)
    elif inferred_type == "id_like":
        return summarize_id_like(series)
    else:
        # Fallback ensuring native unparsed data is summarized functionally via valid generics.
        return summarize_common(series, inferred_type="unsupported_or_mixed")

def run_pipeline(df: pd.DataFrame, dataset_name: str = "dataset", target_feat: Optional[str] = None) -> DataReport:
    """
    Primary orchestration engine cleanly sweeping execution arrays completely systematically natively.
    """
    # 1. Dataset Core Properties & Inferences extraction
    dataset_summary = summarize_dataset(df, dataset_name=dataset_name)
    
    inferred_types = {}
    feature_summaries = {}
    
    # 2. Iterate each column predicting mapping cleanly identically to targeted profiles
    for col in df.columns:
        inf_res = infer_feature_type(df[col])
        inferred_types[col] = inf_res.feature_type
        
        feature_summaries[col] = _route_column_summary(df[col], inf_res.feature_type)
        
    # 3. Compile local Ranked Association structures organically mapping metrics
    feature_associations = {}
    for col in df.columns:
        feature_associations[col] = compute_feature_associations(df, target_col=col, feature_types=inferred_types)
        
    # 4. Integrate final combinatoric matrix layers recursively cleanly 
    matrix_output = build_association_matrix(df, feature_types=inferred_types)
    
    # Wrap Data limits securely matching schema expectations 1:1 internally
    return DataReport(
        dataset_summary=dataset_summary,
        feature_summaries=feature_summaries,
        feature_associations=feature_associations,
        association_matrix=matrix_output.association_matrix,
        association_pairs=matrix_output.association_pairs,
        target_feat=target_feat
    )
