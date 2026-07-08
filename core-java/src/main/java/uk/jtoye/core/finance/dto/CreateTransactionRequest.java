package uk.jtoye.core.finance.dto;

import jakarta.validation.constraints.NotNull;
import uk.jtoye.core.finance.VatRate;

import java.util.UUID;

/**
 * Request DTO for creating a new financial transaction.
 * Validates required fields for financial compliance.
 *
 * <p>{@code orderId} links a settlement ledger row to its owning order for
 * idempotency (Issue #81 BUG 3). Null for the admin / manual ledger path.
 */
public record CreateTransactionRequest(
        @NotNull(message = "Amount is required")
        Long amountPennies,

        @NotNull(message = "VAT rate is required")
        VatRate vatRate,

        String description,

        UUID orderId
) {
    /**
     * Convenience constructor for the admin / manual ledger path and existing
     * 3-arg callers/tests — no owning order (orderId = null).
     */
    public CreateTransactionRequest(Long amountPennies, VatRate vatRate, String description) {
        this(amountPennies, vatRate, description, null);
    }
}
