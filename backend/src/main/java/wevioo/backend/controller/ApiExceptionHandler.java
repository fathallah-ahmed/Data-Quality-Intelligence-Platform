package wevioo.backend.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import wevioo.backend.exception.ConflictException;
import wevioo.backend.exception.NotFoundException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(ConflictException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(wevioo.backend.correction.OllamaClient.LlmUnavailableException.class)
    public ResponseEntity<Map<String, String>> modelUnavailable(wevioo.backend.correction.OllamaClient.LlmUnavailableException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
    }

    @ExceptionHandler(wevioo.backend.graph.GraphStore.GraphUnavailableException.class)
    public ResponseEntity<Map<String, String>> graphUnavailable(wevioo.backend.graph.GraphStore.GraphUnavailableException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
    }

    @ExceptionHandler(wevioo.backend.qa.QaClient.QaUnavailableException.class)
    public ResponseEntity<Map<String, String>> questionServiceUnavailable(wevioo.backend.qa.QaClient.QaUnavailableException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "The upload is larger than the server limit.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> responseStatus(ResponseStatusException exception) {
        String reason = exception.getReason() != null ? exception.getReason() : exception.getStatusCode().toString();
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("error", reason));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> otherErrors(Exception exception) {
        // Spring MVC exceptions (unknown route, wrong method, missing parameter...) keep their own status.
        if (exception instanceof ErrorResponse errorResponse && !errorResponse.getStatusCode().is5xxServerError()) {
            String detail = errorResponse.getBody().getDetail();
            return ResponseEntity.status(errorResponse.getStatusCode())
                .body(Map.of("error", detail != null ? detail : errorResponse.getStatusCode().toString()));
        }
        log.error("Unhandled API error", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error. Check the backend logs for details.");
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
