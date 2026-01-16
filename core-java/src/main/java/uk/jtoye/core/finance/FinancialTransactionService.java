package uk.jtoye.core.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.finance.dto.CreateTransactionRequest;
import uk.jtoye.core.finance.dto.FinancialTransactionDto;
import uk.jtoye.core.security.TenantContext;

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
     */
    public FinancialTransactionDto createTransaction(CreateTransactionRequest request) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating financial transaction for tenant {}: amount={} pennies, VAT rate={}, reference={}",
                tenantId, request.amountPennies(), request.vatRate(), request.description());

        // Create transaction entity using mapper
        FinancialTransaction transaction = financialTransactionMapper.toEntity(request);
        transaction.setTenantId(tenantId);

        // Save transaction
        transaction = financialTransactionRepository.save(transaction);

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

    // NOTE: No update or delete methods - financial transactions are IMMUTABLE
    // This maintains audit trail integrity per RLS policies and compliance requirements
}
