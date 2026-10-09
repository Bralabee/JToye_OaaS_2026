package uk.jtoye.core.shop;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 37-19 (D-15 data half, D-25; RWO-022 / UXT-022): V80 gives every EXISTING shop a structured schedule
 * and a source flag, on a database that already holds shops, under the RLS-bound migration role.
 *
 * <p><strong>The trap this pins.</strong> {@code shops} is ENABLE + FORCE ROW LEVEL SECURITY. Flyway runs as
 * the RLS-bound application role, so with no tenant GUC set {@code current_tenant_id()} is NULL and the
 * write policy hides every row: a bare {@code UPDATE shops SET hours_source = ...} matches ZERO rows and
 * reports success. {@code hours_source} is nullable (a shop the application writes before 37-20 has no
 * classification yet), so nothing would then fail: every existing shop would be left unclassified, and
 * 37-20's policy would read "no source, no schedule" as CLOSED, closing every empty-hours storefront that
 * D-25 says must stay open. V80 therefore walks the tenants registry and pins the GUC per tenant (the
 * V44/V57/V78/V79 pattern).
 *
 * <p><strong>Why a fresh Testcontainers database cannot catch it.</strong> A fresh database runs V79 and
 * V80 back to back on an empty {@code shops}, and the Testcontainers bootstrap role is a SUPERUSER, which
 * bypasses FORCE RLS. So this class migrates to V79 as the superuser, seeds shops for TWO tenants (tenant
 * A's published, so the cross-tenant public-read policy is also in play; tenant B's not), provisions
 * {@code rls_migrator} (NOSUPERUSER NOBYPASSRLS, mirroring {@code jtoye_app}), hands it ownership of the two
 * tables V80 alters, and applies V80 as that role. Two tenants, because a loop that stopped after the first
 * tenant would pass a one-tenant test.
 *
 * <p>The migration runs ONCE per class (lazily, from inside the first test, so a missing V80 is an
 * assertion failure in a test rather than a fixture error), and every test reads its own seeded rows.
 * Plain JUnit + the Flyway API, mirroring {@code LedgerEntryKindMigrationIntegrationTest}: the scenario is
 * Flyway's behaviour under an RLS-bound role, not the Spring context.
 */
@Testcontainers
@Tag("testcontainers")
class V80OpeningScheduleBackfillIntegrationTest {

    private static final String RLS_MIGRATOR = "rls_migrator";
    private static final String RLS_MIGRATOR_PW = "rls_migrator_pw";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_v80")
            .withUsername("test")
            .withPassword("test");

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final List<UUID> TENANTS = List.of(TENANT_A, TENANT_B);

    /** Per tenant: the shop seeded with SQL NULL opening_hours, and the one seeded with {}. */
    private static final UUID[] NULL_HOURS_SHOP = new UUID[2];
    private static final UUID[] EMPTY_HOURS_SHOP = new UUID[2];

    /** Per shared fixture name: the shop seeded with it in tenant A [0] and tenant B [1]. */
    private static final Map<String, UUID[]> FIXTURE_SHOPS = new HashMap<>();
    /** Per SQL-only fixture name: the shop seeded with its raw jsonb in tenant A [0] and tenant B [1]. */
    private static final Map<String, UUID[]> RAW_FIXTURE_SHOPS = new HashMap<>();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static MigrateResult v80Result;

