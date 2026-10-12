package uk.jtoye.core.storefront;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.GuestOrderAcknowledgements;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 37-28 (D-18 / D-19 / D-27, RWO-041 / UXT-041): one CONNECTION cannot flood a shop with open
 * cash orders even when every order invents a fresh email and phone — the third axis of the cash caps
 * — and the only thing the row records about that connection is a keyed digest, never the address.
 *
 * <p>Every arm goes through {@code POST /api/v1/public/shops/{slug}/orders} on real Postgres
 * (Testcontainers), with the socket peer set on the request. {@code jtoye.security.trusted-proxies} is
 * empty in the test profile (trust none), so the peer IS the client the resolver answers with, exactly
 * as a browser reaching compose's core directly. The caps in force are the application defaults
 * ({@code per-ip} 5). Each arm mints its own identities and its own documentation-range addresses
 * (RFC 5737), so the email and phone axes (37-27) can never be the refusal under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ClientIpCapIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * The key the digests are computed under, FIXED for this class so the expected digest can be computed
     * independently of the bean. The test profile's {@code ${random.value}${random.value}} is re-drawn on
     * every resolution (measured: a second {@code @Value} read gave a different key from the bean's, so the
     * digests disagreed), which is right for every other test and wrong for one that must predict the
     * value. 64 hex characters; a fixture, not a credential.
     */
    private static final String HMAC_KEY = "37280000111122223333444455556666777788889999aaaabbbbccccddddeeee";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("jtoye.storefront.client-ip-hmac-key", () -> HMAC_KEY);
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;

    /** Dedicated tenant so a parallel fork's fixtures cannot collide on slug or SKU. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000003728");
    private static final String SHOP_SLUG = "shop-37-28-ip-cap";
    private static final String STOREFRONT_ENDPOINT = "storefront.orders.create";
    private static final String PROBLEM_TYPE = "https://jtoye.uk/errors/order-cap-exceeded";
    private static final String PROBLEM_CODE = "ORDER_CAP_EXCEEDED";
    private static final int PER_IP_CAP = 5;

    private UUID shopId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "37-28 IP Cap Tenant");
        shopId = seedShopIdempotent(SHOP_SLUG, "37-28 IP Cap Shop");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // The per-IP cap: five open cash orders from one connection, each with a fresh email and phone, are
    // allowed; the sixth — spelled as the IPv4-mapped IPv6 form of the same client — is a typed 429 that
    // writes nothing and keeps no reservation; a sixth from ANOTHER connection is 201. The rows hold the
    // HMAC of the client address under the configured key and no column holds the address itself.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("UXT-041 / D-27: five open cash orders from one IP (fresh email and phone each) are 201; the sixth from the same client (mapped IPv6 spelling) is 429 order-cap-exceeded axis IP with no row and no reservation; from another IP it is 201; the rows hold HMAC-SHA256(key, ip) and never the IP")
    void sixthOpenCashOrderFromOneConnection_isRefusedOnIp_andOnlyADigestIsStored() throws Exception {
        UUID jollof = seedProduct("SKU-3728-IP", "Jollof Rice", 1000L);
        String clientIp = "198.51.100.7";
        String mappedSpelling = "::ffff:198.51.100.7";
        String otherClientIp = "203.0.113.9";
        String tag = UUID.randomUUID().toString();
        List<String> emails = new ArrayList<>();

        for (int i = 1; i <= PER_IP_CAP; i++) {
            String email = "ip-" + i + "-" + tag + "@example.com";
            emails.add(email);
            MvcResult created = perform(post(ordersUrl()).with(peer(clientIp)).header("Idempotency-Key", "ipaxis-open-" + i + "-" + tag),
                    storefrontBody(email, uniquePhone(), List.of(line(jollof, 1))));
            assertThat(created.getResponse().getStatus()).as("order " + i + " of " + PER_IP_CAP + " (the cap itself is allowed): "
                    + responseText(created)).isEqualTo(201);
        }

        // What the rows record about the connection: the keyed digest, computed here independently of the
        // class under test, and nothing that equals or contains the address.
        String expectedDigest = ClientIpDigesterTest.hmacSha256Hex(HMAC_KEY, clientIp);
        for (String email : emails) {
            UUID orderId = orderIdByEmail(email);
            assertThat(clientIpDigestOf(orderId)).as("orders.client_ip_digest for " + email).isEqualTo(expectedDigest);
            assertNoColumnHoldsTheAddress(orderId, clientIp);
        }
        assertThat(countOpenCashOrdersByDigest(expectedDigest)).as("five open cash orders are held by this client").isEqualTo(PER_IP_CAP);

        // The sixth, from the SAME client spelled as an IPv4-mapped IPv6 literal, with a fresh email and phone.
        String sixthEmail = "ip-6-" + tag + "@example.com";
        String sixthKey = "ipaxis-sixth-" + tag;
        MvcResult refused = perform(post(ordersUrl()).with(peer(mappedSpelling)).header("Idempotency-Key", sixthKey),
                storefrontBody(sixthEmail, uniquePhone(), List.of(line(jollof, 1))));
        JsonNode problem = assertProblem(refused, 429, PROBLEM_TYPE, PROBLEM_CODE);
        String text = responseText(refused);
        assertThat(problem.path("axis").asString()).as(text).isEqualTo("IP");
        assertThat(problem.path("cap").asInt()).as(text).isEqualTo(PER_IP_CAP);
        assertThat(refused.getResponse().getHeader("Retry-After")).as("no Retry-After: a slot frees when an order completes").isNull();
        assertThat(text).as("the refusal never echoes the address").doesNotContain(clientIp);

        assertThat(countOrdersByEmail(sixthEmail)).as("the refused order was not written").isZero();
        assertThat(countReservationsForKey(sixthKey))
                .as("the refusal is thrown inside the reserved work, so the reservation rolled back")
                .isZero();

        // A sixth from ANOTHER connection is a different client: 201, with its own digest.
        String otherEmail = "ip-other-" + tag + "@example.com";
        MvcResult accepted = perform(post(ordersUrl()).with(peer(otherClientIp)).header("Idempotency-Key", "ipaxis-other-" + tag),
                storefrontBody(otherEmail, uniquePhone(), List.of(line(jollof, 1))));
        assertThat(accepted.getResponse().getStatus()).as("another client, its own count: " + responseText(accepted)).isEqualTo(201);
        UUID otherOrderId = orderIdByEmail(otherEmail);
        assertThat(clientIpDigestOf(otherOrderId)).isEqualTo(ClientIpDigesterTest.hmacSha256Hex(HMAC_KEY, otherClientIp))
                .isNotEqualTo(expectedDigest);
        assertNoColumnHoldsTheAddress(otherOrderId, otherClientIp);
    }

    // ------------------------------------------------------------------
    // Completing an order frees the IP slot (the count is of OPEN orders), and the same Idempotency-Key
    // then succeeds — the reservation had rolled back with the refusal.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-18: once one of the five open cash orders from a client completes, the refused key is 201 on retry")
    void completingOneOrder_freesTheIpSlot_andTheSameKeyThenSucceeds() throws Exception {
        UUID suya = seedProduct("SKU-3728-FREE", "Suya", 900L);
        String clientIp = "198.51.100.21";
        String tag = UUID.randomUUID().toString();
        String firstEmail = null;

        for (int i = 1; i <= PER_IP_CAP; i++) {
            String email = "free-" + i + "-" + tag + "@example.com";
            if (firstEmail == null) {
                firstEmail = email;
            }
            MvcResult created = perform(post(ordersUrl()).with(peer(clientIp)).header("Idempotency-Key", "free-open-" + i + "-" + tag),
                    storefrontBody(email, uniquePhone(), List.of(line(suya, 1))));
            assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);
        }

        String sixthEmail = "free-6-" + tag + "@example.com";
        String sixthPhone = uniquePhone();
        String sixthKey = "free-sixth-" + tag;
        MvcResult refused = perform(post(ordersUrl()).with(peer(clientIp)).header("Idempotency-Key", sixthKey),
                storefrontBody(sixthEmail, sixthPhone, List.of(line(suya, 1))));
        assertThat(assertProblem(refused, 429, PROBLEM_TYPE, PROBLEM_CODE).path("axis").asString()).isEqualTo("IP");

        completeOrderByEmail(firstEmail);

        MvcResult accepted = perform(post(ordersUrl()).with(peer(clientIp)).header("Idempotency-Key", sixthKey),
                storefrontBody(sixthEmail, sixthPhone, List.of(line(suya, 1))));
        assertThat(accepted.getResponse().getStatus()).as("a slot freed: " + responseText(accepted)).isEqualTo(201);
        assertThat(countOrdersByEmail(sixthEmail)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // The digest is a storefront fact: a vendor's order (POST /api/v1/orders) is placed by staff, not by
    // a customer's connection, so it records nothing (NULL, not a digest of the staff member's address).
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-27: a vendor order (POST /api/v1/orders) records no client_ip_digest (NULL)")
    void vendorOrder_recordsNoDigest() throws Exception {
        UUID jollof = seedProduct("SKU-3728-VENDOR", "Jollof Rice", 1000L);
        String email = "vendor-" + UUID.randomUUID() + "@example.com";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shopId", shopId);
        body.put("customerName", "Phone Order Customer");
        body.put("customerEmail", email);
        body.put("customerPhone", uniquePhone());
        body.put("items", List.of(line(jollof, 1)));
        MvcResult created = perform(post("/api/v1/orders").with(vendorJwt()).with(peer("198.51.100.99"))
                .header("Idempotency-Key", "vendor-" + UUID.randomUUID()), body);
        assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);

        assertThat(clientIpDigestOf(orderIdByEmail(email))).as("the vendor is the writer: no customer connection to record").isNull();
    }

    // ---- HTTP helpers ----

    /** The socket peer of the request — the address the resolver answers with when no proxy is trusted. */
    private static RequestPostProcessor peer(String remoteAddr) {
        return request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        };
    }

    /** A realm-admin token for this tenant (an implicit GROUP_ADMIN, D-03) with both order scopes. */
    private RequestPostProcessor vendorJwt() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", TENANT_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_orders:write"),
                        new SimpleGrantedAuthority("SCOPE_orders:read"));
    }

    private String ordersUrl() {
        return "/api/v1/public/shops/" + SHOP_SLUG + "/orders";
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    private static Map<String, Object> line(UUID productId, int quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("quantity", quantity);
        return item;
    }

    /** A storefront body acknowledging the CURRENT allergen set of its products (D-05), as the checkout does. */
    private Map<String, Object> storefrontBody(String email, String phone, List<Map<String, Object>> items) {
        List<UUID> productIds = items.stream().map(i -> (UUID) i.get("productId")).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "IP Cap Buyer");
        body.put("customerEmail", email);
        body.put("customerPhone", phone);
        body.put("fulfilmentType", "COLLECTION");
        body.put("items", items);
        body.put("acknowledgedAllergenMask", GuestOrderAcknowledgements.currentMask(jdbcTemplate, TENANT_ID, productIds));
        return body;
    }

    private JsonNode assertProblem(MvcResult result, int status, String type, String code) throws Exception {
        String text = responseText(result);
        assertThat(result.getResponse().getStatus()).as(text).isEqualTo(status);
        JsonNode problem = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("type").asString()).as(text).isEqualTo(type);
        assertThat(problem.path("code").asString()).as(text).isEqualTo(code);
        return problem;
    }

    private static String responseText(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    /** Six random digits behind a UK mobile prefix: the phone axis (37-27) must never be the refusal here. */
    private static String uniquePhone() {
        return "07700 " + String.format(java.util.Locale.ROOT, "%06d",
                java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
    }

    // ---- SQL read helpers (superuser bootstrap role; tenant pinned for correctness) ----

    private <T> T inTenant(java.util.function.Supplier<T> read) {
        TenantContext.set(TENANT_ID);
        try {
            return read.get();
        } finally {
            TenantContext.clear();
        }
    }

    private String clientIpDigestOf(UUID orderId) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT client_ip_digest FROM orders WHERE tenant_id = ? AND id = ?", String.class, TENANT_ID, orderId));
    }

    /**
     * D-27 / T-37-71: every column of the row, read as text, is compared with the address. "No column
     * holds the IP" is asserted over the WHOLE row, not the one column this plan added, so a future
     * writer that stashes the address in notes or a reference is caught by the same arm.
     */
    private void assertNoColumnHoldsTheAddress(UUID orderId, String clientIp) {
        Map<String, Object> row = inTenant(() -> jdbcTemplate.queryForMap(
                "SELECT * FROM orders WHERE tenant_id = ? AND id = ?", TENANT_ID, orderId));
        assertThat(row).as("the order row exists and has columns").isNotEmpty();
        for (Map.Entry<String, Object> column : row.entrySet()) {
            Object value = column.getValue();
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            assertThat(text).as("orders." + column.getKey() + " must not equal the client address").isNotEqualTo(clientIp);
            assertThat(text).as("orders." + column.getKey() + " must not contain the client address").doesNotContain(clientIp);
        }
    }

    private long countOpenCashOrdersByDigest(String digest) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE tenant_id = ? AND shop_id = ? AND client_ip_digest = ? "
                        + "AND status IN ('PENDING','CONFIRMED','PREPARING','READY') AND payment_status = 'NONE' AND voided_at IS NULL",
                Long.class, TENANT_ID, shopId, digest)));
    }

    private long countOrdersByEmail(String email) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE tenant_id = ? AND customer_email = ?", Long.class, TENANT_ID, email)));
    }

    private UUID orderIdByEmail(String email) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE tenant_id = ? AND customer_email = ?", UUID.class, TENANT_ID, email)));
    }

    private void completeOrderByEmail(String email) {
        inTenant(() -> {
            int updated = jdbcTemplate.update("UPDATE orders SET status = 'COMPLETED' WHERE tenant_id = ? AND customer_email = ?",
                    TENANT_ID, email);
            assertThat(updated).as("exactly one order completed").isEqualTo(1);
            return null;
        });
    }

    private long countReservationsForKey(String key) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE tenant_id = ? AND endpoint = ? AND idempotency_key = ?",
                Long.class, TENANT_ID, STOREFRONT_ENDPOINT, key)));
    }

    // ---- Idempotent seed helpers (the CashOrderCapIntegrationTest pattern) ----

    private UUID seedShopIdempotent(String slug, String name) {
        return inTenant(() -> {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, TENANT_ID, slug);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            // No hours and LEGACY_ALWAYS_OPEN (37-20, D-25): the shop takes orders at any time, and no
            // minimum order, so the only refusal an arm can meet is the one under test.
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                    id, TENANT_ID, name, slug);
            return id;
        });
    }

    /** A fresh product per call (unique SKU suffix) at the test shop, with untracked stock. */
    private UUID seedProduct(String skuPrefix, String title, long pricePennies) {
        return inTenant(() -> {
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, 0, ?, 0, true, false, ?, NULL, 'STANDARD', 0)",
                    productId, TENANT_ID, skuPrefix + "-" + productId, title, "see label", pricePennies, shopId);
            return productId;
        });
    }
}
