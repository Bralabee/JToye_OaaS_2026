package uk.jtoye.core.media;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMG-01 (T-24-07) — regression proof for the V53 {@code image_url ->
 * product_media} backfill on a <em>non-fresh</em> database, the exact scenario
 * every fresh Testcontainers DB silently skips.
 *
 * <p><strong>The bug this pins.</strong> {@code products} carries ENABLE + FORCE
 * ROW LEVEL SECURITY. A bare {@code INSERT .. SELECT FROM products} backfill in V53
 * would run as the RLS-bound migration role ({@code jtoye_app}, NOSUPERUSER, no
 * {@code spring.flyway.user} override) with NO tenant GUC set, so
 * {@code current_tenant_id()} returns NULL, the policy hides every product, and the
 * backfill INSERTs ZERO {@code media_asset}/{@code product_media} rows — shipping an
 * empty asset model on every non-fresh DB while fresh Testcontainers DBs stay green
 * (nothing to backfill) and mask it. The fix is V44's per-tenant {@code set_config}
 * loop so every product is reached under the policy
 * ({@code trap_rls_migration_backfill}, recurring V25->V44->V57->V53).
 *
 * <p><strong>Why fresh DBs never catch it, and how this test does.</strong> This
 * test (mirroring {@code V57GrantSourceBackfillIntegrationTest}):
 * <ol>
 *   <li>migrates to V52 as the container SUPERUSER (stops BEFORE V53);</li>
 *   <li>seeds pre-V53 {@code products} rows with {@code image_url} +
 *       {@code additional_image_urls[]} across TWO tenants on the superuser
 *       connection (bypasses RLS, so the rows land for both tenants);</li>
 *   <li>provisions {@code rls_migrator} — NOSUPERUSER NOBYPASSRLS, mirroring
 *       {@code jtoye_app} — and applies V53 as that role, SUBJECT to FORCE RLS on
 *       the backfill reads/writes.</li>
 * </ol>
 *
 * <p>Against a bare no-GUC backfill this would produce ZERO {@code product_media}
 * rows for both tenants; the assertions below require every seeded product to be
 * backfilled for BOTH tenants — proving the per-tenant loop reached the whole
 * registry, not just the first tenant (nor zero). Seed-path images
 * ({@code /products/seed/}) are asserted EXCLUDED (SPEC D1).
 */
@Testcontainers
@Tag("testcontainers")
class MediaBackfillMigrationIntegrationTest {

    private static final String RLS_MIGRATOR = "rls_migrator";
    private static final String RLS_MIGRATOR_PW = "rls_migrator_pw";
    private static final String PUBLIC_URL = "http://localhost:9000/jtoye-images/";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_media")
            .withUsername("test")
            .withPassword("test");

