package uk.jtoye.core.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link VendorOnboarding} aggregate. All queries execute
 * under RLS (V43 tenant policy), so results are already tenant-scoped by the
 * {@code app.current_tenant_id} GUC — the {@code findByTenantId} finder is the
 * one-per-tenant lookup ({@code UNIQUE(tenant_id)}).
 */
public interface VendorOnboardingRepository extends JpaRepository<VendorOnboarding, UUID> {

    Optional<VendorOnboarding> findByTenantId(UUID tenantId);
}
