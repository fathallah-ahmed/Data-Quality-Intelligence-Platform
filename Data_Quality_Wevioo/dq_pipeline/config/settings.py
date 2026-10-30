import os
from pathlib import Path
from typing import Optional


def _load_env_file(path: str = ".env") -> None:
    p = Path(path)
    if not p.exists():
        return
    for raw in p.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        k = k.strip()
        v = v.strip().strip('"').strip("'")
        os.environ.setdefault(k, v)


# Pre-load local .env if present so fallback Settings pick it up
_load_env_file()

try:
    # pydantic v1 used BaseSettings, newer pydantic v2 separates settings.
    # Support both: prefer pydantic when available, otherwise fallback to env lookups.
    from pydantic import BaseSettings, AnyHttpUrl


    class Settings(BaseSettings):
        # Data paths
        DATA_DIR: str = "data"
        SWEETVIZ_OUTPUT: str = "sweetviz_extracted"
        STORAGE_BASE_PATH: str = "./storage"

        # Backend API settings
        BACKEND_URL: Optional[str] = None
        PIPELINE_API_TOKEN: str = ""

        # Ollama / LLM settings
        OLLAMA_URL: Optional[AnyHttpUrl] = None
        OLLAMA_MODEL: str = "qwen2.5:14b-instruct"
        GLOBAL_LLM_ENABLED: bool = True
        GLOBAL_LLM_TEMPERATURE: float = 0.2
        GLOBAL_LLM_TOP_P: float = 0.9
        GLOBAL_LLM_NUM_PREDICT: int = 1800
        GLOBAL_LLM_TIMEOUT_SECONDS: int = 420

        # Runtime
        ENV: str = "development"
        LOG_LEVEL: str = "INFO"

        # Parallelism knobs
        SWEETVIZ_MAX_WORKERS: int = 4
        FORMATTER_MAX_WORKERS: int = 6
        LLM_ANALYZER_MAX_WORKERS: int = 2
        DASHBOARD_MAX_WORKERS: int = 6
        GLOBAL_PAYLOAD_MAX_WORKERS: int = 6

        class Config:
            env_file = ".env"
            env_file_encoding = "utf-8"


    settings = Settings()
except Exception:
    class Settings:
        DATA_DIR = os.getenv("DATA_DIR", "data")
        SWEETVIZ_OUTPUT = os.getenv("SWEETVIZ_OUTPUT", "sweetviz_extracted")
        STORAGE_BASE_PATH = os.getenv("STORAGE_BASE_PATH", "./storage")
        BACKEND_URL = os.getenv("BACKEND_URL") or None
        PIPELINE_API_TOKEN = os.getenv("PIPELINE_API_TOKEN", "")
        OLLAMA_URL = os.getenv("OLLAMA_URL") or None
        OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen2.5:14b-instruct")
        GLOBAL_LLM_ENABLED = os.getenv("GLOBAL_LLM_ENABLED", "true").strip().lower() in ("1", "true", "yes", "on")
        try:
            GLOBAL_LLM_TEMPERATURE = float(os.getenv("GLOBAL_LLM_TEMPERATURE", "0.2"))
        except Exception:
            GLOBAL_LLM_TEMPERATURE = 0.2
        try:
            GLOBAL_LLM_TOP_P = float(os.getenv("GLOBAL_LLM_TOP_P", "0.9"))
        except Exception:
            GLOBAL_LLM_TOP_P = 0.9
        try:
            GLOBAL_LLM_NUM_PREDICT = int(os.getenv("GLOBAL_LLM_NUM_PREDICT", "1800"))
        except Exception:
            GLOBAL_LLM_NUM_PREDICT = 1800
        try:
            GLOBAL_LLM_TIMEOUT_SECONDS = int(os.getenv("GLOBAL_LLM_TIMEOUT_SECONDS", "420"))
        except Exception:
            GLOBAL_LLM_TIMEOUT_SECONDS = 420
        ENV = os.getenv("ENV", "development")
        LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")
        try:
            SWEETVIZ_MAX_WORKERS = int(os.getenv("SWEETVIZ_MAX_WORKERS", "4"))
        except Exception:
            SWEETVIZ_MAX_WORKERS = 4
        try:
            FORMATTER_MAX_WORKERS = int(os.getenv("FORMATTER_MAX_WORKERS", "6"))
        except Exception:
            FORMATTER_MAX_WORKERS = 6
        try:
            LLM_ANALYZER_MAX_WORKERS = int(os.getenv("LLM_ANALYZER_MAX_WORKERS", "2"))
        except Exception:
            LLM_ANALYZER_MAX_WORKERS = 2
        try:
            DASHBOARD_MAX_WORKERS = int(os.getenv("DASHBOARD_MAX_WORKERS", "6"))
        except Exception:
            DASHBOARD_MAX_WORKERS = 6
        try:
            GLOBAL_PAYLOAD_MAX_WORKERS = int(os.getenv("GLOBAL_PAYLOAD_MAX_WORKERS", "6"))
        except Exception:
            GLOBAL_PAYLOAD_MAX_WORKERS = 6

    settings = Settings()
