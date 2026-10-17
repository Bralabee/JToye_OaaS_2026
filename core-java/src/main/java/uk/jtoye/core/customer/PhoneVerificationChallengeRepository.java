package uk.jtoye.core.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
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

    /**
     * A-4 (PR #949 round 1/2): consume one verification attempt atomically. Returns 1 when an
     * attempt was available and consumed, 0 when the challenge is exhausted or already
     * verified — so N parallel guesses cannot all read the same count and exceed max_attempts.
     * {@code clearAutomatically = true} evicts the persistence context so Hibernate commit
     * auto-flush never overwrites the atomic DB counter with a stale managed snapshot.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PhoneVerificationChallenge c SET c.attempts = c.attempts + 1 "
            + "WHERE c.id = :id AND c.attempts < c.maxAttempts AND c.verifiedAt IS NULL")
    int consumeAttemptIfAvailable(@Param("id") UUID id);

    /**
     * A-4 (PR #949 round 2): stamp {@code verified_at} atomically via conditional UPDATE
     * without flushing a stale managed {@link PhoneVerificationChallenge} snapshot.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PhoneVerificationChallenge c SET c.verifiedAt = :now "
            + "WHERE c.id = :id AND c.verifiedAt IS NULL")
    int markVerified(@Param("id") UUID id, @Param("now") OffsetDateTime now);
}
