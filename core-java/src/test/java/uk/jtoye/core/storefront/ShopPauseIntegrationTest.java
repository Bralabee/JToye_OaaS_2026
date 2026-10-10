package uk.jtoye.core.storefront;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.ShopGrants;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 37-20 (RWO-022 / UXT-022, decisions D-15 and D-25): a vendor can pause storefront orders in one
 * call, and the storefront obeys, on the server.
 *
 * <p>Before this plan there was no pause at all, and the only server gate on a guest order
 * ({@code PublicStorefrontService.validateShopIsOpen}) failed open on free-text hours it could not read.
 * Every arm goes through the real HTTP stack (MockMvc, the security filter chain, the controller, the
 * idempotency store) against a Testcontainers Postgres carrying the real Flyway schema, V80 included.
 *
 * <p>Each arm seeds its OWN random tenant and shop, so arms cannot interfere and the class can run in
 * any order. The seeded shop is what V80 makes of a pre-existing shop with no hours: hours_source
 * LEGACY_ALWAYS_OPEN (D-25), so the ONLY thing that can close it in the pause arms is the pause.
 *
 * <p>The instrument for a refusal is the problem TYPE, the stable code and the database (no order row, no
 * idempotency reservation), never the status alone: a 409 can come from several handlers.
 *
 * <p>RLS note: the Testcontainers bootstrap role is a SUPERUSER, so these arms prove the application
 * contract (the gate, the policy, the endpoint), not RLS enforcement; this plan adds no table and no policy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ShopPauseIntegrationTest {

    private static final String SHOP_CLOSED = "https://jtoye.uk/errors/shop-closed";
    private static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";
    private static final String VALIDATION = "https://jtoye.uk/errors/validation";
    private static final String GUEST_ENDPOINT = "storefront.orders.create";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
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
    @Autowired private java.time.Clock clock;

    /** The value the context booted with: restored after every test, never a literal (37-02). */
    private Object bootedStrictScoping;

    @BeforeEach
    void strictScoping() {
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
        // Strict: an ungranted user is NOT auto-provisioned, so the 403 arm measures the gate, not JIT.
        StrictScopingGuard.set(shopAccessService, true);
    }

    @AfterEach
    void restoreStrictScoping() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
    }

    // ------------------------------------------------------------------
    // Task 1: the pause refuses storefront orders, with a typed reason, and resume lets them through
    // ------------------------------------------------------------------

    @Test
    @DisplayName("UXT-022: STAFF pauses 20 minutes -> a guest order is refused 409 shop-closed PAUSED with reopensAt and writes nothing; DELETE pause -> the SAME key is accepted 201")
    void pause20_refusesGuestOrder_thenResumeAcceptsTheSameKey() throws Exception {
        Fixture f = Fixture.seed(jdbc, "LEGACY_ALWAYS_OPEN", null, null);
        UUID staff = grant(f, "STAFF");

        OffsetDateTime before = OffsetDateTime.now();
        MvcResult paused = mockMvc.perform(withJson(put(pauseUrl(f)).with(vendor(f.tenant, staff)),
                        Map.of("minutes", 20)))
                .andReturn();
        OffsetDateTime after = OffsetDateTime.now();
        assertThat(paused.getResponse().getStatus()).as(text(paused)).isEqualTo(200);
        Map<String, Object> state = pauseColumns(f.shopId);
        assertThat(state.get("orders_paused_indefinitely")).as("a timed pause is not indefinite").isEqualTo(false);
        OffsetDateTime until = ((java.sql.Timestamp) state.get("orders_paused_until")).toInstant()
                .atOffset(java.time.ZoneOffset.UTC);
        assertThat(until).as("paused until twenty minutes from the request")
                .isBetween(before.plusMinutes(20).minusSeconds(1), after.plusMinutes(20).plusSeconds(1));

        String key = "pause-" + UUID.randomUUID();
        String email = "paused-" + UUID.randomUUID() + "@example.com";
        MvcResult refused = guestOrder(f, key, email);
        JsonNode problem = assertProblem(refused, 409, SHOP_CLOSED, "SHOP_CLOSED");
        assertThat(problem.path("reason").asString()).as(text(refused)).isEqualTo("PAUSED");
        assertThat(problem.path("reopensAt").isMissingNode() || problem.path("reopensAt").isNull())
                .as("a timed pause names when orders reopen: %s", text(refused)).isFalse();
        assertThat(OffsetDateTime.parse(problem.path("reopensAt").asString()).toInstant())
                .as("reopensAt is the pause end").isEqualTo(until.toInstant());
        assertThat(problem.path("detail").asString())
                .as("human words, naming the shop (goods P2-CHA-14)")
                .contains(f.shopName).contains("isn't taking orders");
        assertThat(countOrdersByEmail(f, email)).as("the refused order wrote no row").isZero();
        assertThat(countReservations(f, key))
                .as("the refusal runs BEFORE the idempotency reservation, so none is left behind")
                .isZero();

        MvcResult resumed = mockMvc.perform(delete(pauseUrl(f)).with(vendor(f.tenant, staff))).andReturn();
        assertThat(resumed.getResponse().getStatus()).as(text(resumed)).isEqualTo(200);
        Map<String, Object> cleared = pauseColumns(f.shopId);
        assertThat(cleared.get("orders_paused_until")).as("resume clears the timed pause").isNull();
        assertThat(cleared.get("orders_paused_indefinitely")).isEqualTo(false);

        MvcResult accepted = guestOrder(f, key, email);
        assertThat(accepted.getResponse().getStatus()).as(text(accepted)).isEqualTo(201);
        assertThat(countOrdersByEmail(f, email)).as("the same key now places exactly one order").isEqualTo(1);
    }

    @Test
    @DisplayName("UXT-022: a shop whose pause columns are set refuses storefront orders (the storefront reads the pause, it does not only store it)")
    void pausedInTheDatabase_refusesGuestOrders() throws Exception {
        Fixture f = Fixture.seed(jdbc, "LEGACY_ALWAYS_OPEN", null, null);
        jdbc.update("UPDATE shops SET orders_paused_indefinitely = true WHERE id = ?", f.shopId);

        String email = "db-paused-" + UUID.randomUUID() + "@example.com";
        String key = "db-paused-" + UUID.randomUUID();
        MvcResult refused = guestOrder(f, key, email);
        JsonNode problem = assertProblem(refused, 409, SHOP_CLOSED, "SHOP_CLOSED");
        assertThat(problem.path("reason").asString()).isEqualTo("PAUSED");
        assertThat(countOrdersByEmail(f, email)).as("no order row").isZero();
        assertThat(countReservations(f, key)).as("no reservation").isZero();
        assertThat(guestOrder(f, null, email).getResponse().getStatus())
                .as("the keyless create is refused the same way").isEqualTo(409);

        // A timed pause that has already ENDED does not refuse anything (expiry needs no clean-up job).
        jdbc.update("UPDATE shops SET orders_paused_indefinitely = false, orders_paused_until = now() - interval '1 minute' "
                + "WHERE id = ?", f.shopId);
        assertThat(guestOrder(f, key, email).getResponse().getStatus())
                .as("an expired pause lets the same key through").isEqualTo(201);
    }

    @Test
    @DisplayName("UXT-022: 'until reopened' pauses indefinitely -> 409 PAUSED with no reopensAt; a repeat PUT and a repeat DELETE are absolute (same state)")
    void pauseUntilReopened_refusesWithNoReopeningTime_andIsAbsolute() throws Exception {
        Fixture f = Fixture.seed(jdbc, "LEGACY_ALWAYS_OPEN", null, null);
        UUID staff = grant(f, "STAFF");

        for (int i = 0; i < 2; i++) {
            MvcResult paused = mockMvc.perform(withJson(put(pauseUrl(f)).with(vendor(f.tenant, staff)),
                            Map.of("untilReopened", true)))
                    .andReturn();
            assertThat(paused.getResponse().getStatus()).as(text(paused)).isEqualTo(200);
            Map<String, Object> state = pauseColumns(f.shopId);
            assertThat(state.get("orders_paused_indefinitely")).as("PUT #%d", i + 1).isEqualTo(true);
            assertThat(state.get("orders_paused_until")).as("an indefinite pause has no end time").isNull();
        }

        String email = "indefinite-" + UUID.randomUUID() + "@example.com";
        MvcResult refused = guestOrder(f, "indef-" + UUID.randomUUID(), email);
        JsonNode problem = assertProblem(refused, 409, SHOP_CLOSED, "SHOP_CLOSED");
        assertThat(problem.path("reason").asString()).isEqualTo("PAUSED");
        assertThat(problem.path("reopensAt").isMissingNode() || problem.path("reopensAt").isNull())
                .as("no reopening time is set: %s", text(refused)).isTrue();
        assertThat(problem.path("detail").asString()).contains("No reopening time is set");
        assertThat(countOrdersByEmail(f, email)).isZero();

        for (int i = 0; i < 2; i++) {
            MvcResult resumed = mockMvc.perform(delete(pauseUrl(f)).with(vendor(f.tenant, staff))).andReturn();
            assertThat(resumed.getResponse().getStatus()).as("DELETE #%d %s", i + 1, text(resumed)).isEqualTo(200);
            assertThat(pauseColumns(f.shopId).get("orders_paused_indefinitely")).isEqualTo(false);
        }
        assertThat(guestOrder(f, "indef-after-" + UUID.randomUUID(), email).getResponse().getStatus())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("T-37-51: a user with no grant on the shop cannot pause it (403 shop-access-denied); a STAFF grant on ANOTHER shop is not enough; nothing changes")
    void ungrantedUser_cannotPause() throws Exception {
        Fixture f = Fixture.seed(jdbc, "LEGACY_ALWAYS_OPEN", null, null);
        UUID ungranted = UUID.randomUUID();

        MvcResult refused = mockMvc.perform(withJson(put(pauseUrl(f)).with(vendor(f.tenant, ungranted)),
                        Map.of("untilReopened", true)))
                .andReturn();
        assertProblem(refused, 403, SHOP_ACCESS_DENIED, null);

        UUID otherShop = Fixture.seedShop(jdbc, f.tenant, "LEGACY_ALWAYS_OPEN", null, null);
        UUID otherStaff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, otherStaff, otherShop, "STAFF", "other-" + otherStaff + "@example.com");
        MvcResult crossShop = mockMvc.perform(withJson(put(pauseUrl(f)).with(vendor(f.tenant, otherStaff)),
                        Map.of("minutes", 40)))
                .andReturn();
        assertProblem(crossShop, 403, SHOP_ACCESS_DENIED, null);

        MvcResult resume = mockMvc.perform(delete(pauseUrl(f)).with(vendor(f.tenant, ungranted))).andReturn();
        assertProblem(resume, 403, SHOP_ACCESS_DENIED, null);

        Map<String, Object> state = pauseColumns(f.shopId);
        assertThat(state.get("orders_paused_indefinitely")).as("the refused pauses changed nothing").isEqualTo(false);
        assertThat(state.get("orders_paused_until")).isNull();
        assertThat(guestOrder(f, null, "still-open-" + UUID.randomUUID() + "@example.com").getResponse().getStatus())
                .as("the shop still takes orders").isEqualTo(201);
    }

    @Test
    @DisplayName("D-15: only 20 or 40 minutes, or until reopened: 15 minutes, an empty body and an ambiguous body are typed 400s and change nothing")
    void onlyTwentyFortyOrUntilReopened() throws Exception {
        Fixture f = Fixture.seed(jdbc, "LEGACY_ALWAYS_OPEN", null, null);
        UUID staff = grant(f, "STAFF");

        List<Map<String, Object>> bad = List.of(
                Map.of("minutes", 15),
                Map.of(),
                Map.of("minutes", 0),
                Map.of("minutes", 20, "untilReopened", true),
                Map.of("untilReopened", false));
        for (Map<String, Object> body : bad) {
            MvcResult result = mockMvc.perform(withJson(put(pauseUrl(f)).with(vendor(f.tenant, staff)), body))
                    .andReturn();
            assertProblem(result, 400, VALIDATION, null);
        }

        MvcResult forty = mockMvc.perform(withJson(put(pauseUrl(f)).with(vendor(f.tenant, staff)),
                        Map.of("minutes", 40)))
                .andReturn();
        assertThat(forty.getResponse().getStatus()).as("CONTROL: 40 minutes is accepted: %s", text(forty))
                .isEqualTo(200);
        mockMvc.perform(delete(pauseUrl(f)).with(vendor(f.tenant, staff))).andReturn();
        Map<String, Object> state = pauseColumns(f.shopId);
        assertThat(state.get("orders_paused_until")).isNull();
        assertThat(state.get("orders_paused_indefinitely")).isEqualTo(false);
    }

    @Test
    @DisplayName("Recorded reason: a vendor POST /api/v1/orders (a phone order taken by staff) is NOT blocked by a pause -> 201")
    void vendorOrderDuringPause_isAccepted() throws Exception {
        Fixture f = Fixture.seed(jdbc, "LEGACY_ALWAYS_OPEN", null, null);
        UUID staff = grant(f, "STAFF");
        MvcResult paused = mockMvc.perform(withJson(put(pauseUrl(f)).with(vendor(f.tenant, staff)),
                        Map.of("untilReopened", true)))
                .andReturn();
        assertThat(paused.getResponse().getStatus()).as(text(paused)).isEqualTo(200);

        String email = "phone-order-" + UUID.randomUUID() + "@example.com";
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("shopId", f.shopId);
        request.put("customerEmail", email);
        request.put("items", List.of(Map.of("productId", f.productId, "quantity", 1)));
        MvcResult created = mockMvc.perform(withJson(post("/api/v1/orders")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                                        .claim("tenant_id", f.tenant.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                                        new SimpleGrantedAuthority("SCOPE_orders:write")))
                        .header("Idempotency-Key", "vendor-" + UUID.randomUUID()), request))
                .andReturn();
        assertThat(created.getResponse().getStatus()).as(text(created)).isEqualTo(201);
        assertThat(countOrdersByEmail(f, email)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Task 2: structured hours decide open and closed; D-25 legacy shops behave as before deploy
    // ------------------------------------------------------------------

    @Test
    @DisplayName("D-15: a STRUCTURED shop closed every day refuses a guest order 409 shop-closed NO_HOURS (was 201: no hours meant always open); CONTROL: 24-hour windows -> 201")
    void structuredClosedEveryDay_refusesNoHours() throws Exception {
        Fixture closed = Fixture.seed(jdbc, "STRUCTURED", null, scheduleJson(null, false));
        String email = "no-hours-" + UUID.randomUUID() + "@example.com";
        MvcResult refused = guestOrder(closed, "no-hours-" + UUID.randomUUID(), email);
        JsonNode problem = assertProblem(refused, 409, SHOP_CLOSED, "SHOP_CLOSED");
        assertThat(problem.path("reason").asString()).isEqualTo("NO_HOURS");
        assertThat(problem.path("detail").asString()).contains("No reopening time is set");
        assertThat(countOrdersByEmail(closed, email)).isZero();

        Fixture open = Fixture.seed(jdbc, "STRUCTURED", null, scheduleJson(null, true));
        assertThat(guestOrder(open, null, "all-day-" + UUID.randomUUID() + "@example.com").getResponse().getStatus())
                .as("CONTROL: the same path accepts an order for a shop open all day").isEqualTo(201);
    }

    @Test
    @DisplayName("D-25 (owner ruling 2026-10-10): a NEEDS_REVIEW shop's unreadable day stays OPEN until the vendor saves structured hours -> 201, as before deploy")
    void needsReviewUnreadableDay_staysOpen() throws Exception {
        String today = todayKey();
        Fixture f = Fixture.seed(jdbc, "NEEDS_REVIEW", legacyTextJson(today, "ask at the counter"),
                scheduleJson(today, true));
        MvcResult accepted = guestOrder(f, "review-open-" + UUID.randomUUID(), "review-" + UUID.randomUUID() + "@example.com");
        assertThat(accepted.getResponse().getStatus())
                .as("today's text could not be read; D-25 keeps the pre-deploy fail-open: %s", text(accepted))
                .isEqualTo(201);
    }

    @Test
    @DisplayName("D-25: a NEEDS_REVIEW shop's day written 'Closed' stays closed -> 409 shop-closed CLOSED_TODAY (was a 400 invalid-argument)")
    void needsReviewClosedDay_staysClosed() throws Exception {
        String today = todayKey();
        Fixture f = Fixture.seed(jdbc, "NEEDS_REVIEW", legacyTextJson(today, "Closed"), scheduleJson(today, true));
        String email = "review-closed-" + UUID.randomUUID() + "@example.com";
        MvcResult refused = guestOrder(f, "review-closed-" + UUID.randomUUID(), email);
        JsonNode problem = assertProblem(refused, 409, SHOP_CLOSED, "SHOP_CLOSED");
        assertThat(problem.path("reason").asString()).isEqualTo("CLOSED_TODAY");
        assertThat(problem.path("reopensAt").asString()).as("tomorrow's window opens at midnight").isNotBlank();
        assertThat(countOrdersByEmail(f, email)).isZero();
    }

    /** Today's day key in UK time, from the application's own clock. */
    private String todayKey() {
        java.time.DayOfWeek day = java.time.ZonedDateTime.now(clock)
                .withZoneSameInstant(java.time.ZoneId.of("Europe/London")).getDayOfWeek();
        return uk.jtoye.core.shop.OpeningSchedule.DAY_KEYS.get(day.ordinal());
    }

    /**
     * A V80-shaped schedule: every day a 24-hour window ({@code allDay}) or closed, except {@code exceptDay},
     * which is closed ({@code []}).
     */
    private String scheduleJson(String exceptDay, boolean allDay) {
        Map<String, Object> json = new LinkedHashMap<>();
        for (String key : uk.jtoye.core.shop.OpeningSchedule.DAY_KEYS) {
            boolean open = allDay && !key.equals(exceptDay);
            json.put(key, open ? List.of(Map.of("open", "00:00", "close", "00:00")) : List.of());
        }
        return jsonMapper.writeValueAsString(json);
    }

    /** The legacy free text V80 read: "00:00 - 24:00" every day except {@code day}, which holds {@code entry}. */
    private String legacyTextJson(String day, String entry) {
        Map<String, String> text = new LinkedHashMap<>();
        for (String key : uk.jtoye.core.shop.OpeningSchedule.DAY_KEYS) {
            text.put(key, key.equals(day) ? entry : "00:00 - 24:00");
        }
        return jsonMapper.writeValueAsString(text);
    }

    // ------------------------------------------------------------------
    // Fixture and helpers
    // ------------------------------------------------------------------

    /** One random tenant with one published shop and one allergen-free product. */
    record Fixture(UUID tenant, UUID shopId, String shopName, String slug, UUID productId) {

        static Fixture seed(JdbcTemplate jdbc, String hoursSource, String openingHoursJson, String scheduleJson) {
            UUID tenant = UUID.randomUUID();
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                    tenant, "37-20 pause " + tenant);
            UUID shopId = seedShop(jdbc, tenant, hoursSource, openingHoursJson, scheduleJson);
            Map<String, Object> shop = jdbc.queryForMap("SELECT name, slug FROM shops WHERE id = ?", shopId);
            UUID productId = UUID.randomUUID();
            jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, 100, 'STANDARD', 0)",
                    productId, tenant, "SKU-3720-" + productId, "Jollof Rice", "rice, tomato", shopId);
            return new Fixture(tenant, shopId, (String) shop.get("name"), (String) shop.get("slug"), productId);
        }

        static UUID seedShop(JdbcTemplate jdbc, UUID tenant, String hoursSource, String openingHoursJson,
                             String scheduleJson) {
            UUID shopId = UUID.randomUUID();
            jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, delivery_fee_pennies, "
                            + "minimum_order_pennies, version, hours_source, opening_hours, opening_schedule) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, ?, ?::jsonb, ?::jsonb)",
                    shopId, tenant, "Pause Kitchen " + shopId.toString().substring(0, 8), "pause-" + shopId,
                    hoursSource, openingHoursJson, scheduleJson);
            return shopId;
        }
    }

    private UUID grant(Fixture f, String role) {
        UUID user = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, f.tenant, user, f.shopId, role, "staff-" + user + "@example.com");
        return user;
    }

    private static String pauseUrl(Fixture f) {
        return "/api/v1/shops/" + f.shopId + "/pause";
    }

    private static RequestPostProcessor vendor(UUID tenant, UUID sub) {
        return jwt().jwt(j -> j.subject(sub.toString())
                .claim("tenant_id", tenant.toString())
                .claim("email", "user-" + sub + "@example.com"));
    }

    private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder request, Object body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body));
    }

    /** A valid storefront guest order for one allergen-free item; {@code key == null} is the keyless create. */
    private MvcResult guestOrder(Fixture f, String key, String email) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Pause Buyer");
        body.put("customerEmail", email);
        body.put("customerPhone", "+447700900372");
        body.put("fulfilmentType", "COLLECTION");
        body.put("items", List.of(Map.of("productId", f.productId, "quantity", 1)));
        body.put("acknowledgedAllergenMask", 0);
        MockHttpServletRequestBuilder request = post("/api/v1/public/shops/" + f.slug + "/orders");
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(withJson(request, body)).andReturn();
    }

    private Map<String, Object> pauseColumns(UUID shopId) {
        return jdbc.queryForMap("SELECT orders_paused_until, orders_paused_indefinitely FROM shops WHERE id = ?", shopId);
    }

    private long countOrdersByEmail(Fixture f, String email) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE tenant_id = ? AND customer_email = ?", Long.class, f.tenant, email));
    }

    private long countReservations(Fixture f, String key) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE tenant_id = ? AND endpoint = ? AND idempotency_key = ?",
                Long.class, f.tenant, GUEST_ENDPOINT, key));
    }

    private JsonNode assertProblem(MvcResult result, int status, String type, String code) throws Exception {
        String t = text(result);
        assertThat(result.getResponse().getStatus()).as(t).isEqualTo(status);
        JsonNode problem = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("type").asString()).as(t).isEqualTo(type);
        if (code != null) {
            assertThat(problem.path("code").asString()).as(t).isEqualTo(code);
        }
        return problem;
    }

    private static String text(MvcResult result) {
        try {
            return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
        } catch (Exception e) {
            return "<unreadable body: " + e + ">";
        }
    }
}
