package uk.jtoye.core.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V60 durability-schema proofs (27-01, Task 1) — asserted against the APPLIED schema on a live
 * Postgres, never against the migration file's text.
 *
 * <p>The distinction matters. The draft of AC-1.3 was
 * {@code grep -cE '^\s*(UPDATE|DO \$\$)' V60… == 0} — an assertion that was already true before
 * any edit (so it could not fail), and whose {@code grep -c} returning 0 <em>exits 1</em> and
 * would have killed its own {@code set -e} harness. Each criterion here reads
 * {@code information_schema}, {@code pg_indexes} or {@code pg_attribute} on the running container.
 *
 * <p>Runs as the Testcontainers superuser, so RLS is bypassed — these prove SCHEMA shape and the
 * Envers mirror, not tenant isolation (that is {@code MediaAssetRlsPolicyIntegrationTest}).
 * Deliberately NOT {@code @Transactional}: Envers writes its audit rows during
 * {@code beforeTransactionCompletion}, so a test that never commits would see an empty
 * {@code media_asset_aud} and AC-1.4 would pass vacuously for the wrong reason.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class MediaDurabilityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private MediaQuarantineRetentionSweep sweep;
    @Autowired private MediaProperties mediaProperties;
    @org.springframework.boot.test.mock.mockito.SpyBean private uk.jtoye.core.storage.StorageService storageService;

    private UUID tenant;

    @BeforeEach
    void seedTenant() {
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "test-" + tenant);
        TenantContext.set(tenant);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM media_asset_aud WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM media_asset WHERE tenant_id = ?", tenant);
        TenantContext.clear();
        Mockito.reset(storageService);
    }

    // ------------------------------------------------------------------
    // AC-1.1 — the three columns exist with the right nullability/defaults,
    //          and a row that does not mention them still reads the defaults.
    // ------------------------------------------------------------------
    @Test
    void preExistingRowsGetDefaultsWithoutBackfill() {
        Map<String, Map<String, Object>> cols = columnMetadata("media_asset");

        assertThat(cols).containsKeys(
                "process_attempts", "quarantine_expires_at", "quarantine_reclaimed_at");

        assertThat(cols.get("process_attempts"))
                .as("process_attempts is NOT NULL DEFAULT 0 — the default is what makes the "
                        + "ADD COLUMN metadata-only, so pre-existing rows need no backfill")
                .containsEntry("is_nullable", "NO")
                .containsEntry("column_default", "0");

        assertThat(cols.get("quarantine_expires_at"))
                .as("NULL means 'no retained raw bytes were ever claimed' — correct for every "
                        + "V53-backfilled ACTIVE row, so this column must be nullable")
                .containsEntry("is_nullable", "YES");
        assertThat(cols.get("quarantine_reclaimed_at"))
                .as("the sentinel is NULL until a delete is CONFIRMED")
                .containsEntry("is_nullable", "YES");

        // A row inserted without mentioning the new columns — the shape of every pre-V60 row.
        UUID assetId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type, status, flagged)
                VALUES (?, ?, ?, ?, ?, 'PENDING', false)
                """, assetId, tenant, tenant + "/quarantine/" + assetId, "a".repeat(64), "image/jpeg");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT process_attempts, quarantine_expires_at, quarantine_reclaimed_at "
                        + "FROM media_asset WHERE id = ?", assetId);

        assertThat(row.get("process_attempts")).isEqualTo(0);
        assertThat(row.get("quarantine_expires_at")).isNull();
        assertThat(row.get("quarantine_reclaimed_at")).isNull();
    }

    // ------------------------------------------------------------------
    // AC-1.2 — the outbox asset index exists AND is on the right columns
    //          in the right order (F-2).
    // ------------------------------------------------------------------
    @Test
    void outboxAssetIndexIsOnTheRightColumns() {
        List<String> defs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE tablename = 'media_event_outbox' AND indexname = 'idx_media_event_outbox_asset'",
                String.class);

        assertThat(defs).as("exactly one index by that name").hasSize(1);
        assertThat(defs.get(0))
                .as("the reaper's dispatch probe is DISTINCT ON (asset_id) ORDER BY asset_id, "
                        + "created_at DESC — a name-only check would pass on an index over the "
                        + "wrong columns")
                .contains("asset_id")
                .contains("created_at DESC");
    }

    /**
     * The draft's {@code EXPLAIN … WHERE asset_id IN (…)} arm is deliberately NOT used. It
     * inverts on a small table: Postgres seq-scans a perfectly good index at low row counts, so
     * "must not contain Seq Scan" fails on a CORRECT tree — an expected-0 that is 1 when
     * everything is right. Asserting the index definition is order-independent, row-count
     * independent, and strictly stronger than a name-only check.
     */
    @Test
    void quarantineSweepIndexIsPartialOnTheSentinel() {
        List<String> defs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE tablename = 'media_asset' AND indexname = 'idx_media_asset_quarantine_sweep'",
                String.class);

        assertThat(defs).hasSize(1);
        assertThat(defs.get(0))
                .as("a reclaimed row is never a candidate again, so it does not belong in the index")
                .contains("quarantine_expires_at")
                .contains("quarantine_reclaimed_at IS NULL");
    }

    // ------------------------------------------------------------------
    // AC-1.3 — V60 ran no backfill: proven against the APPLIED schema (D-09).
    // ------------------------------------------------------------------
    @Test
    void addColumnWasMetadataOnly() {
        Map<String, Object> att = jdbc.queryForMap("""
                SELECT atthasmissing, attmissingval::text AS missingval
                FROM pg_attribute
                WHERE attrelid = 'media_asset'::regclass AND attname = 'process_attempts'
                """);

        assertThat(att.get("atthasmissing"))
                .as("""
                        Postgres sets atthasmissing ONLY when ADD COLUMN … DEFAULT was satisfied \
                        without rewriting the table — i.e. this is POSITIVE evidence that no \
                        per-row write occurred, not merely the absence of an UPDATE in the file. \
                        The rewrite form (ADD COLUMN nullable; UPDATE; SET NOT NULL; SET DEFAULT) \
                        yields false here, which is what makes this criterion falsifiable.""")
                .isEqualTo(true);
        assertThat((String) att.get("missingval")).isEqualTo("{0}");
    }

    // ------------------------------------------------------------------
    // AC-1.4 — the Envers mirror carries all three columns (the D-09 trap).
    //          Highest-value criterion in Task 1: invisible to any test that
    //          only INSERTs, because the mirror is written on UPDATE.
    // ------------------------------------------------------------------
    @Test
    void enversMirrorCarriesTheNewColumns() {
        UUID assetId = txTemplate.execute(status -> {
            TenantContext.set(tenant);
            MediaAsset asset = new MediaAsset();
            asset.setTenantId(tenant);
            asset.setObjectKey(tenant + "/quarantine/" + UUID.randomUUID());
            asset.setSha256("b".repeat(64));
            asset.setContentType("image/jpeg");
            asset.setStatus(MediaAsset.Status.PENDING);
            return mediaAssetRepository.saveAndFlush(asset).getId();
        });

        // The UPDATE is what makes this test able to fail. Without the three
        // `ALTER TABLE media_asset_aud ADD COLUMN` lines in V60 this throws
        // `column "process_attempts" of relation "media_asset_aud" does not exist`.
        OffsetDateTime reclaimedAt = OffsetDateTime.now();
        txTemplate.executeWithoutResult(status -> {
            TenantContext.set(tenant);
            MediaAsset asset = mediaAssetRepository.findById(assetId).orElseThrow();
            asset.setProcessAttempts(2);
            asset.setQuarantineReclaimedAt(reclaimedAt);
            mediaAssetRepository.saveAndFlush(asset);
        });

        Map<String, Object> aud = jdbc.queryForMap(
                "SELECT process_attempts, quarantine_reclaimed_at, quarantine_expires_at "
                        + "FROM media_asset_aud WHERE id = ? ORDER BY rev DESC LIMIT 1", assetId);

        assertThat(aud.get("process_attempts"))
                .as("the audit revision must carry the updated value, not a NULL placeholder")
                .isEqualTo(2);
        assertThat(aud.get("quarantine_reclaimed_at")).isNotNull();
        assertThat(aud).containsKey("quarantine_expires_at");
    }

    // ==================================================================
    // AC-3.2 — the sentinel actually TERMINATES (M1).
    // Testcontainers, not Mockito: the guards under test live in the @Query,
    // and a mocked repository never executes it — the stub does the filtering,
    // so editing the JPQL changes nothing and the criterion holds in BOTH
    // directions. That vacuity is exactly what would ship a sweep that
    // re-deletes the same objects on every tick forever.
    // ==================================================================

    @Test
    void reclaimedAssetIsNotSweptAgain() {
        // LEGACY row: quarantine_expires_at IS NULL, created 5 days ago -> the legacy arm selects
        // it. This is the shape that proves the draft's design looped forever: its termination
        // marker was "set quarantineExpiresAt = null", which is this arm's own PRECONDITION.
        String key = tenant + "/quarantine/legacy.jpg";
        UUID assetId = seedLegacyReclaimable(key);
        Mockito.doReturn(true).when(storageService).deleteByKeyChecked(key);

        // VOID guard: prove the fixture is genuinely selectable BEFORE tick 1. "Never selected"
        // and "selected once, then terminated" are not the same property.
        assertThat(reclaimableIds()).as("fixture must be selectable, or the test proves nothing")
                .contains(assetId);

        sweep.sweep();
        sweep.sweep();

        Mockito.verify(storageService, Mockito.times(1)).deleteByKeyChecked(key);
        assertThat(reclaimableIds())
                .as("(b) the real predicate — not the sweep's bookkeeping — must no longer return it")
                .doesNotContain(assetId);
        assertThat(mediaAssetRepository.findById(assetId).orElseThrow().getQuarantineReclaimedAt())
                .isNotNull();
    }

    @Test
    void expiringRowIsReclaimedOnce() {
        String key = tenant + "/quarantine/expiring.jpg";
        UUID assetId = seedReclaimable(key, "FAILED", OffsetDateTime.now().minusHours(1));
        Mockito.doReturn(true).when(storageService).deleteByKeyChecked(key);

        assertThat(reclaimableIds()).contains(assetId);

        sweep.sweep();
        sweep.sweep();

        Mockito.verify(storageService, Mockito.times(1)).deleteByKeyChecked(key);
        assertThat(reclaimableIds()).doesNotContain(assetId);
    }

    // ==================================================================
    // AC-3.3(a) — guard 1 (status <> ACTIVE) fails independently.
    // The key deliberately CONTAINS /quarantine/ so guard 2 does NOT block
    // this fixture: guard 1 is the only thing in the way.
    // ==================================================================

    @Test
    void activeAssetIsNeverReclaimed() {
        String key = tenant + "/quarantine/live.jpg";       // deliberately inconsistent state
        UUID assetId = seedReclaimable(key, "ACTIVE", OffsetDateTime.now().minusHours(1));

        assertThat(reclaimableIds())
                .as("guard 1 lives in the @Query — a live derivative is never a candidate")
                .doesNotContain(assetId);

        sweep.sweep();

        Mockito.verify(storageService, Mockito.never()).deleteByKeyChecked(key);
        assertThat(mediaAssetRepository.findById(assetId).orElseThrow().getQuarantineReclaimedAt())
                .isNull();
    }

    // ---- fixtures for the sweep criteria ----------------------------------

    /** The real predicate, run under the tenant GUC — this is what AC-3.2(b) asserts on. */
    private List<UUID> reclaimableIds() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime legacyCutoff =
                now.minusNanos(mediaProperties.getQuarantineRetentionMs() * 1_000_000L);
        return txTemplate.execute(s -> {
            TenantContext.set(tenant);
            return mediaAssetRepository.findReclaimableQuarantine(now, legacyCutoff)
                    .stream().map(MediaAsset::getId).toList();
        });
    }

    private UUID seedReclaimable(String key, String status, OffsetDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type,
                                         status, flagged, quarantine_expires_at)
                VALUES (?, ?, ?, ?, 'image/jpeg', ?, false, ?)
                """, id, tenant, key, randomSha(), status, expiresAt);
        return id;
    }

    /** A pre-V60 row: no expiry claimed, old enough for the legacy created_at arm. */
    private UUID seedLegacyReclaimable(String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type,
                                         status, flagged, quarantine_expires_at, created_at)
                VALUES (?, ?, ?, ?, 'image/jpeg', 'FAILED', false, NULL, now() - interval '5 days')
                """, id, tenant, key, randomSha());
        return id;
    }

    private static String randomSha() {
        return (UUID.randomUUID().toString().replace("-", "") + "0".repeat(64)).substring(0, 64);
    }

    /** {@code information_schema.columns} for a table, keyed by column name. */
    private Map<String, Map<String, Object>> columnMetadata(String table) {
        return jdbc.queryForList(
                        "SELECT column_name, is_nullable, column_default "
                                + "FROM information_schema.columns WHERE table_name = ?", table)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> (String) r.get("column_name"), r -> r));
    }
}
