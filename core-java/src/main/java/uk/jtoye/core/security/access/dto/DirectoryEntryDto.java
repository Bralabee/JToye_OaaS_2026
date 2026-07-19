package uk.jtoye.core.security.access.dto;

import uk.jtoye.core.security.access.UserDirectory;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A login-populated {@code user_directory} entry — the grant-target picker source
 * for the GROUP_ADMIN staff screen (Phase 23, D-09). A GROUP_ADMIN grants access
 * only to identities that have already been seen (logged in) within the tenant;
 * there is NO Keycloak enumeration (KC24 admin client is inert). {@code email} is
 * PII, so this DTO is returned ONLY from the GROUP_ADMIN-gated {@code /api/v1/staff}
 * surface (the underlying table is ENABLE+FORCE RLS, proven cross-tenant in
 * {@code ShopStaffRlsPolicyIntegrationTest}).
 */
public record DirectoryEntryDto(
        UUID userId,
        String email,
        String displayName,
        OffsetDateTime lastSeen) {

    public static DirectoryEntryDto from(UserDirectory d) {
        return new DirectoryEntryDto(
                d.getUserId(), d.getEmail(), d.getDisplayName(), d.getLastSeen());
    }
}
