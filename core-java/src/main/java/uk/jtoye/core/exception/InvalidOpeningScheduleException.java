package uk.jtoye.core.exception;

import java.util.Map;

/**
 * Thrown when a vendor saves an opening schedule that cannot be a statement of when the shop is open
 * (Phase 37-20, D-15): more than {@code OpeningSchedule.MAX_WINDOWS_PER_DAY} windows on a day, two windows
 * that overlap (within a day or across midnight), a window with a missing time, or a key that is not
 * {@code mon}..{@code sun}.
 *
 * <p>Maps to HTTP 400 via {@code GlobalExceptionHandler.handleInvalidOpeningSchedule}, with the same
 * {@code https://jtoye.uk/errors/validation} type and {@code errors} shape as bean validation, keyed
 * {@code openingSchedule.<day>}, so the dashboard puts each message on the day it is about. Nothing is saved.
 */
public class InvalidOpeningScheduleException extends RuntimeException {

    private final Map<String, String> errors;

    public InvalidOpeningScheduleException(Map<String, String> errors) {
        super("Opening schedule is invalid: " + errors);
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
