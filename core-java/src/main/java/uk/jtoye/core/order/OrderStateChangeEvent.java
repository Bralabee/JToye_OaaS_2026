package uk.jtoye.core.order;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Order state-change event, carried through the transactional outbox to the
 * {@code order.events} exchange and fanned out to KDS SSE subscribers.
 *
 * <p>Phase 23 (VSA-02 §3-FLAG #2): {@code shopId} was appended so the SSE fan-out
 * ({@code OrderSseService}) can filter live events to each subscriber's granted
 * shops — the KDS stream must not leak another shop's orders in real time. The
 * field is LAST and a back-compat 6-arg constructor (shopId = null) is retained so
 * pre-existing callers/tests and in-flight outbox payloads (which Jackson
 * deserializes with a null shopId) keep working. The order-state path
 * ({@code OrderService}, {@code PaymentService}, {@code PublicStorefrontService})
 * passes the real shopId; a null shopId is treated as "GROUP_ADMIN-only" by the
 * fan-out filter (never leaked to a scoped subscriber).
 */
public record OrderStateChangeEvent(
    UUID orderId,
    UUID tenantId,
    String orderNumber,
    OrderStatus previousStatus,
    OrderStatus newStatus,
    OffsetDateTime timestamp,
    UUID shopId
) {
    /**
     * Back-compat constructor (shopId unknown → null). Keeps existing 6-arg call
     * sites and deserialization of legacy outbox payloads compiling/working.
     */
    public OrderStateChangeEvent(UUID orderId, UUID tenantId, String orderNumber,
                                 OrderStatus previousStatus, OrderStatus newStatus,
                                 OffsetDateTime timestamp) {
        this(orderId, tenantId, orderNumber, previousStatus, newStatus, timestamp, null);
    }
}
