package uk.jtoye.core.exception;

/**
 * Exception thrown when attempting an invalid state transition.
 * Results in HTTP 400 Bad Request response.
 */
public class InvalidStateTransitionException extends RuntimeException {
    public InvalidStateTransitionException(String message) {
        super(message);
    }

    public InvalidStateTransitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
