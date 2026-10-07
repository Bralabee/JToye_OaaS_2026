package uk.jtoye.core.storefront;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import uk.jtoye.core.testsupport.GuestOrderAcknowledgements;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 31.1 plan 09 (#785 data half, D-06, D-07, D-08): every customer-facing order contract
 * states what the customer ACKNOWLEDGED (the V69 order columns) and what the kitchen RECORDED
 * (the V63 line snapshot), as separate fields, with the advisory reconciliation flags kept apart
 * from both.
 *
 * <p>Before this plan no confirmation, tracking response or history entry carried any allergen
 * field at all, so a customer could never see what had been recorded, and an order a vendor
 * entered on the customer's behalf was indistinguishable from one the customer acknowledged.
 *
 * <p>Every arm goes through the real HTTP endpoints and reads the JSON the client reads, so a
 * field that is missing, renamed or coerced (null read as 0, null read as []) fails here.
 * NULL ("not recorded") and empty ("recorded, and nothing declared") are asserted as DIFFERENT
 * JSON values on every new field. Real Postgres (Testcontainers), not {@code @Transactional}: each
 * request commits. The test profile has no Stripe key, so storefront orders take the COD branch.
 *
 * <p>RLS note: the Testcontainers bootstrap role is a SUPERUSER, so these arms prove the
 * application contract, not RLS enforcement. This plan adds no table and no policy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class CustomerRecordedAllergenSetIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // The N+1 guard on the history list reads Hibernate's per-collection fetch statistics.
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    /** The Hibernate collection role whose initialisation the history list must never trigger. */
    private static final String ORDER_ITEMS_ROLE = "uk.jtoye.core.order.Order.items";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PublicStorefrontService publicStorefrontService;

    /** Dedicated tenant so a parallel fork's fixtures cannot collide on slug or SKU. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000311091");
    private static final String SHOP_SLUG = "shop-311-09-recorded-allergens";

    /** AllergenCatalog bits, spelled out so a reader can check the expected sets by eye. */
    private static final int GLUTEN = 1;       // bit 0
    private static final int MILK = 1 << 6;    // bit 6

    private UUID shopId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "31.1-09 Recorded Allergens Tenant");
        shopId = seedShopIdempotent(SHOP_SLUG);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // D-08: the confirmation the customer sees moments after paying states the acknowledged set
    // and the recorded set, and an idempotent replay re-derives the SAME four values from the row.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-08: the guest-order confirmation carries the acknowledged and recorded sets, and a replay under the same key carries the same four fields")
    void confirmationAndReplay_carryTheAcknowledgedAndRecordedSets() throws Exception {
        UUID bread = seedProduct("SKU-31109-CONF-A", "Sourdough", GLUTEN, "flour, water, salt");
        UUID cheese = seedProduct("SKU-31109-CONF-B", "Cheddar", MILK, "cheese");
        List<UUID> basket = List.of(bread, cheese);
        int ack = currentMask(basket);
        assertThat(ack).isEqualTo(GLUTEN | MILK);
        String key = "conf-" + UUID.randomUUID();
        Map<String, Object> request = body("conf-" + UUID.randomUUID() + "@example.com", basket, ack);

        MvcResult first = perform(post(ordersUrl()).header("Idempotency-Key", key), request);
        JsonNode confirmation = json(first, 201);
        String text = responseText(first);

        assertField(confirmation, "acknowledgedAllergenMask", text).isEqualTo(GLUTEN | MILK);
        assertThat(texts(field(confirmation, "acknowledgedAllergens", text)))
                .as("AllergenCatalog bit order: Gluten(0), Milk(6)").containsExactly("Gluten", "Milk");
        assertThat(texts(field(confirmation, "recordedAllergens", text)))
                .as("the V63 snapshot union").containsExactly("Gluten", "Milk");
        JsonNode flags = field(confirmation, "recordedAllergenFlags", text);
        assertThat(flags.isArray()).as("flags are an EMPTY array when nothing was flagged: " + text).isTrue();
        assertThat(flags.size()).as(text).isZero();

        MvcResult replay = perform(post(ordersUrl()).header("Idempotency-Key", key), request);
        JsonNode replayed = jsonMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replay.getResponse().getStatus()).as(responseText(replay)).isBetween(200, 201);
        assertThat(replayed.path("orderNumber").asString()).isEqualTo(confirmation.path("orderNumber").asString());
        for (String name : List.of("acknowledgedAllergenMask", "acknowledgedAllergens",
                "recordedAllergens", "recordedAllergenFlags")) {
            assertThat(field(replayed, name, responseText(replay)))
                    .as("the replay re-derives " + name + " from the order row")
                    .isEqualTo(confirmation.get(name));
        }
    }

    // ------------------------------------------------------------------
    // Phase 31 D-03 / T-31.1-30: a reconciliation flag is ADVISORY. It appears in
    // recordedAllergenFlags and never in recordedAllergens or acknowledgedAllergens.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-03: an ingredient that names an undeclared allergen appears in recordedAllergenFlags and NOT in recordedAllergens")
    void undeclaredIngredientAllergen_isAFlag_neverARecordedAllergen() throws Exception {
        UUID croissant = seedProduct("SKU-31109-FLAG", "Croissant", GLUTEN, "flour, butter (MILK), yeast");
        int ack = currentMask(List.of(croissant));
        assertThat(ack).as("only the DECLARED mask is acknowledged").isEqualTo(GLUTEN);

        MvcResult created = perform(post(ordersUrl()),
                body("flag-" + UUID.randomUUID() + "@example.com", List.of(croissant), ack));
        JsonNode confirmation = json(created, 201);
        String text = responseText(created);

        assertThat(texts(field(confirmation, "recordedAllergens", text)))
                .as("the declaration is not widened by the heuristic").containsExactly("Gluten");
        assertThat(texts(field(confirmation, "acknowledgedAllergens", text))).containsExactly("Gluten");
        JsonNode flags = field(confirmation, "recordedAllergenFlags", text);
        assertThat(flags.size()).as(text).isEqualTo(1);
        assertThat(flags.get(0).path("productName").asString()).isEqualTo("Croissant");
        assertThat(flags.get(0).path("allergenBit").asInt()).isEqualTo(6);
        assertThat(flags.get(0).path("allergenName").asString()).isEqualTo("Milk");
    }

    // ------------------------------------------------------------------
    // Tracking: the same four fields plus the channel, read from the order's own columns and the
    // V63 snapshot.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Tracking a storefront order returns the acknowledged set, the recorded set, the flags and placedVia STOREFRONT")
    void trackingAStorefrontOrder_carriesBothSetsAndTheChannel() throws Exception {
        UUID croissant = seedProduct("SKU-31109-TRACK-A", "Croissant", GLUTEN, "flour, butter (MILK), yeast");
        UUID cheese = seedProduct("SKU-31109-TRACK-B", "Cheddar", MILK, "cheese");
        List<UUID> basket = List.of(croissant, cheese);
        String email = "track-" + UUID.randomUUID() + "@example.com";
        String orderNumber = placeStorefrontOrder(email, basket);

        MvcResult tracked = mockMvc.perform(get(trackUrl(orderNumber)).param("email", email)).andReturn();
        JsonNode status = json(tracked, 200);
        String text = responseText(tracked);

        assertField(status, "acknowledgedAllergenMask", text).isEqualTo(GLUTEN | MILK);
        assertThat(texts(field(status, "acknowledgedAllergens", text))).containsExactly("Gluten", "Milk");
        assertThat(texts(field(status, "recordedAllergens", text))).containsExactly("Gluten", "Milk");
        JsonNode flags = field(status, "recordedAllergenFlags", text);
        assertThat(flags.size()).as(text).isEqualTo(1);
        assertThat(flags.get(0).path("productName").asString()).isEqualTo("Croissant");
        assertThat(flags.get(0).path("allergenName").asString()).isEqualTo("Milk");
        assertThat(field(status, "placedVia", text).asString()).isEqualTo("STOREFRONT");
    }

    // ------------------------------------------------------------------
    // D-07: an order the SHOP entered for the customer took no acknowledgement. Tracking says so:
    // the acknowledged fields are null (not recorded) and the channel is VENDOR; the kitchen's
    // recorded set is still shown.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-07: tracking a vendor-entered order returns acknowledged fields null, placedVia VENDOR and the recorded snapshot")
    void trackingAVendorOrder_saysNoAcknowledgementWasRecorded() throws Exception {
        UUID cheese = seedProduct("SKU-31109-VENDOR", "Cheddar", MILK, "cheese");
        String email = "vendor-" + UUID.randomUUID() + "@example.com";
        String orderNumber = placeVendorOrder(email, cheese);

        MvcResult tracked = mockMvc.perform(get(trackUrl(orderNumber)).param("email", email)).andReturn();
        JsonNode status = json(tracked, 200);
        String text = responseText(tracked);

        assertThat(field(status, "acknowledgedAllergenMask", text).isNull()).as("not recorded, never 0: " + text).isTrue();
        assertThat(field(status, "acknowledgedAllergens", text).isNull()).as("not recorded, never []: " + text).isTrue();
        assertThat(field(status, "placedVia", text).asString()).isEqualTo("VENDOR");
        assertThat(texts(field(status, "recordedAllergens", text))).containsExactly("Milk");
    }

    // ------------------------------------------------------------------
    // D-06: a row that predates V69 has NULL ack columns. NULL is "not recorded" and must reach
    // the wire as JSON null, never as 0 or [] (which would claim an acknowledgement of nothing).
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-06: a pre-V69 order (ack columns NULL) tracks with acknowledgedAllergenMask and acknowledgedAllergens JSON null, not 0 and not []")
    void preV69Order_tracksWithNullAcknowledgement_notZeroOrEmpty() throws Exception {
        UUID cheese = seedProduct("SKU-31109-PREV69", "Cheddar", MILK, "cheese");
        String email = "prev69-" + UUID.randomUUID() + "@example.com";
        String orderNumber = placeStorefrontOrder(email, List.of(cheese));
        int updated = inTenant(() -> jdbcTemplate.update(
                "UPDATE orders SET allergen_ack_mask = NULL, allergen_ack_at = NULL, placed_via = NULL "
                        + "WHERE tenant_id = ? AND order_number = ?", TENANT_ID, orderNumber));
        assertThat(updated).as("the order was reshaped to its pre-V69 form").isEqualTo(1);

        MvcResult tracked = mockMvc.perform(get(trackUrl(orderNumber)).param("email", email)).andReturn();
        JsonNode status = json(tracked, 200);
        String text = responseText(tracked);

        assertThat(field(status, "acknowledgedAllergenMask", text).isNull()).as("JSON null, not 0: " + text).isTrue();
        assertThat(field(status, "acknowledgedAllergens", text).isNull()).as("JSON null, not []: " + text).isTrue();
        assertThat(field(status, "placedVia", text).isNull()).as("no channel is guessed: " + text).isTrue();
        assertThat(texts(field(status, "recordedAllergens", text)))
                .as("the line snapshot (V63) is independent of the V69 columns").containsExactly("Milk");
    }

    // ------------------------------------------------------------------
    // V63's three-state rule on the RECORDED side: a line without a snapshot makes the order
    // "not recorded" (null), never an empty set.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("V63: an order whose lines predate the snapshot tracks with recordedAllergens and recordedAllergenFlags JSON null")
    void preV63Lines_trackWithNullRecordedSet() throws Exception {
        UUID cheese = seedProduct("SKU-31109-PREV63", "Cheddar", MILK, "cheese");
        String email = "prev63-" + UUID.randomUUID() + "@example.com";
        String orderNumber = placeStorefrontOrder(email, List.of(cheese));
        int updated = inTenant(() -> jdbcTemplate.update(
                "UPDATE order_items SET allergen_mask = NULL, allergen_flag_mask = NULL WHERE order_id = "
                        + "(SELECT id FROM orders WHERE tenant_id = ? AND order_number = ?)", TENANT_ID, orderNumber));
        assertThat(updated).isEqualTo(1);

        MvcResult tracked = mockMvc.perform(get(trackUrl(orderNumber)).param("email", email)).andReturn();
        JsonNode status = json(tracked, 200);
        String text = responseText(tracked);

        assertThat(field(status, "recordedAllergens", text).isNull()).as("not recorded, never []: " + text).isTrue();
        assertThat(field(status, "recordedAllergenFlags", text).isNull()).as(text).isTrue();
        assertField(status, "acknowledgedAllergenMask", text).isEqualTo(MILK);
    }

    // ------------------------------------------------------------------
    // D-06: an all-zero basket that the customer acknowledged is RECORDED as nothing declared:
    // 0 and [], which is a different statement from null.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-06: an acknowledged all-zero basket returns acknowledgedAllergenMask 0 and acknowledgedAllergens [] on confirmation and tracking")
    void acknowledgedAllZeroBasket_isZeroAndEmpty_notNull() throws Exception {
        UUID water = seedProduct("SKU-31109-ZERO", "Still Water", 0, "water");
        String email = "zero-" + UUID.randomUUID() + "@example.com";

        MvcResult created = perform(post(ordersUrl()), body(email, List.of(water), 0));
        JsonNode confirmation = json(created, 201);
        String text = responseText(created);
        assertField(confirmation, "acknowledgedAllergenMask", text).isEqualTo(0);
        assertEmptyArray(field(confirmation, "acknowledgedAllergens", text), text);
        assertEmptyArray(field(confirmation, "recordedAllergens", text), text);

        String orderNumber = confirmation.path("orderNumber").asString();
        MvcResult tracked = mockMvc.perform(get(trackUrl(orderNumber)).param("email", email)).andReturn();
        JsonNode status = json(tracked, 200);
        String trackedText = responseText(tracked);
        assertField(status, "acknowledgedAllergenMask", trackedText).isEqualTo(0);
        assertEmptyArray(field(status, "acknowledgedAllergens", trackedText), trackedText);
        assertEmptyArray(field(status, "recordedAllergens", trackedText), trackedText);
    }

    // ------------------------------------------------------------------
    // The signed-in / verified order history reads order COLUMNS only. It states the acknowledged
    // set and the channel on every entry, leaves the recorded set null (detail-only), and must not
    // initialise a single order-item collection: the V63 measurement was one extra SELECT per row
    // (7 orders, 7 statements) the moment the lines are touched on a list path (T-31.1-31).
    // ------------------------------------------------------------------
    @Test
    @DisplayName("History: 7 orders carry the acknowledged set and placedVia from columns, recordedAllergens null, and no order-items collection is initialised")
    void history_carriesTheAcknowledgedSetFromColumns_withoutLoadingOrderLines() throws Exception {
        UUID bread = seedProduct("SKU-31109-HIST-A", "Sourdough", GLUTEN, "flour, water, salt");
        UUID cheese = seedProduct("SKU-31109-HIST-B", "Cheddar", MILK, "cheese");
        String email = "history-" + UUID.randomUUID() + "@example.com";
        List<String> storefrontOrders = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            storefrontOrders.add(placeStorefrontOrder(email, List.of(bread, cheese)));
        }
        String vendorOrder = placeVendorOrder(email, cheese);

        // Through the real endpoint: what the client reads.
        MvcResult listed = mockMvc.perform(get("/api/v1/public/orders")
                .param("email", email).param("verify", storefrontOrders.get(0)).param("size", "20")).andReturn();
        JsonNode page = json(listed, 200);
        String text = responseText(listed);
        JsonNode content = page.path("content");
        assertThat(content.size()).as(text).isEqualTo(7);
        int storefrontSeen = 0;
        for (JsonNode entry : content) {
            String number = entry.path("orderNumber").asString();
            assertThat(field(entry, "recordedAllergens", text).isNull())
                    .as("detail-only: the list does not load lines, so it states nothing about them").isTrue();
            assertThat(field(entry, "recordedAllergenFlags", text).isNull()).as(text).isTrue();
            if (number.equals(vendorOrder)) {
                assertThat(field(entry, "placedVia", text).asString()).isEqualTo("VENDOR");
                assertThat(field(entry, "acknowledgedAllergenMask", text).isNull()).as("not recorded: " + text).isTrue();
                assertThat(field(entry, "acknowledgedAllergens", text).isNull()).as("not recorded: " + text).isTrue();
            } else {
                assertThat(storefrontOrders).contains(number);
                storefrontSeen++;
                assertThat(field(entry, "placedVia", text).asString()).isEqualTo("STOREFRONT");
                assertField(entry, "acknowledgedAllergenMask", text).isEqualTo(GLUTEN | MILK);
                assertThat(texts(field(entry, "acknowledgedAllergens", text))).containsExactly("Gluten", "Milk");
            }
        }
        assertThat(storefrontSeen).isEqualTo(6);

        // The N+1 guard, measured on the service call alone (the endpoint's verify step is a
        // single-order trackOrder, which legitimately loads ONE order's lines).
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        assertThat(statistics.getCollectionRoleNames()).as("the probe names a real collection role")
                .contains(ORDER_ITEMS_ROLE);

        statistics.clear();
        org.springframework.data.domain.Page<uk.jtoye.core.storefront.dto.PublicOrderStatus> history =
                publicStorefrontService.getCustomerOrders(email, org.springframework.data.domain.PageRequest.of(0, 20));
        long historyFetches = statistics.getCollectionStatistics(ORDER_ITEMS_ROLE).getFetchCount();
        assertThat(history.getContent()).hasSize(7);
        assertThat(history.getContent()).allSatisfy(entry -> assertThat(entry.getPlacedVia()).isNotNull());
        assertThat(historyFetches)
                .as("T-31.1-31: the history list initialised order-item collections (one SELECT per row)")
                .isZero();

        // Positive control: the same probe DOES see a single-order read load its lines, so the zero
        // above is a measurement and not a blind instrument.
        statistics.clear();
        publicStorefrontService.trackOrder(storefrontOrders.get(0), email);
        assertThat(statistics.getCollectionStatistics(ORDER_ITEMS_ROLE).getFetchCount())
                .as("control: trackOrder loads exactly one order's lines").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // The vendor DETAIL view (the dashboard order page, the kitchen board and the MCP read_orders
    // orderId call) carries the acknowledgement and the channel beside the existing recorded set
    // and flags, which stay separate.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Vendor detail: GET /orders/{id}/detail carries allergenAckMask, allergenAckAt, placedVia and acknowledgedAllergenNames; a vendor order carries null and VENDOR")
    void vendorDetail_carriesTheAcknowledgementAndTheChannel() throws Exception {
        UUID croissant = seedProduct("SKU-31109-DETAIL-A", "Croissant", GLUTEN, "flour, butter (MILK), yeast");
        String email = "detail-" + UUID.randomUUID() + "@example.com";
        String storefrontOrder = placeStorefrontOrder(email, List.of(croissant));
        String vendorOrder = placeVendorOrder(email, croissant);

        MvcResult storefront = mockMvc.perform(get("/api/v1/orders/" + orderId(storefrontOrder) + "/detail")
                .with(vendorJwt())).andReturn();
        JsonNode detail = json(storefront, 200);
        String text = responseText(storefront);
        assertField(detail, "allergenAckMask", text).isEqualTo(GLUTEN);
        assertThat(field(detail, "allergenAckAt", text).isNull()).as("acknowledged-at is recorded: " + text).isFalse();
        assertThat(field(detail, "placedVia", text).asString()).isEqualTo("STOREFRONT");
        assertThat(texts(field(detail, "acknowledgedAllergenNames", text))).containsExactly("Gluten");
        assertThat(texts(field(detail, "allergenNames", text)))
                .as("the recorded set is unchanged and excludes the flag").containsExactly("Gluten");
        assertThat(field(detail, "allergenFlags", text).get(0).path("allergenName").asString()).isEqualTo("Milk");

        MvcResult vendor = mockMvc.perform(get("/api/v1/orders/" + orderId(vendorOrder) + "/detail")
                .with(vendorJwt())).andReturn();
        JsonNode vendorDetail = json(vendor, 200);
        String vendorText = responseText(vendor);
        assertThat(field(vendorDetail, "allergenAckMask", vendorText).isNull()).as("not recorded: " + vendorText).isTrue();
        assertThat(field(vendorDetail, "allergenAckAt", vendorText).isNull()).as(vendorText).isTrue();
        assertThat(field(vendorDetail, "acknowledgedAllergenNames", vendorText).isNull())
                .as("not recorded, never []: " + vendorText).isTrue();
        assertThat(field(vendorDetail, "placedVia", vendorText).asString()).isEqualTo("VENDOR");
    }

    // ------------------------------------------------------------------
    // The vendor LIST view (OrderDto) carries the three column fields. OrderDto is a Phase 38
    // golden DTO, so the new fields are NON_NULL (31.1-01 section 2, route (a)): ABSENT means not
    // recorded, and it is never written as 0.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Vendor list: OrderDto carries allergenAckMask, allergenAckAt and placedVia; a vendor order has placedVia VENDOR and no acknowledgement")
    void vendorList_carriesTheColumnFields() throws Exception {
        UUID cheese = seedProduct("SKU-31109-LIST", "Cheddar", MILK, "cheese");
        String email = "list-" + UUID.randomUUID() + "@example.com";
        String storefrontOrder = placeStorefrontOrder(email, List.of(cheese));
        String vendorOrder = placeVendorOrder(email, cheese);

        MvcResult listed = mockMvc.perform(get("/api/v1/orders/shop/" + shopId)
                .param("size", "200").with(vendorJwt())).andReturn();
        JsonNode content = json(listed, 200).path("content");
        String text = responseText(listed);

        JsonNode storefront = entryFor(content, storefrontOrder, text);
        assertField(storefront, "allergenAckMask", text).isEqualTo(MILK);
        assertThat(field(storefront, "allergenAckAt", text).isNull()).as(text).isFalse();
        assertThat(field(storefront, "placedVia", text).asString()).isEqualTo("STOREFRONT");

        JsonNode vendor = entryFor(content, vendorOrder, text);
        assertThat(field(vendor, "placedVia", text).asString()).isEqualTo("VENDOR");
        assertThat(vendor.has("allergenAckMask")).as("not recorded is ABSENT on OrderDto, never 0: " + text).isFalse();
        assertThat(vendor.has("allergenAckAt")).as(text).isFalse();
    }

    // ---- order placement ----

    private UUID orderId(String orderNumber) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE tenant_id = ? AND order_number = ?", UUID.class, TENANT_ID, orderNumber));
    }

    private static JsonNode entryFor(JsonNode content, String orderNumber, String text) {
        for (JsonNode entry : content) {
            if (orderNumber.equals(entry.path("orderNumber").asString())) {
                return entry;
            }
        }
        throw new AssertionError("order " + orderNumber + " is not on the list: " + text);
    }

    private String placeStorefrontOrder(String email, List<UUID> basket) throws Exception {
        MvcResult created = perform(post(ordersUrl()), body(email, basket, currentMask(basket)));
        return json(created, 201).path("orderNumber").asString();
    }

    private String placeVendorOrder(String email, UUID product) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("shopId", shopId);
        request.put("customerEmail", email);
        request.put("items", List.of(Map.of("productId", product, "quantity", 1)));
        MvcResult created = perform(post("/api/v1/orders")
                .with(vendorJwt())
                .header("Idempotency-Key", "vendor-" + UUID.randomUUID()), request);
        return json(created, 201).path("orderNumber").asString();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor vendorJwt() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", TENANT_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_orders:write"));
    }

    private int currentMask(List<UUID> productIds) {
        return GuestOrderAcknowledgements.currentMask(jdbcTemplate, TENANT_ID, productIds);
    }

    // ---- JSON helpers ----

    /** The field must be PRESENT on the wire; a missing field is a different failure from a null one. */
    private static JsonNode field(JsonNode node, String name, String text) {
        assertThat(node.has(name)).as("field '" + name + "' is present on the wire: " + text).isTrue();
        return node.get(name);
    }

    private static org.assertj.core.api.AbstractIntegerAssert<?> assertField(JsonNode node, String name, String text) {
        JsonNode value = field(node, name, text);
        assertThat(value.isInt()).as("'" + name + "' is an integer, not null: " + text).isTrue();
        return assertThat(value.asInt()).as(name + ": " + text);
    }

    private static void assertEmptyArray(JsonNode node, String text) {
        assertThat(node.isArray()).as("an EMPTY ARRAY, not null: " + text).isTrue();
        assertThat(node.size()).as(text).isZero();
    }

    private static List<String> texts(JsonNode array) {
        assertThat(array.isArray()).as("expected an array, got " + array).isTrue();
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus()).as(responseText(result)).isEqualTo(expectedStatus);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    // ---- HTTP helpers ----

    private String ordersUrl() {
        return "/api/v1/public/shops/" + SHOP_SLUG + "/orders";
    }

    private static String trackUrl(String orderNumber) {
        return "/api/v1/public/orders/" + orderNumber;
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    private static Map<String, Object> body(String email, List<UUID> productIds, Integer ack) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (UUID productId : productIds) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", productId);
            item.put("quantity", 1);
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Recorded Allergens Buyer");
        body.put("customerEmail", email);
        body.put("customerPhone", "+447700900319");
        body.put("fulfilmentType", "COLLECTION");
        body.put("items", items);
        if (ack != null) {
            body.put("acknowledgedAllergenMask", ack);
        }
        return body;
    }

    private static String responseText(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    // ---- SQL helpers (superuser bootstrap role; tenant pinned for correctness) ----

    private <T> T inTenant(Supplier<T> read) {
        TenantContext.set(TENANT_ID);
        try {
            return read.get();
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedShopIdempotent(String slug) {
        return inTenant(() -> {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, TENANT_ID, slug);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            // No opening_hours -> the shop is always open (validateShopIsOpen returns early).
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                    id, TENANT_ID, "31.1-09 Recorded Allergens Shop", slug);
            return id;
        });
    }

    /** A fresh product per call (unique SKU suffix), so no arm can leak a mask into another. */
    private UUID seedProduct(String skuPrefix, String title, int allergenMask, String ingredients) {
        return inTenant(() -> {
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, ?, 1000, 0, true, false, ?, 100, 'STANDARD', 0)",
                    productId, TENANT_ID, skuPrefix + "-" + productId, title, ingredients, allergenMask, shopId);
            return productId;
        });
    }
}
