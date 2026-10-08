package uk.jtoye.core.security.access.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.security.access.ShopRole;

/**
 * What the accept page shows before the person commits (37-08, D-07, D-26; UI-SPEC B2): who invited them,
 * to which business, with which grant, and which of the three account states applies to the invited
 * address.
 *
 * @param businessName the inviting business (the tenant's name)
 * @param role         the role the invitation grants
 * @param shopName     the shop it grants, or {@code null} for every shop of the business
 * @param inviterName  the Group admin who issued it, as the staff directory knows them, or {@code null}
 * @param email        the invited address: the account the person will sign in with
 * @param accountState NEW (no account: create one), EXISTS_HERE (an account of this business: accept with
 *                     no password), OTHER_BUSINESS (the address belongs to another business: accepting is
 *                     refused with 409)
 */
public record StaffInvitePreviewDto(
        String businessName,
        ShopRole role,
        @Schema(nullable = true, description = "null = all shops of the business") String shopName,
        @Schema(nullable = true) String inviterName,
        String email,
        AccountState accountState) {

    /** Which path accepting will take for the invited address. */
    public enum AccountState { NEW, EXISTS_HERE, OTHER_BUSINESS }
}
