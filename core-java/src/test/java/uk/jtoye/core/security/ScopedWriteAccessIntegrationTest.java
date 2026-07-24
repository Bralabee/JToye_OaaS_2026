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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scoped write-access integration tests (Phase 25 [AI-02]; threats T-25-01/02/03).
 *
 * <p>The mutating create surfaces are least-privilege: {@code POST /api/v1/orders} is gated by
 * {@code @PreAuthorize("hasAuthority('SCOPE_orders:write')")} (D-01, activating the reserved
 * {@code orders:write} scope) and {@code POST /api/v1/customers} by
 * {@code @PreAuthorize("hasAuthority('SCOPE_customers:write')")} (D-02, a new scope). This test
 * exercises the REAL {@link JwtRolesAndScopesConverter} end-to-end: the MockMvc {@code jwt()}
 * post-processor bypasses the app's resource-server converter, so authorities are supplied via
 * {@code .authorities(new JwtRolesAndScopesConverter())} against a {@code scope} claim — the same
 * mapping production uses. This proves the gates fire against the real {@code OrderController} /
 * {@code CustomerController} on a Testcontainers Postgres (real RLS + Flyway schema), not H2.
 *
 * <p><strong>Load-bearing test-body invariant (the D-04 ordering trap):</strong> every POST body is
 * a fully valid, validation-passing request. {@code @Valid} argument resolution runs in
 * {@code InvocableHandlerMethod.getMethodArgumentValues()} BEFORE the {@code @PreAuthorize} method
 * interceptor, so an invalid body would 400 (via {@code GlobalExceptionHandler}) BEFORE the
 * authorization gate ever runs — masking the 403. Do NOT assume the authorization gate
 * short-circuits body validation; it does not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ScopedWriteAccessIntegrationTest {

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

    // Fully valid CreateOrderRequest — shopId + a non-empty items[] of {productId,quantity} so the
    // request passes @Valid and reaches (or is blocked by) the @PreAuthorize gate, never 400 first.
    // A random shopId 404s downstream via OrderService, which still satisfies not403().
    private static final String VALID_ORDER_JSON =
            "{\"shopId\":\"" + UUID.randomUUID() + "\",\"items\":[{\"productId\":\""
            + UUID.randomUUID() + "\",\"quantity\":1}]}";

    // Fully valid CreateCustomerRequest — name + email present so @Valid passes.
    private static final String VALID_CUSTOMER_JSON =
            "{\"name\":\"Ada\",\"email\":\"ada@example.com\"}";

    @BeforeEach
    void setUp() {
        // Seed one tenant row so tenant-scoped reads/writes have a valid TenantContext target,
        // mirroring ScopedCatalogAccessIntegrationTest's seeding pattern.
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_A, "Tenant A");
    }

    // Each token carries a UUID subject — the 23-08 fail-closed ShopAccessService gate denies any
    // authenticated principal whose sub is not a UUID (parseSub -> null -> typed 403). Under
    // strict-scoping OFF a UUID-subject caller is a day-one implicit GROUP_ADMIN, so the VSA-02 shop
    // gate on create_order never masks the scope assertion.

    /** No-write-scope token: scope=catalog:read only — lacks BOTH orders:write and customers:write. */
    private static RequestPostProcessor noWriteScopeJwt() {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT_A.toString())
                        .claim("scope", "catalog:read"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    /** Order-write token: scope=orders:write (as the integration-orders-rw client carries). */
    private static RequestPostProcessor ordersWriteJwt() {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT_A.toString())
                        .claim("scope", "orders:write"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    /** Customer-write token: scope=customers:write. */
    private static RequestPostProcessor customersWriteJwt() {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT_A.toString())
                        .claim("scope", "customers:write"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    // --- D-01: a no-write-scope token is FORBIDDEN on order create (orders:write gate fires) ---

    @Test
    void noScopeTokenForbiddenOnOrderCreate() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .with(noWriteScopeJwt())
                        .contentType("application/json")
                        .content(VALID_ORDER_JSON))
                .andExpect(status().isForbidden());
    }

    // --- D-02: a no-write-scope token is FORBIDDEN on customer create (customers:write gate fires) ---

    @Test
    void noScopeTokenForbiddenOnCustomerCreate() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .with(noWriteScopeJwt())
                        .contentType("application/json")
                        .content(VALID_CUSTOMER_JSON))
                .andExpect(status().isForbidden());
    }

    // --- D-01 boundary: an orders:write token is NOT rejected by the order-create gate ---

    @Test
    void writeScopedTokenNot403OnOrderCreate() throws Exception {
        // The gate must pass; downstream status (a non-existent shopId 404s) is irrelevant — assert only != 403.
        mockMvc.perform(post("/api/v1/orders")
                        .with(ordersWriteJwt())
                        .contentType("application/json")
                        .content(VALID_ORDER_JSON))
                .andExpect(not403());
    }

    // --- D-02 boundary: a customers:write token is NOT rejected by the customer-create gate ---

    @Test
    void writeScopedTokenNot403OnCustomerCreate() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .with(customersWriteJwt())
                        .contentType("application/json")
                        .content(VALID_CUSTOMER_JSON))
                .andExpect(not403());
    }

    /**
     * Matcher for "any status except 403" — asserts the write-scoped token passed the gate without
     * pinning the downstream status (a successful create is 201; a non-existent shop 404s; anything
     * non-403 proves the authorization interceptor did not reject the request).
     */
    private static org.springframework.test.web.servlet.ResultMatcher not403() {
        return result -> {
            int status = result.getResponse().getStatus();
            if (status == 403) {
                throw new AssertionError("Expected the write-scoped token to pass the write gate, but got 403");
            }
        };
    }
}
