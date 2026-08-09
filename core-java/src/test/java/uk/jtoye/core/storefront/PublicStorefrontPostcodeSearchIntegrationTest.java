package uk.jtoye.core.storefront;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Postcode-proximity search — issue #619, against a real PostgreSQL.
 *
 * <h2>Why this cannot be a unit test</h2>
 *
 * <p>Three of the things under test live only in SQL: the CLOSED key range against
 * {@code postcode_centroid_pkey}, the {@code length(postcode)} guard that keeps M11's units out
 * of an M1 lookup, and the {@code published = true} predicate in BOTH the row query and the
 * count query of {@code findPublishedNear}. A mock can be made to agree with any of them.
 *
 * <h2>postcode_centroid is EMPTY in every integration test, and that is deliberate</h2>
 *
 * <p>{@code application-test.yml} sets {@code jtoye.geo.postcode-import.enabled: false}, because
 * COPY-ing 1,748,230 rows into every Testcontainers Postgres would cost minutes per container.
 * So this class seeds it — with <strong>real Code-Point Open values read out of the committed
 * dataset</strong>, never invented numbers. An unseeded arm would pass by returning nothing,
 * which is exactly the pre-fix behaviour #619 describes.
 *
 * <h2>The M1 geometry, which is the whole of CA-F</h2>
 *
 * <p>Two M1 units (5 characters) and one M11 unit (6 characters) are seeded. {@code M111AA}
 * sorts INSIDE the M1 district's key range {@code [M10AA, M19ZZ]} — only the length guard
 * excludes it. So there are two possible centroids, and the fixture is arranged so they return
 * DIFFERENT SETS of shops rather than merely different decimals:
 *
 * <pre>
 *   guarded centroid    mean of M11AD + M11AE                (the correct answer)
 *   unguarded centroid  mean of M11AD + M11AE + M111AA       ~1.5 km ESE of it
 *
 *   MCR_CENTRE  sits ON the guarded centroid    -> 0.0 km guarded, ~1.5 km unguarded
 *   MCR_BEYOND  sits 5.8 km along that drift    -> 5.8 km guarded (OUTSIDE the 5 km radius),
 *                                                  ~4.3 km unguarded (INSIDE it)
 * </pre>
 *
 * <p>Deleting the guard therefore changes WHICH SHOPS COME BACK, not just how far away they are
 * reported to be. {@link #theM1FixtureCanActuallyDistinguishTheTwoCentroids()} asserts that
 * property of the fixture itself, so this arm cannot quietly stop being decisive.
 *
 * <h2>Two fixtures exist to be ABSENT</h2>
 *
 * <p>SE22_UNPUB carries SE22_NEAR's exact coordinates, so nothing but {@code published = true}
 * can hide it, and it is asserted absent from the page CONTENT and from {@code totalElements}
 * separately — <strong>at page size 2</strong>, because Spring Data's
 * {@code PageableExecutionUtils.getPage} skips the count query entirely when the page size
 * exceeds the content size, and 33-06 lost an arm to exactly that. SE154QA is this repo's
 * permanent negative control: well-formed, in our own seeded demo data, and confirmed absent
 * from the committed dataset.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class PublicStorefrontPostcodeSearchIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String HEADER = SearchInterpretation.HEADER;

    /** The platform radius the postcode tier applies — jtoye.geo.default-radius-km. */
    private static final double RADIUS_KM = 5.0;

    /** pi * 6371.0088 / 180 — one degree of latitude in km, the radius GeoBounds uses. */
    private static final double KM_PER_DEGREE_LATITUDE = Math.PI * GeoBounds.EARTH_RADIUS_KM / 180.0;

    // ---- Real Code-Point Open rows, read out of geo/postcode-centroids.csv.gz ----------

    private static final double SE22_0AA_LAT = 51.447688, SE22_0AA_LON = -0.072271;
    private static final double SE22_0AD_LAT = 51.444527, SE22_0AD_LON = -0.068217;

    private static final double SE15_5BS_LAT = 51.472435, SE15_5BS_LON = -0.070047;
    private static final double SE15_4BW_LAT = 51.466812, SE15_4BW_LON = -0.073164;

    private static final double M1_1AD_LAT = 53.483813, M1_1AD_LON = -2.244854;
    private static final double M1_1AE_LAT = 53.483471, M1_1AE_LON = -2.231184;
    private static final double M11_1AA_LAT = 53.473612, M11_1AA_LON = -2.172088;

    /** Derived, never pasted: the district centroid the SHIPPED query must produce. */
    private static final double SE22_LAT = (SE22_0AA_LAT + SE22_0AD_LAT) / 2;
    private static final double SE22_LON = (SE22_0AA_LON + SE22_0AD_LON) / 2;

    private static final double M1_GUARDED_LAT = (M1_1AD_LAT + M1_1AE_LAT) / 2;
    private static final double M1_GUARDED_LON = (M1_1AD_LON + M1_1AE_LON) / 2;

    /** What the query would answer with the length guard DELETED — CA-F's wrong answer. */
    private static final double M1_UNGUARDED_LAT = (M1_1AD_LAT + M1_1AE_LAT + M11_1AA_LAT) / 3;
    private static final double M1_UNGUARDED_LON = (M1_1AD_LON + M1_1AE_LON + M11_1AA_LON) / 3;

    // ---- Shop fixtures -----------------------------------------------------------------

    // NO fixture name may contain "se22", "m1", "bt1" or any other search term used below —
    // WITH ONE DELIBERATE EXCEPTION, SE15_NAMESAKE.
    //
    // The reason for the rule INVERTED at the 33-09 owner gate, and both halves are worth
    // stating because the rule looks unchanged. Under the shipped-33-08 ordering a text match
    // meant the postcode tier NEVER RAN, so a shop called "postcode-se22-near" would have made
    // the whole suite pass while measuring nothing. Under interpretation-first a resolvable
    // postcode is answered as a place BEFORE either text query is issued, so a text match can no
    // longer suppress the proximity tier — but it can still pollute an exact-set assertion by
    // arriving through the text path on the arms that genuinely take it.
    //
    // SE15_NAMESAKE exists precisely to be that text match, parked far outside every radius, so
    // the flip is observable: it is what the old ordering returned for "SE15 5BS" and what the
    // new ordering must not.
    private static final String SE22_NEAR = "dulwich-near-kitchen";
    private static final String SE22_MID = "dulwich-mid-kitchen";
    private static final String SE22_ALSO = "dulwich-third-kitchen";
    private static final String SE22_UNPUB = "dulwich-unpublished-kitchen";
    private static final String SE22_BEYOND = "dulwich-beyond-radius-kitchen";
    private static final String JOLLOF = "jollof-house-kitchen";
    private static final String MCR_CENTRE = "manchester-centre-kitchen";
    private static final String MCR_BEYOND = "manchester-eastern-kitchen";

    /**
     * The kitchen whose own NAME carries the literal string "SE15 5BS" — the case that separates
     * the two orderings (D-A). Deliberately 55 km away, so proximity can never return it and its
     * presence in a result set can only mean the text path answered.
     */
    private static final String SE15_NAMESAKE = "namesake-far-kitchen";
    private static final String SE15_NAMESAKE_NAME = "SE15 5BS Namesake Kitchen";

    private static final double SE22_NEAR_LAT = SE22_LAT + 1.5 / KM_PER_DEGREE_LATITUDE;
    private static final double SE22_MID_LAT = SE22_LAT + 2.0 / KM_PER_DEGREE_LATITUDE;
    private static final double SE22_ALSO_LAT = SE22_LAT + 2.5 / KM_PER_DEGREE_LATITUDE;
    private static final double SE22_BEYOND_LAT = SE22_LAT + 40.0 / KM_PER_DEGREE_LATITUDE;
    private static final double JOLLOF_LAT = SE22_LAT + 45.0 / KM_PER_DEGREE_LATITUDE;
    private static final double SE15_NAMESAKE_LAT = SE22_LAT + 55.0 / KM_PER_DEGREE_LATITUDE;

    /** 5.8 km from the guarded centroid, along the drift the M11 intruder would cause. */
    private static final double MCR_BEYOND_LAT;
    private static final double MCR_BEYOND_LON;

    static {
        double kmPerDegLon = KM_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(M1_GUARDED_LAT));
        double driftNorthKm = (M1_UNGUARDED_LAT - M1_GUARDED_LAT) * KM_PER_DEGREE_LATITUDE;
        double driftEastKm = (M1_UNGUARDED_LON - M1_GUARDED_LON) * kmPerDegLon;
        double drift = Math.hypot(driftEastKm, driftNorthKm);
        double scale = 5.8 / drift;
        MCR_BEYOND_LAT = M1_GUARDED_LAT + (driftNorthKm * scale) / KM_PER_DEGREE_LATITUDE;
        MCR_BEYOND_LON = M1_GUARDED_LON + (driftEastKm * scale) / kmPerDegLon;
    }

    @Autowired private ShopRepository shopRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM shops");

        // The reference table carries no tenant column and no RLS (33-02), so it is seeded as the
        // bootstrap role with no set_config pin. Real centroids, never invented numbers.
        seedCentroid("SE220AA", SE22_0AA_LAT, SE22_0AA_LON);
        seedCentroid("SE220AD", SE22_0AD_LAT, SE22_0AD_LON);
        seedCentroid("SE155BS", SE15_5BS_LAT, SE15_5BS_LON);
        seedCentroid("SE154BW", SE15_4BW_LAT, SE15_4BW_LON);
        seedCentroid("M11AD", M1_1AD_LAT, M1_1AD_LON);
        seedCentroid("M11AE", M1_1AE_LAT, M1_1AE_LON);
        // The intruder: 6 characters, inside [M10AA, M19ZZ], excluded only by the length guard.
        seedCentroid("M111AA", M11_1AA_LAT, M11_1AA_LON);

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "postcode-tenant-a-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "postcode-tenant-b-" + tenantB);

        // SE22_MID is in a DIFFERENT tenant on purpose. The anonymous storefront read is
        // cross-tenant by design, so a result spanning tenants is the assertion that no tenant
        // filter crept into the new path — "some shops came back" would pass while a stray
        // filter silently halved the directory.
        seedShop(tenantA, SE22_NEAR, SE22_NEAR_LAT, SE22_LON, true, SE22_NEAR);
        seedShop(tenantB, SE22_MID, SE22_MID_LAT, SE22_LON, true, SE22_MID);
        seedShop(tenantA, SE22_ALSO, SE22_ALSO_LAT, SE22_LON, true, SE22_ALSO);
        // Exactly on SE22_NEAR's coordinates: nothing but published = true can hide it.
        seedShop(tenantA, SE22_UNPUB, SE22_NEAR_LAT, SE22_LON, false, SE22_UNPUB);
        seedShop(tenantA, SE22_BEYOND, SE22_BEYOND_LAT, SE22_LON, true, SE22_BEYOND);
        // Has REAL coordinates on purpose: a null distanceKm on the text path then proves the
        // code path did not compute one, rather than proving the shop had nothing to compute.
        seedShop(tenantA, JOLLOF, JOLLOF_LAT, SE22_LON, true, "Jollof House");
        seedShop(tenantA, MCR_CENTRE, M1_GUARDED_LAT, M1_GUARDED_LON, true, MCR_CENTRE);
        seedShop(tenantB, MCR_BEYOND, MCR_BEYOND_LAT, MCR_BEYOND_LON, true, MCR_BEYOND);
        // The D-A flip's fixture: text-matchable on "SE15 5BS", and 55 km from every centroid
        // this class seeds, so proximity cannot reach it.
        seedShop(tenantA, SE15_NAMESAKE, SE15_NAMESAKE_LAT, SE22_LON, true, SE15_NAMESAKE_NAME);
    }

    private void seedCentroid(String postcode, double latitude, double longitude) {
        jdbc.update("INSERT INTO postcode_centroid (postcode, latitude, longitude) VALUES (?, ?, ?) "
                + "ON CONFLICT (postcode) DO NOTHING", postcode, latitude, longitude);
    }

    private void seedShop(UUID tenantId, String slug, Double latitude, Double longitude,
                          boolean published, String name) {
        TenantContext.set(tenantId);
        try {
            Shop shop = new Shop();
            shop.setTenantId(tenantId);
            shop.setName(name);
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

    /** Great-circle distance in km — the same figure the SQL computes, computed independently. */
    private static double kmBetween(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * GeoBounds.EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }

    private JsonNode search(String q) throws Exception {
        String body = mockMvc.perform(get("/public/shops").param("q", q))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static List<String> slugsOf(JsonNode page) {
        List<String> slugs = new ArrayList<>();
        page.path("content").forEach(node -> slugs.add(node.path("slug").asText()));
        return slugs;
    }

    // =====================================================================================
    // 1. The district hit — the whole of #619
    // =====================================================================================

    @Test
    @DisplayName("a bare outward code returns nearby published kitchens, nearest first, where it "
            + "returned NONE before")
    void districtSearchReturnsNearbyPublishedShops() throws Exception {
        JsonNode page = search("SE22");

        assertThat(slugsOf(page))
                .as("nearest first — 1.5 km, 2.0 km, 2.5 km from the SE22 district centroid")
                .containsExactly(SE22_NEAR, SE22_MID, SE22_ALSO);

        List<Double> distances = new ArrayList<>();
        page.path("content").forEach(node -> {
            assertThat(node.path("distanceKm").isNull())
                    .as("every shop on a proximity page must carry a distance")
                    .isFalse();
            distances.add(node.path("distanceKm").asDouble());
        });
        assertThat(distances).as("ascending").isSorted();
        assertThat(distances.get(0)).isCloseTo(1.5, org.assertj.core.data.Offset.offset(0.05));

        // The 40 km shop is the control on the radius: without it, "three shops came back" would
        // be satisfied by a query with no radius predicate at all.
        assertThat(slugsOf(page)).doesNotContain(SE22_BEYOND);

        mockMvc.perform(get("/public/shops").param("q", "SE22"))
                .andExpect(header().string(HEADER,
                        "proximity; postcode=SE22; precision=district; radiusKm=5.0"));
    }

    // =====================================================================================
    // 2. The unit hit
    // =====================================================================================

    @Test
    @DisplayName("a full unit resolves by primary key: precision=unit, and the key loses its space")
    void fullUnitSearchResolvesByPrimaryKey() throws Exception {
        mockMvc.perform(get("/public/shops").param("q", "SE22 0AA"))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER,
                        "proximity; postcode=SE220AA; precision=unit; radiusKm=5.0"))
                .andExpect(jsonPath("$.content[0].distanceKm").isNumber());
    }

    // =====================================================================================
    // 3. The unit miss falling back to its district
    // =====================================================================================

    @Test
    @DisplayName("SE15 4QA — well-formed, in our own demo data, and not real — now answers with "
            + "SE15's area instead of nothing")
    void nonExistentUnitFallsBackToItsDistrict() throws Exception {
        // The precondition IS the test's meaning: if SE154QA were ever added to the dataset this
        // arm would silently start measuring the unit path instead of the fallback.
        Integer seeded = jdbc.queryForObject(
                "SELECT count(*) FROM postcode_centroid WHERE postcode = 'SE154QA'", Integer.class);
        assertThat(seeded).as("SE15 4QA must remain absent — it is the phase's negative control")
                .isZero();

        mockMvc.perform(get("/public/shops").param("q", "SE15 4QA"))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER,
                        "proximity; postcode=SE15; precision=district; radiusKm=5.0"));
    }

    // =====================================================================================
    // 3b. D-A, FLIPPED AT THE 33-09 OWNER GATE — a postcode is a place, not a string
    // =====================================================================================

    @Test
    @DisplayName("D-A FLIP: 'SE15 5BS' answers with the kitchens NEAR SE15 5BS, not with the "
            + "far-away kitchen whose own name carries that string")
    void aFullUnitIsAnsweredAsLocalityNotAsItsOwnTextMatch() throws Exception {
        // ── NON-VACUITY, FIRST ──────────────────────────────────────────────────────────────
        // Measured with the LIKE tier's OWN predicate, against the real table. Without this the
        // arm would pass over a fixture that simply has no text match, and would be a statement
        // about the fixture rather than about the ordering. Under the shipped-33-08 ordering
        // this one row IS what `q=SE15 5BS` returned.
        Integer textMatches = jdbc.queryForObject(
                "SELECT count(*) FROM shops WHERE published = true "
                        + "AND lower(name) LIKE lower('%SE15 5BS%')", Integer.class);
        assertThat(textMatches)
                .as("CONTROL: the LIKE tier WOULD have matched this exact string")
                .isEqualTo(1);
        assertThat(kmBetween(SE15_5BS_LAT, SE15_5BS_LON, SE15_NAMESAKE_LAT, SE22_LON))
                .as("CONTROL: and the namesake is far outside the radius, so proximity cannot "
                        + "return it for an unrelated reason")
                .isGreaterThan(RADIUS_KM);

        // ── THE FLIP ────────────────────────────────────────────────────────────────────────
        mockMvc.perform(get("/public/shops").param("q", "SE15 5BS"))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER,
                        "proximity; postcode=SE155BS; precision=unit; radiusKm=5.0"));

        JsonNode page = search("SE15 5BS");
        assertThat(slugsOf(page))
                .as("every kitchen within 5 km of SE15 5BS, nearest first")
                .containsExactly(SE22_ALSO, SE22_MID, SE22_NEAR);
        assertThat(slugsOf(page))
                .as("and NOT the namesake, which is only a string match")
                .doesNotContain(SE15_NAMESAKE);
    }

    @Test
    @DisplayName("D-A FLIP CONTROL: the namesake is still reachable by a term that is NOT a "
            + "postcode — the text path was reordered, not removed")
    void theNamesakeIsStillReachableByText() throws Exception {
        // The other half of the owner's decision: non-postcode-shaped queries are untouched.
        // If this arm ever reds, the flip has broken the text search rather than reordered it.
        mockMvc.perform(get("/public/shops").param("q", "Namesake"))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER, "text"));

        JsonNode page = search("Namesake");
        assertThat(slugsOf(page)).containsExactly(SE15_NAMESAKE);
        page.path("content").forEach(node ->
                assertThat(node.path("distanceKm").isNull())
                        .as("a text result must carry no distance")
                        .isTrue());
    }

    // =====================================================================================
    // 4. CA-F — the unit-length guard, as a permanent test
    // =====================================================================================

    @Test
    @DisplayName("CA-F: the fixture can actually distinguish a guarded M1 lookup from an unguarded "
            + "one — asserted, so the arm below cannot quietly stop being decisive")
    void theM1FixtureCanActuallyDistinguishTheTwoCentroids() {
        double drift = kmBetween(M1_GUARDED_LAT, M1_GUARDED_LON, M1_UNGUARDED_LAT, M1_UNGUARDED_LON);
        assertThat(drift)
                .as("the M11 intruder must move the centroid by a usable distance")
                .isGreaterThan(1.0);

        assertThat(kmBetween(M1_GUARDED_LAT, M1_GUARDED_LON, MCR_BEYOND_LAT, MCR_BEYOND_LON))
                .as("MCR_BEYOND must be OUTSIDE the radius of the correct centroid")
                .isGreaterThan(RADIUS_KM);
        assertThat(kmBetween(M1_UNGUARDED_LAT, M1_UNGUARDED_LON, MCR_BEYOND_LAT, MCR_BEYOND_LON))
                .as("...and INSIDE the radius of the wrong one, so deleting the guard changes the SET")
                .isLessThan(RADIUS_KM);
        Double seededLatitude = jdbc.queryForObject(
                "SELECT latitude FROM shops WHERE slug = ?", Double.class, MCR_CENTRE);
        Double seededLongitude = jdbc.queryForObject(
                "SELECT longitude FROM shops WHERE slug = ?", Double.class, MCR_CENTRE);
        assertThat(kmBetween(M1_GUARDED_LAT, M1_GUARDED_LON, seededLatitude, seededLongitude))
                .as("MCR_CENTRE, read back out of the DATABASE, sits on the correct centroid")
                .isLessThan(0.001);
    }

    @Test
    @DisplayName("CA-F: an M1 search is not pulled toward M11's units — the length guard is real")
    void m1SearchExcludesTheM11District() throws Exception {
        JsonNode page = search("M1");

        // Asserted on WHICH SHOPS come back, not on the centroid's decimals: an arithmetic
        // coincidence cannot satisfy a set membership claim. With the guard deleted the centroid
        // drifts ~1.5 km ESE and MCR_BEYOND enters the radius.
        assertThat(slugsOf(page))
                .as("only the shop on the true M1 centroid is within 5 km of it")
                .containsExactly(MCR_CENTRE);

        mockMvc.perform(get("/public/shops").param("q", "M1"))
                .andExpect(header().string(HEADER,
                        "proximity; postcode=M1; precision=district; radiusKm=5.0"));
    }

    // =====================================================================================
    // 5. CA-A — the fall-through is byte-identical
    // =====================================================================================

    @Test
    @DisplayName("CA-A: an ordinary food search is unchanged — the FTS page, header 'text', and "
            + "distanceKm NULL on every shop")
    void ordinaryTextSearchIsUnchanged() throws Exception {
        JsonNode page = search("jollof");

        assertThat(slugsOf(page)).containsExactly(JOLLOF);

        // The null sweep is what proves tier 3 did not run. JOLLOF HAS coordinates, so a null
        // distance here is a statement about the code path, not about missing data.
        page.path("content").forEach(node ->
                assertThat(node.path("distanceKm").isNull())
                        .as("a text result must carry no distance")
                        .isTrue());

        Double seededLatitude = jdbc.queryForObject(
                "SELECT latitude FROM shops WHERE slug = ?", Double.class, JOLLOF);
        assertThat(seededLatitude)
                .as("CONTROL: the shop DOES have a coordinate, so the null above is the path's doing")
                .isNotNull();

        mockMvc.perform(get("/public/shops").param("q", "jollof"))
                .andExpect(header().string(HEADER, "text"));
    }

    // =====================================================================================
    // 6. Northern Ireland — Code-Point Open is GB-only
    // =====================================================================================

    @Test
    @DisplayName("a Northern Ireland postcode falls through to text — never a proximity claim on a "
            + "branch that did not apply")
    void northernIrelandPostcodeFallsThroughToText() throws Exception {
        mockMvc.perform(get("/public/shops").param("q", "BT1 5GS"))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER, "text"))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    // =====================================================================================
    // 7. A postcode plus words is a text search — what the both-ends anchor buys
    // =====================================================================================

    @Test
    @DisplayName("'SE22 pizza' is a text search, not a proximity one")
    void postcodePlusWordsIsATextSearch() throws Exception {
        // SE22 on its own resolves (arm 1), so this is not passing because SE22 is unknown — the
        // difference is entirely the extra word, i.e. the leading/trailing anchor.
        mockMvc.perform(get("/public/shops").param("q", "SE22 pizza"))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER, "text"))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    // =====================================================================================
    // 8. No unpublished shop leaks — in content OR in the total
    // =====================================================================================

    @Test
    @DisplayName("the unpublished shop on the nearest shop's exact coordinates is absent from the "
            + "content AND from the total, at a page size that actually issues the count query")
    void unpublishedShopLeaksNeitherIntoContentNorIntoTheTotal() throws Exception {
        // PAGE SIZE 2 IS LOAD-BEARING. PageableExecutionUtils.getPage skips the countQuery when
        // the page size exceeds the content size, so at size=20 the total would silently
        // re-measure the content and this assertion would be incapable of failing (33-06).
        String body = mockMvc.perform(get("/public/shops").param("q", "SE22").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(SE22_UNPUB);

        // Non-vacuity: the unpublished shop really is in the table, on coordinates that put it
        // FIRST if published were ignored. Without this the arm would pass over an empty table.
        Integer unpublishedRows = jdbc.queryForObject(
                "SELECT count(*) FROM shops WHERE slug = ? AND published = false", Integer.class, SE22_UNPUB);
        assertThat(unpublishedRows).as("the hidden shop must actually exist to be hidden").isEqualTo(1);
        assertThat(kmBetween(SE22_LAT, SE22_LON, SE22_NEAR_LAT, SE22_LON))
                .as("and it sits inside the radius, so only the predicate excludes it")
                .isLessThan(RADIUS_KM);
    }

    // =====================================================================================
    // 9. The result spans more than one tenant
    // =====================================================================================

    @Test
    @DisplayName("with NO tenant GUC the postcode result spans more than one tenant")
    void postcodeResultStaysCrossTenant() throws Exception {
        assertThat(TenantContext.get())
                .as("precondition: the request is genuinely anonymous")
                .isEmpty();

        List<String> slugs = slugsOf(search("SE22"));

        // Slugs come out of the RESPONSE, tenant ids out of the DATABASE, so this cannot pass by
        // asserting the fixture against itself. ">= 2", never "> 0": a single-tenant result would
        // satisfy "some shops came back" while a stray tenant filter halved the directory.
        List<UUID> tenantIds = jdbc.queryForList(
                "SELECT DISTINCT tenant_id FROM shops WHERE slug IN (?, ?, ?)",
                UUID.class, slugs.get(0), slugs.get(1), slugs.get(2));
        assertThat(tenantIds).hasSizeGreaterThanOrEqualTo(2)
                .containsExactlyInAnyOrder(tenantA, tenantB);
    }

    // =====================================================================================
    // 10. The q + coordinate guard is untouched
    // =====================================================================================

    @Test
    @DisplayName("q combined with a caller-supplied coordinate is STILL a typed 400 on $.type")
    void queryWithCoordinateIsStillATypedBadRequest() throws Exception {
        // A postcode inside q is a coordinate the SERVER derived; this guard is about one the
        // CALLER supplied alongside it. Asserting $.type, not merely the status — a bare 400
        // tells a machine consumer nothing.
        mockMvc.perform(get("/public/shops")
                        .param("q", "SE22").param("lat", "51.47").param("lon", "-0.07"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/invalid-argument"));
    }

    // =====================================================================================
    // 11. A hostile q reaches neither the regex nor the database
    // =====================================================================================

    @Test
    @DisplayName("a 400-character q is answered by the text page and never reaches the geocoder "
            + "(T-33-08-01)")
    void anOverLongQueryIsHarmless() throws Exception {
        String hostile = "a".repeat(400);

        mockMvc.perform(get("/public/shops").param("q", hostile))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER, "text"))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    // =====================================================================================
    // Regression: the shipped healthy behaviours 33-07 measured live
    // =====================================================================================

    @Test
    @DisplayName("REGRESSION: the plain listing carries no interpretation header at all")
    void plainListingCarriesNoHeader() throws Exception {
        mockMvc.perform(get("/public/shops"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HEADER));

        mockMvc.perform(get("/public/shops").param("lat", "51.47").param("lon", "-0.07"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HEADER));
    }
}
