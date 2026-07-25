package uk.jtoye.core.common.idempotency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.exception.IdempotencyConflictException;
import uk.jtoye.core.exception.IdempotencyPayloadMismatchException;
import uk.jtoye.core.order.OrderController;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.security.TenantContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #204 (AI-2) — app-layer proof that {@code POST /api/v1/orders} honors
 * the uniform {@code Idempotency-Key} contract: same-key replay returns the
 * ORIGINAL order and mints zero duplicate rows, a CONCURRENT same-key race
 * yields exactly one order (never a 500), and same-key/different-payload is
 * rejected 422.
 *
 * <p>Drives the real {@link OrderController#createOrder} method so the
 * {@code @Idempotent} header branch is exercised end-to-end through the
 * controller into {@link IdempotencyService#execute}.
 *
 * <p>Scaffold copied from {@code ConcurrentStockDecrementIntegrationTest}
 * (Testcontainers Postgres, Flyway on, ddl-auto=none, rate-limiting off,
 * RabbitMQ stubbed). The class is deliberately NOT {@code @Transactional} so
 * each {@code execute} runs its own committed transaction — required for both
 * the replay-observes-committed-row and the concurrent-race assertions.
 *
 * <p><b>RLS note:</b> the Testcontainers bootstrap role is a Postgres SUPERUSER
 * which bypasses FORCE RLS — fine HERE because these tests prove app-layer
 * replay/race behavior, not RLS enforcement. The cross-tenant RLS proof lives
 * in {@link IdempotencyKeysRlsPolicyIntegrationTest} under the NOSUPERUSER
 * role-downgrade. The Phase 13 {@code TenantContext} leakage lesson is honored:
 * every thread sets + clears its own context in try/finally.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
// Phase 25 [CR-01]: POST /orders is now gated by @PreAuthorize("hasAuthority('SCOPE_orders:write')"),
// and OrderService.createOrder additionally requires SHOP_MANAGER on the target shop (VSA-02). This
// test drives the proxied controller bean directly, so @EnableMethodSecurity needs a SecurityContext.
// Pre-Phase-25 it ran with NO auth — the ShopAccessService "internal caller" bypass treated that as an
// implicit GROUP_ADMIN. A realm-admin principal (ROLE_admin = implicit GROUP_ADMIN) preserves exactly
// that access intent, and SCOPE_orders:write satisfies the new gate. Supplied class-wide for the
// main-thread cases; the concurrent worker threads install it explicitly (ThreadLocal context does not
// propagate to the pool).
@WithMockUser(authorities = {"ROLE_admin", "SCOPE_orders:write"})
class OrderIdempotencyIntegrationTest {

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
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired OrderController orderController;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000204");

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        seedTenantIdempotent(TENANT_ID, "Idempotency Tenant");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void sameKeyReplay_returnsOriginalOrder_zeroDuplicates() {
        // Fresh shop+product per test so count(*) WHERE shop_id is isolated from
        // orders created by sibling test methods on the same container.
        Fixture fx = seedFreshShopWithProduct(/*stock=*/ 100);
        String key = "order-key-" + UUID.randomUUID();
        TenantContext.set(TENANT_ID);
        try {
            CreateOrderRequest request = buildRequest(fx, "replay notes");

            ResponseEntity<OrderDto> first = orderController.createOrder(request, key);
            ResponseEntity<OrderDto> second = orderController.createOrder(request, key);

            assertThat(first.getBody()).isNotNull();
            assertThat(second.getBody()).isNotNull();
            assertThat(second.getBody().getId())
                    .as("replay returns the ORIGINAL order id")
                    .isEqualTo(first.getBody().getId());
            assertThat(second.getBody().getOrderNumber())
                    .as("replay returns the ORIGINAL order number")
                    .isEqualTo(first.getBody().getOrderNumber());
            assertThat(second.getStatusCode().value()).isEqualTo(201);

            assertThat(countOrders(fx.shopId))
                    .as("exactly one order row despite the repeated key")
                    .isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void concurrentSameKey_exactlyOneRow_no500() throws Exception {
        Fixture fx = seedFreshShopWithProduct(/*stock=*/ 100);
        String key = "order-key-concurrent-" + UUID.randomUUID();
        CreateOrderRequest request = buildRequest(fx, "concurrent notes");

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> worker = () -> {
                TenantContext.set(TENANT_ID);
                // Phase 25 [CR-01]: the pooled worker thread does not inherit the class-level
                // @WithMockUser SecurityContext, so install it explicitly — ROLE_admin (implicit
                // GROUP_ADMIN for the VSA-02 shop gate) + SCOPE_orders:write (the @PreAuthorize gate).
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken("idem-worker", "n/a",
                                java.util.List.of(new SimpleGrantedAuthority("ROLE_admin"),
                                        new SimpleGrantedAuthority("SCOPE_orders:write"))));
                try {
                    gate.await();
                    return orderController.createOrder(request, key);
                } catch (Throwable t) {
                    return t;
                } finally {
                    TenantContext.clear();
                    SecurityContextHolder.clearContext();
                }
            };

            Future<Object> fA = pool.submit(worker);
            Future<Object> fB = pool.submit(worker);
            gate.countDown();

            List<Object> results = new ArrayList<>(2);
            results.add(fA.get(30, SECONDS));
            results.add(fB.get(30, SECONDS));

            List<UUID> createdIds = new ArrayList<>();
            for (Object result : results) {
                if (result instanceof ResponseEntity<?> response) {
                    Object body = response.getBody();
                    assertThat(body).isInstanceOf(OrderDto.class);
                    createdIds.add(((OrderDto) body).getId());
                } else if (result instanceof IdempotencyConflictException) {
                    // Acceptable: the in-flight race loser — never a duplicate, never a 500.
                } else {
                    throw new AssertionError("Unexpected result from concurrent create: " + result,
                            result instanceof Throwable t ? t : null);
                }
            }

            // Whatever the race outcome, at least one create returned an order, and
            // every returned order is the SAME id (one created, the other replayed).
            assertThat(createdIds).as("at least one create returned an order").isNotEmpty();
            assertThat(createdIds).as("all returned orders are the same id (no duplicate)")
                    .containsOnly(createdIds.get(0));

            TenantContext.set(TENANT_ID);
            try {
                assertThat(countOrders(fx.shopId))
                        .as("exactly one order row from the concurrent same-key race")
                        .isEqualTo(1);
            } finally {
                TenantContext.clear();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameKeyDifferentPayload_returns422() {
        Fixture fx = seedFreshShopWithProduct(/*stock=*/ 100);
        String key = "order-key-mismatch-" + UUID.randomUUID();
        TenantContext.set(TENANT_ID);
        try {
            orderController.createOrder(buildRequest(fx, "payload A"), key);

            assertThatThrownBy(() -> orderController.createOrder(buildRequest(fx, "materially different payload B"), key))
                    .as("same key + different body maps to 422")
                    .isInstanceOf(IdempotencyPayloadMismatchException.class);
        } finally {
            TenantContext.clear();
        }
    }

    // ---- helpers ----

    /** A freshly-seeded shop + product pair, isolated per test method. */
    private record Fixture(UUID shopId, UUID productId) {}

    private CreateOrderRequest buildRequest(Fixture fx, String notes) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(fx.shopId());
        request.setCustomerName("Idem Customer");
        request.setCustomerEmail("idem-customer@example.com");
        request.setNotes(notes);
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(fx.productId());
        item.setQuantity(1);
        request.setItems(List.of(item));
        return request;
    }

    private Integer countOrders(UUID shopId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE shop_id = ?", Integer.class, shopId);
    }

    private void seedTenantIdempotent(UUID id, String name) {
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                id, name);
    }

    private Fixture seedFreshShopWithProduct(int stock) {
        TenantContext.set(TENANT_ID);
        try {
            String discriminator = UUID.randomUUID().toString().substring(0, 8);
            UUID shopId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, delivery_fee_pennies, minimum_order_pennies, version) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                    shopId, TENANT_ID, "Idempotency Shop " + discriminator, "shop-idem-" + discriminator);
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, ?, 0)",
                    productId, TENANT_ID, "SKU-IDEM-" + discriminator, "Croissant",
                    "butter, flour", shopId, stock);
            return new Fixture(shopId, productId);
        } finally {
            TenantContext.clear();
        }
    }
}
