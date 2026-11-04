package uk.jtoye.core.finance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.finance.EntryKind;
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
        OffsetDateTime createdAt,
        @Schema(description = "What the row is (Phase 37-10): SALE (an order's settlement), REVERSAL (takes a "
                + "voided order's SALE back out; negative, and not an expense), or MANUAL (no order; positive is "
                + "revenue, negative an expense).")
        EntryKind entryKind,
        @Schema(description = "The order this row belongs to; null for a MANUAL row.")
        UUID orderId,
        @Schema(description = "For a REVERSAL, the id of the SALE it reverses; null otherwise.")
        UUID reversesTransactionId,
        @Schema(description = "The shop the row belongs to (Phase 37-11): the order's shop for a SALE, the "
                + "sale's shop for a REVERSAL; null for a MANUAL row, which only the tenant-wide view lists.")
        UUID shopId,
        @Schema(description = "#827: whether the order behind the row was paid online (card, through the platform): "
                + "Paid online. false for a cash order and for a MANUAL row (Not paid online). Present on the "
                + "list; null where it was not computed (the create response).", nullable = true)
        Boolean paidOnline
) {
    /** The pre-#827 shape, with the paid-online flag not computed. */
    public FinancialTransactionDto(UUID id, UUID tenantId, Long amountPennies, VatRate vatRate,
                                   Long vatAmountPennies, String description, OffsetDateTime createdAt,
                                   EntryKind entryKind, UUID orderId, UUID reversesTransactionId, UUID shopId) {
        this(id, tenantId, amountPennies, vatRate, vatAmountPennies, description, createdAt, entryKind, orderId,
                reversesTransactionId, shopId, null);
    }

    public FinancialTransactionDto withPaidOnline(boolean paidOnline) {
        return new FinancialTransactionDto(id, tenantId, amountPennies, vatRate, vatAmountPennies, description,
                createdAt, entryKind, orderId, reversesTransactionId, shopId, paidOnline);
    }
}
