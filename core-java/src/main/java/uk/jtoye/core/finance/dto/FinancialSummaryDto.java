package uk.jtoye.core.finance.dto;

import uk.jtoye.core.finance.VatRate;

import java.util.List;

public record FinancialSummaryDto(
        long totalRevenuePennies,
        long totalExpensesPennies,
        long netAmountPennies,
        long totalVatPennies,
        int transactionCount,
        List<VatBreakdown> vatBreakdown
) {
    public record VatBreakdown(
            VatRate vatRate,
            long totalAmountPennies,
            long totalVatPennies,
            int count
    ) {}
}
