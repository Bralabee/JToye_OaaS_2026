package uk.jtoye.core.integration.credential;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminClient;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminProperties;
import uk.jtoye.core.tenant.keycloak.KeycloakClientSpec;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;
import uk.jtoye.core.testsupport.ShopGrants;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static uk.jtoye.core.testsupport.TenantJwts.serviceAccountJwt;
import static uk.jtoye.core.testsupport.TenantJwts.vendorJwt;

/**
 * UXT-035 / RWO-035 (D-22, D-23, D-24; Phase 37-45): a Group admin issues a scoped integrator credential
 * instead of sharing the owner's password, and the credential is limited exactly like a person.
 *
 * <p>Keycloak is a Mockito double of {@link KeycloakAdminClient}: 37-44's {@code KeycloakAdminClientTest}
 * proves the HTTP shape of every call, and the live Keycloak proof is the 37-E gate (37-47). Everything
 * else is real: Postgres 15 with the Flyway schema, the security filter chain, the shop-access gate, the
 * exception handler and the idempotency store. The service-account token is built in the shape 37-44's
 * client mints ({@link uk.jtoye.core.testsupport.TenantJwts#serviceAccountJwt}): no realm roles, no email,
 * no preferred_username.
 *
 * <p>Non-default config on purpose: the vendor realm is {@code jtoye-credential-it}, so a credential
 * service that ignored {@code jtoye.keycloak.admin.vendor-realm} misses every stub and reds the test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class IntegrationCredentialIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    static final String REALM = "jtoye-credential-it";
    static final String PATH = "/api/v1/credentials";
    static final String SCOPE_INVALID = "https://jtoye.uk/errors/credential-scope-invalid";
    static final String ROLE_INVALID = "https://jtoye.uk/errors/credential-role-invalid";
    static final String NOT_CONFIGURED = "https://jtoye.uk/errors/credentials-not-configured";
    static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";
    static final List<String> SIX_SCOPES = List.of("catalog:read", "catalog:write", "orders:read",
            "orders:write", "customers:read", "customers:write");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // The Keycloak admin seam is ON (the client itself is a mock), with a non-default vendor realm.
        registry.add("jtoye.keycloak.admin.enabled", () -> "true");
        registry.add("jtoye.keycloak.admin.base-url", () -> "http://keycloak.invalid:8080");
        registry.add("jtoye.keycloak.admin.password", () -> "not-a-real-admin-password");
        registry.add("jtoye.keycloak.admin.vendor-realm", () -> REALM);
    }

    @MockitoBean private KeycloakAdminClient keycloak;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private KeycloakAdminProperties keycloakProperties;

    private UUID tenantId;
    private UUID shopA;
    private UUID shopB;
    private UUID admin;
    /** Per test, so a value seen in one arm can never satisfy another arm's scan. */
    private String fixtureSecret;
    private UUID clientUuid;
    private UUID serviceAccountUser;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())",
                tenantId, "Credential IT " + tenantId.toString().substring(0, 8));
        shopA = seedShop(tenantId, "Credential Shop A");
        shopB = seedShop(tenantId, "Credential Shop B");
        admin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");

        fixtureSecret = "fixture-only-not-a-real-credential-secret-" + tenantId.toString().substring(0, 8);
        clientUuid = UUID.randomUUID();
        serviceAccountUser = UUID.randomUUID();
        when(keycloak.obtainAdminToken()).thenReturn("tok");
        when(keycloak.createClient(eq(REALM), any(KeycloakClientSpec.class), eq("tok")))
                .thenReturn(clientUuid.toString());
        when(keycloak.getServiceAccountUserId(REALM, clientUuid.toString(), "tok"))
                .thenReturn(serviceAccountUser.toString());
        when(keycloak.getClientSecret(REALM, clientUuid.toString(), "tok")).thenReturn(fixtureSecret);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- Task 1 (tracer): a Group admin issues a credential that is limited like a person ----------

    @Test
    @DisplayName("UXT-035: a Group admin creates a SHOP_MANAGER credential on shop A; the secret is shown once with "
            + "no-store, Keycloak gets the tenant and scopes, and the service-account user holds an OPERATOR grant on A")
    void groupAdmin_createsCredential_secretShownOnce_andTheServiceAccountIsGrantedLikeAPerson() throws Exception {
        MvcResult created = mockMvc.perform(post(PATH).with(vendorJwt(admin, tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Till", List.of("catalog:read", "orders:write"), "SHOP_MANAGER", shopA)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
        assertThat(created.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .as("a response carrying a secret must never be cached")
                .contains("no-store");

        JsonNode json = json(created);
        String clientId = json.path("clientId").asString();
        assertThat(clientId).as("the clientId is generated server-side").matches("jtoye-int-[a-z2-7]{12}");
        assertThat(json.path("clientSecret").asString()).isEqualTo(fixtureSecret);
        assertThat(json.path("secretShown").asBoolean()).isTrue();
        assertThat(json.path("tokenEndpoint").asString()).endsWith("/protocol/openid-connect/token");
        assertThat(json.path("name").asString()).isEqualTo("Till");
        assertThat(json.path("role").asString()).isEqualTo("SHOP_MANAGER");
        assertThat(json.path("shopId").asString()).isEqualTo(shopA.toString());
        assertThat(json.path("createdBy").asString()).isEqualTo(admin.toString());
        assertThat(json.path("status").asString()).isEqualTo("ACTIVE");
        List<String> scopes = strings(json.path("scopes"));
        assertThat(scopes).containsExactly("catalog:read", "orders:write");
        UUID credentialId = UUID.fromString(json.path("id").asString());

        ArgumentCaptor<KeycloakClientSpec> spec = ArgumentCaptor.forClass(KeycloakClientSpec.class);
        verify(keycloak).createClient(eq(REALM), spec.capture(), eq("tok"));
        assertThat(spec.getValue().clientId()).isEqualTo(clientId);
        assertThat(spec.getValue().tenantId()).as("the issuing tenant is the caller's").isEqualTo(tenantId);
        assertThat(spec.getValue().scopes()).containsExactly("catalog:read", "orders:write");

        Map<String, Object> grant = inTenant(() -> jdbc.queryForMap(
                "SELECT shop_id, role, grant_source, created_by FROM shop_staff WHERE tenant_id = ? AND user_id = ?",
                tenantId, serviceAccountUser));
        assertThat(grant.get("shop_id")).isEqualTo(shopA);
        assertThat(grant.get("role")).isEqualTo("SHOP_MANAGER");
        assertThat(grant.get("grant_source")).isEqualTo("OPERATOR");
        assertThat(grant.get("created_by")).as("who issued it is recorded on the grant").isEqualTo(admin);

        Map<String, Object> row = inTenant(() -> jdbc.queryForMap(
                "SELECT tenant_id, name, kc_client_id, kc_client_uuid, service_account_user_id, shop_id, role, "
                        + "created_by, created_at, revoked_at FROM integration_credential WHERE id = ?", credentialId));
        assertThat(row.get("tenant_id")).isEqualTo(tenantId);
        assertThat(row.get("kc_client_id")).isEqualTo(clientId);
        assertThat(row.get("kc_client_uuid")).isEqualTo(clientUuid);
        assertThat(row.get("service_account_user_id")).isEqualTo(serviceAccountUser);
        assertThat(row.get("shop_id")).isEqualTo(shopA);
        assertThat(row.get("role")).isEqualTo("SHOP_MANAGER");
        assertThat(row.get("created_by")).isEqualTo(admin);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("revoked_at")).isNull();

        // T-37-108: the secret is never persisted. The whole row rendered as text, every column, must not
        // contain it; the positive control proves the scan reads a populated row.
        String rowText = inTenant(() -> jdbc.queryForObject(
                "SELECT ic::text FROM integration_credential ic WHERE id = ?", String.class, credentialId));
        assertThat(rowText).as("POSITIVE CONTROL: the scanned row is the credential").contains(clientId);
        assertThat(rowText).as("no column of the credential row holds the secret").doesNotContain(fixtureSecret);
    }

    @Test
    @DisplayName("UXT-035: the credential's own token reads shop A's orders (its grant) and is refused shop B (no grant)")
    void credentialToken_isLimitedToItsGrant() throws Exception {
        String clientId = createCredential("Till", List.of("orders:read"), "SHOP_MANAGER", shopA);

        mockMvc.perform(get("/api/v1/orders/shop/" + shopA)
                        .with(serviceAccountJwt(serviceAccountUser, tenantId, clientId, "orders:read")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        MvcResult refused = mockMvc.perform(get("/api/v1/orders/shop/" + shopB)
                        .with(serviceAccountJwt(serviceAccountUser, tenantId, clientId, "orders:read")))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(403);
        assertThat(json(refused).path("type").asString()).isEqualTo(SHOP_ACCESS_DENIED);
    }

    @Test
    @DisplayName("Q7: a credential can never hold GROUP_ADMIN (400 credential-role-invalid); Keycloak is never called")
    void groupAdminRole_isRefused() throws Exception {
        MvcResult refused = mockMvc.perform(post(PATH).with(vendorJwt(admin, tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Root", List.of("orders:read"), "GROUP_ADMIN", null)))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(400);
        assertThat(json(refused).path("type").asString()).isEqualTo(ROLE_INVALID);
        verify(keycloak, never()).createClient(anyString(), any(), anyString());
        assertThat(credentialCount()).isZero();
    }

    @Test
    @DisplayName("Q7: a scope outside the six #206 scopes is refused 400 credential-scope-invalid naming the allowed list")
    void scopeOutsideTheSix_isRefused() throws Exception {
        MvcResult refused = mockMvc.perform(post(PATH).with(vendorJwt(admin, tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Roles", List.of("orders:read", "roles"), "STAFF", shopA)))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(400);
        JsonNode problem = json(refused);
        assertThat(problem.path("type").asString()).isEqualTo(SCOPE_INVALID);
        assertThat(strings(problem.path("invalidScopes")))
                .containsExactly("roles");
        assertThat(strings(problem.path("allowedScopes")))
                .containsExactlyElementsOf(SIX_SCOPES);
        verify(keycloak, never()).createClient(anyString(), any(), anyString());
        assertThat(credentialCount()).isZero();
    }

    @Test
    @DisplayName("Only a Group admin issues credentials: a SHOP_MANAGER gets the typed 403 and Keycloak is never called")
    void nonGroupAdmin_isRefused() throws Exception {
        UUID manager = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, manager, shopA, "SHOP_MANAGER", "manager-" + manager + "@example.com");
        MvcResult refused = mockMvc.perform(post(PATH).with(vendorJwt(manager, tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Mine", List.of("orders:read"), "STAFF", shopA)))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(403);
        assertThat(json(refused).path("type").asString()).isEqualTo(SHOP_ACCESS_DENIED);
        verify(keycloak, never()).createClient(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("#102 precedent: with the Keycloak admin seam not configured, create and list are the typed 400")
    void seamNotConfigured_isTyped400() throws Exception {
        keycloakProperties.setEnabled(false);
        try {
            MvcResult create = mockMvc.perform(post(PATH).with(vendorJwt(admin, tenantId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("Off", List.of("orders:read"), "STAFF", shopA)))
                    .andReturn();
            assertThat(create.getResponse().getStatus()).as(body(create)).isEqualTo(400);
            assertThat(json(create).path("type").asString()).isEqualTo(NOT_CONFIGURED);

            MvcResult list = mockMvc.perform(get(PATH).with(vendorJwt(admin, tenantId))).andReturn();
            assertThat(list.getResponse().getStatus()).as(body(list)).isEqualTo(400);
            assertThat(json(list).path("type").asString()).isEqualTo(NOT_CONFIGURED);
        } finally {
            keycloakProperties.setEnabled(true);
        }
        verify(keycloak, never()).createClient(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Idempotency-Key: a same-key replay is 200 with the credential and secretShown false; the secret "
            + "is never stored in idempotency_keys and Keycloak is called once")
    void idempotentReplay_returnsTheCredentialWithoutTheSecret() throws Exception {
        String key = "cred-" + UUID.randomUUID();
        String payload = createBody("Till", List.of("orders:read"), "STAFF", shopA);

        MvcResult first = mockMvc.perform(post(PATH).with(vendorJwt(admin, tenantId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn();
        assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(201);
        assertThat(json(first).path("clientSecret").asString()).isEqualTo(fixtureSecret);

        MvcResult replay = mockMvc.perform(post(PATH).with(vendorJwt(admin, tenantId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn();
        assertThat(replay.getResponse().getStatus()).as(body(replay)).isEqualTo(200);
        JsonNode replayed = json(replay);
        assertThat(replayed.path("id").asString()).isEqualTo(json(first).path("id").asString());
        assertThat(replayed.path("clientId").asString()).isEqualTo(json(first).path("clientId").asString());
        assertThat(replayed.path("secretShown").asBoolean()).isFalse();
        assertThat(replayed.path("clientSecret").isNull() || replayed.path("clientSecret").isMissingNode())
                .as("a replay never re-displays the secret: %s", body(replay))
                .isTrue();
        assertThat(body(replay)).doesNotContain(fixtureSecret);
        verify(keycloak, times(1)).createClient(eq(REALM), any(KeycloakClientSpec.class), eq("tok"));
        assertThat(credentialCount()).isEqualTo(1);

        Map<String, Object> stored = inTenant(() -> jdbc.queryForMap(
                "SELECT response_status, response_body, k::text AS row_text FROM idempotency_keys k "
                        + "WHERE tenant_id = ? AND idempotency_key = ?", tenantId, key));
        assertThat(stored.get("response_status")).as("POSITIVE CONTROL: the reservation completed").isEqualTo(201);
        assertThat(stored.get("response_body")).as("the response (with its secret) is never stored").isNull();
        assertThat((String) stored.get("row_text")).doesNotContain(fixtureSecret);
    }

    @Test
    @DisplayName("GET lists the credential with its scopes, shop, role, issuer and the last-used throttle interval")
    void list_showsTheCredential() throws Exception {
        String clientId = createCredential("Till", List.of("catalog:read", "orders:write"), "SHOP_MANAGER", shopA);

        MvcResult list = mockMvc.perform(get(PATH).with(vendorJwt(admin, tenantId))).andReturn();
        assertThat(list.getResponse().getStatus()).as(body(list)).isEqualTo(200);
        JsonNode listed = json(list);
        assertThat(listed.path("lastUsedIntervalMinutes").asLong()).isEqualTo(15L);
        List<JsonNode> credentials = nodes(listed.path("credentials"));
        assertThat(credentials).hasSize(1);
        JsonNode c = credentials.get(0);
        assertThat(c.path("clientId").asString()).isEqualTo(clientId);
        assertThat(c.path("name").asString()).isEqualTo("Till");
        assertThat(c.path("role").asString()).isEqualTo("SHOP_MANAGER");
        assertThat(c.path("shopId").asString()).isEqualTo(shopA.toString());
        assertThat(c.path("createdBy").asString()).isEqualTo(admin.toString());
        assertThat(c.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(c.has("clientSecret")).as("the list never carries a secret").isFalse();
        assertThat(body(list)).doesNotContain(fixtureSecret);
    }

    // ---- helpers --------------------------------------------------------------------------------------

    String createCredential(String name, List<String> scopes, String role, UUID shopId) throws Exception {
        MvcResult created = mockMvc.perform(post(PATH).with(vendorJwt(admin, tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(name, scopes, role, shopId)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
        return json(created).path("clientId").asString();
    }

    String createBody(String name, List<String> scopes, String role, UUID shopId) throws Exception {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", name);
        body.put("scopes", scopes);
        body.put("role", role);
        body.put("shopId", shopId == null ? null : shopId.toString());
        return jsonMapper.writeValueAsString(body);
    }

    long credentialCount() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM integration_credential WHERE tenant_id = ?", Long.class, tenantId));
    }

    UUID seedShop(UUID tenant, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, address, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, true, 0, 0, 0)",
                id, tenant, name + " " + id.toString().substring(0, 4), "cred-" + id, "1 Test Street, London, E1 6AN");
        return id;
    }

    /** One transaction with this test's tenant pinned, so the read goes THROUGH the FORCE RLS policy. */
    <T> T inTenant(Supplier<T> work) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(s -> {
            jdbc.queryForObject("SELECT set_config('app.current_tenant_id', ?, true)", String.class,
                    tenantId.toString());
            return work.get();
        });
    }

    JsonNode json(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    static List<JsonNode> nodes(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.values().spliterator(), false).toList();
    }

    static List<String> strings(JsonNode array) {
        return nodes(array).stream().map(JsonNode::asString).toList();
    }

    static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }
}
