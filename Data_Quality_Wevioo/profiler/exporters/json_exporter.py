import json
import numpy as np
import pandas as pd
from typing import Any, Optional
from dataclasses import asdict
from profiler.models.schemas import DataReport
from profiler.utils.encoders import SafeJSONEncoder



def export_to_json(report: DataReport, filepath: Optional[str] = None) -> str:
    """
    Transforms the aggregated structured python tree directly cleanly into LLM-consumable JSON mapping.
    """
    # Flattens dataclasses recursively safely 
    report_dict = asdict(report)
    
    # Generate tightly formatted string
    json_str = json.dumps(report_dict, indent=2, ensure_ascii=False, cls=SafeJSONEncoder)
    
    if filepath:
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(json_str)
            
    return json_str
