package uk.jtoye.core.exception;

import java.util.Map;

/**
 * Thrown when a request body is well-formed but one or more of its fields are refused by a rule bean
 * validation cannot express: a field required only in combination with another, two fields that
 * contradict each other, or a property the endpoint does not accept at all.
 *
 * <p>First users (Phase 37-39, RWO-016 / UXT-016): {@code trackInventory: true} without a
 * {@code quantityInStock}, {@code trackInventory: false} with one, and a property other than
 * {@code available}, {@code quantityInStock} or {@code trackInventory} on {@code PATCH /products/{id}}.
 *
 * <p>Maps to HTTP 400 via {@code GlobalExceptionHandler.handleInvalidRequestFields}, with the same
 * {@code https://jtoye.uk/errors/validation} type, title and {@code errors} map (field name to message) as
 * bean validation, so a client handles both alike and can name the field. Nothing is saved.
 */
public class InvalidRequestFieldsException extends RuntimeException {

    private final Map<String, String> errors;

    public InvalidRequestFieldsException(Map<String, String> errors) {
        super("Invalid request fields: " + errors);
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
