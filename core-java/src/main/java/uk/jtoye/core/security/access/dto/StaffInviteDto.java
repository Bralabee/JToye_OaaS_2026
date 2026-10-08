package uk.jtoye.core.security.access.dto;

import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.security.access.StaffInvite;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One invitation as a Group admin sees it (D-07). {@code status} is computed by the server at read time
 * (OPEN | EXPIRED | ACCEPTED | CANCELLED). {@code shopId} null means all shops. The token and its digest
 * are never part of this body: the token exists only in the invitee's email.
 *
 * <p>{@code email} is shown in full (unlike the masked directory picker, WR-10): the Group admin typed
 * it, and the pending list and the cancel confirmation must name the address the link went to.
 */
public record StaffInviteDto(
        UUID id,
        String email,
        ShopRole role,
        UUID shopId,
        StaffInvite.Status status,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt,
        UUID createdBy,
        OffsetDateTime acceptedAt,
        OffsetDateTime revokedAt) {

    public static StaffInviteDto from(StaffInvite invite, OffsetDateTime now) {
        return new StaffInviteDto(invite.getId(), invite.getEmailNormalised(), invite.getRole(),
                invite.getShopId(), invite.statusAt(now), invite.getExpiresAt(), invite.getCreatedAt(),
                invite.getCreatedBy(), invite.getAcceptedAt(), invite.getRevokedAt());
    }
}
