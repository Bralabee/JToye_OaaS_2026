package uk.jtoye.core.webhook;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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

import java.util.List;
import java.util.UUID;

/**
 * Vendor webhook delivery-log + manual replay (COMMS-05/COMMS-06). STUB — real
 * implementation lands in the GREEN commit.
 */
@RestController
@RequestMapping("/api/v1/webhooks/{subscriptionId}/deliveries")
public class WebhookDeliveryController {

    @GetMapping
    public Page<Object> list(@PathVariable UUID subscriptionId,
                             @RequestParam(required = false) WebhookDelivery.Status status,
                             @RequestParam(required = false) String eventType,
                             Pageable pageable) {
        return new PageImpl<>(List.of(), pageable, 0);
    }

    @PostMapping("/{deliveryId}/replay")
    public ResponseEntity<Object> replay(@PathVariable UUID subscriptionId,
                                         @PathVariable UUID deliveryId,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
