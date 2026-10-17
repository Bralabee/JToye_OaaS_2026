package uk.jtoye.core.storefront;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 37-38 gate deviation (owner decision 2026-10-12, option A): an anonymous order-tracking read sees
 * the order's LINES, so the recorded allergen set it returns is the V63 snapshot and not "not recorded".
 *
 * <p>The defect, found live by allergen-ack-race at the 37-C gate: {@code orders} has had the
 * {@code orders_guest_tracking} SELECT policy since V17 (order number AND email, both from
 * transaction-local GUCs), but {@code order_items} had only {@code order_items_select_policy}
 * ({@code tenant_id = current_tenant_id()}). The tracking request carries no tenant, so under FORCE RLS
 * it read the order row and ZERO lines, and {@code PublicStorefrontService.trackOrder} — which derives
 * {@code recordedAllergens} from {@code order.getItems()} — answered null for every guest order. The
 * cash confirmation route, /track and the card-order route all render that response.
 *
 * <p>{@link CustomerRecordedAllergenSetIntegrationTest} could not see it: it runs as the Testcontainers
 * bootstrap SUPERUSER, which bypasses every RLS policy. This class is that test's missing arm. Every
 * request and every SQL read below runs as the application's own datasource role AFTER it is downgraded
 * to NOSUPERUSER NOBYPASSRLS (both asserted), so FORCE RLS is genuinely enforced, and a positive control
 * proves each "zero rows" reading is about the policy and not a query that can see nothing.
 *
 * <p>V84 adds {@code order_items_guest_tracking}: FOR SELECT only, admitting a line of an order whose
 * number AND email match the tracking GUCs (the {@code orders_guest_tracking} predicate, re-checked on
 * the order through an EXISTS that is itself filtered by the orders policies). No write is widened.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class GuestTrackingOrderLinesRlsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;
    @Autowired JsonMapper jsonMapper;

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000373801");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000373802");

    /** A1: the order being tracked. Two lines, Gluten and Milk declared, Milk also flagged on line 1. */
    private static final String A1_NUMBER = "ORD-3738-A1";
    private static final String A1_EMAIL = "a1-tracker@example.com";
    /** A2: same tenant, same shop, a different customer. One line, Peanuts. */
    private static final String A2_NUMBER = "ORD-3738-A2";
    private static final String A2_EMAIL = "a2-other@example.com";
    /** B1: another tenant. One line, Sesame. */
    private static final String B1_NUMBER = "ORD-3738-B1";
    private static final String B1_EMAIL = "b1-tenant-b@example.com";

    private static final int GLUTEN = 1;        // bit 0
    private static final int PEANUTS = 1 << 4;  // bit 4
    private static final int MILK = 1 << 6;     // bit 6
    private static final int SESAME = 1 << 10;  // bit 10

    private static boolean seeded;
    private static UUID orderA1;
    private static UUID orderA2;
    private static UUID orderB1;
    private static UUID breadA;

    @BeforeEach
    void seedOnceThenDowngrade() {
        if (!seeded) {
            // Seeding runs as the bootstrap SUPERUSER (no tenant pin needed); the downgrade follows.
            insertTenant(TENANT_A, "37-38 Tracking Tenant A");
            insertTenant(TENANT_B, "37-38 Tracking Tenant B");
            UUID shopA = insertShop(TENANT_A, "tracking-lines-shop-a");
            UUID shopB = insertShop(TENANT_B, "tracking-lines-shop-b");
            UUID bread = insertProduct(TENANT_A, shopA, "Sourdough", GLUTEN);
            breadA = bread;
            UUID cheese = insertProduct(TENANT_A, shopA, "Cheddar", MILK);
            UUID satay = insertProduct(TENANT_A, shopA, "Satay", PEANUTS);
            UUID tahini = insertProduct(TENANT_B, shopB, "Tahini", SESAME);

            orderA1 = insertOrder(TENANT_A, shopA, A1_NUMBER, A1_EMAIL, GLUTEN | MILK);
            insertLine(TENANT_A, orderA1, bread, "Sourdough", GLUTEN, MILK);
            insertLine(TENANT_A, orderA1, cheese, "Cheddar", MILK, 0);
            orderA2 = insertOrder(TENANT_A, shopA, A2_NUMBER, A2_EMAIL, PEANUTS);
            insertLine(TENANT_A, orderA2, satay, "Satay", PEANUTS, 0);
            orderB1 = insertOrder(TENANT_B, shopB, B1_NUMBER, B1_EMAIL, SESAME);
            insertLine(TENANT_B, orderB1, tahini, "Tahini", SESAME, 0);

            jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER NOBYPASSRLS");
            seeded = true;
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class))
                .as("PRECONDITION: every arm below is meaningless if the datasource role still bypasses RLS")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // Positive control: the downgraded role CAN see lines, through the tenant policy.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("control: pinned to tenant A, the downgraded role reads A's three lines and none of B's")
    void control_tenantPinnedRoleSeesItsOwnLines() throws Exception {
        assertThat(visibleLines(TENANT_A, null, null, orderA1)).as("A1 lines, tenant A pinned").isEqualTo(2);
        assertThat(visibleLines(TENANT_A, null, null, null)).as("all lines visible to tenant A").isEqualTo(3);
        assertThat(visibleLines(TENANT_B, null, null, null)).as("all lines visible to tenant B").isEqualTo(1);
        assertThat(visibleLines(null, null, null, null)).as("no tenant, no tracking GUC").isZero();
    }

    // ------------------------------------------------------------------
    // (a) The anonymous tracking read returns the lines, and the response carries the recorded set.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("(a) anonymous tracking with the right number and email returns recordedAllergens = the V63 snapshot")
    void anonymousTracking_returnsTheRecordedSnapshot() throws Exception {
        MvcResult tracked = mockMvc.perform(get("/api/v1/public/orders/" + A1_NUMBER).param("email", A1_EMAIL))
                .andReturn();
        String text = "HTTP " + tracked.getResponse().getStatus() + " " + tracked.getResponse().getContentAsString();
        assertThat(tracked.getResponse().getStatus()).as(text).isEqualTo(200);
        JsonNode status = jsonMapper.readTree(tracked.getResponse().getContentAsString());

        assertThat(texts(status.get("acknowledgedAllergens"), text))
                .as("the order's own column, readable before the fix too").containsExactly("Gluten", "Milk");
        assertThat(status.get("recordedAllergens").isNull())
                .as("recordedAllergens must NOT be null ('not recorded') for an order whose lines carry a snapshot: " + text)
                .isFalse();
        assertThat(texts(status.get("recordedAllergens"), text))
                .as("the union of the two lines' snapshots").containsExactly("Gluten", "Milk");
        JsonNode flags = status.get("recordedAllergenFlags");
        assertThat(flags.isArray()).as(text).isTrue();
        assertThat(flags.size()).as("Sourdough's Milk flag: " + text).isEqualTo(1);
        assertThat(flags.get(0).path("productName").asString()).isEqualTo("Sourdough");
        assertThat(flags.get(0).path("allergenName").asString()).isEqualTo("Milk");
    }

    @Test
    @DisplayName("(a) SQL: with only the tracking GUCs set, exactly A1's two lines are visible")
    void trackingGucs_admitExactlyTheTrackedOrdersLines() throws Exception {
        assertThat(visibleLines(null, A1_NUMBER, A1_EMAIL, orderA1)).as("A1 lines via tracking").isEqualTo(2);
        assertThat(visibleLines(null, A1_NUMBER, A1_EMAIL, null))
                .as("nothing but A1's lines: not A2's (same tenant), not B1's").isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // (b) A wrong email, another order's pair, or an empty GUC admits nothing.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("(b) wrong email → 404 over HTTP, and zero lines for every mismatched pair")
    void mismatchedPairs_admitNoLines() throws Exception {
        MvcResult wrong = mockMvc.perform(get("/api/v1/public/orders/" + A1_NUMBER).param("email", A2_EMAIL))
                .andReturn();
        assertThat(wrong.getResponse().getStatus()).as(wrong.getResponse().getContentAsString()).isEqualTo(404);

        assertThat(visibleLines(null, A1_NUMBER, A2_EMAIL, null)).as("A1 number, A2 email").isZero();
        assertThat(visibleLines(null, A2_NUMBER, A1_EMAIL, null)).as("A2 number, A1 email").isZero();
        assertThat(visibleLines(null, A1_NUMBER, "", null)).as("empty email GUC").isZero();
        assertThat(visibleLines(null, "", A1_EMAIL, null)).as("empty number GUC").isZero();
        assertThat(visibleLines(null, A1_NUMBER, A1_EMAIL.toUpperCase(java.util.Locale.ROOT), null))
                .as("the email comparison is exact, as on orders_guest_tracking").isZero();
        // The pair is right for A1 but the line asked for belongs to A2: still nothing.
        assertThat(visibleLines(null, A1_NUMBER, A1_EMAIL, orderA2)).as("A2's line under A1's pair").isZero();
    }

    // ------------------------------------------------------------------
    // (c) Cross-tenant: tenant B pinned sees none of A's lines unless it satisfies A1's tracking pair.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("(c) tenant B pinned reads none of A's lines; with A1's tracking pair it reads exactly A1's, never A2's")
    void crossTenant_onlyTheTrackingPredicateCrossesTheWall() throws Exception {
        assertThat(visibleLines(TENANT_B, null, null, orderA1)).as("A1 lines, tenant B pinned").isZero();
        assertThat(visibleLines(TENANT_B, null, null, orderA2)).as("A2 lines, tenant B pinned").isZero();
        assertThat(visibleLines(TENANT_B, A1_NUMBER, A1_EMAIL, orderA1))
                .as("A1 lines, tenant B pinned AND A1's pair (the predicate a tracking request satisfies)").isEqualTo(2);
        assertThat(visibleLines(TENANT_B, A1_NUMBER, A1_EMAIL, orderA2)).as("A2 lines under A1's pair").isZero();
        assertThat(visibleLines(TENANT_B, A1_NUMBER, A1_EMAIL, null)).as("B1's one line plus A1's two").isEqualTo(3);
        assertThat(visibleLines(TENANT_A, B1_NUMBER, "wrong@example.com", orderB1)).as("B1 lines, A pinned, wrong email")
                .isZero();
    }

    // ------------------------------------------------------------------
    // (d) No write is widened: the tracking pair admits a READ, never an UPDATE, DELETE or INSERT.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("(d) with the tracking pair set, UPDATE and DELETE touch 0 lines and INSERT is refused 42501")
    void trackingPair_widensNoWrite() throws Exception {
        assertThat(write(null, A1_NUMBER, A1_EMAIL,
                "UPDATE order_items SET quantity = quantity + 1 WHERE order_id = ?", orderA1))
                .as("UPDATE under the tracking pair").isZero();
        assertThat(write(null, A1_NUMBER, A1_EMAIL, "DELETE FROM order_items WHERE order_id = ?", orderA1))
                .as("DELETE under the tracking pair").isZero();
        assertThat(write(TENANT_B, A1_NUMBER, A1_EMAIL,
                "UPDATE order_items SET quantity = quantity + 1 WHERE order_id = ?", orderA1))
                .as("UPDATE, tenant B pinned plus A1's pair").isZero();
        // VALUES, not INSERT ... SELECT: the forged row must not depend on what the pair can read.
        assertThatThrownBy(() -> write(null, A1_NUMBER, A1_EMAIL,
                "INSERT INTO order_items (tenant_id, order_id, product_id, quantity, unit_price_pennies, "
                        + "total_price_pennies, product_name) VALUES (?, ?, ?, 1, 1, 1, 'forged')",
                TENANT_A, orderA1, breadA))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).as(e.getMessage()).isEqualTo("42501"));

        // Control for the write arms: tenant A pinned CAN update its own line (the arm can succeed).
        assertThat(write(TENANT_A, null, null,
                "UPDATE order_items SET quantity = quantity WHERE order_id = ?", orderA1))
                .as("control: the owning tenant's UPDATE matches its two lines").isEqualTo(2);
        // And the lines are untouched by the refused arms (rolled back or never matched).
        assertThat(visibleLines(TENANT_A, null, null, orderA1)).isEqualTo(2);
    }

    @Test
    @DisplayName("catalog: order_items_guest_tracking exists, is PERMISSIVE and FOR SELECT only")
    void policyIsSelectOnly() {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT cmd || ':' || permissive FROM pg_policies "
                        + "WHERE tablename = 'order_items' AND policyname = 'order_items_guest_tracking'",
                String.class);
        assertThat(rows).containsExactly("SELECT:PERMISSIVE");
    }

    // ---- SQL as the downgraded role, inside one rolled-back transaction ----

    /** Lines visible to the role with the given GUCs (null = leave unset); orderId null = every line. */
    private int visibleLines(UUID tenant, String number, String email, UUID orderId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                pin(c, tenant, number, email);
                String sql = "SELECT count(*) FROM order_items" + (orderId == null ? "" : " WHERE order_id = ?");
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    if (orderId != null) {
                        ps.setObject(1, orderId);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getInt(1);
                    }
                }
            } finally {
                c.rollback();
            }
        }
    }

    private int write(UUID tenant, String number, String email, String sql, Object... params) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                pin(c, tenant, number, email);
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = 0; i < params.length; i++) {
                        ps.setObject(i + 1, params[i]);
                    }
                    return ps.executeUpdate();
                }
            } finally {
                c.rollback();
            }
        }
    }

    private static void pin(Connection c, UUID tenant, String number, String email) throws SQLException {
        set(c, "app.current_tenant_id", tenant == null ? null : tenant.toString());
        set(c, "app.tracking_order_number", number);
        set(c, "app.tracking_email", email);
    }

    private static void set(Connection c, String guc, String value) throws SQLException {
        if (value == null) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config(?, ?, true)")) {
            ps.setString(1, guc);
            ps.setString(2, value);
            ps.execute();
        }
    }

    private static List<String> texts(JsonNode array, String text) {
        assertThat(array).as("field present: " + text).isNotNull();
        assertThat(array.isArray()).as("an array: " + text).isTrue();
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    // ---- seeding (bootstrap superuser, before the downgrade) ----

    private void insertTenant(UUID id, String name) {
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                id, name);
    }

    private UUID insertShop(UUID tenant, String slug) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                id, tenant, "37-38 " + slug, slug);
        return id;
    }

    private UUID insertProduct(UUID tenant, UUID shop, String title, int mask) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                        + "allergen_mask, price_pennies, display_order, available, featured, shop_id, "
                        + "quantity_in_stock, vat_rate, version) "
                        + "VALUES (?, ?, now(), ?, ?, '', ?, 500, 0, true, false, ?, 100, 'STANDARD', 0)",
                id, tenant, "SKU-3738-" + id, title, mask, shop);
        return id;
    }

    private UUID insertOrder(UUID tenant, UUID shop, String number, String email, int ackMask) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update("INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_name, "
                        + "customer_email, subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, "
                        + "delivery_fee_pennies, item_count, allergen_ack_mask, allergen_ack_at, placed_via, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', 'Tracking Buyer', ?, 1000, 'STANDARD', 167, 1000, 0, 1, ?, ?, "
                        + "'STOREFRONT', ?, ?)",
                id, tenant, shop, number, email, ackMask, now, now, now);
        return id;
    }

    private void insertLine(UUID tenant, UUID order, UUID product, String name, int mask, int flagMask) {
        jdbcTemplate.update("INSERT INTO order_items (tenant_id, order_id, product_id, quantity, unit_price_pennies, "
                        + "total_price_pennies, product_name, allergen_mask, allergen_flag_mask) "
                        + "VALUES (?, ?, ?, 1, 500, 500, ?, ?, ?)",
                tenant, order, product, name, mask, flagMask);
    }
}
