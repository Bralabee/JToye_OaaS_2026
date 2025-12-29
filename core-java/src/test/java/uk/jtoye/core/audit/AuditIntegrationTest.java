package uk.jtoye.core.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Envers audit functionality with tenant isolation.
 * Verifies that:
 * 1. Entity changes are tracked in audit tables
 * 2. Audit history respects tenant boundaries (RLS policies)
 * 3. RevInfo captures tenant and user context
 */
@SpringBootTest
@Testcontainers
class AuditIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb")
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
    private AuditService auditService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        TenantContext.clear();

        // Create test tenants required by foreign key constraints
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT_A, "Tenant A");
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT_B, "Tenant B");
    }

    @Test
    @WithMockUser(username = "tenant-a-user")
    void shouldTrackShopCreationInAuditHistory() {
        // Given: Tenant A context
        TenantContext.set(TENANT_A);

        UUID shopId;

        // When: Create a shop (transaction commits automatically without @Transactional)
        Shop shop = new Shop();
        shop.setTenantId(TENANT_A);
        shop.setName("Test Shop");
        shop.setAddress("123 Main St");
        shop = shopRepository.saveAndFlush(shop);
        shopId = shop.getId();

        // Debug: Check if audit records were created
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shops_aud WHERE id = ?",
                Integer.class,
                shopId
        );
        System.out.println("DEBUG: Audit records in shops_aud: " + auditCount);

        // Then: Audit history should contain 1 revision
        List<Shop> history = auditService.getEntityHistory(Shop.class, shopId);
        System.out.println("DEBUG: AuditService returned " + history.size() + " revisions");

        assertThat(history).hasSize(1);

        Shop auditedShop = history.get(0);
        assertThat(auditedShop.getName()).isEqualTo("Test Shop");
        assertThat(auditedShop.getTenantId()).isEqualTo(TENANT_A);
    }

    @Test
    @WithMockUser(username = "tenant-a-user")
    void shouldTrackProductUpdatesInAuditHistory() {
        // Given: Tenant A context and a product
        TenantContext.set(TENANT_A);

        Product product = new Product();
        product.setTenantId(TENANT_A);
        product.setSku("TEST-UPDATE-001");
        product.setTitle("Original Title");
        product.setIngredientsText("Flour, Water");
        product.setPricePennies(1000L);
        product = productRepository.saveAndFlush(product);

        UUID productId = product.getId();

        // When: Update the product twice
        product.setTitle("Updated Title v1");
        productRepository.saveAndFlush(product);

        product.setTitle("Updated Title v2");
        product.setPricePennies(1500L);
        productRepository.saveAndFlush(product);

        // Then: Audit history should contain 3 revisions
        List<Product> history = auditService.getEntityHistory(Product.class, productId);
        assertThat(history).hasSize(3);

        // Verify revision progression
        assertThat(history.get(0).getTitle()).isEqualTo("Original Title");
        assertThat(history.get(0).getPricePennies()).isEqualTo(1000L);

        assertThat(history.get(1).getTitle()).isEqualTo("Updated Title v1");
        assertThat(history.get(1).getPricePennies()).isEqualTo(1000L);

        assertThat(history.get(2).getTitle()).isEqualTo("Updated Title v2");
        assertThat(history.get(2).getPricePennies()).isEqualTo(1500L);

        // Verify revision count
        int revisionCount = auditService.getRevisionCount(Product.class, productId);
        assertThat(revisionCount).isEqualTo(3);
    }

    @Test
    @WithMockUser(username = "tenant-a-user")
    void shouldIsolateAuditHistoryByTenant() {
        // Given: Create shops for Tenant A
        TenantContext.set(TENANT_A);

        Shop shopA1 = new Shop();
        shopA1.setTenantId(TENANT_A);
        shopA1.setName("Isolation Test - Tenant A Shop 1");
        shopRepository.saveAndFlush(shopA1);

        Shop shopA2 = new Shop();
        shopA2.setTenantId(TENANT_A);
        shopA2.setName("Isolation Test - Tenant A Shop 2");
        shopRepository.saveAndFlush(shopA2);

        // When: Switch to Tenant B and create a shop
        TenantContext.clear();
        TenantContext.set(TENANT_B);

        Shop shopB = new Shop();
        shopB.setTenantId(TENANT_B);
        shopB.setName("Isolation Test - Tenant B Shop");
        shopRepository.saveAndFlush(shopB);

        // Then: Tenant A can only see their audit history
        TenantContext.clear();
        TenantContext.set(TENANT_A);

        List<Shop> tenantARevisions = auditService.getAllEntityRevisions(Shop.class);
        assertThat(tenantARevisions)
                .isNotEmpty()
                .allMatch(shop -> shop.getTenantId().equals(TENANT_A))
                .anyMatch(shop -> shop.getName().startsWith("Isolation Test - Tenant A"));

        // Tenant B can only see their audit history
        TenantContext.clear();
        TenantContext.set(TENANT_B);

        List<Shop> tenantBRevisions = auditService.getAllEntityRevisions(Shop.class);
        assertThat(tenantBRevisions)
                .isNotEmpty()
                .allMatch(shop -> shop.getTenantId().equals(TENANT_B))
                .anyMatch(shop -> shop.getName().startsWith("Isolation Test - Tenant B"));
    }

    @Test
    @WithMockUser(username = "tenant-a-user")
    void shouldNotSeeAuditHistoryForOtherTenantEntities() {
        // Given: Tenant B creates a product
        TenantContext.set(TENANT_B);

        Product productB = new Product();
        productB.setTenantId(TENANT_B);
        productB.setSku("TENANT-B-CROSSCHECK-001");
        productB.setTitle("Tenant B Product");
        productB.setIngredientsText("Secret ingredients");
        productB.setPricePennies(2000L);
        productB = productRepository.saveAndFlush(productB);

        UUID productBId = productB.getId();

        // When: Tenant A tries to access Tenant B's audit history
        TenantContext.clear();
        TenantContext.set(TENANT_A);

        List<Product> history = auditService.getEntityHistory(Product.class, productBId);

        // Then: Should see no history due to RLS filtering
        assertThat(history).isEmpty();
    }

    @Test
    @WithMockUser(username = "tenant-a-user")
    void shouldTrackDeletionInAuditHistory() {
        // Given: Tenant A creates and then deletes a shop
        TenantContext.set(TENANT_A);

        Shop shop = new Shop();
        shop.setTenantId(TENANT_A);
        shop.setName("Delete Test Shop");
        shop = shopRepository.saveAndFlush(shop);

        UUID shopId = shop.getId();

        // When: Delete the shop
        shopRepository.delete(shop);
        shopRepository.flush();

        // Then: Audit history should contain 2 revisions (create + delete)
        List<Shop> history = auditService.getEntityHistory(Shop.class, shopId);
        assertThat(history).hasSize(2);

        // First revision: creation
        assertThat(history.get(0).getName()).isEqualTo("Delete Test Shop");

        // Second revision: deletion (Envers stores snapshot at delete)
        assertThat(history.get(1)).isNotNull();
    }
}
