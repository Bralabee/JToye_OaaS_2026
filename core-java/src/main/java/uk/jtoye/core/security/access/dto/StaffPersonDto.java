package uk.jtoye.core.security.access.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.security.access.EffectiveAccess;
import uk.jtoye.core.security.access.UserDirectory;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One person on the Staff page (D-09, Phase 37-05): everyone who has signed in to the tenant
 * ({@code user_directory}) plus every grant holder who has no directory row, each with the access
 * the server actually enforces for them.
 *
 * <p>The email is masked exactly as {@link DirectoryEntryDto} masks it (WR-10): first local-part
 * character plus the domain. A grant holder with no directory row has no email, name or last-seen.
 */
@Schema(description = "A person in this tenant with their server-computed effective access.")
public record StaffPersonDto(
        UUID userId,
        @Schema(description = "Masked email (first character + domain); null when the person has never "
                + "signed in here.", nullable = true)
        String maskedEmail,
        @Schema(nullable = true)
        String displayName,
        @Schema(description = "Last sign-in recorded in the directory; null for a grant holder who has "
                + "never signed in here.", nullable = true)
        OffsetDateTime lastSeen,
        EffectiveAccess effectiveAccess) {

    public static StaffPersonDto from(UserDirectory entry, EffectiveAccess access) {
        return new StaffPersonDto(entry.getUserId(), DirectoryEntryDto.maskEmail(entry.getEmail()),
                entry.getDisplayName(), entry.getLastSeen(), access);
    }

    /** A grant holder with no {@code user_directory} row. */
    public static StaffPersonDto grantOnly(UUID userId, EffectiveAccess access) {
        return new StaffPersonDto(userId, null, null, null, access);
    }
}
