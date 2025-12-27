package uk.jtoye.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-critical tests verifying Row-Level Security (RLS) enforcement.
 * These tests ensure tenant isolation cannot be bypassed.
 */
@SpringBootTest
@Testcontainers
@Transactional
class TenantIsolationSecurityTest {

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
    }

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setup() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        // Create tenant rows
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantA, "Tenant A");
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantB, "Tenant B");
    }

    @Test
    void shouldOnlySeeTenantAShopsWhenTenantContextSetToA() {
        // Given: Create shops for both tenants
        TenantContext.set(tenantA);
        Shop shopA1 = createShop("Shop A1");
        Shop shopA2 = createShop("Shop A2");
        TenantContext.clear();

        TenantContext.set(tenantB);
        Shop shopB1 = createShop("Shop B1");
        TenantContext.clear();

        // When: Query as Tenant A
        TenantContext.set(tenantA);
        List<Shop> shops = shopRepository.findAll();
        TenantContext.clear();

        // Then: Only see Tenant A shops
        assertThat(shops).hasSize(2);
        assertThat(shops).extracting(Shop::getName).containsExactlyInAnyOrder("Shop A1", "Shop A2");
        assertThat(shops).noneMatch(s -> s.getName().equals("Shop B1"));
    }

    @Test
    void shouldNotSeeAnyShopsWhenTenantContextNotSet() {
        // Given: Create shops for tenant A
        TenantContext.set(tenantA);
        createShop("Shop A1");
        TenantContext.clear();

        // When: Query without tenant context (simulating missing tenant in request)
        List<Shop> shops = shopRepository.findAll();

        // Then: RLS blocks all rows
        assertThat(shops).isEmpty();
    }

    @Test
    void shouldEnforceRLSOnProductsTable() {
        // Given: Create products for both tenants
        TenantContext.set(tenantA);
        Product prodA = createProduct("SKU-A-001", "Product A");
        TenantContext.clear();

        TenantContext.set(tenantB);
        Product prodB = createProduct("SKU-B-001", "Product B");
        TenantContext.clear();

        // When: Query as Tenant A
        TenantContext.set(tenantA);
        List<Product> products = productRepository.findAll();
        TenantContext.clear();

        // Then: Only see Tenant A products
        assertThat(products).hasSize(1);
        assertThat(products.get(0).getSku()).isEqualTo("SKU-A-001");
    }

    @Test
    void shouldPreventInsertingDataForOtherTenant() {
        // Given: Set context to Tenant A
        TenantContext.set(tenantA);

        // When: Try to create a shop with Tenant B's ID (RLS should block)
        Shop shop = new Shop();
        shop.setTenantId(tenantB); // Attempting to insert for different tenant
        shop.setName("Malicious Shop");

        // Then: RLS WITH CHECK policy should prevent this
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
            shopRepository.saveAndFlush(shop);
        });

        TenantContext.clear();
    }

    @Test
    void shouldAllowCrossTenantsWhenContextChanges() {
        // Given: Create data for both tenants
        TenantContext.set(tenantA);
        createShop("Shop A");
        TenantContext.clear();

        TenantContext.set(tenantB);
        createShop("Shop B");
        TenantContext.clear();

        // When/Then: Switch contexts and verify isolation
        TenantContext.set(tenantA);
        assertThat(shopRepository.findAll()).hasSize(1).allMatch(s -> s.getName().equals("Shop A"));
        TenantContext.clear();

        TenantContext.set(tenantB);
        assertThat(shopRepository.findAll()).hasSize(1).allMatch(s -> s.getName().equals("Shop B"));
        TenantContext.clear();
    }

    // Helper methods
    private Shop createShop(String name) {
        Shop shop = new Shop();
        shop.setTenantId(TenantContext.get().orElseThrow());
        shop.setName(name);
        shop.setAddress("Test Address");
        return shopRepository.save(shop);
    }

    private Product createProduct(String sku, String title) {
        Product product = new Product();
        product.setTenantId(TenantContext.get().orElseThrow());
        product.setSku(sku);
        product.setTitle(title);
        product.setIngredientsText("Test ingredients");
        product.setAllergenMask(0);
        return productRepository.save(product);
    }
}
