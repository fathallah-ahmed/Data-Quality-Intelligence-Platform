"""Pipeline settings, read from environment variables (and a local ``.env`` for CLI use).

Docker Compose passes the same variables to the Airflow containers, so this module
has no dependency on pydantic.
"""
import os
from pathlib import Path
from typing import Optional

# Single source of truth for the default model; keep in sync with .env.example and docker-compose.yml.
DEFAULT_OLLAMA_MODEL = "qwen2.5:14b-instruct"


def _load_env_file(path: str = ".env") -> None:
    p = Path(path)
    if not p.exists():
        return
    for raw in p.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def _env_str(name: str, default: str = "") -> str:
    return os.getenv(name, default)


def _env_optional(name: str) -> Optional[str]:
    value = os.getenv(name, "").strip()
    return value or None


def _env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None or value.strip() == "":
        return default
    return value.strip().lower() in ("1", "true", "yes", "on")


def _env_number(name: str, default, cast):
    try:
        return cast(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        return default


_load_env_file()


class Settings:
    def __init__(self) -> None:
        self.STORAGE_BASE_PATH = _env_str("STORAGE_BASE_PATH", "./storage")

        # Backend callbacks
        self.BACKEND_URL = _env_optional("BACKEND_URL")
        self.PIPELINE_API_TOKEN = _env_str("PIPELINE_API_TOKEN", "")

        # Ollama / LLM
        self.OLLAMA_URL = _env_optional("OLLAMA_URL")
        self.OLLAMA_MODEL = _env_str("OLLAMA_MODEL", DEFAULT_OLLAMA_MODEL) or DEFAULT_OLLAMA_MODEL
        self.TABLE_LLM_ENABLED = _env_bool("TABLE_LLM_ENABLED", True)
        self.GLOBAL_LLM_ENABLED = _env_bool("GLOBAL_LLM_ENABLED", True)
        self.GLOBAL_LLM_TEMPERATURE = _env_number("GLOBAL_LLM_TEMPERATURE", 0.2, float)
        self.GLOBAL_LLM_TOP_P = _env_number("GLOBAL_LLM_TOP_P", 0.9, float)
        self.GLOBAL_LLM_NUM_PREDICT = _env_number("GLOBAL_LLM_NUM_PREDICT", 1800, int)
        self.GLOBAL_LLM_TIMEOUT_SECONDS = _env_number("GLOBAL_LLM_TIMEOUT_SECONDS", 420, int)

        # Runtime
        self.ENV = _env_str("ENV", "development")
        self.LOG_LEVEL = _env_str("LOG_LEVEL", "INFO")


settings = Settings()
