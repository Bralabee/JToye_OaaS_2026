package uk.jtoye.core.onboarding.dto;

import uk.jtoye.core.onboarding.OnboardingModel;
import uk.jtoye.core.onboarding.OnboardingState;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Vendor-facing view of the onboarding aggregate plus its per-gate breakdown,
 * returned by {@code POST /onboarding}, {@code POST /onboarding/submit} and
 * {@code GET /onboarding/me}. Carries the lifecycle state and the milestone
 * timestamps but never the {@code tenantId} — the tenant is resolved server-side
 * from the caller's context, never echoed.
 *
 * @param id           onboarding id
 * @param status       current lifecycle state
 * @param model        MARKETPLACE / WHITE_LABEL
 * @param shopId       the shop this onboarding gates go-live for
 * @param companyNumber Companies House number, if supplied
 * @param submittedAt     when the vendor submitted (DRAFT → VERIFYING)
 * @param approvedAt      when approval was granted (→ APPROVED)
 * @param wentLiveAt      when the storefront went live (→ LIVE)
 * @param rejectionReason the human reviewer's reason (set on REJECT; null otherwise).
 *                        ONBD-05/D-09 — previously admin-only ({@link AdminOnboardingDto}),
 *                        now surfaced to the vendor so a REJECTED/SUSPENDED terminal state
 *                        can explain itself instead of a bare "contact support".
 * @param reviewPending   ONBD-03/D-03 — server-derived "in review" flag: true iff the
 *                        onboarding is VERIFYING with a MANUAL_REVIEW gate and no
 *                        still-PENDING gate (a human, not an automated check, is the
 *                        remaining blocker). Derived at the single {@code toDto} site so
 *                        the UI never re-computes gate lifecycle logic.
 * @param gates           the per-requirement gate breakdown
 */
public record OnboardingDto(UUID id, OnboardingState status, OnboardingModel model, UUID shopId,
                            String companyNumber, OffsetDateTime submittedAt, OffsetDateTime approvedAt,
                            OffsetDateTime wentLiveAt, String rejectionReason, boolean reviewPending,
                            List<GateDto> gates) {
}
