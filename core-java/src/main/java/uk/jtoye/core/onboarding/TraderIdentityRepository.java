package uk.jtoye.core.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link TraderIdentity}. Every query executes under FORCE RLS (V71), so a
 * lookup returns a row only when the session's {@code app.current_tenant_id} GUC is that
 * row's tenant. The explicit {@code tenantId} predicate is kept anyway: it states the intent
 * at the call site and keeps the lookup correct for a role that bypasses row-level security.
 */
public interface TraderIdentityRepository extends JpaRepository<TraderIdentity, UUID> {

    Optional<TraderIdentity> findByTenantId(UUID tenantId);
}
