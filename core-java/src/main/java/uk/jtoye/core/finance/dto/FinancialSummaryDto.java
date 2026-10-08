package uk.jtoye.core.finance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.finance.VatRate;

import java.util.List;

public record FinancialSummaryDto(
        long totalRevenuePennies,
        long totalExpensesPennies,
        long netAmountPennies,
        long totalVatPennies,
        int transactionCount,
        List<VatBreakdown> vatBreakdown,
        @Schema(description = "The scope these figures were filtered on (Phase 37-11): read it for the "
                + "\"Showing ...\" banner rather than the shop switcher's own value.")
        FinanceScopeDto scope
) {
    /**
     * The pre-37-11 shape with no scope attached (the CQ-02 golden and its stabiliser build summaries this
     * way). The controller always attaches the scope it filtered on.
     */
    public FinancialSummaryDto(long totalRevenuePennies, long totalExpensesPennies, long netAmountPennies,
                               long totalVatPennies, int transactionCount, List<VatBreakdown> vatBreakdown) {
        this(totalRevenuePennies, totalExpensesPennies, netAmountPennies, totalVatPennies, transactionCount,
                vatBreakdown, null);
    }

    public record VatBreakdown(
            VatRate vatRate,
            long totalAmountPennies,
            long totalVatPennies,
            int count
    ) {}
}
