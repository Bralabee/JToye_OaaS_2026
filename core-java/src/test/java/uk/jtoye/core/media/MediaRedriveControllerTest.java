package uk.jtoye.core.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.JwtRolesAndScopesConverter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 4 (27-01 / D-04, D-10) — {@code POST /api/v1/media/{assetId}/reprocess}, the recovery half
 * of the durability trade V60 makes, plus the two derived DTO bits and the D-10 review-queue
 * widening that make it discoverable.
 *
 * <h2>Why the whole application datasource is downgraded to NOSUPERUSER</h2>
 * AC-4.3 ("a foreign-tenant asset is 404, never 403") is <b>unfalsifiable under the Testcontainers
 * superuser</b>, and worse than unfalsifiable: superusers bypass FORCE RLS, so {@code findById}
 * WOULD find tenant B's asset, the shop gate would then decide, and the criterion would report
 * 202-or-403 <em>on a correct tree</em> — the "expected-0 that is 1 on the correct tree" shape.
 * The wall has to be real for the assertion to mean anything, so this class reuses the mechanism
 * {@code MediaSweepTenantScopeIntegrationTest} landed for AC-3.6: the application datasource
 * authenticates as a NOSUPERUSER/NOBYPASSRLS role from its first connection while <b>Flyway keeps
 * the superuser</b>, with {@code ALTER DEFAULT PRIVILEGES} issued before context start so every
 * table Flyway creates is auto-granted. Seeding uses a SEPARATE superuser template, because the app
 * datasource is RLS-bound and could not insert another tenant's row even to set the fixture up.
 * {@link #assertDowngradeIsReal()} is the VOID guard and probes the INJECTED datasource — never a
 * hand-rolled connection, which is the trap that made AC-3.6's first guard decorative.
 *
 * <p>Deliberately NOT {@code @Transactional}: AC-4.2's replay must observe the FIRST request's
 * COMMITTED idempotency reservation, which a rolled-back test transaction would hide.
 *
 * <p>The three scheduled media jobs are pushed far into the future here. The reaper would otherwise
 * be free to flip the deliberately-stale PENDING fixture (AC-4.8) to FAILED mid-test, and the
 * outbox flusher would churn {@code attempts} against the dead-port broker under the very rows the
 * outbox assertions count. Neither is under test; both are sources of flake.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class MediaRedriveControllerTest {

    private static final String REDRIVE_ROLE = "rls_redrive_role";
    private static final String REDRIVE_PW = "rls_redrive_pw";

    /** Matches the {@code jtoye.media.max-process-attempts} default asserted by AC-4.6. */
    private static final int MAX_ATTEMPTS = 3;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        try (Connection su = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = su.createStatement()) {
            st.execute("DO $$ BEGIN "
                    + "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + REDRIVE_ROLE + "') THEN "
                    + "    CREATE ROLE " + REDRIVE_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN PASSWORD '"
                    + REDRIVE_PW + "'; "
                    + "  END IF; "
                    + "END $$");
            st.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + REDRIVE_ROLE);
            st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + postgres.getUsername()
                    + " IN SCHEMA public GRANT ALL ON TABLES TO " + REDRIVE_ROLE);
            st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + postgres.getUsername()
                    + " IN SCHEMA public GRANT ALL ON SEQUENCES TO " + REDRIVE_ROLE);
        } catch (SQLException e) {
            throw new IllegalStateException("could not provision the downgraded role", e);
        }

        // Layer ON the shared helper, never replace it — it also points the H2-defaulted
        // application-test.yml at Postgres and disables the broker. Later add() calls win.
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);

        registry.add("spring.datasource.username", () -> REDRIVE_ROLE);
        registry.add("spring.datasource.password", () -> REDRIVE_PW);
        // Flyway keeps the SUPERUSER — the downgraded role must not own the schema, or FORCE RLS
        // would apply to it as owner and the migrations themselves would be filtered.
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);

        // Park the scheduled media jobs (see class javadoc).
        registry.add("jtoye.media.reaper-interval-ms", () -> "86400000");
        registry.add("jtoye.media.retention-interval-ms", () -> "86400000");
        registry.add("media.outbox.flush-interval-ms", () -> "86400000");
        registry.add("media.outbox.resurrect-interval-ms", () -> "86400000");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private javax.sql.DataSource dataSource;
    @Autowired private ShopAccessService shopAccessService;

    /** Superuser template — the app datasource is RLS-bound and cannot seed another tenant's row. */
    private JdbcTemplate su;

    private ShopAccessService targetService;
    private UUID tenantA;
    private UUID tenantB;
    private UUID shopA;
    private UUID productA;
    private int seq;

    @BeforeEach
    void seed() {
        su = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        seq = 0;
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        for (UUID t : new UUID[]{tenantA, tenantB}) {
            su.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                    t, "redrive-" + t);
        }
        shopA = seedShop(tenantA);
        productA = seedProduct(tenantA, shopA);
    }

    @AfterEach
    void cleanUp() {
        setStrictScoping(false);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // --- AC-4.1 -------------------------------------------------------------

    @Test
    @DisplayName("AC-4.1: a retained FAILED asset re-drives to PENDING with a fresh outbox row")
    void redriveReturns202AndReQueues() throws Exception {
        assertDowngradeIsReal();
        UUID asset = seedAsset(tenantA, productA, "FAILED", retained(), null, 0, "dispatch stalled");

        mockMvc.perform(post("/api/v1/media/{assetId}/reprocess", asset)
                        .header("Idempotency-Key", key()).with(vendorJwt(tenantA)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.assetId").value(asset.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(statusOf(asset)).as("the asset is back in the pipeline").isEqualTo("PENDING");
        assertThat(intOf("SELECT process_attempts FROM media_asset WHERE id = ?", asset))
                .as("the human re-drive is counted against the T-27-03 budget").isEqualTo(1);
        assertThat(su.queryForObject("SELECT failure_reason FROM media_asset WHERE id = ?", String.class, asset))
                .as("the stale rejection copy is cleared").isNull();
        // The load-bearing half: a status flip WITHOUT an enqueued event is a row that sits in
        // PENDING forever. The status assertion alone would still pass with the outbox save deleted.
        assertThat(outboxCount(asset)).as("exactly one fresh media_event_outbox row").isEqualTo(1);
    }

    // --- AC-4.2 -------------------------------------------------------------

    @Test
    @DisplayName("AC-4.2: a same-key replay echoes the original response and does not double-enqueue")
    void replayWithSameKeyReturnsOriginalAndDoesNotDoubleEnqueue() throws Exception {
        assertDowngradeIsReal();
        UUID asset = seedAsset(tenantA, productA, "FAILED", retained(), null, 0, "dispatch stalled");
        String key = key();

        MvcResult first = mockMvc.perform(post("/api/v1/media/{assetId}/reprocess", asset)
                        .header("Idempotency-Key", key).with(vendorJwt(tenantA)))
                .andExpect(status().isAccepted()).andReturn();
        MvcResult second = mockMvc.perform(post("/api/v1/media/{assetId}/reprocess", asset)
                        .header("Idempotency-Key", key).with(vendorJwt(tenantA)))
                .andExpect(status().isAccepted()).andReturn();

        assertThat(assetIdOf(second)).as("the replay echoes the ORIGINAL response body")
                .isEqualTo(assetIdOf(first));
        assertThat(outboxCount(asset)).as("a retried click enqueues once, not twice").isEqualTo(1);
        assertThat(intOf("SELECT process_attempts FROM media_asset WHERE id = ?", asset))
                .as("the replay does not spend a second attempt from the budget").isEqualTo(1);
    }

    // --- AC-4.3 -------------------------------------------------------------

    @Test
    @DisplayName("AC-4.3: a foreign-tenant asset is 404, never a 403 oracle")
    void foreignTenantAssetIs404NotAnOracle() throws Exception {
        assertDowngradeIsReal();
        UUID shopB = seedShop(tenantB);
        UUID productB = seedProduct(tenantB, shopB);
        UUID assetB = seedAsset(tenantB, productB, "FAILED", retained(), null, 0, "dispatch stalled");

        MvcResult result = mockMvc.perform(post("/api/v1/media/{assetId}/reprocess", assetB)
                        .header("Idempotency-Key", key()).with(vendorJwt(tenantA)))
                .andExpect(status().isNotFound())
                .andReturn();

        // A 403 here would confirm the id exists somewhere and name the shop that owns it. The
        // 404 body must not leak either — hence asserting on the payload, not only the status.
        String body = result.getResponse().getContentAsString();
        assertThat(body).as("the 404 body names no shop").doesNotContain(shopB.toString());
        assertThat(body).as("the 404 body names no product").doesNotContain(productB.toString());
        assertThat(statusOf(assetB)).as("the foreign asset was not mutated").isEqualTo("FAILED");
        assertThat(outboxCount(assetB)).as("no event was enqueued for the foreign asset").isZero();
    }

    // --- AC-4.4 -------------------------------------------------------------

    @Test
    @DisplayName("AC-4.4: a SHOP_MANAGER of a different shop in the same tenant is 403")
    void nonManagerOfOwningShopIs403() throws Exception {
        assertDowngradeIsReal();
        UUID otherShop = seedShop(tenantA);
        UUID asset = seedAsset(tenantA, productA, "FAILED", retained(), null, 0, "dispatch stalled");

        UUID sm = UUID.randomUUID();
        grantShopStaff(tenantA, sm, otherShop);
        setStrictScoping(true);   // without this a JIT tenant-wide GROUP_ADMIN would pass

        mockMvc.perform(post("/api/v1/media/{assetId}/reprocess", asset)
                        .header("Idempotency-Key", key()).with(vendorJwt(tenantA, sm)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/shop-access-denied"));

        assertThat(statusOf(asset)).as("the denied re-drive never committed").isEqualTo("FAILED");
        assertThat(outboxCount(asset)).as("the denied re-drive enqueued nothing").isZero();
    }

    // --- AC-4.5 -------------------------------------------------------------

    @Test
    @DisplayName("AC-4.5: no retained bytes is a typed 409 — both halves independently")
    void assetWithNoRetainedBytesIsTyped409() throws Exception {
        assertDowngradeIsReal();
        // Half 1 — the bytes were never claimed (every pre-V60 row, every V53 backfill).
        UUID neverClaimed = seedAsset(tenantA, productA, "FAILED", null, null, 0, "legacy failure");
        // Half 2 — the bytes WERE claimed and have since been reclaimed (swept, or discarded by a
        // worker validation veto). Identical outcome, different history.
        UUID reclaimed = seedAsset(tenantA, productA, "FAILED", retained(), OffsetDateTime.now(), 0, "not an image");

        for (UUID asset : new UUID[]{neverClaimed, reclaimed}) {
            mockMvc.perform(post("/api/v1/media/{assetId}/reprocess", asset)
                            .header("Idempotency-Key", key()).with(vendorJwt(tenantA)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/media-quarantine-not-retained"))
                    .andExpect(jsonPath("$.code").value("media.quarantine_not_retained"));

            // The concrete harm the guard prevents: a bytes-gone asset left PENDING with an
            // enqueued event the worker can only fail on a missing object.
            assertThat(statusOf(asset)).as("a rejected re-drive leaves the asset terminal").isEqualTo("FAILED");
            assertThat(outboxCount(asset)).as("a rejected re-drive enqueues nothing").isZero();
        }
    }

    // --- AC-4.6 -------------------------------------------------------------

    @Test
    @DisplayName("AC-4.6: the re-drive budget is enforced (T-27-03)")
    void redriveBudgetExhaustedIsTyped409() throws Exception {
        assertDowngradeIsReal();
        UUID asset = seedAsset(tenantA, productA, "FAILED", retained(), null, MAX_ATTEMPTS, "still broken");

        mockMvc.perform(post("/api/v1/media/{assetId}/reprocess", asset)
                        .header("Idempotency-Key", key()).with(vendorJwt(tenantA)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/media-redrive-budget-exhausted"))
                .andExpect(jsonPath("$.code").value("media.redrive_budget_exhausted"));

        assertThat(outboxCount(asset)).as("an exhausted budget enqueues no further work").isZero();
        assertThat(intOf("SELECT process_attempts FROM media_asset WHERE id = ?", asset))
                .as("the rejected attempt did not increment the counter").isEqualTo(MAX_ATTEMPTS);
    }

    // --- AC-4.8 -------------------------------------------------------------

    @Test
    @DisplayName("AC-4.8: redrivable + delayed reach the wire and the queue carries the stalled row")
    void redrivableAndDelayedReachTheWire() throws Exception {
        assertDowngradeIsReal();
        // (1) reaper-failed, bytes retained -> recoverable in place.
        UUID reaperFailed = seedAsset(tenantA, productA, "FAILED", retained(), null, 0, "dispatch stalled");
        // (2) worker-vetoed, bytes discarded -> NOT recoverable; the vendor must re-upload.
        UUID workerVetoed = seedAsset(tenantA, productA, "FAILED", retained(), OffsetDateTime.now(), 0, "not an image");
        // (3) a 30-minute-old PENDING — past the 15-minute reaper grace, so visibly stalled. Before
        //     D-10 this row appeared in NO queue at all; it was a spinner on one product page.
        UUID stalledPending = seedAsset(tenantA, productA, "PENDING", retained(), null, 0, null);
        ageTo(stalledPending, OffsetDateTime.now().minusMinutes(30));

        mockMvc.perform(get("/api/v1/media/review-queue").with(vendorJwt(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.assetId=='" + reaperFailed + "')].redrivable").value(true))
                .andExpect(jsonPath("$[?(@.assetId=='" + reaperFailed + "')].delayed").value(false))
                .andExpect(jsonPath("$[?(@.assetId=='" + workerVetoed + "')].redrivable").value(false))
                .andExpect(jsonPath("$[?(@.assetId=='" + stalledPending + "')].delayed").value(true))
                .andExpect(jsonPath("$[?(@.assetId=='" + stalledPending + "')].redrivable").value(true));
    }

    // --- VOID guard ---------------------------------------------------------

    /**
     * Without this, every RLS-dependent assertion in this class holds whatever the code does —
     * a superuser sees through FORCE RLS, so AC-4.3 could neither pass for the right reason nor
     * fail for one. Probes the INJECTED datasource: a hand-rolled {@code DriverManager} connection
     * using the downgraded role's own credentials always observes the downgrade and proves nothing
     * about the datasource under test (the defect AC-3.6's VOID arm exposed).
     */
    private void assertDowngradeIsReal() {
        String user;
        boolean superuser;
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_user, current_setting('is_superuser')::boolean")) {
            rs.next();
            user = rs.getString(1);
            superuser = rs.getBoolean(2);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(user).as("VOID: the app datasource is not the downgraded role").isEqualTo(REDRIVE_ROLE);
        assertThat(superuser).as("VOID: still superuser — RLS does not apply").isFalse();

        // And prove the wall actually FILTERS: an unpinned read on the app datasource must return
        // zero rows even though the superuser template can see the seeded fixtures.
        int unpinned;
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("SELECT set_config('app.current_tenant_id', '', false)");
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM media_asset")) {
                rs.next();
                unpinned = rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(unpinned).as("VOID: FORCE RLS is not filtering — an unpinned read must be empty").isZero();
    }

    // --- seeding + read-back (all through the SUPERUSER template) -----------

    private UUID seedShop(UUID tenant) {
        UUID id = UUID.randomUUID();
        su.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, delivery_fee_pennies, "
                        + "minimum_order_pennies, version) VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                id, tenant, "Shop " + id, "shop-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID seedProduct(UUID tenant, UUID shopId) {
        UUID id = UUID.randomUUID();
        su.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, allergen_mask, "
                        + "price_pennies, display_order, available, featured, shop_id, quantity_in_stock, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, 0, 0)",
                id, tenant, "SKU-" + id.toString().substring(0, 8), "Suya", "beef, spice", shopId);
        return id;
    }

    /**
     * One {@code media_asset} row with the exact V60 durability state under test.
     *
     * @param expiresAt   non-null = the raw quarantine bytes were claimed for a horizon
     * @param reclaimedAt non-null = THE SENTINEL — the quarantine object is gone
     */
    private UUID seedAsset(UUID tenant, UUID productId, String status, OffsetDateTime expiresAt,
                           OffsetDateTime reclaimedAt, int attempts, String failureReason) {
        UUID id = UUID.randomUUID();
        su.update("INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type, status, flagged, "
                        + "failure_reason, product_id, process_attempts, quarantine_expires_at, "
                        + "quarantine_reclaimed_at) "
                        + "VALUES (?, ?, ?, ?, 'image/jpeg', ?, false, ?, ?, ?, ?, ?)",
                id, tenant, tenant + "/quarantine/" + id + ".jpg", String.format("%064d", seq++),
                status, failureReason, productId, attempts, expiresAt, reclaimedAt);
        return id;
    }

    /** {@code created_at} defaults to now(); age a row so it is past the reaper grace (D-10). */
    private void ageTo(UUID assetId, OffsetDateTime when) {
        su.update("UPDATE media_asset SET created_at = ? WHERE id = ?", when, assetId);
    }

    private void grantShopStaff(UUID tenant, UUID userId, UUID shopId) {
        su.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at) "
                        + "VALUES (?, ?, ?, ?, 'SHOP_MANAGER', now())",
                UUID.randomUUID(), tenant, userId, shopId);
    }

    private static OffsetDateTime retained() {
        return OffsetDateTime.now().plusHours(72);
    }

    private String statusOf(UUID assetId) {
        return su.queryForObject("SELECT status FROM media_asset WHERE id = ?", String.class, assetId);
    }

    private int outboxCount(UUID assetId) {
        return intOf("SELECT count(*) FROM media_event_outbox WHERE asset_id = ?", assetId);
    }

    private int intOf(String sql, UUID arg) {
        Integer n = su.queryForObject(sql, Integer.class, arg);
        return n == null ? 0 : n;
    }

    private static String assetIdOf(MvcResult result) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.assetId");
    }

    private static String key() {
        return "redrive-" + UUID.randomUUID();
    }

    // --- auth + strict-scoping plumbing -------------------------------------

    private static RequestPostProcessor vendorJwt(UUID tenant) {
        return vendorJwt(tenant, UUID.randomUUID());
    }

    private static RequestPostProcessor vendorJwt(UUID tenant, UUID subject) {
        return jwt()
                .jwt(j -> j.subject(subject.toString())
                        .claim("tenant_id", tenant.toString())
                        .claim("email", "vendor-" + subject + "@example.com")
                        .claim("scope", "catalog:read catalog:write"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    private void setStrictScoping(boolean value) {
        if (targetService == null) {
            targetService = AopTestUtils.getTargetObject(shopAccessService);
        }
        ReflectionTestUtils.setField(targetService, "strictScoping", value);
    }
}
