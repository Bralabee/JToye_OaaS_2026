package uk.jtoye.core.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.finance.dto.CreateTransactionRequest;
import uk.jtoye.core.finance.dto.FinanceExportRow;
import uk.jtoye.core.finance.dto.FinancialAggregateRow;
import uk.jtoye.core.finance.dto.FinancialSummaryDto;
import uk.jtoye.core.finance.dto.FinancialTransactionDto;
import uk.jtoye.core.finance.dto.FinancialVatRow;
import uk.jtoye.core.security.TenantContext;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for financial transaction management operations.
 * All operations are automatically tenant-scoped via RLS policies.
 *
 * Financial transactions are IMMUTABLE after creation (append-only ledger).
 * No update or delete operations are provided to maintain audit trail integrity.
 *
 * Caching Strategy: NO CACHING
 * - Financial data is compliance-sensitive
 * - Audit trail integrity must be maintained
 * - High-volume append-only operations don't benefit from caching
 */
@Service
@Transactional
public class FinancialTransactionService {
    private static final Logger log = LoggerFactory.getLogger(FinancialTransactionService.class);

    private final FinancialTransactionRepository financialTransactionRepository;
    private final FinancialTransactionMapper financialTransactionMapper;

    public FinancialTransactionService(FinancialTransactionRepository financialTransactionRepository,
                                       FinancialTransactionMapper financialTransactionMapper) {
        this.financialTransactionRepository = financialTransactionRepository;
        this.financialTransactionMapper = financialTransactionMapper;
    }

