package uk.jtoye.core.websocket;

import java.util.UUID;

/**
 * The single source of truth for STOMP destination shape (#266).
 *
 * <p><b>Why this class exists.</b> The destination grammar is a broker constraint, not a
 * naming preference, and it was previously duplicated as string concatenation in the
 * publisher and as index arithmetic in {@link TenantChannelInterceptor}. The two drifted
 * apart from what RabbitMQ actually accepts and nothing failed until the relay was
 * exercised on a cluster. Both sides now derive from here.
 *
 * <p><b>The constraint.</b> RabbitMQ's STOMP plugin maps {@code /topic/<name>} onto the
 * {@code amq.topic} exchange using {@code <name>} as the AMQP routing key, and a routing
 * key may not contain {@code '/'}. Spring's in-memory {@code SimpleBroker} accepts
 * arbitrary slashed paths, so the old {@code /topic/kitchen/{tenantId}/{shopId}} shape
 * worked in development and was rejected outright — {@code ERROR: Invalid destination} —
 * in every environment running {@code STOMP_BROKER_MODE=relay} (k8s base, inherited by
 * staging and production). Verified against the live broker on 2026-07-26: the slashed
 * form is rejected, this dotted form receives a RECEIPT.
 *
 * <p><b>The shape.</b> {@code /topic/{feature}.{tenantId}[.{qualifier}]} — one dot-separated
 * segment after the prefix, which is also the idiomatic AMQP topic routing key. The tenant
 * id is always the second word: {@link TenantChannelInterceptor} relies on that position to
 * enforce the tenant wall, so a new feature topic MUST keep it there.
 *
 * <p><b>The shape is asserted at CONSTRUCTION (27-04, D-09), and the placement is the point.</b>
 * {@link #assertPublishable} runs inside this builder, so a malformed destination is caught
 * where it is built — in a pure function that cannot abort a message listener.
 *
 * <p>The obvious alternative, asserting on the publish path in {@code OrderStateChangeListener},
 * was rejected as an <em>availability regression</em>. A throw there escapes the swallowing
 * {@code catch} around the broadcast, propagates out of the {@code @RabbitListener}, exhausts the
 * 3-attempt retry advice and dead-letters the order event to {@code order.state-changes.dlq} —
 * and {@code sendEmailForState} on the following line never runs. Because a destination is
 * deterministic per {@code (tenant, shop)}, a shape defect is not intermittent: it would fire on
 * every message for that tenant. That converts "the KDS silently stops updating" into "all
 * order-state processing and all order confirmation emails stop for that tenant" — trading a
 * working good away to add a new one. {@code OrderStateChangeListener} is therefore deliberately
 * NOT modified; its swallowing catch is correct for transport failures.
 *
 * <p><b>Honest coverage note.</b> {@link #kitchen} takes {@code UUID}s, and a UUID's
 * {@code toString()} can never contain {@code '/'}. So on today's tree this assertion cannot fire
 * from bad <em>input</em> — it fires from a bad <em>shape constant</em> ({@link #SEPARATOR},
 * {@link #TOPIC_PREFIX}) or from a future overload taking caller-supplied text. That is precisely
 * the #266 regression class it exists to catch. It does not see a caller that bypasses this class
 * and hand-builds a string; that residual is covered by {@code StompPublishCallSiteTest}, not by
 * silence.
 *
 * <p>Cross-reference <b>#289</b> (the STOMP shop-gate is hard-coded to the kitchen topic): the
 * same one-feature assumption recorded there is why the guard belongs in the builder — a future
 * shop-scoped topic inherits it for free.
 */
public final class StompDestinations {

    /** Broker-managed topic prefix. Everything after it is one AMQP routing key. */
    public static final String TOPIC_PREFIX = "/topic/";

    /** AMQP routing-key word separator. NOT '/', which the broker rejects — see class doc. */
    public static final String SEPARATOR = ".";

    /** The one feature whose destination carries a shop id in the third word. */
    public static final String KITCHEN_FEATURE = "kitchen";

    /**
     * Features whose destinations carry a shop id and therefore need a per-shop grant check
     * on SUBSCRIBE, on top of the tenant wall (#289).
     *
     * <p><b>Why a set and not an {@code equals} in the interceptor.</b> The gate previously read
     * {@code if (KITCHEN_FEATURE.equals(parts[FEATURE_WORD]))}. That is correct today, because
     * {@code kitchen} is the only shop-scoped topic — and it is <em>default-open</em>: a second
     * shop-scoped feature added tomorrow gets the tenant wall and no shop check, silently, which
     * is the CR-02 class of gap re-opened. Nothing about adding a destination factory prompts
     * anyone to edit the interceptor.
     *
     * <p>Membership here is now the single declaration, and {@code StompShopGateCoverageTest}
     * fails the build when a shop-scoped factory exists whose feature is not in this set. So the
     * registry cannot silently fall behind the factories — the omission is what the test detects,
     * not something a reviewer has to notice.
     *
     * <p><b>Adding a shop-scoped topic:</b> add the factory, add its feature word here. Forgetting
     * the second step is a red build, not a security hole.
     */
    public static final java.util.Set<String> SHOP_SCOPED_FEATURES = java.util.Set.of(KITCHEN_FEATURE);

    /** Word positions within the routing key (after {@link #TOPIC_PREFIX} is stripped). */
    public static final int FEATURE_WORD = 0;
    public static final int TENANT_WORD = 1;
    public static final int SHOP_WORD = 2;

    private StompDestinations() {
    }

    /**
     * The kitchen-display destination for one shop:
     * {@code /topic/kitchen.{tenantId}.{shopId}}.
     */
    public static String kitchen(UUID tenantId, UUID shopId) {
        return assertPublishable(
                TOPIC_PREFIX + KITCHEN_FEATURE + SEPARATOR + tenantId + SEPARATOR + shopId);
    }

    /**
     * Returns {@code destination} if the broker can actually route it, or throws.
     *
     * <p>A pure function by design — see the class javadoc for why this must never be able to
     * abort a message listener.
     *
     * <p><b>Package-private on purpose, not by oversight.</b> {@link #kitchen} cannot produce a
     * slashed destination from {@code UUID} input, so a test driving only the public API could
     * never exercise the reject arm — the assertion would be observed passing and never proven
     * capable of failing. Package visibility lets {@code StompDestinationsTest} call it with
     * arbitrary text, which makes the reject arm a permanent CI-runnable criterion rather than a
     * one-off scratch-build experiment.
     *
     * <p>The reason text is reused <b>verbatim</b> from {@link TenantChannelInterceptor}'s
     * SUBSCRIBE-side rejection so the publish wall and the subscribe wall cannot drift in wording.
     * An operator grepping logs for one finds the other.
     *
     * @throws IllegalArgumentException if the destination is not broker-routable
     */
    static String assertPublishable(String destination) {
        if (destination == null || !destination.startsWith(TOPIC_PREFIX)) {
            throw new IllegalArgumentException(
                    "Topic destinations must start with " + TOPIC_PREFIX + ": " + destination);
        }
        String routingKey = destination.substring(TOPIC_PREFIX.length());
        if (routingKey.indexOf('/') >= 0) {
            throw new IllegalArgumentException(
                    "Topic destinations must be a single dot-separated segment; '/' is not a valid "
                            + "routing-key character: " + destination);
        }
        return destination;
    }
}
