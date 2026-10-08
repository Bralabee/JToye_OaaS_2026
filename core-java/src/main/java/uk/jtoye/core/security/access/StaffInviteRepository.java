package uk.jtoye.core.security.access;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code staff_invite} (V77). Every finder carries an explicit tenant predicate on top of FORCE RLS, so
 * a regression in the GUC pin can never widen what a Group admin sees or touches.
 */
public interface StaffInviteRepository extends JpaRepository<StaffInvite, UUID> {

    Optional<StaffInvite> findByIdAndTenantId(UUID id, UUID tenantId);

    List<StaffInvite> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** The live (neither accepted nor revoked) invitations of one address, backed by the V77 partial index. */
    List<StaffInvite> findByTenantIdAndEmailNormalisedAndAcceptedAtIsNullAndRevokedAtIsNull(UUID tenantId,
                                                                                         String emailNormalised);
}