    /**
     * Create a new financial transaction.
     * Automatically assigns tenant from context.
     * Validates required fields (amount, VAT rate).
     * NO CACHING - financial records are append-only and compliance-sensitive.
     *
     * <p>IDEMPOTENT PER ORDER (Issue #81 BUG 3): when {@code request.orderId()} is
     * set, exactly one ledger row may exist per settled order. A card order fires
     * this once on Stripe settlement (PaymentService) and again on the later
     * COMPLETED transition (OrderService); a cash order fires it once. This method
     * makes the second call a no-op:
     * <ol>
     *   <li>Fast-path: {@code findByOrderIdAndEntryKind(orderId, SALE)} — if the order's
     *       SALE already exists, return its DTO without saving. Keyed by SALE since
     *       Phase 37-10: a voided order also has a REVERSAL, and a lookup by order alone
     *       would be a two-row result.</li>
     *   <li>Race-safe backstop: if two concurrent calls both pass the fast-path,
     *       the partial unique index {@code uq_fin_tx_tenant_order} rejects the
     *       second {@code save()} with {@link DataIntegrityViolationException}; we
     *       catch it, re-query, and return the existing row.</li>
     * </ol>
     *
     * <p>The row's kind follows the request (V78): with an order it is that order's
     * {@link EntryKind#SALE}; without one it is {@link EntryKind#MANUAL}, the admin ledger
     * path, which is the same rule V78's backfill applied to the rows that predate it.
     *
     * <p>The row's shop (V79, Phase 37-11) is {@code request.shopId()} for a SALE and always null for a
     * MANUAL row.
     */
    public FinancialTransactionDto createTransaction(CreateTransactionRequest request) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating financial transaction for tenant {}: amount={} pennies, VAT rate={}, reference={}, orderId={}",
                tenantId, request.amountPennies(), request.vatRate(), request.description(), request.orderId());

        // Idempotency fast-path: one canonical ledger row per settled order.
        if (request.orderId() != null) {
            Optional<FinancialTransaction> existing =
                    financialTransactionRepository.findByOrderIdAndEntryKind(request.orderId(), EntryKind.SALE);
            if (existing.isPresent()) {
                log.info("Idempotent ledger no-op for order {} — existing transaction {} retained",
                        request.orderId(), existing.get().getId());
                return financialTransactionMapper.toDto(existing.get());
            }
        }

        // Create transaction entity using mapper
        FinancialTransaction transaction = financialTransactionMapper.toEntity(request);
        transaction.setTenantId(tenantId);
        transaction.setOrderId(request.orderId());
        transaction.setEntryKind(request.orderId() != null ? EntryKind.SALE : EntryKind.MANUAL);
        // Phase 37-11: an order's SALE carries the order's shop (passed by the order writers). A MANUAL row
        // never does, even if a request names one: it has no order to attribute it to, and it belongs to the
        // tenant-wide view only.
        transaction.setShopId(request.orderId() != null ? request.shopId() : null);

        // Save transaction. Explicit flush() forces the INSERT so a partial
        // unique-index violation surfaces HERE (inside the try) rather than
        // being deferred to transaction commit, keeping the race backstop viable.
        try {
            transaction = financialTransactionRepository.save(transaction);
            financialTransactionRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // Race-safe backstop: a concurrent call won the unique index. Re-query
            // and return the row it created rather than surfacing an error.
            if (request.orderId() != null) {
                Optional<FinancialTransaction> existing =
                        financialTransactionRepository.findByOrderIdAndEntryKind(request.orderId(), EntryKind.SALE);
                if (existing.isPresent()) {
                    log.info("Idempotent ledger race resolved for order {} — existing transaction {} retained",
                            request.orderId(), existing.get().getId());
                    return financialTransactionMapper.toDto(existing.get());
                }
            }
            throw e;
        }

        log.info("Created financial transaction {} with amount {} pennies, VAT rate: {}, VAT amount: {} pennies",
                transaction.getId(), transaction.getAmountPennies(),
                transaction.getVatRate(), transaction.calculateVatAmount());

        return financialTransactionMapper.toDto(transaction);
    }

    /**
     * Take an order's SALE back out of the ledger: the void's reversing entry (Phase 37-10, D-10).
     *
     * <p>Writes ONE {@link EntryKind#REVERSAL} row: the sale's amount negated, the sale's VAT rate (no VAT
     * computation changes in this phase, CONTEXT discretion Q2), the same order, and
     * {@code reverses_transaction_id} naming the sale. The sale itself is never touched: the ledger stays
     * append-only. The tenant is the sale's own, so a reversal can only ever land beside the row it reverses.
     *
     * <p>Idempotent per order, exactly like {@link #createTransaction}: an existing REVERSAL is returned
     * unchanged, and the partial unique index {@code uq_fin_tx_tenant_order (tenant_id, order_id,
     * entry_kind)} is the race backstop. Joins the caller's transaction, so the void and its reversal commit
     * or roll back together.
     *
     * @param sale      the order's SALE row
     * @param reference the human reference printed on the row, e.g. "Reversal of ORD-…"
     */
    public FinancialTransactionDto createReversal(FinancialTransaction sale, String reference) {
        if (sale.getEntryKind() != EntryKind.SALE || sale.getOrderId() == null) {
            throw new IllegalArgumentException("Only an order's SALE can be reversed, not " + sale.getEntryKind());
        }
        UUID orderId = sale.getOrderId();

        Optional<FinancialTransaction> existing =
                financialTransactionRepository.findByOrderIdAndEntryKind(orderId, EntryKind.REVERSAL);
        if (existing.isPresent()) {
            log.info("Idempotent reversal no-op for order {} — existing reversal {} retained",
                    orderId, existing.get().getId());
            return financialTransactionMapper.toDto(existing.get());
        }

        FinancialTransaction reversal = new FinancialTransaction(-sale.getAmountPennies(), sale.getVatRate(), reference);
        reversal.setTenantId(sale.getTenantId());
        reversal.setOrderId(orderId);
        reversal.setEntryKind(EntryKind.REVERSAL);
        reversal.setReversesTransactionId(sale.getId());
        // Phase 37-11: the reversal belongs to the same shop as the sale it takes back out, so a shop's
        // figures net the void exactly as the tenant's do.
        reversal.setShopId(sale.getShopId());

        try {
            reversal = financialTransactionRepository.save(reversal);
            financialTransactionRepository.flush();
        } catch (DataIntegrityViolationException e) {
            Optional<FinancialTransaction> raced =
                    financialTransactionRepository.findByOrderIdAndEntryKind(orderId, EntryKind.REVERSAL);
            if (raced.isPresent()) {
                log.info("Idempotent reversal race resolved for order {} — existing reversal {} retained",
                        orderId, raced.get().getId());
                return financialTransactionMapper.toDto(raced.get());
            }
            throw e;
        }

        log.info("Reversed ledger transaction {} for order {} with reversal {} ({} pennies, VAT rate {})",
                sale.getId(), orderId, reversal.getId(), reversal.getAmountPennies(), reversal.getVatRate());
        return financialTransactionMapper.toDto(reversal);
    }

    /**
     * The order's SALE row, if it has one (Phase 37-10). A void reverses it; an order that never settled
     * (no card payment, not completed) has none, and its void writes no ledger row.
     */
    public Optional<FinancialTransaction> findSaleForOrder(UUID orderId) {
        return financialTransactionRepository.findByOrderIdAndEntryKind(orderId, EntryKind.SALE);
    }

    /**
     * Get financial transaction by ID (tenant-scoped).
     * NO CACHING - financial data is compliance-sensitive.
     */
    @Transactional(readOnly = true)
    public Optional<FinancialTransactionDto> getTransactionById(UUID transactionId) {
        log.debug("Fetching financial transaction by ID: {}", transactionId);
        return financialTransactionRepository.findById(transactionId)
                .map(financialTransactionMapper::toDto);
    }

    /**
     * Get all financial transactions (tenant-scoped, pageable).
     * NO CACHING - high-volume append-only data.
     */
    @Transactional(readOnly = true)
    public Page<FinancialTransactionDto> getAllTransactions(Pageable pageable) {
        return getTransactions(FinanceScope.tenantWide(), pageable);
    }

    /**
     * The ledger rows of a scope (Phase 37-11, RWO-027): one shop's rows, or every row of the tenant, in a
     * UK-day range. The caller has already been authorised for the scope (the controller's shop gate).
     * An unfiltered scope reads exactly what {@code findAll} always did.
     */
    @Transactional(readOnly = true)
    public Page<FinancialTransactionDto> getTransactions(FinanceScope scope, Pageable pageable) {
        log.debug("Fetching financial transactions for scope {}: page={}, size={}",
                scope, pageable.getPageNumber(), pageable.getPageSize());
        Page<FinancialTransaction> page = scope.isUnfiltered()
                ? financialTransactionRepository.findAll(pageable)
                : financialTransactionRepository.findForScope(scope.allShops(), scope.shopIdOrPlaceholder(),
                        scope.fromInclusive(), scope.toExclusive(), pageable);
        return page.map(financialTransactionMapper::toDto);
    }

    /**
     * Find transactions by reference (e.g., order number, invoice ID).
     * Useful for financial reconciliation.
     */
    @Transactional(readOnly = true)
    public Optional<FinancialTransactionDto> findByReference(String reference) {
        log.debug("Fetching financial transaction by reference: {}", reference);
        return financialTransactionRepository.findOneByReference(reference)
                .map(financialTransactionMapper::toDto);
    }

    /**
     * Get a financial summary for the current tenant.
     * Aggregates revenue, expenses, VAT breakdown, and transaction count.
     *
     * <p>CQ-02 (Phase 14 Plan 02) — now issues exactly 2 SQL statements
     * (scalar aggregate + per-VAT-rate breakdown) via the repository's
     * JPQL {@code SELECT new ...} constructor-expression queries, instead
     * of pulling every row into JVM heap and reducing in-memory. Scales
     * linearly in DB time, flat in JVM memory. RLS continues to append
     * the tenant predicate at the SQL rewriter stage — no explicit WHERE
     * needed, no risk of cross-tenant leak.
     *
     * <p>VAT math mirrors {@link FinancialTransaction#calculateVatAmount()}
     * byte-for-byte (multiply before divide, integer division truncating
     * toward zero) so the rewrite produces output identical to the legacy
     * {@code findAll() + 4 stream reductions} — pinned by
     * {@code FinancialSummaryGoldenFileTest}.
     *
     * <p>The {@link FinancialSummaryDto.VatBreakdown} list is sorted by
     * {@link VatRate#name()} as defence-in-depth: the JPQL already
     * {@code ORDER BY ft.vatRate}, but a belt-and-braces Java sort
     * guarantees stable ordering regardless of Hibernate enum-rendering
     * quirks across Postgres / H2 dialects.
     */
    @Transactional(readOnly = true)
    public FinancialSummaryDto getSummary() {
        return getSummary(FinanceScope.tenantWide());
    }

    /**
     * The summary of a scope (Phase 37-11, RWO-027 / UXT-027): one shop's figures, or the tenant's, in a
     * UK-day range, still exactly two DB-side aggregate statements. An unfiltered scope runs the two CQ-02
     * aggregates above unchanged (the 1k golden pins their output); any filter runs their scoped twins, whose
     * arithmetic is identical. The response carries the scope it was filtered on.
     */
    @Transactional(readOnly = true)
    public FinancialSummaryDto getSummary(FinanceScope scope) {
        log.debug("Generating financial summary for scope {} via DB-side aggregation", scope);

        FinancialAggregateRow aggregate;
        List<FinancialVatRow> vatRows;
        if (scope.isUnfiltered()) {
            aggregate = financialTransactionRepository.aggregateForCurrentTenant();
            vatRows = financialTransactionRepository.aggregateByVatRate();
        } else {
            aggregate = financialTransactionRepository.aggregateForScope(scope.allShops(),
                    scope.shopIdOrPlaceholder(), scope.fromInclusive(), scope.toExclusive());
            vatRows = financialTransactionRepository.aggregateByVatRateForScope(scope.allShops(),
                    scope.shopIdOrPlaceholder(), scope.fromInclusive(), scope.toExclusive());
        }

        List<FinancialSummaryDto.VatBreakdown> vatBreakdown = vatRows.stream()
                .sorted(Comparator.comparing(row -> row.vatRate().name()))
                .map(row -> new FinancialSummaryDto.VatBreakdown(
                        row.vatRate(),
                        row.totalAmountPennies(),
                        row.totalVatPennies(),
                        (int) row.count()))
                .toList();

        long net = aggregate.totalRevenuePennies() - aggregate.totalExpensesPennies();

        return new FinancialSummaryDto(
                aggregate.totalRevenuePennies(),
                aggregate.totalExpensesPennies(),
                net,
                aggregate.totalVatPennies(),
                (int) aggregate.transactionCount(),
                vatBreakdown,
                scope.toDto()
        );
    }

    /**
     * The CSV export of a scope (Phase 37-11, RWO-027, roadmap SC-2): exactly the rows the summary and the
     * list count for the same scope, oldest first, written by {@link FinanceCsvExporter}. One statement
     * (order numbers and shop names joined DB-side). A scope with no rows is the header row alone.
     */
    @Transactional(readOnly = true)
    public String exportCsv(FinanceScope scope) {
        List<FinanceExportRow> rows = financialTransactionRepository.exportForScope(scope.allShops(),
                scope.shopIdOrPlaceholder(), scope.fromInclusive(), scope.toExclusive());
        log.info("Exporting {} ledger row(s) for scope {}", rows.size(), scope.headerEcho());
        return FinanceCsvExporter.write(rows);
    }

    // NOTE: No update or delete methods - financial transactions are IMMUTABLE
    // This maintains audit trail integrity per RLS policies and compliance requirements
}
