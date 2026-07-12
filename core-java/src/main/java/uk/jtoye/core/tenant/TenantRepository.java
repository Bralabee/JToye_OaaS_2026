package uk.jtoye.core.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@code tenants} registry (issue #102). The table carries
 * NO RLS (it IS the cross-tenant registry — see V2/V48), so these queries work
 * without a tenant GUC: both the request-time status check (interceptor, no
 * transaction-bound tenant) and the Stripe {@code account.updated} webhook
 * (tenant unknown until resolved by account id) depend on that.
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    /** Webhook lookup: resolve the tenant a connected account belongs to (V48 unique index). */
    Optional<Tenant> findByStripeAccountId(String stripeAccountId);

    Optional<Tenant> findByName(String name);
}
