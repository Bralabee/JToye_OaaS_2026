package uk.jtoye.core.security.access.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.security.access.ShopRole;

/**
 * The grant the server wrote when an invitation was accepted (37-08, D-07, D-26): the words of the
 * one-time "You've joined {business}. You can work on {shop / all shops}." toast come from here, never
 * from what the page sent.
 *
 * @param businessName the business the person joined
 * @param role         the role they now hold
 * @param shopName     the shop it covers, or {@code null} for every shop of the business
 */
public record StaffInviteAcceptedDto(
        String businessName,
        ShopRole role,
        @Schema(nullable = true, description = "null = all shops of the business") String shopName) {
}
