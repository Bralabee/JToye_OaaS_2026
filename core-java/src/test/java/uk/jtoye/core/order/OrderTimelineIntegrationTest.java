package uk.jtoye.core.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Issue #857: an order's activity timeline - who moved it to each status, and when.
 *
 * <p>Driven through the real HTTP endpoints so the history is written by the real Envers listener, not
 * seeded: two different signed-in people take one order through confirm, start-preparation and mark-ready,
 * and the timeline must name each of them against the step they took, in order. Read back over HTTP, so
 * the assertions are about the bytes on the wire (and, in particular, that the raw subject is never in them).
 *
 * <p>Access is the order detail's: STAFF on the order's shop, and a foreign or unknown id is a 404.
 * The bootstrap role is a SUPERUSER, so these arms prove the application contract and the shop-access gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class OrderTimelineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired ShopAccessService shopAccessService;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000085700");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000085701");
    private static final UUID ADE = UUID.fromString("85700000-0000-4000-8000-0000000000a1");
    private static final UUID BOLA = UUID.fromString("85700000-0000-4000-8000-0000000000b2");

    private UUID shopId;
    private Object bootedStrictScoping;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
        for (UUID t : List.of(TENANT_ID, OTHER_TENANT_ID)) {
            jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                    t, "857 Timeline Tenant " + t);
        }
        shopId = seedShop();
        // Ade is named in the directory (display name); Bola only by email.
        jdbc.update("INSERT INTO user_directory (tenant_id, user_id, email, display_name, last_seen) "
                + "VALUES (?, ?, 'ade@kitchen.test', 'Ade Cook', now()) ON CONFLICT (tenant_id, user_id) DO NOTHING",
                TENANT_ID, ADE);
        jdbc.update("INSERT INTO user_directory (tenant_id, user_id, email, display_name, last_seen) "
                + "VALUES (?, ?, 'bola@kitchen.test', NULL, now()) ON CONFLICT (tenant_id, user_id) DO NOTHING",
                TENANT_ID, BOLA);
    }

    @AfterEach
    void tearDown() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
        TenantContext.clear();
    }

    @Test
    @DisplayName("#857: each step is attributed to the person who took it, in order, with when; the raw subject never appears")
    void timelineNamesWhoTookEachStep() throws Exception {
        UUID orderId = seedPendingOrder();

        transition(orderId, "confirm", admin(ADE));
        transition(orderId, "start-preparation", admin(BOLA));
        transition(orderId, "mark-ready", admin(BOLA));

        MvcResult res = mockMvc.perform(get("/api/v1/orders/" + orderId + "/timeline").with(admin(ADE))).andReturn();
        String body = res.getResponse().getContentAsString();
        assertThat(res.getResponse().getStatus()).as(body).isEqualTo(200);
        JsonNode events = jsonMapper.readTree(body);

        List<String> toStatus = new java.util.ArrayList<>();
        for (JsonNode e : events) {
            if ("STATUS".equals(e.path("kind").asString())) {
                toStatus.add(e.path("status").asString());
            }
        }
        assertThat(toStatus).as(body).containsExactly("CONFIRMED", "PREPARING", "READY");

        JsonNode confirm = firstStatus(events, "CONFIRMED");
        JsonNode prep = firstStatus(events, "PREPARING");
        JsonNode ready = firstStatus(events, "READY");
        assertThat(confirm.path("actorType").asString()).isEqualTo("STAFF");
        assertThat(confirm.path("actorName").asString()).as("display name wins: " + body).isEqualTo("Ade Cook");
        assertThat(prep.path("actorName").asString()).as("email is the fallback name").isEqualTo("bola@kitchen.test");
        assertThat(ready.path("actorName").asString()).isEqualTo("bola@kitchen.test");
        assertThat(prep.path("fromStatus").asString()).isEqualTo("CONFIRMED");
        // Chronological, and a real timestamp on each.
        assertThat(java.time.Instant.parse(confirm.path("at").asString()))
                .isBeforeOrEqualTo(java.time.Instant.parse(prep.path("at").asString()));
        assertThat(java.time.Instant.parse(prep.path("at").asString()))
                .isBeforeOrEqualTo(java.time.Instant.parse(ready.path("at").asString()));
        // Names, not identifiers: the JWT subject is not part of the response.
        assertThat(body).doesNotContain(ADE.toString()).doesNotContain(BOLA.toString());
    }

    @Test
    @DisplayName("#857: a cancel shows who cancelled and the reason code the vendor recorded (every projected column round-trips, not just the actor)")
    void cancelCarriesItsReason() throws Exception {
        UUID orderId = seedPendingOrder();
        MvcResult cancel = mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                .contentType("application/json").content("{\"reasonCode\":\"SUSPECTED_FAKE\"}").with(admin(ADE))).andReturn();
        assertThat(cancel.getResponse().getStatus()).as(cancel.getResponse().getContentAsString()).isEqualTo(200);

        MvcResult res = mockMvc.perform(get("/api/v1/orders/" + orderId + "/timeline").with(admin(ADE))).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String cancelBody = res.getResponse().getContentAsString();
        JsonNode cancelled = firstStatus(jsonMapper.readTree(cancelBody), "CANCELLED");
        assertThat(cancelled.path("detail").asString()).as(cancelBody).isEqualTo("SUSPECTED_FAKE");
        assertThat(cancelled.path("actorName").asString()).isEqualTo("Ade Cook");
        // The seeded order was written outside Envers, so the cancel is the first revision the history holds.
        assertThat(cancelled.path("fromStatus").isNull()).isTrue();
    }

    @Test
    @DisplayName("#857: a caller with no grant on the order's shop is refused, and a foreign or unknown order is a 404")
    void accessIsTheOrderDetailsAccess() throws Exception {
        UUID orderId = seedPendingOrder();
        transition(orderId, "confirm", admin(ADE));

        UUID stranger = UUID.randomUUID();
        MvcResult noGrant = mockMvc.perform(get("/api/v1/orders/" + orderId + "/timeline").with(shopUser(stranger))).andReturn();
        assertThat(noGrant.getResponse().getStatus()).as(noGrant.getResponse().getContentAsString()).isEqualTo(403);

        MvcResult unknown = mockMvc.perform(get("/api/v1/orders/" + UUID.randomUUID() + "/timeline").with(admin(ADE))).andReturn();
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);

        // Another tenant's admin cannot read this order's history either (RLS + the explicit tenant predicate).
        MvcResult foreign = mockMvc.perform(get("/api/v1/orders/" + orderId + "/timeline")
                .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenant_id", OTHER_TENANT_ID.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_admin")))).andReturn();
        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------

    private void transition(UUID orderId, String action, RequestPostProcessor auth) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/v1/orders/" + orderId + "/" + action).with(auth)).andReturn();
        assertThat(r.getResponse().getStatus())
                .as("%s: %s", action, r.getResponse().getContentAsString()).isEqualTo(200);
    }

    private static JsonNode firstStatus(JsonNode events, String status) {
        for (JsonNode e : events) {
            if ("STATUS".equals(e.path("kind").asString()) && status.equals(e.path("status").asString())) {
                return e;
            }
        }
        throw new AssertionError("no STATUS event to " + status + " in " + events);
    }

    private UUID seedPendingOrder() {
        UUID id = UUID.randomUUID();
        inTenant(() -> jdbc.update(
                "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, customer_email, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                        + "item_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', 'Timeline Customer', 'timeline@example.test', "
                        + "1000, 'STANDARD', 200, 1200, 0, 1, now(), now())",
                id, TENANT_ID, shopId, "ORD-857-" + id.toString().substring(0, 8)));
        return id;
    }

    private UUID seedShop() {
        return inTenant(() -> {
            List<UUID> existing = jdbc.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = 'shop-85-7'", UUID.class, TENANT_ID);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version) "
                            + "VALUES (?, ?, now(), '857 Shop', 'shop-85-7', true, 0, 0, 0)",
                    id, TENANT_ID);
            return id;
        });
    }

    private <T> T inTenant(Supplier<T> work) {
        TenantContext.set(TENANT_ID);
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** A realm admin of the tenant (implicit GROUP_ADMIN), identified by JWT subject. */
    private static RequestPostProcessor admin(UUID subject) {
        // The directory is filled from the token's own email / name claims (ShopAccessService), as in
        // production: Ade carries a display name, Bola only an email.
        return jwt().jwt(j -> {
                    j.subject(subject.toString()).claim("tenant_id", TENANT_ID.toString());
                    if (ADE.equals(subject)) {
                        j.claim("email", "ade@kitchen.test").claim("name", "Ade Cook");
                    } else if (BOLA.equals(subject)) {
                        j.claim("email", "bola@kitchen.test");
                    }
                })
                .authorities(new SimpleGrantedAuthority("ROLE_admin"),
                        new SimpleGrantedAuthority("SCOPE_orders:write"));
    }

    /** A signed-in user of the tenant with no shop grant. */
    private static RequestPostProcessor shopUser(UUID subject) {
        return jwt().jwt(j -> j.subject(subject.toString()).claim("tenant_id", TENANT_ID.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_orders:write"));
    }
}
