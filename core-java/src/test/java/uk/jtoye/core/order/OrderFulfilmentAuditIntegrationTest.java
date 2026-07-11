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
 * Testcontainers proof (Phase 19 UIX-04) that after V45 a REAL audited order write
 * succeeds against a real Postgres 15 with the {@code orders_aud} Envers mirror in
 * place — i.e. there is NO audit-column drift (the V38 landmine).
 *
 * <p><strong>Why a real audited write is the only proof:</strong> Envers writes the
 * {@code orders_aud} row at transaction commit. If V45 had added
 * {@code fulfilment_type}/{@code address_*} to {@code orders} but NOT mirrored them
 * onto {@code orders_aud}, the audit INSERT would fail with
 * "column ... does not exist" — a latent HTTP 500 on the very next order write.
 * A {@code @DataJpaTest} (no real commit) and {@code RlsContractTest} (walks
 * table-level RLS, not columns) both MISS this; only a committed audited write on a
 * real Postgres catches it (RESEARCH Pitfall 1). This class is therefore deliberately
 * NOT {@code @Transactional} — each repository save commits so Envers actually runs.
 *
 * <p>The Testcontainers bootstrap role is a Postgres SUPERUSER (bypasses even FORCE
 * RLS), so seeding + the audited writes run here without the NOSUPERUSER downgrade.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OrderFulfilmentAuditIntegrationTest {

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
            UUID.fromString("00000000-0000-0000-0000-000000190001");
    private static final UUID SHOP_A =
            UUID.fromString("00000000-0000-0000-0000-000000190002");

    @Autowired OrderRepository orderRepository;
    @Autowired ProductRepository productRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID productId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        // SUPERUSER bootstrap role bypasses FORCE RLS, so direct seeding needs no GUC.
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) "
                        + "ON CONFLICT (id) DO NOTHING",
                TENANT_A, "Phase 19 Fulfilment Tenant");
        jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, name, slug, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, 0) ON CONFLICT (id) DO NOTHING",
                SHOP_A, TENANT_A, "Fulfilment Test Shop", "fulfilment-test-shop-19");

        // Product row for the order_items.product_id FK. Set the tenant GUC via the
        // aspect (inside the repository's transaction) so the seed write is scoped.
        TenantContext.set(TENANT_A);
        Product product = new Product();
        product.setTenantId(TENANT_A);
        // Unique SKU per test method: the class is NOT @Transactional (Envers must
        // commit), so a fixed SKU would collide on idx_products_tenant_sku when the
        // second method re-seeds.
        product.setSku("SKU-FULFIL-" + UUID.randomUUID().toString().substring(0, 8));
        product.setTitle("Jollof Rice");
        product.setIngredientsText("Rice, tomatoes, peppers");
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
    @DisplayName("A DELIVERY order with an address commits and Envers writes it to orders_aud (no drift)")
    void deliveryOrderAuditedWriteSucceeds() {
        TenantContext.set(TENANT_A);
        try {
            Order order = newOrder("ORD-DELIV-19");
            order.setFulfilmentType(FulfilmentType.DELIVERY);
            order.setAddressLine1("1 High Street");
            order.setAddressLine2("Flat 2");
            order.setAddressCity("London");
            order.setAddressPostcode("E1 6AN");
            order.addItem(newItem());
            order.calculateTotal();

            // The audited write itself: if orders_aud lacks the V45 columns this
            // saveAndFlush throws at the Envers INSERT. Reaching the assertions = no drift.
            UUID orderId = orderRepository.saveAndFlush(order).getId();

            // Live row carries the fulfilment + address.
            Order persisted = orderRepository.findById(orderId).orElseThrow();
            assertThat(persisted.getFulfilmentType()).isEqualTo(FulfilmentType.DELIVERY);
            assertThat(persisted.getAddressLine1()).isEqualTo("1 High Street");
            assertThat(persisted.getAddressCity()).isEqualTo("London");
            assertThat(persisted.getAddressPostcode()).isEqualTo("E1 6AN");

            // Envers wrote an orders_aud row with the NEW columns readable back —
            // the direct proof that the audit mirror exists and matches.
            assertThat(auditRowCount(orderId))
                    .as("orders_aud must have received a row for the committed order")
                    .isGreaterThan(0);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT fulfilment_type FROM orders_aud WHERE id = ? ORDER BY rev DESC LIMIT 1",
                    String.class, orderId))
                    .isEqualTo("DELIVERY");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT address_postcode FROM orders_aud WHERE id = ? ORDER BY rev DESC LIMIT 1",
                    String.class, orderId))
                    .isEqualTo("E1 6AN");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("A COLLECTION order persists fulfilment_type=COLLECTION with no address and £0 delivery fee")
    void collectionOrderPersistsWithZeroFee() {
        TenantContext.set(TENANT_A);
        try {
            Order order = newOrder("ORD-COLLECT-19");
            order.setFulfilmentType(FulfilmentType.COLLECTION);
            order.setDeliveryFeePennies(0L);
            order.addItem(newItem());
            order.calculateTotal();

            UUID orderId = orderRepository.saveAndFlush(order).getId();

            Order persisted = orderRepository.findById(orderId).orElseThrow();
            assertThat(persisted.getFulfilmentType()).isEqualTo(FulfilmentType.COLLECTION);
            assertThat(persisted.getDeliveryFeePennies()).isZero();
            assertThat(persisted.getAddressLine1()).isNull();
            assertThat(persisted.getAddressPostcode()).isNull();

            // Audited write still lands for COLLECTION (fulfilment_type mirror present).
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT fulfilment_type FROM orders_aud WHERE id = ? ORDER BY rev DESC LIMIT 1",
                    String.class, orderId))
                    .isEqualTo("COLLECTION");
        } finally {
            TenantContext.clear();
        }
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

    private OrderItem newItem() {
        OrderItem item = new OrderItem(productId, 2, 899L);
        item.setTenantId(TENANT_A);
        item.setProductName("Jollof Rice");
        return item;
    }

    private Long auditRowCount(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders_aud WHERE id = ?", Long.class, orderId);
    }
}
