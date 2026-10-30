import pandas as pd
from typing import Dict, List, Any
from profiler.models.schemas import RankedAssociation, FeatureAssociationsSummary
from profiler.associations.metrics import compute_pearson, compute_correlation_ratio, compute_uncertainty_coefficient
from profiler.core.config import config

def compute_feature_associations(df: pd.DataFrame, target_col: str, feature_types: Dict[str, str]) -> FeatureAssociationsSummary:
    """
    Computes ranked combinations mappings independently sorting correlation hooks down 
    via generic inferences. Supports exclusively numeric and nominals.
    """
    target_type = feature_types.get(target_col, "unsupported_or_mixed")
    
    numerical_assocs = []
    categorical_assocs = []
    provides_info = []
    given_info = []
    
    # Validation gates perfectly dropping untargetable parameters mapping
    valid_types = ["numerical", "categorical", "boolean"]
    if target_type not in valid_types:
        return FeatureAssociationsSummary(target_feature=target_col)
        
    for other_col, other_type in feature_types.items():
        if other_col == target_col or other_type not in valid_types:
            continue
            
        # Target Numerical Context
        if target_type == "numerical":
            if other_type == "numerical":
                res = compute_pearson(df[target_col], df[other_col])
                if res.value is not None:
                    numerical_assocs.append(RankedAssociation(other_col, "pearson", res.value, res.direction))
            elif other_type in ["categorical", "boolean"]:
                # Correlation ratio evaluates categoricals against continuous target (num)
                res = compute_correlation_ratio(df[other_col], df[target_col])
                if res.value is not None:
                    categorical_assocs.append(RankedAssociation(other_col, "correlation_ratio", res.value, res.direction))
                    
        # Target Categorical/Boolean Context
        elif target_type in ["categorical", "boolean"]:
            if other_type == "numerical":
                # Correlation ratio evaluates target (cat) against continuous (other)
                res = compute_correlation_ratio(df[target_col], df[other_col])
                if res.value is not None:
                    numerical_assocs.append(RankedAssociation(other_col, "correlation_ratio", res.value, res.direction))
            elif other_type in ["categorical", "boolean"]:
                # 1. Target provides info on Other -> U(Other|Target)
                res_provides = compute_uncertainty_coefficient(df[other_col], df[target_col])
                if res_provides.value is not None:
                    provides_info.append(RankedAssociation(other_col, "uncertainty_coefficient", res_provides.value, res_provides.direction))
                
                # 2. Target receives info from Other -> U(Target|Other)
                res_given = compute_uncertainty_coefficient(df[target_col], df[other_col])
                if res_given.value is not None:
                    given_info.append(RankedAssociation(other_col, "uncertainty_coefficient", res_given.value, res_given.direction))

    # Explicit configurations targeting sorts explicitly 
    k = config.top_associations_k
    sort_key = lambda x: abs(x.value)
    
    return FeatureAssociationsSummary(
        target_feature=target_col,
        numerical_associations=sorted(numerical_assocs, key=sort_key, reverse=True)[:k],
        categorical_associations=sorted(categorical_assocs, key=sort_key, reverse=True)[:k],
        provides_information_on=sorted(provides_info, key=sort_key, reverse=True)[:k],
        given_information_by=sorted(given_info, key=sort_key, reverse=True)[:k]
    )
