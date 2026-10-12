package uk.jtoye.core.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How many orders one bulk cancel may name (Phase 37-29, D-18): {@code jtoye.orders.bulk-cancel.max-ids},
 * default 100. Configuration, never a literal (D-18), held by its own bean in the {@link OrderLimits}
 * shape so the value is bound and validated once at startup: a value below 1 refuses to start, naming
 * the key, rather than quietly refusing every bulk cancel.
 *
 * <p>Why a bound at all: each id becomes its own transaction with its own cancel transition, outbox row
 * and customer email, so an unbounded list is an unbounded unit of work on one request thread. 100 is
 * more than any kitchen board page shows and more than the persona run's flood (UXT-041) ever reached.
 */
@Component
public class BulkCancelLimit {

    static final String MAX_IDS_KEY = "jtoye.orders.bulk-cancel.max-ids";

    private final int maxIds;

    public BulkCancelLimit(@Value("${" + MAX_IDS_KEY + ":100}") int maxIds) {
        if (maxIds < 1) {
            throw new IllegalStateException(MAX_IDS_KEY + " must be at least 1, but is " + maxIds);
        }
        this.maxIds = maxIds;
    }

    /** The most ids one bulk cancel may name. */
    public int maxIds() {
        return maxIds;
    }
}
