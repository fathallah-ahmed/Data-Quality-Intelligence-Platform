"""The question-answering service: POST /ask, called by the Spring backend (never by the browser)."""
import hmac
import logging
from typing import Any, Callable, Dict, Optional

import neo4j
from fastapi import Depends, FastAPI, Header, HTTPException
from neo4j_graphrag.llm import OllamaLLM
from pydantic import BaseModel, Field

from app.config import Settings
from app.guard import SCOPE_PATTERN
from app.qa import Answerer, GraphUnavailable

log = logging.getLogger("qa")


class AskRequest(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    scope: str = Field(pattern=SCOPE_PATTERN.pattern)
    facts: Dict[str, Any] = Field(default_factory=dict)


class SubgraphRequest(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    cypher: str = Field(min_length=1, max_length=4000)
    scope: str = Field(pattern=SCOPE_PATTERN.pattern)
    facts: Dict[str, Any] = Field(default_factory=dict)


class ExpandRequest(BaseModel):
    scope: str = Field(pattern=SCOPE_PATTERN.pattern)
    nodeId: str = Field(min_length=1, max_length=200)


def default_answerer(settings: Settings) -> Callable[[], Answerer]:
    """One Neo4j driver for the whole service; the model client is cheap and is built per question."""
    driver = neo4j.GraphDatabase.driver(settings.neo4j_uri, auth=(settings.neo4j_user, settings.neo4j_password),
                                        connection_timeout=5, connection_acquisition_timeout=8)

    def build() -> Answerer:
        if not settings.ollama_model:
            raise HTTPException(503, "No language model is configured: set OLLAMA_MODEL for the question service.")
        llm = OllamaLLM(
            model_name=settings.ollama_model,
            model_params={"options": {"temperature": 0.1, "num_ctx": 16384, "num_predict": 1500}, "think": False},
            host=settings.ollama_url,
        )
        return Answerer(driver, llm, settings.query_timeout_seconds)

    return build


def create_app(settings: Optional[Settings] = None, answerer: Optional[Callable[[], Answerer]] = None) -> FastAPI:
    settings = settings or Settings.from_env()
    factory = answerer or default_answerer(settings)
    app = FastAPI(title="Data Quality question service")

    def authorised(x_qa_token: str = Header(default="")) -> None:
        if not hmac.compare_digest(x_qa_token.encode(), settings.api_token.encode()):
            raise HTTPException(401, "Invalid token")

    @app.get("/health")
    def health() -> Dict[str, Any]:
        return {"status": "ok", "model": settings.ollama_model or None}

    @app.post("/ask", dependencies=[Depends(authorised)])
    def ask(request: AskRequest) -> Dict[str, Any]:
        try:
            return factory().ask(request.question, request.scope, request.facts)
        except GraphUnavailable as error:
            raise HTTPException(503, str(error))
        except HTTPException:
            raise
        except ValueError as error:
            raise HTTPException(400, str(error))
        except Exception as error:  # the model is unreachable, slow or refused: say so, do not crash
            log.exception("Question failed")
            raise HTTPException(503, f"The language model could not answer: {error}")

    def guarded(call: Callable[[], Dict[str, Any]]) -> Dict[str, Any]:
        try:
            return call()
        except GraphUnavailable as error:
            raise HTTPException(503, str(error))
        except HTTPException:
            raise
        except ValueError as error:
            raise HTTPException(400, str(error))
        except Exception as error:
            log.exception("Graph request failed")
            raise HTTPException(503, f"The graph could not be read: {error}")

    @app.post("/subgraph", dependencies=[Depends(authorised)])
    def subgraph(request: SubgraphRequest) -> Dict[str, Any]:
        return guarded(lambda: factory().subgraph(request.question, request.cypher, request.scope, request.facts))

    @app.post("/expand", dependencies=[Depends(authorised)])
    def expand(request: ExpandRequest) -> Dict[str, Any]:
        return guarded(lambda: factory().expand(request.scope, request.nodeId))

    return app


# `uvicorn app.main:app`. Creating the driver does not connect: Neo4j is only reached when a question arrives.
app = create_app()
