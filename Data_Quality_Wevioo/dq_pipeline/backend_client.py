"""
Backend REST client for streaming pipeline results.

Sends table-level and global-level analysis results to the backend API
as they complete, enabling progressive UI updates on the frontend.
"""

import requests
import time
import os
from typing import Optional, Dict, Any
from pathlib import Path
from dq_pipeline.config.settings import settings
from dq_pipeline.models import TableAnalysisResult, GlobalAnalysisResult


class BackendClient:
    """Client for communicating with Spring Boot backend."""

    def __init__(self, backend_url: Optional[str] = None, timeout_seconds: int = 30, max_retries: int = 3):
        """
        Initialize backend client.
        
        Args:
            backend_url: Backend API base URL (e.g., http://localhost:8080).
                        Defaults to settings.BACKEND_URL if not provided.
            timeout_seconds: HTTP request timeout in seconds.
            max_retries: Number of times to retry failed requests.
        """
        self.backend_url = (backend_url or settings.BACKEND_URL or "http://localhost:8080").rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.max_retries = max_retries
        self.pipeline_api_token = os.getenv("PIPELINE_API_TOKEN", getattr(settings, "PIPELINE_API_TOKEN", ""))
        self.session = requests.Session()

    def post_table_analysis(
        self,
        pipeline_run_id: str,
        result: TableAnalysisResult,
        debug: bool = False,
    ) -> bool:
        """
        POST table-level analysis result to backend.
        
        Endpoint: POST /api/pipeline/{pipeline_run_id}/table-analysis
        
        Args:
            pipeline_run_id: Unique identifier for this pipeline execution.
            result: TableAnalysisResult object to send.
            debug: If True, print debug information.
        
        Returns:
            True if successful, False otherwise.
        """
        url = f"{self.backend_url}/api/pipeline/{pipeline_run_id}/table-analysis"
        payload = result.to_dict()
        
        return self._post_with_retry(url, payload, f"table-analysis:{result.table_name}", debug)

    def post_global_analysis(
        self,
        pipeline_run_id: str,
        result: GlobalAnalysisResult,
        debug: bool = False,
    ) -> bool:
        """
        POST global (portfolio-level) analysis result to backend.
        
        Endpoint: POST /api/pipeline/{pipeline_run_id}/global-analysis
        
        Args:
            pipeline_run_id: Unique identifier for this pipeline execution.
            result: GlobalAnalysisResult object to send.
            debug: If True, print debug information.
        
        Returns:
            True if successful, False otherwise.
        """
        url = f"{self.backend_url}/api/pipeline/{pipeline_run_id}/global-analysis"
        payload = result.to_dict()
        
        return self._post_with_retry(url, payload, "global-analysis", debug)

    def post_pipeline_run_started(
        self,
        pipeline_run_id: str,
        dataset_id: str,
        version_id: str,
        debug: bool = False,
    ) -> bool:
        """
        Notify backend that pipeline has started.
        
        Endpoint: POST /api/pipeline/{pipeline_run_id}/start
        
        Args:
            pipeline_run_id: Unique identifier for this pipeline execution.
            dataset_id: Dataset being processed.
            version_id: Version of the dataset.
            debug: If True, print debug information.
        
        Returns:
            True if successful, False otherwise.
        """
        url = f"{self.backend_url}/api/pipeline/{pipeline_run_id}/start"
        payload = {
            "pipeline_run_id": pipeline_run_id,
            "dataset_id": dataset_id,
            "version_id": version_id,
            "started_at": time.time(),
        }
        
        return self._post_with_retry(url, payload, "pipeline-started", debug)

    def post_pipeline_run_completed(
        self,
        pipeline_run_id: str,
        status: str = "success",
        error_message: Optional[str] = None,
        debug: bool = False,
    ) -> bool:
        """
        Notify backend that pipeline has completed.
        
        Endpoint: POST /api/pipeline/{pipeline_run_id}/complete
        
        Args:
            pipeline_run_id: Unique identifier for this pipeline execution.
            status: 'success', 'failed', or 'partial'.
            error_message: Error details if status is 'failed'.
            debug: If True, print debug information.
        
        Returns:
            True if successful, False otherwise.
        """
        url = f"{self.backend_url}/api/pipeline/{pipeline_run_id}/complete"
        payload = {
            "pipeline_run_id": pipeline_run_id,
            "status": status,
            "error_message": error_message,
            "completed_at": time.time(),
        }
        
        return self._post_with_retry(url, payload, "pipeline-completed", debug)

    def _post_with_retry(
        self,
        url: str,
        payload: Dict[str, Any],
        label: str,
        debug: bool = False,
    ) -> bool:
        """
        POST request with automatic retry logic.
        
        Args:
            url: Full URL to POST to.
            payload: JSON payload to send.
            label: Human-readable label for debugging.
            debug: If True, print debug information.
        
        Returns:
            True if successful, False otherwise.
        """
        headers = {"Content-Type": "application/json"}
        if self.pipeline_api_token:
            headers["X-Pipeline-Token"] = self.pipeline_api_token
        
        for attempt in range(1, self.max_retries + 1):
            try:
                if debug:
                    print(f"[DEBUG] POST {label}: attempt {attempt}/{self.max_retries}")
                
                response = self.session.post(
                    url,
                    json=payload,
                    headers=headers,
                    timeout=self.timeout_seconds,
                )
                
                if response.status_code in (200, 201, 202):
                    if debug:
                        print(f"[OK] {label} posted successfully (status {response.status_code})")
                    return True
                else:
                    error_detail = response.text[:200] if response.text else "(no detail)"
                    print(f"[WARN] {label} HTTP {response.status_code}: {error_detail}")
                    
                    # Retry on server errors (5xx)
                    if response.status_code >= 500 and attempt < self.max_retries:
                        time.sleep(2 ** attempt)  # Exponential backoff
                        continue
                    return False
                    
            except requests.exceptions.Timeout:
                print(f"[WARN] {label} request timeout (attempt {attempt}/{self.max_retries})")
                if attempt < self.max_retries:
                    time.sleep(2 ** attempt)
                    continue
                return False
                
            except requests.exceptions.ConnectionError as e:
                print(f"[WARN] {label} connection error: {e}")
                if attempt < self.max_retries:
                    time.sleep(2 ** attempt)
                    continue
                return False
                
            except Exception as e:
                print(f"[ERROR] {label} unexpected error: {e}")
                return False
        
        return False

    def health_check(self, debug: bool = False) -> bool:
        """
        Check if backend is reachable.
        
        Endpoint: GET /health
        
        Args:
            debug: If True, print debug information.
        
        Returns:
            True if backend is reachable, False otherwise.
        """
        url = f"{self.backend_url}/health"
        try:
            response = self.session.get(url, timeout=5)
            is_healthy = response.status_code == 200
            if debug:
                status = "✓ healthy" if is_healthy else f"✗ HTTP {response.status_code}"
                print(f"[DEBUG] Backend health check: {status}")
            return is_healthy
        except Exception as e:
            if debug:
                print(f"[DEBUG] Backend unreachable: {e}")
            return False

    def close(self):
        """Close the session."""
        self.session.close()

    def __enter__(self):
        """Context manager support."""
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        """Context manager cleanup."""
        self.close()


# Convenience singleton-like functions
_client: Optional[BackendClient] = None


def get_backend_client(backend_url: Optional[str] = None) -> BackendClient:
    """Get or create a backend client instance."""
    global _client
    if _client is None:
        _client = BackendClient(backend_url=backend_url)
    return _client


def close_backend_client():
    """Close the singleton backend client."""
    global _client
    if _client is not None:
        _client.close()
        _client = None
