package uk.jtoye.core.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Consolidated kitchen "Cook Sheet" response (V87, ADR-0007 / GAP-GTM-03).
 *
 * <p>Aggregates active, non-voided kitchen orders ({@code CONFIRMED}, {@code PREPARING}, {@code READY})
 * for a shop (optionally filtered to a {@code scheduledForDate} or {@code fulfilmentSlotId}) into:
 * <ul>
 *   <li>A per-product batch prep tally ({@link CookSheetItemRow}) with total units, order count, and
 *       the V63 recorded allergen declaration.</li>
 *   <li>An explicit list of per-order V73 allergy notes ({@link CookSheetAllergyAlert}) so batch cooking
 *       never obscures a customer's dietary or allergy instruction.</li>
 * </ul>
 */
public record CookSheetDto(
        @Schema(description = "Shop ID this cook sheet was generated for.")
        UUID shopId,
        @Schema(description = "Optional scheduled fulfilment date filter applied.")
        LocalDate scheduledForDate,
        @Schema(description = "Optional scheduled fulfilment slot ID filter applied.")
        UUID fulfilmentSlotId,
        @Schema(description = "Total active non-voided orders included in this cook sheet.")
        int totalOrders,
        @Schema(description = "Total units across all aggregated line items.")
        int totalUnits,
        @Schema(description = "Aggregated per-product batch prep rows, ordered by totalQuantity descending then productName.")
        List<CookSheetItemRow> items,
        @Schema(description = "Orders in this batch carrying a customer allergyNote (V73), requiring individual attention.")
        List<CookSheetAllergyAlert> allergyAlerts
) {
    public record CookSheetItemRow(
            UUID productId,
            String productName,
            int totalQuantity,
            int orderCount,
            Integer allergenMask,
            List<String> allergenNames
    ) {
    }

    public record CookSheetAllergyAlert(
            UUID orderId,
            String orderNumber,
            String customerName,
            String allergyNote,
            boolean acknowledged
    ) {
    }
}
