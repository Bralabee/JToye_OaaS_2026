package uk.jtoye.core.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One line of an order's activity timeline (issue #857): what happened, when, and who did it.
 *
 * <p>Read from the order's Envers history ({@code orders_aud} joined to {@code revinfo}), so it is the
 * record the platform already keeps, not a second one that could disagree with it.
 *
 * @param kind        PLACED | STATUS | PAYMENT | VOIDED
 * @param fromStatus  the status before a STATUS change; null for the first thing the history shows
 * @param status      the order's status at this point (PLACED, STATUS, VOIDED) - a PAYMENT keeps the status
 *                    it happened under
 * @param paymentStatus the payment status after a PAYMENT change; null otherwise
 * @param detail      the vendor's reason where one was recorded (a cancel reason code or a void reason)
 * @param at          when the change was committed
 * @param actorType   STAFF when a team member (a user of this tenant's directory) did it, SYSTEM otherwise:
 *                    a guest or signed-in customer at checkout, a payment webhook, a scheduled job
 * @param actorName   the team member's directory display name, never an email (field minimisation); null for
 *                    SYSTEM or a member with no display name - the caller then says "a team member"
 */
@Schema(description = "One entry of an order's activity timeline: what changed, when, and who did it.")
public record OrderTimelineEventDto(
        String kind,
        String fromStatus,
        String status,
        String paymentStatus,
        String detail,
        Instant at,
        String actorType,
        String actorName) {
}
