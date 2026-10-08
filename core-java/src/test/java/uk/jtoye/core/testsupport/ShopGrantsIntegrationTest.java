package uk.jtoye.core.testsupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Proof for {@link ShopGrants} (Phase 37-02, threat T-37-03): the helper seeds access THROUGH the
 * row-level-security policies, on a role that cannot bypass them, and the grant it writes is one the
 * shop-access gate honours under strict-scoping ON.
 *
 * <p>The Testcontainers bootstrap role is a SUPERUSER, which bypasses even FORCE RLS, so a helper that
 * "works" against it proves nothing about the pin. This class downgrades that role to NOSUPERUSER once
 * per container (the {@code TraderIdentityRlsIntegrationTest} idiom) and asserts the precondition before
 * every test. Then:
 * <ul>
 *   <li>the grant and directory rows are visible with the tenant pinned and invisible without it (the
 *       policies are in force on this connection);</li>
 *   <li>the same INSERT with no tenant pinned, or pinned to another tenant, is REFUSED (42501) — the fail
 *       direction: the pin is what makes the write legal;</li>
 *   <li>under strict ON the gate grants the seeded shop and denies an ungranted shop and an ungranted user.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ShopGrantsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String DOWNGRADED_APP_ROLE = "test";
    private static final AtomicBoolean DOWNGRADED = new AtomicBoolean(false);
    private static final String RAW_GRANT = "INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, grant_source, created_by) "
            + "VALUES (?, ?, ?, ?, 'STAFF', 'OPERATOR', ?)";

    @Autowired private ShopAccessService shopAccessService;
    @Autowired private ShopService shopService;
    @Autowired private JdbcTemplate jdbc;

    private Object bootedStrictScoping;

    @BeforeEach
    void setUp() {
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
        TenantContext.clear();
        // Only a superuser may run ALTER ROLE, so this happens exactly once per container.
        if (DOWNGRADED.compareAndSet(false, true)) {
            assertThat(postgres.getUsername()).isEqualTo(DOWNGRADED_APP_ROLE);
            jdbc.execute("ALTER ROLE \"" + DOWNGRADED_APP_ROLE + "\" NOSUPERUSER");
        }
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = ?", Boolean.class,
                DOWNGRADED_APP_ROLE))
                .as("PRECONDITION: the seeding role must not bypass row-level security")
                .isFalse();
    }

    @AfterEach
    void tearDown() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void grantOperator_writesThroughRls_andTheGateHonoursItUnderStrictOn() {
        UUID tenant = newTenant();
        UUID shopA = seedShop(tenant, "Grant Shop A");
        UUID shopB = seedShop(tenant, "Grant Shop B");
        UUID manager = UUID.randomUUID();

        UUID grantId = ShopGrants.grantOperator(jdbc, tenant, manager, shopA, "SHOP_MANAGER", "mgr@example.com");

        Map<String, Object> row = inTenant(tenant, () -> jdbc.queryForMap(
                "SELECT user_id, shop_id, role, grant_source, created_by FROM shop_staff WHERE id = ?", grantId));
        assertThat(row.get("user_id")).isEqualTo(manager);
        assertThat(row.get("shop_id")).isEqualTo(shopA);
        assertThat(row.get("role")).isEqualTo("SHOP_MANAGER");
        assertThat(row.get("grant_source")).as("an OPERATOR grant, which strict ON keeps honouring").isEqualTo("OPERATOR");
        assertThat(row.get("created_by")).isEqualTo(ShopGrants.TEST_OPERATOR);
        assertThat(inTenant(tenant, () -> jdbc.queryForObject(
                "SELECT count(*) FROM user_directory WHERE tenant_id = ? AND user_id = ?", Long.class, tenant, manager)))
                .as("the directory row StaffManagementService.grant requires")
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_staff WHERE id = ?", Long.class, grantId))
                .as("with no tenant pinned the same row is invisible — the policies are in force here")
                .isZero();

        StrictScopingGuard.set(shopAccessService, true);
        TenantContext.set(tenant);
        assertThat(shopAccessService.canAccessShop(tenant, manager, false, shopA))
                .as("the seeded OPERATOR grant is honoured under strict ON").isTrue();
        assertThat(shopAccessService.canAccessShop(tenant, manager, false, shopB))
                .as("and confines the user to the granted shop").isFalse();
        assertThat(shopAccessService.canAccessShop(tenant, UUID.randomUUID(), false, shopA))
                .as("an ungranted user has no access under strict ON").isFalse();
    }

    @Test
    void theSameInsertWithoutTheTenantPinned_isRefusedByRls() {
        UUID tenant = newTenant();
        UUID other = newTenant();
        UUID shop = seedShop(tenant, "Unpinned Shop");

        DataAccessException unpinned = catchThrowableOfType(() -> jdbc.update(RAW_GRANT,
                UUID.randomUUID(), tenant, UUID.randomUUID(), shop, ShopGrants.TEST_OPERATOR), DataAccessException.class);
        assertThat(unpinned).as("an INSERT with no tenant GUC must be refused on a NOSUPERUSER role").isNotNull();
        assertThat(sqlState(unpinned)).isEqualTo("42501");

        DataAccessException foreign = catchThrowableOfType(() -> inTenant(other, () -> jdbc.update(RAW_GRANT,
                UUID.randomUUID(), tenant, UUID.randomUUID(), shop, ShopGrants.TEST_OPERATOR)), DataAccessException.class);
        assertThat(foreign).as("an INSERT pinned to another tenant must be refused").isNotNull();
        assertThat(sqlState(foreign)).isEqualTo("42501");

        // Positive control: the identical statement succeeds once the row's own tenant is pinned.
        assertThat(inTenant(tenant, () -> jdbc.update(RAW_GRANT,
                UUID.randomUUID(), tenant, UUID.randomUUID(), shop, ShopGrants.TEST_OPERATOR)))
                .isEqualTo(1);
    }

    // --- plumbing ----------------------------------------------------------

    private UUID newTenant() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "ShopGrants Tenant " + tenant);
        return tenant;
    }

    private UUID seedShop(UUID tenant, String name) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("email", "operator@example.com")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_admin"))));
        TenantContext.set(tenant);
        try {
            CreateShopRequest req = new CreateShopRequest();
            req.setName(name);
            req.setAddress("1 Test Street, London");
            return shopService.createShop(req).getId();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /** One JDBC transaction with the tenant GUC pinned on the connection the JdbcTemplate uses. */
    private <T> T inTenant(UUID tenant, Supplier<T> work) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(s -> {
            jdbc.queryForObject("SELECT set_config('app.current_tenant_id', ?, true)", String.class, tenant.toString());
            return work.get();
        });
    }

    private static String sqlState(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
