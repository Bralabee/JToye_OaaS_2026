package uk.jtoye.core.order.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OrderItemDto(
    UUID id,
    UUID productId,
    String productName,
    Integer quantity,
    Long unitPricePennies,
    Long totalPricePennies,
    OffsetDateTime createdAt
) {}
