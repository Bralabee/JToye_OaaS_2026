package uk.jtoye.core.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.finance.dto.CreateTransactionRequest;
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
     *   <li>Fast-path: {@code findByOrderId} — if a row already exists, return its
     *       DTO without saving.</li>
     *   <li>Race-safe backstop: if two concurrent calls both pass the fast-path,
     *       the partial unique index {@code uq_fin_tx_tenant_order} rejects the
     *       second {@code save()} with {@link DataIntegrityViolationException}; we
     *       catch it, re-query, and return the existing row.</li>
     * </ol>
     */
    public FinancialTransactionDto createTransaction(CreateTransactionRequest request) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating financial transaction for tenant {}: amount={} pennies, VAT rate={}, reference={}, orderId={}",
                tenantId, request.amountPennies(), request.vatRate(), request.description(), request.orderId());

        // Idempotency fast-path: one canonical ledger row per settled order.
        if (request.orderId() != null) {
            Optional<FinancialTransaction> existing =
                    financialTransactionRepository.findByOrderId(request.orderId());
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
                        financialTransactionRepository.findByOrderId(request.orderId());
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
        log.debug("Fetching all financial transactions with pagination: page={}, size={}",
                pageable.getPageNumber(), pageable.getPageSize());
        return financialTransactionRepository.findAll(pageable)
                .map(financialTransactionMapper::toDto);
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
        log.debug("Generating financial summary for current tenant via DB-side aggregation");

        FinancialAggregateRow aggregate = financialTransactionRepository.aggregateForCurrentTenant();
        List<FinancialVatRow> vatRows = financialTransactionRepository.aggregateByVatRate();

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
                vatBreakdown
        );
    }

    // NOTE: No update or delete methods - financial transactions are IMMUTABLE
    // This maintains audit trail integrity per RLS policies and compliance requirements
}