    @BeforeAll
    static void migrateToV79AndSeed() throws Exception {
        Flyway toV79 = baseConfig(postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("79"))
                .load();
        toV79.migrate();
        assertThat(appliedVersions(toV79)).as("V79 applied, V80 not yet").contains("79").doesNotContain("80");

        try (Connection su = connect(postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement()) {
            s.execute("DROP ROLE IF EXISTS " + RLS_MIGRATOR);
            s.execute("CREATE ROLE " + RLS_MIGRATOR
                    + " NOSUPERUSER NOBYPASSRLS LOGIN PASSWORD '" + RLS_MIGRATOR_PW + "'");
            s.execute("GRANT ALL ON SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO " + RLS_MIGRATOR);
            // Ownership lets the role run V80's ALTERs and COMMENTs while it stays SUBJECT to FORCE RLS.
            s.execute("ALTER TABLE shops OWNER TO " + RLS_MIGRATOR);
            s.execute("ALTER TABLE shops_aud OWNER TO " + RLS_MIGRATOR);

            s.execute("INSERT INTO tenants (id, name) VALUES "
                    + "('" + TENANT_A + "', 'V80 Tenant A'), ('" + TENANT_B + "', 'V80 Tenant B')");

            assertThat(columnCount(su, "shops", "hours_source")).as("pre-V80: hours_source must not exist yet")
                    .isZero();

            for (int i = 0; i < TENANTS.size(); i++) {
                UUID tenant = TENANTS.get(i);
                boolean published = tenant.equals(TENANT_A);
                NULL_HOURS_SHOP[i] = seedShop(su, tenant, null, published);
                EMPTY_HOURS_SHOP[i] = seedShop(su, tenant, "{}", published);
                for (OpeningScheduleFixtures.Fixture fixture : OpeningScheduleFixtures.ALL) {
                    String json = fixture.hours() == null ? null : JSON.writeValueAsString(fixture.hours());
                    FIXTURE_SHOPS.computeIfAbsent(fixture.name(), k -> new UUID[2])[i] =
                            seedShop(su, tenant, json, published);
                }
                for (OpeningScheduleFixtures.RawFixture fixture : OpeningScheduleFixtures.SQL_ONLY) {
                    RAW_FIXTURE_SHOPS.computeIfAbsent(fixture.name(), k -> new UUID[2])[i] =
                            seedShop(su, tenant, fixture.rawJson(), published);
                }
            }
        }
    }

    /** Applies V80 as the RLS-bound migrator exactly once, asserting it ran. */
    private static synchronized void applyV80() {
        if (v80Result != null) {
            return;
        }
        // Resolved with no target, so a missing V80 reads as this assertion rather than a target error.
        assertThat(Arrays.stream(baseConfig(RLS_MIGRATOR, RLS_MIGRATOR_PW).load().info().all())
                .anyMatch(m -> m.getVersion() != null && "80".equals(m.getVersion().getVersion())))
                .as("V80__shop_opening_schedule_pause.sql must be on the classpath")
                .isTrue();
        MigrateResult result = baseConfig(RLS_MIGRATOR, RLS_MIGRATOR_PW)
                .target(MigrationVersion.fromVersion("80"))
                .load()
                .migrate();
        assertThat(result.migrationsExecuted).as("exactly V80 applied on top of V79").isEqualTo(1);
        assertThat(result.migrations.get(0).version).isEqualTo("80");
        v80Result = result;
    }

    @Test
    @DisplayName("D-25: a shop with NULL or {} opening hours becomes LEGACY_ALWAYS_OPEN with no schedule, in both tenants")
    void emptyHoursShopsBecomeLegacyAlwaysOpenInEveryTenant() throws Exception {
        applyV80();
        try (Connection su = connect(postgres.getUsername(), postgres.getPassword())) {
            for (int i = 0; i < TENANTS.size(); i++) {
                String tenant = "tenant " + (i == 0 ? "A (published)" : "B (unpublished)");
                for (UUID shop : new UUID[]{NULL_HOURS_SHOP[i], EMPTY_HOURS_SHOP[i]}) {
                    String label = tenant + ", shop " + (shop.equals(NULL_HOURS_SHOP[i]) ? "NULL hours" : "{} hours");
                    assertThat(string(su, "SELECT hours_source FROM shops WHERE id = '" + shop + "'"))
                            .as(label + ": hours_source").isEqualTo("LEGACY_ALWAYS_OPEN");
                    assertThat(string(su, "SELECT opening_schedule::text FROM shops WHERE id = '" + shop + "'"))
                            .as(label + ": a legacy shop has no schedule (the migration states none)").isNull();
                    assertThat(string(su, "SELECT orders_paused_indefinitely::text FROM shops WHERE id = '" + shop + "'"))
                            .as(label + ": not paused").isEqualTo("false");
                    assertThat(string(su, "SELECT orders_paused_until::text FROM shops WHERE id = '" + shop + "'"))
                            .as(label + ": no pause end").isNull();
                }
            }
            // The original text column is kept unchanged for one release (dual-read).
            assertThat(string(su, "SELECT opening_hours::text FROM shops WHERE id = '" + EMPTY_HOURS_SHOP[1] + "'"))
                    .as("opening_hours kept as it was").isEqualTo("{}");
            assertThat(string(su, "SELECT opening_hours::text FROM shops WHERE id = '" + NULL_HOURS_SHOP[1] + "'"))
                    .as("opening_hours kept as it was").isNull();
        }
    }

    @Test
    @DisplayName("V80 adds the four columns to shops AND shops_aud, and the CHECK lists every hours_source value")
    void columnsMirrorsAndCheckConstraint() throws Exception {
        applyV80();
        try (Connection su = connect(postgres.getUsername(), postgres.getPassword())) {
            for (String column : new String[]{"opening_schedule", "hours_source", "orders_paused_until",
                    "orders_paused_indefinitely"}) {
                assertThat(columnCount(su, "shops", column)).as("shops." + column).isEqualTo(1);
                assertThat(columnCount(su, "shops_aud", column)).as("shops_aud." + column
                        + " (Shop is @Audited: a missing mirror throws on the next audited write)").isEqualTo(1);
            }
            assertThat(string(su, "SELECT data_type FROM information_schema.columns WHERE table_schema = 'public' "
                    + "AND table_name = 'shops' AND column_name = 'opening_schedule'")).isEqualTo("jsonb");
            assertThat(string(su, "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public' "
                    + "AND table_name = 'shops' AND column_name = 'orders_paused_indefinitely'")).isEqualTo("NO");

            // Every value the enum will ever hold is admitted now (the V43 lesson: no later constraint rewrite).
            List<UUID> admitted = new ArrayList<>();
            for (String value : new String[]{"STRUCTURED", "MIGRATED_FROM_TEXT", "NEEDS_REVIEW", "LEGACY_ALWAYS_OPEN"}) {
                admitted.add(seedShopWithSource(su, TENANT_A, value));
            }
            assertThat(admitted).hasSize(4);
            assertThatThrownBy(() -> seedShopWithSource(su, TENANT_A, "ALWAYS_OPEN"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_shops_hours_source");
            // A legacy always-open shop carries no schedule: the migration never states one for it.
            assertThatThrownBy(() -> {
                try (Statement s = su.createStatement()) {
                    s.executeUpdate("UPDATE shops SET opening_schedule = '{\"mon\": []}'::jsonb "
                            + "WHERE id = '" + admitted.get(3) + "'");
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("ck_shops_legacy_always_open_no_schedule");
        }
    }

    static Stream<OpeningScheduleFixtures.Fixture> fixtures() {
        return OpeningScheduleFixtures.ALL.stream();
    }

    static Stream<OpeningScheduleFixtures.RawFixture> sqlOnlyFixtures() {
        return OpeningScheduleFixtures.SQL_ONLY.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("V80 classifies each shared fixture as expected AND exactly as OpeningSchedule.fromLegacy does, in both tenants")
    void migrationMatchesTheJavaParser(OpeningScheduleFixtures.Fixture fixture) throws Exception {
        applyV80();
        OpeningSchedule.LegacyClassification java = OpeningSchedule.fromLegacy(fixture.hours());
        JsonNode javaSchedule = java.schedule() == null ? null
                : JSON.readTree(JSON.writeValueAsString(java.schedule().toJson()));
        JsonNode expected = fixture.expectedScheduleJson() == null ? null : JSON.readTree(fixture.expectedScheduleJson());
        try (Connection su = connect(postgres.getUsername(), postgres.getPassword())) {
            for (int i = 0; i < TENANTS.size(); i++) {
                UUID shop = FIXTURE_SHOPS.get(fixture.name())[i];
                String label = "tenant " + (i == 0 ? "A" : "B") + ", " + fixture.name();
                String source = string(su, "SELECT hours_source FROM shops WHERE id = '" + shop + "'");
                String scheduleText = string(su, "SELECT opening_schedule::text FROM shops WHERE id = '" + shop + "'");
                JsonNode sqlSchedule = scheduleText == null ? null : JSON.readTree(scheduleText);

                assertThat(source).as(label + ": SQL hours_source").isEqualTo(fixture.expectedSource().name());
                assertThat(sqlSchedule).as(label + ": SQL opening_schedule").isEqualTo(expected);
                // Parity: the migration and the application read the same text the same way.
                assertThat(source).as(label + ": SQL and Java hours_source agree").isEqualTo(java.source().name());
                assertThat(sqlSchedule).as(label + ": SQL and Java schedule agree").isEqualTo(javaSchedule);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sqlOnlyFixtures")
    @DisplayName("V80 classifies jsonb the entity cannot load (not an object of strings) without inventing hours")
    void migrationClassifiesShapesOnlyTheDatabaseCanHold(OpeningScheduleFixtures.RawFixture fixture) throws Exception {
        applyV80();
        JsonNode expected = fixture.expectedScheduleJson() == null ? null : JSON.readTree(fixture.expectedScheduleJson());
        try (Connection su = connect(postgres.getUsername(), postgres.getPassword())) {
            for (int i = 0; i < TENANTS.size(); i++) {
                UUID shop = RAW_FIXTURE_SHOPS.get(fixture.name())[i];
                String label = "tenant " + (i == 0 ? "A" : "B") + ", " + fixture.name();
                assertThat(string(su, "SELECT hours_source FROM shops WHERE id = '" + shop + "'"))
                        .as(label + ": hours_source").isEqualTo(fixture.expectedSource().name());
                String scheduleText = string(su, "SELECT opening_schedule::text FROM shops WHERE id = '" + shop + "'");
                assertThat(scheduleText == null ? null : JSON.readTree(scheduleText))
                        .as(label + ": opening_schedule").isEqualTo(expected);
                try (PreparedStatement ps = su.prepareStatement(
                        "SELECT opening_hours = ?::jsonb FROM shops WHERE id = ?")) {
                    ps.setString(1, fixture.rawJson());
                    ps.setObject(2, shop);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getBoolean(1)).as(label + ": the original text is kept for the vendor notice").isTrue();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("every seeded shop in both tenants is classified: none is left NULL")
    void noSeededShopIsLeftUnclassified() throws Exception {
        applyV80();
        try (Connection su = connect(postgres.getUsername(), postgres.getPassword())) {
            for (UUID tenant : TENANTS) {
                assertThat(string(su, "SELECT count(*) FROM shops WHERE tenant_id = '" + tenant
                        + "' AND slug LIKE 'v80-%' AND slug NOT LIKE 'v80-src-%'"))
                        .as("shops seeded before V80 in tenant " + tenant)
                        .isEqualTo(String.valueOf(2 + OpeningScheduleFixtures.ALL.size() + OpeningScheduleFixtures.SQL_ONLY.size()));
                assertThat(string(su, "SELECT count(*) FROM shops WHERE tenant_id = '" + tenant
                        + "' AND hours_source IS NULL")).as("unclassified shops in tenant " + tenant).isEqualTo("0");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------


    private static UUID seedShop(Connection c, UUID tenantId, String openingHoursJson, boolean published)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO shops (id, tenant_id, name, slug, opening_hours, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?, 0)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setString(3, "V80 shop " + id);
            ps.setString(4, "v80-" + id);
            ps.setString(5, openingHoursJson);
            ps.setBoolean(6, published);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedShopWithSource(Connection c, UUID tenantId, String hoursSource) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO shops (id, tenant_id, name, slug, hours_source, delivery_fee_pennies) VALUES (?, ?, ?, ?, ?, 0)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setString(3, "V80 source " + id);
            ps.setString(4, "v80-src-" + id);
            ps.setString(5, hoursSource);
            ps.executeUpdate();
        }
        return id;
    }

    private static int columnCount(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String string(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).as("row must exist for: " + sql).isTrue();
            return rs.getString(1);
        }
    }

    private static FluentConfiguration baseConfig(String user, String password) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), user, password)
                .locations("classpath:db/migration");
    }

    private static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), user, password);
    }

    private static List<String> appliedVersions(Flyway flyway) {
        return Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .toList();
    }
}
