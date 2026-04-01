package uk.jtoye.core.order;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OrderStateChangeEvent(
    UUID orderId,
    UUID tenantId,
    String orderNumber,
    OrderStatus previousStatus,
    OrderStatus newStatus,
    OffsetDateTime timestamp
) {}
