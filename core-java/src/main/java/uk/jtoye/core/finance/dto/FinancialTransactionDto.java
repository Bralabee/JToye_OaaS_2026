package uk.jtoye.core.finance.dto;

import uk.jtoye.core.finance.VatRate;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Data Transfer Object for FinancialTransaction entity.
 * Includes calculated VAT amount for client convenience.
 *
 * This immutable record represents a financial transaction with all computed values.
 */
public record FinancialTransactionDto(
        UUID id,
        UUID tenantId,
        Long amountPennies,
        VatRate vatRate,
        Long vatAmountPennies,
        String description,
        OffsetDateTime createdAt
) {
}
