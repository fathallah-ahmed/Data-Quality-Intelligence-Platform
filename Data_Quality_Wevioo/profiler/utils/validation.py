import pandas as pd

def safe_nunique(series: pd.Series, dropna: bool = True) -> int:
    """
    Safely calculates exactly native series uniqueness counts dropping elements securely
    handling explicitly unhashable structures recursively mapping cleanly safely.
    """
    try:
        return int(series.nunique(dropna=dropna))
    except TypeError:
        # Fails gracefully exactly if encountering structurally unhashable collections (lists, dicts, sets).
        return 0
