package uk.jtoye.core.finance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.jtoye.core.finance.dto.FinanceExportRow;
import uk.jtoye.core.finance.dto.FinancialAggregateRow;
import uk.jtoye.core.finance.dto.FinancialVatRow;

import java.time.OffsetDateTime;
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
     * The order's ledger row of one kind (Issue #81 BUG 3; keyed by kind since Phase 37-10, D-10).
     * Tenant-scoped via RLS; backed by the partial unique index {@code uq_fin_tx_tenant_order}
     * {@code (tenant_id, order_id, entry_kind)}, so at most one row of each kind exists per order.
     *
     * <p>The unkeyed {@code findByOrderId} this replaces is deleted on purpose: once an order is voided it
     * has two rows, its SALE and its REVERSAL, and a single-row lookup by order alone throws
     * {@code IncorrectResultSizeDataAccessException}. Used as the idempotency fast path and race backstop of
     * {@link FinancialTransactionService#createTransaction} (SALE) and
     * {@link FinancialTransactionService#createReversal} (REVERSAL).
     */
    Optional<FinancialTransaction> findByOrderIdAndEntryKind(UUID orderId, EntryKind entryKind);

    /**
     * CQ-02 scalar aggregate for the current tenant — totalRevenue (sum of
     * positive amounts, LESS every REVERSAL), totalExpenses (sum of absolute-valued
     * negatives that are not reversals),
     * totalVat (VAT math mirrored exactly from
     * {@link FinancialTransaction#calculateVatAmount()} via the HMRC VAT
     * fraction method — {@code (amount * rate) / (100 + rate)}, multiply BEFORE
     * divide, integer division truncating toward zero, same as
     * {@link VatCalculator#vatFromGross(long, VatRate)}), and transactionCount.
     *
     * <p><b>Revenue is SALE minus REVERSAL (Phase 37-10, D-10).</b> A void writes a
     * REVERSAL row carrying the sale's amount negated. Under the sign rule alone that
     * negative would be reported as an EXPENSE, so the voided sale would stay in revenue
     * and the books would show a cost nobody incurred. A REVERSAL therefore adds its
     * (negative) amount to revenue and nothing to expenses. SALE and MANUAL rows keep the
     * sign rule exactly as before: positive is revenue, negative is an expense. Rows with
     * no reversal produce byte-identical output to the pre-V78 query, which the 1k golden
     * ({@code FinancialSummaryGoldenFileTest}) pins. VAT is unchanged: a reversal carries
     * the sale's rate, so its fraction-method VAT is the sale's VAT negated and the
     * voided sale's VAT nets out.
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
              COALESCE(SUM(CASE
                WHEN ft.entryKind = uk.jtoye.core.finance.EntryKind.REVERSAL THEN ft.amountPennies
                WHEN ft.amountPennies > 0 THEN ft.amountPennies
                ELSE 0L END), 0L),
              COALESCE(SUM(CASE
                WHEN ft.entryKind = uk.jtoye.core.finance.EntryKind.REVERSAL THEN 0L
                WHEN ft.amountPennies < 0 THEN -ft.amountPennies
                ELSE 0L END), 0L),
              COALESCE(SUM(CASE
                WHEN ft.vatRate = uk.jtoye.core.finance.VatRate.REDUCED  THEN (ft.amountPennies * 5)  / 105
                WHEN ft.vatRate = uk.jtoye.core.finance.VatRate.STANDARD THEN (ft.amountPennies * 20) / 120
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
     * mirrors {@link FinancialTransaction#calculateVatAmount()} /
     * {@link VatCalculator#vatFromGross(long, VatRate)} byte-for-byte via the
     * HMRC VAT fraction method ({@code (amount * rate) / (100 + rate)}, round
     * down), pinned by {@code FinancialSummaryGoldenFileTest}.
     */
    @Query("""
            SELECT new uk.jtoye.core.finance.dto.FinancialVatRow(
              ft.vatRate,
              COALESCE(SUM(ft.amountPennies), 0L),
              COALESCE(SUM(CASE
                WHEN ft.vatRate = uk.jtoye.core.finance.VatRate.REDUCED  THEN (ft.amountPennies * 5)  / 105
                WHEN ft.vatRate = uk.jtoye.core.finance.VatRate.STANDARD THEN (ft.amountPennies * 20) / 120
                ELSE 0L END), 0L),
              COUNT(ft)
            )
            FROM FinancialTransaction ft
            GROUP BY ft.vatRate
            ORDER BY ft.vatRate
            """)
    List<FinancialVatRow> aggregateByVatRate();

    // ------------------------------------------------------------------
    // Scoped reads (Phase 37-11, RWO-027 / UXT-027): one shop, or every shop, inside a date window.
    //
    // Shape, and why: the scope is bound as NON-NULL parameters only. {@code allShops} selects every row of
    // the tenant (rows with no shop included); otherwise {@code shopId} must match. The window is half-open,
    // {@code fromTs <= created_at < toTs}, and the service passes wide sentinels for an open end. A nullable
    // "{@code :p IS NULL OR ...}" shape was rejected: PostgreSQL cannot type an untyped NULL bind and fails
    // "could not determine data type of parameter". As with the two aggregates above there is NO tenant
    // predicate: RLS appends {@code tenant_id = current_tenant_id()}, and the shop gate
    // ({@code ShopAccessService.require}) has already proven the shop is the caller's tenant's. The VAT and
    // revenue arithmetic is the two aggregates' above, character for character, so a scope that selects
    // every row yields their output exactly.
    // ------------------------------------------------------------------

    /** {@link #aggregateForCurrentTenant()} restricted to a shop (or every shop) and a time window. */
    @Query("""
            SELECT new uk.jtoye.core.finance.dto.FinancialAggregateRow(
              COALESCE(SUM(CASE
                WHEN ft.entryKind = uk.jtoye.core.finance.EntryKind.REVERSAL THEN ft.amountPennies
                WHEN ft.amountPennies > 0 THEN ft.amountPennies
                ELSE 0L END), 0L),
              COALESCE(SUM(CASE
                WHEN ft.entryKind = uk.jtoye.core.finance.EntryKind.REVERSAL THEN 0L
                WHEN ft.amountPennies < 0 THEN -ft.amountPennies
                ELSE 0L END), 0L),
              COALESCE(SUM(CASE
                WHEN ft.vatRate = uk.jtoye.core.finance.VatRate.REDUCED  THEN (ft.amountPennies * 5)  / 105
                WHEN ft.vatRate = uk.jtoye.core.finance.VatRate.STANDARD THEN (ft.amountPennies * 20) / 120
                ELSE 0L END), 0L),
              COUNT(ft)
            )
            FROM FinancialTransaction ft
            WHERE (:allShops = true OR ft.shopId = :shopId)
              AND ft.createdAt >= :fromTs
              AND ft.createdAt < :toTs
            """)
    FinancialAggregateRow aggregateForScope(@Param("allShops") boolean allShops,
                                            @Param("shopId") UUID shopId,
                                            @Param("fromTs") OffsetDateTime fromTs,
                                            @Param("toTs") OffsetDateTime toTs);

    /** {@link #aggregateByVatRate()} restricted to a shop (or every shop) and a time window. */
    @Query("""
            SELECT new uk.jtoye.core.finance.dto.FinancialVatRow(
              ft.vatRate,
              COALESCE(SUM(ft.amountPennies), 0L),
              COALESCE(SUM(CASE
                WHEN ft.vatRate = uk.jtoye.core.finance.VatRate.REDUCED  THEN (ft.amountPennies * 5)  / 105
                WHEN ft.vatRate = uk.jtoye.core.finance.VatRate.STANDARD THEN (ft.amountPennies * 20) / 120
                ELSE 0L END), 0L),
              COUNT(ft)
            )
            FROM FinancialTransaction ft
            WHERE (:allShops = true OR ft.shopId = :shopId)
              AND ft.createdAt >= :fromTs
              AND ft.createdAt < :toTs
            GROUP BY ft.vatRate
            ORDER BY ft.vatRate
            """)
    List<FinancialVatRow> aggregateByVatRateForScope(@Param("allShops") boolean allShops,
                                                     @Param("shopId") UUID shopId,
                                                     @Param("fromTs") OffsetDateTime fromTs,
                                                     @Param("toTs") OffsetDateTime toTs);

    /** The ledger rows of a scope, paged (the transaction list). */
    @Query("""
            SELECT ft FROM FinancialTransaction ft
            WHERE (:allShops = true OR ft.shopId = :shopId)
              AND ft.createdAt >= :fromTs
              AND ft.createdAt < :toTs
            """)
    Page<FinancialTransaction> findForScope(@Param("allShops") boolean allShops,
                                            @Param("shopId") UUID shopId,
                                            @Param("fromTs") OffsetDateTime fromTs,
                                            @Param("toTs") OffsetDateTime toTs,
                                            Pageable pageable);

    /**
     * Every ledger row of a scope as an export line, oldest first, with the order's number and the shop's
     * name joined DB-side (one statement). LEFT joins: a MANUAL row has neither, and still exports.
     */
    @Query("""
            SELECT new uk.jtoye.core.finance.dto.FinanceExportRow(
              ft.id, ft.createdAt, ft.reference, o.orderNumber, ft.entryKind, ft.amountPennies, ft.vatRate, s.name)
            FROM FinancialTransaction ft
            LEFT JOIN Order o ON o.id = ft.orderId
            LEFT JOIN Shop s ON s.id = ft.shopId
            WHERE (:allShops = true OR ft.shopId = :shopId)
              AND ft.createdAt >= :fromTs
              AND ft.createdAt < :toTs
            ORDER BY ft.createdAt, ft.id
            """)
    List<FinanceExportRow> exportForScope(@Param("allShops") boolean allShops,
                                          @Param("shopId") UUID shopId,
                                          @Param("fromTs") OffsetDateTime fromTs,
                                          @Param("toTs") OffsetDateTime toTs);
}
