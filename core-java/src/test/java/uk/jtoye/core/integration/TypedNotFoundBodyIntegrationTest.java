package uk.jtoye.core.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.JwtRolesAndScopesConverter;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #500 — every 404 this API emits carries an RFC 7807 body, not a bare status.
 *
 * <p><strong>The defect.</strong> Twelve controller sites answered
 * {@code ResponseEntity.notFound().build()}: an empty-bodied 404 with no {@code type}, no stable
 * error code and no detail. #500 named three of them in {@code CustomerController}; the {@code rg -uu}
 * sweep it asked for found nine more, in {@code ProductController} (3),
 * {@code OrderController} (2), and one each in {@code Shop}, {@code Announcement},
 * {@code Promotion} and {@code FinancialTransaction}. All twelve are covered below.
 *
 * <p><strong>Why the assertion is on the BODY.</strong> Every one of these routes already answered
 * <b>404</b> before the fix — a status-only assertion passes on the unfixed tree and proves
 * nothing. What changed is the payload, so that is what is asserted: a {@code type} of
 * {@code .../errors/not-found}, and — the part that actually fails on the old code — a
 * {@code Content-Type} of {@code application/problem+json}, which an empty response cannot have.
 *
 * <p><strong>Falsifiability.</strong> Run against the unfixed tree, all twelve arms fail on the
 * content type ({@code Content type not set}) rather than on the status. Recorded in the commit
 * that introduces this class.
 *
 * <p><strong>Control arm.</strong> {@code liveRowStillAnswers200} is the other half: without it,
 * making every one of these routes throw unconditionally would pass this suite. It proves the
 * success path is untouched — a real customer still reads back 200 with its own body, so the
 * change narrowed nothing.
 *
 * <p>Harness mirrors {@code MarketingMissingRowStatusIntegrationTest}: real Postgres 15 + the
 * Flyway-managed RLS schema, NOT {@code @Transactional} (each request owns its transaction exactly
 * as in production), fresh random tenant per test.
 *
 * <p>Note on the gated routes: {@code @PreAuthorize} runs BEFORE the method body, so the four
 * mutation arms carry the scope their gate requires ({@code catalog:write} /
 * {@code customers:write}). Without it they would answer 403 and never reach the code under test —
 * the same ordering trap {@code ScopedWriteAccessIntegrationTest} documents.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class TypedNotFoundBodyIntegrationTest {

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
    @Autowired private JdbcTemplate jdbc;

    private static final String NOT_FOUND_TYPE = "https://jtoye.uk/errors/not-found";
    private static final String PROBLEM_JSON = "application/problem+json";

    // ------------------------------------------------------------------
    // CustomerController — the three sites #500 named
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /customers/{unknown} carries a typed problem body")
    void getCustomer_unknownId_isTypedProblem() throws Exception {
        assertTypedNotFound(get(customerPath()).with(vendor(seedTenant())));
    }

    @Test
    @DisplayName("PUT /customers/{unknown} carries a typed problem body")
    void updateCustomer_unknownId_isTypedProblem() throws Exception {
        assertTypedNotFound(put(customerPath())
                .with(customersWrite(seedTenant()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(customerJson()));
    }

    @Test
    @DisplayName("DELETE /customers/{unknown} carries a typed problem body")
    void deleteCustomer_unknownId_isTypedProblem() throws Exception {
        assertTypedNotFound(delete(customerPath()).with(customersWrite(seedTenant())));
    }

    // ------------------------------------------------------------------
    // The nine further sites the sweep found — same class, same treatment
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /products/{unknown} carries a typed problem body")
    void getProduct_unknownId_isTypedProblem() throws Exception {
        assertTypedNotFound(get(path("products")).with(vendor(seedTenant())));
    }

    @Test
    @DisplayName("PUT /products/{unknown} carries a typed problem body")
    void updateProduct_unknownId_isTypedProblem() throws Exception {
        assertTypedNotFound(put(path("products"))
                .with(catalogWrite(seedTenant()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson()));
    }

    @Test
    @DisplayName("DELETE /products/{unknown} carries a typed problem body")
    void deleteProduct_unknownId_isTypedProblem() throws Exception {
        assertTypedNotFound(delete(path("products")).with(catalogWrite(seedTenant())));
    }

    @Test
    @DisplayName("GET /orders/{unknown} carries a typed problem body")
    void getOrder_unknownId_isTypedProblem() throws Exception {
        assertTypedNotFound(get(path("orders")).with(vendor(seedTenant())));
    }

    @Test
    @DisplayName("GET /orders/{unknown}/detail carries a typed problem body")
    void getOrderDetail_unknownId_isTypedProblem() throws Exception {
        assertTypedNotFound(get("/api/v1/orders/" + UUID.randomUUID() + "/detail")
                .with(vendor(seedTenant())));
    }

    @Test
    @DisplayName("GET /shops/{unknown} carries a typed problem body")
    void getShop_unknownId_isTypedProblem() throws Exception {
        assertTypedNotFound(get(path("shops")).with(vendor(seedTenant())));
    }

    @Test
    @DisplayName("GET /announcements/{unknown} carries a typed problem body")
    void getAnnouncement_unknownId_isTypedProblem() throws Exception {
        assertTypedNotFound(get(path("announcements")).with(vendor(seedTenant())));
    }

    @Test
    @DisplayName("GET /promotions/{unknown} carries a typed problem body")
    void getPromotion_unknownId_isTypedProblem() throws Exception {
        assertTypedNotFound(get(path("promotions")).with(vendor(seedTenant())));
    }

    @Test
    @DisplayName("GET /financial-transactions/{unknown} carries a typed problem body")
    void getFinancialTransaction_unknownId_isTypedProblem() throws Exception {
        // FinancialTransactionController is @PreAuthorize("hasRole('admin')") at CLASS level
        // (issue #83 P1-1), so the ledger needs the realm role — a plain vendor gets 403 and
        // never reaches the 404 under test.
        assertTypedNotFound(get(path("financial-transactions")).with(realmAdmin(seedTenant())));
    }

    // ------------------------------------------------------------------
    // CONTROL — without this, "throw 404 from everywhere" passes the suite
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CONTROL: a live customer still answers 200 with its own body, not a problem")
    void liveRowStillAnswers200() throws Exception {
        UUID tenant = seedTenant();
        UUID customer = seedCustomer(tenant);

        mockMvc.perform(get("/api/v1/customers/" + customer).with(vendor(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customer.toString()))
                .andExpect(jsonPath("$.type").doesNotExist());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * The whole assertion, in one place so no arm can quietly assert less than its siblings.
     * The content-type check is the load-bearing half: an empty-bodied 404 sets no content type
     * at all, so this is what fails on the unfixed tree.
     */
    private void assertTypedNotFound(
            org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(NOT_FOUND_TYPE))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    private static String path(String resource) {
        return "/api/v1/" + resource + "/" + UUID.randomUUID();
    }

    private static String customerPath() {
        return path("customers");
    }

    /** A day-one vendor: UUID subject (the 23-08 fail-closed gate) + tenant claim, no realm role. */
    private static RequestPostProcessor vendor(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                .claim("tenant_id", tenant.toString()));
    }

    private static RequestPostProcessor scoped(UUID tenant, String scope) {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", tenant.toString())
                        .claim("scope", scope))
                .authorities(new JwtRolesAndScopesConverter());
    }

    private static RequestPostProcessor customersWrite(UUID tenant) {
        return scoped(tenant, "customers:write");
    }

    private static RequestPostProcessor catalogWrite(UUID tenant) {
        return scoped(tenant, "catalog:write");
    }

    /** Carries the realm role the finance ledger's class-level gate requires. */
    private static RequestPostProcessor realmAdmin(UUID tenant) {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"));
    }

    private UUID seedTenant() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "typed-404-tenant-" + tenant);
        return tenant;
    }

    private UUID seedCustomer(UUID tenant) {
        UUID customer = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, tenant_id, name, email) VALUES (?, ?, ?, ?)",
                customer, tenant, "Control Customer", "control-" + customer + "@example.com");
        return customer;
    }

    private static String customerJson() {
        return """
                {"name":"Renamed","email":"renamed@example.com","phone":"07700900000"}
                """;
    }

    /**
     * Every @NotNull/@NotBlank on CreateProductRequest is populated. @Valid resolution runs BEFORE
     * the handler body, so an incomplete body answers 400 and the 404 under test is never reached —
     * the ordering trap ScopedWriteAccessIntegrationTest documents, and the reason the first run of
     * this class failed on {@code expected:<404> but was:<400>}.
     */
    private static String productJson() {
        return """
                {"sku":"SKU-RENAMED","title":"Renamed","pricePennies":500,
                 "ingredientsText":"flour, water","allergenMask":1}
                """;
    }
}
