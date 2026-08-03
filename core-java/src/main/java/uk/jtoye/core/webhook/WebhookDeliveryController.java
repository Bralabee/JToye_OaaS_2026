package uk.jtoye.core.webhook;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.common.idempotency.IdempotencyOutcome;
import uk.jtoye.core.common.idempotency.IdempotencyService;
import uk.jtoye.core.webhook.dto.WebhookDeliveryView;

import java.util.UUID;

/**
 * Vendor-facing webhook delivery-log + manual replay (COMMS-05 / COMMS-06).
 *
 * <p><b>Path prefix:</b> the {@code /api/v1} version prefix is hard-coded because
 * {@code uk.jtoye.core.webhook} is intentionally NOT in
 * {@code WebConfig.API_V1_PACKAGES} (mirrors {@code WebhookSubscriptionController}
 * / {@code RefundController}). All endpoints are JWT-authenticated and
 * tenant-scoped. Errors are RFC 7807 via {@code GlobalExceptionHandler}
 * (unknown subscription/delivery → {@code ResourceNotFoundException} 404).
 *
 * <p><b>No repository here (#444).</b> This controller injected
 * {@link WebhookDeliveryRepository} directly and read it with no transaction, so
 * {@code TenantSetLocalAspect} never pinned the tenant GUC and the FORCE-RLS
 * policy correctly returned nothing — the delivery log was permanently empty.
 * All data access now goes through the {@code @Transactional}
 * {@link WebhookDeliveryService}; do not reintroduce a repository dependency on
 * a controller, because under RLS that failure mode presents as "no data"
 * rather than as an error.
 */
@RestController
@RequestMapping("/api/v1/webhooks/{subscriptionId}/deliveries")
@Tag(name = "Webhook Deliveries", description = "Webhook delivery log + manual replay (COMMS-05)")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class WebhookDeliveryController {

    private final WebhookDeliveryService deliveryService;
    private final IdempotencyService idempotencyService;

    public WebhookDeliveryController(WebhookDeliveryService deliveryService,
                                     IdempotencyService idempotencyService) {
        this.deliveryService = deliveryService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping
    @Operation(summary = "List webhook deliveries",
            description = "Paged delivery log for a subscription, newest first, filterable by status + event type.")
    public Page<WebhookDeliveryView> list(@PathVariable UUID subscriptionId,
                                          @RequestParam(required = false) WebhookDelivery.Status status,
                                          @RequestParam(required = false) String eventType,
                                          Pageable pageable) {
        return deliveryService.list(subscriptionId, status, eventType, pageable);
    }

    @PostMapping("/{deliveryId}/replay")
    @Operation(summary = "Replay a webhook delivery",
            description = "Re-enqueues a past delivery as a NEW attempt tagged replay, reusing the original "
                    + "envelope id so a retry can never double-deliver at the receiver. Supply an "
                    + "Idempotency-Key header to make a retried replay safe: a repeated key replays the "
                    + "original result and never creates a second delivery. The original delivery's status "
                    + "history is left intact.")
    public ResponseEntity<WebhookDeliveryView> replay(
            @PathVariable UUID subscriptionId,
            @PathVariable UUID deliveryId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        // WR-01: without a key, one click == one replay row (legacy behavior).
        // With a key, route through the generic V50 idempotency store so a
        // same-key retry (the frontend api-client auto-retries same key on 5xx)
        // returns the ORIGINAL replay and creates NO second delivery row / POST.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(deliveryService.replay(subscriptionId, deliveryId));
        }

        IdempotencyOutcome<WebhookDeliveryView> outcome = idempotencyService.execute(
                "webhooks.replay",
                idempotencyKey,
                new ReplayRequest(subscriptionId, deliveryId),
                WebhookDeliveryView.class,
                () -> deliveryService.replay(subscriptionId, deliveryId));
        return ResponseEntity.status(outcome.status()).body(outcome.value());
    }

    /** Request identity for the Idempotency-Key hash (same-key/different-target = 422). */
    private record ReplayRequest(UUID subscriptionId, UUID deliveryId) {
    }
}
