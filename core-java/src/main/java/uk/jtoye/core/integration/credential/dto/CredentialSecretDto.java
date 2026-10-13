package uk.jtoye.core.integration.credential.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.integration.credential.IntegrationCredential;
import uk.jtoye.core.security.access.ShopRole;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The create and rotate response (D-22): the credential plus, ONCE, its secret and where to exchange it for
 * a token. Sent with {@code Cache-Control: no-store}. Core never stores, logs or re-displays the secret
 * (T-37-108): a same-key create replay returns the credential with {@code secretShown=false} and
 * {@code clientSecret=null}, and the only way to see a secret again is to rotate it.
 *
 * <p>{@link #toString()} is redacted, so an accidental {@code log.info("{}", dto)} cannot print the secret.
 */
public record CredentialSecretDto(
        UUID id,
        String name,
        String clientId,
        @Schema(description = "The client secret. Present only on the response that created or rotated it; "
                + "null on an Idempotency-Key replay. Store it now: it is never shown again.", nullable = true)
        String clientSecret,
        @Schema(description = "True when this response carries the secret; false on a replay")
        boolean secretShown,
        @Schema(description = "The Keycloak token endpoint for the client-credentials grant")
        String tokenEndpoint,
        List<String> scopes,
        @Schema(nullable = true) UUID shopId,
        ShopRole role,
        IntegrationCredential.Status status,
        UUID createdBy,
        OffsetDateTime createdAt,
        @Schema(nullable = true) UUID rotatedBy,
        @Schema(nullable = true) OffsetDateTime rotatedAt) {

    public static CredentialSecretDto shown(IntegrationCredential c, String secret, String tokenEndpoint) {
        return of(c, secret, true, tokenEndpoint);
    }

    public static CredentialSecretDto withheld(IntegrationCredential c, String tokenEndpoint) {
        return of(c, null, false, tokenEndpoint);
    }

    private static CredentialSecretDto of(IntegrationCredential c, String secret, boolean shown, String tokenEndpoint) {
        return new CredentialSecretDto(c.getId(), c.getName(), c.getKcClientId(), secret, shown, tokenEndpoint,
                c.getScopes(), c.getShopId(), c.getRole(), c.status(), c.getCreatedBy(), c.getCreatedAt(),
                c.getRotatedBy(), c.getRotatedAt());
    }

    @Override
    public String toString() {
        return "CredentialSecretDto[id=" + id + ", clientId=" + clientId + ", secretShown=" + secretShown
                + ", clientSecret=" + (clientSecret == null ? "null" : "<redacted>") + "]";
    }
}
