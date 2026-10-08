package uk.jtoye.core.security.access;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One person's effective access within the caller's tenant, as {@link ShopAccessService} decides it
 * (D-09, Phase 37-05). The Staff page renders this value; it never reconstructs access from raw
 * {@code shop_staff} rows (theme 2: what the page says must be what enforcement does).
 *
 * <p>Produced only by {@link ShopAccessService#effectiveAccessFor(UUID)}, which runs the same
 * {@code isGroupAdminForUser} + {@code resolveMembership} path that {@code require} and
 * {@code canAccessShop} decide with. Two rows can say GROUP_ADMIN and still read differently here:
 * under strict scoping a JIT-sourced tenant-wide GROUP_ADMIN is honoured only for the tenant's
 * oldest JIT admin ({@code bootstrapAdmin}), so a younger one reads {@link Level#NONE}.
 *
 * @param userId           the person (Keycloak {@code sub})
 * @param level            the strongest thing this person may do; see {@link Level}
 * @param bootstrapAdmin   true when the GROUP_ADMIN level comes only from the strict-scoping lockout
 *                         rule (the tenant's oldest JIT admin, kept because the tenant has no
 *                         OPERATOR admin) — a hint to grant an explicit admin
 * @param allShops         true when the access applies to every shop of the tenant, present and
 *                         future: GROUP_ADMIN, REALM_ADMIN, or a tenant-wide shop role
 * @param tenantWideRole   the role a tenant-wide (NULL-shop) STAFF or SHOP_MANAGER grant confers on
 *                         every shop; null when the person holds none
 * @param perShopRole      the role on each specifically granted shop
 * @param realmAdminSeenAt when this person was first seen signing in with the realm {@code admin}
 *                         role; null when never observed with it (the database cannot see Keycloak
 *                         roles, so this is an observation, not a live lookup)
 */
@Schema(description = "A person's effective access in this tenant, computed by the same decision "
        + "the API enforces with. Never derived from raw grant rows.")
public record EffectiveAccess(
        UUID userId,
        @Schema(description = "GROUP_ADMIN: manages the tenant and all shops. REALM_ADMIN: a platform "
                + "admin account (seen signing in with the realm admin role). SHOP_ROLES: a role on some "
                + "or all shops. NONE: signed in, no access.")
        Level level,
        @Schema(description = "GROUP_ADMIN only because the tenant has no operator-granted admin "
                + "and this is its oldest automatic admin (kept so the tenant cannot lock itself out).")
        boolean bootstrapAdmin,
        @Schema(description = "The access covers every shop of the tenant.")
        boolean allShops,
        @Schema(description = "Role conferred on every shop by a tenant-wide STAFF or SHOP_MANAGER "
                + "grant; null when none.", nullable = true)
        ShopRole tenantWideRole,
        @Schema(description = "Role per specifically granted shop id.")
        Map<UUID, ShopRole> perShopRole,
        @Schema(description = "First time this person was seen with the realm admin role; null when "
                + "never observed.", nullable = true)
        OffsetDateTime realmAdminSeenAt) {

    /** The strongest thing a person may do in the tenant. */
    public enum Level {
        /** Tenant-wide GROUP_ADMIN as the decision honours it (operator grant, or the bootstrap admin). */
        GROUP_ADMIN,
        /** A realm admin account: implicit GROUP_ADMIN whenever it signs in with that role. */
        REALM_ADMIN,
        /** A role on some shops, or on all shops through a tenant-wide STAFF/SHOP_MANAGER grant. */
        SHOP_ROLES,
        /** Signed in, no access. */
        NONE
    }
}
