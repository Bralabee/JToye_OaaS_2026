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
 *
 * <p>Cached per-user via {@code TenantAwareCacheKeyGenerator} (key
 * {@code tenant:{tid}:resolveMembership:{sub}}) and evicted on grant/revoke
 * (D-05, immediate revocation). A Java record so it is trivially value-equal and
 * JSON-serialisable for the Redis cache.
 */
public record Membership(boolean isGroupAdmin, boolean groupAdminFromJit, Map<UUID, ShopRole> perShopRole) {
}
