package uk.jtoye.core.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.StorageService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-10 / T-27-01 — the load-bearing security proof for 27-04, and the reason the concurrency
 * raise is safe to ship (issue <b>#284</b>).
 *
 * <p><b>Why this test has to exist.</b> A {@code @RabbitListener} thread carries no ambient
 * identity: Spring propagates no SecurityContext onto it, so the explicit tenant-GUC pin in
 * {@link MediaProcessingWorker} is the <em>entire</em> control keeping one tenant's media out of
 * another's. Before 27-04 a weakened pin would leak on one thread. After it, the media container
 * is configured {@code concurrentConsumers=1, maxConcurrentConsumers=2} and Spring AMQP scales up
 * under sustained backlog with no operator action — so the same weakening would leak on N. This
 * test is what makes that regression detectable.
 *
 * <p><b>Deliberately NOT {@code @Transactional}.</b> Every other worker test in this package is,
 * and that is correct for them — but a test-managed transaction is never shared with the threads
 * spawned below, so a {@code @Transactional} version of this test would silently prove nothing
 * about concurrency. Each worker invocation here takes its own transaction and its own pooled
 * connection, which is exactly the production shape.
 *
 * <p><b>Two assertions, and (b) is the one that can fail.</b>
 * <ol>
 *   <li>(a) No asset is written or read under the wrong tenant, with the DB role downgraded to
 *       {@code NOSUPERUSER NOBYPASSRLS} so RLS is genuinely enforced rather than bypassed by the
 *       Testcontainers superuser (the {@code IntegrationTestSupport} RLS caveat).</li>
 *   <li>(b) At the <em>start</em> of each worker transaction, on a connection that has been
 *       returned to and re-issued from the Hikari pool, {@code current_setting('app.current_tenant_id',
 *       true)} is <b>empty</b>. This is the direct check on {@code set_config(..., is_local = true)}
 *       being transaction-scoped. Without it the test is close to vacuous: assertion (a) alone
 *       passes even with a session-scoped pin, because every worker re-pins before it reads.</li>
 * </ol>
 *
 * <p><b>The break arm is an OMITTED pin, not a flipped {@code is_local}.</b> The draft criterion
 * proposed flipping the pin to {@code is_local = false} — but the worker re-pins unconditionally
 * before any query, so a leaked session value is overwritten before it can be read and the test
 * passes with {@code false}. That criterion could not fail. Deleting the {@code session.doWork(...)}
 * block on one of the two interleaved consumers is what actually breaks isolation.
 *
 * <p><b>Fail-closed.</b> An empty result set is VOID, never clean: if the probe collects no
 * transaction-start readings at all, {@link #assertProbeNonVacuous} fails rather than letting
 * "found nothing" read as "found nothing wrong".
 *
 * <h2>STATUS: PASSES, BUT NOT YET FALSIFIED — DO NOT TREAT AC-10 AS SATISFIED</h2>
 *
 * <p>This test is green, and it must not be trusted on that basis. <b>Three successive break arms
 * were run and the test passed through all three</b>, which means it cannot presently fail and is
 * therefore not yet evidence of anything:
 *
 * <ol>
 *   <li><b>Omit the explicit {@code session.doWork} GUC pin</b> — the break the 27-04 plan
 *       prescribes. Still GREEN. Cause: {@code TenantSetLocalAspect} pins the GUC from
 *       {@link TenantContext} before every repository call, so the explicit pin is redundant and
 *       removing it changes nothing. The plan's prescribed break arm is itself vacuous here.</li>
 *   <li><b>Set {@link TenantContext} to a random UUID</b> — still GREEN. Cause: the explicit
 *       {@code set_config} pins {@code event.tenantId()} <em>directly</em>, not
 *       {@code TenantContext.get()}, so it re-establishes the correct tenant regardless.</li>
 *   <li><b>Break BOTH pins at once</b> — still GREEN. This is the one that proves the problem is
 *       in the harness, not the worker.</li>
 * </ol>
 *
 * <p><b>Leading hypothesis for (3):</b> {@code ALTER ROLE … NOSUPERUSER} applies to <em>new</em>
 * sessions; the Hikari pool has already established its connections, and those sessions keep
 * superuser attributes for their lifetime. So the workers very likely still bypass FORCE RLS and
 * assertion (a) is passing vacuously. Next step is to force the pool to re-establish connections
 * after the downgrade (evict/soft-evict, or downgrade before the context's first connection), then
 * re-run break arm (3) and require RED.
 *
 * <p>Worth keeping regardless of that outcome: the worker has <b>two independent tenant pins</b>
 * (the aspect via {@code TenantContext}, and the explicit {@code set_config} via
 * {@code event.tenantId()}). A single-point regression in either is already tolerated — which is a
 * stronger safety property than 27-04's threat model assumed, and is why break arms (1) and (2)
 * could not fail.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class MediaTenantIsolationUnderConcurrencyIntegrationTest {

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
    /** Assets per tenant. Enough that the two tenants' work genuinely interleaves. */
    private static final int PER_TENANT = 6;

    @Autowired private MediaProcessingWorker worker;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private StorageService storageService;

    private UUID tenantA;
    private UUID tenantB;

    /** Transaction-start GUC readings, one per worker invocation (assertion (b)). */
    private final List<String> gucAtTransactionStart = java.util.Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void seed() throws Exception {
        jdbc.execute("DO $$ BEGIN "
                + "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN "
                + "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; "
                + "    GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE + "; "
                + "    GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE + "; "
                + "    GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE + "; "
                + "  END IF; "
                + "END $$");

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        for (UUID t : List.of(tenantA, tenantB)) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                    t, "test-" + t);
        }

        byte[] jpeg = jpegOf(600, 400);
        Mockito.doReturn(jpeg).when(storageService).getBytes(Mockito.anyString());
        Mockito.doReturn("http://minio/derivative").when(storageService)
                .putBytes(Mockito.anyString(), Mockito.any(byte[].class), Mockito.anyString());
        Mockito.doNothing().when(storageService).deleteByKey(Mockito.anyString());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        // Restore superuser so the next test class in this fork can seed. The container is
        // per-class static but the ROLE change is global to the database.
        try {
            jdbc.execute("ALTER ROLE \"" + postgres.getUsername() + "\" SUPERUSER");
        } catch (RuntimeException ignored) {
            // Already superuser, or the test failed before the downgrade — neither is a problem.
        }
    }

    @Test
    void twoTenantsInterleavedAcrossConcurrentConsumersNeverCrossTheTenantWall() throws Exception {
        // Seed PER_TENANT PENDING assets for each tenant, recording which tenant owns which asset.
        java.util.Map<UUID, UUID> ownerByAsset = new ConcurrentHashMap<>();
        // One product PER ASSET, deliberately. Sharing a product would make every asset target the
        // same product_media slot, and the copy-on-write placement releases the displaced asset —
        // a physical delete at ref-count 0. Six assets on one slot therefore leave one or two rows,
        // and the count assertion below would fail for a reason that has nothing to do with tenant
        // isolation. (Measured: 200 uploads to a single product leave exactly 1 live asset.)
        for (UUID t : List.of(tenantA, tenantB)) {
            for (int i = 0; i < PER_TENANT; i++) {
                ownerByAsset.put(insertPendingAsset(t, seedProduct(t)), t);
            }
        }

        // Interleave the two tenants' events so concurrent consumers are genuinely handling
        // DIFFERENT tenants at the same moment — the condition under test. A per-tenant batch
        // would let each thread see only one tenant and prove much less.
        List<java.util.Map.Entry<UUID, UUID>> interleaved = new ArrayList<>(ownerByAsset.entrySet());
        interleaved.sort((x, y) -> 0); // stable; the alternation comes from the shuffle below
        java.util.Collections.shuffle(interleaved, new java.util.Random(42));

        // DOWNGRADE THE WORKERS' OWN ROLE — seeding is done, so RLS can now bite.
        //
        // This line is what makes assertion (a) mean anything, and it was missing in the first
        // draft: the Testcontainers bootstrap role is a Postgres SUPERUSER, which bypasses even
        // FORCE ROW LEVEL SECURITY. Creating rls_test_role and using it only for the read-back
        // left the WORKERS running as superuser, so they saw every tenant's rows regardless of
        // the GUC and the test passed even with the tenant pin deliberately broken. Caught by
        // running the break arm — which is the entire reason the break arm is mandatory.
        jdbc.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");

        // ---- NON-VACUITY GUARD ON THE READ-BACK INSTRUMENT ITSELF -----------------------------
        // Every seeded asset is PENDING at this instant and no worker has run, so the instrument
        // used for the terminal assertion MUST see PER_TENANT PENDING rows per tenant right here.
        // If it cannot see them now, a later "nothing is PENDING" reading is blindness, not
        // success. This guard exists because the original terminal check was exactly that blind:
        // it counted PENDING rows on an UNTRANSACTED connection with no tenant GUC pinned, so once
        // the role above is downgraded RLS filters every row and the count is structurally 0.
        // Measured on the real tree: `expected: 12 but was: 0` with all 12 assets PENDING.
        for (UUID t : List.of(tenantA, tenantB)) {
            assertThat(visibleAssetsAsTenant(t))
                    .as("read-back instrument must SEE tenant %s's %d PENDING assets before any "
                            + "worker runs — otherwise the terminal assertion cannot fail", t, PER_TENANT)
                    .hasSize(PER_TENANT)
                    .allSatisfy(r -> assertThat(r.status()).isEqualTo("PENDING"));
        }

        // 2 threads = the shipped maxConcurrentConsumers ceiling.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            for (java.util.Map.Entry<UUID, UUID> e : interleaved) {
                UUID assetId = e.getKey();
                UUID owner = e.getValue();
                pool.submit(() -> {
                    try {
                        start.await();
                        // Assertion (b): read the GUC BEFORE the worker's own transaction pins it.
                        // Because worker.onMediaEvent is @Transactional, this reading is taken on a
                        // connection the pool has already issued to earlier work in this run.
                        gucAtTransactionStart.add(readGucOnAPooledConnection());
                        worker.onMediaEvent(new MediaProcessingEvent(owner, assetId));
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(120, TimeUnit.SECONDS))
                    .as("workers must finish; a timeout is a broken harness, not evidence about isolation")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures)
                .as("no worker may throw — an exception here masks whatever the isolation result was")
                .isEmpty();

        // ---- assertion (b): the pin is transaction-scoped ------------------------------------
        assertProbeNonVacuous();
        assertThat(gucAtTransactionStart)
                .as("app.current_tenant_id must be EMPTY at the start of every worker transaction. A "
                        + "non-empty reading means a previous transaction's tenant rode a recycled "
                        + "Hikari connection into this one — the exact leak set_config(..., true) prevents")
                .allSatisfy(v -> assertThat(v).isEmpty());

        // ---- assertion (a): no asset crossed the tenant wall, and every one WAS processed ------
        // Read back under the DOWNGRADED role so RLS is enforced, once per tenant, THROUGH THE
        // TENANT-PINNED PATH — the same instrument the guard above proved can see PENDING rows.
        for (UUID t : List.of(tenantA, tenantB)) {
            List<AssetRow> visible = visibleAssetsAsTenant(t);
            assertThat(visible)
                    .as("tenant %s must see exactly its own %d assets under NOBYPASSRLS", t, PER_TENANT)
                    .hasSize(PER_TENANT);
            for (AssetRow seen : visible) {
                assertThat(ownerByAsset.get(seen.id()))
                        .as("asset %s surfaced for tenant %s but belongs to another tenant", seen.id(), t)
                        .isEqualTo(t);
            }

            // The processing-sensitive half, and the only part of this test that a broken pin can
            // move. An asset processed under the WRONG tenant is invisible to its worker (RLS), so
            // MediaProcessingWorker takes the `asset_not_visible` early return — it logs a WARN and
            // returns without throwing, leaving the row PENDING. Nothing else in this test observes
            // that: the worker never rewrites tenant_id, so the ownership loop above holds either
            // way, and no exception reaches `failures`. A terminal status is therefore the evidence
            // that the asset was handled under its own tenant.
            assertThat(visible.stream().filter(r -> "PENDING".equals(r.status())).map(AssetRow::id).toList())
                    .as("these assets of tenant %s are still PENDING — their workers could not SEE "
                            + "them, i.e. ran under the wrong tenant. That is the isolation failure "
                            + "this test exists to catch", t)
                    .isEmpty();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * Reads {@code app.current_tenant_id} on a connection drawn from the same Hikari pool the
     * workers use. Returns "" when unset — {@code current_setting(..., true)} yields NULL for a
     * never-set GUC and empty string once a transaction that set it has ended.
     */
    private String readGucOnAPooledConnection() {
        String v = jdbc.queryForObject(
                "SELECT COALESCE(current_setting('app.current_tenant_id', true), '')", String.class);
        return v == null ? "" : v;
    }

    /**
     * An empty probe is VOID, not clean. If no readings were collected the {@code allSatisfy}
     * below would pass trivially over an empty list — "found nothing" reported as "nothing wrong".
     */
    private void assertProbeNonVacuous() {
        assertThat(gucAtTransactionStart)
                .as("the transaction-start GUC probe collected NO readings — this arm is VOID, not passing")
                .hasSize(PER_TENANT * 2);
    }

    /** One {@code media_asset} row as the tenant-scoped read-back sees it. */
    private record AssetRow(UUID id, String status) {}

    /**
     * Reads the assets visible to {@code tenant} with the role downgraded so RLS applies, WITH the
     * tenant GUC pinned for the read. The pin is not optional bookkeeping: without it
     * {@code current_tenant_id()} is NULL and the policy filters every row, so an unpinned read
     * returns zero rows and cannot distinguish "clean" from "blind".
     */
    private List<AssetRow> visibleAssetsAsTenant(UUID tenant) {
        return new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        java.util.Objects.requireNonNull(jdbc.getDataSource())))
                .execute(status -> {
                    // No `SET LOCAL ROLE` here. The datasource role itself was downgraded to
                    // NOSUPERUSER before the workers ran, so RLS already applies to this
                    // connection. An earlier draft did SET LOCAL ROLE to a separate rls_test_role
                    // and broke: once "test" is NOSUPERUSER it is not a member of that role, so
                    // the statement fails with BadSqlGrammarException — and the break arm then
                    // went RED for a HARNESS reason instead of the isolation assertion, which is
                    // not evidence of anything.
                    //
                    // queryForObject, NOT update(): `SELECT set_config(...)` RETURNS a row, and
                    // JdbcTemplate.update rejects that with "A result was returned when none was
                    // expected". The worker uses PreparedStatement.execute(), which tolerates both.
                    jdbc.queryForObject("SELECT set_config('app.current_tenant_id', ?, true)",
                            String.class, tenant.toString());
                    return jdbc.query("SELECT id, status FROM media_asset",
                            (rs, i) -> new AssetRow(rs.getObject("id", UUID.class), rs.getString("status")));
                });
    }

    private UUID seedProduct(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, sku, title, ingredients_text) VALUES (?, ?, ?, ?, ?)",
                id, tenant, "SKU-" + id.toString().substring(0, 8), "Product", "Yam (100%)");
        return id;
    }

    private UUID insertPendingAsset(UUID tenant, UUID productId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset "
                        + "(id, tenant_id, object_key, sha256, content_type, status, product_id, is_primary, sort_order) "
                        + "VALUES (?, ?, ?, ?, 'image/jpeg', 'PENDING', ?, false, 0)",
                id, tenant, tenant + "/quarantine/" + id + ".jpg", randomSha(), productId);
        return id;
    }

    private static String randomSha() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private static byte[] jpegOf(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.ORANGE, w, h, Color.BLUE));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
