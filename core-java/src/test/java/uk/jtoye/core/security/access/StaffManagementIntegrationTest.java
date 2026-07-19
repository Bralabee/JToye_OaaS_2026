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
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.StaffManagementService.GrantResult;
import uk.jtoye.core.security.access.dto.GrantStaffRequest;
import uk.jtoye.core.security.access.dto.StaffMemberDto;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

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
}
