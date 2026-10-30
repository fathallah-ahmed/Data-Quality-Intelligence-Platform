import json
import numpy as np
import pandas as pd
from typing import Any

class SafeJSONEncoder(json.JSONEncoder):
    """
    Safely captures straggling Pandas/Numpy types guaranteeing native JSON stability.
    """
    def default(self, obj: Any) -> Any:
        if isinstance(obj, np.integer):
            return int(obj)
        if isinstance(obj, np.floating):
            return float(obj)
        if isinstance(obj, np.ndarray):
            return obj.tolist()
        if pd.isna(obj):
            return None
        return super().default(obj)
