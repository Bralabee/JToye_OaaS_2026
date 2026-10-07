package uk.jtoye.core.exception;

/**
 * #861 (D-17): the production date given for a PPDS label cannot be true. Either it is after
 * today in UK time (food cannot have been made tomorrow), or the durability date it implies
 * (production date + shelf life) has already passed, so the label would tell a customer food is
 * fine to eat after its use-by.
 *
 * <p>Maps to HTTP 422 (type {@code https://jtoye.uk/errors/invalid-production-date}, member
 * {@code field: "productionDate"}) via {@code GlobalExceptionHandler}. No label is produced.
 */
public class InvalidProductionDateException extends RuntimeException {

    /** The request parameter this error is about, so a client can branch without parsing prose. */
    public static final String FIELD = "productionDate";

    public InvalidProductionDateException(String message) {
        super(message);
    }
}
