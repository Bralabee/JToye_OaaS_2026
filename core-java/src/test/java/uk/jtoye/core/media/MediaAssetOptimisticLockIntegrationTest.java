package uk.jtoye.core.media;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WR-02 — the reaper/worker race guard. {@link MediaPendingReaper} flips a grace-exceeded
 * {@code PENDING} row to {@code FAILED} (and deletes its quarantine object) in its own
 * transaction, while a {@link MediaProcessingWorker} that is legitimately still processing the
 * same grace-exceeded asset runs in a separate transaction. Without an optimistic-lock guard
 * this is last-write-wins: the reaper could overwrite the worker's {@code ACTIVE} flip back to
 * {@code FAILED} AFTER the worker already stored the derivative and repointed the
 * {@code product_media} slot — leaving a FAILED asset that is a product's live primary image.
 *
 * <p>{@code MediaAsset} now carries {@code @Version} (V59), so a stale write from the reaper
 * against a row the worker already advanced fails fast with an optimistic-lock exception
 * instead of silently clobbering the live ACTIVE image. This test reproduces the interleaving
 * with a detached (stale) reaper copy: the worker commits ACTIVE, then the reaper's stale
 * FAILED write is rejected.
 *
 * <p>Runs as the Testcontainers superuser (RLS bypassed) — this proves the version MECHANIC,
 * not tenant isolation.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class MediaAssetOptimisticLockIntegrationTest {

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
    @PersistenceContext private EntityManager em;

    private UUID tenant;

    @BeforeEach
    void seedTenant() {
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "test-" + tenant);
        TenantContext.set(tenant);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void reaperStaleWriteCannotClobberWorkersActiveFlip() {
        UUID id = insertPendingAsset();
        em.clear();

        // The reaper reads a stale copy of the still-PENDING row, then detaches it — modelling a
        // reaper transaction that snapshotted the row BEFORE the worker committed.
        MediaAsset staleReaperCopy = mediaAssetRepository.findById(id).orElseThrow();
        assertThat(staleReaperCopy.getStatus()).isEqualTo(MediaAsset.Status.PENDING);
        em.detach(staleReaperCopy);

        // The worker flips the SAME asset PENDING -> ACTIVE and commits (version bumps).
        MediaAsset workerCopy = mediaAssetRepository.findById(id).orElseThrow();
        workerCopy.setStatus(MediaAsset.Status.ACTIVE);
        mediaAssetRepository.saveAndFlush(workerCopy);
        em.flush();
        em.clear();
        assertThat(mediaAssetRepository.findById(id).orElseThrow().getStatus())
                .as("the worker's ACTIVE flip is persisted")
                .isEqualTo(MediaAsset.Status.ACTIVE);
        em.clear();

        // The reaper now tries to FAIL its stale copy — the @Version guard rejects the lost
        // update rather than silently clobbering the live ACTIVE image.
        staleReaperCopy.setStatus(MediaAsset.Status.FAILED);
        staleReaperCopy.setFailureReason("Processing timed out — please re-upload");
        assertThatThrownBy(() -> mediaAssetRepository.saveAndFlush(staleReaperCopy))
                .as("a stale reaper write against a worker-advanced row fails the optimistic lock")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private UUID insertPendingAsset() {
        UUID id = UUID.randomUUID();
        String sha = UUID.randomUUID().toString().replace("-", "") + "0".repeat(64);
        jdbc.update("INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type, status) "
                        + "VALUES (?, ?, ?, ?, 'image/jpeg', 'PENDING')",
                id, tenant, tenant + "/quarantine/" + id + ".jpg", sha.substring(0, 64));
        return id;
    }
}
