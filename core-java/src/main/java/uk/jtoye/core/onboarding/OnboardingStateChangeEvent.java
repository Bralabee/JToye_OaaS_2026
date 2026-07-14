package uk.jtoye.core.onboarding;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Fixed-shape, serializable onboarding notification event (Phase 21 / D-01).
 *
 * <p>Written to the shared V46 transactional outbox by
 * {@link OnboardingEventPublisher} and routed to the {@code onboarding.events}
 * exchange. Currently the only occasion that emits one is a MANUAL_REVIEW stall
 * (an onboarding that recomputes but stays parked in {@code VERIFYING} because a
 * mandatory gate needs a human decision), so {@code status} is
 * {@link OnboardingState#VERIFYING} for now — the record is intentionally
 * general so later phases can reuse it for other lifecycle notifications.
 *
 * <p>This is an internal event with no external consumer yet (Phase 24 / #205
 * delivers it), so the field set has a low blast radius. {@code reason} MUST be
 * a fixed, human-readable string — never raw provider/upstream text — mirroring
 * the FhrsGate discipline (raw text stays in WARN logs), because a future
 * J'Toye-side reviewer may surface it (ASVS V7).
 *
 * @param onboardingId the stalled application
 * @param tenantId     owning tenant (stamps the RLS-scoped outbox row)
 * @param shopId       the vendor's shop under review
 * @param status       lifecycle state at emission time (VERIFYING for a stall)
 * @param reason       fixed human-readable explanation (no provider internals)
 * @param occurredAt   emission timestamp
 */
public record OnboardingStateChangeEvent(
        UUID onboardingId,
        UUID tenantId,
        UUID shopId,
        OnboardingState status,
        String reason,
        OffsetDateTime occurredAt
) {}
