package uk.jtoye.core.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link PhoneVerificationChallenge}, scoped by tenant_id + RLS.
 */
@Repository
public interface PhoneVerificationChallengeRepository extends JpaRepository<PhoneVerificationChallenge, UUID> {

    Optional<PhoneVerificationChallenge> findTopByTenantIdAndPhoneNormalizedAndVerifiedAtIsNullOrderByCreatedAtDesc(
            UUID tenantId, String phoneNormalized);

    boolean existsByTenantIdAndPhoneNormalizedAndVerifiedAtIsNotNull(
            UUID tenantId, String phoneNormalized);
}
