package uk.jtoye.core.security.access.dto;

import jakarta.validation.constraints.NotNull;
import uk.jtoye.core.security.access.ShopRole;

import java.util.UUID;

/**
 * Body of {@code POST /api/v1/staff/grant} (Phase 23, VSA-04). Grants
 * {@code (userId, shopId|null, role)} to a directory-known user.
 *
 * <p>{@code userId} is a Keycloak {@code sub} that MUST already appear in the
 * tenant's {@code user_directory} (the picker lists only seen users — D-09; there
 * is no Keycloak enumeration). {@code shopId} is nullable: {@code null} is a
 * tenant-wide (GROUP_ADMIN-shape) grant, a non-null value scopes the grant to that
 * one shop. {@code role} is constrained by the {@link ShopRole} enum (and the V52
 * {@code role} CHECK), so a client cannot escalate beyond the defined tiers.
 */
public record GrantStaffRequest(
        @NotNull UUID userId,
        UUID shopId,
        @NotNull ShopRole role) {
}
