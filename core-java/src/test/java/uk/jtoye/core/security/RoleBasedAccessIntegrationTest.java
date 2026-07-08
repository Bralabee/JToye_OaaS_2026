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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role-based access integration tests (issue #83 P1-1; threats T-rlp-01/02/03).
 *
 * <p>Exercises the REAL {@link KeycloakRealmRoleConverter} end-to-end: the MockMvc
 * {@code jwt()} post-processor bypasses the app's resource-server converter, so authorities
 * are supplied via {@code .authorities(new KeycloakRealmRoleConverter())} against a
 * {@code realm_access} claim — the same mapping production uses. This proves the
 * {@code @PreAuthorize("hasRole('admin')")} gates fire correctly against real controllers on a
 * Testcontainers Postgres (real RLS + Flyway schema), not H2.
 *
 * <p>Positive control: an admin token passes the gate (200 on the finance list; non-403 on
 * GDPR export). Negative control: a low-privilege {@code user}-only token is rejected with 403
 * on refunds, finance, and GDPR — the gate short-circuits before the service, so the nonexistent
 * IDs in the URLs never matter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class RoleBasedAccessIntegrationTest {

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

    @BeforeEach
    void setUp() {
        // Seed one tenant row so tenant-scoped reads have a valid TenantContext target,
        // mirroring CrossTenantSpoofIntegrationTest's seeding pattern.
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_A, "Tenant A");
    }

    /** admin token + tenant -> exercises the REAL converter via .authorities(...). */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt() {
        return jwt()
                .jwt(j -> j.claim("tenant_id", TENANT_A.toString())
                        .claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    /** low-privilege (user-only) token + tenant. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor userJwt() {
        return jwt()
                .jwt(j -> j.claim("tenant_id", TENANT_A.toString())
                        .claim("realm_access", Map.of("roles", List.of("user"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    // --- positive controls: admin passes the gate ---

    // NOTE on paths: WebConfig.configurePathMatch adds the /api/v1 prefix to controllers in the
    // finance and gdpr packages, so their real runtime paths are /api/v1/financial-transactions
    // and /api/v1/gdpr/customers/... . RefundController hard-codes /api/v1/orders itself. Using
    // the real paths ensures the request reaches the mapped handler (and its @PreAuthorize gate)
    // rather than 404-ing before authorization is evaluated.

    @Test
    void adminCanListFinancialTransactions() throws Exception {
        mockMvc.perform(get("/api/v1/financial-transactions").with(adminJwt()))
                .andExpect(status().isOk());
    }

    @Test
    void adminReachesGdprExportService() throws Exception {
        // Gate is passed; the customer does not exist so a non-403 (e.g. 404) is expected.
        // The assertion is only that the ROLE gate did NOT reject — i.e. status != 403.
        mockMvc.perform(get("/api/v1/gdpr/customers/{customerId}/export", UUID.randomUUID()).with(adminJwt()))
                .andExpect(not403());
    }

    // --- negative controls: low-priv token gets 403 ---

    @Test
    void lowPrivForbiddenOnFinancialTransactions() throws Exception {
        mockMvc.perform(get("/api/v1/financial-transactions").with(userJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void lowPrivForbiddenOnRefund() throws Exception {
        // Well-formed CreateRefundRequest body (reason is @NotNull) — though the gate
        // short-circuits before @Valid body binding, keep the JSON valid.
        String refundJson = "{\"amountPennies\":100,\"reason\":\"REQUESTED_BY_CUSTOMER\",\"note\":\"test\"}";
        mockMvc.perform(post("/api/v1/orders/{orderId}/refund", UUID.randomUUID())
                        .with(userJwt())
                        .contentType("application/json")
                        .content(refundJson))
                .andExpect(status().isForbidden());
    }

    @Test
    void lowPrivForbiddenOnGdprExport() throws Exception {
        mockMvc.perform(get("/api/v1/gdpr/customers/{customerId}/export", UUID.randomUUID()).with(userJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void lowPrivForbiddenOnGdprErase() throws Exception {
        mockMvc.perform(delete("/api/v1/gdpr/customers/{customerId}/erase", UUID.randomUUID()).with(userJwt()))
                .andExpect(status().isForbidden());
    }

    /**
     * Matcher for "any status except 403" — asserts the admin token passed the authorization
     * gate without pinning the downstream status (404 for an absent customer is acceptable).
     */
    private static org.springframework.test.web.servlet.ResultMatcher not403() {
        return result -> {
            int status = result.getResponse().getStatus();
            if (status == 403) {
                throw new AssertionError("Expected the admin token to pass the role gate, but got 403");
            }
        };
    }
}
