package uk.jtoye.core.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.MissingTenantContextException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.webhook.dto.WebhookDeliveryView;
import uk.jtoye.core.webhook.dto.WebhookSubscriptionDto;

import java.util.UUID;

/**
 * Tenant-scoped read + replay for {@link WebhookDelivery} (COMMS-05/COMMS-06).
 *
 * <p><b>Why this class exists (issue #444).</b> {@code WebhookDeliveryController}
 * used to inject {@link WebhookDeliveryRepository} directly and call it with no
 * transaction. {@code TenantSetLocalAspect} sets {@code app.current_tenant_id}
 * with a transaction-LOCAL {@code set_config}, and both of its pointcuts return
 * early unless {@code TransactionSynchronizationManager.isActualTransactionActive()};
 * a bare repository call from a non-transactional caller therefore reached
 * Postgres with no tenant GUC. Under the V56 {@code webhook_delivery_tenant}
 * FORCE-RLS policy ({@code tenant_id = current_tenant_id()}) an unset GUC yields
 * NULL, so the policy matched nothing and the vendor's delivery log was
 * permanently empty — not an error, just zero rows, which is why it survived
 * every gate. Every entry point here is {@code @Transactional}, so the aspect's
 * just-in-time repository pointcut fires and the GUC is pinned before the query.
 *
 * <p><b>The unset-tenant case is loud, not empty.</b> {@link #requireTenant()}
 * throws {@link MissingTenantContextException} (RFC 7807 500, ERROR-logged)
 * rather than letting an unpinned read return an empty page. An empty page is
 * indistinguishable from a genuine empty log, and that ambiguity is exactly what
 * hid this defect: a vendor debugging a failing integration was shown "nothing
 * was ever sent" while six attempts had been made and the subscription had been
 * auto-paused.
 *
 * <p><b>RLS stays the row filter.</b> The queries are deliberately NOT given an
 * extra {@code tenant_id} predicate. Subscription ownership is checked first
 * (which is the tenant boundary for the addressable resource), and the rows
 * themselves are bounded by the FORCE-RLS policy — so
 * {@code WebhookDeliveryLogIntegrationTest} proving that another tenant's row
 * filed under this subscription id is excluded is a genuine test of the database
 * boundary rather than a restatement of a WHERE clause.
 *
 * <p><strong>GROUP_ADMIN only (QA-council 20260902 SEC-1).</strong> {@code webhook_delivery.payload}
 * is the serialized event envelope whose {@code data} is the full {@code OrderDto} — customer
 * name, email and phone — and V56 carries no {@code shop_id}, so the log is a TENANT-WIDE
 * order-event store. Both entry points therefore call {@link ShopAccessService#requireGroupAdmin()}
 * (the {@code StaffManagementService} precedent) immediately after {@link #requireTenant()} and
 * BEFORE the subscription-ownership lookup: a scoped caller gets the typed shop-access 403 and
 * never learns whether the subscription exists. The tenant check stays FIRST so the documented
 * "no tenant established → loud {@code missing-tenant-context} 500" contract above is unchanged.
 */
@Service
@Transactional
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSubscriptionService subscriptionService;
    private final ShopAccessService shopAccessService;

    public WebhookDeliveryService(WebhookDeliveryRepository deliveryRepository,
                                  WebhookSubscriptionService subscriptionService,
                                  ShopAccessService shopAccessService) {
        this.deliveryRepository = deliveryRepository;
        this.subscriptionService = subscriptionService;
        this.shopAccessService = shopAccessService;
    }

    /**
     * Paged delivery log for one owned subscription, newest first, with optional
     * status + event-type filters (both {@code null} = unfiltered).
     *
     * @throws uk.jtoye.core.exception.ShopAccessDeniedException the caller is not a GROUP_ADMIN (403)
     * @throws ResourceNotFoundException     the subscription does not exist for this tenant (404)
     * @throws MissingTenantContextException no tenant is established for the request (500)
     */
    @Transactional(readOnly = true)
    public Page<WebhookDeliveryView> list(UUID subscriptionId,
                                          WebhookDelivery.Status status,
                                          String eventType,
                                          Pageable pageable) {
        requireTenant();
        shopAccessService.requireGroupAdmin();
        requireOwnedSubscription(subscriptionId);
        return deliveryRepository.findLog(subscriptionId, status, eventType, pageable)
                .map(WebhookDeliveryView::from);
    }

    /**
     * Insert a replay of {@code deliveryId} and return its view.
     *
     * <p>A NEW row, PENDING, tagged is_replay/replay_of. It REUSES the original
     * envelope id (X-JToye-Event-Id), so the vendor's receiver dedupes it against
     * the original event — a replay (and its retries) can never double-deliver.
     * The original row is untouched.
     *
     * <p>Called either directly (no {@code Idempotency-Key}: one call, one row) or
     * as the {@code work} supplier of {@code IdempotencyService.execute}, which is
     * itself {@code @Transactional} — this method then joins that transaction, so
     * a 404 here rolls the key reservation back and a genuine retry still works.
     */
    public WebhookDeliveryView replay(UUID subscriptionId, UUID deliveryId) {
        requireTenant();
        shopAccessService.requireGroupAdmin();
        requireOwnedSubscription(subscriptionId);

        WebhookDelivery original = deliveryRepository.findByIdAndSubscriptionId(deliveryId, subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Webhook delivery not found: " + deliveryId));

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
        log.info("event=webhook_delivery_replayed subscription={} original={} replay={}",
                subscriptionId, original.getId(), saved.getId());
        return WebhookDeliveryView.from(saved);
    }

    /** 404 (RFC 7807) unless the subscription exists and belongs to the caller's tenant. */
    private void requireOwnedSubscription(UUID subscriptionId) {
        WebhookSubscriptionDto owned = subscriptionService.getById(subscriptionId);
        if (owned == null) {
            throw new ResourceNotFoundException("Webhook subscription not found: " + subscriptionId);
        }
    }

    /**
     * Fail loudly when no tenant is established. Without this, an unpinned read
     * returns an empty page that is indistinguishable from a genuine empty log —
     * the documented "RLS returns zero rows" trap this issue was an instance of.
     */
    private UUID requireTenant() {
        return TenantContext.get().orElseThrow(() -> new MissingTenantContextException(
                "Tenant context not set for a webhook delivery operation"));
    }
}
