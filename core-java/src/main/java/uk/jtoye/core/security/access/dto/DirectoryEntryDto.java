package uk.jtoye.core.security.access.dto;

import uk.jtoye.core.security.access.UserDirectory;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A login-populated {@code user_directory} entry — the grant-target picker source
 * for the GROUP_ADMIN staff screen (Phase 23, D-09). A GROUP_ADMIN grants access
 * only to identities that have already been seen (logged in) within the tenant;
 * there is NO Keycloak enumeration (KC24 admin client is inert).
 *
 * <p><strong>Email is MASKED (WR-10, 23-12 Task 3).</strong> {@code user_directory}
 * is a new PII surface this phase introduced (before V52 the platform held no staff
 * email at all). The {@code email} carried here is masked at the boundary — only the
 * first local-part character + the full domain leave the server (e.g.
 * {@code a***@example.com}) — enough to recognise a colleague in the grant picker
 * WITHOUT bulk-exporting addresses. The full value is retained server-side only and
 * never returned on this endpoint. Grants key on {@code userId}, never on email, so
 * the picker stays fully functional. The DTO is still returned only from the
 * GROUP_ADMIN-gated {@code /api/v1/staff} surface (the underlying table is ENABLE+FORCE
 * RLS, proven cross-tenant in {@code ShopStaffRlsPolicyIntegrationTest}).
 */
public record DirectoryEntryDto(
        UUID userId,
        String email,
        String displayName,
        OffsetDateTime lastSeen) {

    public static DirectoryEntryDto from(UserDirectory d) {
        return new DirectoryEntryDto(
                d.getUserId(), maskEmail(d.getEmail()), d.getDisplayName(), d.getLastSeen());
    }

    /**
     * Mask an email for the grant-target picker (WR-10): keep the first local-part
     * character and the full domain, replacing the rest of the local part with
     * {@code ***} — e.g. {@code alice@example.com → a***@example.com}. Enough to
     * identify a colleague without exposing the full address. Null/blank/degenerate
     * inputs (no {@code @}, or an empty local part) are handled defensively so no
     * fragment leaks.
     */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            // No local part, or no '@' at all — do not leak any of it.
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
