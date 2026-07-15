package uk.jtoye.core.notification.consent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped access to {@link MarketingOptIn} rows. Marketing consent is a
 * presence check keyed on {@code tenantId} so the RLS predicate and the query
 * predicate agree.
 */
public interface MarketingOptInRepository extends JpaRepository<MarketingOptIn, UUID> {

    boolean existsByTenantIdAndRecipient(UUID tenantId, String recipient);

    Optional<MarketingOptIn> findByTenantIdAndRecipient(UUID tenantId, String recipient);
}
