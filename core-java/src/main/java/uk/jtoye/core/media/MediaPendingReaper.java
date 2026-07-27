package uk.jtoye.core.media;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.security.TenantContext;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stall classifier for quarantined uploads (27-01 / D-01, D-02, D-04, D-05).
 *
 * <h2>What this component may and may not do</h2>
 * <ul>
 *   <li><b>May</b> flip a {@code PENDING} asset to {@code FAILED} with a vendor-visible,
 *       re-drivable reason. That flip is cheap and reversible.</li>
 *   <li><b>May not delete an object.</b> It holds no {@code StorageService} reference at all, so
 *       it is <em>structurally</em> incapable of destroying bytes (D-02). Byte reclamation belongs
 *       exclusively to {@link MediaQuarantineRetentionSweep}, on a declared retention horizon.</li>
 *   <li><b>May not enqueue anything.</b> {@link MediaEventOutboxRepository} is injected READ-ONLY,
 *       for the dispatch-evidence probe. No scheduled component may create a second in-flight
 *       event for one asset (D-04).</li>
 *   <li><b>May not touch the quarantine markers.</b> {@code quarantineExpiresAt} and
 *       {@code quarantineReclaimedAt} are untouched here, so a reaped asset stays re-drivable.</li>
 * </ul>
 *
 * <h2>What the pre-Phase-27 version did, and why it was a P0</h2>
 * It selected on <em>status alone</em> ({@code PENDING AND createdAt < now - 15 min}) and then
 * called {@code storageService.deleteByKey(...)} — permanently destroying the vendor's original
 * upload. During a broker / media-outbox outage the event has <b>provably not been dispatched</b>:
 * {@code MediaEventOutboxFlusher} backs off 5+10+20+40+80+160+300+300+300 s (~20 min) to
 * {@code MAX_ATTEMPTS}, so at the 15-minute cutoff the outbox row is still {@code PENDING} with
 * ~7-8 attempts. The transactional outbox protected the <em>event</em>; nothing protected the
 * <em>object</em>. A transient infrastructure failure therefore produced unrecoverable data loss.
 *
 * <p>The irreversible delete also sat <em>inside</em> the {@code TransactionTemplate} callback but
 * was not transactional: with {@code @Version} (V59) a concurrent worker flip made the commit throw
 * {@code ObjectOptimisticLockingFailureException}, the DB writes rolled back — and the object
 * deletes did not. Removing the dependency closes that by construction.
 *
 * <h2>Dispatch evidence, and why absence fails CLOSED (D-01)</h2>
 * Reap-eligible iff the LATEST {@code media_event_outbox} row for the asset is {@code SENT}, or is
 * {@code FAILED AND poison = true}. Everything else — {@code PENDING}, non-poison {@code FAILED},
 * and <b>no row at all</b> — is left completely untouched.
 *
 * <p>An absent row is genuinely ambiguous (a pre-outbox asset? a future purge? a manual delete?),
 * and this repository's standing rule is that a missing or empty discovery result is never "clean".
 * The cost of failing closed is that a genuinely orphaned asset with no outbox row is never
 * stall-classified — which is exactly why {@link MediaQuarantineRetentionSweep} is
 * <b>unconditional</b> and collects it anyway on the retention horizon.
 *
 * <h2>Why re-drive is human-initiated only (D-04)</h2>
 * An earlier design had this reaper insert a fresh outbox row for a stalled {@code SENT} asset.
 * That manufactures a <b>worker/worker race on one asset</b>: the second event publishes within
 * ~5 s while the first worker may still be inside {@code normalize}/{@code putBytes}/Ollama; the
 * worker's only interlock is {@code status != PENDING}, which the in-flight worker has not yet
 * invalidated; both would then {@code putBytes} the same derivative key and both run
 * {@code placeAsset}, whose {@code releaseAsset} physically deletes the displaced asset at
 * ref-count 0. {@code @Version} (V59) exists precisely because reaper/worker concurrency was
 * already a real hazard — manufacturing a second concurrent writer on a schedule, to fix a
 * data-loss bug, is not an acceptable trade. Recovery is therefore exclusively the human-initiated
 * {@code POST /api/v1/media/{assetId}/reprocess} (D-06), made safe by the worker's claim lock.
 *
 * <h2>Exactly what the suspension circuit can and cannot observe (D-05)</h2>
 * <table border="1">
 *   <caption>Suspension arms</caption>
 *   <tr><th>Arm</th><th>Signal</th><th>What it covers</th></tr>
 *   <tr><td>1 — reachability</td>
 *       <td>{@code AmqpAdmin} absent, {@code getQueueInfo} throws, or returns {@code null}</td>
 *       <td>The broker is unreachable from this JVM — the outage case.
 *           <b>This is the arm that fires in the delivered runtime.</b></td></tr>
 *   <tr><td>2 — local consumer</td>
 *       <td>no container for {@code media.process}, {@code isRunning() == false}, or
 *           {@code getActiveConsumerCount() == 0}</td>
 *       <td><em>This</em> JVM's consumer is down while the connection survives — a
 *           single-replica deployment's real "dispatched, consumer down" case.</td></tr>
 *   <tr><td>3 — broker-wide zero</td>
 *       <td>{@code getQueueInfo().getConsumerCount() == 0}</td>
 *       <td>Belt-and-braces only. <b>Structurally unreachable while this JVM's container runs</b>:
 *           {@code getQueueInfo} returns the BROKER-WIDE count and this JVM hosts the
 *           {@code media.process} listener, so the count is &ge; 1 whenever the reaper ticks.
 *           Unit-falsifiable with a stubbed {@code AmqpAdmin}; runtime-unfalsifiable.</td></tr>
 * </table>
 *
 * <p><b>Not covered, stated rather than implied:</b> a <em>remote replica's</em> dead consumer
 * ({@code --scale core-java=N}, N&gt;1) is invisible to all three arms, because this JVM's own
 * consumer keeps the broker-wide count &ge; 1. That case is made <b>non-destructive</b> by D-02
 * (this class cannot delete), <b>not detected</b>.
 *
 * <p>The consequence of a misfire is correspondingly small: a false "alive" reading causes at worst
 * a premature vendor-visible {@code FAILED} <em>with bytes retained</em> and a one-click
 * Re-process — not data loss. Forever-suspended is survivable because the retention sweep is
 * deliberately NOT gated on this circuit.
 *
 * <p>Clones the {@code WebhookRetentionCleanup} shape: per-tenant, its own transaction each,
 * {@link TenantContext} + the connection GUC pinned so the find/update is RLS-scoped (a bare query
 * would see ZERO rows under FORCE RLS), and a {@link TransactionTemplate} rather than a
 * {@code @Transactional} private method (Spring self-invocation would skip the proxy and the tenant
 * would come out NULL).
 */
