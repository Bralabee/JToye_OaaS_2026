package uk.jtoye.core.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
