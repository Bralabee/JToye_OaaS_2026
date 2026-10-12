package uk.jtoye.core.exception;

/**
 * A bulk cancel named more order ids than {@code jtoye.orders.bulk-cancel.max-ids} allows (Phase 37-29,
 * D-18). Rendered as a 400 {@code https://jtoye.uk/errors/bulk-cancel-too-large} with the count and the
 * limit as typed properties, so a dashboard can split the selection and an agent can branch without
 * parsing the sentence.
 *
 * <p>400, not 422: the request as a whole is outside the contract (too many ids), before any id is looked
 * at. Nothing is written — the check runs before the first per-order transaction.
 */
public class BulkCancelTooLargeException extends RuntimeException {

    private final int count;
    private final int limit;

    public BulkCancelTooLargeException(int count, int limit) {
        super("A bulk cancel can name at most " + limit + " orders; this request names " + count + ".");
        this.count = count;
        this.limit = limit;
    }

    public int getCount() {
        return count;
    }

    public int getLimit() {
        return limit;
    }
}
