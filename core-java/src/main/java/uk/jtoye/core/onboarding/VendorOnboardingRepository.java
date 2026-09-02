package uk.jtoye.core.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
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

    /**
     * Admin-queue finder (#178 slice 2): onboardings currently in {@code status},
     * oldest submission first. Executes under RLS, so it returns only the
     * caller-tenant's rows — see {@code OnboardingAdminController} for why the
     * queue is tenant-scoped in this slice.
     */
    List<VendorOnboarding> findByStatusOrderBySubmittedAtAsc(OnboardingState status);

    /**
     * Review-queue finder (INT-1 / A15): onboardings in ANY of {@code statuses}, oldest
     * submission first. Backs {@code listReviewPending}, whose membership is decided by the
     * presence of a MANUAL_REVIEW gate row rather than by a single lifecycle state — a
     * parked gate beside a FAILED one sits in ACTION_REQUIRED, not VERIFYING. Executes under
     * RLS like every other finder here.
     */
    List<VendorOnboarding> findByStatusInOrderBySubmittedAtAsc(Collection<OnboardingState> statuses);
}
