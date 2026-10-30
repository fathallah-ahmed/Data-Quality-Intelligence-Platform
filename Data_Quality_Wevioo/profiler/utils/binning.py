import pandas as pd
import numpy as np
from typing import Dict, Any, Union

def generate_histogram_data(series: pd.Series, bins: Union[int, str]) -> Dict[str, Any]:
    """
    Generates histogram bin edges and counts safely for JSON serialization.
    Can pass numeric integers (e.g. 5, 15, 30) or strings ('auto').
    """
    cleaned = series.dropna()
    if len(cleaned) == 0:
        return {"edges": [], "counts": []}
        
    counts, edges = np.histogram(cleaned, bins=bins)
    
    # Cast cleanly back to standard python primitives
    return {
        "counts": [int(c) for c in counts],
        "edges": [float(e) for e in edges]
    }
