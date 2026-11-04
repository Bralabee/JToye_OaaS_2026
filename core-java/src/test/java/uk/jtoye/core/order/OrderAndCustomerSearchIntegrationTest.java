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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Issue #822: the Orders list and the Customers list SEARCH over every page, in the query.
 *
 * <p>The dashboards used to filter the one page they had loaded, so an order on page three was not
 * found, and Customers had no search at all. Proven here over HTTP through the real filter chain
 * (JWT, tenant filter, access gate, service, query, DTO, JSON), so every assertion is about what the
 * response says, never about a Java getter:
 * <ul>
 *   <li>a match on a LATER page is found (page size 2, the target is the oldest of five);</li>
 *   <li>name, phone with the spaces ignored, and an order-number suffix all find it;</li>
 *   <li>a shop-scoped caller never finds another shop's order, and another tenant's row never
 *       appears (tenant + grant scope are in the query, not a post-filter);</li>
 *   <li>the customer EMAIL is not an oracle: STAFF (whose response hides the email, issue #856)
 *       cannot find an order by it, a SHOP_MANAGER can;</li>
 *   <li>{@code %} and {@code _} are data, not wildcards;</li>
 *   <li>a blank {@code q} is the ordinary list.</li>
 * </ul>
 *
 * <p>Every literal is suffixed per fixture, and every call asserts a 2xx before its body is read, so
 * an empty result can never be a 403 body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OrderAndCustomerSearchIntegrationTest {

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
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
    }

    @AfterEach
    void restoreStrictScoping() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
    }

    /** One tenant with two shops; shop A holds five orders, the OLDEST carrying the target literals. */
    private record Fixture(UUID tenant, UUID shopA, UUID shopB, UUID targetOrder, UUID shopBOrder,
                           String token, String targetName, String targetEmail, String targetPhoneSpaced,
                           String targetPhoneDigits, String targetNumber,
                           UUID customerA, UUID customerB, String customerAName, String customerAEmail,
                           String customerAPhoneDigits, String customerBName) {}

    private Fixture seed() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "search-tenant-" + tenant);
        UUID shopA = seedShop(tenant, "srch-a");
        UUID shopB = seedShop(tenant, "srch-b");

        // Phone literals are unique per fixture: 07 + 9 hex-free digits derived from the uuid digits.
        String digits = UUID.randomUUID().toString().replaceAll("[^0-9]", "") + "00000000000000000000";
        String phoneDigits = "07" + digits.substring(0, 9);
        String phoneSpaced = phoneDigits.substring(0, 5) + " " + phoneDigits.substring(5);

        String targetName = "Zephyrine Quill " + s;
        String targetEmail = "zq-" + s + "@order.example.test";
        UUID target = seedOrder(tenant, shopA, "Target", targetName, targetEmail, phoneSpaced, 400);
        String targetNumber = jdbc.queryForObject("SELECT order_number FROM orders WHERE id = ?", String.class, target);
        // Four newer shop-A orders with unrelated literals: with size=2 the target is on page 3.
        for (int i = 0; i < 4; i++) {
            seedOrder(tenant, shopA, "Filler" + i, "Filler Person " + i + " " + s, "filler" + i + "-" + s + "@order.example.test",
                    "0161 000 000" + i, 300 - i * 50);
        }
        UUID shopBOrder = seedOrder(tenant, shopB, "ShopB", "Shop B Buyer " + s,
                "shopb-" + s + "@order.example.test", "0121 111 1111", 10);

        String custAName = "Customer Alpha " + s;
        String custAEmail = "alpha-" + s + "@cust.example.test";
        String custAPhone = "07" + digits.substring(9, 18);
        UUID custA = seedCustomer(tenant, custAName, custAEmail, custAPhone.substring(0, 5) + " " + custAPhone.substring(5), shopA);
        String custBName = "Customer Beta " + s;
        UUID custB = seedCustomer(tenant, custBName, "beta-" + s + "@cust.example.test", "0121 222 2222", shopB);

        return new Fixture(tenant, shopA, shopB, target, shopBOrder, s, targetName, targetEmail, phoneSpaced,
                phoneDigits, targetNumber, custA, custB, custAName, custAEmail, custAPhone, custBName);
    }

    // ------------------------------------------------------------------
    // Orders
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#822: a Group admin's search finds an order on a LATER page, by name, by phone with spaces ignored, and by an order-number suffix")
    void orders_searchSpansEveryPage() throws Exception {
        Fixture f = seed();
        UUID admin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");

        // Control: shop A's plain list, page 0 of size 2, does NOT contain the target (the oldest of five).
        JsonNode plain = json(get("/api/v1/orders").param("shopId", f.shopA().toString()).param("size", "2")
                .param("page", "0").param("sort", "createdAt,desc"), f.tenant(), admin);
        assertThat(ids(plain)).doesNotContain(f.targetOrder());
        assertThat(plain.path("totalElements").asInt()).isEqualTo(5);
        // ...and the same search narrowed to the shop still finds it.
        JsonNode inShop = json(get("/api/v1/orders").param("shopId", f.shopA().toString())
                .param("q", f.targetName()).param("size", "2"), f.tenant(), admin);
        assertThat(ids(inShop)).containsExactly(f.targetOrder());

        for (String q : List.of(f.targetName().toUpperCase(), f.targetPhoneDigits(), f.targetPhoneSpaced(),
                f.targetNumber().substring(f.targetNumber().length() - 6), f.targetEmail())) {
            JsonNode found = json(get("/api/v1/orders").param("q", q).param("size", "2").param("page", "0")
                    .param("sort", "createdAt,desc"), f.tenant(), admin);
            assertThat(ids(found)).as("q=%s", q).containsExactly(f.targetOrder());
            assertThat(found.path("totalElements").asInt()).as("q=%s total", q).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("#822: a blank q is the ordinary list, and a q that matches nothing is an empty page, not an error")
    void orders_blankIsOrdinary_noMatchIsEmpty() throws Exception {
        Fixture f = seed();
        UUID admin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");

        JsonNode blank = json(get("/api/v1/orders").param("q", "   "), f.tenant(), admin);
        JsonNode ordinary = json(get("/api/v1/orders"), f.tenant(), admin);
        assertThat(blank.path("totalElements").asInt()).isEqualTo(ordinary.path("totalElements").asInt());
        assertThat(blank.path("totalElements").asInt()).isPositive();
        JsonNode none = json(get("/api/v1/orders").param("q", "no-such-" + f.token()), f.tenant(), admin);
        assertThat(none.path("totalElements").asInt()).isZero();
    }

    @Test
    @DisplayName("#822: % and _ are data - q=% matches no order that holds no percent sign")
    void orders_wildcardsAreLiteral() throws Exception {
        Fixture f = seed();
        UUID admin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");

        assertThat(json(get("/api/v1/orders").param("q", "%"), f.tenant(), admin).path("totalElements").asInt()).isZero();
        assertThat(json(get("/api/v1/orders").param("q", "_"), f.tenant(), admin).path("totalElements").asInt()).isZero();
        assertThat(json(get("/api/v1/orders").param("q", "\\"), f.tenant(), admin).path("totalElements").asInt()).isZero();
    }

    @Test
    @DisplayName("#822: a shop-scoped caller cannot find another shop's order, with or without an explicit shopId")
    void orders_searchIsScopedToTheGrantSet() throws Exception {
        Fixture f = seed();
        UUID staffB = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), staffB, f.shopB(), "STAFF", "staff-" + staffB + "@example.com");

        // The shop-A target is invisible to a shop-B caller by name, by phone and by order number.
        for (String q : List.of(f.targetName(), f.targetPhoneDigits(), f.targetNumber())) {
            assertThat(json(get("/api/v1/orders").param("q", q), f.tenant(), staffB).path("totalElements").asInt())
                    .as("q=%s", q).isZero();
        }
        // Control: its own shop's order IS found, so the zero above is scope, not a dead search.
        JsonNode own = json(get("/api/v1/orders").param("q", "Shop B Buyer " + f.token()), f.tenant(), staffB);
        assertThat(ids(own)).containsExactly(f.shopBOrder());

        // An explicit shopId the caller holds no grant on is refused exactly as the unsearched list refuses it.
        MvcResult refused = mockMvc.perform(get("/api/v1/orders").param("shopId", f.shopA().toString())
                .param("q", f.targetName()).with(vendor(f.tenant(), staffB))).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("#822: another tenant's order never appears, even for a Group admin searching its exact literals")
    void orders_searchNeverCrossesTenants() throws Exception {
        Fixture mine = seed();
        Fixture theirs = seed();
        UUID admin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, mine.tenant(), admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");

        assertThat(json(get("/api/v1/orders").param("q", theirs.targetName()), mine.tenant(), admin)
                .path("totalElements").asInt()).isZero();
        assertThat(json(get("/api/v1/orders").param("q", theirs.targetNumber()), mine.tenant(), admin)
                .path("totalElements").asInt()).isZero();
    }

    @Test
    @DisplayName("#822 / #856: the customer email is not an oracle - STAFF cannot find an order by an email its response hides; a SHOP_MANAGER can")
    void orders_emailIsSearchableOnlyWhereItIsShown() throws Exception {
        Fixture f = seed();
        UUID staff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), staff, f.shopA(), "STAFF", "staff-" + staff + "@example.com");
        UUID manager = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), manager, f.shopA(), "SHOP_MANAGER", "mgr-" + manager + "@example.com");

        JsonNode asStaff = json(get("/api/v1/orders").param("q", f.targetEmail()), f.tenant(), staff);
        assertThat(asStaff.path("totalElements").asInt()).isZero();
        // ...while the SAME staff caller finds the order by the name, which its response shows.
        JsonNode staffByName = json(get("/api/v1/orders").param("q", f.targetName()), f.tenant(), staff);
        assertThat(ids(staffByName)).containsExactly(f.targetOrder());
        assertThat(staffByName.path("content").get(0).path("customerEmail").isNull()).isTrue();

        JsonNode asManager = json(get("/api/v1/orders").param("q", f.targetEmail()), f.tenant(), manager);
        assertThat(ids(asManager)).containsExactly(f.targetOrder());
    }

    /**
     * Review round 1, finding 3: a phone is found however it was typed or stored. The term and the
     * column are both reduced to digits and a leading 44 (or 440) becomes 0, in SQL on the column side.
     */
    @Test
    @DisplayName("#822 review: +44, hyphen, bracket and space spellings of a phone all find it, on orders and customers, whichever spelling is stored")
    void phoneMatchesAcrossSpellings() throws Exception {
        Fixture f = seed();
        UUID admin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");
        // 9 distinctive digits per fixture so no other row can match by accident.
        String tail = String.format("%09d", Math.abs(f.token().hashCode()) % 1_000_000_000L);
        String local = "07" + tail;                 // 07xxxxxxxxx
        String intl = "+44 " + local.substring(1, 5) + " " + local.substring(5);  // +44 7xxx xxxxxx
        UUID stored07 = seedOrder(f.tenant(), f.shopA(), "P07", "Phone Zero " + f.token(), "p0-" + f.token() + "@x.test", local, 5);
        UUID storedIntl = seedOrder(f.tenant(), f.shopA(), "PINT", "Phone Intl " + f.token(), "p1-" + f.token() + "@x.test", intl, 6);
        UUID storedBrackets = seedOrder(f.tenant(), f.shopA(), "PBR", "Phone Brk " + f.token(), "p2-" + f.token() + "@x.test",
                "(" + local.substring(0, 5) + ") " + local.substring(5, 8) + "-" + local.substring(8), 7);
        seedOrder(f.tenant(), f.shopA(), "PDECOY", "Phone Decoy " + f.token(), "p3-" + f.token() + "@x.test", "07" + new StringBuilder(tail).reverse(), 8);

        List<String> spellings = List.of(local, "+44" + local.substring(1), "+44 (0)" + local.substring(1),
                "(" + local.substring(0, 5) + ") " + local.substring(5), local.substring(0, 5) + "-" + local.substring(5));
        for (String q : spellings) {
            assertThat(ids(json(get("/api/v1/orders").param("q", q), f.tenant(), admin)))
                    .as("q=%s", q).containsExactlyInAnyOrder(stored07, storedIntl, storedBrackets);
        }

        UUID custIntl = seedCustomer(f.tenant(), "Cust Intl " + f.token(), "ci-" + f.token() + "@x.test", intl, f.shopA());
        for (String q : List.of(local, "+44" + local.substring(1), "(" + local.substring(0, 5) + ") " + local.substring(5))) {
            assertThat(ids(json(get("/api/v1/customers").param("q", q), f.tenant(), admin)))
                    .as("customers q=%s", q).contains(custIntl);
        }
    }

    // ------------------------------------------------------------------
    // Customers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#822: the Customers list searches by name, by phone with spaces ignored and (Group admin) by email, across pages")
    void customers_searchByNamePhoneEmail() throws Exception {
        Fixture f = seed();
        UUID admin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");

        for (String q : List.of(f.customerAName().toLowerCase(), f.customerAPhoneDigits(), f.customerAEmail())) {
            JsonNode found = json(get("/api/v1/customers").param("q", q).param("size", "1"), f.tenant(), admin);
            assertThat(customerIds(found)).as("q=%s", q).containsExactly(f.customerA());
            assertThat(found.path("totalElements").asInt()).isEqualTo(1);
        }
        assertThat(json(get("/api/v1/customers").param("q", "%"), f.tenant(), admin).path("totalElements").asInt()).isZero();
        assertThat(json(get("/api/v1/customers").param("q", " "), f.tenant(), admin).path("totalElements").asInt())
                .as("blank q is the ordinary list")
                .isEqualTo(json(get("/api/v1/customers"), f.tenant(), admin).path("totalElements").asInt());
    }

    @Test
    @DisplayName("#822 / #856: a shop-scoped caller's customer search stays inside its grant set and never matches the hidden email")
    void customers_searchIsScopedAndEmailIsNotAnOracle() throws Exception {
        Fixture f = seed();
        UUID staffA = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant(), staffA, f.shopA(), "STAFF", "staff-" + staffA + "@example.com");

        // Shop B's customer is outside the grant set.
        assertThat(json(get("/api/v1/customers").param("q", f.customerBName()), f.tenant(), staffA)
                .path("totalElements").asInt()).isZero();
        // Its own customer is found by name...
        JsonNode own = json(get("/api/v1/customers").param("q", f.customerAName()), f.tenant(), staffA);
        assertThat(customerIds(own)).containsExactly(f.customerA());
        assertThat(own.path("content").get(0).path("email").isNull()).isTrue();
        // ...but not by the email its response hides.
        assertThat(json(get("/api/v1/customers").param("q", f.customerAEmail()), f.tenant(), staffA)
                .path("totalElements").asInt()).isZero();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private UUID seedShop(UUID tenant, String prefix) {
        UUID shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, true, 0)",
                shopId, tenant, "Shop " + prefix, prefix + "-" + shopId, "1 Test Street, London, E1 6AN");
        return shopId;
    }

    private UUID seedCustomer(UUID tenant, String name, String email, String phone, UUID originShopId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, tenant_id, name, email, phone, allergen_restrictions, origin_shop_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0, ?, NOW(), NOW())",
                id, tenant, name, email, phone, originShopId);
        return id;
    }

    private UUID seedOrder(UUID tenant, UUID shopId, String tag, String name, String email, String phone, int minutesAgo) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, "
                        + "customer_email, customer_phone, fulfilment_type, subtotal_pennies, vat_rate, vat_amount_pennies, "
                        + "total_amount_pennies, delivery_fee_pennies, item_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, 'COLLECTION', 1000, 'STANDARD', 200, 1200, 0, 1, "
                        + "NOW() - make_interval(mins => ?), NOW())",
                orderId, tenant, shopId, "ORD-" + tag + "-" + orderId.toString().substring(0, 8), name,
                email, phone, minutesAgo);
        return orderId;
    }

    private JsonNode json(MockHttpServletRequestBuilder request, UUID tenant, UUID sub) throws Exception {
        MvcResult result = mockMvc.perform(request.with(vendor(tenant, sub))).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        assertThat(status).as("expected 2xx for %s as %s, got %s %s", request, sub, status, body).isBetween(200, 299);
        return jsonMapper.readTree(body);
    }

    private static Set<UUID> ids(JsonNode page) {
        Set<UUID> out = new HashSet<>();
        for (JsonNode node : page.path("content")) {
            out.add(UUID.fromString(node.path("id").asString()));
        }
        return out;
    }

    private static Set<UUID> customerIds(JsonNode page) {
        return ids(page);
    }

    private static RequestPostProcessor vendor(UUID tenant, UUID sub) {
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
