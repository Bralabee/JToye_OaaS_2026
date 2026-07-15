package uk.jtoye.core.webhook;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.webhook.dto.WebhookSubscriptionDto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Vendor-facing webhook delivery-log + manual replay (COMMS-05 / COMMS-06).
 *
 * <p><b>Path prefix:</b> the {@code /api/v1} version prefix is hard-coded because
 * {@code uk.jtoye.core.webhook} is intentionally NOT in
 * {@code WebConfig.API_V1_PACKAGES} (mirrors {@code WebhookSubscriptionController}
 * / {@code RefundController}). All endpoints are JWT-authenticated and
 * tenant-scoped (the subscription is verified through
 * {@link WebhookSubscriptionService} which bounds to {@code TenantContext}; RLS
 * bounds the delivery rows). Errors are RFC 7807 via {@code GlobalExceptionHandler}
 * (unknown subscription/delivery → {@code ResourceNotFoundException} 404).
 */
@RestController
@RequestMapping("/api/v1/webhooks/{subscriptionId}/deliveries")
@Tag(name = "Webhook Deliveries", description = "Webhook delivery log + manual replay (COMMS-05)")
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class WebhookDeliveryController {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryController.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSubscriptionService subscriptionService;

    public WebhookDeliveryController(WebhookDeliveryRepository deliveryRepository,
                                     WebhookSubscriptionService subscriptionService) {
        this.deliveryRepository = deliveryRepository;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping
    @Operation(summary = "List webhook deliveries",
            description = "Paged delivery log for a subscription, newest first, filterable by status + event type.")
    public Page<WebhookDeliveryView> list(@PathVariable UUID subscriptionId,
                                          @RequestParam(required = false) WebhookDelivery.Status status,
                                          @RequestParam(required = false) String eventType,
                                          Pageable pageable) {
        requireOwnedSubscription(subscriptionId);
        return deliveryRepository.findLog(subscriptionId, status, eventType, pageable)
                .map(WebhookDeliveryView::from);
    }

    @PostMapping("/{deliveryId}/replay")
    @Operation(summary = "Replay a webhook delivery",
            description = "Re-enqueues a past delivery as a NEW attempt tagged replay, reusing the original "
                    + "envelope id so a retry can never double-deliver at the receiver (Idempotency-Key safe). "
                    + "The original delivery's status history is left intact.")
    public ResponseEntity<WebhookDeliveryView> replay(
            @PathVariable UUID subscriptionId,
            @PathVariable UUID deliveryId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireOwnedSubscription(subscriptionId);

        WebhookDelivery original = deliveryRepository.findByIdAndSubscriptionId(deliveryId, subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Webhook delivery not found: " + deliveryId));

        // A NEW row, PENDING, tagged is_replay/replay_of. It REUSES the original
        // envelope id (X-JToye-Event-Id), so the vendor's receiver dedupes it
        // against the original event — a replay (and its retries) can never
        // double-deliver. The original row is untouched.
        WebhookDelivery replay = new WebhookDelivery();
        replay.setTenantId(original.getTenantId());
        replay.setSubscriptionId(original.getSubscriptionId());
        replay.setEventId(original.getEventId());
        replay.setEventType(original.getEventType());
        replay.setPayload(original.getPayload());
        replay.setStatus(WebhookDelivery.Status.PENDING);
        replay.setReplay(true);
        replay.setReplayOf(original.getId());

        WebhookDelivery saved = deliveryRepository.save(replay);
        log.info("event=webhook_delivery_replayed subscription={} original={} replay={} idempotencyKey={}",
                subscriptionId, original.getId(), saved.getId(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(WebhookDeliveryView.from(saved));
    }

    /** 404 (RFC 7807) unless the subscription exists and belongs to the caller's tenant. */
    private void requireOwnedSubscription(UUID subscriptionId) {
        WebhookSubscriptionDto owned = subscriptionService.getById(subscriptionId);
        if (owned == null) {
            throw new ResourceNotFoundException("Webhook subscription not found: " + subscriptionId);
        }
    }

    /**
     * Read view of a delivery (no {@code payload} body in the log list). Nested to
     * keep the change within the plan's declared file list (22-03 precedent).
     */
    public record WebhookDeliveryView(
            UUID id,
            UUID subscriptionId,
            UUID eventId,
            String eventType,
            String status,
            int attemptCount,
            Integer lastHttpStatus,
            String lastError,
            boolean replay,
            UUID replayOf,
            OffsetDateTime nextAttemptAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        static WebhookDeliveryView from(WebhookDelivery d) {
            return new WebhookDeliveryView(
                    d.getId(),
                    d.getSubscriptionId(),
                    d.getEventId(),
                    d.getEventType(),
                    d.getStatus().name(),
                    d.getAttemptCount(),
                    d.getLastHttpStatus(),
                    d.getLastError(),
                    d.isReplay(),
                    d.getReplayOf(),
                    d.getNextAttemptAt(),
                    d.getCreatedAt(),
                    d.getUpdatedAt());
        }
    }
}
