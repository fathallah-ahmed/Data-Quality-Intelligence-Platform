package wevioo.backend.exception;

/** The request conflicts with the current state, e.g. re-running an active version (HTTP 409). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
