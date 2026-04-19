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
 * <p>Introduced by CQ-02 (Phase 14 Plan 02) to replace the legacy
 * {@code findAll() + 4 stream reductions} implementation of
 * {@link uk.jtoye.core.finance.FinancialTransactionService#getSummary()}.
 */
public record FinancialAggregateRow(
        long totalRevenuePennies,
        long totalExpensesPennies,
        long totalVatPennies,
        long transactionCount
) {}
