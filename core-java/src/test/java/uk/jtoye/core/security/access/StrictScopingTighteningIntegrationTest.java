package uk.jtoye.core.security.access;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CR-07 central proof (gap-closure plan 23-14): enabling {@code strict-scoping} MUST
 * genuinely tighten access for a tenant with existing day-one usage. Before this plan,
 * D-04's JIT lazy-provision wrote a real, persistent tenant-wide GROUP_ADMIN row for
 * every user's first write request, and {@code resolveMembership} honoured every such
 * row unconditionally — so flipping {@code ACCESS_STRICT_SCOPING=true} changed NOTHING
 * for anyone who had already made a request. The switch was an off-ramp that did not
 * lead anywhere.
 *
 * <p>The revised semantics proven here (against real Postgres 15 via Testcontainers):
 * <ul>
 *   <li>strict ON de-honours a JIT-sourced tenant-wide GROUP_ADMIN — the user becomes
 *       scoped and is denied shop-scoped calls (the central CR-07 case);</li>
 *   <li>an OPERATOR-sourced tenant-wide GROUP_ADMIN is honoured unchanged;</li>
 *   <li>lockout safety: when a tenant's ONLY tenant-wide GROUP_ADMINs are JIT-sourced,
 *       the deterministic OLDEST is retained as the bootstrap admin (never zero admins);</li>
 *   <li>WR-09: a declared machine client no longer accumulates a JIT GROUP_ADMIN row;</li>
 *   <li>day-one preservation: with strict OFF nothing changes — JIT rows still honoured;</li>
 *   <li>the STOMP ladder ({@code canAccessShop}) tightens too, via the shared decision helper.</li>
 * </ul>
 *
 * <p>Harness mirrors {@code ShopAccessJitProvisionTest}: NOT {@code @Transactional} (seeded
 * grants must genuinely COMMIT so a separate-transaction gate call observes them), fresh
 * random {@code (tenant, sub)} per test, and the strict-scoping flag + machine allowlist
 * toggled on the {@link AopTestUtils}-unwrapped target bean. Tenant-wide GROUP_ADMIN rows
 * have a NULL {@code shop_id} (no FK) so no {@code tenants}/{@code shops} seeding is needed.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class StrictScopingTighteningIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private ShopAccessService shopAccessService;
    @Autowired private JdbcTemplate jdbc;

    private ShopAccessService targetService;

    @AfterEach
    void tearDown() {
        setStrictScoping(false);
        setMachineClientIds(Set.of());
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // --- CR-07 central case: strict ON de-honours JIT tenant-wide GROUP_ADMINs --------

    /**
     * The central proof (must FAIL pre-fix): seed one OPERATOR GROUP_ADMIN and TWO JIT
     * GROUP_ADMINs under strict OFF, flip strict ON, and assert both JIT users are now
     * SCOPED — denied on a shop-scoped {@code require()} and no longer group admins —
     * while the OPERATOR user keeps GROUP_ADMIN. The operator admin means neither JIT
     * user is the bootstrap admin, so both are fully de-honoured.
     */
    @Test
    void strictOn_deHonoursJitGroupAdmins_operatorGrantHonoured() {
        UUID tenant = UUID.randomUUID();
        UUID operator = UUID.randomUUID();
        UUID jitA = UUID.randomUUID();
        UUID jitB = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        seedTenantWideGroupAdmin(tenant, operator, "OPERATOR", now.minusDays(3));
        seedTenantWideGroupAdmin(tenant, jitA, "JIT", now.minusDays(2));
        seedTenantWideGroupAdmin(tenant, jitB, "JIT", now.minusDays(1));

        setStrictScoping(true);

        // Both JIT users are de-honoured: no longer group admin, and denied a shop-scoped call.
        for (UUID jit : List.of(jitA, jitB)) {
            authenticate(jit, false);
            TenantContext.set(tenant);
            assertThat(shopAccessService.isGroupAdmin())
                    .as("a JIT-sourced GROUP_ADMIN is de-honoured under strict-scoping ON")
                    .isFalse();
            assertThatThrownBy(() -> shopAccessService.require(UUID.randomUUID(), ShopRole.STAFF))
                    .as("the de-honoured JIT user is now scoped and denied a shop-scoped call")
                    .isInstanceOf(ShopAccessDeniedException.class);
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }

        // The deliberate operator grant is honoured, unchanged.
        authenticate(operator, false);
        TenantContext.set(tenant);
        assertThat(shopAccessService.isGroupAdmin())
                .as("an OPERATOR-sourced GROUP_ADMIN is honoured under strict-scoping ON")
                .isTrue();
    }

    /**
     * Lockout safety: when a tenant's ONLY tenant-wide GROUP_ADMINs are JIT-sourced, flipping
     * strict ON must retain the deterministic OLDEST (by created_at) as the bootstrap admin so
     * the tenant is never left with zero admins — every younger JIT admin is de-honoured.
     */
    @Test
    void strictOn_retainsOldestJitAsBootstrap_whenAllGroupAdminsAreJit() {
        UUID tenant = UUID.randomUUID();
        UUID oldest = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        OffsetDateTime base = OffsetDateTime.now().minusDays(10);
        seedTenantWideGroupAdmin(tenant, oldest, "JIT", base);
        seedTenantWideGroupAdmin(tenant, middle, "JIT", base.plusDays(1));
        seedTenantWideGroupAdmin(tenant, newest, "JIT", base.plusDays(2));

        setStrictScoping(true);

        assertThat(isGroupAdminAs(oldest, tenant))
                .as("the oldest JIT GROUP_ADMIN is retained as the bootstrap admin (no zero-admin lockout)")
                .isTrue();
        assertThat(isGroupAdminAs(middle, tenant))
                .as("a younger JIT GROUP_ADMIN is de-honoured")
                .isFalse();
        assertThat(isGroupAdminAs(newest, tenant))
                .as("the newest JIT GROUP_ADMIN is de-honoured")
                .isFalse();
    }

    /**
     * Day-one preservation: with strict OFF (the default) a JIT-sourced GROUP_ADMIN is honoured
     * exactly as before, and a fully-ungranted user is still the day-one implicit GROUP_ADMIN —
     * byte-for-byte unchanged.
     */
    @Test
    void strictOff_dayOneUnchanged() {
        UUID tenant = UUID.randomUUID();
        UUID jit = UUID.randomUUID();
        seedTenantWideGroupAdmin(tenant, jit, "JIT", OffsetDateTime.now().minusDays(1));

        setStrictScoping(false);

        assertThat(isGroupAdminAs(jit, tenant))
                .as("strict OFF: a JIT GROUP_ADMIN is honoured (day-one preserved)")
                .isTrue();

        // A fully-ungranted user under strict OFF is the day-one implicit GROUP_ADMIN.
        // FC-1: require() now checks the named shop is in the caller's tenant, so seed a real
        // in-tenant shop (a random/non-existent id would now be correctly denied and stop
        // exercising the day-one implicit-GROUP_ADMIN path this asserts).
        UUID shopId = seedTenantAndShop(tenant);
        UUID ungranted = UUID.randomUUID();
        authenticate(ungranted, false);
        TenantContext.set(tenant);
        assertThatCode(() -> shopAccessService.require(shopId, ShopRole.GROUP_ADMIN))
                .as("strict OFF: an ungranted user is the day-one implicit GROUP_ADMIN")
                .doesNotThrowAnyException();
    }

    // --- WR-09: a declared machine client no longer accumulates a JIT GROUP_ADMIN row ---

    /**
     * WR-09: a declared machine client (a Keycloak service account whose {@code sub} is a UUID
     * but whose {@code azp} is on the allowlist) must NOT be JIT-provisioned a persistent
     * tenant-wide GROUP_ADMIN row on a write-capable request — those rows survived the
     * strict-scoping flip and showed up in the staff list as opaque UUIDs. Its access still
     * works through the allowlist; it simply stops accumulating grants by accident.
     */
    @Test
    void declaredMachineClient_isNotJitProvisioned() {
        UUID tenant = UUID.randomUUID();
        UUID machineSub = UUID.randomUUID();   // Keycloak service accounts carry a UUID sub

        setStrictScoping(false);               // strict OFF is exactly when JIT would fire
        setMachineClientIds(Set.of("mcp-server"));
        authenticateMachine(machineSub, "mcp-server");
        TenantContext.set(tenant);

        // A write-capable gate call would JIT-provision a normal user here.
        shopAccessService.grantedShopIds();

        assertThat(tenantWideGroupAdminCount(tenant, machineSub))
                .as("a declared machine client accumulates NO JIT GROUP_ADMIN row (WR-09)")
                .isZero();
    }

    // --- STOMP ladder (canAccessShop) tightens through the shared decision helper -------

    /**
     * The STOMP shop-read gate ({@code canAccessShop}, 23-11) funnels through the same
     * {@code isGroupAdminForUser} decision helper as the HTTP boundary, so strict-scoping
     * tightens BOTH transports at once. A JIT admin is denied the shop feed under strict ON;
     * an operator admin is permitted.
     */
    @Test
    void stompLadder_tightensToo() {
        UUID tenant = UUID.randomUUID();
        UUID operator = UUID.randomUUID();
        UUID jit = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        seedTenantWideGroupAdmin(tenant, operator, "OPERATOR", now.minusDays(2));
        seedTenantWideGroupAdmin(tenant, jit, "JIT", now.minusDays(1));

        setStrictScoping(true);
        TenantContext.set(tenant);   // canAccessShop asserts the pinned tenant equals its arg

        UUID anyShop = UUID.randomUUID();
        assertThat(shopAccessService.canAccessShop(tenant, jit, false, anyShop))
                .as("strict ON: a de-honoured JIT admin cannot read a shop feed over STOMP either")
                .isFalse();
        assertThat(shopAccessService.canAccessShop(tenant, operator, false, anyShop))
                .as("strict ON: an operator admin still reads any shop feed over STOMP")
                .isTrue();
    }

    // --- helpers ----------------------------------------------------------------------

    /** Authenticate as {@code sub}, pin {@code tenant}, and answer isGroupAdmin(); then clear. */
    private boolean isGroupAdminAs(UUID sub, UUID tenant) {
        authenticate(sub, false);
        TenantContext.set(tenant);
        try {
            return shopAccessService.isGroupAdmin();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * FC-1 (QA-council): a GROUP_ADMIN's require() now verifies the named shop belongs to the
     * caller's tenant (a tenant-wide GROUP_ADMIN is not cross-tenant). Seed a real tenant + shop
     * so a day-one implicit GROUP_ADMIN passes on a shop it genuinely owns; a random
     * (non-existent) shop id would now be correctly denied and no longer exercise this path.
     */
    private UUID seedTenantAndShop(UUID tenant) {
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "strict-tenant-" + tenant);
        UUID shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, true, 0)",
                shopId, tenant, "shop-" + shopId, "slug-" + shopId, "1 Test Street, London");
        return shopId;
    }

    /** Seed a committed tenant-wide GROUP_ADMIN row with an explicit provenance + created_at. */
    private void seedTenantWideGroupAdmin(UUID tenant, UUID user, String grantSource, OffsetDateTime createdAt) {
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, grant_source, created_at) "
                        + "VALUES (?, ?, ?, NULL, 'GROUP_ADMIN', ?, ?)",
                UUID.randomUUID(), tenant, user, grantSource, createdAt);
    }

    private long tenantWideGroupAdminCount(UUID tenant, UUID user) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM shop_staff "
                + "WHERE tenant_id = ? AND user_id = ? AND role = 'GROUP_ADMIN' AND shop_id IS NULL",
                Long.class, tenant, user);
        return n == null ? 0 : n;
    }

    /** A normal vendor-user JWT (UUID subject), optionally realm-admin. */
    private void authenticate(UUID sub, boolean realmAdmin) {
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
    }

    /** A Keycloak service-account JWT: a UUID subject PLUS an {@code azp} client id (WR-09). */
    private void authenticateMachine(UUID sub, String azp) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(sub.toString())
                .claim("azp", azp)
                .claim("email", "svc-" + sub + "@example.com")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
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

    private void setMachineClientIds(Set<String> ids) {
        ReflectionTestUtils.setField(target(), "machineClientIds", ids);
    }
}
