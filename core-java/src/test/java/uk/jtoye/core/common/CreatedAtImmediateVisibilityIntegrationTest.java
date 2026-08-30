package uk.jtoye.core.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.customer.CustomerController.CreateCustomerRequest;
import uk.jtoye.core.customer.CustomerController.CustomerDto;
import uk.jtoye.core.customer.CustomerService;
import uk.jtoye.core.order.OrderService;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.product.ProductService;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.AsSystemHarness;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-council cluster P1 (API-3 rider) — {@code createdAt} is a Hibernate
 * {@code @CreationTimestamp}, generated at FLUSH time, not at {@code persist()}/{@code save()}.
 * {@code CustomerService.createCustomer}, {@code OrderService.createOrder} and
 * {@code ProductService.createProduct} all mapped their DTO straight off the just-{@code save()}d
 * entity with no intervening flush, so the response body carried {@code createdAt: null} even
 * though the row itself persists with a real timestamp at commit — a client reading the create
 * response back gets a lie about its own row.
 *
 * <p>{@code saveAndFlush} forces Hibernate to generate the timestamp (and execute the INSERT)
 * before the DTO is built, so the value in the HTTP response and the value in the row are always
 * the same read.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
// Scaffolding only (seeding a shop for the order-creation case) — no authorization outcome is
// asserted in this class; see AsSystemHarness's own Javadoc for the declared-not-inferred rule.
@AsSystemHarness
class CreatedAtImmediateVisibilityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private CustomerService customerService;
    @Autowired private ProductService productService;
    @Autowired private OrderService orderService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID tenant;

    @BeforeEach
    void setUp() {
        tenant = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + tenant);
        TenantContext.set(tenant);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createCustomerReturnsNonNullCreatedAt() {
        CustomerDto dto = customerService.createCustomer(
                new CreateCustomerRequest("Test Customer", "createdat-" + UUID.randomUUID() + "@example.com",
                        null, null));

        assertThat(dto.createdAt())
                .as("the create response must not lie about its own row's createdAt")
                .isNotNull();
    }

    @Test
    void createProductReturnsNonNullCreatedAt() {
        CreateProductRequest req = new CreateProductRequest();
        req.setSku("CA-SKU-" + UUID.randomUUID());
        req.setTitle("Created-at product");
        req.setIngredientsText("Water");
        req.setAllergenMask(0);
        req.setPricePennies(500L);

        ProductDto dto = productService.createProduct(req);

        assertThat(dto.getCreatedAt())
                .as("the create response must not lie about its own row's createdAt")
                .isNotNull();
    }

    @Test
    void createOrderReturnsNonNullCreatedAt() {
        UUID shopId = seedShop();

        CreateOrderRequest req = new CreateOrderRequest();
        req.setShopId(shopId);
        req.setCustomerName("Guest");
        req.setCustomerEmail("guest@example.com");
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(seedProduct(shopId));
        item.setQuantity(1);
        req.setItems(List.of(item));

        OrderDto dto = orderService.createOrder(req);

        assertThat(dto.getCreatedAt())
                .as("the create response must not lie about its own row's createdAt")
                .isNotNull();
    }

    // ---- helpers -------------------------------------------------------

    private UUID seedShop() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                id, tenant, "Shop " + id, "shop-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID seedProduct(UUID shopId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, "
                        + "shop_id, vat_rate, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 500, 0, true, false, ?, 'STANDARD', 0)",
                id, tenant, "CA-ORD-SKU-" + id, "Order product", "Water", shopId);
        return id;
    }
}
