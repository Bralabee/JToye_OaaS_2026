package uk.jtoye.core.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.customer.CustomerController.CreateCustomerRequest;
import uk.jtoye.core.customer.CustomerController.CustomerDto;
import uk.jtoye.core.customer.CustomerRepository;
import uk.jtoye.core.customer.CustomerService;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.order.OrderService;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 25 [AI-02] — the WRITE-SIDE cross-tenant RLS proof under the MCP write
 * credential. The MCP {@code create_order} / {@code create_customer} tools are
 * thin forwarders (no tenant decision of their own); the sole cross-tenant
 * boundary is Postgres FORCE ROW LEVEL SECURITY in core. This test proves that
 * boundary at the service (HTTP-adjacent) layer:
 *
 * <ul>
 *   <li><b>create_order, foreign shopId → 404</b>: a caller carrying tenant A's
 *       GUC that references tenant B's {@code shopId} cannot create against it —
 *       {@code shopRepository.findById} is RLS-hidden, so {@code createOrder}
 *       resolves {@link ResourceNotFoundException} (HTTP 404), never a
 *       cross-tenant success.</li>
 *   <li><b>create_customer lands under the caller tenant only</b>: customers have
 *       no foreign-id vector (the tenant is implicit from the GUC), so the proof
 *       is that the created row is tagged with — and visible only to — tenant A.</li>
 * </ul>
 *
 * <p><b>Why the NOSUPERUSER downgrade is mandatory (falsifiability).</b> The
 * Testcontainers bootstrap role is a Postgres SUPERUSER, which bypasses even
 * FORCE RLS. So the isolation proof is only real under a downgraded
 * {@code rls_test_role} (NOSUPERUSER NOBYPASSRLS) applied with
 * {@code SET LOCAL ROLE} inside the transaction (the house pattern from
 * {@code IdempotencyKeysRlsPolicyIntegrationTest}). The
 * {@link #superuserBypassesForceRls_provesTheDowngradeIsLoadBearing()} case makes
 * this explicit: WITHOUT the downgrade the identical tenant-A→tenant-B shop
 * lookup returns the row — i.e. a superuser Testcontainers role would falsely
 * pass a cross-tenant create. The two together are the RED/GREEN.
 *
 * <p>The service methods are invoked with NO {@code Authentication} on the thread
 * (the trusted-internal path): {@code ShopAccessService.onRequest()} returns
 * immediately and {@code isGroupAdmin()} short-circuits true via
 * {@code isInternalCaller()}, so the VSA-02 shop gate passes WITHOUT touching
 * {@code shop_staff} — the only boundary left to prove is RLS itself, which is
 * exactly the point.
 *
 * <p>Idempotent-replay for these two creates is proven elsewhere and NOT
 * duplicated here: {@code OrderIdempotencyIntegrationTest} covers
 * {@code orders.create} (same-key replay → the ORIGINAL order + zero duplicates,
 * concurrent race → exactly one row, same-key/different-body → 422), and
 * {@code IdempotencyKeysRlsPolicyIntegrationTest} proves the reserve-first store
 * is itself FORCE-RLS tenant-scoped. Customer create rides the identical
 * {@code IdempotencyService.execute} path.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class CrossTenantMcpWriteRlsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private OrderService orderService;
    @Autowired private CustomerService customerService;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private JdbcTemplate jdbc;

    @PersistenceContext private EntityManager entityManager;

    private static final String RLS_TEST_ROLE = "rls_test_role";

    private UUID tenantA;
    private UUID tenantB;
    private UUID tenantBShopId;

    @BeforeEach
    void seed() {
        // Provision a dedicated non-superuser role (idempotent) so FORCE RLS fires
        // under SET LOCAL ROLE — a superuser bootstrap role bypasses it entirely.
        jdbc.execute("DO $$ BEGIN " +
                "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN " +
                "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; " +
                "    GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "    GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "    GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "  END IF; " +
                "END $$");

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        // tenants registry is not RLS-scoped; seed both as the (superuser) bootstrap role.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "test-A-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "test-B-" + tenantB);

        // Seed a shop under tenant B (as superuser: WITH CHECK bypassed). The GUC is
        // still set to tenant B so the row's tenant_id column is tenant B's.
        TenantContext.set(tenantB);
        Shop shopB = new Shop();
        shopB.setTenantId(tenantB);
        shopB.setName("Tenant B Shop");
        shopB.setAddress("Test Address");
        // shops.slug is NOT NULL UNIQUE and normally generated by ShopService.
        shopB.setSlug("it-" + UUID.randomUUID().toString().substring(0, 8));
        // saveAndFlush: Hibernate batching would otherwise defer this INSERT to a later
        // flush under a different GUC -> RLS WITH CHECK violation.
        tenantBShopId = shopRepository.saveAndFlush(shopB).getId();
        TenantContext.clear();
        // Detach: subsequent findById must hit SQL (where RLS filters), not return the
        // seeded instance from the persistence context.
        entityManager.clear();
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** Downgrade the current transaction's role so FORCE RLS is actually enforced. */
    private void dropSuperuserForTransaction() {
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
    }

    @Test
    @DisplayName("create_order with tenant-A GUC + tenant-B shopId resolves 404 — the foreign shop is RLS-hidden")
    void crossTenantCreateOrder_foreignShopId_resolvesNotFound() {
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();

        // The RLS mechanism createOrder relies on: tenant A cannot see tenant B's shop.
        assertThat(shopRepository.findById(tenantBShopId))
                .as("tenant A must NOT see tenant B's shop under FORCE RLS")
                .isEmpty();

        // The write path consuming it: a create against the foreign shopId 404s.
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShopId(tenantBShopId);
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(UUID.randomUUID());
        item.setQuantity(1);
        req.setItems(List.of(item));

        assertThatThrownBy(() -> orderService.createOrder(req))
                .as("a tenant-A token cannot create an order against a tenant-B shop")
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Shop not found");
    }

    @Test
    @DisplayName("WITHOUT the downgrade a superuser bypasses FORCE RLS — proving the NOSUPERUSER role is load-bearing")
    void superuserBypassesForceRls_provesTheDowngradeIsLoadBearing() {
        // Deliberately do NOT downgrade: stay on the Testcontainers bootstrap SUPERUSER.
        TenantContext.set(tenantA);
        entityManager.clear();

        // A superuser bypasses even FORCE RLS, so tenant A "sees" tenant B's shop by id.
        // This is the RED the real proof above would show if run under a superuser role:
        // the cross-tenant create would falsely succeed. The downgrade is what makes it real.
        assertThat(shopRepository.findById(tenantBShopId))
                .as("a superuser bypasses FORCE RLS — the cross-tenant lookup falsely resolves")
                .isPresent();
    }

    @Test
    @DisplayName("create_customer under tenant A lands the row ONLY under tenant A (RLS-scoped, no foreign vector)")
    void createCustomer_landsUnderCallerTenantOnly() {
        TenantContext.set(tenantA);
        dropSuperuserForTransaction();

        CreateCustomerRequest req = new CreateCustomerRequest(
                "MCP Agent Customer", "mcp-agent@example.com", null, null);
        CustomerDto created = customerService.createCustomer(req);

        assertThat(created.tenantId())
                .as("the created customer is tagged with the caller's tenant (A)")
                .isEqualTo(tenantA);

        // Detach so the cross-tenant read hits SQL (RLS), not the persistence context.
        entityManager.clear();

        // Under tenant B's GUC (still NOSUPERUSER), tenant A's customer is invisible.
        TenantContext.set(tenantB);
        assertThat(customerRepository.findById(created.id()))
                .as("tenant B must NOT see tenant A's freshly-created customer under FORCE RLS")
                .isEmpty();
    }
}
