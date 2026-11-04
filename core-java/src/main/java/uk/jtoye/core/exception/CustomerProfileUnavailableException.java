package uk.jtoye.core.exception;

/**
 * Thrown when a customer's display name cannot be changed because the sign-in provider's admin seam is
 * switched off in this runtime, or refused/failed the change -> HTTP 503 (SW-JRD-07, owner ruling
 * 2026-11-04). The message is generic: the provider's error text is never echoed.
 */
public class CustomerProfileUnavailableException extends RuntimeException {

    public CustomerProfileUnavailableException(Throwable cause) {
        super("Your name can't be changed right now. Please try again later.", cause);
    }
}
