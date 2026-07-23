package uk.jtoye.core.media;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import uk.jtoye.core.storage.StorageService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit proof for {@link MediaPendingReaper}: a crashed-worker orphan (a PENDING
 * {@code media_asset} older than the config grace) is flipped to FAILED with a
 * vendor-visible reason and its quarantine object deleted, while a fresh PENDING is
 * never targeted (the reaper's cutoff sits {@code reaper-grace-ms} in the past, so
 * the DB {@code createdAt < cutoff} filter excludes a just-created upload).
 *
 * <p>The per-tenant {@code TransactionTemplate} is exercised against a mock
 * {@code PlatformTransactionManager} (its {@code getTransaction}/{@code commit} are
 * no-ops, so the callback runs inline); the GUC-pin {@code session.doWork} is stubbed
 * (no real connection). Real RLS/tenant-loop behaviour is covered by the worker's
 * Testcontainers suite; this proves the reaper's own logic.
 */
@ExtendWith(MockitoExtension.class)
class MediaPendingReaperTest {

    @Mock private MediaAssetRepository mediaAssetRepository;
    @Mock private StorageService storageService;
    @Mock private EntityManager entityManager;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private Session session;
    @Mock private Query tenantQuery;

    private final MediaProperties properties = new MediaProperties();
    private MediaPendingReaper reaper;

    private final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reaper = new MediaPendingReaper(mediaAssetRepository, properties, storageService,
                entityManager, transactionManager);
        // listTenantIds() -> one tenant; the GUC-pin session.doWork is a no-op (no real connection).
        lenient().when(entityManager.createNativeQuery("SELECT id FROM tenants")).thenReturn(tenantQuery);
        lenient().when(tenantQuery.getResultList()).thenReturn(List.of(tenant));
        lenient().when(entityManager.unwrap(Session.class)).thenReturn(session);
        lenient().doNothing().when(session).doWork(any());
    }

    @Test
    @DisplayName("A stale PENDING orphan is flipped to FAILED and its quarantine object deleted")
    void staleOrphanReapedToFailed() {
        MediaAsset stale = new MediaAsset();
        stale.setId(UUID.randomUUID());
        stale.setTenantId(tenant);
        stale.setStatus(MediaAsset.Status.PENDING);
        String quarantineKey = tenant + "/quarantine/orphan.jpg";
        stale.setObjectKey(quarantineKey);
        when(mediaAssetRepository.findStalePending(any())).thenReturn(List.of(stale));

        reaper.reapOrphans();

        verify(storageService).deleteByKey(quarantineKey);
        assertThat(stale.getStatus()).isEqualTo(MediaAsset.Status.FAILED);
        assertThat(stale.getFailureReason()).containsIgnoringCase("re-upload");
    }

    @Test
    @DisplayName("A fresh PENDING is untouched — the cutoff is grace in the past, and nothing is deleted")
    void freshPendingNotReaped() {
        when(mediaAssetRepository.findStalePending(any())).thenReturn(List.of());
        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);

        reaper.reapOrphans();

        verify(mediaAssetRepository).findStalePending(cutoff.capture());
        // The cutoff is reaper-grace (default 15 min) in the past — a just-created PENDING
        // (createdAt ~= now) can never satisfy createdAt < cutoff, so it is never reaped.
        OffsetDateTime c = cutoff.getValue();
        assertThat(c).isBefore(OffsetDateTime.now().minusMinutes(14));
        assertThat(c).isAfter(OffsetDateTime.now().minusMinutes(16));
        verify(storageService, never()).deleteByKey(anyString());
    }
}
