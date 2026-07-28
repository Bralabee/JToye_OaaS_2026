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

        // ---- assertion (a): no asset crossed the tenant wall ----------------------------------
        // Read back under the DOWNGRADED role so RLS is enforced, once per tenant.
        for (UUID t : List.of(tenantA, tenantB)) {
            List<UUID> visible = visibleAssetIdsAsRlsRole(t);
            assertThat(visible)
                    .as("tenant %s must see exactly its own %d assets under NOBYPASSRLS", t, PER_TENANT)
                    .hasSize(PER_TENANT);
            for (UUID seen : visible) {
                assertThat(ownerByAsset.get(seen))
                        .as("asset %s surfaced for tenant %s but belongs to another tenant", seen, t)
                        .isEqualTo(t);
            }
        }

        // Every asset must have been processed under its OWN tenant: an asset processed under the
        // wrong tenant would not have been visible to the worker at all (RLS), so it would still
        // be PENDING. A terminal status therefore proves it was handled by the right tenant.
        // Scoped by tenant rather than `id = ANY(?)`: JdbcTemplate treats a UUID[] as the VARARGS
        // parameter list, so the array is expanded into N parameters against a 1-parameter
        // statement ("The column index is out of range: 2, number of columns: 1").
        // These two tenants own only the assets seeded above, so this is equivalent.
        Integer stillPending = jdbc.queryForObject(
                "SELECT count(*) FROM media_asset WHERE tenant_id IN (?, ?) AND status = 'PENDING'",
                Integer.class, tenantA, tenantB);
        assertThat(stillPending)
                .as("an asset left PENDING means its worker could not see it — i.e. it ran under the "
                        + "wrong tenant, which is the isolation failure this test exists to catch")
                .isZero();
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

    /** Reads the asset ids visible to {@code tenant} with the role downgraded so RLS applies. */
    private List<UUID> visibleAssetIdsAsRlsRole(UUID tenant) {
        return new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        java.util.Objects.requireNonNull(jdbc.getDataSource())))
                .execute(status -> {
                    jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
                    // queryForObject, NOT update(): `SELECT set_config(...)` RETURNS a row, and
                    // JdbcTemplate.update rejects that with "A result was returned when none was
                    // expected". The worker uses PreparedStatement.execute(), which tolerates both.
                    jdbc.queryForObject("SELECT set_config('app.current_tenant_id', ?, true)",
                            String.class, tenant.toString());
                    return jdbc.queryForList("SELECT id FROM media_asset", UUID.class);
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
