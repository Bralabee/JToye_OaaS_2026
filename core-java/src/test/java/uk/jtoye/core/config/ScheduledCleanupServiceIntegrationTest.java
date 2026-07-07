package uk.jtoye.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderService;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * QA-council M1 real-medium (Testcontainers Postgres + RLS) regression guard for
 * {@link ScheduledCleanupService}.
 *
 * <p>Reproduces the original defect: with stale DRAFT orders (each carrying an
 * order_item) in TWO tenants, the previous single-{@code @Transactional} loop
 * deferred tenant A's cascade {@code order_items} delete until tenant B's query
 * auto-flush — by which point the transaction-local RLS GUC had switched to
 * tenant B, so FORCE-RLS filtered A's rows to 0 → {@code StaleStateException} →
 * the whole job rolled back and cleaned nothing. This test fails on that code and
 * passes once cleanup runs one transaction per tenant.
 *
 * <p>Deliberately NOT {@code @Transactional}: a class-level test transaction would
 * make the service's per-tenant {@code TransactionTemplate} (propagation REQUIRED)
 * join it, defeating the very isolation under test. {@code cleanup.stale-draft-hours=0}
 * makes freshly-created drafts qualify as stale without backdating @CreationTimestamp.
 */
@SpringBootTest
@Testcontainers
@org.junit.jupiter.api.Tag("testcontainers")
class ScheduledCleanupServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        // Any DRAFT created before the job runs counts as stale — no backdating needed.
        registry.add("cleanup.stale-draft-hours", () -> "0");
    }

    @Autowired private ScheduledCleanupService cleanupService;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT_A, "Tenant A");
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT_B, "Tenant B");
        seedStaleDraftWithItem(TENANT_A, "SKU-A");
        seedStaleDraftWithItem(TENANT_B, "SKU-B");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void cleanupAcrossMultipleTenantsCompletesAndDeletesEachTenantsDrafts() {
        // Precondition: both tenants have exactly one stale DRAFT.
        assertThat(draftCountFor(TENANT_A)).isEqualTo(1);
        assertThat(draftCountFor(TENANT_B)).isEqualTo(1);

        // The job must complete without StaleStateException (the original defect).
        assertThatCode(() -> cleanupService.cleanupStaleDraftOrders()).doesNotThrowAnyException();

        // Both tenants' stale drafts (and their cascaded order_items) are gone.
        assertThat(draftCountFor(TENANT_A)).as("Tenant A drafts cleaned").isZero();
        assertThat(draftCountFor(TENANT_B)).as("Tenant B drafts cleaned").isZero();
    }

    private int draftCountFor(UUID tenantId) {
        TenantContext.set(tenantId);
        try {
            return orderRepository.findByStatus(OrderStatus.DRAFT).size();
        } finally {
            TenantContext.clear();
        }
    }

    private void seedStaleDraftWithItem(UUID tenantId, String sku) {
        TenantContext.set(tenantId);
        try {
            Shop shop = new Shop();
            shop.setTenantId(tenantId);
            shop.setName("Shop " + sku);
            shop.setAddress("Address " + sku);
            shop = shopRepository.save(shop);

            Product product = new Product();
            product.setTenantId(tenantId);
            product.setSku(sku);
            product.setTitle("Product " + sku);
            product.setIngredientsText("Ingredients");
            product.setAllergenMask(0);
            product.setPricePennies(1000L);
            product = productRepository.save(product);

            CreateOrderRequest request = new CreateOrderRequest();
            request.setShopId(shop.getId());
            request.setCustomerName("Abandoned Cart");
            OrderItemRequest item = new OrderItemRequest();
            item.setProductId(product.getId());
            item.setQuantity(2);
            request.setItems(List.of(item));

            // createOrder yields a DRAFT order carrying an order_item — the cascade
            // delete of that order_item is what triggered the original bug.
            orderService.createOrder(request);
        } finally {
            TenantContext.clear();
        }
    }
}
