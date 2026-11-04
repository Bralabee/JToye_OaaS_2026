package uk.jtoye.core.shop;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.exception.ShopHasProductsException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #833 (UXT-064 / ADE-05): deleting a shop orphaned its products. products.shop_id is
 * {@code ON DELETE SET NULL} (V20), so the products survived with shop_id NULL: still listed,
 * unreachable by id ("Shop not found"), and an edit moved them to "All Shops".
 *
 * A shop that still has products is now REFUSED, with a typed 409 that names the count so the vendor
 * can move or delete them first. A shop with none deletes as before, and a product the vendor already
 * archived (#832) does not block it.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class ShopDeleteWithProductsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private ShopService shopService;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;
    @MockitoBean private ShopAccessService shopAccessService;

    private UUID tenant;

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "test-" + tenant);
        TenantContext.set(tenant);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private UUID seedShop() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, false, 0, 0, 0)",
                id, tenant, "Shop " + id, "shop-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID seedProduct(UUID shopId, String archivedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, sku, title, ingredients_text, shop_id, available, archived_at) "
                        + "VALUES (?, ?, ?, 'Dish', 'Yam (100%)', ?, true, " + (archivedAt == null ? "NULL" : "now()") + ")",
                id, tenant, "SKU-" + id.toString().substring(0, 8), shopId);
        return id;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void aShopWithProductsIsRefusedWithATypedConflictNamingTheCount() {
        UUID shop = seedShop();
        seedProduct(shop, null);
        seedProduct(shop, null);

        assertThatThrownBy(() -> shopService.deleteShop(shop))
                .isInstanceOfSatisfying(ShopHasProductsException.class, ex -> {
                    assertThat(ex.getProductCount()).isEqualTo(2);
                    ProblemDetail problem = new GlobalExceptionHandler().handleShopHasProducts(ex);
                    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
                    assertThat(problem.getType().toString()).isEqualTo("https://jtoye.uk/errors/shop-has-products");
                    assertThat(problem.getProperties()).containsEntry("productCount", 2L);
                    assertThat(problem.getDetail()).contains("2 products");
                });
        em.flush();
        em.clear();

        assertThat(count("SELECT COUNT(*) FROM shops WHERE id = ?", shop)).as("the shop is kept").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM products WHERE shop_id = ?", shop))
                .as("and no product is orphaned to shop_id NULL").isEqualTo(2);
    }

    @Test
    void aShopWithNoProductsDeletesAsBefore() {
        UUID shop = seedShop();

        shopService.deleteShop(shop);
        em.flush();
        em.clear();

        assertThat(count("SELECT COUNT(*) FROM shops WHERE id = ?", shop)).isZero();
    }

    @Test
    void anArchivedProductDoesNotBlockTheShopDelete() {
        UUID shop = seedShop();
        seedProduct(shop, "archived");

        shopService.deleteShop(shop);
        em.flush();
        em.clear();

        assertThat(count("SELECT COUNT(*) FROM shops WHERE id = ?", shop)).isZero();
    }
}
