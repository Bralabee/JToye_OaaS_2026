package uk.jtoye.core.integration.credential.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.integration.credential.IntegrationCredential;
import uk.jtoye.core.security.access.ShopRole;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One credential as the Developers page lists it (D-24): what it may do, who issued, rotated and revoked it
 * and when, and when it was last used. Never carries the secret.
 */
public record CredentialDto(
        UUID id,
        String name,
        @Schema(description = "The Keycloak clientId: the client_id of the client-credentials grant and the azp "
                + "of every token the credential mints")
        String clientId,
        List<String> scopes,
        @Schema(description = "The shop the role applies to; null means every shop of the business", nullable = true)
        UUID shopId,
        ShopRole role,
        IntegrationCredential.Status status,
        UUID createdBy,
        OffsetDateTime createdAt,
        @Schema(nullable = true) UUID rotatedBy,
        @Schema(nullable = true) OffsetDateTime rotatedAt,
        @Schema(nullable = true) UUID revokedBy,
        @Schema(nullable = true) OffsetDateTime revokedAt,
        @Schema(description = "Best-effort and throttled: never more precise than lastUsedIntervalMinutes. "
                + "Null means never used.", nullable = true)
        OffsetDateTime lastUsedAt,
        @Schema(description = "The Keycloak user behind the credential's service account: the userId its staff "
                + "grant and its staff-directory entry carry, so a client can show that entry as this credential")
        UUID serviceAccountUserId) {

    public static CredentialDto from(IntegrationCredential c) {
        return new CredentialDto(c.getId(), c.getName(), c.getKcClientId(), c.getScopes(), c.getShopId(),
                c.getRole(), c.status(), c.getCreatedBy(), c.getCreatedAt(), c.getRotatedBy(), c.getRotatedAt(),
                c.getRevokedBy(), c.getRevokedAt(), c.getLastUsedAt(), c.getServiceAccountUserId());
    }
}
