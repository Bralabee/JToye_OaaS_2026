package uk.jtoye.core.integration.credential;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminClient;
import uk.jtoye.core.tenant.keycloak.KeycloakClientSpec;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static uk.jtoye.core.testsupport.TenantJwts.serviceAccountJwt;
import static uk.jtoye.core.testsupport.TenantJwts.vendorJwt;

/**
 * T-37-109 (D-22..D-24, Phase 37-45): the credential register is walled per tenant by the DATABASE, not only
 * by the service. Postgres 15 with every migration, the application's connection role downgraded to
 * NOSUPERUSER so FORCE row-level security is genuinely enforced, the real filter chain behind MockMvc, and
 * Keycloak as a Mockito double.
 *
 * <p>One arm each, every refusal beside a control that proves the same statement CAN succeed:
 * <ul>
 *   <li>the whole create path works under the NOSUPERUSER role (credential row and OPERATOR grant written
 *       through FORCE RLS), and the credential's token then reads its shop;</li>
 *   <li>under tenant A's GUC the database hides B's credential, an UPDATE of it matches 0 rows, and an INSERT
 *       stamped with another tenant is refused SQLSTATE 42501;</li>
 *   <li>the API never crosses tenants: B's Group admin does not see A's credential and cannot rotate or delete
 *       it (non-disclosing 404, Keycloak never called);</li>
 *   <li>the last-used recorder writes through FORCE RLS: a bare write with no tenant pinned would match zero
 *       rows and report success (the V25/V44/V57 trap), so last_used_at turning non-null under this role is
 *       the proof its own transaction pins the tenant.</li>
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional}: every seed and read runs in its own transaction with the GUC
 * pinned on the SAME connection ({@code Session.doWork}), the {@code TraderIdentityRlsIntegrationTest} recipe.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class IntegrationCredentialRlsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    static final String REALM = "jtoye-credential-rls-it";
    static final String PATH = "/api/v1/credentials";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("jtoye.keycloak.admin.enabled", () -> "true");
        registry.add("jtoye.keycloak.admin.base-url", () -> "http://keycloak.invalid:8080");
        registry.add("jtoye.keycloak.admin.password", () -> "not-a-real-admin-password");
        registry.add("jtoye.keycloak.admin.vendor-realm", () -> REALM);
    }

    private static final String DOWNGRADED_APP_ROLE = "test";
    private static final AtomicBoolean DOWNGRADED = new AtomicBoolean(false);

    @MockitoBean private KeycloakAdminClient keycloak;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        if (DOWNGRADED.compareAndSet(false, true)) {
            assertThat(postgres.getUsername())
                    .as("the role this test downgrades must be the one it names")
                    .isEqualTo(DOWNGRADED_APP_ROLE);
            jdbc.execute("ALTER ROLE \"" + DOWNGRADED_APP_ROLE + "\" NOSUPERUSER");
        }
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = ?", Boolean.class,
                DOWNGRADED_APP_ROLE))
                .as("PRECONDITION: every assertion below is meaningless if the role still bypasses row-level security")
                .isFalse();
        when(keycloak.obtainAdminToken()).thenReturn("tok");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- 1. The create path works through FORCE RLS ------------------------------------------------

    @Test
    @DisplayName("NOSUPERUSER: a Group admin issues a credential; the row and the OPERATOR grant are written through "
            + "FORCE RLS and the credential's token reads its shop")
    void createWorksUnderForceRls() throws Exception {
        Seed a = seedTenantWithAdminAndShop();
        Issued issued = issue(a);

        assertThat(this.<Long>inTenant(a.tenant, c -> queryLong(c,
                "SELECT count(*) FROM integration_credential WHERE id = ?::uuid", issued.id.toString())))
                .isEqualTo(1L);
        assertThat(this.<Long>inTenant(a.tenant, c -> queryLong(c,
                "SELECT count(*) FROM shop_staff WHERE user_id = ?::uuid AND shop_id = ?::uuid "
                        + "AND role = 'STAFF' AND grant_source = 'OPERATOR'",
                issued.serviceAccountUser.toString(), a.shop.toString())))
                .isEqualTo(1L);
        mockMvc.perform(get("/api/v1/orders/shop/" + a.shop)
                        .with(serviceAccountJwt(issued.serviceAccountUser, a.tenant, issued.clientId, "orders:read")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    // ---- 2. The database wall, under A's GUC ------------------------------------------------------

    @Test
    @DisplayName("NOSUPERUSER under A's GUC: A's credential visible, B's hidden, UPDATE of B matches 0, INSERT as another tenant refused 42501")
    void databaseRefusesCrossTenantReadAndWrite() throws Exception {
        Seed a = seedTenantWithAdminAndShop();
        Seed b = seedTenantWithAdminAndShop();
        Issued credA = issue(a);
        Issued credB = issue(b);

        assertThat(this.<Long>inTenant(a.tenant, c -> queryLong(c,
                "SELECT count(*) FROM integration_credential WHERE id = ?::uuid", credA.id.toString())))
                .as("PRECONDITION: A's session sees A's own credential, so the zeros below are the wall")
                .isEqualTo(1L);
        assertThat(this.<Long>inTenant(a.tenant, c -> queryLong(c,
                "SELECT count(*) FROM integration_credential WHERE id = ?::uuid", credB.id.toString())))
                .as("A's session cannot see B's credential")
                .isZero();
        assertThat(this.<Long>inTenant(a.tenant, c -> queryLong(c,
                "SELECT count(*) FROM integration_credential WHERE kc_client_id = ?", credB.clientId)))
                .as("not even by B's clientId")
                .isZero();

        assertThat(this.<Integer>inTenant(a.tenant, c -> executeUpdate(c,
                "UPDATE integration_credential SET name = name WHERE id = ?::uuid", credA.id.toString())))
                .as("POSITIVE CONTROL: the same UPDATE matches A's own row")
                .isEqualTo(1);
        assertThat(this.<Integer>inTenant(a.tenant, c -> executeUpdate(c,
                "UPDATE integration_credential SET name = 'Hijacked by A' WHERE id = ?::uuid", credB.id.toString())))
                .as("an UPDATE of B's credential under A's GUC matches zero rows")
                .isZero();
        assertThat(this.<String>inTenant(b.tenant, c -> queryString(c,
                "SELECT name FROM integration_credential WHERE id = ?::uuid", credB.id.toString())))
                .as("B's credential is untouched, read under B's own GUC")
                .isEqualTo("Till");

        assertThat(this.<Integer>inTenant(a.tenant, c -> executeUpdate(c, insertSql(),
                UUID.randomUUID().toString(), a.tenant.toString(), "jtoye-int-ctrl" + shortId(), a.admin.toString())))
                .as("POSITIVE CONTROL: an INSERT stamped with the session's own tenant is accepted")
                .isEqualTo(1);
        SQLException refusal = this.<SQLException>inTenant(a.tenant, c -> refusalOf(c, insertSql(),
                UUID.randomUUID().toString(), b.tenant.toString(), "jtoye-int-forg" + shortId(), a.admin.toString()));
        assertThat((Throwable) refusal)
                .as("an INSERT stamped with another tenant under A's GUC must be REFUSED by the database")
                .isNotNull();
        assertThat(refusal.getSQLState()).as("SQLSTATE (message: %s)", refusal.getMessage()).isEqualTo("42501");
        assertThat(refusal.getMessage()).contains("row-level security");
    }

    // ---- 3. The API never crosses tenants ---------------------------------------------------------

    @Test
    @DisplayName("API: B's Group admin does not list A's credential and cannot rotate or delete it (404, Keycloak never called)")
    void apiNeverCrossesTenants() throws Exception {
        Seed a = seedTenantWithAdminAndShop();
        Seed b = seedTenantWithAdminAndShop();
        Issued credA = issue(a);

        MvcResult listA = mockMvc.perform(get(PATH).with(vendorJwt(a.admin, a.tenant))).andReturn();
        assertThat(ids(listA)).as("POSITIVE CONTROL: A lists its credential").contains(credA.id.toString());
        MvcResult listB = mockMvc.perform(get(PATH).with(vendorJwt(b.admin, b.tenant))
                .header("X-Tenant-Id", a.tenant.toString())).andReturn();
        assertThat(listB.getResponse().getStatus()).isEqualTo(200);
        assertThat(ids(listB)).as("B never sees A's credential, even naming A in the tenant header")
                .doesNotContain(credA.id.toString());

        MvcResult rotate = mockMvc.perform(post(PATH + "/" + credA.id + "/rotate")
                .with(vendorJwt(b.admin, b.tenant))).andReturn();
        assertThat(rotate.getResponse().getStatus()).as(rotate.getResponse().getContentAsString()).isEqualTo(404);
        MvcResult remove = mockMvc.perform(delete(PATH + "/" + credA.id)
                .with(vendorJwt(b.admin, b.tenant))).andReturn();
        assertThat(remove.getResponse().getStatus()).as(remove.getResponse().getContentAsString()).isEqualTo(404);
        verify(keycloak, never()).regenerateClientSecret(anyString(), anyString(), anyString());
        verify(keycloak, never()).deleteClient(anyString(), anyString(), anyString());
        assertThat(this.<String>inTenant(a.tenant, c -> queryString(c,
                "SELECT coalesce(revoked_at::text, 'active') FROM integration_credential WHERE id = ?::uuid",
                credA.id.toString())))
                .isEqualTo("active");
    }

    // ---- 4. last_used_at is written through FORCE RLS ---------------------------------------------

    @Test
    @DisplayName("NOSUPERUSER: the credential's call stamps last_used_at through FORCE RLS (its own transaction pins the tenant)")
    void lastUsedIsWrittenUnderForceRls() throws Exception {
        Seed a = seedTenantWithAdminAndShop();
        Issued issued = issue(a);
        assertThat(this.<String>inTenant(a.tenant, c -> queryString(c,
                "SELECT last_used_at::text FROM integration_credential WHERE id = ?::uuid", issued.id.toString())))
                .as("never used yet")
                .isNull();

        mockMvc.perform(get("/api/v1/orders/shop/" + a.shop)
                        .with(serviceAccountJwt(issued.serviceAccountUser, a.tenant, issued.clientId, "orders:read")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        assertThat(this.<String>inTenant(a.tenant, c -> queryString(c,
                "SELECT last_used_at::text FROM integration_credential WHERE id = ?::uuid", issued.id.toString())))
                .as("the call was recorded under the NOSUPERUSER role")
                .isNotNull();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    record Seed(UUID tenant, UUID admin, UUID shop) {
    }

    record Issued(UUID id, String clientId, UUID serviceAccountUser) {
    }

    private Issued issue(Seed seed) throws Exception {
        UUID clientUuid = UUID.randomUUID();
        UUID serviceAccountUser = UUID.randomUUID();
        when(keycloak.createClient(eq(REALM), any(KeycloakClientSpec.class), eq("tok"))).thenReturn(clientUuid.toString());
        when(keycloak.getServiceAccountUserId(REALM, clientUuid.toString(), "tok")).thenReturn(serviceAccountUser.toString());
        when(keycloak.getClientSecret(REALM, clientUuid.toString(), "tok"))
                .thenReturn("fixture-only-rls-credential-secret-" + shortId());
        MvcResult created = mockMvc.perform(post(PATH).with(vendorJwt(seed.admin, seed.tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Till\",\"scopes\":[\"orders:read\"],\"role\":\"STAFF\",\"shopId\":\""
                                + seed.shop + "\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as(created.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode json = jsonMapper.readTree(created.getResponse().getContentAsString());
        return new Issued(UUID.fromString(json.path("id").asString()), json.path("clientId").asString(),
                serviceAccountUser);
    }

    private java.util.List<String> ids(MvcResult result) throws Exception {
        JsonNode credentials = jsonMapper.readTree(result.getResponse().getContentAsString()).path("credentials");
        return StreamSupport.stream(credentials.values().spliterator(), false)
                .map(n -> n.path("id").asString()).toList();
    }

    private Seed seedTenantWithAdminAndShop() {
        UUID tenant = UUID.randomUUID();
        // tenants carries no RLS.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "cred-rls-" + tenant);
        UUID shop = UUID.randomUUID();
        this.<Integer>inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, 'Test Address', false, 0)",
                shop.toString(), tenant.toString(), "shop-" + shop, "cred-rls-" + shop));
        UUID admin = UUID.randomUUID();
        this.<Integer>inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, grant_source) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, NULL, 'GROUP_ADMIN', 'OPERATOR')",
                UUID.randomUUID().toString(), tenant.toString(), admin.toString()));
        return new Seed(tenant, admin, shop);
    }

    private static String insertSql() {
        return "INSERT INTO integration_credential (id, tenant_id, name, kc_client_id, kc_client_uuid, "
                + "service_account_user_id, scopes, role, created_by) "
                + "VALUES (?::uuid, ?::uuid, 'Direct', ?, gen_random_uuid(), gen_random_uuid(), "
                + "ARRAY['orders:read'], 'STAFF', ?::uuid)";
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private interface ConnectionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /** One fresh transaction with the tenant GUC pinned on the Hibernate transaction's own connection. */
    private <T> T inTenant(UUID tenant, ConnectionWork<T> work) {
        return new TransactionTemplate(txManager).execute(s ->
                entityManager.unwrap(Session.class).doReturningWork(connection -> {
                    try (PreparedStatement pin = connection.prepareStatement(
                            "SELECT set_config('app.current_tenant_id', ?, true)")) {
                        pin.setString(1, tenant.toString());
                        pin.execute();
                    }
                    return work.run(connection);
                }));
    }

    private static long queryLong(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String queryString(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static int executeUpdate(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /**
     * Runs a write that is expected to be refused and returns the refusal (null if it succeeded). The statement
     * runs inside a savepoint so the refusal does not poison the transaction.
     */
    private static SQLException refusalOf(Connection c, String sql, Object... params) throws SQLException {
        var savepoint = c.setSavepoint();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
            c.rollback(savepoint);
            return null;
        } catch (SQLException e) {
            c.rollback(savepoint);
            return e;
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
