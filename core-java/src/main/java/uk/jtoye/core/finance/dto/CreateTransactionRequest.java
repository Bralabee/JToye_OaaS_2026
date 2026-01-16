package uk.jtoye.core.finance.dto;

import jakarta.validation.constraints.NotNull;
import uk.jtoye.core.finance.VatRate;

/**
 * Request DTO for creating a new financial transaction.
 * Validates required fields for financial compliance.
 */
public record CreateTransactionRequest(
        @NotNull(message = "Amount is required")
        Long amountPennies,

        @NotNull(message = "VAT rate is required")
        VatRate vatRate,

        String description
) {
}
