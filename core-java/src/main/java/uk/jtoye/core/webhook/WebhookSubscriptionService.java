package uk.jtoye.core.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.webhook.dto.CreateWebhookSubscriptionRequest;
import uk.jtoye.core.webhook.dto.WebhookSubscriptionDto;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

    public WebhookSubscriptionService(WebhookSubscriptionRepository repository,
                                      WebhookUrlValidator urlValidator,
                                      ShopAccessService shopAccessService) {
        this.repository = repository;
        this.urlValidator = urlValidator;
        this.shopAccessService = shopAccessService;
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
        return repository.findByTenantId(currentTenant()).stream()
                .map(this::toDto)
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
        log.info("Paused webhook subscription {}", id);
        return toDto(repository.save(entity));
    }

    public WebhookSubscriptionDto resume(UUID id) {
        shopAccessService.requireGroupAdmin();
        WebhookSubscription entity = require(id);
        ensureNotRevoked(entity);
        entity.setStatus(WebhookSubscription.Status.ACTIVE);
        entity.setConsecutiveFailures(0); // a manual resume clears the failure trip
        log.info("Resumed webhook subscription {}", id);
        return toDto(repository.save(entity));
    }

    public WebhookSubscriptionDto revoke(UUID id) {
        shopAccessService.requireGroupAdmin();
        WebhookSubscription entity = require(id);
        entity.setStatus(WebhookSubscription.Status.REVOKED); // terminal
        log.info("Revoked webhook subscription {}", id);
        return toDto(repository.save(entity));
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

    private WebhookSubscriptionDto toDto(WebhookSubscription e) {
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
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
