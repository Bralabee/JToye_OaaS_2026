package uk.jtoye.core.security.access.dto;

import uk.jtoye.core.security.access.GrantSource;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.security.access.ShopStaff;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single {@code shop_staff} grant as returned by the GROUP_ADMIN staff-management
 * surface (Phase 23, VSA-04). {@code shopId == null} is a tenant-wide (GROUP_ADMIN
 * shape) grant; a non-null {@code shopId} is scoped to that one shop.
 *
 * <p>Hand-mapped from {@link ShopStaff} (single small DTO — MapStruct would be
 * ceremony here; mirrors {@code tenant/dto/TenantDto.from}). Carries no secret:
 * {@code user_id}/{@code created_by} are Keycloak {@code sub}s, {@code role} is an
 * enum — the human-recognisable identity lives in {@link DirectoryEntryDto}.
 *
 * <p>{@code grantSource} (V57) surfaces a grant's provenance to the operator so they can
 * see which rows are deliberate ({@link GrantSource#OPERATOR}) versus auto-granted on
 * first sign-in ({@link GrantSource#JIT}) BEFORE flipping the strict-scoping switch, which
 * de-honours JIT tenant-wide GROUP_ADMIN rows (CR-07).
 */
public record StaffMemberDto(
        UUID id,
        UUID userId,
        UUID shopId,
        ShopRole role,
        GrantSource grantSource,
        OffsetDateTime createdAt,
        UUID createdBy) {

    public static StaffMemberDto from(ShopStaff s) {
        return new StaffMemberDto(
                s.getId(), s.getUserId(), s.getShopId(), s.getRole(),
                s.getGrantSource(), s.getCreatedAt(), s.getCreatedBy());
    }
}
