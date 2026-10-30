package wevioo.backend.exception;

/** A requested dataset, version, table or pipeline run does not exist (HTTP 404). */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
