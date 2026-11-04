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
        FinanceScopeDto scope,
        @Schema(description = "#827 (V93): the part of totalRevenuePennies recorded as taken online (card, through "
                + "the platform) when its ledger row was written: Paid online. The deposit of a deposit-split order "
                + "counts here, its cash balance does not. paidOnline + notPaidOnline + paymentNotRecorded = "
                + "totalRevenuePennies.")
        Long paidOnlineRevenuePennies,
        @Schema(description = "#827 (V93): the part of totalRevenuePennies recorded as not taken online: cash, the "
                + "cash balance of a deposit-split order, MANUAL entries. Not paid online.")
        Long notPaidOnlineRevenuePennies,
        @Schema(description = "V93: the part of totalRevenuePennies on legacy rows whose payment channel was never "
                + "recorded (the order could not prove it). In neither figure above; 0 on any ledger written from "
                + "V93 on.")
        Long paymentNotRecordedRevenuePennies
) {
    /** A summary read back from a document that predates #827 (the CQ-02 golden) has no split: that is 0, not an error. */
    public FinancialSummaryDto {
        paidOnlineRevenuePennies = paidOnlineRevenuePennies == null ? 0L : paidOnlineRevenuePennies;
        notPaidOnlineRevenuePennies = notPaidOnlineRevenuePennies == null ? 0L : notPaidOnlineRevenuePennies;
        paymentNotRecordedRevenuePennies = paymentNotRecordedRevenuePennies == null ? 0L : paymentNotRecordedRevenuePennies;
    }

    /**
     * The pre-37-11 shape with no scope attached (the CQ-02 golden and its stabiliser build summaries this
     * way). The controller always attaches the scope it filtered on.
     */
    public FinancialSummaryDto(long totalRevenuePennies, long totalExpensesPennies, long netAmountPennies,
                               long totalVatPennies, int transactionCount, List<VatBreakdown> vatBreakdown) {
        this(totalRevenuePennies, totalExpensesPennies, netAmountPennies, totalVatPennies, transactionCount,
                vatBreakdown, null, 0L, 0L, 0L);
    }

    public record VatBreakdown(
            VatRate vatRate,
            long totalAmountPennies,
            long totalVatPennies,
            int count
    ) {}
}
