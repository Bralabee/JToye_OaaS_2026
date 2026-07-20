package uk.jtoye.core.security.access;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.exception.LastGroupAdminException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.StaffManagementService.GrantResult;
import uk.jtoye.core.security.access.dto.GrantStaffRequest;
import uk.jtoye.core.security.access.dto.MyAccessDto;
import uk.jtoye.core.security.access.dto.StaffMemberDto;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VSA-04 staff-management proof against real Postgres 15 (Testcontainers). Drives
 * {@link StaffController} + {@link StaffManagementService} + {@link ShopAccessService}
 * end-to-end and proves the five must-have truths:
 *
 * <ol>
 *   <li>grant → the target GAINS access on their next {@code require()};</li>
 *   <li>revoke → the target immediately receives the typed shop-access 403;</li>
 *   <li>a duplicate grant is an idempotent typed 200 replay (one row, no
 *       {@code DataIntegrityViolationException} 500) — the agent-readiness contract;</li>
 *   <li>revoking the final GROUP_ADMIN is blocked with a 409
 *       ({@link LastGroupAdminException}, D-11);</li>
 *   <li>a non-GROUP_ADMIN caller receives the typed shop-access 403 (D-10).</li>
 * </ol>
 *
 * <p>Harness mirrors {@code ShopAccessJitProvisionTest}: NOT {@code @Transactional}
 * (grants/revokes must genuinely COMMIT so a separate-transaction {@code require()}
 * observes them), fresh random {@code (tenant, sub)} per test, and the strict-scoping
 * flag toggled on the {@link AopTestUtils}-unwrapped target bean. The caller is a
 * realm-admin (implicit GROUP_ADMIN, {@code ROLE_admin}) so it needs no
 * {@code shop_staff} row and never JIT-provisions one — keeping the GROUP_ADMIN count
 * equal to exactly the target grants under test.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class StaffManagementIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired
    private StaffController staffController;

    @Autowired
    private StaffManagementService staffManagementService;

    @Autowired
    private ShopAccessService shopAccessService;

    @Autowired
    private ShopService shopService;

    @Autowired
    private JdbcTemplate jdbc;

    private ShopAccessService targetService;

    @AfterEach
    void tearDown() {
        setStrictScoping(false);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private ShopAccessService target() {
        if (targetService == null) {
            targetService = AopTestUtils.getTargetObject(shopAccessService);
        }
        return targetService;
    }

    private void setStrictScoping(boolean value) {
        ReflectionTestUtils.setField(target(), "strictScoping", value);
    }

    /** Install a JWT principal (sub + optional realm-admin authority) + pin the tenant. */
    private void authenticate(UUID sub, boolean realmAdmin, UUID tenant) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(sub.toString())
                .claim("email", "user-" + sub + "@example.com")
                .claim("name", "Test User " + sub)
                .build();
        List<GrantedAuthority> authorities = realmAdmin
                ? List.of(new SimpleGrantedAuthority("ROLE_admin"))
                : List.of();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        TenantContext.set(tenant);
    }

    private long shopStaffRowCount(UUID tenant, UUID user, UUID shopId) {
        Long n = shopId == null
                ? jdbc.queryForObject("SELECT count(*) FROM shop_staff "
                        + "WHERE tenant_id = ? AND user_id = ? AND shop_id IS NULL", Long.class, tenant, user)
                : jdbc.queryForObject("SELECT count(*) FROM shop_staff "
                        + "WHERE tenant_id = ? AND user_id = ? AND shop_id = ?", Long.class, tenant, user, shopId);
        return n == null ? 0 : n;
    }

    private long tenantWideGroupAdminCount(UUID tenant) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM shop_staff "
                + "WHERE tenant_id = ? AND role = 'GROUP_ADMIN' AND shop_id IS NULL", Long.class, tenant);
        return n == null ? 0 : n;
    }

    /**
     * A real shop must exist to satisfy {@code shop_staff_shop_id_fkey} on a
     * shop-scoped grant (a tenant-wide GROUP_ADMIN grant has a NULL shop_id and needs
     * none). Seed the tenant row + a shop through the real service layer as a
     * realm-admin (implicit GROUP_ADMIN — bypasses the gate), mirroring
     * {@code ShopAccessEnforcementIntegrationTest}. Returns the committed shop id.
     */
    private UUID seedShop(UUID tenant) {
        // tenants.name is UNIQUE — derive from the id so parallel test tenants never collide.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "Staff Test Tenant " + tenant);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        authenticate(UUID.randomUUID(), true, tenant);
        try {
            CreateShopRequest req = new CreateShopRequest();
            req.setName("Staff Test Shop " + UUID.randomUUID());
            req.setAddress("1 Test Street, London");
            return shopService.createShop(req).getId();
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }

    /**
     * Seed a login-populated {@code user_directory} row so a grant may target this user
     * (WR-05 / 23-12 Task 1: {@code grant()} now requires the target to appear in the
     * tenant's directory — the login-populated picker, D-09). The Testcontainers bootstrap
     * role is a SUPERUSER, so this direct INSERT bypasses FORCE RLS and needs no tenant GUC.
     */
    private void seedDirectory(UUID tenant, UUID userId) {
        jdbc.update("INSERT INTO user_directory (tenant_id, user_id, email, display_name, last_seen) "
                        + "VALUES (?, ?, ?, ?, now()) ON CONFLICT (tenant_id, user_id) DO NOTHING",
                tenant, userId, "user-" + userId + "@example.com", "Directory User " + userId);
    }

    /**
     * Truth 1 + 2: a GROUP_ADMIN grants a shop-scoped role (201) → the target gains
     * access; the GROUP_ADMIN revokes it (204) → the target immediately gets the typed
     * shop-access 403. Run under strict-scoping ON so the revoked (now ungranted)
     * target is deny-by-default rather than the day-one implicit GROUP_ADMIN.
     */
    @Test
    void grantGivesAccess_thenRevokeProduces403() {
        setStrictScoping(true);
        UUID tenant = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        UUID shopId = seedShop(tenant);

        // GROUP_ADMIN (realm-admin) grants the staff member SHOP_MANAGER on one shop.
        authenticate(admin, true, tenant);
        seedDirectory(tenant, staff);
        ResponseEntity<StaffMemberDto> granted =
                staffController.grant(new GrantStaffRequest(staff, shopId, ShopRole.SHOP_MANAGER));
        assertThat(granted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(granted.getBody()).isNotNull();
        UUID grantId = granted.getBody().id();

        // The target now satisfies require() on that shop (access GAINED).
        authenticate(staff, false, tenant);
        assertThatCode(() -> shopAccessService.require(shopId, ShopRole.SHOP_MANAGER))
                .as("granted staff can access their shop")
                .doesNotThrowAnyException();

        // The GROUP_ADMIN revokes the grant (204).
        authenticate(admin, true, tenant);
        ResponseEntity<Void> revoked = staffController.revoke(grantId);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The target immediately receives the typed shop-access 403 (cache evicted /
        // no cache in the test profile → fresh re-resolve sees the deleted row).
        authenticate(staff, false, tenant);
        assertThatThrownBy(() -> shopAccessService.require(shopId, ShopRole.SHOP_MANAGER))
                .as("revoked staff is denied with the typed shop-access 403")
                .isInstanceOf(ShopAccessDeniedException.class);
    }

    /**
     * Truth 4 (agent-readiness): a retried/duplicate grant with the identical
     * (userId, shopId, role) replays the existing grant as a typed 200 carrying the
     * SAME StaffMemberDto id — never a DataIntegrityViolationException 500 — and
     * exactly ONE row is written.
     */
    @Test
    void duplicateGrantIsIdempotentReplay() {
        UUID tenant = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        UUID shopId = seedShop(tenant);

        authenticate(admin, true, tenant);
        seedDirectory(tenant, staff);

        ResponseEntity<StaffMemberDto> first =
                staffController.grant(new GrantStaffRequest(staff, shopId, ShopRole.STAFF));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID firstId = first.getBody().id();

        ResponseEntity<StaffMemberDto> replay =
                staffController.grant(new GrantStaffRequest(staff, shopId, ShopRole.STAFF));
        assertThat(replay.getStatusCode())
                .as("duplicate grant is a typed 200 replay, not a 201/500")
                .isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().id())
                .as("replay returns the SAME canonical grant id")
                .isEqualTo(firstId);

        assertThat(shopStaffRowCount(tenant, staff, shopId))
                .as("no duplicate row written (ON CONFLICT DO NOTHING)")
                .isEqualTo(1);
    }

    /**
     * Truth 5 (D-11): revoking the tenant's FINAL GROUP_ADMIN grant is blocked with a
     * {@link LastGroupAdminException} (→ RFC 7807 409) and the row survives.
     */
    @Test
    void revokingLastGroupAdminIsBlockedWith409() {
        UUID tenant = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID soleGroupAdmin = UUID.randomUUID();

        authenticate(admin, true, tenant);
        seedDirectory(tenant, soleGroupAdmin);
        // Grant the sole tenant-wide GROUP_ADMIN (the realm-admin caller holds no row).
        ResponseEntity<StaffMemberDto> granted =
                staffController.grant(new GrantStaffRequest(soleGroupAdmin, null, ShopRole.GROUP_ADMIN));
        assertThat(granted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID grantId = granted.getBody().id();
        assertThat(tenantWideGroupAdminCount(tenant)).isEqualTo(1);

        assertThatThrownBy(() -> staffController.revoke(grantId))
                .as("the last GROUP_ADMIN cannot be revoked")
                .isInstanceOf(LastGroupAdminException.class);

        assertThat(tenantWideGroupAdminCount(tenant))
                .as("the last GROUP_ADMIN row survives the blocked revoke")
                .isEqualTo(1);
    }

    /**
     * A duplicate GROUP_ADMIN grant is idempotent, and once TWO GROUP_ADMINs exist the
     * guard releases — one of them CAN be revoked (leaving the tenant with one).
     */
    @Test
    void secondGroupAdminCanBeRevokedButNotTheLast() {
        UUID tenant = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID gaOne = UUID.randomUUID();
        UUID gaTwo = UUID.randomUUID();

        authenticate(admin, true, tenant);
        seedDirectory(tenant, gaOne);
        seedDirectory(tenant, gaTwo);
        UUID gaOneGrant = staffController.grant(new GrantStaffRequest(gaOne, null, ShopRole.GROUP_ADMIN))
                .getBody().id();
        staffController.grant(new GrantStaffRequest(gaTwo, null, ShopRole.GROUP_ADMIN));
        assertThat(tenantWideGroupAdminCount(tenant)).isEqualTo(2);

        // With two GROUP_ADMINs the guard releases: revoking one succeeds (204).
        ResponseEntity<Void> revoked = staffController.revoke(gaOneGrant);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(tenantWideGroupAdminCount(tenant)).isEqualTo(1);
    }

    /**
     * Truth 6 (D-10): a non-GROUP_ADMIN caller (ungranted, strict-scoping ON so no
     * day-one implicit GROUP_ADMIN) receives the typed shop-access 403 from every
     * staff endpoint — proven on list() and grant().
     */
    @Test
    void nonGroupAdminReceivesTypedShopAccess403() {
        setStrictScoping(true);
        UUID tenant = UUID.randomUUID();
        UUID nobody = UUID.randomUUID();

        authenticate(nobody, false, tenant);

        assertThatThrownBy(() -> staffManagementService.list())
                .as("non-GROUP_ADMIN cannot list staff")
                .isInstanceOf(ShopAccessDeniedException.class);

        assertThatThrownBy(() -> staffController.grant(
                new GrantStaffRequest(UUID.randomUUID(), UUID.randomUUID(), ShopRole.STAFF)))
                .as("non-GROUP_ADMIN cannot grant")
                .isInstanceOf(ShopAccessDeniedException.class);
    }

    /**
     * The list() view returns the current grants for the tenant (GROUP_ADMIN gate
     * passes for the realm-admin caller). Sanity that the read side composes.
     */
    @Test
    void listReturnsCurrentGrants() {
        UUID tenant = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        UUID shopId = seedShop(tenant);

        authenticate(admin, true, tenant);
        seedDirectory(tenant, staff);
        GrantResult r = staffManagementService.grant(staff, shopId, ShopRole.SHOP_MANAGER);
        assertThat(r.created()).isTrue();

        StaffManagementService.StaffListResponse listing = staffManagementService.list();
        assertThat(listing.grants())
                .as("the new grant appears in the list")
                .anySatisfy(g -> {
                    assertThat(g.userId()).isEqualTo(staff);
                    assertThat(g.shopId()).isEqualTo(shopId);
                    assertThat(g.role()).isEqualTo(ShopRole.SHOP_MANAGER);
                });
    }

    // ====================================================================
    // 23-09 gap-closure — CR-05 (role changes must APPLY, not silently
    // no-op while reporting success) + WR-02 (grants/changes must be audited)
    // + CR-06 (last-GROUP_ADMIN guard serialized under concurrency).
    // ====================================================================

    /**
     * CR-05 (the security-relevant case): re-granting a DIFFERENT role on an existing
     * (user, shop) MUST change the role — a downgrade takes effect instead of silently
     * no-opping while the API reports success. Asserts on the DB RE-READ, not just the
     * return value: the pre-fix bug is precisely that the return value looked right
     * (the service re-selected the stale row carrying the OLD role).
     */
    @Test
    void grantWithDifferentRoleAppliesTheChange() {
        UUID tenant = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        UUID shopId = seedShop(tenant);

        authenticate(admin, true, tenant);
        seedDirectory(tenant, staff);
        UUID grantId = staffController.grant(new GrantStaffRequest(staff, shopId, ShopRole.SHOP_MANAGER))
                .getBody().id();

        ResponseEntity<StaffMemberDto> changed =
                staffController.grant(new GrantStaffRequest(staff, shopId, ShopRole.STAFF));
        assertThat(changed.getStatusCode())
                .as("a role change is an update of the existing grant, not a fresh 201 insert")
                .isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody()).isNotNull();
        assertThat(changed.getBody().role())
                .as("the returned DTO carries the NEW (downgraded) role")
                .isEqualTo(ShopRole.STAFF);
        assertThat(changed.getBody().id())
                .as("the role change keeps the same canonical row id")
                .isEqualTo(grantId);

        assertThat(persistedRole(grantId))
                .as("the PERSISTED role is downgraded to STAFF (CR-05 — the fix)")
                .isEqualTo("STAFF");
        assertThat(shopStaffRowCount(tenant, staff, shopId))
                .as("still exactly one row — a change in place, not a second grant")
                .isEqualTo(1);
    }

    /** CR-05 companion: a same-role re-grant is an idempotent no-change replay (one row, 200). */
    @Test
    void grantWithSameRoleIsIdempotentReplay() {
        UUID tenant = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        UUID shopId = seedShop(tenant);

        authenticate(admin, true, tenant);
        seedDirectory(tenant, staff);
        UUID grantId = staffController.grant(new GrantStaffRequest(staff, shopId, ShopRole.SHOP_MANAGER))
                .getBody().id();

        ResponseEntity<StaffMemberDto> replay =
                staffController.grant(new GrantStaffRequest(staff, shopId, ShopRole.SHOP_MANAGER));
        assertThat(replay.getStatusCode())
                .as("an identical re-grant is a typed 200 replay")
                .isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().id())
                .as("the replay returns the SAME canonical grant id")
                .isEqualTo(grantId);
        assertThat(persistedRole(grantId)).isEqualTo("SHOP_MANAGER");
        assertThat(shopStaffRowCount(tenant, staff, shopId))
                .as("no duplicate row written for an identical re-grant")
                .isEqualTo(1);
    }

    /** CR-05: an UPGRADE (STAFF -> SHOP_MANAGER) applies exactly as a downgrade does. */
    @Test
    void grantUpgradeAppliesTheChange() {
        UUID tenant = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        UUID shopId = seedShop(tenant);

        authenticate(admin, true, tenant);
        seedDirectory(tenant, staff);
        UUID grantId = staffController.grant(new GrantStaffRequest(staff, shopId, ShopRole.STAFF))
                .getBody().id();

        ResponseEntity<StaffMemberDto> upgraded =
                staffController.grant(new GrantStaffRequest(staff, shopId, ShopRole.SHOP_MANAGER));
        assertThat(upgraded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(upgraded.getBody().role()).isEqualTo(ShopRole.SHOP_MANAGER);
        assertThat(persistedRole(grantId))
                .as("the PERSISTED role is upgraded to SHOP_MANAGER")
                .isEqualTo("SHOP_MANAGER");
    }

    /**
     * WR-02 proof: a grant and a subsequent role change each write a
     * {@code shop_staff_aud} Envers revision — proving the write now goes through the
     * Hibernate session, not the native {@code ON CONFLICT} insert that Envers never
     * observed (so "who granted whom GROUP_ADMIN, and when" is now answerable). ADD
     * (revtype 0) for the create, MOD (revtype 1) for the change.
     */
    @Test
    void grantWritesAnAuditRevision() {
        UUID tenant = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        UUID shopId = seedShop(tenant);

        authenticate(admin, true, tenant);
        seedDirectory(tenant, staff);
        UUID grantId = staffController.grant(new GrantStaffRequest(staff, shopId, ShopRole.SHOP_MANAGER))
                .getBody().id();
        assertThat(auditRevisions(grantId, 0))
                .as("the grant CREATE is audited (Envers ADD revision) — WR-02")
                .isGreaterThanOrEqualTo(1);

        staffController.grant(new GrantStaffRequest(staff, shopId, ShopRole.STAFF));
        assertThat(auditRevisions(grantId, 1))
                .as("the role CHANGE is audited (Envers MOD revision) — WR-02")
                .isGreaterThanOrEqualTo(1);
    }

    /**
     * Agent-readiness (WR-02 companion): a CONCURRENT duplicate grant of the identical
     * (user, shop, role) must not surface an untyped {@code DataIntegrityViolationException}
     * 500 now the write is session-based — exactly one insert wins and the other is a
     * typed replay, leaving exactly one row. Proves the REQUIRES_NEW-isolated insert +
     * catch preserves the idempotency contract the native {@code ON CONFLICT} used to give.
     */
    @Test
    void concurrentDuplicateGrantIsIdempotentNotA500() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        UUID shopId = seedShop(tenant);
        seedDirectory(tenant, staff);

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Distinct realm-admin subs per worker so the user_directory upsert in
            // onRequest() does not serialise them (see the revoke race note) — the two
            // grants then genuinely contend on the shop_staff unique index.
            java.util.function.Function<UUID, Callable<Throwable>> grantWorker = admin -> () -> {
                authenticate(admin, true, tenant);
                try {
                    gate.await();
                    staffController.grant(new GrantStaffRequest(staff, shopId, ShopRole.STAFF));
                    return null;
                } catch (Throwable t) {
                    return t;
                } finally {
                    SecurityContextHolder.clearContext();
                    TenantContext.clear();
                }
            };
            Future<Throwable> a = pool.submit(grantWorker.apply(UUID.randomUUID()));
            Future<Throwable> b = pool.submit(grantWorker.apply(UUID.randomUUID()));
            gate.countDown();

            List<Throwable> results = new ArrayList<>(2);
            results.add(a.get(30, SECONDS));
            results.add(b.get(30, SECONDS));

            assertThat(results.stream().filter(Objects::nonNull).toList())
                    .as("no concurrent duplicate grant surfaces an exception (no untyped 500)")
                    .isEmpty();
            assertThat(shopStaffRowCount(tenant, staff, shopId))
                    .as("exactly one row despite the concurrent duplicate grant")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * CR-06 (the race): two concurrent revokes of two DIFFERENT tenant-wide GROUP_ADMIN
     * rows in a two-admin tenant cannot empty the tenant of GROUP_ADMINs.
     *
     * <p>The count→delete window in {@code revoke()} is microseconds wide, so a bare
     * {@link CountDownLatch} does NOT reliably interleave the two transactions — a
     * pre-fix run frequently serialises by luck and passes (green-by-construction). To
     * make the race DETERMINISTIC in both directions, a control connection holds a
     * {@code SELECT ... FOR UPDATE} over BOTH tenant-wide GROUP_ADMIN rows and releases
     * it only once both workers are parked on it:
     * <ul>
     *   <li><b>pre-fix</b> both workers pass the (lock-free) count guard — each reads
     *       {@code count == 2} — and then block on their {@code DELETE}; on release both
     *       delete → the tenant reaches ZERO GROUP_ADMINs (the assertion below FAILS,
     *       proving the race is real);</li>
     *   <li><b>post-fix</b> both workers block earlier, on {@code lockTenantGroupAdmins}
     *       (which is itself {@code FOR UPDATE}); on release exactly one acquires it,
     *       deletes and commits, the other re-reads {@code count == 1} and 409s — the
     *       tenant retains exactly one GROUP_ADMIN.</li>
     * </ul>
     * Bounded {@code get(30, SECONDS)} so a lock-ordering mistake fails loudly.
     */
    @Test
    void concurrentRevokesCannotEmptyTheTenantOfGroupAdmins() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID gaOne = UUID.randomUUID();
        UUID gaTwo = UUID.randomUUID();

        authenticate(admin, true, tenant);
        seedDirectory(tenant, gaOne);
        seedDirectory(tenant, gaTwo);
        UUID grantOne = staffController.grant(new GrantStaffRequest(gaOne, null, ShopRole.GROUP_ADMIN))
                .getBody().id();
        UUID grantTwo = staffController.grant(new GrantStaffRequest(gaTwo, null, ShopRole.GROUP_ADMIN))
                .getBody().id();
        assertThat(tenantWideGroupAdminCount(tenant)).isEqualTo(2);
        SecurityContextHolder.clearContext();
        TenantContext.clear();

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        // Control connection: lock BOTH GROUP_ADMIN rows so the workers park at the exact
        // point that makes the race deterministic (the bootstrap role is a SUPERUSER, so
        // this plain FOR UPDATE bypasses RLS and needs no tenant GUC).
        try (Connection ctrl = jdbc.getDataSource().getConnection()) {
            ctrl.setAutoCommit(false);
            try (PreparedStatement ps = ctrl.prepareStatement(
                    "SELECT id FROM shop_staff WHERE tenant_id = ? AND role = 'GROUP_ADMIN' "
                            + "AND shop_id IS NULL FOR UPDATE")) {
                ps.setObject(1, tenant);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { /* fetch all → both rows locked */ }
                }
            }

            // Two DISTINCT realm-admin revokers: onRequest() upserts a user_directory
            // row keyed (tenant, sub); a shared sub would serialise the workers on that
            // row lock (masking the race). Distinct subs keep the ONLY contention the
            // shop_staff GROUP_ADMIN rows themselves.
            Future<Throwable> a = pool.submit(revokeWorker(UUID.randomUUID(), tenant, grantOne, gate));
            Future<Throwable> b = pool.submit(revokeWorker(UUID.randomUUID(), tenant, grantTwo, gate));
            gate.countDown();
            // Give both workers time to reach (and block on) their DB lock point.
            Thread.sleep(1000);
            // Release the barrier — both workers now proceed against the real fix (or lack of it).
            ctrl.commit();

            List<Throwable> results = new ArrayList<>(2);
            results.add(a.get(30, SECONDS));
            results.add(b.get(30, SECONDS));

            long successes = results.stream().filter(Objects::isNull).count();
            List<Throwable> failures = results.stream().filter(Objects::nonNull).toList();
            assertThat(successes).as("exactly one concurrent revoke succeeds").isEqualTo(1);
            assertThat(failures).as("exactly one concurrent revoke is blocked").hasSize(1);
            assertThat(failures.get(0))
                    .as("the blocked revoke is the typed last-GROUP_ADMIN 409")
                    .isInstanceOf(LastGroupAdminException.class);
            assertThat(tenantWideGroupAdminCount(tenant))
                    .as("the tenant is never raced to zero GROUP_ADMINs")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ====================================================================
    // 23-12 Task 1 — WR-05: grant() validates its inputs (shop tenancy +
    // user existence) with a typed 404, BEFORE the D-11 guard and any write.
    // ====================================================================

    /**
     * WR-05 (the core case): a grant naming a shop that belongs to ANOTHER tenant is
     * rejected with a typed 404 ({@link ResourceNotFoundException}) and writes NO
     * {@code shop_staff} row. A FK cannot catch this — PostgreSQL referential-integrity
     * checks BYPASS RLS, so {@code shop_staff_shop_id_fkey} happily accepts the foreign
     * shop id; only the tenant-scoped existence check does. The foreign-tenant and the
     * non-existent cases return the SAME 404 by design, so the response is not an
     * existence oracle for another tenant's shops (T-23-12-03).
     */
    @Test
    void grantForeignTenantShopIsRejectedAndWritesNoRow() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        UUID foreignShop = seedShop(tenantB);   // a real shop, but committed in tenant B

        authenticate(admin, true, tenantA);
        seedDirectory(tenantA, staff);           // the user IS a known tenant-A directory member

        assertThatThrownBy(() ->
                staffController.grant(new GrantStaffRequest(staff, foreignShop, ShopRole.SHOP_MANAGER)))
                .as("a grant naming another tenant's shop is rejected as a typed 404")
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(shopStaffRowCount(tenantA, staff, foreignShop))
                .as("no shop_staff row is written for the foreign-tenant shop (not a silent success)")
                .isZero();
    }

    /**
     * WR-05: a grant naming a {@code userId} absent from the tenant's {@code user_directory}
     * is rejected with a typed 404 and writes no row — enforcing the contract the
     * {@code GrantStaffRequest} javadoc already claimed. Grants can only target users who
     * have logged in at least once (D-09 login-populated directory).
     */
    @Test
    void grantUnknownUserIsRejected() {
        UUID tenant = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID unknownUser = UUID.randomUUID();     // never seeded into user_directory
        UUID shopId = seedShop(tenant);

        authenticate(admin, true, tenant);

        assertThatThrownBy(() ->
                staffController.grant(new GrantStaffRequest(unknownUser, shopId, ShopRole.STAFF)))
                .as("a grant naming an unknown user is rejected as a typed 404")
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(shopStaffRowCount(tenant, unknownUser, shopId))
                .as("no shop_staff row is written for the unknown user")
                .isZero();
    }

    /**
     * WR-05 (skip-path / regression guard): a tenant-wide grant ({@code shopId == null})
     * to a KNOWN directory user still succeeds — the shop tenancy check is skipped for a
     * null shop and the user check passes. Proves the new validations do not regress the
     * valid path.
     */
    @Test
    void tenantWideGrantToKnownUserSucceeds() {
        UUID tenant = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID staff = UUID.randomUUID();

        authenticate(admin, true, tenant);
        seedDirectory(tenant, staff);

        ResponseEntity<StaffMemberDto> granted =
                staffController.grant(new GrantStaffRequest(staff, null, ShopRole.GROUP_ADMIN));
        assertThat(granted.getStatusCode())
                .as("a tenant-wide grant to a known directory user succeeds")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(shopStaffRowCount(tenant, staff, null)).isEqualTo(1);
    }

    // ====================================================================
    // 23-12 Task 2 — CR-08 backend: GET /api/v1/staff/me returns the caller's
    // OWN effective access (server-authoritative), NOT GROUP_ADMIN-gated.
    // ====================================================================

    /**
     * CR-08 (the case the client gets wrong): a fully-ungranted user under strict-scoping
     * OFF is the day-one implicit GROUP_ADMIN. {@code /me} must report {@code groupAdmin=true}
     * with {@code grantedShopIds=null} (unrestricted) — NOT the empty "no shops" set — and
     * carry the caller's own {@code sub}. This is exactly what a client-side realm-role
     * parse (the user holds no {@code admin} realm role) would get WRONG.
     */
    @Test
    void myAccessReportsDayOneImplicitGroupAdmin() {
        setStrictScoping(false);
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        authenticate(user, false, tenant);   // NOT a realm-admin, holds no grants

        MyAccessDto me = staffController.myAccess().getBody();
        assertThat(me).isNotNull();
        assertThat(me.userId())
                .as("/me carries the caller's own sub")
                .isEqualTo(user);
        assertThat(me.groupAdmin())
                .as("an ungranted user under strict-scoping OFF is the day-one implicit GROUP_ADMIN")
                .isTrue();
        assertThat(me.grantedShopIds())
                .as("a GROUP_ADMIN's grantedShopIds is null (unrestricted), never an empty 'no shops' set")
                .isNull();
    }

    /**
     * CR-08: a SHOP_MANAGER scoped to one shop reports {@code groupAdmin=false} and EXACTLY
     * their granted shop ids — proving {@code /me} answers for a non-GROUP_ADMIN caller (so
     * it is provably not group-gated) and resolves the empty-set sentinel correctly.
     */
    @Test
    void myAccessReportsScopedGrantsForNonGroupAdmin() {
        setStrictScoping(false);
        UUID tenant = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        UUID shopId = seedShop(tenant);

        authenticate(admin, true, tenant);
        seedDirectory(tenant, manager);
        staffController.grant(new GrantStaffRequest(manager, shopId, ShopRole.SHOP_MANAGER));

        authenticate(manager, false, tenant);
        MyAccessDto me = staffController.myAccess().getBody();
        assertThat(me).isNotNull();
        assertThat(me.userId()).isEqualTo(manager);
        assertThat(me.groupAdmin())
                .as("a scoped SHOP_MANAGER is not a GROUP_ADMIN (proves /me is not group-gated)")
                .isFalse();
        assertThat(me.grantedShopIds())
                .as("a scoped caller sees exactly their granted shop ids")
                .containsExactly(shopId);
    }

    private Callable<Throwable> revokeWorker(UUID revoker, UUID tenant, UUID grantId, CountDownLatch gate) {
        return () -> {
            authenticate(revoker, true, tenant);
            try {
                gate.await();
                staffManagementService.revoke(grantId);
                return null;
            } catch (Throwable t) {
                return t;
            } finally {
                SecurityContextHolder.clearContext();
                TenantContext.clear();
            }
        };
    }

    /** The role persisted for a grant id (DB re-read — the return value is not trusted). */
    private String persistedRole(UUID grantId) {
        return jdbc.queryForObject("SELECT role FROM shop_staff WHERE id = ?", String.class, grantId);
    }

    /** Count of Envers {@code shop_staff_aud} revisions of a given revtype (0=ADD, 1=MOD). */
    private long auditRevisions(UUID grantId, int revtype) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff_aud WHERE id = ? AND revtype = ?",
                Long.class, grantId, revtype);
        return n == null ? 0 : n;
    }
}
