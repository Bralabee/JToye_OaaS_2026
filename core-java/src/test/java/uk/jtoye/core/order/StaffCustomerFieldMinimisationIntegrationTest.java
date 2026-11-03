package uk.jtoye.core.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.ShopGrants;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Issue #856 / SW-TND-08 (owner ruling 2026-11-03): field-level minimisation of customer contact
 * data for a caller whose best honoured role on the order's or customer's shop is STAFF.
 *
 * <p>The rule, proven here over HTTP through the real filter chain (JWT, tenant filter, access
 * gate, service, DTO, JSON), so the assertions are about the BYTES ON THE WIRE and never about a
 * Java getter: such a caller sees the customer's name and phone, the items, the allergen fields,
 * the notes and the allergy note, but NOT the customer email, and NO address on a non-DELIVERY
 * order. A DELIVERY order keeps its address, because STAFF performs the hand-off (mark-ready and
 * complete are gated at STAFF, and the kitchen ticket prints the address). SHOP_MANAGER and above
 * are unchanged; a mixed caller is decided per row's shop.
 *
 * <p>Every literal a {@code doesNotContain} looks for is UUID-suffixed per test, so an absence
 * cannot be satisfied by an unrelated match, and every call asserts its HTTP status before its
 * body is read, so a 403 or 404 body can never satisfy an absence.
 *
 * <p>{@link #managerAndAdmins_seeFullContact} is the CONTROL: without it the other four tests
 * could pass by masking everybody.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class StaffCustomerFieldMinimisationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private ShopAccessService shopAccessService;

    private Object bootedStrictScoping;

    @BeforeEach
    void captureStrictScoping() {
        // Left at its booted value (strict ON by default); captured only so it is restored exactly.
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
    }

    @AfterEach
    void restoreStrictScoping() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
    }

    /** One fresh tenant and its literals, every one suffixed so no absence can match by accident. */
    private record Fixture(UUID tenant, UUID shopA, UUID shopB,
                           UUID custA, UUID custB, UUID custShopless,
                           UUID deliveryOrder, UUID collectionOrder, UUID shopBOrder,
                           String custAName, String custAEmail, String custAPhone,
                           String custBName, String custBEmail, String custBPhone,
                           String shoplessName, String shoplessEmail, String shoplessPhone,
                           String deliveryEmail, String deliveryPhone, String deliveryAddress,
                           String deliveryNotes, String allergyNote,
                           String collectionName, String collectionEmail, String collectionPhone,
                           String legacyAddress, String legacyPostcode,
                           String shopBOrderEmail) {}

    // ------------------------------------------------------------------
    // 1. The kitchen board
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#856: STAFF's kitchen board omits every customer email and the collection order's address, and keeps name, phone, items, allergens, notes, allergy note and the DELIVERY address")
    void staffKitchenBoard_omitsEmailAndCollectionAddress_keepsNamePhoneItemsAllergensNotes() throws Exception {
        Fixture f = seed();
        UUID staff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), staff, f.shopA(), "STAFF", "staff-" + staff + "@example.com");

        String board = ok(get("/api/v1/orders/kitchen").param("shopId", f.shopA().toString()), f.tenant(), staff);

        assertThat(board).as("STAFF's kitchen board carries no customer email")
                .doesNotContain(f.deliveryEmail()).doesNotContain(f.collectionEmail());
        assertThat(board).as("STAFF's kitchen board carries no address for the COLLECTION order")
                .doesNotContain(f.legacyAddress()).doesNotContain(f.legacyPostcode());

        JsonNode content = jsonMapper.readTree(board).path("content");
        assertThat(content.size()).as("both shop-A tickets are on the board").isEqualTo(2);
        JsonNode delivery = byId(content, f.deliveryOrder());
        JsonNode collection = byId(content, f.collectionOrder());
        assertThat(delivery.path("customerName").asString()).isEqualTo(f.custAName());
        assertThat(delivery.path("customerPhone").asString()).isEqualTo(f.deliveryPhone());
        assertThat(delivery.path("notes").asString()).isEqualTo(f.deliveryNotes());
        assertThat(delivery.path("allergyNote").asString()).isEqualTo(f.allergyNote());
        assertThat(delivery.path("addressLine1").asString())
                .as("STAFF performs the delivery hand-off, so the DELIVERY address is kept")
                .isEqualTo(f.deliveryAddress());
        assertThat(delivery.path("items").get(0).path("allergenMask").asInt()).isEqualTo(1);
        assertThat(collection.path("customerName").asString()).isEqualTo(f.collectionName());
        assertThat(collection.path("customerPhone").asString()).isEqualTo(f.collectionPhone());
        assertThat(collection.path("items").get(0).path("allergenMask").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 2. Every other order read, a transition and the acknowledgement
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#856: STAFF's order reads, a transition and the allergy-note acknowledgement carry no customer email; the collection detail carries no address")
    void staffOrderReads_transitionAndAck_omitEmail() throws Exception {
        Fixture f = seed();
        UUID staff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), staff, f.shopA(), "STAFF", "staff-" + staff + "@example.com");

        List<String> bodies = new ArrayList<>();
        bodies.add(ok(get("/api/v1/orders"), f.tenant(), staff));
        bodies.add(ok(get("/api/v1/orders").param("shopId", f.shopA().toString()), f.tenant(), staff));
        bodies.add(ok(get("/api/v1/orders/shop/" + f.shopA()), f.tenant(), staff));
        bodies.add(ok(get("/api/v1/orders/status/CONFIRMED"), f.tenant(), staff));
        bodies.add(ok(get("/api/v1/orders/customer/" + f.custA()), f.tenant(), staff));
        bodies.add(ok(get("/api/v1/orders/" + f.deliveryOrder()), f.tenant(), staff));
        String collectionDetail = ok(get("/api/v1/orders/" + f.collectionOrder() + "/detail"), f.tenant(), staff);
        bodies.add(collectionDetail);
        bodies.add(ok(post("/api/v1/orders/" + f.deliveryOrder() + "/start-preparation"), f.tenant(), staff));
        bodies.add(ok(post("/api/v1/orders/" + f.deliveryOrder() + "/allergy-note/acknowledgement"), f.tenant(), staff));

        for (int i = 0; i < bodies.size(); i++) {
            assertThat(bodies.get(i)).as("STAFF response #%d carries no customer email", i)
                    .doesNotContain(f.deliveryEmail())
                    .doesNotContain(f.collectionEmail());
        }
        assertThat(collectionDetail).as("the COLLECTION order's detail carries no address for STAFF")
                .doesNotContain(f.legacyAddress()).doesNotContain(f.legacyPostcode());
        assertThat(jsonMapper.readTree(collectionDetail).path("customerPhone").asString())
                .as("the phone is kept").isEqualTo(f.collectionPhone());
        assertThat(bodies.get(0)).as("the list keeps the customer's name and phone")
                .contains(f.custAName()).contains(f.deliveryPhone());
    }

    // ------------------------------------------------------------------
    // 3. The customer reads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#856: STAFF (per-shop and tenant-wide) reads customers with name and phone but no email")
    void staffCustomerReads_omitEmail_keepNameAndPhone() throws Exception {
        Fixture f = seed();
        UUID staffA = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), staffA, f.shopA(), "STAFF", "staff-" + staffA + "@example.com");
        UUID staffAll = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), staffAll, null, "STAFF", "staff-" + staffAll + "@example.com");

        String listA = ok(get("/api/v1/customers"), f.tenant(), staffA);
        assertThat(listA).contains(f.custAName()).contains(f.custAPhone())
                .as("per-shop STAFF's customer list carries no email").doesNotContain(f.custAEmail());
        String oneA = ok(get("/api/v1/customers/" + f.custA()), f.tenant(), staffA);
        assertThat(oneA).contains(f.custAName()).contains(f.custAPhone())
                .as("per-shop STAFF's customer read carries no email").doesNotContain(f.custAEmail());

        String listAll = ok(get("/api/v1/customers"), f.tenant(), staffAll);
        assertThat(listAll).contains(f.custAName()).contains(f.custBName()).contains(f.shoplessName())
                .contains(f.shoplessPhone())
                .as("tenant-wide STAFF's customer list carries no email, the shop-less customer's included")
                .doesNotContain(f.custAEmail()).doesNotContain(f.custBEmail()).doesNotContain(f.shoplessEmail());
        String oneAll = ok(get("/api/v1/customers/" + f.custA()), f.tenant(), staffAll);
        assertThat(oneAll).contains(f.custAPhone())
                .as("tenant-wide STAFF's customer read carries no email").doesNotContain(f.custAEmail());
    }

    // ------------------------------------------------------------------
    // 4. A mixed caller is decided per row's shop
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#856: SHOP_MANAGER on A + STAFF on B sees full contact on A's orders and customers and the minimised view on B's")
    void mixedRole_fullOnManagedShop_minimisedOnStaffShop() throws Exception {
        Fixture f = seed();
        UUID mixed = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), mixed, f.shopA(), "SHOP_MANAGER", "mixed-" + mixed + "@example.com");
        ShopGrants.grantOperator(jdbc, f.tenant(), mixed, f.shopB(), "STAFF", "mixed-" + mixed + "@example.com");

        String orders = ok(get("/api/v1/orders"), f.tenant(), mixed);
        assertThat(orders).as("the managed shop's order keeps its email").contains(f.deliveryEmail());
        assertThat(orders).as("the STAFF shop's order is listed").contains(f.shopBOrder().toString());
        assertThat(orders).as("the STAFF shop's order carries no email").doesNotContain(f.shopBOrderEmail());

        String customers = ok(get("/api/v1/customers"), f.tenant(), mixed);
        assertThat(customers).as("a customer of the managed shop keeps its email").contains(f.custAEmail());
        assertThat(customers).as("a customer only of the STAFF shop is listed").contains(f.custBName());
        assertThat(customers).as("a customer only of the STAFF shop carries no email").doesNotContain(f.custBEmail());
    }

    // ------------------------------------------------------------------
    // 5. CONTROL: managers and admins are unchanged
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#856 CONTROL: SHOP_MANAGER on the shop, tenant-wide SHOP_MANAGER and GROUP_ADMIN see email and the collection order's address")
    void managerAndAdmins_seeFullContact() throws Exception {
        Fixture f = seed();
        UUID manager = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), manager, f.shopA(), "SHOP_MANAGER", "m-" + manager + "@example.com");
        UUID managerAll = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), managerAll, null, "SHOP_MANAGER", "ma-" + managerAll + "@example.com");
        UUID admin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), admin, null, "GROUP_ADMIN", "ga-" + admin + "@example.com");

        for (UUID caller : List.of(manager, managerAll, admin)) {
            String detail = ok(get("/api/v1/orders/" + f.collectionOrder() + "/detail"), f.tenant(), caller);
            assertThat(detail).as("caller %s sees the collection order's email and address", caller)
                    .contains(f.collectionEmail()).contains(f.legacyAddress()).contains(f.legacyPostcode());
            String board = ok(get("/api/v1/orders/kitchen").param("shopId", f.shopA().toString()), f.tenant(), caller);
            assertThat(board).as("caller %s sees the emails on the board", caller)
                    .contains(f.deliveryEmail()).contains(f.collectionEmail());
            String customers = ok(get("/api/v1/customers"), f.tenant(), caller);
            assertThat(customers).as("caller %s sees the customer email", caller).contains(f.custAEmail());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Fixture seed() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "sfm-tenant-" + tenant);
        UUID shopA = seedShop(tenant, "sfm-a");
        UUID shopB = seedShop(tenant, "sfm-b");
        UUID productA = seedProduct(tenant, shopA);
        UUID productB = seedProduct(tenant, shopB);

        String custAName = "Ada Customer " + s;
        String custAEmail = "ada-" + s + "@cust.example.test";
        String custAPhone = "07700 9" + s.substring(0, 5);
        String custBName = "Bola Customer " + s;
        String custBEmail = "bola-" + s + "@cust.example.test";
        String custBPhone = "07700 8" + s.substring(0, 5);
        String shoplessName = "Shopless Customer " + s;
        String shoplessEmail = "shopless-" + s + "@cust.example.test";
        String shoplessPhone = "07700 7" + s.substring(0, 5);
        UUID custA = seedCustomer(tenant, custAName, custAEmail, custAPhone, shopA);
        UUID custB = seedCustomer(tenant, custBName, custBEmail, custBPhone, shopB);
        UUID custShopless = seedCustomer(tenant, shoplessName, shoplessEmail, shoplessPhone, null);

        String deliveryEmail = "delivery-" + s + "@order.example.test";
        String deliveryPhone = "07700 6" + s.substring(0, 5);
        String deliveryAddress = "12 Hand-off Lane " + s;
        String deliveryNotes = "Ring the bell twice " + s;
        String allergyNote = "No sesame please " + s;
        UUID deliveryOrder = seedOrder(tenant, shopA, custA, custAName, deliveryEmail, deliveryPhone,
                deliveryNotes, allergyNote, "DELIVERY", deliveryAddress, "Birmingham", "B1 1AA");
        seedItem(tenant, deliveryOrder, productA);

        String collectionName = "Collector " + s;
        String collectionEmail = "collect-" + s + "@order.example.test";
        String collectionPhone = "07700 5" + s.substring(0, 5);
        String legacyAddress = "9 Legacy Row " + s;
        String legacyPostcode = "LG" + s.substring(0, 4);
        UUID collectionOrder = seedOrder(tenant, shopA, null, collectionName, collectionEmail, collectionPhone,
                null, null, "COLLECTION", legacyAddress, "Legacyton", legacyPostcode);
        seedItem(tenant, collectionOrder, productA);

        String shopBOrderEmail = "shop-b-" + s + "@order.example.test";
        UUID shopBOrder = seedOrder(tenant, shopB, custB, custBName, shopBOrderEmail, custBPhone,
                null, null, "COLLECTION", null, null, null);
        seedItem(tenant, shopBOrder, productB);

        return new Fixture(tenant, shopA, shopB, custA, custB, custShopless,
                deliveryOrder, collectionOrder, shopBOrder,
                custAName, custAEmail, custAPhone, custBName, custBEmail, custBPhone,
                shoplessName, shoplessEmail, shoplessPhone,
                deliveryEmail, deliveryPhone, deliveryAddress, deliveryNotes, allergyNote,
                collectionName, collectionEmail, collectionPhone, legacyAddress, legacyPostcode,
                shopBOrderEmail);
    }

    private UUID seedShop(UUID tenant, String prefix) {
        UUID shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, true, 0)",
                shopId, tenant, "Shop " + prefix, prefix + "-" + shopId, "1 Test Street, London, E1 6AN");
        return shopId;
    }

    private UUID seedProduct(UUID tenant, UUID shopId) {
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, shop_id, sku, title, ingredients_text, allergen_mask, "
                        + "price_pennies, vat_rate, available, featured, display_order, created_at) "
                        + "VALUES (?, ?, ?, ?, 'Test Dish', 'Rice, water', 1, 1000, 'STANDARD', true, false, 0, NOW())",
                productId, tenant, shopId, "SKU-" + productId.toString().substring(0, 8));
        return productId;
    }

    private UUID seedCustomer(UUID tenant, String name, String email, String phone, UUID originShopId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, tenant_id, name, email, phone, allergen_restrictions, origin_shop_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0, ?, NOW(), NOW())",
                id, tenant, name, email, phone, originShopId);
        return id;
    }

    private UUID seedOrder(UUID tenant, UUID shopId, UUID customerId, String name, String email, String phone,
                           String notes, String allergyNote, String fulfilmentType,
                           String addressLine1, String addressCity, String addressPostcode) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, shop_id, customer_id, order_number, status, customer_name, "
                        + "customer_email, customer_phone, notes, allergy_note, fulfilment_type, address_line1, "
                        + "address_city, address_postcode, subtotal_pennies, vat_rate, vat_amount_pennies, "
                        + "total_amount_pennies, delivery_fee_pennies, item_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "1000, 'STANDARD', 200, 1200, 0, 1, NOW(), NOW())",
                orderId, tenant, shopId, customerId, "ORD-" + orderId.toString().substring(0, 8), name,
                email, phone, notes, allergyNote, fulfilmentType, addressLine1, addressCity, addressPostcode);
        return orderId;
    }

    private void seedItem(UUID tenant, UUID orderId, UUID productId) {
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, product_id, product_name, quantity, "
                        + "unit_price_pennies, total_price_pennies, allergen_mask) VALUES (?, ?, ?, ?, ?, 1, 1000, 1000, 1)",
                UUID.randomUUID(), tenant, orderId, productId, "Test Dish");
    }

    /** Perform as the vendor, assert a 2xx BEFORE the body is read, and return the body text. */
    private String ok(MockHttpServletRequestBuilder request, UUID tenant, UUID sub) throws Exception {
        MvcResult result = mockMvc.perform(request.with(vendorWithOrderScopes(tenant, sub))).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        assertThat(status).as("expected 2xx for %s %s, got %s %s", request, sub, status, body)
                .isBetween(200, 299);
        return body;
    }

    private static JsonNode byId(JsonNode content, UUID id) {
        for (JsonNode node : content) {
            if (id.toString().equals(node.path("id").asString())) {
                return node;
            }
        }
        throw new AssertionError("order " + id + " not in " + content);
    }

    private static RequestPostProcessor vendorWithOrderScopes(UUID tenant, UUID sub) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("SCOPE_customers:write"));
        authorities.add(new SimpleGrantedAuthority("SCOPE_orders:write"));
        authorities.add(new SimpleGrantedAuthority("SCOPE_orders:read"));
        return jwt().jwt(j -> j.subject(sub.toString())
                        .claim("azp", "core-api")
                        .claim("tenant_id", tenant.toString())
                        .claim("email", "user-" + sub + "@example.com"))
                .authorities(authorities);
    }
}
