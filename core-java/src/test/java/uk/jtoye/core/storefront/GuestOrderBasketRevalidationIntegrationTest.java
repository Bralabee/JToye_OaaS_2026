package uk.jtoye.core.storefront;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 37 plan 33 (RWO-007 / UXT-007 P0, decision D-16): the server RE-VALIDATES the basket at submit
 * against what the customer was shown, and refuses with a typed diff the customer can re-confirm.
 *
 * <p>Before this plan the checkout never re-validated the stored basket: the persona run saw a vendor
 * reprice Suya from £9.00 to £14.00 between render and submit and the order was placed at £14.00 — a
 * total the customer never saw. 37-32 put the prices the customer saw on the contract
 * ({@code expectedUnitPricePennies} per line, {@code expectedTotalPennies} for the order); this plan
 * makes them required (422 {@code basket-confirmation-required}, CONTEXT discretion Q4) and compares
 * them, in the SAME product loop and transaction that snapshots allergens, so a change is refused with
 * ONE 409 {@code basket-changed} listing every change (D-16), composed with — never beside — 31.1's
 * {@code allergen-acknowledgement-stale} refusal.
 *
 * <p>Every arm goes through the real HTTP endpoint ({@code POST /api/v1/public/shops/{slug}/orders})
 * with a JSON map body, the vendor's edits through the real {@code PUT /api/v1/products/{id}}, and every
 * persisted fact is read back by SQL. Real Postgres (Testcontainers): the reservation semantics live in
 * {@code INSERT ... ON CONFLICT}. The class is NOT {@code @Transactional}; each request commits. The test
 * profile has no Stripe key, so every storefront order takes the COD branch — and the 37-27/37-28 cash
 * caps count OPEN cash orders per email, phone and connection at a shop, so every arm mints its own
 * contact identity (never a raised default).
 *
 * <p>RLS note: the Testcontainers bootstrap role is a SUPERUSER, so these arms prove the application
 * contract, not RLS enforcement. This plan adds no column and no policy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class GuestOrderBasketRevalidationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;

    /** Dedicated tenant so a parallel fork's fixtures cannot collide on slug or SKU. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000373301");
    private static final String SHOP_SLUG = "shop-37-33-basket-revalidation";
    private static final String ENDPOINT = "storefront.orders.create";

    private static final String BASKET_CHANGED = "https://jtoye.uk/errors/basket-changed";
    private static final String CONFIRMATION_REQUIRED = "https://jtoye.uk/errors/basket-confirmation-required";

    /** AllergenCatalog bits, spelled out so a reader can check the expected sets by eye. */
    private static final int GLUTEN = 1;       // bit 0
    private static final int PEANUTS = 1 << 4; // bit 4
    private static final int MILK = 1 << 6;    // bit 6

    private UUID shopId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "37-33 Basket Revalidation Tenant");
        shopId = seedShopIdempotent(SHOP_SLUG, "37-33 Basket Revalidation Shop", 0);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // UXT-007, the persona race: the customer sees Suya at £9.00, the vendor reprices it to £14.00,
    // the customer submits what they saw. Pre-fix: 201 at £14.00. Post-fix: 409 basket-changed naming
    // the line (name, both prices) and the total; nothing recorded; the same key accepts the corrected
    // body at the server's price (goods P2-KYL-P2, P2-CHA-12/13, P2-RAV-20).
    // ------------------------------------------------------------------
    @Test
    @DisplayName("UXT-007: a vendor reprice between render and submit is refused 409 basket-changed with PRICE_CHANGED and TOTAL_CHANGED; the same key then accepts the corrected prices and charges the server's")
    void vendorRepriceBetweenRenderAndSubmit_isRefusedBasketChanged_thenAcceptedUnderTheSameKey() throws Exception {
        UUID suya = seedProduct("SKU-3733-REPRICE", "Suya", 900L, PEANUTS, 100);
        long shown = renderedPrice(suya);
        assertThat(shown).as("the customer saw Suya at £9.00").isEqualTo(900L);

        vendorEdits(suya, "Suya", 1400L, PEANUTS, true, 100);

        String key = "reprice-" + UUID.randomUUID();
        String email = "reprice-" + UUID.randomUUID() + "@example.com";
        List<Map<String, Object>> basket = List.of(line(suya, 1, shown));
        MvcResult refused = perform(post(ordersUrl()).header("Idempotency-Key", key),
                body(email, basket, shown, PEANUTS));
        JsonNode problem = assertProblem(refused, 409, BASKET_CHANGED, "BASKET_CHANGED");
        String text = responseText(refused);
        assertThat(problem.path("title").asString()).as(text).isEqualTo("Your basket has changed");

        JsonNode changes = problem.path("changes");
        assertThat(changes.isArray()).as("changes[] is the typed diff: " + text).isTrue();
        assertThat(changes.size()).as(text).isEqualTo(2);
        JsonNode price = changes.get(0);
        assertThat(price.path("kind").asString()).as(text).isEqualTo("PRICE_CHANGED");
        assertThat(price.path("productId").asString()).isEqualTo(suya.toString());
        assertThat(price.path("name").asString()).as("the line is named so the UI can say 'Suya £9.00 → £14.00'").isEqualTo("Suya");
        assertThat(price.path("expected").asLong()).isEqualTo(900L);
        assertThat(price.path("current").asLong()).isEqualTo(1400L);
        JsonNode total = changes.get(1);
        assertThat(total.path("kind").asString()).as(text).isEqualTo("TOTAL_CHANGED");
        assertThat(total.path("expected").asLong()).isEqualTo(900L);
        assertThat(total.path("current").asLong()).isEqualTo(1400L);
        assertThat(total.has("productId")).as("a basket-level entry names no product").isFalse();
        // Only the prices changed, so the 31.1 allergen properties are NOT on this problem.
        assertThat(problem.has("currentAllergenMask")).as("no allergen change, no allergen properties: " + text).isFalse();
        assertThat(problem.has("lines")).as(text).isFalse();

        assertThat(countOrdersByEmail(email)).as("nothing was recorded at a price the customer never saw").isZero();
        assertThat(countReservationsForKey(key))
                .as("T-37-82: the refusal is thrown inside the reserved work, so the reservation rolled back")
                .isZero();

        // The customer accepts the change; the client resubmits under the SAME key with the current prices.
        long reShown = renderedPrice(suya);
        assertThat(reShown).isEqualTo(1400L);
        MvcResult accepted = perform(post(ordersUrl()).header("Idempotency-Key", key),
                body(email, List.of(line(suya, 1, reShown)), reShown, PEANUTS));
        assertThat(accepted.getResponse().getStatus()).as(responseText(accepted)).isEqualTo(201);
        assertThat(jsonMapper.readTree(accepted.getResponse().getContentAsString()).path("totalAmountPennies").asLong())
                .isEqualTo(1400L);
        Map<String, Object> row = orderRowByEmail(email);
        assertThat(((Number) row.get("total_amount_pennies")).longValue()).as("charged the server's price").isEqualTo(1400L);
        assertThat(unitPriceOfOnlyLine((UUID) row.get("id"))).isEqualTo(1400L);
        assertThat(countOrdersByEmail(email)).as("one intent, one order").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // T-37-80: the expected prices are compared, never charged. A client claiming a lower price is
    // refused with the server's price in the diff; it is never 201 at the claimed price.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("T-37-80: a client claiming a lower unit price than the server's is refused 409 basket-changed naming the server's price, never charged the claimed one")
    void clientClaimingALowerPrice_isRefused_neverCharged() throws Exception {
        UUID zobo = seedProduct("SKU-3733-TAMPER", "Zobo", 900L, 0, 100);
        String email = "tamper-" + UUID.randomUUID() + "@example.com";

        MvcResult refused = perform(post(ordersUrl()), body(email, List.of(line(zobo, 2, 500L)), 1000L, 0));
        JsonNode problem = assertProblem(refused, 409, BASKET_CHANGED, "BASKET_CHANGED");
        List<JsonNode> priceChanges = changesOfKind(problem, "PRICE_CHANGED");
        assertThat(priceChanges).as(responseText(refused)).hasSize(1);
        assertThat(priceChanges.get(0).path("expected").asLong()).isEqualTo(500L);
        assertThat(priceChanges.get(0).path("current").asLong()).as("the server's price, which is what would be charged").isEqualTo(900L);
        List<JsonNode> totalChanges = changesOfKind(problem, "TOTAL_CHANGED");
        assertThat(totalChanges).hasSize(1);
        assertThat(totalChanges.get(0).path("expected").asLong()).isEqualTo(1000L);
        assertThat(totalChanges.get(0).path("current").asLong()).isEqualTo(1800L);
        assertThat(countOrdersByEmail(email)).as("never an order at the claimed price").isZero();
    }

    // ------------------------------------------------------------------
    // CONTEXT discretion Q4: the expected prices are REQUIRED, like 31.1's acknowledgement. A body
    // without them is refused 422 with its own type before any product is read, keyed or keyless, and
    // leaves no order and no reservation; the same key then accepts a body that carries them.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Q4: a storefront order without expectedUnitPricePennies / expectedTotalPennies is refused 422 basket-confirmation-required and writes nothing; the same key then accepts a body that carries them")
    void missingExpectedPrices_isRefused422_andWritesNothing_thenAcceptedUnderTheSameKey() throws Exception {
        UUID rice = seedProduct("SKU-3733-MISSING", "Plain Rice", 700L, 0, 100);
        String key = "missing-" + UUID.randomUUID();
        String email = "missing-" + UUID.randomUUID() + "@example.com";
        long ordersBefore = countOrders();

        // Neither field.
        MvcResult keyed = perform(post(ordersUrl()).header("Idempotency-Key", key),
                bodyWithoutExpectations(email, List.of(lineWithoutPrice(rice, 1)), 0));
        assertProblem(keyed, 422, CONFIRMATION_REQUIRED, "BASKET_CONFIRMATION_REQUIRED");
        // The keyless path (no reservation at all) is refused the same way.
        MvcResult keyless = perform(post(ordersUrl()), bodyWithoutExpectations(email, List.of(lineWithoutPrice(rice, 1)), 0));
        assertProblem(keyless, 422, CONFIRMATION_REQUIRED, "BASKET_CONFIRMATION_REQUIRED");
        // The total alone is not a confirmation: a line without its unit price is refused too.
        MvcResult lineMissing = perform(post(ordersUrl()), body(email, List.of(lineWithoutPrice(rice, 1)), 700L, 0));
        assertProblem(lineMissing, 422, CONFIRMATION_REQUIRED, "BASKET_CONFIRMATION_REQUIRED");
        // And a line price without the total.
        Map<String, Object> totalMissing = bodyWithoutExpectations(email, List.of(line(rice, 1, 700L)), 0);
        MvcResult noTotal = perform(post(ordersUrl()), totalMissing);
        assertProblem(noTotal, 422, CONFIRMATION_REQUIRED, "BASKET_CONFIRMATION_REQUIRED");

        assertThat(countOrders()).as("no order row was written").isEqualTo(ordersBefore);
        assertThat(countOrdersByEmail(email)).as("nothing under this customer").isZero();
        assertThat(countReservationsForKey(key))
                .as("the refusal is thrown inside the reserved work, so the reservation rolled back")
                .isZero();

        MvcResult accepted = perform(post(ordersUrl()).header("Idempotency-Key", key),
                body(email, List.of(line(rice, 1, 700L)), 700L, 0));
        assertThat(accepted.getResponse().getStatus()).as(responseText(accepted)).isEqualTo(201);
        assertThat(countOrdersByEmail(email)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // No regression: a basket whose expectation equals the server's figures is placed exactly as before.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Unchanged basket: expected prices equal to the server's are accepted 201 and the order carries them")
    void unchangedBasket_isAccepted201() throws Exception {
        UUID suya = seedProduct("SKU-3733-UNCHANGED-A", "Suya", 900L, PEANUTS, 100);
        UUID rice = seedProduct("SKU-3733-UNCHANGED-B", "Plain Rice", 700L, 0, 100);
        String email = "unchanged-" + UUID.randomUUID() + "@example.com";

        MvcResult created = perform(post(ordersUrl()).header("Idempotency-Key", "unchanged-" + UUID.randomUUID()),
                body(email, List.of(line(suya, 2, 900L), line(rice, 1, 700L)), 2500L, PEANUTS));
        assertThat(created.getResponse().getStatus()).as(responseText(created)).isEqualTo(201);
        Map<String, Object> row = orderRowByEmail(email);
        assertThat(((Number) row.get("total_amount_pennies")).longValue()).isEqualTo(2500L);
        assertThat(row.get("allergen_ack_mask")).isEqualTo(PEANUTS);
    }

    // ------------------------------------------------------------------
    // Task 2: every other kind of change, each collected in the same loop and reported together.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("UNAVAILABLE: a product made unavailable after render is refused 409 basket-changed naming the line, with the total of what remains; the same key then accepts the basket without it")
    void productMadeUnavailableAfterRender_isRefusedUnavailable_thenAcceptedWithoutTheLine() throws Exception {
        UUID suya = seedProduct("SKU-3733-UNAVAIL-A", "Suya", 900L, PEANUTS, 100);
        UUID rice = seedProduct("SKU-3733-UNAVAIL-B", "Plain Rice", 700L, 0, 100);
        vendorEdits(suya, "Suya", 900L, PEANUTS, false, 100);

        String key = "unavailable-" + UUID.randomUUID();
        String email = "unavailable-" + UUID.randomUUID() + "@example.com";
        MvcResult refused = perform(post(ordersUrl()).header("Idempotency-Key", key),
                body(email, List.of(line(suya, 1, 900L), line(rice, 1, 700L)), 1600L, PEANUTS));
        JsonNode problem = assertProblem(refused, 409, BASKET_CHANGED, "BASKET_CHANGED");
        String text = responseText(refused);
        assertThat(kinds(problem)).as(text).containsExactly("UNAVAILABLE", "TOTAL_CHANGED");
        JsonNode unavailable = problem.path("changes").get(0);
        assertThat(unavailable.path("productId").asString()).isEqualTo(suya.toString());
        assertThat(unavailable.path("name").asString()).isEqualTo("Suya");
        assertThat(unavailable.has("expected")).as("an availability entry carries no prices").isFalse();
        JsonNode total = problem.path("changes").get(1);
        assertThat(total.path("expected").asLong()).isEqualTo(1600L);
        assertThat(total.path("current").asLong()).as("the total of the lines that can still be ordered").isEqualTo(700L);
        assertThat(countOrdersByEmail(email)).isZero();
        assertThat(countReservationsForKey(key)).isZero();

        MvcResult accepted = perform(post(ordersUrl()).header("Idempotency-Key", key),
                body(email, List.of(line(rice, 1, 700L)), 700L, 0));
        assertThat(accepted.getResponse().getStatus()).as(responseText(accepted)).isEqualTo(201);
        assertThat(countOrdersByEmail(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("SOLD_OUT: fewer units in stock than requested is refused 409 basket-changed with requested and available; the same key then accepts the available quantity")
    void soldOutAfterRender_isRefusedSoldOut_withRequestedAndAvailable_thenAcceptedAtTheAvailableQuantity() throws Exception {
        UUID suya = seedProduct("SKU-3733-SOLDOUT", "Suya", 900L, PEANUTS, 10);
        vendorEdits(suya, "Suya", 900L, PEANUTS, true, 2);

        String key = "soldout-" + UUID.randomUUID();
        String email = "soldout-" + UUID.randomUUID() + "@example.com";
        MvcResult refused = perform(post(ordersUrl()).header("Idempotency-Key", key),
                body(email, List.of(line(suya, 3, 900L)), 2700L, PEANUTS));
        JsonNode problem = assertProblem(refused, 409, BASKET_CHANGED, "BASKET_CHANGED");
        String text = responseText(refused);
        assertThat(kinds(problem)).as(text).containsExactly("SOLD_OUT", "TOTAL_CHANGED");
        JsonNode soldOut = problem.path("changes").get(0);
        assertThat(soldOut.path("productId").asString()).isEqualTo(suya.toString());
        assertThat(soldOut.path("name").asString()).isEqualTo("Suya");
        assertThat(soldOut.path("requested").asInt()).isEqualTo(3);
        assertThat(soldOut.path("available").asInt()).isEqualTo(2);
        JsonNode total = problem.path("changes").get(1);
        assertThat(total.path("expected").asLong()).isEqualTo(2700L);
        assertThat(total.path("current").asLong()).as("the line counted at the quantity that can be had").isEqualTo(1800L);
        assertThat(countOrdersByEmail(email)).isZero();

        MvcResult accepted = perform(post(ordersUrl()).header("Idempotency-Key", key),
                body(email, List.of(line(suya, 2, 900L)), 1800L, PEANUTS));
        assertThat(accepted.getResponse().getStatus()).as(responseText(accepted)).isEqualTo(201);
        assertThat(((Number) orderRowByEmail(email).get("total_amount_pennies")).longValue()).isEqualTo(1800L);
    }

    @Test
    @DisplayName("REMOVED (T-37-81): another shop's product and a non-existent id get the same 409 entry, the id only, never the other shop's name; the same key then accepts the basket without it")
    void anotherShopsProduct_andANonExistentId_areBothRemoved_withTheIdOnly() throws Exception {
        UUID otherShop = seedShopIdempotent("shop-37-33-other", "37-33 Other Shop", 0);
        UUID secret = seedProductInShop(otherShop, "SKU-3733-OTHER", "Secret Jollof 4471", 1200L, 0, 100);
        UUID rice = seedProduct("SKU-3733-REMOVED-OK", "Plain Rice", 700L, 0, 100);
        UUID ghost = UUID.randomUUID();

        String key = "removed-" + UUID.randomUUID();
        String email = "removed-" + UUID.randomUUID() + "@example.com";
        MvcResult foreign = perform(post(ordersUrl()).header("Idempotency-Key", key),
                body(email, List.of(line(secret, 1, 1200L), line(rice, 1, 700L)), 1900L, 0));
        JsonNode foreignProblem = assertProblem(foreign, 409, BASKET_CHANGED, "BASKET_CHANGED");
        String foreignText = responseText(foreign);
        assertThat(foreignText).as("another shop's product is never named").doesNotContain("Secret Jollof", "4471");
        assertThat(foreignText).as("no 31.1-era 404 wording either").doesNotContain("Product not found");
        assertThat(kinds(foreignProblem)).as(foreignText).containsExactly("REMOVED", "TOTAL_CHANGED");
        JsonNode removed = foreignProblem.path("changes").get(0);
        assertThat(removed.path("productId").asString()).isEqualTo(secret.toString());
        assertThat(removed.has("name")).as("id only").isFalse();
        assertThat(removed.has("expected")).isFalse();
        assertThat(foreignProblem.path("changes").get(1).path("current").asLong()).isEqualTo(700L);

        MvcResult missing = perform(post(ordersUrl()),
                body(email, List.of(line(ghost, 1, 1200L), line(rice, 1, 700L)), 1900L, 0));
        JsonNode missingProblem = assertProblem(missing, 409, BASKET_CHANGED, "BASKET_CHANGED");
        // Byte-for-byte the same shape once the id is substituted: nothing distinguishes "exists
        // elsewhere" from "does not exist".
        String foreignShape = foreignProblem.toString().replace(secret.toString(), "<id>");
        String missingShape = missingProblem.toString().replace(ghost.toString(), "<id>");
        assertThat(foreignShape).isEqualTo(missingShape);
        assertThat(countOrdersByEmail(email)).isZero();
        assertThat(countReservationsForKey(key)).isZero();

        MvcResult accepted = perform(post(ordersUrl()).header("Idempotency-Key", key),
                body(email, List.of(line(rice, 1, 700L)), 700L, 0));
        assertThat(accepted.getResponse().getStatus()).as(responseText(accepted)).isEqualTo(201);
        assertThat(countOrdersByEmail(email)).isEqualTo(1);
    }

    @Test
    @DisplayName("BELOW_MINIMUM: a vendor reprice that drops the basket under the shop's minimum is refused 409 basket-changed with the minimum, the subtotal and the human wording, beside the price and total changes")
    void vendorRepriceBelowTheMinimum_isRefusedBelowMinimum_withTheHumanWording() throws Exception {
        UUID minShop = seedShopIdempotent("shop-37-33-minimum", "37-33 Minimum Shop", 2000);
        UUID platter = seedProductInShop(minShop, "SKU-3733-MIN-RACE", "Party Platter", 2500L, 0, 100);
        vendorEditsInShop(minShop, platter, "Party Platter", 900L, 0, true, 100);

        String email = "minimum-" + UUID.randomUUID() + "@example.com";
        MvcResult refused = perform(post(ordersUrl("shop-37-33-minimum")),
                body(email, List.of(line(platter, 1, 2500L)), 2500L, 0));
        JsonNode problem = assertProblem(refused, 409, BASKET_CHANGED, "BASKET_CHANGED");
        String text = responseText(refused);
        assertThat(kinds(problem)).as(text).containsExactly("PRICE_CHANGED", "BELOW_MINIMUM", "TOTAL_CHANGED");
        JsonNode below = problem.path("changes").get(1);
        assertThat(below.path("minimum").asLong()).isEqualTo(2000L);
        assertThat(below.path("subtotal").asLong()).isEqualTo(900L);
        assertThat(below.path("detail").asString())
                .as("the WR-01 wording is kept inside the entry")
                .isEqualTo("Order is below this shop's minimum order value of £20.00.");
        assertThat(below.has("productId")).isFalse();
        assertThat(countOrdersByEmail(email)).isZero();

        // Direct API, no race: a basket submitted under the minimum at the prices shown is refused with
        // the BELOW_MINIMUM entry alone (the storefront disables submit; the server is the gate).
        UUID snack = seedProductInShop(minShop, "SKU-3733-MIN-DIRECT", "Snack", 500L, 0, 100);
        String direct = "minimum-direct-" + UUID.randomUUID() + "@example.com";
        MvcResult refusedDirect = perform(post(ordersUrl("shop-37-33-minimum")),
                body(direct, List.of(line(snack, 1, 500L)), 500L, 0));
        JsonNode directProblem = assertProblem(refusedDirect, 409, BASKET_CHANGED, "BASKET_CHANGED");
        assertThat(kinds(directProblem)).as(responseText(refusedDirect)).containsExactly("BELOW_MINIMUM");
        assertThat(countOrdersByEmail(direct)).isZero();
    }

    // ------------------------------------------------------------------
    // Composition with 31.1 D-05. Price AND allergens changed: ONE basket-changed problem carrying the
    // 31.1 allergen properties, so the client re-renders both panels and re-confirms once. Allergens
    // ONLY: the 31.1 problem, unchanged in type, code and properties.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Mixed: a reprice AND an allergen change are refused with ONE 409 basket-changed carrying changes[] and the 31.1 allergen properties; re-confirming both under the same key succeeds")
    void priceAndAllergenChanged_isRefusedBasketChanged_carryingTheAllergenProperties_thenReconfirmedUnderTheSameKey() throws Exception {
        UUID satay = seedProduct("SKU-3733-MIXED", "Satay", 900L, PEANUTS, 100);
        vendorEdits(satay, "Satay", 1400L, PEANUTS | MILK, true, 100);

        String key = "mixed-" + UUID.randomUUID();
        String email = "mixed-" + UUID.randomUUID() + "@example.com";
        MvcResult refused = perform(post(ordersUrl()).header("Idempotency-Key", key),
                body(email, List.of(line(satay, 1, 900L)), 900L, PEANUTS));
        JsonNode problem = assertProblem(refused, 409, BASKET_CHANGED, "BASKET_CHANGED");
        String text = responseText(refused);
        assertThat(kinds(problem)).as(text).containsExactly("PRICE_CHANGED", "TOTAL_CHANGED");
        assertThat(problem.path("currentAllergenMask").asInt()).as(text).isEqualTo(PEANUTS | MILK);
        assertThat(problem.path("acknowledgedAllergenMask").asInt()).isEqualTo(PEANUTS);
        assertThat(texts(problem.path("currentAllergens"))).containsExactly("Peanuts", "Milk");
        JsonNode lines = problem.path("lines");
        assertThat(lines.size()).as(text).isEqualTo(1);
        assertThat(lines.get(0).path("productId").asString()).isEqualTo(satay.toString());
        assertThat(lines.get(0).path("productName").asString()).isEqualTo("Satay");
        assertThat(lines.get(0).path("allergenMask").asInt()).isEqualTo(PEANUTS | MILK);
        assertThat(texts(lines.get(0).path("allergens"))).containsExactly("Peanuts", "Milk");
        assertThat(countOrdersByEmail(email)).isZero();
        assertThat(countReservationsForKey(key)).isZero();

        MvcResult accepted = perform(post(ordersUrl()).header("Idempotency-Key", key),
                body(email, List.of(line(satay, 1, 1400L)), 1400L, PEANUTS | MILK));
        assertThat(accepted.getResponse().getStatus()).as(responseText(accepted)).isEqualTo(201);
        Map<String, Object> row = orderRowByEmail(email);
        assertThat(((Number) row.get("total_amount_pennies")).longValue()).isEqualTo(1400L);
        assertThat(row.get("allergen_ack_mask")).isEqualTo(PEANUTS | MILK);
    }

    @Test
    @DisplayName("Allergens only: an allergen change with the prices unchanged is refused exactly as 31.1 built it (409 allergen-acknowledgement-stale, its code and properties, no changes[])")
    void allergenOnlyChange_isRefusedExactlyAs31_1() throws Exception {
        UUID satay = seedProduct("SKU-3733-ALLERGEN-ONLY", "Satay", 1000L, PEANUTS, 100);
        vendorEdits(satay, "Satay", 1000L, PEANUTS | MILK, true, 100);

        String email = "allergen-only-" + UUID.randomUUID() + "@example.com";
        MvcResult refused = perform(post(ordersUrl()), body(email, List.of(line(satay, 1, 1000L)), 1000L, PEANUTS));
        JsonNode problem = assertProblem(refused, 409, "https://jtoye.uk/errors/allergen-acknowledgement-stale",
                "ALLERGEN_ACKNOWLEDGEMENT_STALE");
        String text = responseText(refused);
        assertThat(problem.path("title").asString()).as(text).isEqualTo("Allergen information changed");
        assertThat(problem.path("currentAllergenMask").asInt()).isEqualTo(PEANUTS | MILK);
        assertThat(problem.path("acknowledgedAllergenMask").asInt()).isEqualTo(PEANUTS);
        assertThat(texts(problem.path("currentAllergens"))).containsExactly("Peanuts", "Milk");
        assertThat(problem.path("lines").size()).isEqualTo(1);
        assertThat(problem.has("changes")).as("the 31.1 contract carries no basket diff: " + text).isFalse();
        assertThat(countOrdersByEmail(email)).isZero();
    }

    // ---- Vendor edits through the real product endpoint ----

    /** The vendor's real edit path: PUT /api/v1/products/{id} with a catalog-write token. */
    private void vendorEdits(UUID productId, String title, long pricePennies, int allergenMask,
                             boolean available, Integer quantityInStock) throws Exception {
        vendorEditsInShop(shopId, productId, title, pricePennies, allergenMask, available, quantityInStock);
    }

    private void vendorEditsInShop(UUID shop, UUID productId, String title, long pricePennies, int allergenMask,
                                   boolean available, Integer quantityInStock) throws Exception {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("sku", "SKU-3733-EDITED-" + productId);
        update.put("title", title);
        update.put("ingredientsText", "see label");
        update.put("allergenMask", allergenMask);
        update.put("pricePennies", pricePennies);
        update.put("vatRate", "STANDARD");
        update.put("available", available);
        update.put("shopId", shop);
        update.put("quantityInStock", quantityInStock);
        MvcResult edited = mockMvc.perform(put("/api/v1/products/" + productId)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                                        .claim("tenant_id", TENANT_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                                        new SimpleGrantedAuthority("SCOPE_catalog:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(update)))
                .andReturn();
        assertThat(edited.getResponse().getStatus()).as(responseText(edited)).isEqualTo(200);
        Map<String, Object> stored = inTenant(() -> jdbcTemplate.queryForMap(
                "SELECT price_pennies, allergen_mask, available, quantity_in_stock FROM products WHERE id = ?", productId));
        assertThat(((Number) stored.get("price_pennies")).longValue()).as("the vendor edit landed").isEqualTo(pricePennies);
        assertThat(stored.get("allergen_mask")).isEqualTo(allergenMask);
        assertThat(stored.get("available")).isEqualTo(available);
    }

    // ---- HTTP helpers ----

    private String ordersUrl() {
        return ordersUrl(SHOP_SLUG);
    }

    private static String ordersUrl(String slug) {
        return "/api/v1/public/shops/" + slug + "/orders";
    }

    /**
     * 37-28 (D-27): every storefront request is sent from the arm's OWN socket peer, derived from the
     * arm's address like the phone is. MockMvc's default peer is one address for the whole class, and
     * the per-connection cash cap (5 open orders at a shop) would refuse this class's sixth order with
     * a 429 that has nothing to do with the basket — a unique identity per arm, never a raised default.
     */
    private MvcResult perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        String peer = peerFor(body);
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                        .with(r -> { r.setRemoteAddr(peer); return r; })
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    private static String peerFor(Object body) {
        Object email = body instanceof Map<?, ?> map ? map.get("customerEmail") : null;
        int hash = Math.floorMod(String.valueOf(email).hashCode(), 250 * 250);
        return "203.0." + (1 + hash / 250) + "." + (1 + hash % 250);
    }

    /** A basket line as the checkout sends it: the product, the quantity and the unit price it showed. */
    private static Map<String, Object> line(UUID productId, int quantity, long expectedUnitPricePennies) {
        Map<String, Object> item = lineWithoutPrice(productId, quantity);
        item.put("expectedUnitPricePennies", expectedUnitPricePennies);
        return item;
    }

    private static Map<String, Object> lineWithoutPrice(UUID productId, int quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", productId);
        item.put("quantity", quantity);
        return item;
    }

    /**
     * 37-27 (D-18): the cash-order caps count OPEN orders per email AND per phone at a shop, and the
     * shop and its orders persist across this class's arms, so every arm needs its own contact details.
     * The phone is derived from the arm's address so an arm that resubmits under the same key sends the
     * same contact.
     */
    private static String phoneFor(String email) {
        return "07700" + String.format(java.util.Locale.ROOT, "%06d", Math.floorMod(email.hashCode(), 1_000_000));
    }

    /** A guest-order body carrying the expected total and the acknowledgement; the lines carry their prices. */
    private Map<String, Object> body(String email, List<Map<String, Object>> items, long expectedTotalPennies, int ack) {
        Map<String, Object> body = bodyWithoutExpectations(email, items, ack);
        body.put("expectedTotalPennies", expectedTotalPennies);
        return body;
    }

    /** What a client from before 37-32 sends: no expectedTotalPennies on the body (the lines are the caller's). */
    private Map<String, Object> bodyWithoutExpectations(String email, List<Map<String, Object>> items, int ack) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Basket Revalidation Buyer");
        body.put("customerEmail", email);
        body.put("customerPhone", phoneFor(email));
        body.put("fulfilmentType", "COLLECTION");
        body.put("items", items);
        body.put("acknowledgedAllergenMask", ack);
        return body;
    }

    /** The price the storefront RENDERS for this product: the public menu endpoint, as the checkout reads it. */
    private long renderedPrice(UUID productId) throws Exception {
        MvcResult menu = mockMvc.perform(get("/api/v1/public/shops/" + SHOP_SLUG + "/products")).andReturn();
        assertThat(menu.getResponse().getStatus()).isEqualTo(200);
        JsonNode categories = jsonMapper.readTree(menu.getResponse().getContentAsString());
        for (JsonNode products : categories) {
            for (JsonNode product : products) {
                if (productId.equals(UUID.fromString(product.get("id").asString()))) {
                    return product.get("pricePennies").asLong();
                }
            }
        }
        throw new AssertionError("product " + productId + " is not on the rendered menu");
    }

    private JsonNode assertProblem(MvcResult result, int status, String type, String code) throws Exception {
        String text = responseText(result);
        assertThat(result.getResponse().getStatus()).as(text).isEqualTo(status);
        JsonNode problem = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("type").asString()).as(text).isEqualTo(type);
        assertThat(problem.path("code").asString()).as(text).isEqualTo(code);
        return problem;
    }

    private static List<JsonNode> changesOfKind(JsonNode problem, String kind) {
        List<JsonNode> matching = new java.util.ArrayList<>();
        problem.path("changes").forEach(change -> {
            if (kind.equals(change.path("kind").asString())) {
                matching.add(change);
            }
        });
        return matching;
    }

    /** The kinds in changes[], in the order the server reported them. */
    private static List<String> kinds(JsonNode problem) {
        List<String> kinds = new java.util.ArrayList<>();
        problem.path("changes").forEach(change -> kinds.add(change.path("kind").asString()));
        return kinds;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private static String responseText(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    // ---- SQL read helpers (superuser bootstrap role; tenant pinned for correctness) ----

    private <T> T inTenant(java.util.function.Supplier<T> read) {
        TenantContext.set(TENANT_ID);
        try {
            return read.get();
        } finally {
            TenantContext.clear();
        }
    }

    private long countOrders() {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE tenant_id = ?", Long.class, TENANT_ID)));
    }

    private long countOrdersByEmail(String email) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE tenant_id = ? AND customer_email = ?", Long.class, TENANT_ID, email)));
    }

    private Map<String, Object> orderRowByEmail(String email) {
        return inTenant(() -> jdbcTemplate.queryForMap(
                "SELECT id, total_amount_pennies, subtotal_pennies, allergen_ack_mask FROM orders "
                        + "WHERE tenant_id = ? AND customer_email = ?",
                TENANT_ID, email));
    }

    private long unitPriceOfOnlyLine(UUID orderId) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT unit_price_pennies FROM order_items WHERE order_id = ?", Long.class, orderId)));
    }

    private long countReservationsForKey(String key) {
        return inTenant(() -> Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE tenant_id = ? AND endpoint = ? AND idempotency_key = ?",
                Long.class, TENANT_ID, ENDPOINT, key)));
    }

    // ---- Idempotent seed helpers (the GuestOrderAllergenAckIntegrationTest pattern) ----

    private UUID seedShopIdempotent(String slug, String name, long minimumOrderPennies) {
        return inTenant(() -> {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, TENANT_ID, slug);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            // 37-20 (D-15/D-25): what V80 makes of a pre-existing shop with no hours, so it takes orders at
            // any time; with no hours_source the shop would be closed (NO_HOURS).
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, ?, 0, 'LEGACY_ALWAYS_OPEN')",
                    id, TENANT_ID, name, slug, minimumOrderPennies);
            return id;
        });
    }

    /** A fresh product per call (unique SKU suffix), so an arm that edits a product cannot leak into another. */
    private UUID seedProduct(String skuPrefix, String title, long pricePennies, int allergenMask, Integer quantityInStock) {
        return seedProductInShop(shopId, skuPrefix, title, pricePennies, allergenMask, quantityInStock);
    }

    private UUID seedProductInShop(UUID shop, String skuPrefix, String title, long pricePennies, int allergenMask,
                                   Integer quantityInStock) {
        return inTenant(() -> {
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, ?, ?, 0, true, false, ?, ?, 'STANDARD', 0)",
                    productId, TENANT_ID, skuPrefix + "-" + productId, title, "see label", allergenMask, pricePennies,
                    shop, quantityInStock);
            return productId;
        });
    }
}
