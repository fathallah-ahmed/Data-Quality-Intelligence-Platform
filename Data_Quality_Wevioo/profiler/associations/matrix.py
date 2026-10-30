import pandas as pd
from typing import Dict, List, Any
from profiler.models.schemas import AssociationPair, AssociationOutput
from profiler.associations.metrics import compute_pearson, compute_correlation_ratio, compute_uncertainty_coefficient

def build_association_matrix(df: pd.DataFrame, feature_types: Dict[str, str]) -> AssociationOutput:
    """
    Builds the complete mapping executing matrices mapping combinatorically. 
    Explicitly drops empty null targets natively safely without pipelines bounds.
    """
    valid_types = ["numerical", "categorical", "boolean"]
    # Safely restrict execution boundaries to statistically viable arrays only
    features = [f for f in df.columns if feature_types.get(f) in valid_types]
    
    # Init matrix safely ensuring None placeholders exist securely natively
    matrix: Dict[str, Dict[str, float]] = {f: {} for f in features}
    pairs: List[AssociationPair] = []
    
    # Base diagonals strictly to 1.0 (Self-Identity limit representation natively)
    for f in features:
        for f2 in features:
            matrix[f][f2] = 1.0 if f == f2 else None
            
    # Iterate all O(n^2) paths safely
    for i in range(len(features)):
        for j in range(len(features)):
            f1 = features[i]
            f2 = features[j]
            t1 = feature_types[f1]
            t2 = feature_types[f2]
            
            if f1 == f2:
                continue
                
            res = None
            if t1 == "numerical" and t2 == "numerical":
                res = compute_pearson(df[f1], df[f2])
            elif t1 == "numerical" and t2 in ["categorical", "boolean"]:
                # f1 Num, f2 Cat -> Eta: num predicted by cat
                res = compute_correlation_ratio(cat_series=df[f2], num_series=df[f1])
            elif t1 in ["categorical", "boolean"] and t2 == "numerical":
                 # f1 Cat, f2 Num -> Eta
                 res = compute_correlation_ratio(cat_series=df[f1], num_series=df[f2])
            elif t1 in ["categorical", "boolean"] and t2 in ["categorical", "boolean"]:
                 # U(f1 | f2) = fraction of f1's info explained exactly given knowing f2 natively
                 res = compute_uncertainty_coefficient(df[f1], df[f2])
                 
            # Guard mapping directly only passing valid properties structurally
            if res and res.value is not None:
                matrix[f1][f2] = res.value
                
                pairs.append(AssociationPair(
                    feature_1=f1,
                    feature_2=f2,
                    metric_name=res.metric_name,
                    value=res.value,
                    symmetric=res.symmetric,
                    direction=res.direction
                ))
                
    return AssociationOutput(
        association_matrix=matrix,
        association_pairs=pairs
    )