@Component
public class MediaPendingReaper {

    private static final Logger log = LoggerFactory.getLogger(MediaPendingReaper.class);

    /**
     * Vendor-visible reason for a stall whose event WAS dispatched. Distinct from
     * {@link #REASON_POISON} so support can tell the two cases apart from the UI alone.
     */
    static final String REASON_STALLED =
            "Image processing did not complete. Your original upload is kept — "
                    + "press Re-process, or upload a new image.";

    /** Vendor-visible reason for an asset whose event could never be published (poison payload). */
    static final String REASON_POISON =
            "This upload could not be queued for processing. Your original upload is kept — "
                    + "press Re-process, or upload a new image.";

    private final MediaAssetRepository mediaAssetRepository;
    private final MediaEventOutboxRepository mediaEventOutboxRepository;
    private final MediaProperties properties;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<AmqpAdmin> amqpAdmin;
    private final ObjectProvider<RabbitListenerEndpointRegistry> listenerRegistry;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public MediaPendingReaper(MediaAssetRepository mediaAssetRepository,
                              MediaEventOutboxRepository mediaEventOutboxRepository,
                              MediaProperties properties,
                              EntityManager entityManager,
                              PlatformTransactionManager transactionManager,
                              ObjectProvider<AmqpAdmin> amqpAdmin,
                              ObjectProvider<RabbitListenerEndpointRegistry> listenerRegistry,
                              ObjectProvider<MeterRegistry> meterRegistry) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.mediaEventOutboxRepository = mediaEventOutboxRepository;
        this.properties = properties;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.amqpAdmin = amqpAdmin;
        this.listenerRegistry = listenerRegistry;
        this.meterRegistry = meterRegistry;
    }

    /** Why a tick suspended, or {@link #ALIVE} when the dispatch path is demonstrably healthy. */
    enum Liveness {
        ALIVE,
        BROKER_ADMIN_ABSENT,
        QUEUE_INFO_THREW,
        QUEUE_INFO_NULL,
        BROKER_WIDE_ZERO_CONSUMERS,
        REGISTRY_ABSENT,
        NO_LOCAL_CONTAINER,
        LOCAL_CONTAINER_STOPPED,
        LOCAL_CONTAINER_NO_CONSUMERS
    }

    @Scheduled(fixedDelayString = "${jtoye.media.reaper-interval-ms:600000}")
    public void reapOrphans() {
        Liveness live = probeDispatchPath();
        if (live != Liveness.ALIVE) {
            // Fail CLOSED. Not one tenant is touched: we cannot tell a stalled upload from an
            // undispatched one while the dispatch path itself is unproven.
            log.warn("event=media_reaper_suspended reason={}", live);
            increment("media.reaper.suspended");
            return;
        }

        OffsetDateTime cutoff = OffsetDateTime.now().minusNanos(properties.getReaperGraceMs() * 1_000_000L);
        List<UUID> tenantIds = listTenantIds();
        long total = 0;
        for (UUID tenantId : tenantIds) {
            try {
                total += reapTenant(tenantId, cutoff);
            } catch (Exception e) {
                log.error("event=media_reaper_failed tenant={} — continuing: {}", tenantId, e.getMessage());
            }
        }
        if (total > 0) {
            log.info("event=media_reaper_swept reaped={} olderThan={} tenants={}", total, cutoff, tenantIds.size());
        }
    }

    /**
     * Fails CLOSED on every uncertainty — an unreachable broker and an absent bean are both
     * "cannot prove the dispatch path is healthy", which is not the same as "healthy".
     */
    Liveness probeDispatchPath() {
        AmqpAdmin admin = amqpAdmin.getIfAvailable();
        if (admin == null) return Liveness.BROKER_ADMIN_ABSENT;

        QueueInformation info;
        try {
            info = admin.getQueueInfo(RabbitMQConfig.MEDIA_EVENTS_QUEUE);
        } catch (Exception e) {
            return Liveness.QUEUE_INFO_THREW;
        }
        if (info == null) return Liveness.QUEUE_INFO_NULL;
        if (info.getConsumerCount() == 0) return Liveness.BROKER_WIDE_ZERO_CONSUMERS;

        RabbitListenerEndpointRegistry registry = listenerRegistry.getIfAvailable();
        if (registry == null) return Liveness.REGISTRY_ABSENT;

        MessageListenerContainer container = localMediaContainer(registry);
        if (container == null) return Liveness.NO_LOCAL_CONTAINER;
        if (!container.isRunning()) return Liveness.LOCAL_CONTAINER_STOPPED;
        if (container instanceof SimpleMessageListenerContainer simple
                && simple.getActiveConsumerCount() == 0) {
            return Liveness.LOCAL_CONTAINER_NO_CONSUMERS;
        }
        return Liveness.ALIVE;
    }

    /** The container in THIS JVM serving {@code media.process}, or null if there is none. */
    private MessageListenerContainer localMediaContainer(RabbitListenerEndpointRegistry registry) {
        for (MessageListenerContainer c : registry.getListenerContainers()) {
            if (c instanceof AbstractMessageListenerContainer amlc) {
                String[] queues = amlc.getQueueNames();
                if (queues != null && Arrays.asList(queues).contains(RabbitMQConfig.MEDIA_EVENTS_QUEUE)) {
                    return c;
                }
            }
        }
        return null;
    }

    private long reapTenant(UUID tenantId, OffsetDateTime cutoff) {
        TenantContext.set(tenantId);
        try {
            Integer reaped = transactionTemplate.execute(status -> {
                pinTenantGuc(tenantId);
                List<MediaAsset> stale = mediaAssetRepository.findStalePending(cutoff);
                if (stale.isEmpty()) return 0;

                Map<UUID, DispatchState> evidence = dispatchEvidence(stale);

                int flipped = 0;
                for (MediaAsset asset : stale) {
                    DispatchState state = evidence.get(asset.getId());

                    if (state == null || state.undispatched()) {
                        // D-01 fail-closed: no row, still PENDING, or a retriable FAILED that the
                        // resurrection pass will re-lease. The work was never dispatched — the
                        // bytes are the vendor's only copy and this component leaves them alone.
                        increment("media.reaper.undispatched_skipped");
                        log.debug("event=media_reaper_skipped asset={} reason={}",
                                asset.getId(), state == null ? "no_outbox_row" : state.describe());
                        continue;
                    }

                    // Dispatched (SENT) or unpublishable (poison). Flip only — NO object delete,
                    // NO marker change, NO enqueue. The asset stays re-drivable.
                    asset.setStatus(MediaAsset.Status.FAILED);
                    asset.setFailureReason(state.poison() ? REASON_POISON : REASON_STALLED);
                    increment("media.reaper.stalled_failed");
                    flipped++;
                }
                return flipped;
            });
            long count = reaped == null ? 0 : reaped;
            if (count > 0) {
                log.info("event=media_reaper_reaped tenant={} reaped={}", tenantId, count);
            }
            return count;
        } finally {
            TenantContext.clear();
        }
    }

    /** Latest outbox row per asset, keyed by asset id. Absent key == no row at all. */
    private Map<UUID, DispatchState> dispatchEvidence(List<MediaAsset> stale) {
        List<UUID> ids = stale.stream().map(MediaAsset::getId).toList();
        Map<UUID, DispatchState> byAsset = new HashMap<>();
        for (Object[] row : mediaEventOutboxRepository.findLatestDispatchStateForAssets(ids)) {
            UUID assetId = (UUID) row[0];
            String status = row[1] == null ? null : row[1].toString();
            boolean poison = row[2] != null && (Boolean) row[2];
            byAsset.put(assetId, new DispatchState(status, poison));
        }
        return byAsset;
    }

    /**
     * The latest outbox row's dispatch state.
     *
     * @param status {@code PENDING} / {@code SENT} / {@code FAILED}
     * @param poison whether the payload is unpublishable (a poison FAILED can never succeed on
     *               retry, so it IS terminal evidence — unlike a retriable FAILED, which the
     *               resurrection pass re-leases)
     */
    private record DispatchState(String status, boolean poison) {

        /** True when this row proves the work has NOT been dispatched and may still be. */
        boolean undispatched() {
            if ("SENT".equals(status)) return false;
            if ("FAILED".equals(status)) return !poison;
            return true;   // PENDING, or anything unrecognised — fail closed.
        }

        String describe() {
            return "outbox_" + status + (poison ? "_poison" : "");
        }
    }

    private void increment(String counter) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) registry.counter(counter).increment();
    }

    @SuppressWarnings("unchecked")
    private List<UUID> listTenantIds() {
        return transactionTemplate.execute(status ->
                entityManager.createNativeQuery("SELECT id FROM tenants").getResultList());
    }

    private void pinTenantGuc(UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.toString());
                stmt.execute();
            }
        });
    }
}
