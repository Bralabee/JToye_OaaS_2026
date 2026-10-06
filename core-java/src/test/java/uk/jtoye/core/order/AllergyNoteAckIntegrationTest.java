package uk.jtoye.core.order;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.GuestOrderAcknowledgements;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

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
 * Phase 31.1 plan 13 (#812, decision D-15): a customer's allergy request is its own field, the shop
 * must acknowledge it, the platform records who acknowledged it and when, and the customer can see
 * that it was read.
 *
 * <p>The persona run sent "My child is allergic to peanuts and sesame - please confirm" as plain
 * delivery NOTES. The order went to COMPLETED and nothing anywhere recorded that anybody had read it.
 *
 * <p>Every arm drives the real HTTP endpoints and reads the JSON a client reads, and every stored
 * fact is read back by SQL, so a field that is missing, renamed, merged into another, or written to
 * the wrong column fails here. Real Postgres 15 (Testcontainers), not {@code @Transactional}: each
 * request commits. The test profile has no Stripe key, so storefront orders take the COD branch.
 *
 * <p>RLS note: the Testcontainers bootstrap role is a SUPERUSER, so these arms prove the application
 * contract and the shop-access gate, not RLS. This plan adds no table and no policy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class AllergyNoteAckIntegrationTest {

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
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000311131");
    private static final String SHOP_SLUG = "shop-311-13-allergy-note";

    /** The persona's request, verbatim apart from the dash. */
    private static final String NOTE = "My child is allergic to peanuts and sesame - please confirm.";
    private static final String DELIVERY_NOTE = "Ring the bell";

    private static final int GLUTEN = 1;

    private UUID shopId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "31.1-13 Allergy Note Tenant");
        shopId = seedShopIdempotent(SHOP_SLUG, "31.1-13 Allergy Note Shop");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // D-15: the allergy note and the delivery note are two fields, stored in two columns, and the
    // vendor's detail view, kitchen board and list each carry the allergy note as its own field.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-15: the allergy note is stored apart from the delivery notes and reaches the vendor detail, list and kitchen board with no acknowledgement yet")
    void allergyNote_isStoredApartFromDeliveryNotes_andReachesTheVendorAndTheKitchen() throws Exception {
        UUID bread = seedProduct("SKU-31113-SEP", "Sourdough", GLUTEN);
        String email = "separate-" + UUID.randomUUID() + "@example.com";
        String orderNumber = placeStorefrontOrder(email, bread, DELIVERY_NOTE, NOTE);
        UUID orderId = orderId(orderNumber);

        // The vendor's order detail (the dashboard order page and the MCP read_orders orderId call).
        MvcResult detailResult = mockMvc.perform(get("/api/v1/orders/" + orderId + "/detail").with(adminJwt()))
                .andReturn();
        JsonNode detail = json(detailResult, 200);
        String text = responseText(detailResult);
        assertThat(field(detail, "allergyNote", text).asString()).isEqualTo(NOTE);
        assertThat(field(detail, "notes", text).asString())
                .as("the delivery note carries only the delivery note").isEqualTo(DELIVERY_NOTE);
        assertThat(field(detail, "allergyNoteAcknowledgedAt", text).isNull())
                .as("nobody has acknowledged it yet: " + text).isTrue();
        assertThat(field(detail, "allergyNoteAcknowledgedBy", text).isNull()).as(text).isTrue();

        // By SQL: each column holds only its own text.
        Map<String, Object> row = inTenant(() -> jdbcTemplate.queryForMap(
                "SELECT notes, allergy_note, allergy_note_ack_at, allergy_note_ack_by FROM orders WHERE id = ?",
                orderId));
        assertThat(row.get("notes")).isEqualTo(DELIVERY_NOTE);
        assertThat(row.get("allergy_note")).isEqualTo(NOTE);
        assertThat(row.get("allergy_note_ack_at")).isNull();
        assertThat(row.get("allergy_note_ack_by")).isNull();

        // The vendor list (OrderDto, a Phase 38 golden DTO): the note is present; the acknowledgement
        // fields are ABSENT until recorded (NON_NULL), never written as null on this DTO.
        MvcResult listed = mockMvc.perform(get("/api/v1/orders/shop/" + shopId)
                .param("size", "200").with(adminJwt())).andReturn();
        String listText = responseText(listed);
        JsonNode entry = entryFor(json(listed, 200).path("content"), orderNumber, listText);
        assertThat(field(entry, "allergyNote", listText).asString()).isEqualTo(NOTE);
        assertThat(entry.has("allergyNoteAcknowledgedAt")).as("absent until recorded: " + listText).isFalse();
        assertThat(entry.has("allergyNoteAcknowledgedBy")).as(listText).isFalse();

        // The kitchen board (OrderDetailDto per ticket) once the order is in a kitchen status.
        int confirmed = inTenant(() -> jdbcTemplate.update(
                "UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", orderId));
        assertThat(confirmed).as("the order was moved onto the kitchen board").isEqualTo(1);
        MvcResult board = mockMvc.perform(get("/api/v1/orders/kitchen").param("shopId", shopId.toString())
                .param("size", "200").with(adminJwt())).andReturn();
        String boardText = responseText(board);
        JsonNode ticket = entryFor(json(board, 200).path("content"), orderNumber, boardText);
        assertThat(field(ticket, "allergyNote", boardText).asString()).isEqualTo(NOTE);
        assertThat(field(ticket, "allergyNoteAcknowledgedAt", boardText).isNull()).as(boardText).isTrue();
    }

    // ------------------------------------------------------------------
    // D-15: a blank note is no note (NULL, "none given"), a padded note is trimmed, and an order
    // that never sent the field stores NULL too.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-15: a whitespace-only allergy note is stored as NULL, a padded one is trimmed, and an order without the field stores NULL")
    void blankNote_isStoredAsNull_andAPaddedNoteIsTrimmed() throws Exception {
        UUID bread = seedProduct("SKU-31113-BLANK", "Sourdough", GLUTEN);

        String blank = placeStorefrontOrder("blank-" + UUID.randomUUID() + "@example.com", bread, null, "   ");
        MvcResult blankDetail = mockMvc.perform(get("/api/v1/orders/" + orderId(blank) + "/detail")
                .with(adminJwt())).andReturn();
        String blankText = responseText(blankDetail);
        assertThat(field(json(blankDetail, 200), "allergyNote", blankText).isNull())
                .as("a blank note is no note: " + blankText).isTrue();
        assertThat(allergyNoteColumn(blank)).as("stored as NULL, never as whitespace").isNull();

        String padded = placeStorefrontOrder("padded-" + UUID.randomUUID() + "@example.com", bread, null,
                "  Sesame allergy  ");
        assertThat(allergyNoteColumn(padded)).isEqualTo("Sesame allergy");

        String absent = placeStorefrontOrder("absent-" + UUID.randomUUID() + "@example.com", bread, null, null);
        assertThat(allergyNoteColumn(absent)).as("no field sent -> NULL").isNull();
    }

    // ------------------------------------------------------------------
    // D-15: the note is capped at 500 characters at the API boundary, like the V73 column.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-15: a 501-character allergy note is refused 400 and no order is created; 500 characters is accepted")
    void noteOver500Characters_isRefused() throws Exception {
        UUID bread = seedProduct("SKU-31113-LONG", "Sourdough", GLUTEN);
        String email = "long-" + UUID.randomUUID() + "@example.com";

        MvcResult refused = perform(post(ordersUrl()), body(email, bread, null, "a".repeat(501)));
        assertThat(refused.getResponse().getStatus()).as(responseText(refused)).isEqualTo(400);
        assertThat(inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND customer_email = ?", Long.class, TENANT_ID, email)))
                .as("nothing was written for the refused request").isZero();

        String accepted = placeStorefrontOrder(email, bread, null, "b".repeat(500));
        assertThat(allergyNoteColumn(accepted)).hasSize(500);
    }

    // ---- order placement ----

    private String placeStorefrontOrder(String email, UUID product, String notes, String allergyNote) throws Exception {
        MvcResult created = perform(post(ordersUrl()), body(email, product, notes, allergyNote));
        return json(created, 201).path("orderNumber").asString();
    }

    private Map<String, Object> body(String email, UUID product, String notes, String allergyNote) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", product);
        item.put("quantity", 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Allergy Note Buyer");
        body.put("customerEmail", email);
        body.put("customerPhone", "+447700900313");
        body.put("fulfilmentType", "COLLECTION");
        body.put("items", List.of(item));
        body.put("acknowledgedAllergenMask",
                GuestOrderAcknowledgements.currentMask(jdbcTemplate, TENANT_ID, List.of(product)));
        if (notes != null) {
            body.put("notes", notes);
        }
        if (allergyNote != null) {
            body.put("allergyNote", allergyNote);
        }
        return body;
    }

    private UUID orderId(String orderNumber) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE tenant_id = ? AND order_number = ?", UUID.class, TENANT_ID, orderNumber));
    }

    private String allergyNoteColumn(String orderNumber) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT allergy_note FROM orders WHERE tenant_id = ? AND order_number = ?",
                String.class, TENANT_ID, orderNumber));
    }

    /** A realm admin of the tenant (implicit GROUP_ADMIN) with the order-write scope. */
    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", TENANT_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_orders:write"));
    }

    // ---- JSON helpers ----

    /** The field must be PRESENT on the wire; a missing field is a different failure from a null one. */
    private static JsonNode field(JsonNode node, String name, String text) {
        assertThat(node.has(name)).as("field '" + name + "' is present on the wire: " + text).isTrue();
        return node.get(name);
    }

    private static JsonNode entryFor(JsonNode content, String orderNumber, String text) {
        for (JsonNode entry : content) {
            if (orderNumber.equals(entry.path("orderNumber").asString())) {
                return entry;
            }
        }
        throw new AssertionError("order " + orderNumber + " is not on the list: " + text);
    }

    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus()).as(responseText(result)).isEqualTo(expectedStatus);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    // ---- HTTP helpers ----

    private String ordersUrl() {
        return "/api/v1/public/shops/" + SHOP_SLUG + "/orders";
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
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

    private UUID seedShopIdempotent(String slug, String name) {
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
                    id, TENANT_ID, name, slug);
            return id;
        });
    }

    /** A fresh product per call (unique SKU suffix), so no arm can leak state into another. */
    private UUID seedProduct(String skuPrefix, String title, int allergenMask) {
        return inTenant(() -> {
            UUID productId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, 'flour, water, salt', ?, 1000, 0, true, false, ?, 100, "
                            + "'STANDARD', 0)",
                    productId, TENANT_ID, skuPrefix + "-" + productId, title, allergenMask, shopId);
            return productId;
        });
    }
}
