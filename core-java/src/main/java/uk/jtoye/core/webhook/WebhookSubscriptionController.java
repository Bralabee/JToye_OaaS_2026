package uk.jtoye.core.webhook;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uk.jtoye.core.webhook.dto.CreateWebhookSubscriptionRequest;
import uk.jtoye.core.webhook.dto.WebhookSubscriptionDto;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Vendor-facing webhook subscription management (COMMS-04).
 *
 * <p><b>Path prefix:</b> the {@code /api/v1} version prefix is hard-coded in the
 * mapping because {@code uk.jtoye.core.webhook} is intentionally NOT in
 * {@code WebConfig.API_V1_PACKAGES} (mirrors {@code RefundController}). Location
 * headers are still built with {@code ServletUriComponentsBuilder.fromCurrentRequest()}
 * so they resolve correctly.
 *
 * <p>All endpoints are JWT-authenticated and tenant-scoped (RLS + app-layer
 * finders). The plaintext {@code signingSecret} is returned ONCE on create and
 * rotate; the list/get DTOs never carry it. Errors are RFC 7807 via
 * {@code GlobalExceptionHandler} (missing id → {@code ResourceNotFoundException}
 * 404; non-HTTPS/private URL → 400).
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Webhooks", description = "Vendor webhook subscription management (COMMS-04)")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class WebhookSubscriptionController {

    private final WebhookSubscriptionService service;

    public WebhookSubscriptionController(WebhookSubscriptionService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List webhook subscriptions",
            description = "Returns this tenant's webhook subscriptions. Never includes the signing secret.")
    public List<WebhookSubscriptionDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a webhook subscription",
            description = "Returns a single subscription by id. Never includes the signing secret.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Subscription found"),
            @ApiResponse(responseCode = "404", description = "Subscription not found")
    })
    public WebhookSubscriptionDto get(@PathVariable UUID id) {
        return service.getById(id);
    }

    @PostMapping
    @Operation(summary = "Register a webhook subscription",
            description = "Creates a subscription. The response carries the signing secret in plaintext ONCE — "
                    + "store it now; it is never returned again.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Subscription created (secret shown once)"),
            @ApiResponse(responseCode = "400", description = "Validation error (non-HTTPS / disallowed URL / no event types)")
    })
    public ResponseEntity<WebhookSubscriptionDto.WithSecret> create(
            @Valid @RequestBody CreateWebhookSubscriptionRequest request) {
        WebhookSubscriptionDto.WithSecret created = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.subscription().id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PostMapping("/{id}/rotate-secret")
    @Operation(summary = "Rotate the signing secret",
            description = "Generates a new signing secret and returns it in plaintext ONCE. Signatures made with "
                    + "the previous secret stop verifying.")
    public WebhookSubscriptionDto.WithSecret rotateSecret(@PathVariable UUID id) {
        return service.rotateSecret(id);
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Pause a subscription", description = "Manually pauses delivery (status PAUSED).")
    public WebhookSubscriptionDto pause(@PathVariable UUID id) {
        return service.pause(id);
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume a subscription",
            description = "Resumes delivery (status ACTIVE) and clears the consecutive-failure counter.")
    public WebhookSubscriptionDto resume(@PathVariable UUID id) {
        return service.resume(id);
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "Revoke a subscription", description = "Terminally revokes the subscription (status REVOKED).")
    public WebhookSubscriptionDto revoke(@PathVariable UUID id) {
        return service.revoke(id);
    }
}
