package uk.jtoye.core.storefront;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.exception.IdempotencyConflictException;
import uk.jtoye.core.exception.IdempotencyPayloadMismatchException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storefront.dto.GuestOrderConfirmation;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * QA council 20260902-134741, Cluster E (API-3, API-4, INT-15) — the guest checkout honours the
 * platform's own V50 {@code Idempotency-Key} contract ({@code docs/idempotency.md}).
 *
 * <p>Before this class the storefront had a bespoke, key-only dedup: {@code findByTenantIdAndIdempotencyKey}
 * on {@code orders}. It ignored the {@code Idempotency-Key} header (API-3), replayed the FIRST order
 * with 201 when the same key arrived with a DIFFERENT basket (API-4 — the customer is charged for
 * and delivered a basket they did not submit), and a concurrent same-key pair raced past the
 * check-then-act and surfaced the raw {@code idx_orders_idempotency} violation as
 * {@code errors/duplicate "Data integrity constraint violated"} (INT-15).
 *
 * <p>The fix routes the keyed path through {@code IdempotencyService.executeWithoutStoringResponse}
 * (adjudication A3): the reservation row is the serialisation point, the request hash is the
 * payload-mismatch detector, and the response body is deliberately NEVER persisted because
 * {@code GuestOrderConfirmation.clientSecret} is a Stripe credential (WR-02 keeps re-fetching it).
 *
 * <p>Real Postgres (Testcontainers) because the reservation semantics live in
 * {@code INSERT ... ON CONFLICT DO NOTHING} and in the unique index — H2 would prove nothing.
 * The class is NOT {@code @Transactional}: each call must commit so the replay observes a
 * committed reservation and the concurrent arm contends for real. Test profile has no Stripe key,
 * so every order takes the COD branch (PENDING, no client secret) — the idempotency contract is
 * independent of the payment branch.
 *
 * <p><b>RLS note:</b> the Testcontainers bootstrap role is a SUPERUSER, so these arms prove the
 * app-layer contract, not RLS enforcement ({@code IdempotencyKeysRlsPolicyIntegrationTest} owns
 * that under the NOSUPERUSER downgrade). The tenant GUC is nevertheless pinned on every path.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class GuestCheckoutIdempotencyIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GuestCheckoutIdempotencyIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired PublicStorefrontService publicStorefrontService;
    @Autowired JdbcTemplate jdbcTemplate;

    /** Dedicated tenant so a parallel fork's fixtures cannot collide on slug/SKU. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
    private static final String SHOP_SLUG = "shop-qa0902-guest-idempotency";
    /** The logical operation id the guest path reserves under — namespaced away from {@code orders.create}. */
    private static final String ENDPOINT = "storefront.orders.create";

    private UUID shopId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        seedTenantIdempotent(TENANT_ID, "QA 0902 Cluster E Tenant");
        shopId = seedShopIdempotent(TENANT_ID, SHOP_SLUG);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // (a) same key + same body -> replay of the ORIGINAL order, one row,
    //     and the reservation row carries a status but NO response body.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("same key + same body: the second submit replays the first order, mints no second row, and the store holds NO response body (API-3/A3)")
    void sameKeyAndBody_replaysTheOriginalOrder_andStoresNoResponseBody() {
        UUID productId = seedProduct("SKU-QA0902-REPLAY");
        String key = "qa0902-replay-" + UUID.randomUUID();

        GuestOrderConfirmation first = publicStorefrontService.createGuestOrder(SHOP_SLUG, guestRequest(productId, 2, key));
        GuestOrderConfirmation second = publicStorefrontService.createGuestOrder(SHOP_SLUG, guestRequest(productId, 2, key));

        assertThat(second.getOrderNumber())
                .as("the replay returns the ORIGINAL order number")
                .isEqualTo(first.getOrderNumber());
        assertThat(countOrdersByKey(key))
                .as("exactly one order row despite the repeated key")
                .isEqualTo(1);

        // The V50 store is the serialisation point, and it must NOT hold the confirmation:
        // GuestOrderConfirmation.clientSecret is a Stripe credential (adjudication A3).
        Map<String, Object> row = reservationRow(key);
        assertThat(row.get("response_status"))
                .as("the reservation is COMPLETED (a NULL status would read as in-flight -> spurious 409 forever)")
                .isEqualTo(201);
        assertThat(row.get("response_body"))
                .as("the guest path must never persist the response body")
                .isNull();
        assertThat(row.get("request_hash"))
                .as("the request hash is what detects a same-key/different-body reuse")
                .isNotNull();
        log.info("CLUSTER-E (a): order={} reservation status={} body={}",
                first.getOrderNumber(), row.get("response_status"), row.get("response_body"));
    }

    // ------------------------------------------------------------------
    // (b) same key + DIFFERENT body -> 422, never a 201 carrying the other basket.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("same key + different body: refused as IdempotencyPayloadMismatchException (422), the first order is NOT replayed (API-4)")
    void sameKeyDifferentBody_isRefusedAsPayloadMismatch() {
        UUID productId = seedProduct("SKU-QA0902-MISMATCH");
        String key = "qa0902-mismatch-" + UUID.randomUUID();

        GuestOrderConfirmation first = publicStorefrontService.createGuestOrder(SHOP_SLUG, guestRequest(productId, 3, key));
        assertThat(first.getItemCount()).isEqualTo(1);

        assertThatThrownBy(() -> publicStorefrontService.createGuestOrder(SHOP_SLUG, guestRequest(productId, 9, key)))
                .as("a changed basket under the same key must be refused, not silently matched to the first basket")
                .isInstanceOf(IdempotencyPayloadMismatchException.class);

        assertThat(countOrdersByKey(key))
                .as("the refused submit created nothing")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // (c) concurrent same key -> ONE order; every loser is a typed outcome
    //     (a replay or IdempotencyConflictException), never the raw
    //     DataIntegrityViolationException that INT-15 measured live.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("concurrent same key: exactly one order; losers are replays or typed 409s, never the raw integrity violation (INT-15)")
    void concurrentSameKey_yieldsOneOrder_andNeverTheRawIntegrityError() throws Exception {
        UUID productId = seedProduct("SKU-QA0902-RACE");
        String key = "qa0902-race-" + UUID.randomUUID();
        int racers = 4;

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            Callable<Object> worker = () -> {
                // Guest calls start with NO upstream tenant; createGuestOrder sets + clears its own.
                TenantContext.clear();
                try {
                    gate.await();
                    return publicStorefrontService.createGuestOrder(SHOP_SLUG, guestRequest(productId, 1, key));
                } catch (Throwable t) {
                    return t;
                } finally {
                    TenantContext.clear();
                }
            };
            List<Future<Object>> futures = new ArrayList<>(racers);
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(worker));
            }
            gate.countDown();

            List<String> orderNumbers = new ArrayList<>();
            int typedConflicts = 0;
            List<Throwable> rawIntegrityErrors = new ArrayList<>();
            List<Throwable> otherFailures = new ArrayList<>();
            for (Future<Object> f : futures) {
                Object result = f.get(60, SECONDS);
                if (result instanceof GuestOrderConfirmation confirmation) {
                    orderNumbers.add(confirmation.getOrderNumber());
                } else if (result instanceof IdempotencyConflictException) {
                    typedConflicts++;
                } else if (result instanceof DataIntegrityViolationException dive) {
                    rawIntegrityErrors.add(dive);
                } else {
                    otherFailures.add((Throwable) result);
                }
            }
            log.info("CLUSTER-E (c): racers={} confirmations={} typedConflicts={} rawIntegrityErrors={} other={}",
                    racers, orderNumbers.size(), typedConflicts, rawIntegrityErrors.size(), otherFailures.size());

            assertThat(rawIntegrityErrors)
                    .as("INT-15: a same-key race must never surface the raw unique-index violation "
                            + "(errors/duplicate 'Data integrity constraint violated'); got %s",
                            rawIntegrityErrors.stream().map(t -> t.getClass().getSimpleName() + ": " + t.getMessage()).toList())
                    .isEmpty();
            assertThat(otherFailures)
                    .as("no racer may fail with anything but the typed conflict")
                    .isEmpty();
            assertThat(orderNumbers).as("at least one racer received the order").isNotEmpty();
            assertThat(orderNumbers).as("every confirmation names the SAME order").containsOnly(orderNumbers.get(0));
            assertThat(countOrdersByKey(key)).as("exactly one order row from the race").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // CONTROL — a keyless submit is the pre-existing non-idempotent create,
    //           untouched: a normal order, no reservation row.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("CONTROL: a submit with no key at all still creates a normal order and reserves nothing")
    void noKey_createsANormalOrder_withoutAReservation() {
        UUID productId = seedProduct("SKU-QA0902-NOKEY");
        long reservationsBefore = countReservations();

        GuestOrderConfirmation confirmation = publicStorefrontService.createGuestOrder(SHOP_SLUG, guestRequest(productId, 1, null));

        assertThat(confirmation.getStatus()).isEqualTo("PENDING");
        assertThat(readOrderKey(confirmation.getOrderNumber())).as("no key was sent, none is stored").isNull();
        assertThat(countReservations()).as("no reservation is made for a keyless submit").isEqualTo(reservationsBefore);
    }

    // ------------------------------------------------------------------
    // (d) the Idempotency-Key HEADER alone is honoured (the platform contract
    //     every other mutating endpoint speaks — API-3's exact repro).
    // ------------------------------------------------------------------
    @Test
    @DisplayName("header-only key (no body field): honoured — a repeated header replays the first order and stores the key on the order row (API-3)")
    void headerKeyAlone_isHonoured() {
        UUID productId = seedProduct("SKU-QA0902-HEADER");
        String headerKey = "qa0902-header-" + UUID.randomUUID();

        GuestOrderConfirmation first = publicStorefrontService.createGuestOrder(
                SHOP_SLUG, guestRequest(productId, 2, null), headerKey);
        GuestOrderConfirmation second = publicStorefrontService.createGuestOrder(
                SHOP_SLUG, guestRequest(productId, 2, null), headerKey);

        assertThat(second.getOrderNumber()).as("the header-keyed repeat is a replay").isEqualTo(first.getOrderNumber());
        assertThat(countOrdersByKey(headerKey)).as("one order row under the header key").isEqualTo(1);
        assertThat(readOrderKey(first.getOrderNumber()))
                .as("the header key is what the order row records")
                .isEqualTo(headerKey);
        assertThat(reservationRow(headerKey).get("response_status")).isEqualTo(201);
    }

    // ------------------------------------------------------------------
    // (e) body and header both present. PR #726 review follow-up: the census arm
    //     here used to assert that the BODY WON when the two disagreed. That was a
    //     preference standing in for a contract — a retry carrying only the header
    //     key would find no reservation and mint the duplicate the key exists to
    //     stop. Disagreement is now refused before any write; agreement is one intent.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("body and header keys that DISAGREE are refused 400 and write nothing: no order row and no reservation under EITHER key")
    void disagreeingBodyAndHeaderKeys_areRefused_andWriteNothing() {
        UUID productId = seedProduct("SKU-QA0902-DISAGREE");
        String bodyKey = "qa0902-body-" + UUID.randomUUID();
        String headerKey = "qa0902-hdr-" + UUID.randomUUID();
        long reservationsBefore = countReservations();

        assertThatThrownBy(() -> publicStorefrontService.createGuestOrder(
                SHOP_SLUG, guestRequest(productId, 1, bodyKey), headerKey))
                .as("two different keys are two different intents on one request — a client defect, not a preference")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");

        assertThat(countOrdersByKey(bodyKey)).as("no order under the body key").isZero();
        assertThat(countOrdersByKey(headerKey)).as("no order under the header key").isZero();
        assertThat(countReservationsForKey(bodyKey)).as("no reservation under the body key").isZero();
        assertThat(countReservationsForKey(headerKey)).as("no reservation under the header key").isZero();
        assertThat(countReservations()).as("the refusal happened before the store was touched").isEqualTo(reservationsBefore);
    }

    @Test
    @DisplayName("CONTROL: body and header carrying the SAME key are one intent — keyed once, replayed on repeat")
    void agreeingBodyAndHeaderKeys_areOneIntent() {
        // Without this arm the refusal above could be firing for EVERY dual-source request and
        // the negative case would still read green.
        UUID productId = seedProduct("SKU-QA0902-AGREE");
        String key = "qa0902-agree-" + UUID.randomUUID();

        GuestOrderConfirmation first = publicStorefrontService.createGuestOrder(
                SHOP_SLUG, guestRequest(productId, 1, key), key);
        GuestOrderConfirmation second = publicStorefrontService.createGuestOrder(
                SHOP_SLUG, guestRequest(productId, 1, key), key);

        assertThat(readOrderKey(first.getOrderNumber())).isEqualTo(key);
        assertThat(second.getOrderNumber()).as("the repeat is a replay").isEqualTo(first.getOrderNumber());
        assertThat(countOrdersByKey(key)).isEqualTo(1);
        assertThat(reservationRow(key).get("response_status")).isEqualTo(201);
    }

    // ---- Request builder ----

    private GuestOrderRequest guestRequest(UUID productId, int qty, String idempotencyKey) {
        GuestOrderItemRequest item = new GuestOrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(qty);

        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Cluster E Buyer");
        request.setCustomerEmail("cluster-e@example.com");
        request.setCustomerPhone("+447700900902");
        request.setNotes("qa0902 cluster E");
        request.setIdempotencyKey(idempotencyKey);
        request.setItems(List.of(item));
        // COLLECTION: no address, delivery fee forced to £0 — fulfilment is not what is under test.
        request.setFulfilmentType("COLLECTION");
        return request;
    }

    // ---- Read helpers (superuser bootstrap role; TenantContext set for correctness) ----

    private long countOrdersByKey(String key) {
        TenantContext.set(TENANT_ID);
        try {
            Long n = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM orders WHERE tenant_id = ? AND idempotency_key = ?", Long.class, TENANT_ID, key);
            return Objects.requireNonNull(n);
        } finally {
            TenantContext.clear();
        }
    }

    private String readOrderKey(String orderNumber) {
        TenantContext.set(TENANT_ID);
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT idempotency_key FROM orders WHERE order_number = ?", String.class, orderNumber);
        } finally {
            TenantContext.clear();
        }
    }

    private Map<String, Object> reservationRow(String key) {
        TenantContext.set(TENANT_ID);
        try {
            return jdbcTemplate.queryForMap(
                    "SELECT request_hash, response_status, response_body FROM idempotency_keys "
                            + "WHERE tenant_id = ? AND endpoint = ? AND idempotency_key = ?",
                    TENANT_ID, ENDPOINT, key);
        } finally {
            TenantContext.clear();
        }
    }

    private long countReservations() {
        TenantContext.set(TENANT_ID);
        try {
            Long n = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM idempotency_keys WHERE tenant_id = ? AND endpoint = ?", Long.class, TENANT_ID, ENDPOINT);
            return Objects.requireNonNull(n);
        } finally {
            TenantContext.clear();
        }
    }

    private long countReservationsForKey(String key) {
        TenantContext.set(TENANT_ID);
        try {
            Long n = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM idempotency_keys WHERE tenant_id = ? AND idempotency_key = ?", Long.class, TENANT_ID, key);
            return Objects.requireNonNull(n);
        } finally {
            TenantContext.clear();
        }
    }

    // ---- Idempotent seed helpers (Phase 13/14 pattern) ----

    private void seedTenantIdempotent(UUID id, String name) {
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                id, name);
    }

    private UUID seedShopIdempotent(UUID tenantId, String slug) {
        TenantContext.set(tenantId);
        try {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, tenantId, slug);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            // No opening_hours -> shop is always open (validateShopIsOpen returns early).
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                    id, tenantId, "QA 0902 Cluster E Shop", slug);
            return id;
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedProduct(String sku) {
        TenantContext.set(TENANT_ID);
        try {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM products WHERE tenant_id = ? AND sku = ?", UUID.class, TENANT_ID, sku);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, 100, 'STANDARD', 0)",
                    productId, TENANT_ID, sku, "Moin Moin", "beans, peppers", shopId);
            return productId;
        } finally {
            TenantContext.clear();
        }
    }
}
