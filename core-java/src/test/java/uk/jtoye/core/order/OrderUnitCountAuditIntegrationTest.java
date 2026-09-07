package uk.jtoye.core.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COR-4 / V66 (QA-council 20260902-134741) — Testcontainers proof, against a real Postgres 15,
 * that the {@code unit_count} column exists on BOTH {@code orders} and its Envers mirror, that a
 * real audited write lands, and that historic rows are genuinely left NULL.
 *
 * <p><b>Why a committed audited write is the only proof of the mirror.</b> Envers writes the
 * {@code orders_aud} row at transaction commit. Had V66 added {@code unit_count} to
 * {@code orders} but not mirrored it, this {@code saveAndFlush} would throw at the Envers INSERT
 * with "column ... does not exist" — a latent HTTP 500 on the very next order write. A
 * {@code @DataJpaTest} (no real commit) and {@code RlsContractTest} (walks table-level RLS, not
 * columns) both MISS it. This is the same landmine V38 had to repair after V30 and the reason
 * {@code OrderFulfilmentAuditIntegrationTest} exists for V45; this class is its V66 twin, and is
 * likewise deliberately NOT {@code @Transactional}.
 *
 * <p>The Testcontainers bootstrap role is a Postgres SUPERUSER (bypasses even FORCE RLS), so
 * seeding and the audited writes run without the NOSUPERUSER downgrade.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OrderUnitCountAuditIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final UUID TENANT_A =
            UUID.fromString("00000000-0000-0000-0000-000000660001");
    private static final UUID SHOP_A =
            UUID.fromString("00000000-0000-0000-0000-000000660002");

    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID productId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) "
                        + "ON CONFLICT (id) DO NOTHING",
                TENANT_A, "COR-4 Unit Count Tenant");
        jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, name, slug, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, 0) ON CONFLICT (id) DO NOTHING",
                SHOP_A, TENANT_A, "Unit Count Test Shop", "unit-count-test-shop-66");

        TenantContext.set(TENANT_A);
        Product product = new Product();
        product.setTenantId(TENANT_A);
        // Unique SKU per method: the class is NOT @Transactional (Envers must commit), so a fixed
        // SKU would collide on idx_products_tenant_sku when the second method re-seeds.
        product.setSku("SKU-UNITS-" + UUID.randomUUID().toString().substring(0, 8));
        product.setTitle("Zobo");
        product.setIngredientsText("Hibiscus, ginger");
        product.setAllergenMask(0);
        product.setPricePennies(899L);
        productId = productRepository.saveAndFlush(product).getId();
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("COR-4/V66: an audited write persists unit_count = SUM(quantity) and mirrors it to orders_aud")
    void auditedWritePersistsUnitsAndMirrorsThem() {
        TenantContext.set(TENANT_A);
        try {
            Order order = newOrder("ORD-UNITS-66-A");
            // ONE line of SIX — the exact fixture the defect is about. If this were 1x1 the two
            // counts would agree and the assertions could not distinguish them.
            order.addItem(newItem(6));
            order.calculateTotal();

            UUID orderId = orderRepository.saveAndFlush(order).getId();

            Order persisted = orderRepository.findById(orderId).orElseThrow();
            assertThat(persisted.getUnitCount())
                    .as("COR-4: the customer bought 6 things")
                    .isEqualTo(6);
            assertThat(persisted.getItemCount())
                    .as("A9: item_count keeps its LINES meaning, untouched")
                    .isEqualTo(1);

            // Read the row back through SQL too: the JPA value could be right while the column
            // was never written (a mapping the entity holds but the DB does not).
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT unit_count FROM orders WHERE id = ?", Integer.class, orderId))
                    .isEqualTo(6);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT item_count FROM orders WHERE id = ?", Integer.class, orderId))
                    .isEqualTo(1);

            // The Envers mirror. Reaching this line at all already proves the audit INSERT did
            // not throw; the value proves the column is the right one and not a silent NULL.
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT unit_count FROM orders_aud WHERE id = ? ORDER BY rev DESC LIMIT 1",
                    Integer.class, orderId))
                    .as("V66 must mirror unit_count onto orders_aud or the next audited write 500s")
                    .isEqualTo(6);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("COR-4/V66: units sum across lines, and the two counts stay independent")
    void unitsSumAcrossLines() {
        TenantContext.set(TENANT_A);
        try {
            Order order = newOrder("ORD-UNITS-66-B");
            order.addItem(newItem(2));
            order.addItem(newItem(3));
            order.calculateTotal();

            UUID orderId = orderRepository.saveAndFlush(order).getId();

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT unit_count FROM orders WHERE id = ?", Integer.class, orderId))
                    .isEqualTo(5);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT item_count FROM orders WHERE id = ?", Integer.class, orderId))
                    .isEqualTo(2);
            // The DB-side statement of the same invariant, so a future write path that sets one
            // and forgets the other is caught here rather than in a browser.
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT o.unit_count = (SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi "
                            + "WHERE oi.order_id = o.id) FROM orders o WHERE o.id = ?",
                    Boolean.class, orderId))
                    .isTrue();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * V66 backfills NOTHING, and this is the arm that proves it rather than asserting it in a
     * comment. A row written directly, the way every pre-V66 row was, must read NULL — "not
     * recorded" — and must NOT be silently repaired to 0 or to the line count by any default,
     * trigger or later migration.
     */
    @Test
    @DisplayName("COR-4/V66: a row written without a unit count stays NULL — no backfill, no DEFAULT 0")
    void aRowWrittenWithoutUnitsStaysNull() {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, delivery_fee_pennies, "
                        + "total_amount_pennies, item_count, fulfilment_type, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', 0, 'ZERO', 0, 0, 0, 3, 'COLLECTION', now(), now())",
                orderId, TENANT_A, SHOP_A, "ORD-PRE-V66-" + orderId.toString().substring(0, 8));

        Integer unitCount = jdbcTemplate.queryForObject(
                "SELECT unit_count FROM orders WHERE id = ?", Integer.class, orderId);

        assertThat(unitCount)
                .as("NULL means NOT RECORDED. A DEFAULT 0 or a backfill would fabricate a "
                        + "customer-visible figure nobody was ever shown.")
                .isNull();
        // And the line count is still there and still means lines — the two are independent.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT item_count FROM orders WHERE id = ?", Integer.class, orderId))
                .isEqualTo(3);
    }

    private Order newOrder(String orderNumber) {
        Order order = new Order();
        order.setTenantId(TENANT_A);
        order.setShopId(SHOP_A);
        order.setOrderNumber(orderNumber);
        order.setCustomerName("Test Customer");
        order.setCustomerEmail("customer@example.com");
        return order;
    }

    private OrderItem newItem(int quantity) {
        OrderItem item = new OrderItem(productId, quantity, 899L);
        item.setTenantId(TENANT_A);
        item.setProductName("Zobo");
        return item;
    }
}
