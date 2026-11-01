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

    /** The role's name as people read it: the invite email, and anywhere else a role is named. */
    public String label() {
        return switch (this) {
            case STAFF -> "Staff";
            case SHOP_MANAGER -> "Shop manager";
            case GROUP_ADMIN -> "Group admin";
        };
    }

    /**
     * What the role can do, in one or two plain sentences (SW-TND-02, swarm epic #964), stated from
     * the tiers above as they are enforced: STAFF reads the shop and moves orders through their
     * states but writes no catalogue; SHOP_MANAGER has full control of a granted shop but no staff
     * management and no shop create or delete; GROUP_ADMIN has every shop, shop create and delete,
     * and staff management. Kept beside the tiers so a change to one is a change to the other.
     */
    public String capabilities() {
        return switch (this) {
            case STAFF -> "As Staff you can see the shop's orders and move them along as they are prepared. "
                    + "You can't change products or prices.";
            case SHOP_MANAGER -> "As a Shop manager you can run the shop: its products and prices, its orders "
                    + "and its settings. You can't invite people or add or remove shops.";
            case GROUP_ADMIN -> "As a Group admin you can run every shop in the business, add and remove shops, "
                    + "and invite people and choose what they can do.";
        };
    }

    /**
     * True when this role is at least {@code minRole} in the tier
     * (e.g. GROUP_ADMIN {@code satisfies} SHOP_MANAGER).
     */
    public boolean satisfies(ShopRole minRole) {
        return this.rank >= minRole.rank;
    }
}
