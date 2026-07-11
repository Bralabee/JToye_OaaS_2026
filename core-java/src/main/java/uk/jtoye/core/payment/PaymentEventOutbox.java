package uk.jtoye.core.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Durable outbox row for a single {@link PaymentEvent}.
 *
 * <p>Written in the same transaction as the Stripe webhook handler, then
 * published to RabbitMQ by {@link PaymentEventOutboxFlusher}. A broker
 * outage no longer drops events — they simply stay PENDING until the
 * next flusher tick retries them.
 */
@Entity
@Table(name = "payment_event_outbox")
public class PaymentEventOutbox {

    public enum Status {
        PENDING,
        SENT,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "routing_key", nullable = false, length = 128)
    private String routingKey;

    /**
     * Destination AMQP exchange for this row (V36 — per-row routing).
     *
     * <p>Defaults to {@code payment.events} so existing payment-event flow is
     * preserved. Refund-domain rows write {@code order.events} so the same
     * outbox table can serve both payment-domain and order-domain events
     * without a second outbox table (UC-2 LOCKED).
     */
    @Column(name = "exchange", nullable = false, length = 128)
    private String exchange = "payment.events";

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    /**
     * Earliest instant the flusher may (re)try this row (V46 — Issue #93).
     * Set to "now" on insert so fresh rows are immediately eligible; pushed
     * out with exponential backoff on each failed publish attempt.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt = OffsetDateTime.now();

    /**
     * TRUE when the payload itself is unrecoverable (e.g. JSON corruption) —
     * retrying can never succeed, so the resurrection pass must skip the row
     * (V46 — Issue #93). FALSE FAILED rows are retry-exhausted but retryable.
     */
    @Column(name = "poison", nullable = false)
    private boolean poison = false;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    public PaymentEventOutbox() {
    }

    public PaymentEventOutbox(UUID tenantId, String eventType, String routingKey, String payload) {
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.routingKey = routingKey;
        this.payload = payload;
    }

    /**
     * Five-arg constructor for callers that target a non-default AMQP exchange
     * (e.g. {@code RefundEventPublisher} → {@code order.events}). The 4-arg
     * constructor remains the entry point for payment-event flow.
     */
    public PaymentEventOutbox(UUID tenantId, String eventType, String routingKey, String payload, String exchange) {
        this(tenantId, eventType, routingKey, payload);
        this.exchange = exchange;
    }

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(OffsetDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public boolean isPoison() { return poison; }
    public void setPoison(boolean poison) { this.poison = poison; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getSentAt() { return sentAt; }
    public void setSentAt(OffsetDateTime sentAt) { this.sentAt = sentAt; }
}
