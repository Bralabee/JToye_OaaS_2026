package uk.jtoye.core.integration.credential;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code integration_credential} (V82). Every finder carries an explicit tenant predicate on top of FORCE
 * RLS (the {@code StaffInviteRepository} rule), so a regression in the GUC pin can never widen what a Group
 * admin sees or touches.
 */
public interface IntegrationCredentialRepository extends JpaRepository<IntegrationCredential, UUID> {

    Optional<IntegrationCredential> findByIdAndTenantId(UUID id, UUID tenantId);

    List<IntegrationCredential> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** The credential an Idempotency-Key created, for a same-key replay. */
    Optional<IntegrationCredential> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /**
     * Whether {@code userId} is the service-account user of any credential of the tenant, active or revoked.
     * {@code StaffManagementService.grant} refuses such a target (Q7): a credential's access is set when it is
     * issued and the Staff API must never widen it.
     */
    boolean existsByTenantIdAndServiceAccountUserId(UUID tenantId, UUID serviceAccountUserId);

    /** The credential a token's {@code azp} names, within the token's own tenant. */
    Optional<IntegrationCredential> findByTenantIdAndKcClientId(UUID tenantId, String kcClientId);

    /**
     * Stamps {@code last_used_at} when it is absent or older than {@code cutoff}, on an active credential
     * only. Conditional in SQL, so two pods recording the same minute write it once, and the row's
     * optimistic-lock version is not bumped (a use must never make a concurrent rotate or delete fail).
     *
     * @return rows written: 1 when stamped, 0 when it was recent enough, revoked or not visible
     */
    @Modifying
    @Query(value = "UPDATE integration_credential SET last_used_at = :now "
            + "WHERE tenant_id = :tenantId AND id = :id AND revoked_at IS NULL "
            + "AND (last_used_at IS NULL OR last_used_at < :cutoff)", nativeQuery = true)
    int touchLastUsed(@Param("tenantId") UUID tenantId, @Param("id") UUID id,
                      @Param("now") OffsetDateTime now, @Param("cutoff") OffsetDateTime cutoff);
}
