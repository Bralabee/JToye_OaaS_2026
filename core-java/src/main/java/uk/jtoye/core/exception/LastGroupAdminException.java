package uk.jtoye.core.exception;

/**
 * Thrown when a staff-management write (revoke or downgrade) would remove the
 * FINAL remaining {@code GROUP_ADMIN} grant in a tenant (Phase 23, VSA-04 /
 * D-11). A tenant must always retain at least one GROUP_ADMIN who can manage
 * staff; the realm-{@code admin} implicit-GROUP_ADMIN bridge is a recovery
 * backstop, but a non-realm-admin group admin could otherwise lock the tenant
 * out of staff management.
 *
 * <p>Maps to HTTP 409 Conflict via
 * {@code GlobalExceptionHandler.handleLastGroupAdmin} with the stable type
 * {@code https://jtoye.uk/errors/last-group-admin} (mirrors the 409
 * {@code IdempotencyConflictException} pattern). Wired here in 23-02 as the typed
 * contract; thrown by the staff backend in 23-04.
 */
public class LastGroupAdminException extends RuntimeException {
    public LastGroupAdminException(String message) {
        super(message);
    }
}
