package uk.jtoye.core.storefront;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.gdpr.GdprService;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.GuestOrderAcknowledgements;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 37-28 (D-27, RWO-041 / RWO-006): the pseudonymous client-address digest a storefront order
 * carries has a DECLARED lifetime, enforced by code, and Article 17 erasure removes it.
 *
 * <p>Three mechanisms, each with its own arm and its own control:
 * <ol>
 *   <li><b>Terminal state.</b> The one transition path ({@code OrderService.transitionOrder}) nulls the
 *       digest when the order reaches COMPLETED, CANCELLED or REFUNDED; a non-terminal transition
 *       (CONFIRMED) keeps it, because the per-IP cap still counts the order while it is open.</li>
 *   <li><b>The daily sweep.</b> {@code ClientIpDigestRetentionSweep} nulls any digest older than
 *       {@code jtoye.storefront.client-ip-digest.retention-hours} (72) in EVERY tenant, pinning each one —
 *       proven with TWO tenants under a NOSUPERUSER role, because a bare UPDATE under FORCE RLS matches
 *       zero rows and reports success (the V25 -> V44 -> V57 -> V78 -> V79 -> V80 class). A 71 h digest
 *       survives the sweep; a 73 h one does not.</li>
 *   <li><b>Erasure.</b> {@code GdprService}'s one anonymisation routine nulls the digest with the rest of
 *       the order's PII.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ClientIpDigestRetentionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired GdprService gdprService;
    @Autowired ClientIpDigestRetentionSweep sweep;
    @Autowired PlatformTransactionManager txManager;

    /** Two tenants: the sweep must reach BOTH, one pinned at a time. */
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-00000000372a");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-00000000372b");
    private static final String SHOP_A = "shop-37-28-retention-a";
    private static final String SHOP_B = "shop-37-28-retention-b";
    private static final String APP_ROLE = "test";
    /**
     * The downgrade is ONE-WAY per container: a role that is no longer a superuser cannot make itself one
     * again (measured: the restore in a finally failed "must be superuser"), so it happens exactly once,
     * and every seed and read in this class runs inside a tenant-pinned transaction — the posture the
     * production role has anyway (NOSUPERUSER, FORCE RLS admits only the pinned tenant's rows).
     */
    private static final java.util.concurrent.atomic.AtomicBoolean DOWNGRADED = new java.util.concurrent.atomic.AtomicBoolean(false);

    private UUID shopA;
    private UUID shopB;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
            jdbcTemplate.update(
                    "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                    tenant, "37-28 Retention Tenant " + tenant.toString().substring(35));
        }
        if (DOWNGRADED.compareAndSet(false, true)) {
            assertThat(postgres.getUsername()).as("the role this test downgrades must be the one it names").isEqualTo(APP_ROLE);
            jdbcTemplate.execute("ALTER ROLE \"" + APP_ROLE + "\" NOSUPERUSER");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = ?", Boolean.class, APP_ROLE))
                .as("PRECONDITION: every arm runs under a role FORCE RLS applies to, or the sweep arm proves nothing").isFalse();
        shopA = seedShopIdempotent(TENANT_A, SHOP_A, "37-28 Retention Shop A");
        shopB = seedShopIdempotent(TENANT_B, SHOP_B, "37-28 Retention Shop B");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Terminal state: the digest exists while the order is open (the cap counts it) and goes with the
    // order's open state. A non-terminal transition keeps it (the control); COMPLETED and CANCELLED
    // null it, through the one transition path every KDS button and the API use.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-27: completing an order nulls its client_ip_digest (CONFIRMED keeps it); cancelling nulls it too")
    void terminalState_nullsTheDigest_andANonTerminalTransitionKeepsIt() throws Exception {
        UUID jollof = seedProduct(TENANT_A, shopA, "SKU-3728-TERM", "Jollof Rice", 1000L);

        UUID completed = placeCashOrder(SHOP_A, TENANT_A, jollof, "198.51.100.31");
        assertThat(digestOf(TENANT_A, completed)).as("PRECONDITION: the storefront wrote a digest").isNotNull().hasSize(64);
        transition(completed, "confirm");
        assertThat(digestOf(TENANT_A, completed)).as("CONFIRMED is open: the cap still counts it, so the digest stays").isNotNull();
        transition(completed, "start-preparation");
        transition(completed, "mark-ready");
        transition(completed, "complete");
        assertThat(statusOf(TENANT_A, completed)).isEqualTo("COMPLETED");
        assertThat(digestOf(TENANT_A, completed)).as("COMPLETED is terminal: the digest is gone with the open state").isNull();

        UUID cancelled = placeCashOrder(SHOP_A, TENANT_A, jollof, "198.51.100.32");
        assertThat(digestOf(TENANT_A, cancelled)).isNotNull();
        transition(cancelled, "cancel");
        assertThat(statusOf(TENANT_A, cancelled)).isEqualTo("CANCELLED");
        assertThat(digestOf(TENANT_A, cancelled)).as("CANCELLED is terminal").isNull();
    }

    // ------------------------------------------------------------------
    // The sweep: 73 h digests are nulled in tenant A AND tenant B under a NOSUPERUSER role (FORCE RLS in
    // force, so only a per-tenant pin can reach the rows); 71 h digests survive in both. The seeded rows
    // are PENDING, so nothing but the sweep could have nulled them.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-27 / RWO-006: the retention sweep nulls a 73 h digest on a PENDING order in tenants A and B under NOSUPERUSER, and leaves a 71 h digest in both")
    void sweep_nullsDigestsPastRetention_inEveryTenant_underNoSuperuser() {
        String digest = "a".repeat(64);
        UUID oldA = seedPendingOrder(TENANT_A, shopA, digest, 73);
        UUID youngA = seedPendingOrder(TENANT_A, shopA, digest, 71);
        UUID oldB = seedPendingOrder(TENANT_B, shopB, digest, 73);
        UUID youngB = seedPendingOrder(TENANT_B, shopB, digest, 71);
        assertThat(digestOf(TENANT_A, oldA)).as("PRECONDITION: seeded").isEqualTo(digest);
        assertThat(digestOf(TENANT_B, oldB)).as("PRECONDITION: seeded").isEqualTo(digest);

        int nulled = sweep.sweep();

        assertThat(nulled).as("one 73 h digest per tenant").isEqualTo(2);
        assertThat(digestOf(TENANT_A, oldA)).as("tenant A, 73 h").isNull();
        assertThat(digestOf(TENANT_B, oldB)).as("tenant B, 73 h: the sweep reached the second tenant").isNull();
        assertThat(digestOf(TENANT_A, youngA)).as("tenant A, 71 h: inside the window").isEqualTo(digest);
        assertThat(digestOf(TENANT_B, youngB)).as("tenant B, 71 h: inside the window").isEqualTo(digest);
        assertThat(statusOf(TENANT_A, oldA)).as("the sweep touches the digest and nothing else").isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------
    // Erasure: the one anonymisation routine nulls the digest with the rest of the order's PII.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-27 / Article 17: erasing the customer nulls client_ip_digest on their order with the rest of the PII")
    void erasure_nullsTheDigest() throws Exception {
        UUID suya = seedProduct(TENANT_A, shopA, "SKU-3728-ERASE", "Suya", 900L);
        String email = "erase-" + UUID.randomUUID() + "@example.com";
        UUID orderId = placeCashOrder(SHOP_A, TENANT_A, suya, "198.51.100.41", email);
        assertThat(digestOf(TENANT_A, orderId)).as("PRECONDITION: the storefront wrote a digest").isNotNull();
        UUID customerId = UUID.randomUUID();
        inTenant(TENANT_A, () -> jdbcTemplate.update(
                "INSERT INTO customers (id, tenant_id, name, email, created_at) VALUES (?, ?, ?, ?, now())",
                customerId, TENANT_A, "Subject", email));

        TenantContext.set(TENANT_A);
        try {
            gdprService.eraseCustomerData(customerId);
        } finally {
            TenantContext.clear();
        }

        assertThat(inTenant(TENANT_A, () -> jdbcTemplate.queryForObject(
                "SELECT customer_email FROM orders WHERE tenant_id = ? AND id = ?", String.class, TENANT_A, orderId)))
                .as("CONTROL: the erasure ran on this order").isNull();
        assertThat(digestOf(TENANT_A, orderId)).as("the digest is PII-adjacent and goes with the rest").isNull();
    }

    // ---- HTTP helpers ----

    private UUID placeCashOrder(String slug, UUID tenant, UUID productId, String peer) throws Exception {
        return placeCashOrder(slug, tenant, productId, peer, "ret-" + UUID.randomUUID() + "@example.com");
    }

    private UUID placeCashOrder(String slug, UUID tenant, UUID productId, String peer, String email) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Retention Buyer");
        body.put("customerEmail", email);
        body.put("customerPhone", "07700 " + String.format(java.util.Locale.ROOT, "%06d",
                java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000)));
        body.put("fulfilmentType", "COLLECTION");
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("productId", productId);
        line.put("quantity", 1);
        List<Map<String, Object>> items = List.of(line);
        body.put("items", items);
        body.put("acknowledgedAllergenMask", inTenant(tenant, () -> GuestOrderAcknowledgements.currentMask(jdbcTemplate, tenant, List.of(productId))));
        // 37-32 (D-16): the line carries the unit price the customer saw, and the body the total.
        body.put("expectedTotalPennies", inTenant(tenant, () -> GuestOrderAcknowledgements.expectCurrentPrices(jdbcTemplate, tenant, "COLLECTION", items)));
        MvcResult created = perform(post("/api/v1/public/shops/" + slug + "/orders")
                .with(r -> { r.setRemoteAddr(peer); return r; }), body);
        assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);
        return inTenant(tenant, () -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE tenant_id = ? AND customer_email = ?", UUID.class, tenant, email)));
    }

    private void transition(UUID orderId, String action) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders/" + orderId + "/" + action).with(vendorJwt(TENANT_A))).andReturn();
        assertThat(result.getResponse().getStatus()).as(action + ": " + responseText(result)).isEqualTo(200);
    }

    private static RequestPostProcessor vendorJwt(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_orders:write"),
                        new SimpleGrantedAuthority("SCOPE_orders:read"));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    private static String responseText(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    // ---- SQL helpers ----

    /**
     * Every seed and read of this class: inside a transaction with the tenant GUC pinned (and the
     * TenantContext set for the aspect), so FORCE RLS admits the pinned tenant's rows to the
     * NOSUPERUSER role. Without the pin the same statements see and write nothing.
     */
    private <T> T inTenant(UUID tenant, java.util.function.Supplier<T> work) {
        TenantContext.set(tenant);
        try {
            return new TransactionTemplate(txManager).execute(status -> {
                jdbcTemplate.queryForObject("SELECT set_config('app.current_tenant_id', ?, true)", String.class, tenant.toString());
                return work.get();
            });
        } finally {
            TenantContext.clear();
        }
    }

    private String digestOf(UUID tenant, UUID orderId) {
        return columnOf(tenant, orderId, "client_ip_digest");
    }

    private String statusOf(UUID tenant, UUID orderId) {
        return columnOf(tenant, orderId, "status");
    }

    /** The pinned session must SEE the row: a NULL from a row RLS hid would read exactly like a nulled digest. */
    private String columnOf(UUID tenant, UUID orderId, String column) {
        return inTenant(tenant, () -> {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT " + column + " FROM orders WHERE tenant_id = ? AND id = ?", String.class, tenant, orderId);
            assertThat(rows).as("the pinned session sees the row (" + column + ")").hasSize(1);
            return rows.get(0);
        });
    }

    /** A PENDING cash order with a digest, created {@code hoursAgo} hours ago, written under the pinned tenant. */
    private UUID seedPendingOrder(UUID tenant, UUID shop, String digest, int hoursAgo) {
        return inTenant(tenant, () -> {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                            + "customer_phone, subtotal_pennies, vat_rate, vat_amount_pennies, delivery_fee_pennies, "
                            + "fulfilment_type, total_amount_pennies, item_count, payment_status, payment_method, "
                            + "created_at, updated_at, version, client_ip_digest) "
                            + "VALUES (?, ?, ?, ?, 'PENDING', 'Sweep Buyer', ?, '07700 900000', 1000, 'STANDARD', 166, 0, "
                            + "'COLLECTION', 1000, 1, 'NONE', 'Unpaid', now() - make_interval(hours => ?), now(), 0, ?)",
                    id, tenant, shop, "ORD-SWEEP-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT),
                    "sweep-" + id + "@example.com", hoursAgo, digest);
            return id;
        });
    }

    private UUID seedShopIdempotent(UUID tenant, String slug, String name) {
        return inTenant(tenant, () -> {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, tenant, slug);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                    id, tenant, name, slug);
            return id;
        });
    }

    private UUID seedProduct(UUID tenant, UUID shop, String skuPrefix, String title, long pricePennies) {
        return inTenant(tenant, () -> {
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, 0, ?, 0, true, false, ?, NULL, 'STANDARD', 0)",
                    productId, tenant, skuPrefix + "-" + productId, title, "see label", pricePennies, shop);
            return productId;
        });
    }
}
