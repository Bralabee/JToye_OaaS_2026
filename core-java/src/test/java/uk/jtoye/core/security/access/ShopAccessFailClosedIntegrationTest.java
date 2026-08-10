package uk.jtoye.core.security.access;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
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
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * CR-03 + CR-04 fail-closed regression proof (gap-closure plan 23-08) against real
 * Postgres 15 via Testcontainers. Mirrors {@code ShopAccessEnforcementIntegrationTest}'s
 * scaffold and adds an {@link #authenticateRaw(String, String)} helper that presents a
 * JWT with a NON-UUID subject — the exploit token the existing helper cannot build.
 *
 * <p>What was broken (pre-23-08):
 * <ul>
 *   <li><b>CR-03</b> — {@code isSystemPrincipal()} mapped "I cannot parse this identity"
 *       (anonymous principal, non-{@code Jwt} principal, or a JWT whose {@code sub} is
 *       not a UUID) to "unrestricted GROUP_ADMIN". That path was reachable over HTTP on
 *       {@code /api/v1/staff} — a full {@code user_directory} PII read plus self-grant of
 *       any role on any shop — directly contradicting locked decision D-04.</li>
 *   <li><b>CR-04</b> — {@code require(null, role)} threw {@code NullPointerException}
 *       (HTTP 500) on {@code Product.shop_id}, which is nullable by design.</li>
 * </ul>
 *
 * <p>The seven cases prove the closed gate. Cases 1-4 and 7 are demonstrated RED against
 * the pre-fix code (see 23-08-SUMMARY); case 6 proves the explicit, empty-by-default machine
 * allowlist.
 *
 * <p><strong>Case 5 changed meaning in Phase 28 (#283).</strong> It was the preservation
 * guard for the retained {@code auth == null} internal bypass — green before AND after 23-08,
 * asserting that a no-principal caller still passed. Plan 28-06 removed that bypass: internal
 * trust is now DECLARED through {@link SystemPrincipal#asSystem}, so case 5 asserts the
 * inverse (denied undeclared, allowed declared). It is the one case here whose expected
 * outcome the fix deliberately flipped.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ShopAccessFailClosedIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final URI SHOP_ACCESS_DENIED_TYPE = URI.create("https://jtoye.uk/errors/shop-access-denied");

    @Autowired private ShopAccessService shopAccessService;
    @Autowired private StaffManagementService staffManagementService;
    @Autowired private ShopService shopService;
    @Autowired private GlobalExceptionHandler exceptionHandler;
    @Autowired private JdbcTemplate jdbc;

    private ShopAccessService targetService;

    @AfterEach
    void tearDown() {
        setStrictScoping(false);
        setMachineClientIds(Set.of());
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // --- CR-03: fail-closed on every unparseable / unexpected identity shape --------

    /**
     * Case 1 — a JWT with {@code sub = "service-account-mcp"} (not a UUID, azp not in the
     * empty-by-default allowlist) calling {@code StaffManagementService.list()} is denied
     * with the typed shop-access 403, NOT escalated to GROUP_ADMIN. Pre-fix: list()
     * returned the directory (RED).
     */
    @Test
    void nonUuidSubjectIsDeniedOnStaffList() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);

        setStrictScoping(true);
        authenticateRaw("service-account-mcp", "mcp-server");
        TenantContext.set(tenant);

        ShopAccessDeniedException denied =
                catchThrowableOfType(() -> staffManagementService.list(), ShopAccessDeniedException.class);
        assertThat(denied)
                .as("a non-UUID-subject token must be denied on the staff list, not treated as GROUP_ADMIN")
                .isNotNull();

        // The denial surfaces as the distinct typed 403 (not the generic 403, not the RLS 404).
        ProblemDetail pd = exceptionHandler.handleShopAccessDenied(denied);
        assertThat(pd.getStatus()).isEqualTo(403);
        assertThat(pd.getType()).isEqualTo(SHOP_ACCESS_DENIED_TYPE);
    }

    /**
     * Case 2 — the SAME exploit token is denied on BOTH mutating staff endpoints
     * (grant + revoke). This is the self-grant escalation path; proving the read alone
     * would miss it. Pre-fix: both mutations proceeded past the gate (RED).
     */
    @Test
    void nonUuidSubjectIsDeniedOnGrantAndRevoke() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);

        setStrictScoping(true);
        authenticateRaw("service-account-mcp", "mcp-server");
        TenantContext.set(tenant);

        assertThatThrownBy(() ->
                staffManagementService.grant(UUID.randomUUID(), UUID.randomUUID(), ShopRole.STAFF))
                .as("non-UUID-subject token cannot self-grant a role")
                .isInstanceOf(ShopAccessDeniedException.class);

        assertThatThrownBy(() -> staffManagementService.revoke(UUID.randomUUID()))
                .as("non-UUID-subject token cannot revoke")
                .isInstanceOf(ShopAccessDeniedException.class);
    }

    /**
     * Case 3 — the PII half of the finding. A {@code user_directory} row with a real
     * email exists for the tenant; the non-UUID-subject caller's {@code list()} throws
     * the typed denial, so NO directory content (PII) can be returned to it. Pre-fix:
     * list() returned the row including the email (RED).
     */
    @Test
    void nonUuidSubjectCannotReadDirectoryPii() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        seedDirectoryEntry(tenant, UUID.randomUUID(), "victim-pii@example.com", "Victim User");

        setStrictScoping(true);
        authenticateRaw("service-account-mcp", "mcp-server");
        TenantContext.set(tenant);

        // The gate denies BEFORE any user_directory read, so the caller structurally
        // cannot receive the seeded PII row — it never gets a StaffListResponse at all.
        assertThatThrownBy(() -> staffManagementService.list())
                .as("a non-UUID-subject caller must not read user_directory PII")
                .isInstanceOf(ShopAccessDeniedException.class);
    }

    /**
     * Case 4 — an {@link AnonymousAuthenticationToken} in the SecurityContext is denied,
     * not escalated. Pre-fix: a non-{@code Jwt} principal took the "no JWT principal =
     * trusted" branch and became GROUP_ADMIN (RED).
     */
    @Test
    void anonymousPrincipalIsDenied() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);

        setStrictScoping(true);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "anon-key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        TenantContext.set(tenant);

        assertThatThrownBy(() -> staffManagementService.list())
                .as("an anonymous principal must be denied, never escalated to GROUP_ADMIN")
                .isInstanceOf(ShopAccessDeniedException.class);
    }

    // --- Preservation guards: internal + declared-machine callers still work ---------

    /**
     * Case 5 — <strong>INVERTED by Phase 28 / #283.</strong> This case used to be the
     * PRESERVATION GUARD for the retained {@code auth == null} internal bypass: it asserted
     * that a gated call with the SecurityContext cleared and only {@link TenantContext} set
     * still SUCCEEDED. That bypass is the thing #283 removes, so the assertion is now the
     * other way round — an absent {@code Authentication} with no declaration is DENIED, and
     * the same call succeeds only once the caller DECLARES itself internal via
     * {@link SystemPrincipal#asSystem}.
     *
     * <p>Recorded rather than silently rewritten: this test was not "made to pass". It
     * asserted the old contract correctly and now asserts the new one, both directions in one
     * method so the change of rule is legible at the point of the change. The fuller proof
     * (typed 403 shape, scope lifetime, and #284's background-path guard) lives in
     * {@code SystemPrincipalGuardTest}.
     */
    @Test
    void absentAuthenticationIsDeniedUnlessDeclaredSystem() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);

        SecurityContextHolder.clearContext();
        TenantContext.set(tenant);

        assertThatThrownBy(() -> staffManagementService.list())
                .as("#283: no Authentication and no declaration is DENIED — trust is never inferred "
                        + "from a missing principal")
                .isInstanceOf(ShopAccessDeniedException.class);

        assertThatCode(() -> SystemPrincipal.asSystem(() -> staffManagementService.list()))
                .as("#283: an internal caller that DECLARES itself passes the gate")
                .doesNotThrowAnyException();
    }

    /**
     * Case 6 — a declared machine client passes via the EXPLICIT, empty-by-default
     * allowlist (trust is declared, never inferred). With {@code machineClientIds}
     * populated (set on the proxy-unwrapped bean, mirroring {@code setStrictScoping}),
     * a non-UUID token whose {@code azp} is allowlisted passes even under strict-scoping.
     */
    @Test
    void declaredMachineClientPasses() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);

        setStrictScoping(true);
        setMachineClientIds(Set.of("mcp-server"));
        authenticateRaw("service-account-mcp", "mcp-server");
        TenantContext.set(tenant);

        assertThatCode(() -> staffManagementService.list())
                .as("a declared (allowlisted) machine client bypasses shop-scoping — RLS still tenant-scopes it")
                .doesNotThrowAnyException();
    }

    // --- CR-04: require(null, role) is a typed 403, never an NPE ----------------------

    /**
     * Case 7 — a scoped SHOP_MANAGER calling {@code require(null, STAFF)} receives the
     * typed {@link ShopAccessDeniedException}, explicitly NOT a
     * {@link NullPointerException}. Pre-fix: {@code perShopRole().get(null)} on the
     * {@code Map.copyOf(...)} map threw NPE → HTTP 500 (RED).
     */
    @Test
    void requireNullShopThrowsTypedDenialNotNpe() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID manager = UUID.randomUUID();
        grantShopStaff(tenant, manager, shopA, "SHOP_MANAGER");

        setStrictScoping(true);
        authenticate(manager, false);
        TenantContext.set(tenant);

        assertThatThrownBy(() -> shopAccessService.require(null, ShopRole.STAFF))
                .as("require(null, role) for a scoped caller is a typed 403, never an NPE/500")
                .isInstanceOf(ShopAccessDeniedException.class)
                .isNotInstanceOf(NullPointerException.class);

        // The typed denial names the tenant-wide GROUP_ADMIN requirement (shopId == null).
        ShopAccessDeniedException denied = catchThrowableOfType(
                () -> shopAccessService.require(null, ShopRole.SHOP_MANAGER), ShopAccessDeniedException.class);
        assertThat(denied).isNotNull();
        assertThat(denied.getShopId()).as("shopId is null (tenant-wide resource)").isNull();
        assertThat(denied.getRequiredRole())
                .as("the required role is GROUP_ADMIN for a null-shop write")
                .isEqualTo(ShopRole.GROUP_ADMIN);
    }

    // --- seeding helpers (run through the real service layer as a realm-admin) --------

    private UUID seedShop(UUID tenant, String name) {
        boolean prevStrict = currentStrictScoping();
        authenticate(UUID.randomUUID(), true);
        TenantContext.set(tenant);
        try {
            CreateShopRequest req = new CreateShopRequest();
            req.setName(name);
            req.setAddress("1 Test Street, London");
            return shopService.createShop(req).getId();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
            setStrictScoping(prevStrict);
        }
    }

    /** shops/shop_staff carry an FK to {@code tenants}; seed the (RLS-free) tenant row first. */
    private void ensureTenant(UUID tenant) {
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "FailClosed Test Tenant " + tenant);
    }

    private void grantShopStaff(UUID tenant, UUID userId, UUID shopId, String role) {
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, now())",
                UUID.randomUUID(), tenant, userId, shopId, role);
    }

    private void seedDirectoryEntry(UUID tenant, UUID userId, String email, String displayName) {
        jdbc.update("INSERT INTO user_directory (tenant_id, user_id, email, display_name, last_seen) "
                        + "VALUES (?, ?, ?, ?, now()) ON CONFLICT (tenant_id, user_id) DO NOTHING",
                tenant, userId, email, displayName);
    }

    // --- auth plumbing ----------------------------------------------------------------

    /** A normal vendor-user JWT (UUID subject), optionally carrying the realm-admin authority. */
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

    /**
     * A JWT with an arbitrary (NON-UUID) subject and an {@code azp} client-id claim — the
     * exploit / machine-client token the UUID-only {@link #authenticate} helper cannot
     * build. No realm-admin authority.
     */
    private void authenticateRaw(String sub, String azp) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(sub)
                .claim("azp", azp)
                .claim("email", "machine-" + sub + "@example.com")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    // --- strict-scoping + allowlist plumbing (on the proxy-unwrapped target bean) ------

    private ShopAccessService target() {
        if (targetService == null) {
            targetService = AopTestUtils.getTargetObject(shopAccessService);
        }
        return targetService;
    }

    private void setStrictScoping(boolean value) {
        ReflectionTestUtils.setField(target(), "strictScoping", value);
    }

    private boolean currentStrictScoping() {
        return Boolean.TRUE.equals(ReflectionTestUtils.getField(target(), "strictScoping"));
    }

    private void setMachineClientIds(Set<String> ids) {
        ReflectionTestUtils.setField(target(), "machineClientIds", ids);
    }
}
