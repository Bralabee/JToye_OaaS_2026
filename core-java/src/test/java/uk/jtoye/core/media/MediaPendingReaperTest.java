package uk.jtoye.core.media;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.storage.StorageService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit proof for the rewritten {@link MediaPendingReaper} (27-01 Task 2).
 *
 * <p><b>A deliberately deleted test.</b> The previous version of this file had
 * {@code staleOrphanReapedToFailed}, which asserted
 * {@code verify(storageService).deleteByKey(quarantineKey)} and a FAILED flip — i.e. exactly the
 * data-destroying behaviour this plan removes. It is deleted, not lost. Its pre-change PASS was
 * recorded first as the historical baseline for AC-2.2, because the alternative arm ("run the new
 * tests against the pre-fix tree via git stash") cannot work: these tests reference
 * {@code probeDispatchPath}, {@code findLatestDispatchStateForAssets} and the new constructor
 * arity, so stashing the main-source change yields a COMPILE ERROR, and a compile error is not
 * evidence about behaviour. {@code #dispatchedStallFailsButRetainsBytes} replaces it.
 *
 * <p>The per-tenant {@code TransactionTemplate} runs against a mock
 * {@code PlatformTransactionManager} (getTransaction/commit are no-ops, so the callback runs
 * inline); the GUC-pin {@code session.doWork} is stubbed. Real RLS/tenant-loop behaviour is
 * covered by the Testcontainers suite; this proves the reaper's own logic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaPendingReaperTest {

    @Mock private MediaAssetRepository mediaAssetRepository;
    @Mock private MediaEventOutboxRepository mediaEventOutboxRepository;
    @Mock private EntityManager entityManager;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private Session session;
    @Mock private jakarta.persistence.Query tenantQuery;

    @Mock private AmqpAdmin amqpAdmin;
    @Mock private RabbitListenerEndpointRegistry endpointRegistry;
    @Mock private SimpleMessageListenerContainer mediaContainer;

    private final MediaProperties properties = new MediaProperties();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private MediaPendingReaper reaper;

    private final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(entityManager.createNativeQuery("SELECT id FROM tenants")).thenReturn(tenantQuery);
        lenient().when(tenantQuery.getResultList()).thenReturn(List.of(tenant));
        lenient().when(entityManager.unwrap(Session.class)).thenReturn(session);
        lenient().doNothing().when(session).doWork(any());
        aliveDispatchPath();
        reaper = build();
    }

    private MediaPendingReaper build() {
        return new MediaPendingReaper(mediaAssetRepository, mediaEventOutboxRepository, properties,
                entityManager, transactionManager,
                provider(amqpAdmin), provider(endpointRegistry), provider(meters));
    }

    /** The healthy baseline: broker reachable, consumers present, local container running. */
    private void aliveDispatchPath() {
        lenient().when(amqpAdmin.getQueueInfo("media.process"))
                .thenReturn(new QueueInformation("media.process", 0, 1));
        lenient().when(mediaContainer.getQueueNames()).thenReturn(new String[]{"media.process"});
        lenient().when(mediaContainer.isRunning()).thenReturn(true);
        lenient().when(mediaContainer.getActiveConsumerCount()).thenReturn(1);
        lenient().when(endpointRegistry.getListenerContainers())
                .thenReturn(List.<MessageListenerContainer>of(mediaContainer));
    }

    // ==================================================================
    // AC-2.1 — structurally incapable of deleting an object (D-02)
    // ==================================================================

    @Test
    @DisplayName("AC-2.1: the reaper holds no StorageService — it cannot delete bytes at all")
    void reaperHasNoStorageDependency() {
        assertThat(Arrays.stream(MediaPendingReaper.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getType))
                .as("a reaper that cannot reach a StorageService cannot destroy a vendor's only "
                        + "copy of an upload, whatever its logic later becomes")
                .doesNotContain(StorageService.class);

        assertThat(Arrays.stream(MediaPendingReaper.class.getDeclaredConstructors())
                .flatMap(c -> Arrays.stream(c.getParameterTypes())))
                .doesNotContain(StorageService.class);
    }

    // ==================================================================
    // AC-2.2 — an UNDISPATCHED stall is never touched (the regression test)
    // ==================================================================

    @Test
    @DisplayName("AC-2.2: outbox row still PENDING — the event was never dispatched, so hands off")
    void undispatchedStallIsNeverTouched() {
        MediaAsset stale = stalePending();
        stubStale(stale);
        stubDispatch(stale.getId(), "PENDING", false);

        reaper.reapOrphans();

        assertThat(stale.getStatus()).isEqualTo(MediaAsset.Status.PENDING);
        assertThat(stale.getProcessAttempts()).isZero();
        assertThat(stale.getQuarantineReclaimedAt()).isNull();
    }

    @Test
    @DisplayName("AC-2.2: retriable FAILED (poison=false) — resurrection will re-lease it, so hands off")
    void retriableFailedOutboxStallIsNeverTouched() {
        MediaAsset stale = stalePending();
        stubStale(stale);
        stubDispatch(stale.getId(), "FAILED", false);

        reaper.reapOrphans();

        assertThat(stale.getStatus()).isEqualTo(MediaAsset.Status.PENDING);
    }

    @Test
    @DisplayName("AC-2.2: NO outbox row at all — ambiguous, so fail CLOSED (D-01)")
    void absentOutboxRowIsNeverReaped() {
        MediaAsset stale = stalePending();
        stubStale(stale);
        when(mediaEventOutboxRepository.findLatestDispatchStateForAssets(any())).thenReturn(List.of());

        reaper.reapOrphans();

        assertThat(stale.getStatus())
                .as("an absent row is ambiguous (pre-outbox? purged? manual delete?) and a missing "
                        + "discovery result is never 'clean' — the retention sweep collects it later")
                .isEqualTo(MediaAsset.Status.PENDING);
    }

    // ==================================================================
    // AC-2.3 / AC-2.4 — a dispatched stall fails WITHOUT losing bytes
    // ==================================================================

    @Test
    @DisplayName("AC-2.3: dispatched (SENT) stall flips FAILED but the bytes stay claimed")
    void dispatchedStallFailsButRetainsBytes() {
        MediaAsset stale = stalePending();
        OffsetDateTime expiry = OffsetDateTime.now().plusHours(72);
        stale.setQuarantineExpiresAt(expiry);
        stubStale(stale);
        stubDispatch(stale.getId(), "SENT", false);

        reaper.reapOrphans();

        assertThat(stale.getStatus()).isEqualTo(MediaAsset.Status.FAILED);
        assertThat(stale.getFailureReason()).contains("Re-process");
        assertThat(stale.getQuarantineExpiresAt())
                .as("the retained bytes must still be claimed — this is what makes it re-drivable")
                .isEqualTo(expiry);
        assertThat(stale.getQuarantineReclaimedAt())
                .as("stamping the sentinel here would make the asset unrecoverable — the exact harm")
                .isNull();
    }

    @Test
    @DisplayName("AC-2.4: a poisoned outbox row fails with a DIFFERENT reason, bytes still retained")
    void poisonedOutboxStallFailsWithDistinctReason() {
        MediaAsset stale = stalePending();
        stale.setQuarantineExpiresAt(OffsetDateTime.now().plusHours(72));
        stubStale(stale);
        stubDispatch(stale.getId(), "FAILED", true);

        reaper.reapOrphans();

        assertThat(stale.getStatus()).isEqualTo(MediaAsset.Status.FAILED);
        assertThat(stale.getQuarantineReclaimedAt()).isNull();
        assertThat(stale.getFailureReason())
                .as("support must be able to tell 'never queued' from 'queued but stalled' from "
                        + "the UI alone")
                .isNotEqualTo(MediaPendingReaper.REASON_STALLED)
                .isEqualTo(MediaPendingReaper.REASON_POISON);
    }

    // ==================================================================
    // AC-2.5 — a fresh PENDING inside the grace is never selected
    // ==================================================================

    @Test
    @DisplayName("AC-2.5: a 1-minute-old PENDING is outside the cutoff and never classified")
    void freshPendingNotReaped() {
        MediaAsset fresh = asset(1);   // 1 minute old
        // The stub emulates the DB predicate `createdAt < cutoff`, so changing the grace really
        // does change what comes back — without this the criterion could not fail.
        when(mediaAssetRepository.findStalePending(any())).thenAnswer(inv -> {
            OffsetDateTime cutoff = inv.getArgument(0);
            return fresh.getCreatedAt().isBefore(cutoff) ? List.of(fresh) : List.of();
        });
        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);

        reaper.reapOrphans();

        verify(mediaAssetRepository).findStalePending(cutoff.capture());
        assertThat(cutoff.getValue()).isBefore(OffsetDateTime.now().minusMinutes(14));
        assertThat(cutoff.getValue()).isAfter(OffsetDateTime.now().minusMinutes(16));
        verify(mediaEventOutboxRepository, never()).findLatestDispatchStateForAssets(any());
        assertThat(fresh.getStatus()).isEqualTo(MediaAsset.Status.PENDING);
    }

    // ==================================================================
    // AC-2.6 — broker unreachable suspends the WHOLE tick (arm 1, fail closed)
    // ==================================================================

    @Test
    @DisplayName("AC-2.6(a): getQueueInfo returns null -> suspend, not one tenant touched")
    void brokerQueueInfoNullSuspendsTheSweep() {
        when(amqpAdmin.getQueueInfo("media.process")).thenReturn(null);

        reaper.reapOrphans();

        verify(mediaAssetRepository, never()).findStalePending(any());
    }

    @Test
    @DisplayName("AC-2.6(b): getQueueInfo throws -> suspend")
    void brokerUnreachableSuspendsTheSweep() {
        when(amqpAdmin.getQueueInfo("media.process")).thenThrow(new AmqpConnectException(new RuntimeException("down")));

        reaper.reapOrphans();

        verify(mediaAssetRepository, never()).findStalePending(any());
    }

    @Test
    @DisplayName("AC-2.6(c): no AmqpAdmin bean at all -> suspend")
    void absentAmqpAdminSuspendsTheSweep() {
        reaper = new MediaPendingReaper(mediaAssetRepository, mediaEventOutboxRepository, properties,
                entityManager, transactionManager,
                emptyProvider(), provider(endpointRegistry), provider(meters));

        reaper.reapOrphans();

        verify(mediaAssetRepository, never()).findStalePending(any());
    }

    // ==================================================================
    // AC-2.7 — a dead LOCAL consumer suspends the tick (the arm M2 required)
    // ==================================================================

    @Test
    @DisplayName("AC-2.7: the local media.process container is stopped -> suspend")
    void localListenerContainerDownSuspendsTheSweep() {
        when(mediaContainer.isRunning()).thenReturn(false);

        reaper.reapOrphans();

        verify(mediaAssetRepository, never()).findStalePending(any());
    }

    @Test
    @DisplayName("AC-2.7: no local container serves media.process -> suspend")
    void noLocalContainerSuspendsTheSweep() {
        when(endpointRegistry.getListenerContainers()).thenReturn(List.of());

        reaper.reapOrphans();

        verify(mediaAssetRepository, never()).findStalePending(any());
    }

    @Test
    @DisplayName("AC-2.7: the local container reports zero active consumers -> suspend")
    void localContainerWithNoConsumersSuspendsTheSweep() {
        when(mediaContainer.getActiveConsumerCount()).thenReturn(0);

        reaper.reapOrphans();

        verify(mediaAssetRepository, never()).findStalePending(any());
    }

    /**
     * Arm 3. Unit-falsifiable with a stubbed {@code AmqpAdmin}, and <b>structurally unreachable in
     * the delivered runtime</b>: {@code getQueueInfo} returns the BROKER-WIDE consumer count and
     * this JVM hosts the {@code media.process} listener, so the count is &ge; 1 whenever the reaper
     * ticks with its own container running. This pass is NOT runtime evidence and must not be
     * presented as such.
     */
    @Test
    @DisplayName("AC-2.7: broker-wide zero consumers -> suspend (belt-and-braces; unreachable live)")
    void brokerWideZeroConsumersSuspendsTheSweep() {
        when(amqpAdmin.getQueueInfo("media.process"))
                .thenReturn(new QueueInformation("media.process", 5, 0));

        reaper.reapOrphans();

        verify(mediaAssetRepository, never()).findStalePending(any());
    }

    // ==================================================================
    // AC-2.9 — no scheduled component can enqueue a media event (D-04)
    // ==================================================================

    @Test
    @DisplayName("AC-2.9: no classification branch ever writes an outbox row, and the field set forbids it")
    void reaperNeverEnqueues() {
        // (i) behavioural — every branch, one tick each.
        for (Object[] branch : new Object[][]{
                {"PENDING", false}, {"FAILED", false}, {"SENT", false}, {"FAILED", true}}) {
            MediaAsset stale = stalePending();
            stubStale(stale);
            stubDispatch(stale.getId(), (String) branch[0], (Boolean) branch[1]);
            reaper.reapOrphans();
        }
        MediaAsset absent = stalePending();
        stubStale(absent);
        when(mediaEventOutboxRepository.findLatestDispatchStateForAssets(any())).thenReturn(List.of());
        reaper.reapOrphans();

        verify(mediaEventOutboxRepository, never()).save(any());
        verify(mediaEventOutboxRepository, never()).saveAll(any());

        // (ii) structural — the exact declared-field type set. A reaper with no reference to a
        // writer cannot call one. (Reflection cannot inspect method BODIES, so the earlier
        // "no method whose body could reach an outbox write" phrasing was not implementable and
        // is withdrawn rather than silently skipped; that would need ASM/ArchUnit.)
        Set<Class<?>> actual = Arrays.stream(MediaPendingReaper.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getType)
                .collect(Collectors.toSet());

        assertThat(actual).containsExactlyInAnyOrder(
                MediaAssetRepository.class,
                MediaEventOutboxRepository.class,
                MediaProperties.class,
                EntityManager.class,
                TransactionTemplate.class,
                ObjectProvider.class);
        assertThat(actual)
                .as("MediaAssetService is the enqueue path — the reaper must not be able to reach it")
                .doesNotContain(MediaAssetService.class);
    }

    // ==================================================================
    // AC-2.10 — F-4: an outbox purge must not orphan a PENDING asset
    // ==================================================================

    @Test
    @DisplayName("AC-2.10: no outbox purge may delete the dispatch evidence the stall gate reads")
    void noOutboxPurgeExistsForPendingAssets() {
        for (Method m : MediaEventOutboxRepository.class.getDeclaredMethods()) {
            if (m.getAnnotation(Modifying.class) == null) continue;
            org.springframework.data.jpa.repository.Query q =
                    m.getAnnotation(org.springframework.data.jpa.repository.Query.class);
            if (q == null) continue;
            String sql = q.value().toUpperCase();
            if (!sql.contains("DELETE")) continue;

            // A purge is allowed to exist, but only if it protects still-PENDING assets.
            boolean guarded = sql.contains("NOT EXISTS") && sql.contains("MEDIA_ASSET")
                    && sql.contains("PENDING");
            if (!guarded) {
                fail("media_event_outbox purge '" + m.getName() + "' would delete dispatch evidence "
                        + "for PENDING assets — the Phase 27 stall gate reads it. Add a "
                        + "NOT EXISTS (... media_asset ... status = 'PENDING' ...) predicate.");
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private MediaAsset stalePending() {
        return asset(30);
    }

    /** A PENDING asset {@code ageMinutes} old. {@code createdAt} is @CreationTimestamp-managed, so
     *  it has no setter and must be set reflectively for the cutoff arithmetic to mean anything. */
    private MediaAsset asset(int ageMinutes) {
        MediaAsset a = new MediaAsset();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenant);
        a.setStatus(MediaAsset.Status.PENDING);
        a.setObjectKey(tenant + "/quarantine/" + UUID.randomUUID());
        a.setSha256("c".repeat(64));
        a.setContentType("image/jpeg");
        try {
            Field f = MediaAsset.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(a, OffsetDateTime.now().minusMinutes(ageMinutes));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return a;
    }

    private void stubStale(MediaAsset... assets) {
        when(mediaAssetRepository.findStalePending(any())).thenReturn(List.of(assets));
    }

    private void stubDispatch(UUID assetId, String status, boolean poison) {
        // List.<Object[]>of(...) is load-bearing: a bare List.of(new Object[]{..}) spreads the
        // array into the varargs slot and yields List<Object>, not a one-row List<Object[]>.
        when(mediaEventOutboxRepository.findLatestDispatchStateForAssets(any()))
                .thenReturn(List.<Object[]>of(new Object[]{assetId, status, poison}));
    }

    private <T> ObjectProvider<T> provider(T bean) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> p = org.mockito.Mockito.mock(ObjectProvider.class);
        lenient().when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private <T> ObjectProvider<T> emptyProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> p = org.mockito.Mockito.mock(ObjectProvider.class);
        lenient().when(p.getIfAvailable()).thenReturn(null);
        return p;
    }
}
