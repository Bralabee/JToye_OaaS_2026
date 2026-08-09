package uk.jtoye.core.storefront;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.geo.GeoBounds;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.shop.ShopWithDistance;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The distance query — #460 link 5, against a real Postgres.
 *
 * <h2>Why this is an integration test and not a unit test</h2>
 *
 * <p>Everything under test lives in SQL: the haversine expression, the {@code published = true}
 * predicate in BOTH the row query and the count query, the {@code BETWEEN} prefilter, and the
 * ordering. H2 would answer none of those questions the way the delivered database does — and
 * one of the behaviours (a query point sitting exactly on a shop's centroid) is a PostgreSQL
 * domain error in the formulation this query deliberately avoids.
 *
 * <h2>The geometry, stated once</h2>
 *
 * <p>All fixtures sit around one query point P. Their distances are arithmetic, not magic:
 * one degree of latitude is {@code pi * 6371.0088 / 180 = 111.1949 km}, and every fixture except
 * CORNER differs from P in latitude only, so its distance is a multiple of that.
 *
 * <pre>
 *   P        51.4700, -0.0700   the query point
 *   NEAR     51.4710, -0.0700   0.1112 km   tenant B
 *   MID      51.4750, -0.0700   0.5560 km   tenant A
 *   FAR      51.4900, -0.0700   2.2239 km   tenant A
 *   CORNER   51.5105, -0.0050   ~6.36 km    tenant A   INSIDE the 5 km box, OUTSIDE the 5 km circle
 *   NULLCO   (null, null)       —           tenant A   published, no coordinate
 *   UNPUB    51.4710, -0.0700   0.1112 km   tenant A   NOT published
 * </pre>
 *
 * <p><strong>CORNER is the fixture that makes the radius assertion mean anything.</strong> The
 * bounding box is a square that contains the circle, so a shop can be inside the box and outside
 * the radius — the corner of the box is {@code r * sqrt(2)} from the centre. If the query filtered
 * on the box alone, every other radius arm here would still pass. CORNER sits at 90% of the box
 * half-extent on both axes, i.e. about {@code 1.27 r}, and is the only fixture that distinguishes
 * "filtered by a box" from "filtered by a radius".
 *
 * <h2>Two fixtures exist to be ABSENT</h2>
 *
 * <p>NULLCO and UNPUB are negative controls. UNPUB carries NEAR's exact coordinates so it cannot
 * be excluded by distance — only by {@code published = true}. It is asserted absent from the page
 * CONTENT and from {@code getTotalElements()} separately, because a count query that forgets the
 * predicate leaks the existence of unpublished shops through the total while the content stays
 * correct, and a content-only assertion cannot see that.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class PublicStorefrontDistanceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    /** The query point every arm below measures from. */
    private static final double P_LAT = 51.4700;
    private static final double P_LON = -0.0700;

    /** pi * 6371.0088 / 180 — one degree of latitude in km, the same radius GeoBounds uses. */
    private static final double KM_PER_DEGREE_LATITUDE = Math.PI * GeoBounds.EARTH_RADIUS_KM / 180.0;

    private static final double NEAR_LAT = 51.4710;
    private static final double MID_LAT = 51.4750;
    private static final double FAR_LAT = 51.4900;
    private static final double SHARED_LON = -0.0700;

    /** 90% of the 5 km box half-extent on both axes: inside the box, ~1.27 x 5 km from P. */
    private static final double CORNER_LAT;
    private static final double CORNER_LON;

    static {
        GeoBounds box = GeoBounds.boxAround(P_LAT, P_LON, 5.0);
        CORNER_LAT = P_LAT + 0.9 * (box.maxLatitude() - P_LAT);
        CORNER_LON = P_LON + 0.9 * (box.maxLongitude() - P_LON);
    }

    private static final String NEAR = "distance-near";
    private static final String MID = "distance-mid";
    private static final String FAR = "distance-far";
    private static final String CORNER = "distance-corner";
    private static final String NULLCO = "distance-null-coordinates";
    private static final String UNPUB = "distance-unpublished";

    @Autowired private ShopRepository shopRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void seed() {
        // A fresh fixture per test: two arms below MUTATE coordinates, and a leftover swap would
        // silently invert the expectations of whichever test ran next.
        jdbc.update("DELETE FROM shops");

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "distance-tenant-a-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "distance-tenant-b-" + tenantB);

        // NEAR is in a DIFFERENT tenant from MID and FAR on purpose. The anonymous storefront read
        // is cross-tenant by design (shops_public_read OR-permits published = true), so an ordering
        // that spans tenants is also the assertion that no tenant filter crept into this path.
        seedShop(tenantB, NEAR, NEAR_LAT, SHARED_LON, true);
        seedShop(tenantA, MID, MID_LAT, SHARED_LON, true);
        seedShop(tenantA, FAR, FAR_LAT, SHARED_LON, true);
        seedShop(tenantA, CORNER, CORNER_LAT, CORNER_LON, true);
        seedShop(tenantA, NULLCO, null, null, true);
        seedShop(tenantA, UNPUB, NEAR_LAT, SHARED_LON, false);
    }

    private void seedShop(UUID tenantId, String slug, Double latitude, Double longitude, boolean published) {
        TenantContext.set(tenantId);
        try {
            Shop shop = new Shop();
            shop.setTenantId(tenantId);
            shop.setName(slug);
            shop.setSlug(slug);
            shop.setAddress("1 Fixture Street, London");
            shop.setLatitude(latitude);
            shop.setLongitude(longitude);
            shop.setPublished(published);
            shopRepository.saveAndFlush(shop);
        } finally {
            TenantContext.clear();
        }
    }

    /** The call under test, always with an UNSORTED pageable — the query owns its ordering. */
    private Page<ShopWithDistance> near(double lat, double lon, double radiusKm, Pageable pageable) {
        GeoBounds box = GeoBounds.boxAround(lat, lon, radiusKm);
        return shopRepository.findPublishedNear(lat, lon,
                box.minLatitude(), box.maxLatitude(), box.minLongitude(), box.maxLongitude(),
                radiusKm, pageable);
    }

    private Page<ShopWithDistance> near(double lat, double lon, double radiusKm) {
        return near(lat, lon, radiusKm, PageRequest.of(0, 20, Sort.unsorted()));
    }

    private static List<String> slugsOf(Page<ShopWithDistance> page) {
        return page.getContent().stream().map(ShopWithDistance::getSlug).toList();
    }

    // =====================================================================================
    // Ordering
    // =====================================================================================

    @Test
    @DisplayName("results are ordered by real distance, and the ordering spans tenants")
    void ordersByRealDistance() {
        Page<ShopWithDistance> page = near(P_LAT, P_LON, 5.0);

        assertThat(slugsOf(page))
                .as("nearest first — NEAR (0.11 km), MID (0.56 km), FAR (2.22 km)")
                .containsExactly(NEAR, MID, FAR);

        assertThat(page.getContent().get(0).getDistanceKm())
                .isCloseTo(0.0010 * KM_PER_DEGREE_LATITUDE, org.assertj.core.data.Offset.offset(0.01));
        assertThat(page.getContent().get(1).getDistanceKm())
                .isCloseTo(0.0050 * KM_PER_DEGREE_LATITUDE, org.assertj.core.data.Offset.offset(0.01));
        assertThat(page.getContent().get(2).getDistanceKm())
                .isCloseTo(0.0200 * KM_PER_DEGREE_LATITUDE, org.assertj.core.data.Offset.offset(0.01));

        // Non-vacuity for the cross-tenant claim: NEAR is tenant B, MID and FAR are tenant A. If a
        // tenant filter were added to this path the list would collapse to one tenant's shops and
        // the ordering assertion above would fail for a reason that looks like a distance bug.
        List<UUID> tenantIds = jdbc.queryForList(
                "SELECT tenant_id FROM shops WHERE slug IN (?, ?, ?)", UUID.class, NEAR, MID, FAR);
        assertThat(tenantIds).as("the three ordered shops must not all belong to one tenant")
                .containsOnlyOnce(tenantA, tenantB);
    }

    @Test
    @DisplayName("swapping two shops' coordinates changes the order — the SQL is driving the sort")
    void swappingCoordinatesChangesTheOrder() {
        assertThat(slugsOf(near(P_LAT, P_LON, 5.0)))
                .as("baseline before the swap")
                .containsExactly(NEAR, MID, FAR);

        // Read the row counts, never the exit status: an UPDATE that matched nothing also
        // "succeeds", and this arm would then be asserting that nothing changed nothing.
        int movedMid = jdbc.update("UPDATE shops SET latitude = ? WHERE slug = ?", FAR_LAT, MID);
        int movedFar = jdbc.update("UPDATE shops SET latitude = ? WHERE slug = ?", MID_LAT, FAR);
        assertThat(movedMid).as("rows moved for " + MID).isEqualTo(1);
        assertThat(movedFar).as("rows moved for " + FAR).isEqualTo(1);

        assertThat(slugsOf(near(P_LAT, P_LON, 5.0)))
                .as("after the swap the middle and far shops must trade places; if this still reads "
                        + "NEAR, MID, FAR the result is insertion order and the query is not sorting")
                .containsExactly(NEAR, FAR, MID);
    }

    // =====================================================================================
    // The radius is a radius, not a box
    // =====================================================================================

    @Test
    @DisplayName("a shop inside the bounding box but outside the radius is excluded — and reappears when the radius widens")
    void radiusExcludesTheBoxCorner() {
        GeoBounds box = GeoBounds.boxAround(P_LAT, P_LON, 5.0);
        assertThat(box.contains(CORNER_LAT, CORNER_LON))
                .as("CORNER must be INSIDE the 5 km box, or this arm proves nothing about the radius")
                .isTrue();

        Page<ShopWithDistance> tight = near(P_LAT, P_LON, 5.0);
        assertThat(slugsOf(tight)).as("5 km radius").doesNotContain(CORNER);
        assertThat(tight.getTotalElements()).as("5 km total").isEqualTo(3);

        // The control. A filter that never admits anything is not a filter; widening must let
        // exactly this shop back in.
        Page<ShopWithDistance> wide = near(P_LAT, P_LON, 8.0);
        assertThat(slugsOf(wide)).as("8 km radius").contains(CORNER);
        assertThat(wide.getContent().get(wide.getContent().size() - 1).getDistanceKm())
                .as("CORNER is the furthest of the four, between 5 and 8 km")
                .isBetween(5.0, 8.0);
    }

    // =====================================================================================
    // Absences
    // =====================================================================================

    @Test
    @DisplayName("a shop with NULL coordinates is absent, and its presence in the table throws nothing")
    void nullCoordinatesAreExcludedWithoutThrowing() {
        assertThatCode(() -> {
            Page<ShopWithDistance> page = near(P_LAT, P_LON, 5.0);
            assertThat(slugsOf(page)).doesNotContain(NULLCO);
        }).doesNotThrowAnyException();

        // Non-vacuity: the row really is there, so the absence is a filter and not an empty table.
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM shops WHERE slug = ? AND latitude IS NULL", Integer.class, NULLCO);
        assertThat(rows).as("the NULL-coordinate fixture must exist to be excluded").isEqualTo(1);
    }

    @Test
    @DisplayName("an unpublished shop is absent from the CONTENT and from the TOTAL")
    void unpublishedShopIsAbsentFromContentAndCount() {
        Page<ShopWithDistance> page = near(P_LAT, P_LON, 5.0);

        assertThat(slugsOf(page)).as("page content").doesNotContain(UNPUB);
        assertThat(page.getTotalElements())
                .as("totalElements — an unpublished shop leaking through a countQuery that forgot "
                        + "published = true is invisible to a content-only assertion")
                .isEqualTo(3);

        // Non-vacuity: UNPUB sits on NEAR's exact coordinates, so it is inside every radius used
        // here. Only the published predicate can be excluding it.
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM shops WHERE slug = ? AND published = false", Integer.class, UNPUB);
        assertThat(rows).isEqualTo(1);
    }

    // =====================================================================================
    // The coincident point — the acos trap's only symptom
    // =====================================================================================

    @Test
    @DisplayName("a query point EXACTLY on a shop's coordinates returns distance 0.0, not a 500")
    void coincidentPointReturnsZeroDistance() {
        Page<ShopWithDistance> page = near(NEAR_LAT, SHARED_LON, 5.0);

        assertThat(slugsOf(page)).as("the shop the customer is standing on must come first")
                .startsWith(NEAR);
        assertThat(page.getContent().get(0).getDistanceKm())
                .as("the spherical law of cosines pushes acos's argument to 1.0000000000000002 here "
                        + "and PostgreSQL raises 'input is out of range' — an unauthenticated 500")
                .isEqualTo(0.0);
    }

    // =====================================================================================
    // Paging
    // =====================================================================================

    @Test
    @DisplayName("getTotalElements survives an offset past the end of the result set")
    void totalElementsSurvivesAnOffsetPastTheEnd() {
        Page<ShopWithDistance> secondPage =
                near(P_LAT, P_LON, 5.0, PageRequest.of(1, 2, Sort.unsorted()));

        assertThat(secondPage.getContent()).as("one shop left on page 2 of size 2").hasSize(1);
        assertThat(slugsOf(secondPage)).containsExactly(FAR);
        assertThat(secondPage.getTotalElements())
                .as("offset(2) + size(2) = 4 exceeds the true total of 3 — the shape that makes a "
                        + "hand-built PageImpl rewrite the total it was handed")
                .isEqualTo(3);
        assertThat(secondPage.getTotalPages()).isEqualTo(2);
    }
}
