package uk.jtoye.core.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link WebhookSubscription}.
 *
 * <p>RLS bounds every query to the current tenant at the database layer; the
 * explicit {@code tenantId} finders here are defence-in-depth (app-layer scoping)
 * so a call still returns only the caller's rows even under a superuser
 * connection that bypasses FORCE RLS (e.g. Testcontainers bootstrap role).
 */
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    List<WebhookSubscription> findByTenantId(UUID tenantId);

    List<WebhookSubscription> findByStatus(WebhookSubscription.Status status);

    Optional<WebhookSubscription> findByIdAndTenantId(UUID id, UUID tenantId);
}