    @Test
    void v53BackfillsExistingImagesAcrossTenantsUnderRls() throws Exception {
        // --- 1. Migrate to V52 as the container superuser. Stops BEFORE V53. --------
        Flyway toV52 = baseConfig(postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("52"))
                .load();
        toV52.migrate();
        assertThat(appliedVersions(toV52))
                .as("V52 applied and V53 not yet")
                .contains("52")
                .doesNotContain("53");

        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();
        UUID t1Main = UUID.randomUUID();
        UUID t1Seed = UUID.randomUUID();
        UUID t2Main = UUID.randomUUID();
        UUID t2Seed = UUID.randomUUID();

        try (Connection su = connect(postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement()) {
            // --- 2. RLS-bound migration role mirroring jtoye_app. It CREATES the V53
            //        tables (so it owns them) and runs the backfill SUBJECT to FORCE RLS. --
            s.execute("DROP ROLE IF EXISTS " + RLS_MIGRATOR);
            s.execute("CREATE ROLE " + RLS_MIGRATOR
                    + " NOSUPERUSER NOBYPASSRLS LOGIN PASSWORD '" + RLS_MIGRATOR_PW + "'");
            s.execute("GRANT ALL ON SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_MIGRATOR);

            // --- 3. Seed pre-V53 products across TWO tenants (superuser bypasses RLS). ---
            s.execute("INSERT INTO tenants (id, name) VALUES "
                    + "('" + tenant1 + "', 'Media Tenant 1'), ('" + tenant2 + "', 'Media Tenant 2')");
            seedMain(su, t1Main, tenant1);   // image_url + 2 gallery -> 1 primary + 2 gallery links
            seedSeed(su, t1Seed, tenant1);   // /products/seed/ -> EXCLUDED
            seedMain(su, t2Main, tenant2);
            seedSeed(su, t2Seed, tenant2);

            // media_asset does not exist yet (V53 adds it) — prove we are genuinely pre-V53.
            try (ResultSet rs = s.executeQuery(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = 'media_asset'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("pre-V53: media_asset must not exist yet").isZero();
            }
        }

        // --- 4. Apply V53 as the RLS-bound migrator. A bare no-GUC backfill would insert
        //        ZERO rows here; the per-tenant loop reaches every product. ---------------
        Flyway toV53 = baseConfig(RLS_MIGRATOR, RLS_MIGRATOR_PW)
                .target(MigrationVersion.fromVersion("53"))
                .load();
        var result = toV53.migrate();
        assertThat(result.migrationsExecuted).as("exactly V53 applied on top of V52").isEqualTo(1);
        assertThat(result.migrations.get(0).version).isEqualTo("53");

        // --- 5. Every seeded product backfilled for BOTH tenants (loop reached the whole
        //        registry). Seed-path products excluded. Read as superuser (bypass RLS). ---
        try (Connection su = connect(postgres.getUsername(), postgres.getPassword())) {
            assertThat(productMediaCount(su, t1Main)).as("tenant1 main: 1 primary + 2 gallery").isEqualTo(3);
            assertThat(productMediaCount(su, t2Main)).as("tenant2 main: 1 primary + 2 gallery").isEqualTo(3);
            assertThat(productMediaCount(su, t1Seed)).as("tenant1 seed image EXCLUDED (SPEC D1)").isZero();
            assertThat(productMediaCount(su, t2Seed)).as("tenant2 seed image EXCLUDED (SPEC D1)").isZero();

            assertPrimary(su, t1Main, tenant1);
            assertPrimary(su, t2Main, tenant2);
            assertGalleryContentTypes(su, t1Main);
            assertGalleryContentTypes(su, t2Main);

            assertThat(mediaAssetCount(su, tenant1)).as("tenant1: 3 distinct assets").isEqualTo(3);
            assertThat(mediaAssetCount(su, tenant2)).as("tenant2: 3 distinct assets").isEqualTo(3);

            // No product_media row left unmapped to an ACTIVE media_asset.
            try (Statement s = su.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT count(*) FROM product_media pm "
                                 + "LEFT JOIN media_asset a ON a.id = pm.asset_id "
                                 + "WHERE a.id IS NULL OR a.status <> 'ACTIVE'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("every backfilled link points at an ACTIVE asset").isZero();
            }
        }
    }

    // ---- seeding ------------------------------------------------------------

    private static void seedMain(Connection c, UUID productId, UUID tenantId) throws Exception {
        String base = PUBLIC_URL + tenantId + "/products/" + productId + "/";
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO products (id, tenant_id, sku, title, ingredients_text, image_url, additional_image_urls) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            Array gallery = c.createArrayOf("text",
                    new String[]{base + "g1.png", base + "g2.webp"});
            ps.setObject(1, productId);
            ps.setObject(2, tenantId);
            ps.setString(3, "MAIN-" + productId.toString().substring(0, 8));
            ps.setString(4, "Main Product");
            ps.setString(5, "Yam (100%)");
            ps.setString(6, base + "main.jpg");
            ps.setArray(7, gallery);
            ps.executeUpdate();
        }
    }

    private static void seedSeed(Connection c, UUID productId, UUID tenantId) throws Exception {
        // A seeder-owned demo image on the flat path — must NOT be wrapped (SPEC D1).
        String seedUrl = PUBLIC_URL + tenantId + "/products/seed/demo.jpg";
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO products (id, tenant_id, sku, title, ingredients_text, image_url) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, productId);
            ps.setObject(2, tenantId);
            ps.setString(3, "SEED-" + productId.toString().substring(0, 8));
            ps.setString(4, "Seed Product");
            ps.setString(5, "Yam (100%)");
            ps.setString(6, seedUrl);
            ps.executeUpdate();
        }
    }

    // ---- assertions ---------------------------------------------------------

    private static int productMediaCount(Connection c, UUID productId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM product_media WHERE product_id = ?")) {
            ps.setObject(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        }
    }

    private static int mediaAssetCount(Connection c, UUID tenantId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM media_asset WHERE tenant_id = ?")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        }
    }

    /** The primary link points at an ACTIVE asset whose object_key is the parsed
     *  tenant-relative key (NOT the full URL) and content_type is derived from the ext. */
    private static void assertPrimary(Connection c, UUID productId, UUID tenantId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT pm.is_primary, pm.sort_order, a.object_key, a.status, a.content_type "
                        + "FROM product_media pm JOIN media_asset a ON a.id = pm.asset_id "
                        + "WHERE pm.product_id = ? AND pm.is_primary = true")) {
            ps.setObject(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("a primary link exists").isTrue();
                assertThat(rs.getBoolean("is_primary")).isTrue();
                assertThat(rs.getInt("sort_order")).isZero();
                assertThat(rs.getString("object_key"))
                        .as("object_key is the parsed tenant-relative key, not the full URL")
                        .isEqualTo(tenantId + "/products/" + productId + "/main.jpg");
                assertThat(rs.getString("status")).isEqualTo("ACTIVE");
                assertThat(rs.getString("content_type")).isEqualTo("image/jpeg");
                assertThat(rs.next()).as("exactly one primary per product").isFalse();
            }
        }
    }

    /** Gallery links preserve array order and derive content_type per extension. */
    private static void assertGalleryContentTypes(Connection c, UUID productId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT pm.sort_order, a.content_type "
                        + "FROM product_media pm JOIN media_asset a ON a.id = pm.asset_id "
                        + "WHERE pm.product_id = ? AND pm.is_primary = false ORDER BY pm.sort_order")) {
            ps.setObject(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("sort_order")).isEqualTo(1);
                assertThat(rs.getString("content_type")).isEqualTo("image/png");   // g1.png
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("sort_order")).isEqualTo(2);
                assertThat(rs.getString("content_type")).isEqualTo("image/webp");  // g2.webp
                assertThat(rs.next()).as("exactly two gallery links").isFalse();
            }
        }
    }

    // ---- Flyway plumbing (mirrors V57GrantSourceBackfillIntegrationTest) ----

    private static FluentConfiguration baseConfig(String user, String password) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), user, password)
                .locations("classpath:db/migration");
    }

    private static Connection connect(String user, String password) throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), user, password);
    }

    private static List<String> appliedVersions(Flyway flyway) {
        return Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
    }
}
