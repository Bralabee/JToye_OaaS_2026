package uk.jtoye.core.integration;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.StrictScopingGuard;
import uk.jtoye.core.finance.dto.CreateTransactionRequest;
import uk.jtoye.core.finance.VatRate;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("testcontainers")
class FinancialTransactionControllerIntegrationTest {

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
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShopAccessService shopAccessService;

    private UUID testTenantId;

    @BeforeEach
    void setup() {
        testTenantId = UUID.randomUUID();
        String uniqueTenantName = "Test Tenant " + testTenantId.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?)",
                testTenantId, uniqueTenantName);

        // Clean up existing transactions
        jdbcTemplate.update("DELETE FROM financial_transactions");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        uk.jtoye.core.security.TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Phase 37-11 (RWO-027 / UXT-027): the finance gate is no longer "admin realm role or nothing".
    // With shopId the caller needs SHOP_MANAGER on that shop; without it, Group admin. STAFF is refused
    // either way (goods P2-TUN-15). The admin arms above are the Group-admin, tenant-wide half of the rule.
    // ------------------------------------------------------------------

    @Test
    void staffIsRefusedTheLedgerWithAndWithoutAShop() throws Exception {
        Object booted = StrictScopingGuard.capture(shopAccessService);
        StrictScopingGuard.set(shopAccessService, true);
        try {
            UUID shop = seedShop();
            UUID staff = UUID.randomUUID();
            grant(staff, shop, "STAFF");
            for (String path : new String[]{"/api/v1/financial-transactions/summary", "/api/v1/financial-transactions"}) {
                mockMvc.perform(get(path).param("shopId", shop.toString()).with(vendorJwt(staff)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/shop-access-denied"));
                mockMvc.perform(get(path).with(vendorJwt(staff)))
                        .andExpect(status().isForbidden());
            }
        } finally {
            StrictScopingGuard.restore(shopAccessService, booted);
        }
    }

    @Test
    void shopManagerReadsOwnShop_butIsRefusedTheTenantView() throws Exception {
        Object booted = StrictScopingGuard.capture(shopAccessService);
        StrictScopingGuard.set(shopAccessService, true);
        try {
            UUID shop = seedShop();
            UUID manager = UUID.randomUUID();
            grant(manager, shop, "SHOP_MANAGER");
            mockMvc.perform(get("/api/v1/financial-transactions/summary").param("shopId", shop.toString())
                            .with(vendorJwt(manager)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scope.shopId").value(shop.toString()));
            mockMvc.perform(get("/api/v1/financial-transactions/summary").with(vendorJwt(manager)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/shop-access-denied"));
        } finally {
            StrictScopingGuard.restore(shopAccessService, booted);
        }
    }

    private UUID seedShop() {
        UUID id = UUID.randomUUID();
        uk.jtoye.core.security.TenantContext.set(testTenantId);
        try {
            jdbcTemplate.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version) VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                    id, testTenantId, "Finance gate shop", "fin-gate-" + id.toString().substring(0, 8));
        } finally {
            uk.jtoye.core.security.TenantContext.clear();
        }
        return id;
    }

    private void grant(UUID userId, UUID shop, String role) {
        jdbcTemplate.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at, grant_source) "
                + "VALUES (?, ?, ?, ?, ?, now(), 'OPERATOR')", UUID.randomUUID(), testTenantId, userId, shop, role);
    }

    /** A vendor user with no realm role: their access comes from shop_staff alone. */
    private RequestPostProcessor vendorJwt(UUID subject) {
        UUID tenant = testTenantId;
        return jwt().jwt(j -> j.subject(subject.toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_orders:read"));
    }

    @Test
    @WithMockUser(roles = "admin")  // issue #83 P1-1: finance endpoints now require the admin realm role
    void createTransactionShouldSucceed() throws Exception {
        CreateTransactionRequest request = new CreateTransactionRequest(
                10000L,  // £100.00
                VatRate.STANDARD,
                "Test payment"
        );

        mockMvc.perform(post("/api/v1/financial-transactions")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.tenantId").value(testTenantId.toString()))
                .andExpect(jsonPath("$.amountPennies").value(10000))
                .andExpect(jsonPath("$.vatRate").value("STANDARD"))
                .andExpect(jsonPath("$.vatAmountPennies").value(1666))  // VAT fraction of VAT-inclusive gross: 10000*20/120, round down
                .andExpect(jsonPath("$.description").value("Test payment"));
    }

    @Test
    @WithMockUser(roles = "admin")  // issue #83 P1-1: finance endpoints now require the admin realm role
    void listTransactionsShouldReturnPaginatedResults() throws Exception {
        // Create test transactions
        for (int i = 1; i <= 3; i++) {
            CreateTransactionRequest request = new CreateTransactionRequest(
                    (long) (i * 1000),
                    VatRate.STANDARD,
                    "Transaction " + i
            );
            mockMvc.perform(post("/api/v1/financial-transactions")
                            .header("X-Tenant-ID", testTenantId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        // List transactions
        mockMvc.perform(get("/api/v1/financial-transactions")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    @WithMockUser(roles = "admin")  // issue #83 P1-1: finance endpoints now require the admin realm role
    void getTransactionByIdShouldReturnTransaction() throws Exception {
        // Create transaction
        CreateTransactionRequest request = new CreateTransactionRequest(
                5000L,
                VatRate.REDUCED,
                "Reduced VAT item"
        );

        String response = mockMvc.perform(post("/api/v1/financial-transactions")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String transactionId = objectMapper.readTree(response).get("id").asString();

        // Get transaction by ID
        mockMvc.perform(get("/api/v1/financial-transactions/" + transactionId)
                        .header("X-Tenant-ID", testTenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transactionId))
                .andExpect(jsonPath("$.amountPennies").value(5000))
                .andExpect(jsonPath("$.vatRate").value("REDUCED"))
                .andExpect(jsonPath("$.vatAmountPennies").value(238));  // VAT fraction of VAT-inclusive gross: 5000*5/105, round down
    }

    @Test
    @WithMockUser(roles = "admin")  // issue #83 P1-1: finance endpoints now require the admin realm role
    void createTransactionWithZeroVatShouldCalculateCorrectly() throws Exception {
        CreateTransactionRequest request = new CreateTransactionRequest(
                8000L,
                VatRate.ZERO,
                "Zero VAT item"
        );

        mockMvc.perform(post("/api/v1/financial-transactions")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountPennies").value(8000))
                .andExpect(jsonPath("$.vatRate").value("ZERO"))
                .andExpect(jsonPath("$.vatAmountPennies").value(0));  // 0% VAT
    }

    @Test
    @WithMockUser(roles = "admin")  // issue #83 P1-1: finance endpoints now require the admin realm role
    void createTransactionWithoutTenantShouldFail() throws Exception {
        CreateTransactionRequest request = new CreateTransactionRequest(
                5000L,
                VatRate.STANDARD,
                "Test transaction"
        );

        // A tenant-less request is a CLIENT error: under the test profile the
        // missing-tenant case surfaces as 400 (observed contract post QA-council
        // error-code hardening, PR #70) rather than leaking an internal 500.
        mockMvc.perform(post("/api/v1/financial-transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "admin")  // issue #83 P1-1: finance endpoints now require the admin realm role
    void createTransactionWithNullAmountShouldReturnBadRequest() throws Exception {
        CreateTransactionRequest request = new CreateTransactionRequest(
                null,  // Null amount
                VatRate.STANDARD,
                "Invalid transaction"
        );

        mockMvc.perform(post("/api/v1/financial-transactions")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
