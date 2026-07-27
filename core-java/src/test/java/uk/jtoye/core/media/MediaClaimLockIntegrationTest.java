package uk.jtoye.core.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-04 / D-04a proofs over real Postgres — the worker's {@code SELECT … FOR UPDATE} claim and the
 * bound on its wait.
 *
 * <p><b>Deliberate deviation from the plan.</b> 27-01 placed AC-2.8 and AC-2.11 in
 * {@code MediaDurabilityIntegrationTest}. They live here instead, because both need a
 * <em>non-</em>{@code @Transactional} class (real concurrent transactions — a test-managed
 * transaction would make two "sessions" share one connection and the lock would be uncontended,
 * which would make both criteria pass vacuously) AND a {@code StorageService} spy that the schema
 * criteria in that class must not inherit. Same coverage, honest fixture.
 *
 * <p><b>VOID discipline.</b> "Session B did not block" is meaningless if nothing was holding the
 * lock, so {@link #assertLockIsGenuinelyHeld} probes from a third connection first and fails the
 * test outright if session A is not actually holding the row.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class MediaClaimLockIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MediaProcessingWorker worker;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private MediaProperties mediaProperties;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private StorageService storageService;

    private UUID tenant;
    private long originalClaimTimeout;

    @BeforeEach
    void seedTenant() {
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "test-" + tenant);
        TenantContext.set(tenant);
        originalClaimTimeout = mediaProperties.getClaimLockTimeoutMs();
        Mockito.doReturn("http://minio/derivative").when(storageService)
                .putBytes(Mockito.anyString(), Mockito.any(byte[].class), Mockito.anyString());
        Mockito.doNothing().when(storageService).deleteByKey(Mockito.anyString());
    }

    @AfterEach
    void clear() {
        mediaProperties.setClaimLockTimeoutMs(originalClaimTimeout);
        jdbc.update("DELETE FROM media_asset_aud WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM product_media WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM media_asset WHERE tenant_id = ?", tenant);
        TenantContext.clear();
    }

    // ==================================================================
    // AC-2.11 — the claim wait is BOUNDED (D-04a)
    // ==================================================================

    @Test
    @DisplayName("AC-2.11: a contended claim fails fast with 55P03 instead of blocking for the holder")
    void claimLockWaitIsBounded() throws Exception {
        mediaProperties.setClaimLockTimeoutMs(500);
        UUID assetId = insertPendingAsset(quarantineKey());
        Mockito.doReturn(jpegOf(600, 400)).when(storageService).getBytes(Mockito.anyString());

        long holdMillis = 6_000;
        try (Holder holder = holdRowLock(assetId, holdMillis)) {
            assertLockIsGenuinelyHeld(assetId);   // VOID guard — never treat "no block" as success

            long start = System.nanoTime();
            Throwable thrown = catchThrowable(() -> worker.onMediaEvent(
                    new MediaProcessingEvent(tenant, assetId)));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(thrown)
                    .as("the loser must fail on the CONFIGURED bound, not block for the holder")
                    .isNotNull();
            assertThat(sqlStateChain(thrown))
                    .as("Postgres lock_not_available — proves SET LOCAL lock_timeout took effect "
                            + "on the very connection the claim is taken on")
                    .contains("55P03");
            assertThat(elapsedMs)
                    .as("bounded at 500ms; the holder keeps the row for %dms, so an unbounded "
                            + "wait would take at least that long", holdMillis)
                    .isLessThan(3_000);

            System.out.printf("AC-2.11 bounded arm: failed after %dms with 55P03 "
                    + "(holder held for %dms)%n", elapsedMs, holdMillis);
        }
    }

    // ==================================================================
    // AC-2.8 — two workers on one asset serialise; exactly one processes
    // ==================================================================

    @Test
    @DisplayName("AC-2.8: two concurrent workers on one asset — exactly one runs the pipeline")
    void concurrentWorkersOnOneAssetSerialiseOnTheClaimLock() throws Exception {
        mediaProperties.setClaimLockTimeoutMs(30_000);   // long enough that neither loses on time
        UUID assetId = insertPendingAsset(quarantineKey());
        String derivativeKey = tenant + "/media/" + assetId + ".webp";
        Mockito.doReturn(jpegOf(800, 600)).when(storageService).getBytes(Mockito.anyString());

        // Hold the winner inside the pipeline long enough for the loser to reach its claim.
        CountDownLatch insidePipeline = new CountDownLatch(1);
        Mockito.doAnswer(inv -> {
            insidePipeline.countDown();
            Thread.sleep(2_000);
            return "http://minio/derivative";
        }).when(storageService).putBytes(Mockito.eq(derivativeKey), Mockito.any(byte[].class),
                Mockito.anyString());

        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        Thread a = new Thread(() -> {
            try { worker.onMediaEvent(new MediaProcessingEvent(tenant, assetId)); }
            catch (Throwable t) { errA.set(t); }
        }, "worker-A");
        Thread b = new Thread(() -> {
            try {
                insidePipeline.await(10, TimeUnit.SECONDS);   // only race once A is really inside
                worker.onMediaEvent(new MediaProcessingEvent(tenant, assetId));
            } catch (Throwable t) { errB.set(t); }
        }, "worker-B");

        a.start(); b.start();
        a.join(60_000); b.join(60_000);

        // The pipeline ran exactly once — this is the assertion the break inverts.
        Mockito.verify(storageService, Mockito.times(1))
                .putBytes(Mockito.eq(derivativeKey), Mockito.any(byte[].class), Mockito.anyString());

        assertThat(mediaAssetRepository.findById(assetId).orElseThrow().getStatus())
                .isEqualTo(MediaAsset.Status.ACTIVE);

        assertThat(describe(errA.get()) + " | " + describe(errB.get()))
                .as("no optimistic-lock failure may escape either worker — that is the race the "
                        + "claim lock exists to prevent")
                .doesNotContain("ObjectOptimisticLockingFailureException");
    }

    // ---- fixture -----------------------------------------------------------

    /** Session A: a real second connection holding {@code SELECT … FOR UPDATE} on the asset row. */
    private Holder holdRowLock(UUID assetId, long holdMillis) throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        conn.setAutoCommit(false);
        Thread t = new Thread(() -> {
            try (var st = conn.createStatement()) {
                st.execute("SELECT set_config('app.current_tenant_id', '" + tenant + "', true)");
                st.execute("SELECT id FROM media_asset WHERE id = '" + assetId + "' FOR UPDATE");
                acquired.countDown();
                Thread.sleep(holdMillis);
                conn.rollback();
            } catch (Exception e) {
                acquired.countDown();
            }
        }, "lock-holder");
        t.setDaemon(true);
        t.start();
        assertThat(acquired.await(10, TimeUnit.SECONDS)).as("holder acquired the row lock").isTrue();
        return new Holder(conn, t);
    }

    /**
     * VOID guard. Probes from a THIRD connection with its own short {@code lock_timeout}: if that
     * probe succeeds, session A is not holding the row and any conclusion about session B would be
     * meaningless.
     */
    private void assertLockIsGenuinelyHeld(UUID assetId) throws Exception {
        try (Connection probe = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            probe.setAutoCommit(false);
            try (var st = probe.createStatement()) {
                st.execute("SET LOCAL lock_timeout = 300");
                st.execute("SELECT set_config('app.current_tenant_id', '" + tenant + "', true)");
                st.execute("SELECT id FROM media_asset WHERE id = '" + assetId + "' FOR UPDATE");
                probe.rollback();
                throw new AssertionError("VOID: nothing was holding the row lock, so this test "
                        + "could not have observed contention. Exit 2 territory — not a pass.");
            } catch (SQLException expected) {
                assertThat(expected.getSQLState()).isEqualTo("55P03");
            }
        }
    }

    private record Holder(Connection conn, Thread thread) implements AutoCloseable {
        @Override public void close() throws Exception {
            thread.interrupt();
            if (!conn.isClosed()) conn.close();
        }
    }

    private static Throwable catchThrowable(Runnable r) {
        try { r.run(); return null; } catch (Throwable t) { return t; }
    }

    /** Every SQLSTATE in the cause chain — the 55P03 is wrapped several layers deep. */
    private static String sqlStateChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof SQLException se && se.getSQLState() != null) sb.append(se.getSQLState()).append(' ');
            sb.append(c.getClass().getSimpleName()).append(' ');
        }
        return sb.toString();
    }

    private static String describe(Throwable t) {
        return t == null ? "none" : t.getClass().getName() + ": " + t.getMessage();
    }

    private String quarantineKey() {
        return tenant + "/quarantine/" + UUID.randomUUID();
    }

    private UUID insertPendingAsset(String quarantineKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset "
                        + "(id, tenant_id, object_key, sha256, content_type, status) "
                        + "VALUES (?, ?, ?, ?, 'image/jpeg', 'PENDING')",
                id, tenant, quarantineKey, randomSha());
        return id;
    }

    private static String randomSha() {
        return (UUID.randomUUID().toString().replace("-", "") + "0".repeat(64)).substring(0, 64);
    }

    private static byte[] jpegOf(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.ORANGE, w, h, Color.BLUE));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }
}
