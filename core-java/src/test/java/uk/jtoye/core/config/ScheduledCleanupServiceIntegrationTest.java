package uk.jtoye.core.config;

import com.stripe.exception.ApiConnectionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderService;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.payment.PaymentService;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("testcontainers")
// #418: cleanupStaleDraftOrders() is @Scheduled (cron) and driven by hand here.
// A cron trigger has no startup run, but the other nine @Scheduled workers in the
// context do, and this class sets cleanup.stale-draft-hours=0 — so every draft it
// creates is instantly eligible. Removing the triggers keeps the sweep this class
// asserts on the only one that ever runs.
@org.springframework.context.annotation.Import(
        uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig.class)
// #283: seeds its DRAFT orders through orderService.createOrder — a gated call made by the
// harness, not by the ScheduledCleanupService under test (which reaches no gate at all).
@uk.jtoye.core.testsupport.AsSystemHarness
class ScheduledCleanupServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> { uk.jtoye.core.testsupport.IntegrationTestSupport.ensureDowngradableTestRole(postgres); return postgres.getJdbcUrl(); });
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // application-test.yml defaults to H2; override every H2-specific property so
        // the Testcontainers Postgres is actually used (mirrors RlsContractTest).
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // ddl-auto: none so the Flyway-managed schema (with its RLS policies) is the
        // sole source of truth rather than Hibernate create-drop clobbering it.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        // RabbitMQ (OrderEventPublisher) points at a dead port with listener
        // auto-startup disabled so the context boots without a live broker.
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        // Any DRAFT created before the job runs counts as stale — no backdating needed.
        registry.add("cleanup.stale-draft-hours", () -> "0");
    }

    @Autowired private ScheduledCleanupService cleanupService;
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager txManager;
    // issue #1038: the job now cancels a card draft's PaymentIntent through
    // PaymentService before deleting it; the Stripe edge is replaced so the
    // sweep can be driven to "cancelled" and to "Stripe failed" deterministically.
    @MockitoBean private PaymentService paymentService;

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
    void tearDown() throws Exception {
        // issue #1038: tests in this class must not depend on their order. A kept
        // draft would otherwise survive into the next test's precondition, and a
        // NOSUPERUSER role would make the next BeforeEach seed under RLS.
        for (UUID tenantId : List.of(TENANT_A, TENANT_B)) {
            TenantContext.set(tenantId);
            try {
                new TransactionTemplate(txManager).executeWithoutResult(status ->
                        orderRepository.deleteAll(orderRepository.findByStatus(OrderStatus.DRAFT)));
            } finally {
                TenantContext.clear();
            }
        }
        // ALTER ROLE ... NOSUPERUSER is global and outlives the test, and a
        // NOSUPERUSER role cannot re-grant itself, so the restore goes through the
        // _jtoye_admin superuser that ensureDowngradableTestRole provisioned. A
        // swallowed failure here would leave every later test running under RLS.
        org.testcontainers.containers.Container.ExecResult restore = postgres.execInContainer(
                "psql", "-v", "ON_ERROR_STOP=1", "-U", "_jtoye_admin", "-d", postgres.getDatabaseName(),
                "-c", "ALTER ROLE \"" + postgres.getUsername() + "\" SUPERUSER");
        assertThat(restore.getExitCode())
                .as("SUPERUSER restore failed: %s", restore.getStderr())
                .isZero();
        TenantContext.clear();
    }

    @Test
    void cleanupAcrossMultipleTenantsCompletesAndDeletesEachTenantsDrafts() {
        // The Testcontainers bootstrap role is a SUPERUSER, which BYPASSES RLS
        // entirely (verified: FORCE RLS + superuser still reads across tenants).
        // Seeding above ran as superuser so its inserts were not subject to RLS
        // WITH CHECK timing; downgrade to NOSUPERUSER now so the cleanup below
        // runs under genuinely-enforced RLS — the precondition of the M1 defect.
        jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");

        // Precondition: both tenants have exactly one stale DRAFT.
        assertThat(draftCountFor(TENANT_A)).isEqualTo(1);
        assertThat(draftCountFor(TENANT_B)).isEqualTo(1);

        // The job must complete without StaleStateException (the original defect).
        assertThatCode(() -> cleanupService.cleanupStaleDraftOrders()).doesNotThrowAnyException();

        // Both tenants' stale drafts (and their cascaded order_items) are gone.
        assertThat(draftCountFor(TENANT_A)).as("Tenant A drafts cleaned").isZero();
        assertThat(draftCountFor(TENANT_B)).as("Tenant B drafts cleaned").isZero();
    }

    @Test
    void cardDraftsAreDeletedOnlyAfterTheirIntentIsCancelled_andKeptWhenTheCancelFails() throws Exception {
        // issue #1038: one card draft per tenant beside the no-payment drafts the
        // BeforeEach seeded. Tenant A's cancel fails at Stripe; tenant B's succeeds.
        UUID cardA = seedStaleDraftWithItem(TENANT_A, "SKU-CA");
        markAsCardDraft(TENANT_A, cardA, "pi_it1038_a_cancel_fails");
        UUID cardB = seedStaleDraftWithItem(TENANT_B, "SKU-CB");
        markAsCardDraft(TENANT_B, cardB, "pi_it1038_b_cancelled");

        when(paymentService.isConfigured()).thenReturn(true);
        when(paymentService.directChargeAccountFor(any())).thenReturn(Optional.empty());
        when(paymentService.cancelAbandonedPaymentIntent("pi_it1038_a_cancel_fails", null))
                .thenThrow(new ApiConnectionException("simulated Stripe outage, issue #1038 IT"));
        when(paymentService.cancelAbandonedPaymentIntent("pi_it1038_b_cancelled", null))
                .thenReturn(PaymentService.STRIPE_STATUS_CANCELED);

        // Same downgrade as the M1 test: FORCE RLS is enforced for the sweep below.
        jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");

        assertThat(draftCountFor(TENANT_A)).isEqualTo(2);
        assertThat(draftCountFor(TENANT_B)).isEqualTo(2);

        assertThatCode(() -> cleanupService.cleanupStaleDraftOrders()).doesNotThrowAnyException();

        // Tenant A: the no-payment draft is gone, the card draft whose cancel failed is KEPT.
        assertThat(draftCountFor(TENANT_A)).as("Tenant A keeps only the card draft Stripe could not cancel")
                .isEqualTo(1);
        Optional<Order> keptA = findPinned(TENANT_A, cardA);
        assertThat(keptA).as("tenant A card draft kept").isPresent();
        assertThat(keptA.get().getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(keptA.get().getPaymentReference()).isEqualTo("pi_it1038_a_cancel_fails");
        // Tenant B: both drafts gone, the card one only after its cancel returned canceled.
        assertThat(draftCountFor(TENANT_B)).as("Tenant B drafts cleaned").isZero();
        assertThat(findPinned(TENANT_B, cardB)).isEmpty();

        verify(paymentService, times(1)).cancelAbandonedPaymentIntent("pi_it1038_a_cancel_fails", null);
        verify(paymentService, times(1)).cancelAbandonedPaymentIntent("pi_it1038_b_cancelled", null);
    }

    private Optional<Order> findPinned(UUID tenantId, UUID orderId) {
        TenantContext.set(tenantId);
        try {
            return new TransactionTemplate(txManager).execute(status -> orderRepository.findById(orderId));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * issue #1038: turn a seeded draft into a storefront card draft (PENDING plus
     * its pi_ id), the shape PublicStorefrontService writes. Through the
     * repository with the tenant pinned in a transaction, never a bare UPDATE: a
     * bare UPDATE under FORCE RLS with no tenant GUC matches zero rows.
     */
    private void markAsCardDraft(UUID tenantId, UUID orderId, String intentId) {
        TenantContext.set(tenantId);
        try {
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                Order order = orderRepository.findById(orderId).orElseThrow();
                order.setPaymentReference(intentId);
                order.setPaymentStatus(PaymentStatus.PENDING);
                orderRepository.save(order);
            });
        } finally {
            TenantContext.clear();
        }
    }

    private int draftCountFor(UUID tenantId) {
        TenantContext.set(tenantId);
        try {
            // Read inside a transaction so TenantSetLocalAspect applies the RLS
            // GUC — the aspect no-ops without an active transaction, and a bare
            // derived query method does not start one, so an unscoped read would
            // see current_tenant_id() = NULL and return zero rows.
            return new TransactionTemplate(txManager).execute(status ->
                    orderRepository.findByStatus(OrderStatus.DRAFT).size());
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedStaleDraftWithItem(UUID tenantId, String skuPrefix) {
        // issue #1038: suffixed so a second test's seed never collides with the
        // shop slug and SKU an earlier test's seed left behind.
        String sku = skuPrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        TenantContext.set(tenantId);
        try {
            Shop shop = new Shop();
            shop.setTenantId(tenantId);
            shop.setName("Shop " + sku);
            shop.setAddress("Address " + sku);
            // Seed bypasses ShopService (which generates the slug), so set the
            // NOT-NULL unique slug explicitly. sku is unique per tenant.
            shop.setSlug("shop-" + sku.toLowerCase());
            shop = shopRepository.save(shop);

            Product product = new Product();
            product.setTenantId(tenantId);
            product.setSku(sku);
            product.setTitle("Product " + sku);
            product.setIngredientsText("Ingredients");
            product.setAllergenMask(0);
            product.setPricePennies(1000L);
            product.setShopId(shop.getId()); // 37-40 (RWO-017): createOrder refuses a shop-less product
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
            return orderService.createOrder(request).getId();
        } finally {
            TenantContext.clear();
        }
    }
}
