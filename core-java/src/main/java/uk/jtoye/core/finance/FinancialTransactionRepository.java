package uk.jtoye.core.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import uk.jtoye.core.finance.dto.FinancialAggregateRow;
import uk.jtoye.core.finance.dto.FinancialVatRow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for FinancialTransaction entity.
 * All queries are automatically tenant-scoped via RLS policies.
 */
@Repository
public interface FinancialTransactionRepository extends JpaRepository<FinancialTransaction, UUID> {

    /**
     * Find transactions by reference (e.g., order number).
     * Tenant-scoped via RLS.
     */
    List<FinancialTransaction> findByReference(String reference);

    /**
     * Find transactions by VAT rate.
     * Useful for VAT reporting.
     */
    List<FinancialTransaction> findByVatRate(VatRate vatRate);

    /**
     * Find transaction by reference (expecting unique reference).
     */
    Optional<FinancialTransaction> findOneByReference(String reference);

    /**
     * CQ-02 scalar aggregate for the current tenant — totalRevenue (sum of
     * positive amounts), totalExpenses (sum of absolute-valued negatives),
     * totalVat (VAT math mirrored exactly from
     * {@link FinancialTransaction#calculateVatAmount()} — multiply BEFORE
     * divide, integer division truncating toward zero, same as the Java
     * switch expression), and transactionCount.
     *
     * <p>No explicit WHERE clause — RLS appends
     * {@code tenant_id = current_tenant_id()} at the SQL rewriter stage,
     * so the query is automatically tenant-scoped.
     *
     * <p>Every {@code SUM(...)} is wrapped in {@code COALESCE(..., 0L)} so
     * that an empty table / empty tenant produces {@code (0,0,0,0)} and
     * not a NullPointerException when JPA binds to primitive {@code long}
     * fields of the constructor-expression target.
     *
     * <p>Qualified-enum literals in the CASE WHEN (e.g.
     * {@code uk.jtoye.core.finance.VatRate.REDUCED}) are supported in
     * Hibernate 6.x (Spring Boot 3.4.2 BOM-managed version).
     */
    @Query("""
            SELECT new uk.jtoye.core.finance.dto.FinancialAggregateRow(
              COALESCE(SUM(CASE WHEN ft.amountPennies > 0 THEN ft.amountPennies ELSE 0L END), 0L),
              COALESCE(SUM(CASE WHEN ft.amountPennies < 0 THEN -ft.amountPennies ELSE 0L END), 0L),
              COALESCE(SUM(CASE
                WHEN ft.vatRate = uk.jtoye.core.finance.VatRate.REDUCED  THEN (ft.amountPennies * 5)  / 100
                WHEN ft.vatRate = uk.jtoye.core.finance.VatRate.STANDARD THEN (ft.amountPennies * 20) / 100
                ELSE 0L END), 0L),
              COUNT(ft)
            )
            FROM FinancialTransaction ft
            """)
    FinancialAggregateRow aggregateForCurrentTenant();

    /**
     * CQ-02 per-VAT-rate breakdown for the current tenant. Groups by
     * {@code vatRate}, emits one {@link FinancialVatRow} per distinct rate,
     * ordered by enum name so output is deterministic across
     * Postgres / Hibernate versions (defence-in-depth — the service layer
     * also sorts by name before constructing the outward DTO list).
     *
     * <p>VAT math is identical to {@link #aggregateForCurrentTenant()} —
     * mirrors {@link FinancialTransaction#calculateVatAmount()}
     * byte-for-byte to preserve parity with the legacy in-memory
     * implementation (pinned by {@code FinancialSummaryGoldenFileTest}).
     */
    @Query("""
            SELECT new uk.jtoye.core.finance.dto.FinancialVatRow(
              ft.vatRate,
              COALESCE(SUM(ft.amountPennies), 0L),
              COALESCE(SUM(CASE
                WHEN ft.vatRate = uk.jtoye.core.finance.VatRate.REDUCED  THEN (ft.amountPennies * 5)  / 100
                WHEN ft.vatRate = uk.jtoye.core.finance.VatRate.STANDARD THEN (ft.amountPennies * 20) / 100
                ELSE 0L END), 0L),
              COUNT(ft)
            )
            FROM FinancialTransaction ft
            GROUP BY ft.vatRate
            ORDER BY ft.vatRate
            """)
    List<FinancialVatRow> aggregateByVatRate();
}
