package uk.jtoye.core.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * INT-4 (QA council 20260902-134741): the Keycloak {@code sub} of the user who most
     * recently moved this onboarding into VERIFYING — a SUBMIT or RESUBMIT on a request
     * thread — read from the Envers trail: {@code vendor_onboarding_aud} (FORCE RLS,
     * tenant-filtered) joined to {@code revinfo.user_id}, which {@code TenantRevisionListener}
     * fills from the JWT subject. The aggregate has no submitter column and this change may
     * not add a migration, so the audit trail — which already records WHO — is the source.
     * Revisions written off a request thread (the async recompute) carry a NULL user_id and
     * are skipped. Executes under RLS like every other query here.
     */
    @Query(value = "SELECT r.user_id FROM vendor_onboarding_aud a "
            + "JOIN revinfo r ON r.rev = a.rev "
            + "WHERE a.id = :onboardingId AND a.status = 'VERIFYING' AND r.user_id IS NOT NULL "
            + "ORDER BY a.rev DESC LIMIT 1", nativeQuery = true)
    Optional<String> findLatestSubmitterUserId(@Param("onboardingId") UUID onboardingId);
}
