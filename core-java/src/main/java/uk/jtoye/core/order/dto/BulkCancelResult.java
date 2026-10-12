package uk.jtoye.core.order.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Response of {@code POST /api/v1/orders/bulk-cancel} (Phase 37-29, D-18, roadmap SC-4): one outcome per
 * requested id, in request order.
 *
 * <p>The whole batch is 200 whatever the outcomes: a bulk reject is a set of independent, idempotent
 * actions, each committed on its own, and the caller learns what happened to each one rather than losing
 * the batch to the first order someone else accepted meanwhile. Re-sending the same request reports
 * {@link Outcome#ALREADY_CANCELLED} for everything the first call rejected and writes nothing.
 */
@Schema(description = "One outcome per requested order id, in request order.")
public record BulkCancelResult(
        @ArraySchema(arraySchema = @Schema(description = "The outcomes, one per requested id, in request order."))
        List<Entry> results
) {

    /** What happened to one id. */
    @Schema(enumAsRef = true, description = "REJECTED: it was PENDING and is now CANCELLED, with the reason stored. "
            + "ALREADY_ACCEPTED: the kitchen had already taken it (or it is not a placed order), so it was left as it was. "
            + "ALREADY_CANCELLED: it was already cancelled, refunded or voided; nothing to do. "
            + "NOT_FOUND: no such order the caller may act on (it does not exist, belongs to a shop the caller is not "
            + "granted, or belongs to another tenant; these are deliberately indistinguishable).")
    public enum Outcome {
        /** The order was PENDING and has been cancelled through the ordinary cancel transition. */
        REJECTED,
        /** The order had already moved past PENDING (or is a DRAFT, which is not yet a placed order); untouched. */
        ALREADY_ACCEPTED,
        /** The order was already CANCELLED or REFUNDED, or voided; untouched. */
        ALREADY_CANCELLED,
        /** Nothing the caller may act on answers to this id. Non-disclosing by design (T-37-74). */
        NOT_FOUND
    }

    /** One id and its outcome. */
    @Schema(description = "One requested order id and what happened to it.")
    public record Entry(
            @Schema(description = "The requested order id, echoed.", format = "uuid")
            UUID orderId,
            @Schema(description = "What happened to it.")
            Outcome outcome
    ) {
    }
}
