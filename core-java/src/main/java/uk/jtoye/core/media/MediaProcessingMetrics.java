package uk.jtoye.core.media;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * The single meter on the media consumer path (27-04 T1).
 *
 * <p><b>Why exactly one meter.</b> Queue depth, consumer count and per-consumer
 * {@code prefetch_count} are read out-of-band from the broker's management API by
 * {@code infra/load-testing/media-pipeline-arm.sh}. Duplicating them in-process would create a
 * second source of truth that can disagree with the broker — and the broker's view is the one that
 * survives a correct-source/stale-image mismatch. What the broker <em>cannot</em> supply is
 * per-message service time inside the JVM: {@code media_asset} has {@code created_at} but no
 * {@code updated_at} (V53), so processing latency cannot be derived from the database either. That
 * gap is what this timer closes, and it is the only gap.
 *
 * <p><b>Null-safe by construction.</b> Built through {@link ObjectProvider} exactly like the five
 * existing counters ({@code media.outbox.dead_letter}, {@code media.outbox.resurrected},
 * {@code payment.outbox.*}, {@code tenant.context.missing}, {@code jtoye.ratelimit.fail_open}) —
 * see {@link MediaEventOutboxFlusher}. Many test contexts have no {@link MeterRegistry}; a hard
 * dependency would fail those contexts, and instrumentation must never be the reason a context
 * cannot start. When the registry is absent every {@code record*} call is a no-op.
 *
 * <p><b>Outcome tags.</b> {@code active} and {@code failed} are the two terminal states the worker
 * drives; {@code skipped} covers both no-op paths (asset not visible, and the idempotent
 * already-non-PENDING redelivery). Tagging matters more once 27-04 raises concurrency: a rising
 * {@code skipped} rate is the signal that redeliveries are being produced faster than they are
 * being retired, which a single untagged timer would hide inside its mean.
 */
@Component
public class MediaProcessingMetrics {

    static final String TIMER_NAME = "jtoye.media.process";

    private final Timer activeTimer;
    private final Timer failedTimer;
    private final Timer skippedTimer;

    public MediaProcessingMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry reg = meterRegistryProvider.getIfAvailable();
        this.activeTimer = timer(reg, "active");
        this.failedTimer = timer(reg, "failed");
        this.skippedTimer = timer(reg, "skipped");
    }

    private static Timer timer(MeterRegistry reg, String outcome) {
        return reg == null ? null
                : Timer.builder(TIMER_NAME)
                    .description("Wall time of one media.process message, from the tenant-GUC pin to the finally")
                    .tag("outcome", outcome)
                    .register(reg);
    }

    /**
     * Records one message's service time. Never throws: a metrics failure must not be able to fail
     * a message that processed correctly, which would turn instrumentation into an outage.
     *
     * @param outcome  {@code active}, {@code failed} or {@code skipped}
     * @param nanos    elapsed wall time, measured by the caller with {@link System#nanoTime()}
     */
    public void record(String outcome, long nanos) {
        Timer t = switch (outcome) {
            case "active" -> activeTimer;
            case "failed" -> failedTimer;
            default -> skippedTimer;
        };
        if (t != null) {
            t.record(nanos, TimeUnit.NANOSECONDS);
        }
    }
}
