package uk.jtoye.core.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.WebhookSubscriptionStateException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.webhook.dto.CreateWebhookSubscriptionRequest;
import uk.jtoye.core.webhook.dto.WebhookSubscriptionDto;
import uk.jtoye.core.webhook.dto.WebhookTestEventView;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped lifecycle for {@link WebhookSubscription} (COMMS-04).
 *
 * <p>Every read/write is bounded to {@link TenantContext} via explicit
 * {@code tenantId} finders (defence-in-depth on top of FORCE RLS). The
 * {@code signingSecret} is generated with {@link SecureRandom} (256 bits,
 * base64url) and returned to the caller in plaintext ONLY on create and rotate —
 * it is never logged and never carried on a read DTO.
 *
 * <p><strong>GROUP_ADMIN only (QA-council 20260902 SEC-1).</strong> A webhook subscription is a
 * tenant-wide integration credential: create/rotate mint the HMAC signing secret every
 * downstream receiver trusts, revoke terminally kills the integration, and V55 carries no
 * {@code shop_id} to scope it narrower. Every public entry point therefore opens with
 * {@link ShopAccessService#requireGroupAdmin()} — the SAME service-boundary gate
 * {@code StaffManagementService} puts on the analogous tenant-wide admin surface — BEFORE the
 * ownership lookup, so a scoped caller receives the typed shop-access 403 and never learns
 * whether the id exists. Before this gate the controllers fell through to
 * {@code anyRequest().authenticated()} and a STAFF-rank user granted one shop could rotate
 * the tenant's secrets. Under the strict-scoping-OFF default the gate is a no-op for every
 * ungranted (implicit-admin) user and for realm admins; it bites exactly the population
 * holding an explicit per-shop grant, which is the population it exists for.
 */
@Service
@Transactional
public class WebhookSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookSubscriptionService.class);

    /** 256-bit signing secret (Stripe-comparable strength). */
    private static final int SECRET_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final WebhookSubscriptionRepository repository;
    private final WebhookUrlValidator urlValidator;
    private final ShopAccessService shopAccessService;
    private final WebhookDeliveryRepository deliveryRepository;
    private final JsonMapper jsonMapper;
    private final WebhookProperties properties;

    public WebhookSubscriptionService(WebhookSubscriptionRepository repository,
                                      WebhookUrlValidator urlValidator,
                                      ShopAccessService shopAccessService,
                                      WebhookDeliveryRepository deliveryRepository,
                                      JsonMapper jsonMapper,
                                      WebhookProperties properties) {
        this.repository = repository;
        this.urlValidator = urlValidator;
        this.shopAccessService = shopAccessService;
        this.deliveryRepository = deliveryRepository;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    public WebhookSubscriptionDto.WithSecret create(CreateWebhookSubscriptionRequest request) {
        shopAccessService.requireGroupAdmin();
        urlValidator.validate(request.getTargetUrl());

        List<WebhookEventType> types = request.getEventTypes();
        if (types == null || types.isEmpty()) {
            throw new IllegalArgumentException("At least one event type is required");
        }

        UUID tenantId = currentTenant();
        String secret = generateSecret();

        WebhookSubscription entity = new WebhookSubscription();
        entity.setTenantId(tenantId);
        entity.setTargetUrl(request.getTargetUrl().trim());
        entity.setEventTypes(toNames(types));
        entity.setSigningSecret(secret);
        entity.setStatus(WebhookSubscription.Status.ACTIVE);
        entity.setConsecutiveFailures(0);

        entity = repository.save(entity);
        log.info("Created webhook subscription {} for tenant {} ({} event type(s))",
                entity.getId(), tenantId, types.size());

        return new WebhookSubscriptionDto.WithSecret(toDto(entity), secret);
    }

    @Transactional(readOnly = true)
    public List<WebhookSubscriptionDto> list() {
        shopAccessService.requireGroupAdmin();
        UUID tenantId = currentTenant();
        Map<UUID, Long> held = new HashMap<>();
        for (Object[] row : deliveryRepository.countByStatusPerSubscription(tenantId, WebhookDelivery.Status.HELD)) {
            held.put((UUID) row[0], (Long) row[1]);
        }
        return repository.findByTenantId(tenantId).stream()
                .map(e -> toDto(e, held.getOrDefault(e.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public WebhookSubscriptionDto getById(UUID id) {
        shopAccessService.requireGroupAdmin();
        return toDto(require(id));
    }

    public WebhookSubscriptionDto.WithSecret rotateSecret(UUID id) {
        shopAccessService.requireGroupAdmin();
        WebhookSubscription entity = require(id);
        ensureNotRevoked(entity);

        // Regenerate + persist so signatures made with the OLD secret no longer
        // verify at the receiver (T-22-03-04). The previous value is overwritten.
        String secret = generateSecret();
        entity.setSigningSecret(secret);
        entity = repository.save(entity);
        log.info("Rotated credential for webhook subscription {}", id);

        return new WebhookSubscriptionDto.WithSecret(toDto(entity), secret);
    }

    public WebhookSubscriptionDto pause(UUID id) {
        shopAccessService.requireGroupAdmin();
        WebhookSubscription entity = require(id);
        ensureNotRevoked(entity);
        entity.setStatus(WebhookSubscription.Status.PAUSED);
        entity = repository.save(entity);
        // UXT-038: what is queued is kept for resume, never thrown away by the pause.
        int held = deliveryRepository.holdQueued(entity.getTenantId(), id,
                EnumSet.of(WebhookDelivery.Status.PENDING, WebhookDelivery.Status.RETRYING),
                WebhookDelivery.Status.HELD, OffsetDateTime.now());
        log.info("Paused webhook subscription {} ({} queued deliveries held)", id, held);
        return toDto(entity);
    }

    public WebhookSubscriptionDto resume(UUID id) {
        shopAccessService.requireGroupAdmin();
        WebhookSubscription entity = require(id);
        ensureNotRevoked(entity);
        entity.setStatus(WebhookSubscription.Status.ACTIVE);
        entity.setConsecutiveFailures(0); // a manual resume clears the failure trip
        entity = repository.save(entity);
        // UXT-038: "Resume and deliver held events" — every held row is due again, fresh attempt budget.
        int requeued = deliveryRepository.requeueHeld(entity.getTenantId(), id,
                WebhookDelivery.Status.HELD, WebhookDelivery.Status.PENDING, OffsetDateTime.now());
        log.info("Resumed webhook subscription {} ({} held deliveries re-queued)", id, requeued);
        return toDto(entity);
    }

    public WebhookSubscriptionDto revoke(UUID id) {
        shopAccessService.requireGroupAdmin();
        WebhookSubscription entity = require(id);
        entity.setStatus(WebhookSubscription.Status.REVOKED); // terminal
        entity = repository.save(entity);
        // A revoked endpoint can never be delivered to, so nothing stays held for it.
        int failed = deliveryRepository.failHeld(entity.getTenantId(), id, WebhookDelivery.Status.HELD,
                WebhookDelivery.Status.FAILED, "subscription not active: REVOKED", OffsetDateTime.now());
        log.info("Revoked webhook subscription {} ({} held deliveries failed)", id, failed);
        return toDto(entity);
    }

    /**
     * #879 (UXT-122): queue one {@code webhook.test} ping for an ACTIVE endpoint.
     *
     * <p>A real {@code webhook_delivery} row, so the ping takes the whole production path: the worker signs the
     * stored bytes with the subscription's current secret, the SSRF-hardened client POSTs them to the validated
     * address, and the outcome lands in the delivery log. A green ping therefore means a real event will arrive.
     * Only ACTIVE endpoints are pinged: the worker does not attempt a paused one, so queueing would only produce a
     * ping that sits HELD, and a revoked one is dead. The ping carries no tenant data.
     *
     * <p>A failed ping is terminal after one attempt and never counts toward auto-pause
     * ({@code WebhookDeliveryWorker}): the integrator is testing, and a retry schedule measured in hours is for
     * real events.
     */
    public WebhookTestEventView sendTestEvent(UUID id) {
        shopAccessService.requireGroupAdmin();
        WebhookSubscription entity = require(id);
        if (entity.getStatus() != WebhookSubscription.Status.ACTIVE) {
            throw new WebhookSubscriptionStateException(
                    "A test event can only be sent to an active endpoint, and this one is "
                            + entity.getStatus().name() + ". "
                            + (entity.getStatus() == WebhookSubscription.Status.REVOKED
                            ? "A revoked endpoint is permanent; register a new one."
                            : "Resume it first."),
                    entity.getStatus().name());
        }

        UUID eventId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String payload = jsonMapper.writeValueAsString(new WebhookEventEnvelope(
                eventId, WebhookDelivery.TEST_EVENT_TYPE, entity.getTenantId(), now,
                properties.getEnvelope().getVersion(),
                Map.of("subscriptionId", entity.getId().toString(),
                        "message", "This is a test event from J'Toye. Nothing has happened to any order; "
                                + "no action is needed.")));

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setTenantId(entity.getTenantId());
        delivery.setSubscriptionId(entity.getId());
        delivery.setEventId(eventId);
        delivery.setEventType(WebhookDelivery.TEST_EVENT_TYPE);
        delivery.setPayload(payload);
        delivery.setStatus(WebhookDelivery.Status.PENDING);
        WebhookDelivery saved = deliveryRepository.save(delivery);
        log.info("event=webhook_test_event_queued subscription={} delivery={}", id, saved.getId());
        return new WebhookTestEventView(saved.getId(), eventId, saved.getStatus().name());
    }

    /**
     * #879 (UXT-122): delete a REVOKED endpoint and its delivery log.
     *
     * <p>Revoked is the vendor's own, terminal "I am done with this" (a revoked endpoint can never be delivered
     * to), so only a revoked one can be deleted; a live or paused endpoint answers a typed 409 naming the step
     * before. The log goes with it: it is the endpoint's history and holds order payloads, and an endpoint that no
     * longer exists is no reason to keep them. Both deletes carry an explicit tenant predicate on top of RLS.
     */
    public void delete(UUID id) {
        shopAccessService.requireGroupAdmin();
        WebhookSubscription entity = require(id);
        if (entity.getStatus() != WebhookSubscription.Status.REVOKED) {
            throw new WebhookSubscriptionStateException(
                    "Only a revoked endpoint can be deleted, and this one is " + entity.getStatus().name()
                            + ". Revoke it first.",
                    entity.getStatus().name());
        }
        long removed = deliveryRepository.deleteByTenantIdAndSubscriptionId(entity.getTenantId(), id);
        repository.delete(entity);
        log.info("Deleted revoked webhook subscription {} ({} delivery log rows removed)", id, removed);
    }

    private WebhookSubscription require(UUID id) {
        return repository.findByIdAndTenantId(id, currentTenant())
                .orElseThrow(() -> new ResourceNotFoundException("Webhook subscription not found: " + id));
    }

    private void ensureNotRevoked(WebhookSubscription entity) {
        if (entity.getStatus() == WebhookSubscription.Status.REVOKED) {
            throw new IllegalArgumentException("Webhook subscription is revoked and cannot be modified");
        }
    }

    private UUID currentTenant() {
        return TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
    }

    private String generateSecret() {
        byte[] buf = new byte[SECRET_BYTES];
        secureRandom.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private List<String> toNames(List<WebhookEventType> types) {
        List<String> names = new ArrayList<>(types.size());
        for (WebhookEventType t : types) {
            names.add(t.name());
        }
        return names;
    }

    /** Single-subscription read: one count query for the held deliveries. */
    private WebhookSubscriptionDto toDto(WebhookSubscription e) {
        return toDto(e, deliveryRepository.countByTenantIdAndSubscriptionIdAndStatus(
                e.getTenantId(), e.getId(), WebhookDelivery.Status.HELD));
    }

    private WebhookSubscriptionDto toDto(WebhookSubscription e, long heldCount) {
        List<WebhookEventType> types = new ArrayList<>();
        if (e.getEventTypes() != null) {
            for (String name : e.getEventTypes()) {
                types.add(WebhookEventType.valueOf(name));
            }
        }
        return new WebhookSubscriptionDto(
                e.getId(),
                e.getTargetUrl(),
                types,
                e.getStatus().name(),
                e.getConsecutiveFailures(),
                heldCount,
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
