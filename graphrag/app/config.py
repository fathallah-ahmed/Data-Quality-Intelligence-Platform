"""Settings, read from environment variables (the same names the backend and the worker use)."""
import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    neo4j_uri: str
    neo4j_user: str
    neo4j_password: str
    ollama_url: str
    ollama_model: str
    api_token: str
    query_timeout_seconds: float

    @staticmethod
    def from_env() -> "Settings":
        return Settings(
            neo4j_uri=os.getenv("NEO4J_URI", "bolt://localhost:7687"),
            neo4j_user=os.getenv("NEO4J_USER", "neo4j"),
            # Development defaults, like the rest of the project: set real values before sharing an instance.
            neo4j_password=os.getenv("NEO4J_PASSWORD", "dq-neo4j-dev"),
            ollama_url=os.getenv("OLLAMA_URL", "http://localhost:11434"),
            ollama_model=os.getenv("OLLAMA_MODEL", "").strip(),
            api_token=os.getenv("QA_API_TOKEN", "dev-qa-token"),
            query_timeout_seconds=float(os.getenv("QA_QUERY_TIMEOUT_SECONDS", "15")),
        )
