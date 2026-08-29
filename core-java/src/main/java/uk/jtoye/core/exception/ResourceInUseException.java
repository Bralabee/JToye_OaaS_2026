package uk.jtoye.core.exception;

/**
 * QA-council cluster P2 (API-2/FE-4) — a DELETE was rejected because another row still
 * references the target through a foreign key (SQLState 23503 on the DELETE statement
 * itself, e.g. a product still line-itemed on an {@code order_items} row via
 * {@code fk_order_items_product}, or a customer still named on an {@code orders} row via
 * {@code fk_orders_customer}). 409 Conflict: the request is well-formed and the caller is
 * authorized — the resource simply cannot be removed while something else depends on it.
 *
 * <p>Deliberately scoped to the DELETE direction only. An INSERT/UPDATE that names a
 * nonexistent parent (a bad {@code shopId}, a bad {@code productId}) is a different failure
 * mode, already rejected at the service layer via an explicit {@code findById().orElseThrow()}
 * existence check before any write reaches the database — that path is untouched here.
 *
 * <p>Carries the offending {@code constraintName} (where the driver names one) so a machine
 * client can identify which relationship blocked the delete without parsing prose (D-06 /
 * agent-readiness), mirroring {@code MediaRedriveRejectedException}'s typed-reason contract.
 */
public class ResourceInUseException extends RuntimeException {

    private final String constraintName;

    public ResourceInUseException(String message, String constraintName) {
        super(message);
        this.constraintName = constraintName;
    }

    /** The FK constraint name the database reported, or {@code null} if the driver omitted one. */
    public String getConstraintName() {
        return constraintName;
    }
}
