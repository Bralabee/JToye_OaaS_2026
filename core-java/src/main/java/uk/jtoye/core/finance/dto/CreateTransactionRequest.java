package uk.jtoye.core.finance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import uk.jtoye.core.finance.VatRate;

import java.util.UUID;

/**
 * Request DTO for creating a new financial transaction.
 * Validates required fields for financial compliance.
 *
 * <p>{@code orderId} links a settlement ledger row to its owning order for
 * idempotency (Issue #81 BUG 3). Null for the admin / manual ledger path.
 *
 * <p>{@code shopId} (Phase 37-11, RWO-027) is the owning order's shop, passed by the order writers
 * (completion, card settlement) so the row can be read per shop. It is honoured ONLY together with an
 * {@code orderId}: a MANUAL row (no order) never carries a shop, so it appears only in the tenant-wide view.
 */
public record CreateTransactionRequest(
        @NotNull(message = "Amount is required")
        Long amountPennies,

        @NotNull(message = "VAT rate is required")
        VatRate vatRate,

        String description,

        UUID orderId,

        @Schema(description = "The owning order's shop (Phase 37-11). Honoured only with an orderId: a manual "
                + "row (no order) never carries a shop and appears only in the tenant-wide view.")
        UUID shopId
) {
    /**
     * Convenience constructor for the admin / manual ledger path and existing
     * 3-arg callers/tests — no owning order (orderId = null), no shop.
     */
    public CreateTransactionRequest(Long amountPennies, VatRate vatRate, String description) {
        this(amountPennies, vatRate, description, null, null);
    }

    /** Existing 4-arg callers: an order-linked row whose shop is not supplied (stored NULL). */
    public CreateTransactionRequest(Long amountPennies, VatRate vatRate, String description, UUID orderId) {
        this(amountPennies, vatRate, description, orderId, null);
    }
}
