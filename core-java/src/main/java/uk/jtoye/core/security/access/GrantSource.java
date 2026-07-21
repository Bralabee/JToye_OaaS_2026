package uk.jtoye.core.security.access;

/**
 * The provenance of a {@code shop_staff} grant (V57, Phase 23 CR-07/WR-09).
 *
 * <p>Persisted as {@code @Enumerated(EnumType.STRING)} into the {@code grant_source}
 * VARCHAR(16) + CHECK on {@code shop_staff}. Constant names MUST match the V57 CHECK
 * strings exactly ({@code 'JIT','OPERATOR'}).
 *
 * <p>This distinction is what makes the strict-scoping switch (D-12) reachable: a
 * tenant that has run on the default (strict-scoping OFF) accumulates {@link #JIT}
 * tenant-wide GROUP_ADMIN rows for every day-one user, and enabling strict-scoping
 * de-honours those while leaving every {@link #OPERATOR} grant untouched. See
 * {@code ShopAccessService#isGroupAdminForUser}.
 */
public enum GrantSource {

    /**
     * Auto-created by {@code ShopAccessService.onRequest} (D-04 JIT lazy-provision) on a
     * user's first write request while strict-scoping is OFF — never by a human. A
     * tenant-wide GROUP_ADMIN row with this provenance is DE-HONOURED under strict-scoping
     * ON (the tenant's oldest such row is kept as the bootstrap admin to prevent lockout).
     */
    JIT,

    /**
     * Deliberately created by a GROUP_ADMIN through {@code /api/v1/staff/grant}
     * ({@code StaffManagementService}). Honoured unconditionally, including under
     * strict-scoping ON.
     */
    OPERATOR
}
