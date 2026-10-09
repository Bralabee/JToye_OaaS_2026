package uk.jtoye.core.security.access;

import java.util.Map;
import java.util.UUID;

/**
 * A resolved snapshot of one user's shop-role grants within a tenant — the
 * cached source of every enforcement decision in {@code ShopAccessService}
 * (Phase 23, VSA-02 / D-05).
 *
 * @param isGroupAdmin true when the user holds a tenant-wide GROUP_ADMIN grant
 *                     (a {@code shop_staff} row with {@code shop_id} NULL and
 *                     role GROUP_ADMIN). A GROUP_ADMIN may act on every shop and
 *                     manage staff; realm-{@code admin} is treated as an implicit
 *                     GROUP_ADMIN separately (read from the authority, not stored
 *                     here).
 * @param groupAdminFromJit meaningful ONLY when {@code isGroupAdmin} is true: whether
 *                     that tenant-wide GROUP_ADMIN row was auto-provisioned
 *                     ({@link GrantSource#JIT}, D-04) rather than granted by an operator
 *                     ({@link GrantSource#OPERATOR}). The V52 unique index guarantees at
 *                     most one tenant-wide row per user, so this is unambiguous. It carries
 *                     the raw fact the strict-scoping DECISION needs (CR-07) WITHOUT baking
 *                     the config-dependent policy into the cached snapshot — the policy is
 *                     applied in {@code ShopAccessService.isGroupAdminForUser}, outside the
 *                     cache, so a strict-scoping flag change is never served stale.
 * @param perShopRole  the caller's role on each specific granted shop
 *                     ({@code shop_id} → role). Empty for a fully-ungranted user.
 *                     Immutable.
 * @param tenantWideRole (37-05, D-07/D-23) the role a NULL-shop STAFF or SHOP_MANAGER row
 *                     confers on EVERY shop of the tenant ("a role plus all shops"); null when the
 *                     user holds none. A NULL-shop GROUP_ADMIN row is NOT recorded here — it keeps
 *                     its own meaning ({@code isGroupAdmin}) and the JIT de-honouring rule. Capped at
 *                     the row's own rank (a tenant-wide STAFF is STAFF everywhere, never more), and
 *                     applied only to shops of the caller's own tenant. A membership cached before
 *                     this component existed deserialises with it null, which is the pre-37-05
 *                     meaning (no tenant-wide shop role).
 *
 * <p>Cached per-user via {@code TenantAwareCacheKeyGenerator} (key
 * {@code tenant:{tid}:resolveMembership:{sub}}) and evicted on grant/revoke
 * (D-05, immediate revocation). A Java record so it is trivially value-equal and
 * JSON-serialisable for the Redis cache.
 */
public record Membership(boolean isGroupAdmin, boolean groupAdminFromJit, Map<UUID, ShopRole> perShopRole,
                         ShopRole tenantWideRole) {

    /** A membership with no tenant-wide shop role (every pre-37-05 shape). */
    public Membership(boolean isGroupAdmin, boolean groupAdminFromJit, Map<UUID, ShopRole> perShopRole) {
        this(isGroupAdmin, groupAdminFromJit, perShopRole, null);
    }

    /**
     * The role this membership confers on {@code shopId}: the higher of the specific grant on that
     * shop and the tenant-wide role, or null when neither exists. The caller must already have
     * established that {@code shopId} belongs to the caller's tenant before honouring a role that
     * came from {@link #tenantWideRole()} alone. GROUP_ADMIN is not considered here.
     */
    public ShopRole roleOn(UUID shopId) {
        ShopRole specific = shopId == null ? null : perShopRole.get(shopId);
        if (specific == null) {
            return tenantWideRole;
        }
        if (tenantWideRole == null) {
            return specific;
        }
        return specific.rank() >= tenantWideRole.rank() ? specific : tenantWideRole;
    }
}
