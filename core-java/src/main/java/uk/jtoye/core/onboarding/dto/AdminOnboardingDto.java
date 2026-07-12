package uk.jtoye.core.onboarding.dto;

import uk.jtoye.core.onboarding.OnboardingModel;
import uk.jtoye.core.onboarding.OnboardingState;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin-queue view of an onboarding application (#178 slice 2), returned by the
 * {@code /onboarding/admin} endpoints. Extends the vendor-facing
 * {@link OnboardingDto} shape with the review-relevant fields an approver needs:
 * the shop's display name (resolved tenant-scoped, never trusted from a request)
 * and the persisted {@code rejectionReason}. Like {@link GateDto} it still
 * withholds the raw gate {@code evidence}/{@code externalRef} provider snapshots.
 *
 * @param id              onboarding id
 * @param status          current lifecycle state
 * @param model           MARKETPLACE / WHITE_LABEL — MARKETPLACE always requires
 *                        this human approval step (ADR-0001 Decision 1)
 * @param shopId          the shop this onboarding gates go-live for
 * @param shopName        the shop's display name ({@code null} if the shop row
 *                        is missing)
 * @param companyNumber   Companies House number, if supplied
 * @param submittedAt     when the vendor submitted (DRAFT → VERIFYING)
 * @param approvedAt      when approval was granted (→ APPROVED)
 * @param rejectionReason the human reviewer's reason (set on REJECT; audited via
 *                        Envers on the aggregate)
 * @param gates           the per-requirement gate breakdown
 */
public record AdminOnboardingDto(UUID id, OnboardingState status, OnboardingModel model, UUID shopId,
                                 String shopName, String companyNumber, OffsetDateTime submittedAt,
                                 OffsetDateTime approvedAt, String rejectionReason, List<GateDto> gates) {
}
