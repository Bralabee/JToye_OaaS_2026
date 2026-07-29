package uk.jtoye.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The single source of the RabbitMQ listener tunables (27-04, D-03).
 *
 * <p>Mirrors {@link uk.jtoye.core.media.MediaProperties}: hand-written getters/setters (no
 * Lombok), defaults that state today's effective behaviour, env-overridable under
 * {@code jtoye.rabbit.*}. Every number lives here rather than in a
 * {@code @RabbitListener(concurrency = "1-2")} annotation attribute, because a tuning value
 * baked into a Java annotation cannot be changed per environment — the same rule
 * {@code MediaProperties} exists to satisfy (GLOBAL_RULE_6 / ARCHITECTURE_RULE_8).
 *
 * <p><b>Why these are not {@code spring.rabbitmq.listener.simple.*}.</b> They would be, if that
 * family worked. It does not: this project declares a bean literally named
 * {@code rabbitListenerContainerFactory}, and Boot's
 * {@code RabbitAnnotationDrivenConfiguration.simpleRabbitListenerContainerFactory} is annotated
 * {@code @ConditionalOnMissingBean(name = "rabbitListenerContainerFactory")}, so Boot's factory
 * backs off and {@code SimpleRabbitListenerContainerFactoryConfigurer} — the ONLY consumer of
 * that property family — was never applied. Setting a {@code spring.rabbitmq.listener.simple.*}
 * value was a silent no-op that read as a fix. {@link RabbitMQConfig} now applies the configurer
 * explicitly, and these properties layer on top of it.
 *
 * <p><b>Why media is separate, and why nothing else moves (D-02).</b> Of the nine
 * {@code @RabbitListener} endpoints, exactly one is CPU-bound: the media worker runs two WebP
 * encodes per message. The other eight are IO-bound and short, and raising their concurrency
 * would actively harm two of them — {@code OrderStateChangeListener} above concurrency 1 loses
 * per-order ordering (its {@code processed_order_events} dedup key prevents repeats, not
 * reordering, so a PREPARING email could overtake a CONFIRMED one), and the SSE fan-out queue is
 * a per-JVM {@code AnonymousQueue} where extra consumers only churn UI ordering. A single global
 * concurrency setting would trade those working goods away to fix an unrelated queue.
 */
@ConfigurationProperties(prefix = "jtoye.rabbit")
public class RabbitListenerProperties {

    /**
     * Prefetch for every queue except {@code media.process}.
     *
     * <p>Deliberately 250 — the value
     * {@code AbstractMessageListenerContainer.DEFAULT_PREFETCH_COUNT} already produced when the
     * configurer never ran. Declaring today's effective number rather than a "better" one makes
     * repairing the factory provably behaviour-preserving for the eight untouched queues:
     * the repair and a tuning change cannot then be confused for one another.
     */
    private int defaultPrefetch = 250;

    /** Consumers per queue for every queue except {@code media.process}. See the class javadoc. */
    private int defaultConcurrency = 1;

    /**
     * Prefetch for {@code media.process}.
     *
     * <p>Low by intent. The defect a 250 prefetch creates here is not head-of-line blocking —
     * with one consumer the messages are serial regardless — it is UNFAIR DISTRIBUTION ACROSS
     * REPLICAS, and that gets strictly worse as replicas rise (3 in production): on a burst the
     * first replica to attach can buffer up to 250 unacked messages in its local prefetch window
     * while the other two sit idle with nothing to fetch. 2 leaves one message in flight and one
     * buffered, which is enough to hide fetch latency at a measured 606 ms of service time per
     * message while leaving the rest of a burst available to other replicas.
     */
    private int mediaPrefetch = 2;

    /**
     * Starting consumers on {@code media.process}.
     *
     * <p>1, from measurement rather than instinct. Arm A
     * ({@code infra/load-testing/baselines/2026-07-28-media-A-baseline.md}) recorded peak
     * container CPU of 97.8% at concurrency 1 under a 1-CPU pin — one consumer already saturates
     * one core, and the k8s pod limit is 1000m. It also recorded peak queue depth 0 across all
     * 197 samples, because {@code media.outbox.flush-interval-ms} (5000) paces this pipeline in
     * batches a single consumer drains inside the interval. There is no steady-state backlog for
     * a second consumer to work on.
     */
    private int mediaConcurrency = 1;

    /**
     * Maximum consumers {@code media.process} may scale to under sustained backlog.
     *
     * <p>2 is a ceiling, not a target. It is what the intra-pod budget permits
     * ({@code k8s/scripts/check-consumer-thread-budget.sh}: 8 default endpoints + 2 media + 2
     * httpReserve = 12 against a prod pool of 12) and simultaneously the most a 1000m pod can
     * use, since {@code scrimage-webp} forks a native {@code cwebp} per encode. Raising it to 3
     * breaches the budget; raising the pool to accommodate 3 breaches the CLUSTER-wide
     * {@code check-connection-math.sh} budget at 166 &gt; 157. Both walls independently land on 2.
     */
    private int mediaMaxConcurrency = 2;

    public int getDefaultPrefetch() {
        return defaultPrefetch;
    }

    public void setDefaultPrefetch(int defaultPrefetch) {
        this.defaultPrefetch = defaultPrefetch;
    }

    public int getDefaultConcurrency() {
        return defaultConcurrency;
    }

    public void setDefaultConcurrency(int defaultConcurrency) {
        this.defaultConcurrency = defaultConcurrency;
    }

    public int getMediaPrefetch() {
        return mediaPrefetch;
    }

    public void setMediaPrefetch(int mediaPrefetch) {
        this.mediaPrefetch = mediaPrefetch;
    }

    public int getMediaConcurrency() {
        return mediaConcurrency;
    }

    public void setMediaConcurrency(int mediaConcurrency) {
        this.mediaConcurrency = mediaConcurrency;
    }

    public int getMediaMaxConcurrency() {
        return mediaMaxConcurrency;
    }

    public void setMediaMaxConcurrency(int mediaMaxConcurrency) {
        this.mediaMaxConcurrency = mediaMaxConcurrency;
    }
}
