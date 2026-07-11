package uk.jtoye.core.product;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-shop scoping proof for the storefront menu query (UIX-05, phase 19).
 *
 * <p>Before this phase {@code findAvailableByShopOrderedByCategory} matched
 * {@code (p.shopId = :shopId OR p.shopId IS NULL)}, so every unassigned product
 * bled into (and duplicated across) every shop under a tenant. This test proves,
 * on real Postgres under production-parity FORCE RLS (NOSUPERUSER role), that the
 * scoped query:
 *
 * <ul>
 *   <li>renders each shop's menu strictly from its own products (zero overlap);</li>
 *   <li>never surfaces a NULL-{@code shop_id} product in any shop's menu;</li>
 *   <li>never renders a duplicate line item (an assigned title plus an
 *       unassigned duplicate of the same title).</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@Tag("testcontainers")
class ProductRepositoryScopingIntegrationTest {

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
    private ProductRepository productRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000005c0");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT, "Scoping Tenant");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * FORCE ROW LEVEL SECURITY does NOT bind a SUPERUSER — the Testcontainers
     * bootstrap role bypasses RLS entirely. Downgrade so the query runs under the
     * same barrier production does. Safe: Flyway already ran as superuser at boot,
     * ALTER ROLE is transactional and this class is @Transactional (per-test
     * rollback restores superuser), and inserts still pass WITH CHECK because
     * TenantSetLocalAspect sets the tenant GUC before each repository op.
     */
    private void enforceRls() {
        jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
    }

    @Test
    void eachShopRendersOnlyItsOwnProducts() {
        enforceRls();
        UUID shopA = createShop("scope-shop-a");
        UUID shopB = createShop("scope-shop-b");
        createProduct(shopA, "SCA-JOL", "Jollof Rice", "Mains");
        createProduct(shopA, "SCA-EGU", "Egusi Soup", "Mains");
        createProduct(shopB, "SCB-SUY", "Suya Platter", "Mains");

        List<String> menuA = titles(shopA);
        List<String> menuB = titles(shopB);

        assertThat(menuA).containsExactlyInAnyOrder("Jollof Rice", "Egusi Soup");
        assertThat(menuB).containsExactly("Suya Platter");
        // Zero overlap: shop A's menu ∩ shop B's menu == ∅
        assertThat(menuA).doesNotContainAnyElementsOf(menuB);
    }

    @Test
    void nullShopIdProductNeverAppearsInAnyShopMenu() {
        enforceRls();
        UUID shopA = createShop("scope-shop-a");
        UUID shopB = createShop("scope-shop-b");
        createProduct(shopA, "SCA-JOL", "Jollof Rice", "Mains");
        createProduct(null, "SC-GHOST", "Ghost Item", "Mains"); // unassigned = the old bleed

        assertThat(titles(shopA))
                .contains("Jollof Rice")
                .doesNotContain("Ghost Item");
        assertThat(titles(shopB))
                .doesNotContain("Ghost Item");
    }

    @Test
    void duplicateLineItemNeverRendersOnShopMenu() {
        enforceRls();
        UUID shopA = createShop("scope-shop-a");
        createProduct(shopA, "SCA-JOL", "Jollof Rice", "Mains"); // assigned
        createProduct(null, "SC-JOL-DUP", "Jollof Rice", "Mains"); // unassigned duplicate title

        // The exact "'Jollof Rice' twice" defect: with the NULL bleed the shop
        // menu rendered the assigned row AND the unassigned duplicate.
        assertThat(titles(shopA)).filteredOn("Jollof Rice"::equals).hasSize(1);
    }

    // ---- Helpers ----

    private UUID createShop(String slug) {
        TenantContext.set(TENANT);
        Shop shop = new Shop();
        shop.setTenantId(TENANT);
        shop.setName("Shop " + slug);
        shop.setSlug(slug);
        shop.setPublished(true);
        Shop saved = shopRepository.saveAndFlush(shop);
        TenantContext.clear();
        entityManager.clear();
        return saved.getId();
    }

    private void createProduct(UUID shopId, String sku, String title, String category) {
        TenantContext.set(TENANT);
        Product product = new Product();
        product.setTenantId(TENANT);
        product.setSku(sku);
        product.setTitle(title);
        product.setCategory(category);
        product.setIngredientsText("demo ingredients");
        product.setAllergenMask(0);
        product.setPricePennies(899L);
        product.setAvailable(true);
        product.setShopId(shopId); // null models the pre-scoping bleed row
        // saveAndFlush: Hibernate batching would otherwise defer the INSERT to a
        // later flush, potentially under a cleared tenant GUC -> RLS WITH CHECK fail.
        productRepository.saveAndFlush(product);
        TenantContext.clear();
        // Detach so the menu query hits SQL (where RLS + the shop predicate filter),
        // not the first-level persistence-context cache.
        entityManager.clear();
    }

    private List<String> titles(UUID shopId) {
        TenantContext.set(TENANT);
        List<String> result = productRepository.findAvailableByShopOrderedByCategory(shopId)
                .stream().map(Product::getTitle).toList();
        entityManager.clear();
        return result;
    }
}
