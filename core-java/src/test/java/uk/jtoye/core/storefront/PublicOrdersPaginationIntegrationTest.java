package uk.jtoye.core.storefront;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #95 [P2-4]: the public unauthenticated customer-order lookup
 * ({@code GET /public/orders}) was unbounded — one request pulled a
 * customer's entire order history. It must now paginate, and the global
 * "max 100" page-size cap ({@code spring.data.web.pageable.max-page-size}
 * in application.yml) must clamp oversized requests end-to-end.
 *
 * <p>Full-stack proof: real HTTP through the security filter chain, the
 * shared {@code PageableHandlerMethodArgumentResolver} (the ONE place the
 * cap lives), the transaction-local {@code app.customer_email} RLS GUC, and
 * genuinely-enforced Postgres RLS — the Testcontainers bootstrap role is
 * downgraded to NOSUPERUSER after seeding, because a SUPERUSER bypasses
 * FORCE RLS entirely and would prove nothing.
 *
 * <p>RLS isolation evidence: {@code totalElements} equals EXACTLY the
 * requesting customer's order count. Another customer of the same tenant and
 * a different tenant's orders are seeded alongside; any leak would inflate
 * the count query (which runs under the same RLS policies as the page query).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class PublicOrdersPaginationIntegrationTest {

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
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static final String ALICE = "alice@pagination.test";
    private static final String BOB = "bob@pagination.test";
    private static final String CAROL = "carol@pagination.test";

    /** Alice's order count — above the 100 cap so clamping is observable. */
    private static final int ALICE_ORDERS = 105;

    /** Newest alice order — used as the mandatory 'verify' proof-of-ownership. */
    private static final String ALICE_VERIFY = "ORD-PAGE-A-000";

    private static boolean seeded = false;

    @BeforeEach
    void seedOnce() {
        if (seeded) {
            return;
        }
        UUID shopA = UUID.randomUUID();
        UUID shopB = UUID.randomUUID();

        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, NOW()) ON CONFLICT (id) DO NOTHING",
                TENANT_A, "Pagination Tenant A");
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, NOW()) ON CONFLICT (id) DO NOTHING",
                TENANT_B, "Pagination Tenant B");

        // Published shops — the storefront shop-name lookup relies on the V16
        // public read policy (published = true), which must hold under RLS.
        jdbcTemplate.update("INSERT INTO shops (id, tenant_id, name, slug, published, delivery_fee_pennies, created_at) VALUES (?, ?, ?, ?, true, 0, NOW())",
                shopA, TENANT_A, "Pagination Shop A", "pagination-shop-a");
        jdbcTemplate.update("INSERT INTO shops (id, tenant_id, name, slug, published, delivery_fee_pennies, created_at) VALUES (?, ?, ?, ?, true, 0, NOW())",
                shopB, TENANT_B, "Pagination Shop B", "pagination-shop-b");

        OffsetDateTime base = OffsetDateTime.now();
        // 105 orders for alice (tenant A) with strictly-descending createdAt
        // so "most recent first" ordering is deterministic.
        for (int i = 0; i < ALICE_ORDERS; i++) {
            insertOrder(TENANT_A, shopA, String.format("ORD-PAGE-A-%03d", i), ALICE, base.minusMinutes(i));
        }
        // Same-tenant other customer — must never appear in alice's history.
        for (int i = 0; i < 3; i++) {
            insertOrder(TENANT_A, shopA, String.format("ORD-PAGE-B-%03d", i), BOB, base.minusMinutes(i));
        }
        // Different-tenant customer — must never appear either.
        for (int i = 0; i < 2; i++) {
            insertOrder(TENANT_B, shopB, String.format("ORD-PAGE-C-%03d", i), CAROL, base.minusMinutes(i));
        }

        // Seeding ran as the Testcontainers SUPERUSER (which bypasses FORCE
        // RLS). Downgrade so every HTTP request below runs under genuinely
        // enforced RLS — the same posture as the production jtoye_app role.
        jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
        seeded = true;
    }

    private void insertOrder(UUID tenantId, UUID shopId, String orderNumber, String email, OffsetDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO orders (tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, item_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'COMPLETED', 'Pagination Customer', ?, 1000, 'STANDARD', 200, 1200, 0, 1, ?, ?)",
                tenantId, shopId, orderNumber, email, createdAt, createdAt);
    }

    @Test
    @DisplayName("size=200 is clamped to the global max of 100 — and RLS returns exactly the caller's orders")
    void oversizedPageRequest_isClampedTo100() throws Exception {
        MvcResult result = mockMvc.perform(get("/public/orders")
                        .param("email", ALICE)
                        .param("verify", ALICE_VERIFY)
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.content.length()").value(100))
                .andExpect(jsonPath("$.totalElements").value(ALICE_ORDERS))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andReturn();

        // No cross-customer / cross-tenant leakage on the page itself.
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("ORD-PAGE-B-").doesNotContain("ORD-PAGE-C-");
    }

    @Test
    @DisplayName("second page returns the remainder beyond the cap")
    void secondPage_returnsRemainder() throws Exception {
        mockMvc.perform(get("/public/orders")
                        .param("email", ALICE)
                        .param("verify", ALICE_VERIFY)
                        .param("page", "1")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(ALICE_ORDERS - 100))
                .andExpect(jsonPath("$.totalElements").value(ALICE_ORDERS));
    }

    @Test
    @DisplayName("no size param defaults to 20, most recent order first")
    void defaultPageSize_is20MostRecentFirst() throws Exception {
        mockMvc.perform(get("/public/orders")
                        .param("email", ALICE)
                        .param("verify", ALICE_VERIFY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content.length()").value(20))
                .andExpect(jsonPath("$.content[0].orderNumber").value(ALICE_VERIFY))
                .andExpect(jsonPath("$.content[0].shopName").value("Pagination Shop A"))
                .andExpect(jsonPath("$.totalElements").value(ALICE_ORDERS));
    }

    @Test
    @DisplayName("another customer of the same tenant sees only their own orders under RLS")
    void otherCustomer_seesOnlyOwnOrders() throws Exception {
        MvcResult result = mockMvc.perform(get("/public/orders")
                        .param("email", BOB)
                        .param("verify", "ORD-PAGE-B-000")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("ORD-PAGE-A-").doesNotContain("ORD-PAGE-C-");
    }
}
