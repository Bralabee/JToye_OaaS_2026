package uk.jtoye.core.geo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShopCoordinateBackfill} against a real Postgres with real FORCE RLS — because the
 * property under test IS the RLS behaviour, and H2 has none.
 *
 * <h2>The arm that matters is the negative one</h2>
 *
 * <p>{@code shops} is {@code ENABLE ROW LEVEL SECURITY} + {@code FORCE}. A bulk
 * {@code UPDATE shops SET latitude = …} with no tenant GUC matches <strong>zero rows and
 * reports success</strong>. This repository has shipped that exact defect three times — V25,
 * V44, V57 — so "the backfill wrote some rows" is not evidence of anything on its own; the
 * evidence is that the SAME call writes zero when, and only when, the tenant pin is removed.
 *
 * <h2>Two ways this test could have been vacuous, both closed</h2>
 *
 * <ol>
 *   <li><strong>The Testcontainers bootstrap role is a Postgres SUPERUSER, which bypasses even
 *       FORCE RLS.</strong> Run as that role, the unpinned backfill would happily write and the
 *       negative arm would fail — or worse, an unpinned arm elsewhere would silently pass. So
 *       every RLS arm downgrades to a {@code NOSUPERUSER NOBYPASSRLS} role with
 *       {@code SET LOCAL ROLE} (the house pattern from
 *       {@code CrossTenantMcpWriteRlsIntegrationTest}), and
 *       {@link #superuserBypassesRls_provesTheDowngradeIsLoadBearing()} proves the downgrade is
 *       doing the work by showing the identical unpinned call DOES write without it.</li>
 *   <li><strong>Zero updates can also mean "nothing was attempted".</strong> An absence-only
 *       assertion passes over an empty candidate set. So the negative arm asserts
 *       {@code refused >= 1} alongside {@code updated == 0}: the write was reached, offered to
 *       the database, and turned away. The counters are separate for precisely this reason.</li>
 * </ol>
 *
 * <p>The break is applied at the layer the backfill itself uses: {@code TenantContext} is what
 * {@code ShopCoordinateBackfill.pinTenantFromContext} reads, so clearing it removes the pin
 * there rather than in some lower-level helper that a global aspect might quietly re-apply.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class ShopCoordinateBackfillIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String RLS_TEST_ROLE = "rls_test_role";

    /** Real Code-Point Open centroids, copied from the committed 33-02 fixture. */
    private static final String GEOCODABLE_ADDRESS = "48 Rye Lane, Peckham, London SE15 5BS";
    private static final double SE15_5BS_LAT = 51.472435;
    private static final double SE15_5BS_LON = -0.070047;

    /**
     * Well-formed, satisfies every plausible UK-postcode regex, and does not exist — absent from
     * Code-Point Open and a 404 from ONSPD. The permanent negative control for "an unknown
     * postcode must stay NULL and must never become (0,0)".
     */
    private static final String UNKNOWN_POSTCODE_ADDRESS = "12 Bellenden Road, Peckham, London SE15 4QA";

    /** No extractable postcode at all — the other unresolvable shape. */
    private static final String NO_POSTCODE_ADDRESS = "1 Probe Lane, London";

    @Autowired private ShopCoordinateBackfill backfill;
    @Autowired private ShopRepository shopRepository;
    @Autowired private JdbcTemplate jdbc;

    @PersistenceContext private EntityManager entityManager;

    private UUID tenantId;
    private UUID geocodableShopId;
    private UUID unknownPostcodeShopId;
    private UUID noPostcodeShopId;

    @BeforeEach
    void seed() {
        jdbc.execute("DO $$ BEGIN "
                + "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN "
                + "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; "
                + "  END IF; "
                + "END $$");
        // Re-granted every time rather than only at creation: the role survives inside the
        // container across test classes, so a grant issued before a later migration created a
        // table would leave that table unreadable and the failure would look like RLS.
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE);
        jdbc.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE);
        jdbc.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE);

        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "backfill-" + tenantId);

        // The reference table carries no tenant column and no RLS (33-02), so it is seeded as
        // the bootstrap role. Real centroids, not invented numbers.
        jdbc.update("INSERT INTO postcode_centroid (postcode, latitude, longitude) VALUES (?, ?, ?) "
                + "ON CONFLICT (postcode) DO NOTHING", "SE155BS", SE15_5BS_LAT, SE15_5BS_LON);

        // All three PUBLISHED on purpose. shops_public_read OR-permits published=true, so a
        // published row stays VISIBLE to the unpinned negative arm — which is what lets that
        // arm reach the write and be refused, instead of passing because it saw nothing.
        geocodableShopId = seedShop("backfill-geocodable", GEOCODABLE_ADDRESS);
        unknownPostcodeShopId = seedShop("backfill-unknown-postcode", UNKNOWN_POSTCODE_ADDRESS);
        noPostcodeShopId = seedShop("backfill-no-postcode", NO_POSTCODE_ADDRESS);

        entityManager.clear();
    }

    private UUID seedShop(String slugPrefix, String address) {
        TenantContext.set(tenantId);
        Shop shop = new Shop();
        shop.setTenantId(tenantId);
        shop.setName(slugPrefix);
        shop.setSlug(slugPrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        shop.setAddress(address);
        shop.setPublished(true);
        shop.setLatitude(null);
        shop.setLongitude(null);
        UUID id = shopRepository.saveAndFlush(shop).getId();
        TenantContext.clear();
        return id;
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** Downgrade the current transaction's role so FORCE RLS is actually enforced. */
    private void dropSuperuserForTransaction() {
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
    }

    /**
     * Read a coordinate straight out of the table. Bypasses the persistence context on purpose:
     * the backfill writes with a bulk UPDATE, so a managed entity would still hold the stale
     * pre-backfill value and an assertion against it would be about Hibernate, not the database.
     */
    private Double latitudeOf(UUID shopId) {
        return jdbc.queryForObject("SELECT latitude FROM shops WHERE id = ?", Double.class, shopId);
    }

    // =====================================================================================
    // (a) WITH a tenant pin
    // =====================================================================================

    @Test
    @DisplayName("WITH a tenant pin: a positive number of rows is updated and the shop is populated")
    void withTenantPinTheBackfillWrites() {
        TenantContext.set(tenantId);
        dropSuperuserForTransaction();

        ShopCoordinateBackfill.BackfillReport report = backfill.backfillTenant(tenantId);

        assertThat(report.updated())
                .as("rows updated WITH the tenant pin")
                .isEqualTo(1);
        assertThat(report.refused())
                .as("no write should be refused when the tenant is pinned")
                .isZero();
        assertThat(latitudeOf(geocodableShopId))
                .as("the specific seeded shop's latitude after the backfill")
                .isEqualTo(SE15_5BS_LAT);
    }

    @Test
    @DisplayName("run() — the real entry point, which owns the pin — populates the shop too")
    void runPinsEachTenantItself() {
        dropSuperuserForTransaction();
        // No TenantContext here: run() is the layer that sets it. If it stopped doing so, this
        // arm would report 0 updated while backfillTenant's own arm above stayed green.
        ShopCoordinateBackfill.BackfillReport report = backfill.run();

        assertThat(report.updated()).as("rows updated by run()").isGreaterThanOrEqualTo(1);
        assertThat(latitudeOf(geocodableShopId)).isEqualTo(SE15_5BS_LAT);
    }

    // =====================================================================================
    // (b) WITHOUT a tenant pin — the arm the whole test exists for
    // =====================================================================================

    @Test
    @DisplayName("WITHOUT a tenant pin: ZERO rows updated, and the write was REFUSED, not skipped")
    void withoutTenantPinTheBackfillWritesNothing() {
        // The break, applied at the layer ShopCoordinateBackfill itself reads.
        TenantContext.clear();
        dropSuperuserForTransaction();

        ShopCoordinateBackfill.BackfillReport report = backfill.backfillTenant(tenantId);

        assertThat(report.updated())
                .as("rows updated WITHOUT the tenant pin — asserted on the COUNT, never an exit status")
                .isZero();
        // Non-vacuity. Without this limb a zero could mean "the candidate scan saw nothing",
        // which is what an emptied table or a broken query would also report.
        assertThat(report.refused())
                .as("the write must have been REACHED and turned away, not merely skipped")
                .isGreaterThanOrEqualTo(1);
        assertThat(latitudeOf(geocodableShopId))
                .as("the row is untouched")
                .isNull();
    }

    @Test
    @DisplayName("WITHOUT the NOSUPERUSER downgrade the same unpinned call DOES write — the downgrade is load-bearing")
    void superuserBypassesRls_provesTheDowngradeIsLoadBearing() {
        // Deliberately stay on the Testcontainers bootstrap SUPERUSER, which bypasses even
        // FORCE RLS. This is the control for the arm above: it shows the zero there is caused
        // by row-level security specifically, and not by a guard in our own code that would
        // return zero on any machine, RLS or not.
        TenantContext.clear();

        ShopCoordinateBackfill.BackfillReport report = backfill.backfillTenant(tenantId);

        assertThat(report.updated())
                .as("a superuser bypasses FORCE RLS, so the unpinned write lands")
                .isEqualTo(1);
        assertThat(latitudeOf(geocodableShopId)).isEqualTo(SE15_5BS_LAT);
    }

    // =====================================================================================
    // (c) An unresolvable postcode stays NULL — and never becomes (0,0)
    // =====================================================================================

    @Test
    @DisplayName("a shop whose postcode does not geocode is left NULL, is NOT set to (0,0), and the run completes")
    void unresolvableShopsAreLeftNullNotNullIsland() {
        TenantContext.set(tenantId);
        dropSuperuserForTransaction();

        ShopCoordinateBackfill.BackfillReport report = backfill.backfillTenant(tenantId);

        // The run completed rather than aborting on the two it could not resolve.
        assertThat(report.notGeocoded())
                .as("the unknown postcode and the postcode-less address, counted SEPARATELY from updates")
                .isEqualTo(2);

        assertThat(latitudeOf(unknownPostcodeShopId))
                .as("a postcode that does not exist yields NULL")
                .isNull();
        assertThat(latitudeOf(noPostcodeShopId))
                .as("an address with no postcode yields NULL")
                .isNull();

        Long nullIsland = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shops WHERE latitude = 0 AND longitude = 0", Long.class);
        assertThat(nullIsland)
                .as("no shop anywhere sits at Null Island — it would outrank every real shop")
                .isZero();
    }

    // =====================================================================================
    // (d) Idempotence
    // =====================================================================================

    @Test
    @DisplayName("a second run is a no-op: zero updated, and the coordinate is unchanged")
    void reRunningIsANoOp() {
        TenantContext.set(tenantId);
        dropSuperuserForTransaction();

        ShopCoordinateBackfill.BackfillReport first = backfill.backfillTenant(tenantId);
        ShopCoordinateBackfill.BackfillReport second = backfill.backfillTenant(tenantId);

        assertThat(first.updated()).as("the first pass writes").isEqualTo(1);
        assertThat(second.updated()).as("the second pass writes nothing").isZero();
        assertThat(latitudeOf(geocodableShopId))
                .as("and the value it wrote first time is still there")
                .isEqualTo(SE15_5BS_LAT);
    }
}
