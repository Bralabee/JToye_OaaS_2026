package uk.jtoye.core.security.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.security.TenantContext;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The single in-tenant authorization seam for vendor-scoped access (Phase 23,
 * VSA-02). Every shop-scoped write and read-scope call in 23-03 routes through
 * {@link #require(UUID, ShopRole)} / {@link #grantedShopIds()}; 23-04's staff
 * backend evicts through {@link #evictMembership(UUID)}.
 *
 * <p>It composes proven internal parts, NOT new mechanism:
 * <ul>
 *   <li><strong>Per-user membership cache</strong> — {@link #resolveMembership}
 *       is {@code @Cacheable("shopMembership")} keyed by the
 *       {@code TenantAwareCacheKeyGenerator} ({@code tenant:{tid}:resolveMembership:{sub}},
 *       already tenant-isolated), evictable per-user for immediate revocation
 *       (D-05).</li>
 *   <li><strong>Realm-admin bridge (D-03)</strong> — a caller carrying the
 *       existing {@code ROLE_admin} authority is an implicit GROUP_ADMIN; the
 *       authority is read, {@code realm_access} is NOT re-parsed.</li>
 *   <li><strong>JIT lazy-provision (D-04)</strong> — the first authenticated
 *       request from an ungranted tenant user auto-creates a tenant-wide
 *       GROUP_ADMIN grant for that user's OWN {@code sub} via the race-safe
 *       {@code INSERT ... ON CONFLICT DO NOTHING} (never a client-supplied
 *       role/shop), preserving day-one "everyone can do everything" while
 *       strict-scoping is OFF.</li>
 *   <li><strong>Throttled directory upsert (D-09)</strong> — records/refreshes
 *       the {@code user_directory} grant-target row, gated on a stale
 *       {@code last_seen} so it is never a write per request.</li>
 *   <li><strong>Strict-scoping switch (D-12)</strong> — OFF (default) = D-04
 *       auto-provision; ON = ungranted non-admins deny-by-default.</li>
 * </ul>
 *
 * <p><strong>Pitfall 4 (RESEARCH §5):</strong> the JIT provision + directory
 * upsert live HERE — inside this {@code @Transactional} service, entered on the
 * first {@code require()}/{@code requireGroupAdmin()}/{@code grantedShopIds()} of
 * the request, where {@code TenantSetLocalAspect} has already pinned the tenant
 * GUC — NOT in {@code JwtTenantFilter} (a raw filter has no transaction and no
 * GUC pinned yet).
 */
@Service
@Transactional
public class ShopAccessService {

    private static final Logger log = LoggerFactory.getLogger(ShopAccessService.class);

    /** The authority a realm-{@code admin} carries (KeycloakRealmRoleConverter: {@code admin -> ROLE_admin}). */
    private static final String REALM_ADMIN_AUTHORITY = "ROLE_admin";

    private final ShopStaffRepository shopStaffRepository;
    private final UserDirectoryRepository userDirectoryRepository;
    private final TenantCacheEvictor cacheEvictor;

    /** D-12: OFF preserves the day-one JIT auto-provision; ON denies ungranted non-admins. */
    @Value("${jtoye.access.strict-scoping:false}")
    private boolean strictScoping;

    /** D-09: throttle window — a returning user younger than this is a directory no-op. */
    @Value("${jtoye.access.directory-upsert-interval:PT1H}")
    private Duration directoryUpsertInterval;

    public ShopAccessService(ShopStaffRepository shopStaffRepository,
                             UserDirectoryRepository userDirectoryRepository,
                             TenantCacheEvictor cacheEvictor) {
        this.shopStaffRepository = shopStaffRepository;
        this.userDirectoryRepository = userDirectoryRepository;
        this.cacheEvictor = cacheEvictor;
    }

    // ---------------------------------------------------------------------
    // Enforcement API (the contract 23-03 / 23-04 code against)
    // ---------------------------------------------------------------------

    /**
     * Require at least {@code minRole} on {@code shopId}. A realm-admin or
     * tenant-wide GROUP_ADMIN passes unconditionally. Otherwise the caller's role
     * on {@code shopId} must {@link ShopRole#satisfies(ShopRole)} the floor.
     *
     * @throws ShopAccessDeniedException (distinct RFC 7807 403) if the caller
     *         lacks {@code minRole} on {@code shopId}.
     */
    public void require(UUID shopId, ShopRole minRole) {
        onRequest();
        if (isGroupAdmin()) {
            return;
        }
        Membership membership = resolveMembership(currentUserId());
        ShopRole role = membership.perShopRole().get(shopId);
        if (role == null || !role.satisfies(minRole)) {
            throw new ShopAccessDeniedException(shopId, minRole);
        }
    }

    /**
     * Require GROUP_ADMIN for a group-wide / shopless action (e.g. shop create,
     * staff management). Realm-admin passes (implicit GROUP_ADMIN).
     *
     * @throws ShopAccessDeniedException if the caller is neither realm-admin nor a
     *         tenant-wide GROUP_ADMIN.
     */
    public void requireGroupAdmin() {
        onRequest();
        if (isGroupAdmin()) {
            return;
        }
        throw new ShopAccessDeniedException(null, ShopRole.GROUP_ADMIN);
    }

    /** True for a realm-admin (D-03 bridge) or a tenant-wide GROUP_ADMIN grant. */
    public boolean isGroupAdmin() {
        if (isRealmAdmin()) {
            return true;
        }
        return resolveMembership(currentUserId()).isGroupAdmin();
    }

    /**
     * The specific shop ids the caller may read. For a GROUP_ADMIN this returns an
     * EMPTY set as an "unrestricted" sentinel — callers MUST short-circuit on
     * {@link #isGroupAdmin()} first (a GROUP_ADMIN reads all shops, so there is no
     * finite id set to filter by). For a scoped user it is the exact grant set;
     * for a fully-ungranted user in strict mode it is empty (deny-by-default). The
     * 23-03 read-scope helper interprets this against {@link #isGroupAdmin()}.
     */
    public Set<UUID> grantedShopIds() {
        onRequest();
        if (isGroupAdmin()) {
            return Set.of();
        }
        return Set.copyOf(resolveMembership(currentUserId()).perShopRole().keySet());
    }

    /**
     * Resolve (and cache) the caller's membership snapshot for {@code userId}
     * within the current tenant. Cached per-user; a NULL-shop GROUP_ADMIN row sets
     * {@link Membership#isGroupAdmin()}, specific-shop rows populate
     * {@link Membership#perShopRole()} (highest rank wins on the unlikely
     * duplicate). Side-effect-free — JIT/upsert live in {@link #onRequest()}, NOT
     * here (a {@code @Cacheable} method must be pure).
     */
    @Cacheable(value = "shopMembership", keyGenerator = "tenantAwareCacheKeyGenerator")
    public Membership resolveMembership(UUID userId) {
        UUID tenantId = currentTenantId();
        Map<UUID, ShopRole> perShop = new HashMap<>();
        boolean groupAdmin = false;
        for (ShopStaff row : shopStaffRepository.findByTenantIdAndUserId(tenantId, userId)) {
            if (row.getShopId() == null) {
                // Tenant-wide grant → GROUP_ADMIN shape (D-03).
                if (row.getRole() == ShopRole.GROUP_ADMIN) {
                    groupAdmin = true;
                }
            } else {
                perShop.merge(row.getShopId(), row.getRole(),
                        (a, b) -> a.rank() >= b.rank() ? a : b);
            }
        }
        return new Membership(groupAdmin, Map.copyOf(perShop));
    }

    /**
     * Evict a single user's membership cache entry (D-05, immediate revocation).
     * Called by 23-04's grant/revoke AFTER its DB write commits so the next
     * request re-resolves from {@code shop_staff} with no stale-allow window.
     * No-op in the {@code test} profile (no cache manager).
     */
    public void evictMembership(UUID userId) {
        cacheEvictor.evictEntity("shopMembership", "resolveMembership", userId);
    }

    // ---------------------------------------------------------------------
    // Per-request side effects (D-04 JIT + D-09 directory upsert) — Pitfall 4
    // ---------------------------------------------------------------------

    /**
     * Runs the per-request side effects once, at the top of every enforcement
     * entry point: the throttled directory upsert (always), then — while
     * strict-scoping is OFF — the JIT GROUP_ADMIN provision for a not-yet-granted,
     * non-realm-admin caller. Both target ONLY the caller's own {@code sub}; no
     * client-supplied role/shop is ever read (T-23-02-01).
     */
    private void onRequest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return;
        }
        UUID sub = parseSub(jwt);
        if (sub == null) {
            return;
        }
        UUID tenantId = currentTenantId();

        // D-09: throttled login upsert. Best-effort — a directory write must never
        // fail a real request. The native ON CONFLICT DO UPDATE ... WHERE
        // last_seen < cutoff makes a returning user within the window a no-op.
        try {
            OffsetDateTime cutoff = OffsetDateTime.now().minus(directoryUpsertInterval);
            userDirectoryRepository.upsertSeen(tenantId, sub,
                    jwt.getClaimAsString("email"), displayName(jwt), cutoff);
        } catch (RuntimeException ex) {
            log.warn("Directory upsert skipped (best-effort) for sub {} tenant {}: {}",
                    sub, tenantId, ex.getMessage());
        }

        // D-04 / D-12: JIT lazy-provision. Skipped when strict-scoping is ON, when
        // the caller is a realm-admin (implicit GROUP_ADMIN — no row needed), or
        // when the caller already has any grant. Race-safe via ON CONFLICT DO
        // NOTHING against uq_shop_staff_tenant_user_shop.
        if (!strictScoping
                && !isRealmAdmin()
                && !shopStaffRepository.existsByTenantIdAndUserId(tenantId, sub)) {
            int inserted = shopStaffRepository.insertGroupAdminIfAbsent(UUID.randomUUID(), tenantId, sub);
            if (inserted > 0) {
                log.info("JIT-provisioned tenant-wide GROUP_ADMIN for sub {} in tenant {}", sub, tenantId);
            }
            // Clear any pre-provision (empty) membership cached earlier this
            // request so the fresh grant is seen immediately (D-05).
            evictMembership(sub);
        }
    }

    // ---------------------------------------------------------------------
    // Security-context helpers
    // ---------------------------------------------------------------------

    /** D-03 bridge: read the existing {@code ROLE_admin} authority (do not re-parse realm_access). */
    private boolean isRealmAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> REALM_ADMIN_AUTHORITY.equals(a.getAuthority()));
    }

    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            UUID sub = parseSub(jwt);
            if (sub != null) {
                return sub;
            }
        }
        throw new IllegalStateException("No authenticated JWT principal with a UUID subject");
    }

    private UUID currentTenantId() {
        return TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
    }

    private static UUID parseSub(Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub == null) {
            return null;
        }
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String displayName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isBlank()) {
            return name;
        }
        return jwt.getClaimAsString("preferred_username");
    }
}
