package uk.jtoye.core.order.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import uk.jtoye.core.order.CancelReason;

import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/orders/bulk-cancel} (Phase 37-29, D-18, roadmap SC-4).
 *
 * <p>The ids are taken in order and answered in order ({@link BulkCancelResult}). The reason is REQUIRED
 * here, unlike the single cancel's optional body: a bulk reject is the vendor clearing a flood, and the
 * record of why is the point of the action (UI-SPEC D1: "Reason (kept on the order)"). The number of ids
 * is bounded by {@code jtoye.orders.bulk-cancel.max-ids} (default 100), refused with a typed 400 above it
 * — the service checks it, not a {@code @Size}, because the bound is configuration.
 */
@Schema(description = "Which orders to reject, and why. The reason is kept on each order and never shown to the customer.")
public record BulkCancelRequest(
        @NotEmpty(message = "At least one order id is required")
        @ArraySchema(arraySchema = @Schema(description = "The orders to reject, in the order the outcomes are "
                + "reported. At most jtoye.orders.bulk-cancel.max-ids (default 100) per request; a duplicate id "
                + "reports ALREADY_CANCELLED on its second appearance."),
                schema = @Schema(format = "uuid"))
        List<@NotNull(message = "An order id cannot be null") UUID> orderIds,

        @NotNull(message = "A reasonCode is required to reject orders")
        @Schema(description = "Why the orders are being rejected. Stored on each rejected order for the vendor's "
                + "records; never sent to the customer.", example = "SUSPECTED_FAKE")
        CancelReason reasonCode
) {
}
