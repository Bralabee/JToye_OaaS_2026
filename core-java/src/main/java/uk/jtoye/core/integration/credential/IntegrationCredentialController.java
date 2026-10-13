package uk.jtoye.core.integration.credential;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.common.idempotency.Idempotent;
import uk.jtoye.core.integration.credential.IntegrationCredentialService.CredentialListResponse;
import uk.jtoye.core.integration.credential.dto.CreateCredentialRequest;
import uk.jtoye.core.integration.credential.dto.CredentialSecretDto;
import uk.jtoye.core.security.access.HumanCallerGuard;

import java.util.UUID;

/**
 * Integrator credentials (D-22, D-23, D-24; RWO-035): a Group admin issues a scoped API credential instead of
 * sharing a password, and lists them.
 *
 * <p>Authz, in order: {@link HumanCallerGuard#requireHuman()} first (Q7, T-37-107: no credential or machine
 * client manages credentials, typed 403 {@code human-only-action}), then
 * {@code ShopAccessService.requireGroupAdmin()} in every {@link IntegrationCredentialService}
 * method (the {@code StaffController} precedent), so any other caller gets the typed 403
 * {@code shop-access-denied}. Hard-mapped at {@code /api/v1/credentials}: this package is not in
 * {@code WebConfig.API_V1_PACKAGES}.
 *
 * <p>No MCP tool, by recorded reason: an agent must not mint credentials (37-45 probe notes, T-37-107).
 */
@RestController
@RequestMapping("/api/v1/credentials")
@Tag(name = "Credentials", description = "Integrator API credentials: issue, list, rotate, revoke (Group admin only)")
@SecurityRequirement(name = "bearer-jwt")
public class IntegrationCredentialController {

    private final IntegrationCredentialService credentialService;
    private final HumanCallerGuard humanCallerGuard;

    public IntegrationCredentialController(IntegrationCredentialService credentialService,
                                           HumanCallerGuard humanCallerGuard) {
        this.credentialService = credentialService;
        this.humanCallerGuard = humanCallerGuard;
    }

    @PostMapping
    @Idempotent(endpoint = IntegrationCredentialService.CREATE_ENDPOINT)
    @Operation(summary = "Issue an integrator credential",
            description = "Creates a Keycloak client-credentials client for this business with exactly the given "
                    + "#206 scopes, and grants its service account the given role (STAFF or SHOP_MANAGER) on one "
                    + "shop or on every shop, so it is limited exactly like a person. The client secret is returned "
                    + "ONCE, with Cache-Control: no-store, and is never stored by J'Toye. With an Idempotency-Key, a "
                    + "same-key replay returns 200 with the credential, secretShown false and no secret.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Credential issued; the secret is in this response only"),
            @ApiResponse(responseCode = "200", description = "Idempotency-Key replay: the credential, without its secret"),
            @ApiResponse(responseCode = "400", description = "Invalid request: a scope outside the six "
                    + "(credential-scope-invalid), GROUP_ADMIN (credential-role-invalid), or the Keycloak admin "
                    + "connection is not configured (credentials-not-configured)"),
            @ApiResponse(responseCode = "403", description = "Caller is not a Group admin (shop-access-denied), or the "
                    + "token was not issued to the dashboard client (human-only-action)"),
            @ApiResponse(responseCode = "404", description = "The shop is not a shop of this business"),
            @ApiResponse(responseCode = "503", description = "Keycloak did not answer (credential-service-unavailable); nothing was changed")
    })
    public ResponseEntity<CredentialSecretDto> createCredential(
            @Valid @RequestBody CreateCredentialRequest request,
            // IdempotencyHeaderCustomizer documents the header off @Idempotent; listing it here too would
            // double-list it.
            @Parameter(hidden = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        humanCallerGuard.requireHuman();
        CredentialSecretDto credential = credentialService.create(request, idempotencyKey);
        return ResponseEntity.status(credential.secretShown() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(credential);
    }

    @PostMapping("/{id}/rotate")
    @Operation(summary = "Rotate a credential's secret",
            description = "Keycloak generates a new secret and the old one stops working at once. The new secret "
                    + "is returned ONCE, with Cache-Control: no-store; who rotated it and when are recorded. Not "
                    + "idempotent: every call produces a new secret.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Rotated; the new secret is in this response only"),
            @ApiResponse(responseCode = "400", description = "The Keycloak admin connection is not configured (credentials-not-configured)"),
            @ApiResponse(responseCode = "403", description = "Caller is not a Group admin (shop-access-denied), or the "
                    + "token was not issued to the dashboard client (human-only-action)"),
            @ApiResponse(responseCode = "404", description = "No credential of this business has that id"),
            @ApiResponse(responseCode = "409", description = "The credential is revoked (credential-revoked), or its "
                    + "Keycloak client no longer exists (credential-client-missing)"),
            @ApiResponse(responseCode = "503", description = "Keycloak did not answer (credential-service-unavailable); nothing was changed")
    })
    public ResponseEntity<CredentialSecretDto> rotateCredential(@PathVariable UUID id) {
        humanCallerGuard.requireHuman();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(credentialService.rotate(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke a credential",
            description = "Deletes the Keycloak client (no new token can be minted), removes its service account's "
                    + "shop grant (its next request is refused) and records who revoked it and when. The credential "
                    + "stays in the list as REVOKED. Repeatable: revoking a revoked credential returns 204.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Revoked (or already revoked)"),
            @ApiResponse(responseCode = "400", description = "The Keycloak admin connection is not configured (credentials-not-configured)"),
            @ApiResponse(responseCode = "403", description = "Caller is not a Group admin (shop-access-denied), or the "
                    + "token was not issued to the dashboard client (human-only-action)"),
            @ApiResponse(responseCode = "404", description = "No credential of this business has that id"),
            @ApiResponse(responseCode = "503", description = "Keycloak did not answer (credential-service-unavailable); nothing was changed")
    })
    public ResponseEntity<Void> revokeCredential(@PathVariable UUID id) {
        humanCallerGuard.requireHuman();
        credentialService.revoke(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @Operation(summary = "List integrator credentials",
            description = "Every credential of the business, newest first, revoked ones included: name, scopes, "
                    + "shop, role, who issued, rotated and revoked it and when, and lastUsedAt, which is best-effort "
                    + "and never more precise than lastUsedIntervalMinutes. Never carries a secret.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The credentials"),
            @ApiResponse(responseCode = "400", description = "The Keycloak admin connection is not configured (credentials-not-configured)"),
            @ApiResponse(responseCode = "403", description = "Caller is not a Group admin (shop-access-denied), or the "
                    + "token was not issued to the dashboard client (human-only-action)")
    })
    public ResponseEntity<CredentialListResponse> listCredentials() {
        humanCallerGuard.requireHuman();
        return ResponseEntity.ok(credentialService.list());
    }
}
