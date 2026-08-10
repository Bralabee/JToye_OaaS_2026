package uk.jtoye.core.security.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 *   <li><strong>Strict-scoping switch (D-12, revised CR-07)</strong> — OFF (default)
 *       = D-04 auto-provision, everything honoured (day-one unchanged). ON = stops new
 *       auto-provisioning AND de-honours JIT-sourced tenant-wide GROUP_ADMIN rows (a day-one
 *       user genuinely becomes scoped); deliberate {@link GrantSource#OPERATOR} grants and
 *       realm admins are honoured unchanged; the tenant's oldest JIT admin is retained as a
 *       WARN-logged bootstrap so no tenant can lock itself out on the flip.</li>
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
    /**
     * Read-only lookup of a shop's owning {@code tenant_id} so a tenant-wide GROUP_ADMIN's
     * {@link #require(UUID, ShopRole)} cannot cross the tenant boundary (QA-council FC-1). A
     * JpaRepository proxy — it holds no reference back to this service, so no construction cycle.
     */
    private final ShopRepository shopRepository;
    /**
     * Lazy self-reference (WR-01): {@link #resolveMembership} is {@code @Cacheable}, but
     * {@code @EnableCaching} runs in default proxy mode, so a SELF-invocation
     * ({@code this.resolveMembership(...)}) never passes through the caching interceptor — the
     * cache would never populate, its TTL would be dead config, and every eviction would target
     * keys that never exist. The internal gate methods therefore reach {@code resolveMembership}
     * through this bean proxy ({@code self().resolveMembership(...)}) so the interceptor actually
     * runs. An {@link ObjectProvider} avoids a construction cycle. Mirrors the pattern plan 23-10
     * used for the cache-bypass fix (a proxy-reached cached loader, not self-invocation).
     */
    private final ObjectProvider<ShopAccessService> selfProvider;

    /**
     * D-12 (revised, CR-07): OFF (default) preserves the day-one JIT auto-provision and honours
     * every grant; ON stops new provisioning AND de-honours JIT-sourced tenant-wide GROUP_ADMIN
     * rows (operator grants + realm admins unchanged, oldest JIT admin kept as bootstrap).
     */
    @Value("${jtoye.access.strict-scoping:false}")
    private boolean strictScoping;

    /** D-09: throttle window — a returning user younger than this is a directory no-op. */
    @Value("${jtoye.access.directory-upsert-interval:PT1H}")
    private Duration directoryUpsertInterval;

    /**
     * CR-03 (D-04) fail-closed machine-client allowlist. A bearer token whose
     * {@code sub} is NOT a UUID (a client-credentials / service account, not a vendor
     * user in the UUID-keyed shop-staff model) may bypass shop-scoping ONLY when its
     * {@code azp}/{@code client_id} claim is declared here — trust is NEVER inferred
     * from an unparseable subject. EMPTY by default (the property is unset), so the
     * default posture DENIES every non-UUID-subject token. RLS still tenant-scopes any
     * allowlisted machine caller. Spring binds a comma-separated list to this set.
     */
    @Value("${jtoye.access.machine-client-ids:}")
    private Set<String> machineClientIds;

    /** INFO-log a declared machine client at most once per distinct client id (not per request). */
    private final Set<String> loggedMachineClients = ConcurrentHashMap.newKeySet();

    public ShopAccessService(ShopStaffRepository shopStaffRepository,
                             UserDirectoryRepository userDirectoryRepository,
                             TenantCacheEvictor cacheEvictor,
                             ShopRepository shopRepository,
                             ObjectProvider<ShopAccessService> selfProvider) {
        this.shopStaffRepository = shopStaffRepository;
        this.userDirectoryRepository = userDirectoryRepository;
        this.cacheEvictor = cacheEvictor;
        this.shopRepository = shopRepository;
        this.selfProvider = selfProvider;
    }

    /**
     * The Spring-proxied instance of this bean, so a call to the {@code @Cacheable}
     * {@link #resolveMembership} passes through the caching interceptor (WR-01). Only used for
     * that method — every other internal call is a plain method invocation.
     */
    private ShopAccessService self() {
        return selfProvider.getObject();
    }

    // ---------------------------------------------------------------------
    // Enforcement API (the contract 23-03 / 23-04 code against)
    // ---------------------------------------------------------------------

    /**
     * Require at least {@code minRole} on {@code shopId}. A realm-admin or
     * tenant-wide GROUP_ADMIN passes unconditionally. Otherwise the caller's role
     * on {@code shopId} must {@link ShopRole#satisfies(ShopRole)} the floor.
     *
     * <p><strong>Null-shop policy (CR-04) — this is the WRITE half of a pair:</strong>
     * a {@code null} {@code shopId} denotes a tenant-wide / unassigned resource (e.g. a
     * legacy {@code Product} whose {@code shop_id} is NULL, "available on all tenant
     * shops"). <em>Writes</em> to a null-shop resource are GROUP_ADMIN-only: a scoped
     * non-GROUP_ADMIN caller receives the typed 403
     * ({@code new ShopAccessDeniedException(null, GROUP_ADMIN)}), never a 500. The
     * paired <em>READ</em> half — legacy {@code shop_id IS NULL} products staying
     * visible to any granted scoped user — is implemented by plan <strong>23-09</strong>
     * in {@code ProductService}/{@code ProductRepository} (which depends on this plan).
     * The two halves are stated together so they cannot drift: <em>writes to a null-shop
     * resource are GROUP_ADMIN-only; reads of a null-shop resource are tenant-wide-visible
     * to any granted user</em>. This pairing is additive — it preserves the pre-phase
     * visibility of legacy catalogue data rather than silently removing it (Incremental
     * Betterment).
     *
     * @throws ShopAccessDeniedException (distinct RFC 7807 403) if the caller
     *         lacks {@code minRole} on {@code shopId}, or if {@code shopId} is null and
     *         the caller is not a GROUP_ADMIN.
     */
    public void require(UUID shopId, ShopRole minRole) {
        onRequest();
        if (isGroupAdmin()) {
            // FC-1 (QA-council): a tenant-wide GROUP_ADMIN is tenant-WIDE, NOT cross-tenant. The
            // early-return used to grant access for ANY shopId, so a tenant-B GROUP_ADMIN could
            // name a tenant-A shop on a write (BOLA). When a shop IS named, verify it belongs to
            // the caller's tenant BEFORE the early-return grants access. (A null shopId is a
            // tenant-wide resource handled below/above — nothing to bind to a tenant here.)
            if (shopId != null) {
                requireShopInCallerTenant(shopId);
            }
            return;
        }
        // CR-04: a null shopId is a tenant-wide / unassigned resource; only a
        // GROUP_ADMIN (handled above) may write it. Deny a scoped caller with the typed
        // 403 HERE — BEFORE the perShopRole().get(shopId) lookup below. That map is
        // built with Map.copyOf(...), whose ImmutableCollections.MapN.get(null) throws
        // NullPointerException (→ HTTP 500) in BOTH the populated and empty cases.
        // Guarding in require() (not at map construction) makes the outcome independent
        // of the membership map's concrete type, e.g. a future deserialized LinkedHashMap.
        if (shopId == null) {
            throw new ShopAccessDeniedException(null, ShopRole.GROUP_ADMIN);
        }
        Membership membership = self().resolveMembership(requireVendorUserId());
        ShopRole role = membership.perShopRole().get(shopId);
        if (role == null || !role.satisfies(minRole)) {
            throw new ShopAccessDeniedException(shopId, minRole);
        }
    }

    /**
     * FC-1 (QA-council): assert {@code shopId} is owned by the caller's tenant, so a tenant-wide
     * GROUP_ADMIN's {@link #require} cannot reach across the tenant boundary. Only consulted for a
     * GROUP_ADMIN with a NON-null shopId (the non-GROUP_ADMIN branch is already tenant-safe: a
     * foreign shopId is absent from the caller's per-shop grant map, so {@code role == null} denies
     * it there).
     *
     * <p><strong>The RLS subtlety this method exists to defeat:</strong> the {@code shops_public_read}
     * policy is {@code (published = true) OR (tenant_id = current_tenant_id())}, so
     * {@code shopRepository.findById(foreignShopId)} under the caller's tenant GUC STILL returns a
     * foreign shop when it is PUBLISHED — carrying its real foreign {@code tenant_id}. A
     * null/empty check would therefore pass a published foreign shop straight through. Only an
     * explicit {@code tenant_id} comparison closes the hole. A foreign UNPUBLISHED (or absent) shop
     * is filtered to empty by RLS and denied by {@code orElseThrow}.
     *
     * <p>Cross-tenant shop access is answered NON-DISCLOSINGLY as a 404
     * {@link ResourceNotFoundException}, never a 403 — the established contract from the PR #70 /
     * issue #71 cross-tenant IDOR fix (guarded by {@code ShopImageCrossTenantIntegrationTest}) and
     * the {@code PublicStorefrontService} pattern: a caller must not learn that a shop it cannot
     * touch exists in another tenant. The message is byte-identical to a genuinely-absent shop
     * (ShopService throws the same {@code "Shop not found: "}), so the two are indistinguishable.
     *
     * @throws ShopAccessDeniedException if no tenant is pinned (a security-config error, not a
     *         cross-tenant access).
     * @throws ResourceNotFoundException if the shop is not visible under the caller's tenant, or
     *         the shop's {@code tenant_id} differs from the caller's (both answered as 404).
     */
    private void requireShopInCallerTenant(UUID shopId) {
        UUID callerTenant = TenantContext.get()
                .orElseThrow(() -> new ShopAccessDeniedException(shopId, ShopRole.GROUP_ADMIN));
        UUID shopTenant = shopRepository.findById(shopId)
                .map(Shop::getTenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId)); // foreign unpublished / absent -> RLS-empty
        if (!callerTenant.equals(shopTenant)) {
            throw new ResourceNotFoundException("Shop not found: " + shopId); // foreign published shop -> tenant mismatch (non-disclosing)
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

    /**
     * True for a realm-admin (D-03 bridge), a DECLARED internal system caller
     * ({@link SystemPrincipal#asSystem}), a declared machine client (CR-03 allowlist), or a
     * tenant-wide GROUP_ADMIN grant. Every OTHER caller — anonymous, non-{@code Jwt}, a JWT
     * whose {@code sub} is not a UUID and is not an allowlisted machine client, <em>and now
     * also a thread carrying no {@code Authentication} at all</em> — is DENIED (fail-closed,
     * typed {@link ShopAccessDeniedException} 403), never escalated to GROUP_ADMIN and never
     * a 500.
     *
     * <p><strong>The internal bypass is DECLARED, not inferred (#283 — closed in Phase 28).</strong>
     * This javadoc previously described a retained bypass in which a gate call with NO
     * {@code Authentication} on the thread was treated as trusted internal work — a scheduled
     * job, an AMQP listener, an internal service-to-service call, or one of the 62
     * no-principal tests that depended on it. Its safety rested on an unenforced property
     * (that Spring Security 401s an unauthenticated request before any gated service is
     * entered, so no background path reaches one), and #284 recorded that it was "one new
     * call away" from being false. It is replaced: an internal caller now declares itself
     * through {@link SystemPrincipal#asSystem}, and an absent principal with no declaration
     * is denied. See {@link #isInternalCaller()}.
     *
     * <p><strong>CR-03 / D-04:</strong> the former, wider rule ("any call with no JWT
     * principal is trusted, and any unparseable subject is trusted") mapped "I cannot
     * parse this identity" to "unrestricted GROUP_ADMIN" — fail-OPEN, explicitly
     * rejected by locked decision D-04 as unacceptable for an auth boundary. It is
     * removed: an unparseable or unexpected identity SHAPE now fails closed, and a
     * machine caller is trusted only through the explicit, empty-by-default
     * {@link #machineClientIds} allowlist.
     */
    public boolean isGroupAdmin() {
        if (isRealmAdmin() || isInternalCaller()) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt && isDeclaredMachineClient(jwt)) {
            return true;
        }
        // Beyond this point the caller MUST resolve to a UUID-subject vendor user; an
        // anonymous, non-Jwt, or unparseable-subject request principal is denied with
        // the typed 403 here (never escalated, never a 500 from the old currentUserId()).
        // The membership + strict-scoping tail is shared with canAccessShop(...) via
        // isGroupAdminForUser so the HTTP and STOMP (23-11) boundaries provably cannot
        // drift — realm-admin is already handled above, so pass false here.
        return isGroupAdminForUser(requireVendorUserId(), false);
    }

    /**
     * The shared group/grant decision ladder, taking a fully-explicit
     * {@code (userId, realmAdmin)} so the request-context {@link #isGroupAdmin()} and the
     * explicit-identity {@link #canAccessShop(UUID, UUID, boolean, UUID)} decide identically
     * and CANNOT drift: both funnel through here for the substantive membership +
     * strict-scoping decision (the part that would silently diverge if duplicated).
     *
     * <p>The ladder, mirroring {@link #isGroupAdmin()} exactly:
     * <ol>
     *   <li>{@code realmAdmin} → true (D-03 realm-admin bridge, implicit GROUP_ADMIN).</li>
     *   <li>a tenant-wide GROUP_ADMIN {@code shop_staff} row:
     *     <ul>
     *       <li>strict-scoping OFF (day-one) → true (honour every grant, unchanged);</li>
     *       <li>strict-scoping ON + an {@link GrantSource#OPERATOR} row → true (deliberate
     *           operator grant, always honoured);</li>
     *       <li>strict-scoping ON + a {@link GrantSource#JIT} row → DE-HONOURED (CR-07),
     *           EXCEPT the tenant's deterministic bootstrap admin ({@link #isBootstrapAdmin}),
     *           kept so the flip can never leave a tenant with zero GROUP_ADMINs.</li>
     *     </ul></li>
     *   <li>strict-scoping OFF (day-one) AND a FULLY-ungranted user → true (implicit
     *       GROUP_ADMIN; once a user holds ANY explicit grant they are scoped even under
     *       strict-scoping OFF).</li>
     *   <li>otherwise false.</li>
     * </ol>
     *
     * <p>The strict-scoping policy is applied HERE — in the decision helper, outside the
     * cached {@link #resolveMembership} snapshot — so a change to the {@code strictScoping}
     * flag (which is not part of the cache key) is never served from a stale cached decision
     * (WR-01 corollary). The cached snapshot carries only the raw provenance fact.
     *
     * <p>Reads only {@code shop_staff} (via the cached {@link #resolveMembership}, plus the
     * bootstrap-rule finders under strict-scoping ON); performs NO writes and no
     * ambient-context access — {@code userId} and {@code realmAdmin} are always supplied by
     * the caller.
     */
    private boolean isGroupAdminForUser(UUID userId, boolean realmAdmin) {
        if (realmAdmin) {
            return true;
        }
        Membership membership = self().resolveMembership(userId);
        if (membership.isGroupAdmin()) {
            if (!strictScoping) {
                return true;                         // day-one: honour every grant
            }
            if (!membership.groupAdminFromJit()) {
                return true;                         // operator grant: honoured under strict ON
            }
            // JIT-sourced under strict ON: de-honoured, UNLESS this user is the tenant's
            // deterministic bootstrap admin (kept to avoid a zero-admin lockout).
            return isBootstrapAdmin(userId);
        }
        return !strictScoping && membership.perShopRole().isEmpty();
    }

    /**
     * Lockout-safety rule for the strict-scoping tightening (CR-07): when de-honouring a
     * tenant's JIT-sourced tenant-wide GROUP_ADMIN rows, the OLDEST such row (by
     * {@code created_at}, tie-broken by {@code id}) continues to be honoured as the bootstrap
     * admin so the tenant is never left with zero GROUP_ADMINs. Consulted ONLY when the caller
     * holds a JIT tenant-wide GROUP_ADMIN and strict-scoping is ON.
     *
     * <ul>
     *   <li>if the tenant has ANY {@link GrantSource#OPERATOR} tenant-wide GROUP_ADMIN, no JIT
     *       bootstrap is needed — that operator admin covers the tenant, so every JIT admin is
     *       fully de-honoured (returns false);</li>
     *   <li>otherwise the oldest JIT tenant-wide GROUP_ADMIN is the bootstrap admin; only that
     *       user is honoured, logged at WARN so the operator has a signal that the tightening
     *       is partial and who still holds admin.</li>
     * </ul>
     *
     * <p>The realm-{@code admin} bridge remains an independent recovery backstop, but a tenant
     * with no realm admin must not be able to lock itself out on a config flip — and CR-06's
     * revoke guard cannot help here because the flip is a config change, not a revoke.
     */
    private boolean isBootstrapAdmin(UUID userId) {
        UUID tenantId = currentTenantId();
        if (shopStaffRepository.existsTenantWideGroupAdminBySource(tenantId, GrantSource.OPERATOR)) {
            return false;   // an operator admin covers the tenant → JIT users fully de-honoured
        }
        List<ShopStaff> jitAdmins = shopStaffRepository.findTenantWideJitGroupAdminsOldestFirst(tenantId);
        if (jitAdmins.isEmpty()) {
            return false;   // defensive: the caller holds a JIT row, so this should be non-empty
        }
        boolean bootstrap = jitAdmins.get(0).getUserId().equals(userId);
        if (bootstrap) {
            log.warn("Strict-scoping bootstrap admin retained for tenant {}: user {} is the oldest "
                    + "JIT-provisioned GROUP_ADMIN and is kept to avoid a zero-admin lockout. Grant an "
                    + "explicit operator GROUP_ADMIN to fully tighten day-one access.", tenantId, userId);
        }
        return bootstrap;
    }

    /**
     * Explicit-identity variant of the shop-read decision for callers OUTSIDE a request
     * thread — specifically the STOMP inbound channel (23-11 / CR-02), where the subscriber's
     * identity lives on the WebSocket session principal, NOT in the ambient Spring Security
     * context. Answers "may this specific {@code userId} READ this specific {@code shopId} in
     * {@code tenantId}" with NO ambient-state dependency: every input is a parameter.
     *
     * <p><strong>Why this cannot reuse {@link #isGroupAdmin()}/{@link #grantedShopIds()}:</strong>
     * those read the ambient security context, and the STOMP CONNECT path populates only the
     * WebSocket session principal, never that context — so the ambient-context methods would
     * be deciding about an identity that is not there. Under 23-08 that failed OPEN (an absent
     * {@code Authentication} was the retained internal-caller bypass, so a WebSocket subscriber
     * was classified as internal and granted). Phase 28 (#283) inverted that default: an
     * undeclared no-principal thread is now DENIED, so the same reuse would fail CLOSED and
     * refuse every legitimate subscriber instead. Both directions are wrong for the same
     * reason — the decision needs the identity the session principal holds. Identity therefore
     * arrives explicitly here (T-23-11-02), and this method is unaffected by the #283 change
     * because it never consults the ambient context at all.
     *
     * <p>The group/grant decision funnels through {@link #isGroupAdminForUser} so it stays
     * byte-identical to the HTTP boundary. A specific-shop caller is permitted when it holds
     * ANY per-shop grant (STAFF and above) on {@code shopId} — a SUBSCRIBE is a read. A
     * {@code null} {@code shopId} (tenant-wide resource) is GROUP_ADMIN-only, mirroring
     * {@link #require}'s WRITE guard.
     *
     * <p>Performs NO writes ({@link #onRequest()} is deliberately NOT called — a subscribe
     * must never JIT-provision or upsert the directory) and reads NO identity from ambient
     * thread state. The {@code shop_staff} read is RLS-scoped by the tenant GUC the caller
     * pinned; this method asserts the pinned tenant equals {@code tenantId} so an UNPINNED
     * GUC (which RLS would answer with zero rows → "no grants" → a fail-OPEN implicit
     * GROUP_ADMIN under strict-scoping OFF) cannot pass silently.
     */
    public boolean canAccessShop(UUID tenantId, UUID userId, boolean realmAdmin, UUID shopId) {
        // Fail-closed on an unpinned/mismatched tenant GUC: the RLS-scoped shop_staff read
        // below is only correct when the caller pinned THIS tenant. currentTenantId() throws
        // when no tenant is pinned (→ subscription denied upstream); it derives the tenant
        // from the pinned request tenant, never from a caller identity.
        UUID pinnedTenant = currentTenantId();
        if (!pinnedTenant.equals(tenantId)) {
            throw new IllegalStateException(
                    "canAccessShop tenant mismatch: pinned=" + pinnedTenant + " requested=" + tenantId);
        }
        if (isGroupAdminForUser(userId, realmAdmin)) {
            return true;
        }
        if (shopId == null) {
            // Tenant-wide resource: GROUP_ADMIN-only (handled above). A scoped caller is denied.
            return false;
        }
        // Any explicit per-shop grant (STAFF+) is sufficient to READ/SUBSCRIBE the shop feed.
        return self().resolveMembership(userId).perShopRole().get(shopId) != null;
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
        return Set.copyOf(self().resolveMembership(requireVendorUserId()).perShopRole().keySet());
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
        boolean groupAdminFromJit = false;
        for (ShopStaff row : shopStaffRepository.findByTenantIdAndUserId(tenantId, userId)) {
            if (row.getShopId() == null) {
                // Tenant-wide grant → GROUP_ADMIN shape (D-03). Record its provenance so the
                // strict-scoping DECISION (isGroupAdminForUser) can de-honour a JIT row while
                // honouring an operator one (CR-07). The V52 unique index guarantees at most
                // one tenant-wide row per user, so this single row settles the flag.
                if (row.getRole() == ShopRole.GROUP_ADMIN) {
                    groupAdmin = true;
                    groupAdminFromJit = (row.getGrantSource() == GrantSource.JIT);
                }
            } else {
                perShop.merge(row.getShopId(), row.getRole(),
                        (a, b) -> a.rank() >= b.rank() ? a : b);
            }
        }
        return new Membership(groupAdmin, groupAdminFromJit, Map.copyOf(perShop));
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

    /**
     * Evict {@code userId}'s membership cache entry AFTER the current transaction commits
     * (WR-11 / D-05). The single shared idiom used by BOTH {@link #onRequest}'s JIT provision
     * AND {@link StaffManagementService}'s grant/revoke, so the two can never drift again.
     *
     * <p>Post-commit is load-bearing the moment the cache genuinely engages (Task 3): an inline
     * evict inside the transaction leaves a window in which a concurrent request can call
     * {@link #resolveMembership}, read the not-yet-committed (empty) state, and repopulate the
     * cache with it — stale for the full TTL. Registering an {@code afterCommit} synchronization
     * closes that window. Falls back to an inline evict when no synchronization is active (a no-op
     * in the {@code test} profile — no cache manager).
     */
    public void evictMembershipAfterCommit(UUID userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictMembership(userId);
                }
            });
        } else {
            evictMembership(userId);
        }
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

        // WR-09 (CR-07): a DECLARED machine/service client must never accumulate a persistent
        // tenant-wide GROUP_ADMIN row — even when Keycloak issues its service account a UUID
        // subject (so parseSub above succeeds). Those JIT rows survived the strict-scoping flip
        // and appeared in the staff list as opaque UUIDs with no directory entry. A machine
        // client's access already runs through the 23-08 allowlist (the isGroupAdmin()
        // short-circuit), so skip BOTH the directory upsert (it is a human grant-target picker)
        // AND the JIT provision entirely — nothing breaks, it just stops acquiring grants by
        // accident. Classified purely by the allowlisted azp/client_id, independent of sub shape.
        if (isAllowlistedMachineClient(jwt)) {
            return;
        }

        // Phase 23-03: NEVER attempt a write in a read-only transaction. A failed
        // INSERT (Postgres rejects writes in a read-only tx) would poison the whole
        // transaction ("current transaction is aborted"), breaking the read that
        // triggered this gate call — even though the directory upsert is wrapped in a
        // best-effort try/catch, the SQL statement itself still aborts the tx. The
        // read DECISION does not depend on these writes ({@link #isGroupAdmin()}
        // derives the day-one implicit GROUP_ADMIN from strict-scoping, not from the
        // JIT row), so the directory upsert + JIT provision run ONLY on write-capable
        // request paths. The JIT row is still materialised on the first write request.
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
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
            // WR-11: evict AFTER commit, via the shared helper, so a concurrent request cannot
            // cache the pre-insert (empty) membership and pin it for the full TTL. (Pre-Task-3
            // this fired inline inside the tx — latent only because the cache never engaged.)
            evictMembershipAfterCommit(sub);
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

    /**
     * True ONLY when the current thread is inside an explicitly DECLARED
     * {@link SystemPrincipal#asSystem} scope (#283).
     *
     * <p>Trust is granted ONLY by that declaration — <strong>never inferred from a missing
     * principal</strong>. This method used to return
     * {@code SecurityContextHolder.getContext().getAuthentication() == null}, i.e. it read
     * "I have no identity" as "I am trusted". That rule was correct only for as long as no
     * gated service was reachable from a background path, which nothing enforced and any new
     * call could end (#284). An absent {@code Authentication} with no declaration is now
     * DENIED, in this class's established fail-closed shape: the ladder in
     * {@link #isGroupAdmin()} falls through to {@link #requireVendorUserId()}, which raises
     * the typed {@link ShopAccessDeniedException} 403 — never an untyped 500.
     *
     * <p>The rule that an ANONYMOUS or otherwise non-authenticated <em>request</em> principal
     * is NOT internal was already correct and survives unchanged — in fact it strengthens,
     * because the condition is now a positive declaration rather than the absence of one
     * thing. A request thread never enters {@code asSystem} (only background entry points
     * that act as the system do), so a request principal of any shape answers {@code false}
     * here and is decided by the ladder below it.
     *
     * <p>Mirrors {@link #isDeclaredMachineClient(Jwt)}: declaration over inference. And as
     * there, <strong>RLS still tenant-scopes a system caller</strong> — the marker is an
     * authorisation declaration about the shop-scope gate only, and grants no tenancy escape
     * whatsoever.
     *
     * @see SystemPrincipal
     */
    private boolean isInternalCaller() {
        return SystemPrincipal.isSystem();
    }

    /**
     * True when {@code jwt} is a declared machine/service client: its {@code sub} is
     * NOT a UUID (so it cannot be a vendor user in the UUID-keyed shop-staff model)
     * AND its {@code azp}/{@code client_id} claim is present in the configured,
     * empty-by-default {@link #machineClientIds} allowlist. Trust is granted ONLY by
     * this explicit declaration — never inferred from the unparseable subject alone
     * (CR-03 / D-04). RLS still tenant-scopes an allowlisted machine caller. The INFO
     * line is emitted at most once per distinct client id.
     */
    private boolean isDeclaredMachineClient(Jwt jwt) {
        if (parseSub(jwt) != null) {
            return false; // a UUID subject is a vendor user, resolved via shop_staff
        }
        String clientId = resolveClientId(jwt);
        if (clientId == null || clientId.isBlank()) {
            return false; // no declared client identity → not a machine caller → denied
        }
        boolean declared = machineClientIds != null && machineClientIds.contains(clientId);
        if (declared && loggedMachineClients.add(clientId)) {
            log.info("Machine client '{}' bypasses shop-scoping via jtoye.access.machine-client-ids "
                    + "(RLS still tenant-scopes it)", clientId);
        }
        return declared;
    }

    /**
     * True when {@code jwt}'s declared client id ({@code azp}/{@code client_id}) is on the
     * empty-by-default {@link #machineClientIds} allowlist — regardless of whether its
     * {@code sub} is a UUID. This is the WR-09 gate: {@link #isDeclaredMachineClient} only
     * recognises a NON-UUID-subject token (its purpose is the {@link #isGroupAdmin()} bypass),
     * but a Keycloak service account carries a UUID subject, so {@link #onRequest} needs this
     * subject-shape-independent check to stop provisioning it a GROUP_ADMIN row (CR-07). Trust
     * is still granted ONLY by the explicit allowlist, never inferred.
     */
    private boolean isAllowlistedMachineClient(Jwt jwt) {
        String clientId = resolveClientId(jwt);
        return clientId != null && !clientId.isBlank()
                && machineClientIds != null && machineClientIds.contains(clientId);
    }

    /**
     * The caller's declared client id — Keycloak's {@code azp} (authorized party),
     * falling back to {@code client_id}. Returns {@code null} when neither claim is
     * present, so an unparseable-subject token with no declared client identity is
     * denied by default.
     */
    private static String resolveClientId(Jwt jwt) {
        String azp = jwt.getClaimAsString("azp");
        if (azp != null && !azp.isBlank()) {
            return azp;
        }
        return jwt.getClaimAsString("client_id");
    }

    /**
     * The authenticated vendor user's UUID subject. Fail-closed: a request-scoped
     * principal that is anonymous, non-{@code Jwt}, or carries a non-UUID {@code sub}
     * (and is not a declared machine client — that case short-circuits in
     * {@link #isGroupAdmin()} before this is reached) is DENIED with the typed
     * {@link ShopAccessDeniedException} 403, NOT the {@code IllegalStateException} 500
     * the old {@code currentUserId()} threw (CR-03). An auth failure is a typed 403,
     * never an untyped 500.
     */
    private UUID requireVendorUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            UUID sub = parseSub(jwt);
            if (sub != null) {
                return sub;
            }
        }
        throw new ShopAccessDeniedException(null, ShopRole.GROUP_ADMIN);
    }

    /**
     * The current request principal's vendor user id, or {@link Optional#empty()} when the
     * caller cannot be identified as a UUID-subject vendor user: an absent, anonymous or
     * non-{@code Jwt} principal, or a JWT whose {@code sub} is not a UUID (the declared
     * machine-client shape).
     *
     * <p>Deliberately NOT a rewrite of {@link #requireVendorUserId()}: that method's contract
     * is to DENY an unidentifiable principal with the typed {@link ShopAccessDeniedException}
     * 403, and several call sites rely on exactly that. This is the non-throwing sibling, for
     * the one caller that must make its OWN decision about an unidentifiable principal —
     * {@code OrderSseService.subscribe()} (#281), which refuses to ATTACH an emitter whose
     * owner it could never re-check, rather than raising a 403 out of a stream handshake.
     *
     * <p>Reads only the ambient security context, performs no writes, and is NOT an
     * authorization decision: a present user id says "this principal has a UUID subject" and
     * nothing whatsoever about what that user may read.
     */
    public Optional<UUID> currentVendorUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return Optional.ofNullable(parseSub(jwt));
        }
        return Optional.empty();
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
