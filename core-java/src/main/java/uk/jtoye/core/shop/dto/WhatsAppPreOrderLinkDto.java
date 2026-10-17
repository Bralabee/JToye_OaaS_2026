package uk.jtoye.core.shop.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * ADR-0007 / GAP-GTM-06 (#208): Pre-formatted WhatsApp-to-Storefront pre-order link
 * and broadcast message so vendors can convert WhatsApp DM enquiries and group broadcasts
 * directly into allergen-acknowledged, pre-paid scheduled checkout sessions.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "WhatsApp-to-Storefront pre-order checkout link and pre-formatted message (GAP-GTM-06).")
public record WhatsAppPreOrderLinkDto(
        @Schema(description = "Shop ID") UUID shopId,
        @Schema(description = "Shop canonical URL slug") String shopSlug,
        @Schema(description = "Shop display name") String shopName,
        @Schema(description = "Optional scheduled fulfilment slot ID pre-selected by the link") UUID slotId,
        @Schema(description = "Service date of the scheduled slot when slotId is provided") LocalDate serviceDate,
        @Schema(description = "Human-readable fulfilment window label when slotId is provided") String windowLabel,
        @Schema(description = "Order cutoff timestamp when slotId is provided") OffsetDateTime cutoffAt,
        @Schema(description = "Relative storefront checkout path (e.g. /shop/{slug}?slot={slotId})") String storefrontPath,
        @Schema(description = "Ready-to-paste WhatsApp reply/broadcast text with allergen & pre-order checkout link") String prefilledMessage,
        @Schema(description = "Encoded https://wa.me/?text=... deep-link for one-tap WhatsApp sharing") String waMeUrl
) {
}
