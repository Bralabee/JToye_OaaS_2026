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
 */
public final class StompDestinations {

    /** Broker-managed topic prefix. Everything after it is one AMQP routing key. */
    public static final String TOPIC_PREFIX = "/topic/";

    /** AMQP routing-key word separator. NOT '/', which the broker rejects — see class doc. */
    public static final String SEPARATOR = ".";

    /** The one feature whose destination carries a shop id in the third word. */
    public static final String KITCHEN_FEATURE = "kitchen";

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
        return TOPIC_PREFIX + KITCHEN_FEATURE + SEPARATOR + tenantId + SEPARATOR + shopId;
    }
}
