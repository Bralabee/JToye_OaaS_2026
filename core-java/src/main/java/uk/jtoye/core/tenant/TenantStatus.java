package uk.jtoye.core.tenant;

/**
 * Tenant lifecycle status (issue #102 [P2-11]). Persisted as
 * {@code @Enumerated(EnumType.STRING)} → the {@code status} VARCHAR + CHECK in V48.
 *
 * <p>Transitions (enforced by {@link TenantLifecycleService}):
 * {@code ACTIVE ⇄ SUSPENDED}, {@code ACTIVE|SUSPENDED → OFFBOARDED}.
 * {@code OFFBOARDED} is terminal — a churned vendor is never silently revived.
 */
public enum TenantStatus {
    /** Normal operation — API traffic served. */
    ACTIVE,

    /** Temporarily blocked (e.g. non-payment); reversible via reactivate. */
    SUSPENDED,

    /** Churned/terminated — terminal; API traffic permanently rejected. */
    OFFBOARDED
}
