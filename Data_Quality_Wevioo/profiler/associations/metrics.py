import pandas as pd
import numpy as np
import scipy.stats as ss
from typing import Dict, Any, Optional
from dataclasses import dataclass, field

@dataclass
class MetricResult:
    metric_name: str
    value: Optional[float]
    symmetric: bool
    direction: str = "" # "x_given_y", "num_given_cat", "symmetric"
    metadata: Dict[str, Any] = field(default_factory=dict)

def compute_pearson(x: pd.Series, y: pd.Series) -> MetricResult:
    """
    Computes Pearson correlation coefficient between two continuous variables.
    r = cov(X,Y) / (std(X)*std(Y))
    """
    df = pd.concat([x, y], axis=1).dropna()
    if len(df) < 2:
        return MetricResult("pearson", None, True, "symmetric", {"error": "not enough data", "n_samples": len(df)})
        
    x_name, y_name = df.columns
    x_vals, y_vals = df[x_name].astype(float), df[y_name].astype(float)
    
    if x_vals.nunique() <= 1 or y_vals.nunique() <= 1:
        return MetricResult("pearson", None, True, "symmetric", {"warning": "zero variance", "n_samples": len(df)})
        
    r, p_value = ss.pearsonr(x_vals, y_vals)
    return MetricResult(
        metric_name="pearson", 
        value=float(r), 
        symmetric=True, 
        direction="symmetric",
        metadata={"p_value": float(p_value), "n_samples": len(df)}
    )

def compute_correlation_ratio(cat_series: pd.Series, num_series: pd.Series) -> MetricResult:
    """
    Computes the correlation ratio (eta) for categorical vs continuous variable.
    Directional: How much variance in the continuous variable is explained by the categories.
    """
    df = pd.concat([cat_series, num_series], axis=1).dropna()
    if len(df) < 2:
        return MetricResult("correlation_ratio", None, False, "num_given_cat", {"error": "not enough data", "n_samples": len(df)})
        
    cat_name, num_name = df.columns
    num_vals = df[num_name].astype(float)
    
    if num_vals.nunique() <= 1 or df[cat_name].nunique() <= 1:
        return MetricResult("correlation_ratio", None, False, "num_given_cat", {"warning": "zero variance", "n_samples": len(df)})
        
    y_mean = num_vals.mean()
    ss_total = ((num_vals - y_mean) ** 2).sum()
    
    ss_between = 0.0
    for _, group in df.groupby(cat_name)[num_name]:
        n_x = len(group)
        y_bar_x = group.astype(float).mean()
        ss_between += n_x * ((y_bar_x - y_mean) ** 2)
        
    eta = np.sqrt(ss_between / ss_total) if ss_total > 0 else 0.0
    
    return MetricResult(
        metric_name="correlation_ratio",
        value=float(eta),
        symmetric=False,
        direction="num_given_cat",
        metadata={"n_samples": len(df)}
    )

def compute_uncertainty_coefficient(x: pd.Series, y: pd.Series) -> MetricResult:
    """
    Computes Theil's U (Uncertainty Coefficient) for categorical vs categorical variables.
    U(X|Y) = I(X;Y) / H(X)
    Directional: Fraction of X's entropy explained by knowing Y.
    """
    df = pd.concat([x, y], axis=1).dropna()
    if len(df) < 2:
         return MetricResult("uncertainty_coefficient", None, False, "x_given_y", {"error": "not enough data", "n_samples": len(df)})
         
    x_name, y_name = df.columns
    
    p_x = df[x_name].value_counts(normalize=True).values
    p_y = df[y_name].value_counts(normalize=True).values
    
    h_x = ss.entropy(p_x)
    if h_x == 0:
        return MetricResult("uncertainty_coefficient", None, False, "x_given_y", {"warning": "zero entropy in target", "n_samples": len(df)})
        
    joint_counts = df.groupby([x_name, y_name], observed=True).size()
    joint_prob = joint_counts / len(df)
    h_xy = ss.entropy(joint_prob.values)
    
    h_y = ss.entropy(p_y)
    
    mutual_info = h_x + h_y - h_xy
    u_x_given_y = mutual_info / h_x
    
    u_x_given_y = max(0.0, float(u_x_given_y))
    u_x_given_y = min(1.0, u_x_given_y)
    
    return MetricResult(
        metric_name="uncertainty_coefficient",
        value=u_x_given_y,
        symmetric=False,
        direction="x_given_y",
        metadata={"n_samples": len(df), "entropy_x": float(h_x), "entropy_y": float(h_y)}
    )
