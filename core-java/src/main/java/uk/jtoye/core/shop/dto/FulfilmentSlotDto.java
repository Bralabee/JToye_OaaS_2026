package uk.jtoye.core.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.shop.ShopFulfilmentSlot;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * ADR-0007 / GAP-GTM-02: Representation of a scheduled fulfilment slot for both
 * vendor slot management and public storefront pre-order selection.
 */
public record FulfilmentSlotDto(
        @Schema(description = "Slot UUID") UUID id,
        @Schema(description = "Shop UUID") UUID shopId,
        @Schema(description = "Scheduled service date (YYYY-MM-DD)") LocalDate serviceDate,
        @Schema(description = "Window start time (HH:mm, UK time)") LocalTime windowStart,
        @Schema(description = "Window end time (HH:mm, UK time)") LocalTime windowEnd,
        @Schema(description = "Formatted human-readable window label (e.g. '2026-10-24 14:00-18:00')") String windowLabel,
        @Schema(description = "Pre-order cutoff timestamp after which new orders for this slot are refused") OffsetDateTime cutoffAt,
        @Schema(description = "Maximum orders allowed in this slot (null = unbounded)", nullable = true) Integer maxOrders,
        @Schema(description = "Maximum units/bowls allowed in this slot (null = unbounded)", nullable = true) Integer maxUnits,
        @Schema(description = "Orders currently reserved in this slot") int reservedOrders,
        @Schema(description = "Units currently reserved in this slot") int reservedUnits,
        @Schema(description = "Remaining order capacity (null = unbounded)", nullable = true) Integer remainingOrders,
        @Schema(description = "Remaining unit capacity (null = unbounded)", nullable = true) Integer remainingUnits,
        @Schema(description = "Whether the slot is active for new reservations") boolean active
) {
    public static FulfilmentSlotDto from(ShopFulfilmentSlot slot) {
        return new FulfilmentSlotDto(
                slot.getId(),
                slot.getShopId(),
                slot.getServiceDate(),
                slot.getWindowStart(),
                slot.getWindowEnd(),
                slot.formatWindowLabel(),
                slot.getCutoffAt(),
                slot.getMaxOrders(),
                slot.getMaxUnits(),
                slot.getReservedOrders(),
                slot.getReservedUnits(),
                slot.remainingOrders(),
                slot.remainingUnits(),
                slot.isActive()
        );
    }
}
