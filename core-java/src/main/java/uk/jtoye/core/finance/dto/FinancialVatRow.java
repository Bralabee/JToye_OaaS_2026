package uk.jtoye.core.finance.dto;

import uk.jtoye.core.finance.VatRate;

/**
 * JPQL constructor-target for the per-VAT-rate breakdown query in
 * {@link uk.jtoye.core.finance.FinancialTransactionRepository#aggregateByVatRate}.
 *
 * <p>One row per {@link VatRate} present in the tenant's transaction set —
 * rates with zero rows are absent (consistent with the legacy
 * {@code Collectors.groupingBy(vatRate)} behaviour).
 *
 * <p>Introduced by CQ-02 (Phase 14 Plan 02) to replace the legacy
 * {@code findAll() + 4 stream reductions} implementation of
 * {@link uk.jtoye.core.finance.FinancialTransactionService#getSummary()}.
 *
 * <p>Note: {@link #count()} is typed as {@code long} because JPA
 * {@code COUNT(...)} returns a {@code Long} — the service layer casts to
 * {@code int} when constructing the outward-facing
 * {@link uk.jtoye.core.finance.dto.FinancialSummaryDto.VatBreakdown}.
 */
public record FinancialVatRow(
        VatRate vatRate,
        long totalAmountPennies,
        long totalVatPennies,
        long count
) {}
