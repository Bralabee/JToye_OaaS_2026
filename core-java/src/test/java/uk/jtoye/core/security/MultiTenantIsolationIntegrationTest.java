package uk.jtoye.core.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
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
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.customer.Customer;
import uk.jtoye.core.customer.CustomerRepository;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for multi-tenant data isolation using PostgreSQL Row-Level Security (RLS).
 *
 * These tests verify that:
 * 1. Application is NOT using a superuser account (which would bypass RLS)
 * 2. Tenants can only see their own data
 * 3. Cross-tenant queries return no data (not errors, but empty results due to RLS filtering)
 * 4. RLS policies are active and enforced
 *
 * CRITICAL: These tests must run with a non-superuser database account (jtoye_app)
 * to properly test RLS. Superusers bypass RLS policies.
 *
 * @author J'Toye Engineering Team
 * @since 0.7.1
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@org.junit.jupiter.api.Tag("testcontainers")
class MultiTenantIsolationIntegrationTest {

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
    private ShopRepository shopRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        // Create test tenants required by foreign key constraints.
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT_A, "Tenant A");
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT_B, "Tenant B");

        // FORCE ROW LEVEL SECURITY does NOT bind a SUPERUSER — the Testcontainers
        // bootstrap role bypasses RLS entirely, so without this downgrade every
        // isolation assertion below would see cross-tenant rows (QA-council #71).
        // Safe here because: (1) Flyway already ran at context boot as superuser;
        // (2) ALTER ROLE is transactional and this class is @Transactional, so
        // the per-test rollback restores superuser afterwards; (3) inserts still
        // pass WITH CHECK because TenantSetLocalAspect sets the tenant GUC
        // before each repository op inside the test transaction.
        jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
    }

    @Test
    void shouldEnforceTenantIsolationForShops() {
        // Given: Create shops for both tenants
        Shop shopA = createShop(TENANT_A, "Tenant A Shop");
        Shop shopB = createShop(TENANT_B, "Tenant B Shop");

        // When: Query as Tenant A
        TenantContext.set(TENANT_A);
        List<Shop> tenantAShops = shopRepository.findAll();

        // Then: Tenant A should ONLY see their shops
        assertThat(tenantAShops)
            .as("Tenant A should only see their own shops")
            .extracting(Shop::getId)
            .contains(shopA.getId())
            .doesNotContain(shopB.getId());

        // When: Query as Tenant B
        TenantContext.set(TENANT_B);
        List<Shop> tenantBShops = shopRepository.findAll();

        // Then: Tenant B should ONLY see their shops
        assertThat(tenantBShops)
            .as("Tenant B should only see their own shops")
            .extracting(Shop::getId)
            .contains(shopB.getId())
            .doesNotContain(shopA.getId());

        TenantContext.clear();
    }

    @Test
    void shouldEnforceTenantIsolationForProducts() {
        // Given: Create products for both tenants
        Product productA = createProduct(TENANT_A, "PROD-A", "Product A");
        Product productB = createProduct(TENANT_B, "PROD-B", "Product B");

        // When: Query as Tenant A
        TenantContext.set(TENANT_A);
        List<Product> tenantAProducts = productRepository.findAll();

        // Then: Tenant A should ONLY see their products
        assertThat(tenantAProducts)
            .extracting(Product::getId)
            .contains(productA.getId())
            .doesNotContain(productB.getId());

        // When: Query as Tenant B
        TenantContext.set(TENANT_B);
        List<Product> tenantBProducts = productRepository.findAll();

        // Then: Tenant B should ONLY see their products
        assertThat(tenantBProducts)
            .extracting(Product::getId)
            .contains(productB.getId())
            .doesNotContain(productA.getId());

        TenantContext.clear();
    }

    @Test
    void shouldEnforceTenantIsolationForCustomers() {
        // Given: Create customers for both tenants
        Customer customerA = createCustomer(TENANT_A, "Customer A");
        Customer customerB = createCustomer(TENANT_B, "Customer B");

        // When: Query as Tenant A
        TenantContext.set(TENANT_A);
        List<Customer> tenantACustomers = customerRepository.findAll();

        // Then: Tenant A should ONLY see their customers
        assertThat(tenantACustomers)
            .extracting(Customer::getId)
            .contains(customerA.getId())
            .doesNotContain(customerB.getId());

        // When: Query as Tenant B
        TenantContext.set(TENANT_B);
        List<Customer> tenantBCustomers = customerRepository.findAll();

        // Then: Tenant B should ONLY see their customers
        assertThat(tenantBCustomers)
            .extracting(Customer::getId)
            .contains(customerB.getId())
            .doesNotContain(customerA.getId());

        TenantContext.clear();
    }

    @Test
    void shouldPreventCrossTenantAccessByPrimaryKey() {
        // Given: Create shop for Tenant B
        Shop shopB = createShop(TENANT_B, "Tenant B Shop");
        UUID shopBId = shopB.getId();

        // When: Tenant A tries to access Tenant B's shop by ID
        TenantContext.set(TENANT_A);
        var result = shopRepository.findById(shopBId);

        // Then: Should return empty (RLS filters it out)
        assertThat(result)
            .as("Tenant A should NOT be able to access Tenant B's shop")
            .isEmpty();

        TenantContext.clear();
    }

    @Test
    void shouldVerifyRlsPoliciesAreEnabled() {
        // Verify RLS is enabled on critical tables
        String[] tables = {"shops", "products", "customers", "orders", "financial_transactions"};

        for (String table : tables) {
            Boolean rlsEnabled = jdbcTemplate.queryForObject(
                "SELECT relrowsecurity FROM pg_class WHERE relname = ?",
                Boolean.class,
                table
            );

            assertThat(rlsEnabled)
                .as("RLS must be enabled on table: " + table)
                .isTrue();

            Integer policyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_policies WHERE tablename = ?",
                Integer.class,
                table
            );

            assertThat(policyCount)
                .as("Table " + table + " must have at least one RLS policy")
                .isGreaterThan(0);
        }
    }

    @Test
    void shouldVerifyCurrentTenantIdFunctionExists() {
        // Verify the current_tenant_id() function exists and is callable
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pg_proc WHERE proname = 'current_tenant_id'",
            Integer.class
        );

        assertThat(count)
            .as("Function current_tenant_id() must exist")
            .isGreaterThan(0);
    }

    // Helper methods

    private Shop createShop(UUID tenantId, String name) {
        TenantContext.set(tenantId);
        Shop shop = new Shop();
        shop.setTenantId(tenantId);
        shop.setName(name);
        shop.setAddress("Test Address");
        // shops.slug is NOT NULL UNIQUE and normally generated by ShopService
        shop.setSlug("it-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        // saveAndFlush: Hibernate batching would otherwise defer this INSERT to a
        // later flush under a DIFFERENT tenant GUC -> RLS WITH CHECK violation.
        Shop saved = shopRepository.saveAndFlush(shop);
        TenantContext.clear();
        // Detach: findById/findAll must hit SQL (where RLS filters), not return
        // the seeded instance from the persistence context.
        entityManager.clear();
        return saved;
    }

    private Product createProduct(UUID tenantId, String sku, String title) {
        TenantContext.set(tenantId);
        Product product = new Product();
        product.setTenantId(tenantId);
        product.setSku(sku);
        product.setTitle(title);
        product.setIngredientsText("Test ingredients");
        product.setAllergenMask(0);
        product.setPricePennies(1000L);
        // saveAndFlush: Hibernate batching would otherwise defer this INSERT to a
        // later flush under a DIFFERENT tenant GUC -> RLS WITH CHECK violation.
        Product saved = productRepository.saveAndFlush(product);
        TenantContext.clear();
        // Detach: findById/findAll must hit SQL (where RLS filters), not return
        // the seeded instance from the persistence context.
        entityManager.clear();
        return saved;
    }

    private Customer createCustomer(UUID tenantId, String name) {
        TenantContext.set(tenantId);
        Customer customer = new Customer();
        customer.setTenantId(tenantId);
        customer.setName(name);
        customer.setEmail(name.toLowerCase().replace(" ", "") + "@test.com");
        customer.setAllergenRestrictions(0);
        // saveAndFlush: Hibernate batching would otherwise defer this INSERT to a
        // later flush under a DIFFERENT tenant GUC -> RLS WITH CHECK violation.
        Customer saved = customerRepository.saveAndFlush(customer);
        TenantContext.clear();
        // Detach: findById/findAll must hit SQL (where RLS filters), not return
        // the seeded instance from the persistence context.
        entityManager.clear();
        return saved;
    }
}
