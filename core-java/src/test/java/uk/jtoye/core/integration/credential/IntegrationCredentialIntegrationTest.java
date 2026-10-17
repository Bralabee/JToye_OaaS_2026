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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    static final String REALM = "jtoye-credential-it";
    static final String PATH = "/api/v1/credentials";
    static final String SCOPE_INVALID = "https://jtoye.uk/errors/credential-scope-invalid";
    static final String ROLE_INVALID = "https://jtoye.uk/errors/credential-role-invalid";
    static final String NOT_CONFIGURED = "https://jtoye.uk/errors/credentials-not-configured";
    static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";
    static final String CREDENTIAL_REVOKED = "https://jtoye.uk/errors/credential-revoked";
    static final String NOT_FOUND = "https://jtoye.uk/errors/not-found";
    static final String HUMAN_ONLY = "https://jtoye.uk/errors/human-only-action";
    static final String MACHINE_CLIENT = "it-machine-client";
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
        // A declared machine client (CR-03): a non-UUID subject whose azp is allowlisted is an implicit Group
        // admin for the shop gate. The human-only arms prove it still cannot manage credentials or staff.
        registry.add("jtoye.access.machine-client-ids", () -> MACHINE_CLIENT);
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
    private String rotatedSecret;

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
        rotatedSecret = "fixture-only-rotated-credential-secret-" + tenantId.toString().substring(0, 8);
        when(keycloak.regenerateClientSecret(REALM, clientUuid.toString(), "tok")).thenReturn(rotatedSecret);
        when(keycloak.deleteClient(REALM, clientUuid.toString(), "tok")).thenReturn(true);
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
        // Exactly no-store, set by the endpoint itself. A contains("no-store") check could not fail: Spring
        // Security's default header writer stamps "no-cache, no-store, max-age=0, must-revalidate" on every
        // authenticated response, and the check stayed green with the endpoint's own header removed (arm A).
        // The exact value proves the endpoint does not rely on a global default someone may turn off.
        assertThat(created.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .as("a response carrying a secret must never be cached, by the endpoint's own header")
                .isEqualTo("no-store");

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

    @Test
    @DisplayName("Keycloak fails after it created the client: 503 credential-service-unavailable, the client is "
            + "deleted again, and nothing is written (no credential row, no grant, no secret anywhere)")
    void keycloakFailureAfterCreate_deletesTheClientAgain_andWritesNothing() throws Exception {
        when(keycloak.getClientSecret(REALM, clientUuid.toString(), "tok"))
                .thenThrow(new uk.jtoye.core.tenant.keycloak.KeycloakAdminException(
                        "Keycloak client secret request failed for realm=" + REALM));

        MvcResult failed = mockMvc.perform(post(PATH).with(vendorJwt(admin, tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("Till", List.of("orders:read"), "STAFF", shopA)))
                .andReturn();
        assertThat(failed.getResponse().getStatus()).as(body(failed)).isEqualTo(503);
        assertThat(json(failed).path("type").asString()).isEqualTo("https://jtoye.uk/errors/credential-service-unavailable");
        assertThat(failed.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        verify(keycloak).deleteClient(REALM, clientUuid.toString(), "tok");
        assertThat(credentialCount()).isZero();
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff WHERE tenant_id = ? AND user_id = ?", Long.class,
                tenantId, serviceAccountUser))).isZero();
    }

    // ---- Task 2: rotate, revoke, last used ------------------------------------------------------------

    @Test
    @DisplayName("D-22/D-24: rotate returns the NEW secret once (no-store), stamps who and when, and stores no secret")
    void rotate_returnsTheNewSecretOnce_andStampsWhoAndWhen() throws Exception {
        UUID credentialId = createCredentialId("Till", List.of("orders:read"), "STAFF", shopA);

        MvcResult rotated = mockMvc.perform(post(PATH + "/" + credentialId + "/rotate").with(vendorJwt(admin, tenantId)))
                .andReturn();
        assertThat(rotated.getResponse().getStatus()).as(body(rotated)).isEqualTo(200);
        assertThat(rotated.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        JsonNode json = json(rotated);
        assertThat(json.path("clientSecret").asString()).isEqualTo(rotatedSecret);
        assertThat(json.path("secretShown").asBoolean()).isTrue();
        assertThat(json.path("rotatedBy").asString()).isEqualTo(admin.toString());
        assertThat(json.path("rotatedAt").isNull()).isFalse();
        verify(keycloak).regenerateClientSecret(REALM, clientUuid.toString(), "tok");

        Map<String, Object> row = inTenant(() -> jdbc.queryForMap(
                "SELECT rotated_by, rotated_at, ic::text AS row_text FROM integration_credential ic WHERE id = ?",
                credentialId));
        assertThat(row.get("rotated_by")).isEqualTo(admin);
        assertThat(row.get("rotated_at")).isNotNull();
        assertThat((String) row.get("row_text")).as("POSITIVE CONTROL").contains(credentialId.toString());
        assertThat((String) row.get("row_text")).doesNotContain(rotatedSecret).doesNotContain(fixtureSecret);
    }

    @Test
    @DisplayName("D-22/D-24: delete removes the Keycloak client and the grant, stamps who and when, repeats as 204, "
            + "and the credential's next call is refused")
    void delete_revokesEverywhere_andTheNextCallIsRefused() throws Exception {
        UUID credentialId = createCredentialId("Till", List.of("orders:read"), "SHOP_MANAGER", shopA);
        String clientId = clientIdOf(credentialId);
        mockMvc.perform(get("/api/v1/orders/shop/" + shopA)
                        .with(serviceAccountJwt(serviceAccountUser, tenantId, clientId, "orders:read")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        MvcResult deleted = mockMvc.perform(delete(PATH + "/" + credentialId).with(vendorJwt(admin, tenantId)))
                .andReturn();
        assertThat(deleted.getResponse().getStatus()).as(body(deleted)).isEqualTo(204);
        verify(keycloak).deleteClient(REALM, clientUuid.toString(), "tok");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff WHERE tenant_id = ? AND user_id = ?", Long.class,
                tenantId, serviceAccountUser)))
                .as("the service account's grant is gone")
                .isZero();
        Map<String, Object> row = inTenant(() -> jdbc.queryForMap(
                "SELECT revoked_by, revoked_at FROM integration_credential WHERE id = ?", credentialId));
        assertThat(row.get("revoked_by")).isEqualTo(admin);
        assertThat(row.get("revoked_at")).isNotNull();

        MvcResult again = mockMvc.perform(delete(PATH + "/" + credentialId).with(vendorJwt(admin, tenantId)))
                .andReturn();
        assertThat(again.getResponse().getStatus()).as("a repeated delete: %s", body(again)).isEqualTo(204);
        verify(keycloak, times(1)).deleteClient(anyString(), anyString(), anyString());

        MvcResult refused = mockMvc.perform(get("/api/v1/orders/shop/" + shopA)
                        .with(serviceAccountJwt(serviceAccountUser, tenantId, clientId, "orders:read")))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(403);
        assertThat(json(refused).path("type").asString()).isEqualTo(CREDENTIAL_REVOKED);

        MvcResult listed = mockMvc.perform(get(PATH).with(vendorJwt(admin, tenantId))).andReturn();
        JsonNode c = nodes(json(listed).path("credentials")).get(0);
        assertThat(c.path("status").asString()).isEqualTo("REVOKED");
        assertThat(c.path("revokedBy").asString()).isEqualTo(admin.toString());
    }

    @Test
    @DisplayName("A revoked credential cannot be rotated (409 credential-revoked); an unknown id is 404 for rotate and delete")
    void rotateRevoked_is409_andUnknownIs404() throws Exception {
        UUID credentialId = createCredentialId("Till", List.of("orders:read"), "STAFF", shopA);
        mockMvc.perform(delete(PATH + "/" + credentialId).with(vendorJwt(admin, tenantId)));

        MvcResult rotateRevoked = mockMvc.perform(post(PATH + "/" + credentialId + "/rotate")
                .with(vendorJwt(admin, tenantId))).andReturn();
        assertThat(rotateRevoked.getResponse().getStatus()).as(body(rotateRevoked)).isEqualTo(409);
        assertThat(json(rotateRevoked).path("type").asString()).isEqualTo(CREDENTIAL_REVOKED);
        verify(keycloak, never()).regenerateClientSecret(anyString(), anyString(), anyString());

        UUID unknown = UUID.randomUUID();
        MvcResult rotateUnknown = mockMvc.perform(post(PATH + "/" + unknown + "/rotate")
                .with(vendorJwt(admin, tenantId))).andReturn();
        assertThat(rotateUnknown.getResponse().getStatus()).as(body(rotateUnknown)).isEqualTo(404);
        assertThat(json(rotateUnknown).path("type").asString()).isEqualTo(NOT_FOUND);
        MvcResult deleteUnknown = mockMvc.perform(delete(PATH + "/" + unknown)
                .with(vendorJwt(admin, tenantId))).andReturn();
        assertThat(deleteUnknown.getResponse().getStatus()).as(body(deleteUnknown)).isEqualTo(404);
    }

    @Test
    @DisplayName("Only a Group admin rotates or deletes: a SHOP_MANAGER gets the typed 403 and nothing changes")
    void rotateAndDelete_byNonGroupAdmin_areRefused() throws Exception {
        UUID credentialId = createCredentialId("Till", List.of("orders:read"), "STAFF", shopA);
        UUID manager = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, manager, shopA, "SHOP_MANAGER", "manager-" + manager + "@example.com");

        MvcResult rotate = mockMvc.perform(post(PATH + "/" + credentialId + "/rotate")
                .with(vendorJwt(manager, tenantId))).andReturn();
        assertThat(rotate.getResponse().getStatus()).as(body(rotate)).isEqualTo(403);
        MvcResult remove = mockMvc.perform(delete(PATH + "/" + credentialId)
                .with(vendorJwt(manager, tenantId))).andReturn();
        assertThat(remove.getResponse().getStatus()).as(body(remove)).isEqualTo(403);
        verify(keycloak, never()).regenerateClientSecret(anyString(), anyString(), anyString());
        verify(keycloak, never()).deleteClient(anyString(), anyString(), anyString());
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT revoked_at IS NULL AND rotated_at IS NULL FROM integration_credential WHERE id = ?",
                Boolean.class, credentialId))).isTrue();
    }

    @Test
    @DisplayName("last_used_at: two calls within the interval write it once; a call after the interval writes it again")
    void lastUsed_isWrittenAtMostOncePerInterval() throws Exception {
        UUID credentialId = createCredentialId("Till", List.of("orders:read"), "STAFF", shopA);
        String clientId = clientIdOf(credentialId);
        assertThat(lastUsed(credentialId)).as("never used yet").isNull();

        callAsCredential(clientId);
        java.time.OffsetDateTime first = lastUsed(credentialId);
        assertThat(first).as("the first call is recorded").isNotNull();

        callAsCredential(clientId);
        assertThat(lastUsed(credentialId)).as("a second call inside the interval writes nothing").isEqualTo(first);

        inTenant(() -> jdbc.update(
                "UPDATE integration_credential SET last_used_at = now() - interval '16 minutes' WHERE id = ?",
                credentialId));
        java.time.OffsetDateTime aged = lastUsed(credentialId);
        callAsCredential(clientId);
        assertThat(lastUsed(credentialId)).as("a call after the interval is recorded again").isAfter(aged);
    }

    // ---- Task 3: only humans manage credentials and staff (Q7, T-37-107) ------------------------------

    @Test
    @DisplayName("Q7: a credential's own token is refused 403 human-only-action on every credential endpoint and every "
            + "staff grant, revoke and invite mutation; nothing is written and Keycloak is never called again")
    void credentialToken_cannotManageCredentialsOrStaff() throws Exception {
        UUID credentialId = createCredentialId("Till", List.of("orders:write"), "SHOP_MANAGER", shopA);
        String clientId = clientIdOf(credentialId);
        var machine = serviceAccountJwt(serviceAccountUser, tenantId, clientId, "orders:write");
        UUID staffGrant = ShopGrants.grantOperator(jdbc, tenantId, UUID.randomUUID(), shopA, "STAFF", "s@example.com");

        expectHumanOnly(post(PATH).with(machine).contentType(MediaType.APPLICATION_JSON)
                .content(createBody("Minted by a machine", List.of("orders:read"), "STAFF", shopA)));
        expectHumanOnly(get(PATH).with(machine));
        expectHumanOnly(post(PATH + "/" + credentialId + "/rotate").with(machine));
        expectHumanOnly(delete(PATH + "/" + credentialId).with(machine));
        expectHumanOnly(post("/api/v1/staff/grant").with(machine).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + serviceAccountUser + "\",\"shopId\":\"" + shopB + "\",\"role\":\"SHOP_MANAGER\"}"));
        expectHumanOnly(delete("/api/v1/staff/" + staffGrant).with(machine));
        expectHumanOnly(post("/api/v1/staff/invites").with(machine).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"new.person@example.com\",\"role\":\"STAFF\",\"shopId\":\"" + shopA + "\"}"));

        verify(keycloak, times(1)).createClient(anyString(), any(), anyString());
        verify(keycloak, never()).regenerateClientSecret(anyString(), anyString(), anyString());
        verify(keycloak, never()).deleteClient(anyString(), anyString(), anyString());
        assertThat(credentialCount()).isEqualTo(1);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff WHERE tenant_id = ? AND user_id = ?", Long.class,
                tenantId, serviceAccountUser))).as("the credential did not widen its own grant").isEqualTo(1L);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff WHERE id = ?", Long.class, staffGrant))).isEqualTo(1L);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM staff_invite WHERE tenant_id = ?", Long.class, tenantId))).isZero();
    }

    @Test
    @DisplayName("Q7: a declared machine client (implicit Group admin for the shop gate) is refused human-only-action "
            + "on staff grant and on credentials")
    void declaredMachineClient_cannotManageStaffOrCredentials() throws Exception {
        UUID person = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, person, shopA, "STAFF", "person-" + person + "@example.com");
        var machine = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(j -> j.subject("service-account-" + MACHINE_CLIENT)
                        .claim("tenant_id", tenantId.toString())
                        .claim("azp", MACHINE_CLIENT));

        expectHumanOnly(post("/api/v1/staff/grant").with(machine).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + person + "\",\"shopId\":null,\"role\":\"GROUP_ADMIN\"}"));
        expectHumanOnly(post(PATH).with(machine).contentType(MediaType.APPLICATION_JSON)
                .content(createBody("Minted by a machine", List.of("orders:read"), "STAFF", shopA)));
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff WHERE tenant_id = ? AND user_id = ? AND role = 'GROUP_ADMIN'",
                Long.class, tenantId, person))).as("no machine made anyone a Group admin").isZero();
        verify(keycloak, never()).createClient(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Q7: a Group admin whose token was issued to another client (azp integration-orders-rw) is refused; "
            + "the same person through the dashboard client passes")
    void groupAdminOutsideTheDashboardClient_isRefused_andThroughItPasses() throws Exception {
        UUID person = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, person, shopA, "STAFF", "person-" + person + "@example.com");
        var viaIntegrationClient = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(j -> j.subject(admin.toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("azp", "integration-orders-rw"));
        String grant = "{\"userId\":\"" + person + "\",\"shopId\":\"" + shopB + "\",\"role\":\"STAFF\"}";

        expectHumanOnly(post("/api/v1/staff/grant").with(viaIntegrationClient)
                .contentType(MediaType.APPLICATION_JSON).content(grant));

        MvcResult viaDashboard = mockMvc.perform(post("/api/v1/staff/grant").with(vendorJwt(admin, tenantId))
                .contentType(MediaType.APPLICATION_JSON).content(grant)).andReturn();
        assertThat(viaDashboard.getResponse().getStatus()).as(body(viaDashboard)).isEqualTo(201);
    }

    @Test
    @DisplayName("Q7 / assumption-delta invariant: once a credential has called the API (its service account is now in "
            + "the staff directory), a Group admin still cannot grant it any staff access: 409 and no GROUP_ADMIN row")
    void staffGrant_toACredentialServiceAccount_isRefused() throws Exception {
        UUID credentialId = createCredentialId("Till", List.of("orders:read"), "STAFF", shopA);
        callAsCredential(clientIdOf(credentialId));
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM user_directory WHERE tenant_id = ? AND user_id = ?", Long.class,
                tenantId, serviceAccountUser)))
                .as("PRECONDITION: the credential's call put its service account in the grant-target directory")
                .isEqualTo(1L);

        for (String grant : List.of(
                "{\"userId\":\"" + serviceAccountUser + "\",\"shopId\":null,\"role\":\"GROUP_ADMIN\"}",
                "{\"userId\":\"" + serviceAccountUser + "\",\"shopId\":\"" + shopB + "\",\"role\":\"SHOP_MANAGER\"}")) {
            MvcResult refused = mockMvc.perform(post("/api/v1/staff/grant").with(vendorJwt(admin, tenantId))
                    .contentType(MediaType.APPLICATION_JSON).content(grant)).andReturn();
            assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(409);
            assertThat(json(refused).path("type").asString())
                    .isEqualTo("https://jtoye.uk/errors/staff-target-is-credential");
        }
        // The invariant the plan's assumption delta asks for, read straight from the tables.
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff s JOIN integration_credential c "
                        + "ON c.tenant_id = s.tenant_id AND c.service_account_user_id = s.user_id "
                        + "WHERE s.tenant_id = ? AND s.role = 'GROUP_ADMIN'", Long.class, tenantId)))
                .as("no credential's service account holds GROUP_ADMIN")
                .isZero();
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff WHERE tenant_id = ? AND user_id = ?", Long.class,
                tenantId, serviceAccountUser)))
                .as("the credential still holds exactly its one issued grant")
                .isEqualTo(1L);
    }

    // ---- 37-46: what the Staff page needs to show a credential as an integration -------------------

    @Test
    @DisplayName("37-46: GET carries each credential's serviceAccountUserId, so the Staff page can name the row of "
            + "its service account 'Integration: {name}' instead of an anonymous account")
    void list_namesEachCredentialsServiceAccountUser() throws Exception {
        createCredential("Till", List.of("catalog:read"), "STAFF", shopA);

        MvcResult list = mockMvc.perform(get(PATH).with(vendorJwt(admin, tenantId))).andReturn();
        assertThat(list.getResponse().getStatus()).as(body(list)).isEqualTo(200);
        JsonNode c = nodes(json(list).path("credentials")).get(0);
        assertThat(c.path("serviceAccountUserId").asString())
                .as("the list names the service-account user its grant belongs to: %s", body(list))
                .isEqualTo(serviceAccountUser.toString());
    }

    @Test
    @DisplayName("37-46: the Staff API cannot revoke a credential's grant (409 staff-target-is-credential), so a "
            + "credential can never be left ACTIVE with no access; a person's grant still revokes (204)")
    void staffRevoke_ofACredentialsGrant_isRefused_andAPersonsStillRevokes() throws Exception {
        UUID credentialId = createCredentialId("Till", List.of("orders:read"), "STAFF", shopA);
        UUID grantId = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM shop_staff WHERE tenant_id = ? AND user_id = ?", UUID.class,
                tenantId, serviceAccountUser));

        MvcResult refused = mockMvc.perform(delete("/api/v1/staff/" + grantId).with(vendorJwt(admin, tenantId)))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(409);
        assertThat(json(refused).path("type").asString())
                .isEqualTo("https://jtoye.uk/errors/staff-target-is-credential");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM shop_staff WHERE id = ?", Long.class, grantId)))
                .as("the credential keeps the grant it was issued with")
                .isEqualTo(1L);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT revoked_at IS NULL FROM integration_credential WHERE id = ?", Boolean.class, credentialId)))
                .as("and stays ACTIVE: its access is changed on the Developers page, by deleting it")
                .isTrue();

        // POSITIVE CONTROL: the refusal is about the credential, not about revoking in this tenant.
        UUID person = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, person, shopA, "STAFF", "person-" + person + "@example.com");
        UUID personGrant = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM shop_staff WHERE tenant_id = ? AND user_id = ?", UUID.class, tenantId, person));
        MvcResult revoked = mockMvc.perform(delete("/api/v1/staff/" + personGrant).with(vendorJwt(admin, tenantId)))
                .andReturn();
        assertThat(revoked.getResponse().getStatus()).as(body(revoked)).isEqualTo(204);
    }

    void expectHumanOnly(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        MvcResult refused = mockMvc.perform(request).andReturn();
        assertThat(refused.getResponse().getStatus()).as("%s %s -> %s", refused.getRequest().getMethod(),
                refused.getRequest().getRequestURI(), body(refused)).isEqualTo(403);
        assertThat(json(refused).path("type").asString()).as(body(refused)).isEqualTo(HUMAN_ONLY);
    }

    // ---- helpers --------------------------------------------------------------------------------------

    UUID createCredentialId(String name, List<String> scopes, String role, UUID shopId) throws Exception {
        MvcResult created = mockMvc.perform(post(PATH).with(vendorJwt(admin, tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(name, scopes, role, shopId)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    String clientIdOf(UUID credentialId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT kc_client_id FROM integration_credential WHERE id = ?", String.class, credentialId));
    }

    java.time.OffsetDateTime lastUsed(UUID credentialId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT last_used_at FROM integration_credential WHERE id = ?", java.time.OffsetDateTime.class,
                credentialId));
    }

    void callAsCredential(String clientId) throws Exception {
        mockMvc.perform(get("/api/v1/orders/shop/" + shopA)
                        .with(serviceAccountJwt(serviceAccountUser, tenantId, clientId, "orders:read")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

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
