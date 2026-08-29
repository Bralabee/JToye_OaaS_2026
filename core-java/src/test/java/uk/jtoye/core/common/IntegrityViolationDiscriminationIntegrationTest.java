package uk.jtoye.core.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.customer.CustomerService;
import uk.jtoye.core.exception.ResourceInUseException;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.product.ProductService;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA-council cluster P2 (API-2/FE-4) — {@code GlobalExceptionHandler}'s blanket
 * {@code DataIntegrityViolationException} handler used to return HTTP 409 "Duplicate Entry"
 * {@code errors/duplicate} for EVERY integrity violation, discriminated only by a substring
 * match on the constraint index name. A not-null violation (SQLState 23502) and a foreign-key
 * violation on delete (SQLState 23503) are semantically different failures — a 400 request-shape
 * error and a 409 conflict respectively — and were both misreported as a duplicate.
 *
 * <p>Every exception exercised here is REAL: raised by a real Postgres constraint under
 * Testcontainers and caught as the real {@link DataIntegrityViolationException} /
 * {@link org.hibernate.exception.ConstraintViolationException} Hibernate actually produces.
 * Mockito cannot see a SQLState (there is no live JDBC driver behind a mock), which is why this
 * is a Testcontainers test rather than a unit test with a hand-rolled exception double.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class IntegrityViolationDiscriminationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private ProductRepository productRepository;
    @Autowired private ProductService productService;
    @Autowired private CustomerService customerService;
    @Autowired private JdbcTemplate jdbcTemplate;

    // ProductService.deleteProduct gates on SHOP_MANAGER; this test is about integrity-error
    // translation, not shop authorization, so the gate is a no-op here (proven elsewhere), same
    // pattern as ProductImageDeleteIntegrationTest.
    @MockBean private ShopAccessService shopAccessService;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

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

    // ------------------------------------------------------------------
    // (a) 23502 not-null -> 400 errors/missing-field
    // ------------------------------------------------------------------

    @Test
    void notNullViolationMapsTo400MissingField() {
        Product product = validProduct("NN-SKU-1");
        product.setAvailable(null); // NOT NULL column, no default applied here — deliberate

        DataIntegrityViolationException caught = catchDataIntegrityViolation(
                () -> productRepository.saveAndFlush(product));

        assertThat(caught)
                .as("a NOT NULL column write must raise a real SQLState 23502 under Postgres")
                .isNotNull();
        assertThat(uk.jtoye.core.exception.SqlStateExtractor.sqlState(caught))
                .as("sanity: the real exception really does carry SQLState 23502")
                .contains("23502");

        ProblemDetail problem = handler.handleDataIntegrityViolation(caught);

        assertThat(problem.getStatus()).as("23502 is a client request-shape error, not a duplicate")
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getType().toString())
                .as("distinct type from the duplicate-entry mapping")
                .isEqualTo("https://jtoye.uk/errors/missing-field");
    }

    // ------------------------------------------------------------------
    // (b) 23503 FK on delete -> 409 errors/in-use + constraint name (ProductService)
    // ------------------------------------------------------------------

    @Test
    void deletingAProductStillReferencedByAnOrderItemIsResourceInUse() {
        UUID shopId = seedShop();
        UUID productId = seedProduct("FK-SKU-1", shopId);
        seedOrderReferencingProduct(shopId, productId);

        Throwable thrown = catchThrowable(() -> {
            productService.deleteProduct(productId);
            // Force the pending DELETE to execute now if deleteProduct() itself did not
            // already flush — proves the exception is translated INSIDE the service method,
            // not merely raised somewhere downstream of it.
            productRepository.flush();
        });

        assertThat(thrown)
                .as("a product still line-itemed on an order must not delete silently or 500")
                .isInstanceOf(ResourceInUseException.class);
        ResourceInUseException ex = (ResourceInUseException) thrown;

        ProblemDetail problem = handler.handleResourceInUse(ex);
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getType().toString()).isEqualTo("https://jtoye.uk/errors/in-use");
    }

    // ------------------------------------------------------------------
    // (b') same defect, CustomerService (the plan's other named call site)
    // ------------------------------------------------------------------

    @Test
    void deletingACustomerStillReferencedByAnOrderIsResourceInUse() {
        UUID shopId = seedShop();
        UUID customerId = seedCustomer();
        seedOrderReferencingCustomer(shopId, customerId);

        assertThatThrownBy(() -> customerService.deleteCustomer(customerId))
                .as("a customer still named on an order must not delete silently or 500")
                .isInstanceOf(ResourceInUseException.class);
    }

    // ------------------------------------------------------------------
    // (c) 23505 unique -> 409 errors/duplicate, UNCHANGED (regression guard)
    // ------------------------------------------------------------------

    @Test
    void uniqueViolationStillMapsTo409Duplicate() {
        UUID shopId = seedShop();
        productRepository.saveAndFlush(validProductWithShop("DUP-SKU-1", shopId));

        Product duplicate = validProductWithShop("DUP-SKU-1", shopId);
        DataIntegrityViolationException caught = catchDataIntegrityViolation(
                () -> productRepository.saveAndFlush(duplicate));

        assertThat(caught).isNotNull();
        assertThat(uk.jtoye.core.exception.SqlStateExtractor.sqlState(caught)).contains("23505");

        ProblemDetail problem = handler.handleDataIntegrityViolation(caught);
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getType().toString()).isEqualTo("https://jtoye.uk/errors/duplicate");
    }

    // ---- helpers -------------------------------------------------------

    private DataIntegrityViolationException catchDataIntegrityViolation(Runnable r) {
        try {
            r.run();
        } catch (DataIntegrityViolationException e) {
            return e;
        }
        return null;
    }

    private Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable c) {
        try {
            c.call();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private Product validProduct(String sku) {
        Product p = new Product();
        p.setTenantId(tenant);
        p.setSku(sku);
        p.setTitle("Test product " + sku);
        p.setIngredientsText("Water");
        p.setAllergenMask(0);
        p.setPricePennies(500L);
        return p;
    }

    private Product validProductWithShop(String sku, UUID shopId) {
        Product p = validProduct(sku);
        p.setShopId(shopId);
        return p;
    }

    private UUID seedShop() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                id, tenant, "Shop " + id, "shop-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID seedProduct(String sku, UUID shopId) {
        Product product = validProductWithShop(sku, shopId);
        return productRepository.saveAndFlush(product).getId();
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO customers (id, tenant_id, name, email) VALUES (?, ?, ?, ?)",
                id, tenant, "Test Customer", "customer-" + id + "@example.com");
        return id;
    }

    private void seedOrderReferencingProduct(UUID shopId, UUID productId) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, delivery_fee_pennies, "
                        + "total_amount_pennies, item_count, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, 0, now(), now())",
                orderId, tenant, shopId, "ORD-" + orderId, "PENDING",
                500L, "ZERO", 500L, 1);
        jdbcTemplate.update(
                "INSERT INTO order_items (id, tenant_id, order_id, product_id, quantity, "
                        + "unit_price_pennies, total_price_pennies, product_name, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 500, 500, ?, now())",
                UUID.randomUUID(), tenant, orderId, productId, 1, "Test product");
    }

    private void seedOrderReferencingCustomer(UUID shopId, UUID customerId) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO orders (id, tenant_id, shop_id, customer_id, order_number, status, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, delivery_fee_pennies, "
                        + "total_amount_pennies, item_count, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, 0, now(), now())",
                orderId, tenant, shopId, customerId, "ORD-" + orderId, "PENDING",
                500L, "ZERO", 500L, 1);
    }
}
