package uk.jtoye.core.security.access.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /api/v1/public/staff-invites/preview} (37-08, D-07): the invitation reference from
 * the emailed link, {@code {tenantId}.{token}}.
 *
 * <p>The reference travels in a JSON body, never in a request line: the link carries it in the URL
 * fragment, which a browser never sends to a server, and the accept page POSTs it here (the V75 DSAR-link
 * rule). It is a bearer credential until it is used or expires, so {@link #toString()} never prints it
 * (Spring MVC logs every body it reads at DEBUG through that method).
 */
@Schema(description = "The invitation reference from the emailed link's fragment: {tenantId}.{token}.")
public record StaffInviteRefRequest(
        @Schema(description = "The {tenantId}.{token} reference, exactly as it appears after '#token=' "
                + "in the emailed link.", accessMode = Schema.AccessMode.WRITE_ONLY)
        String ref) {

    @Override
    public String toString() {
        return "StaffInviteRefRequest[ref=<redacted>]";
    }
}
