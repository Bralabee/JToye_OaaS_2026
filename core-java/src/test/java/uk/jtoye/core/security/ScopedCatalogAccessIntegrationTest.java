package uk.jtoye.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scoped catalog-access integration tests (issue #206 [AI-4]; threat T-2g8-01).
 *
 * <p>Exercises the REAL {@link JwtRolesAndScopesConverter} end-to-end: the MockMvc
 * {@code jwt()} post-processor bypasses the app's resource-server converter, so authorities
 * are supplied via {@code .authorities(new JwtRolesAndScopesConverter())} against a
 * {@code scope} claim — the same mapping production uses. This proves the new
 * {@code @PreAuthorize("hasAuthority('SCOPE_catalog:write')")} gates fire correctly against
 * the real {@code ProductController} on a Testcontainers Postgres (real RLS + Flyway schema),
 * not H2.
 *
 * <p><strong>Load-bearing test-body invariant:</strong> every POST body is a fully valid,
 * validation-passing {@link uk.jtoye.core.product.dto.CreateProductRequest} carrying ALL FIVE
 * constrained fields (sku, title, ingredientsText, allergenMask, pricePennies). {@code @Valid}
 * argument resolution runs in {@code InvocableHandlerMethod.getMethodArgumentValues()} BEFORE
 * the {@code @PreAuthorize} method interceptor, so an invalid body would 400 (via
 * {@code GlobalExceptionHandler}) BEFORE the authorization gate ever runs — masking the 403.
 * Do NOT assume the authorization gate short-circuits body validation; it does not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ScopedCatalogAccessIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // Fully valid CreateProductRequest — ALL FIVE constrained fields present so the request
    // passes @Valid and reaches (or is blocked by) the @PreAuthorize gate, never 400 first.
    private static final String VALID_PRODUCT_JSON =
            "{\"sku\":\"SCOPE-SKU-1\",\"title\":\"Scope Test\",\"ingredientsText\":\"Water\","
            + "\"allergenMask\":0,\"pricePennies\":999}";

    @BeforeEach
    void setUp() {
        // Seed one tenant row so tenant-scoped reads/writes have a valid TenantContext target,
        // mirroring RoleBasedAccessIntegrationTest's seeding pattern.
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_A, "Tenant A");
    }

    // Each token carries a UUID subject — the 23-08 fail-closed ShopAccessService gate denies
    // any authenticated principal whose sub is not a UUID (parseSub -> null -> typed 403). The
    // pre-Phase-23 default MockMvc subject ("user") tripped that gate BEFORE the scope contract
    // under test could be exercised. The scope-gate semantics are unchanged: the write gate is
    // driven by the `scope` claim via JwtRolesAndScopesConverter, and under strict-scoping OFF a
    // UUID-subject caller is a day-one implicit GROUP_ADMIN, so the shop gate never masks the
    // scope assertion.

    /** Read-only machine token: scope=catalog:read, no realm role. */
    private static RequestPostProcessor readOnlyJwt() {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT_A.toString())
                        .claim("scope", "catalog:read"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    /** Operator-shaped token: scope=catalog:read catalog:write (as core-api grants by default). */
    private static RequestPostProcessor operatorJwt() {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT_A.toString())
                        .claim("scope", "catalog:read catalog:write"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    /** Legacy/stale token shape: authenticated + tenant but carries NO scope claim at all. */
    private static RequestPostProcessor noScopeJwt() {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT_A.toString()))
                .authorities(new JwtRolesAndScopesConverter());
    }

    // --- AC-1a: read-only scope lists products (authenticated-only read surface) ---

    @Test
    void readOnlyScopeCanListProducts() throws Exception {
        mockMvc.perform(get("/api/v1/products").with(readOnlyJwt()))
                .andExpect(status().isOk());
    }

    // --- AC-1b: read-only scope is FORBIDDEN on create (write gate fires) ---

    @Test
    void readOnlyScopeForbiddenOnCreate() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .with(readOnlyJwt())
                        .contentType("application/json")
                        .content(VALID_PRODUCT_JSON))
                .andExpect(status().isForbidden());
    }

    // --- write surface consistently gated: DELETE also 403 for read-only scope ---

    @Test
    void readOnlyScopeForbiddenOnDelete() throws Exception {
        mockMvc.perform(delete("/api/v1/products/{id}", UUID.randomUUID()).with(readOnlyJwt()))
                .andExpect(status().isForbidden());
    }

    // --- AC-2 boundary: operator-scoped token is NOT rejected by the write gate ---

    @Test
    void operatorScopeNotForbiddenOnCreate() throws Exception {
        // The gate must pass; downstream status (201/other) is irrelevant — assert only != 403.
        mockMvc.perform(post("/api/v1/products")
                        .with(operatorJwt())
                        .contentType("application/json")
                        .content(VALID_PRODUCT_JSON))
                .andExpect(not403());
    }

    // --- explicit stale-token contract: a scopeless token 403s on writes until re-login ---

    @Test
    void noScopeTokenForbiddenOnCreate() throws Exception {
        // Fail-closed migration posture (same as #87/#88): tokens minted before the realm
        // re-import lack catalog:write and are DENIED product writes. Asserted, not accidental.
        mockMvc.perform(post("/api/v1/products")
                        .with(noScopeJwt())
                        .contentType("application/json")
                        .content(VALID_PRODUCT_JSON))
                .andExpect(status().isForbidden());
    }

    /**
     * Matcher for "any status except 403" — asserts the operator token passed the write gate
     * without pinning the downstream status (a successful create is 201; anything non-403 proves
     * the authorization interceptor did not reject the request).
     */
    private static org.springframework.test.web.servlet.ResultMatcher not403() {
        return result -> {
            int status = result.getResponse().getStatus();
            if (status == 403) {
                throw new AssertionError("Expected the operator-scoped token to pass the write gate, but got 403");
            }
        };
    }
}
