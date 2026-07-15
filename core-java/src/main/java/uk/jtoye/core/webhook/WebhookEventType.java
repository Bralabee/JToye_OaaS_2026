package uk.jtoye.core.webhook;

/**
 * The event families a vendor can subscribe a webhook to (COMMS-04, D-06).
 *
 * <p>Each value maps to the AMQP routing key(s) its deliveries originate from —
 * the delivery engine (22-05) uses {@link #routingKey()} to match an outbox
 * event to the subscriptions that should receive it. The four families mirror
 * the producers already flowing through the shared V46 transactional outbox
 * (order state, refund, onboarding state, payment).
 */
public enum WebhookEventType {

    /** Order lifecycle state transitions (routing key {@code order.state.*}). */
    ORDER_STATE_CHANGED("order.state.*"),

    /** Order refunded (routing key {@code order.refunded}). */
    ORDER_REFUNDED("order.refunded"),

    /** Vendor onboarding state transitions (routing key {@code onboarding.state.*}). */
    ONBOARDING_STATE_CHANGED("onboarding.state.*"),

    /** Payment events (routing key {@code payment.*}). */
    PAYMENT_EVENT("payment.*");

    private final String routingKey;

    WebhookEventType(String routingKey) {
        this.routingKey = routingKey;
    }

    /** The AMQP routing key (pattern) this family is delivered under. */
    public String routingKey() {
        return routingKey;
    }
}
