package uk.jtoye.core.integration.credential.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import uk.jtoye.core.security.access.ShopRole;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/credentials} (D-22, D-23). What a Group admin grants an integrator: a name the
 * business recognises, the #206 scopes the token carries, and the shop role its service-account user holds,
 * on one shop or on every shop. The Keycloak clientId is never chosen here: core generates it (T-37-110).
 *
 * <p>The scope allow-list and the role ceiling are the service's checks, not bean validation, so each
 * refusal carries its own problem type ({@code credential-scope-invalid} naming the allowed list,
 * {@code credential-role-invalid}).
 */
public record CreateCredentialRequest(
        @Schema(description = "A name the business recognises, e.g. 'Till' or 'Stock sync'", example = "Till",
                maxLength = 100)
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "The #206 client scopes the credential's tokens carry; a subset of catalog:read, "
                + "catalog:write, orders:read, orders:write, customers:read, customers:write",
                example = "[\"catalog:read\", \"orders:write\"]")
        @NotEmpty @Size(max = 6) List<@NotBlank String> scopes,
        @Schema(description = "The shop role the credential holds: STAFF or SHOP_MANAGER. GROUP_ADMIN is refused.",
                example = "SHOP_MANAGER")
        @NotNull ShopRole role,
        @Schema(description = "The one shop the role applies to; null means every shop of the business",
                nullable = true)
        UUID shopId) {
}
