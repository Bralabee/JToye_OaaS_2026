package uk.jtoye.core.security.access.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /api/v1/public/staff-invites/accept} (37-08, D-07, D-26).
 *
 * <p>For a person with no J'Toye account yet ({@code accountState NEW}) all four fields are required:
 * the account is created with this name and this password. For a person whose account already belongs
 * to the inviting business ({@code EXISTS_HERE}) only {@code ref} is read; names and password are
 * ignored.
 *
 * <p><b>The password (T-37-22).</b> It is a {@code char[]} so the server can zero it once Keycloak has
 * it; it is write-only in JSON (never serialised back) and in the published contract; it is never
 * logged, never stored by core-java and never echoed in an error. {@link #toString()} omits it and the
 * reference (a bearer credential), because Spring MVC logs every body it reads at DEBUG through that
 * method. Length limits are enforced by the service with constant messages, so a refusal never quotes
 * what was sent.
 */
@Schema(description = "Accept a staff invitation. Names and password are required when the invited "
        + "address has no J'Toye account yet, and ignored when it already has one in this business.")
public record AcceptStaffInviteRequest(
        @Schema(description = "The {tenantId}.{token} reference from the emailed link's fragment.",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        String ref,
        @Schema(description = "Given name for a new account (max 255).", maxLength = 255)
        String firstName,
        @Schema(description = "Family name for a new account (max 255).", maxLength = 255)
        String lastName,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(type = "string", format = "password", maxLength = 256,
                accessMode = Schema.AccessMode.WRITE_ONLY,
                description = "The password for a new account. Checked against the vendor realm's "
                        + "password policy by Keycloak; never stored or echoed by the API.")
        char[] password) {

    @Override
    public String toString() {
        return "AcceptStaffInviteRequest[ref=<redacted>, firstName=" + firstName + ", lastName=" + lastName
                + ", password=<redacted>]";
    }
}
