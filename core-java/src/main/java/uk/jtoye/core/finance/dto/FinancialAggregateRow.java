package uk.jtoye.core.finance.dto;

/**
 * JPQL constructor-target for the scalar-aggregate query in
 * {@link uk.jtoye.core.finance.FinancialTransactionRepository#aggregateForCurrentTenant}.
 *
 * <p>Each field maps 1:1 to a {@code SUM(...)} / {@code COUNT(...)} column in
 * the query's {@code SELECT new FinancialAggregateRow(...)} projection.
 * All scalar sums are wrapped in {@code COALESCE(..., 0L)} on the SQL side so
 * an empty-result query produces {@code FinancialAggregateRow(0L, 0L, 0L, 0L)}
 * rather than triggering a NullPointerException on primitive bind.
 *
 * <p>#827 (V93): {@code paidOnlineRevenuePennies} is the part of {@code totalRevenuePennies} recorded as taken
 * online ({@code SUM(paid_online_pennies)} over the revenue rows) and {@code paymentNotRecordedRevenuePennies} the
 * part of it on legacy rows whose channel was never recorded (NULL). Both are plain column sums in the same
 * statement: no join on the order, no reading of its current status.
 *
 * <p>Introduced by CQ-02 (Phase 14 Plan 02) to replace the legacy
 * {@code findAll() + 4 stream reductions} implementation of
 * {@link uk.jtoye.core.finance.FinancialTransactionService#getSummary()}.
 */
public record FinancialAggregateRow(
        long totalRevenuePennies,
        long totalExpensesPennies,
        long totalVatPennies,
        long transactionCount,
        long paidOnlineRevenuePennies,
        long paymentNotRecordedRevenuePennies
) {
    /** The pre-#827 shape: no online revenue (the CQ-02 service tests build rows this way). */
    public FinancialAggregateRow(long totalRevenuePennies, long totalExpensesPennies, long totalVatPennies,
                                 long transactionCount) {
        this(totalRevenuePennies, totalExpensesPennies, totalVatPennies, transactionCount, 0L, 0L);
    }
}
