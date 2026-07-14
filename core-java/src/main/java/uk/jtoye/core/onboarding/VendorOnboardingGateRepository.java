package uk.jtoye.core.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link VendorOnboardingGate} child rows. Under RLS the
 * onboarding-scoped finders return only the caller-tenant's gates. The
 * {@code findByOnboardingIdAndGateType} lookup backs the per-gate recompute /
 * resubmit path (V43 {@code UNIQUE(onboarding_id, gate_type)}).
 */
public interface VendorOnboardingGateRepository extends JpaRepository<VendorOnboardingGate, UUID> {

    List<VendorOnboardingGate> findByOnboardingId(UUID onboardingId);

    Optional<VendorOnboardingGate> findByOnboardingIdAndGateType(UUID onboardingId, GateType gateType);

    /**
     * Review-queue predicate (ONBD-03): does this onboarding have a gate row in
     * {@code status}? Backs {@code listReviewPending}'s "VERIFYING + a MANUAL_REVIEW
     * gate" filter without loading the full gate list per candidate.
     */
    boolean existsByOnboardingIdAndStatus(UUID onboardingId, GateStatus status);
}
