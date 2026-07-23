package uk.jtoye.core.security.access;

/**
 * In-tenant shop-role tier for vendor-scoped access (Phase 23, D-03).
 *
 * <p>Persisted as {@code @Enumerated(EnumType.STRING)} → the {@code role} VARCHAR(16)
 * + CHECK in V52. Constant names MUST match the V52 CHECK strings exactly
 * ({@code 'GROUP_ADMIN','SHOP_MANAGER','STAFF'}).
 *
 * <p>Each role carries a {@link #rank()} so the enforcement gate
 * {@code shopAccessService.require(shopId, minRole)} (23-02) can express
 * "at least this role" as a single comparison:
 * {@code callerRole.satisfies(minRole)}. Higher rank = broader authority:
 * GROUP_ADMIN &gt; SHOP_MANAGER &gt; STAFF.
 *
 * <ul>
 *   <li>{@code GROUP_ADMIN} — all shops incl. shop create/delete + staff mgmt.</li>
 *   <li>{@code SHOP_MANAGER} — full CRUD on a granted shop; no staff mgmt, no shop create/delete.</li>
 *   <li>{@code STAFF} — operational read + order state transitions on a granted shop; no catalogue writes.</li>
 * </ul>
 */
public enum ShopRole {
    STAFF(0),
    SHOP_MANAGER(1),
    GROUP_ADMIN(2);

    private final int rank;

    ShopRole(int rank) {
        this.rank = rank;
    }

    /** Ordering weight — higher is broader authority (GROUP_ADMIN highest). */
    public int rank() {
        return rank;
    }

    /**
     * True when this role is at least {@code minRole} in the tier
     * (e.g. GROUP_ADMIN {@code satisfies} SHOP_MANAGER).
     */
    public boolean satisfies(ShopRole minRole) {
        return this.rank >= minRole.rank;
    }
}
