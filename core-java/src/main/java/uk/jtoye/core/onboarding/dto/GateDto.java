package uk.jtoye.core.onboarding.dto;

import uk.jtoye.core.onboarding.GateStatus;
import uk.jtoye.core.onboarding.GateType;

import java.time.OffsetDateTime;

/**
 * Vendor-facing view of one onboarding gate row. Deliberately omits the raw
 * {@code evidence} JSONB and {@code externalRef} — those hold provider snapshots
 * (FHRS payloads, Companies House data) that the vendor payload must not leak
 * this slice; only the gate's type, status, whether it blocks approval, a
 * human-readable reason, and when it was last checked are exposed.
 *
 * @param gateType  which compliance requirement
 * @param status    current gate status
 * @param mandatory whether this gate blocks approval
 * @param reason    human-readable reason (chiefly for FAILED / MANUAL_REVIEW / WAIVED)
 * @param checkedAt when the gate was last evaluated ({@code null} until checked)
 */
public record GateDto(GateType gateType, GateStatus status, boolean mandatory,
                      String reason, OffsetDateTime checkedAt) {
}
