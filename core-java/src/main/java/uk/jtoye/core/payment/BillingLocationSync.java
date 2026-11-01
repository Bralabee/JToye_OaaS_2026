package uk.jtoye.core.payment;

import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionItemUpdateParams;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.tenant.BillingPlanMode;
import uk.jtoye.core.tenant.SubscriptionStatus;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantRepository;
import uk.jtoye.core.tenant.TenantStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps a subscribed tenant's Stripe {@link SubscriptionItem} quantity aligned with
 * the tenant's committed location count under the owner's D-04 {@code all-shops} rule
 * (Phase 30 Plan 30-25, {@code PAY-02}, {@code evidence/30-25-d04-decision.txt}).
 *
 * <p>Security &amp; consistency invariants:
 * <ul>
 *   <li><b>T-30-98 (TenantContext in afterCommit):</b> {@link #syncAfterCommit(UUID, String)}
 *       captures {@code tenantId} at registration time and pins {@link TenantContext} inside a
 *       {@code PROPAGATION_REQUIRES_NEW} read transaction during {@code afterCommit()} before
 *       calling {@link StripeBillingService#locationQuantity(UUID, BillingPlanMode)}.</li>
 *   <li><b>T-30-99 (Idempotency on quantity update):</b> {@link SubscriptionItem#update} passes
 *       {@code RequestOptions} with idempotency key
 *       {@code billing-qty-<subscriptionItemId>-<quantity>-<eventToken>}.</li>
 *   <li><b>T-30-100 (Non-blocking shop write):</b> Sync runs strictly in
 *       {@link TransactionSynchronization#afterCommit()}; any Stripe or runtime exception is
 *       caught, logged at {@code ERROR} ({@code event=billing_location_sync_failed}), and
 *       counted on {@code jtoye.billing.location_sync.failed} so a Stripe outage never rolls
 *       back a committed shop creation or deletion.</li>
 * </ul>
 */
@Component
public class BillingLocationSync {

    private static final Logger log = LoggerFactory.getLogger(BillingLocationSync.class);

    public static final String FAILED_METRIC = "jtoye.billing.location_sync.failed";

    private final StripeProperties stripeProperties;
    private final TenantRepository tenantRepository;
    private final StripeBillingService stripeBillingService;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewReadTx;

    public BillingLocationSync(StripeProperties stripeProperties,
                               TenantRepository tenantRepository,
                               StripeBillingService stripeBillingService,
                               @Nullable MeterRegistry meterRegistry) {
        this(stripeProperties, tenantRepository, stripeBillingService, meterRegistry, null);
    }

    @Autowired
    public BillingLocationSync(StripeProperties stripeProperties,
                               TenantRepository tenantRepository,
                               StripeBillingService stripeBillingService,
                               @Nullable MeterRegistry meterRegistry,
                               @Nullable PlatformTransactionManager transactionManager) {
        this.stripeProperties = stripeProperties;
        this.tenantRepository = tenantRepository;
        this.stripeBillingService = stripeBillingService;
        this.meterRegistry = meterRegistry;
        if (transactionManager != null) {
            TransactionTemplate tpl = new TransactionTemplate(transactionManager);
            tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tpl.setReadOnly(true);
            this.requiresNewReadTx = tpl;
        } else {
            this.requiresNewReadTx = null;
        }
    }

    /**
     * Schedule a per-location Stripe subscription quantity sync to run after the current
     * transaction commits (or immediately if no transaction synchronization is active).
     */
    public void syncAfterCommit(UUID tenantId) {
        syncAfterCommit(tenantId, null);
    }

    /**
     * Schedule a per-location Stripe subscription quantity sync with a deterministic
     * {@code eventToken} (e.g. {@code shop-create-<shopId>}) for the Stripe idempotency key.
     */
    public void syncAfterCommit(UUID tenantId, @Nullable String eventToken) {
        if (tenantId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sync(tenantId, eventToken);
                }
            });
        } else {
            sync(tenantId, eventToken);
        }
    }

    public void sync(UUID tenantId) {
        sync(tenantId, null);
    }

    /**
     * Reconcile the tenant's Stripe {@link SubscriptionItem} quantity with the tenant's
     * committed location count. Never throws: any error is logged and counted on
     * {@link #FAILED_METRIC} (T-30-100).
     */
    public void sync(UUID tenantId, @Nullable String eventToken) {
        if (tenantId == null
                || stripeProperties.getApiKey() == null
                || stripeProperties.getApiKey().isBlank()) {
            return;
        }
        Optional<UUID> priorTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            SyncTarget target = requiresNewReadTx != null
                    ? requiresNewReadTx.execute(status -> resolveSyncTarget(tenantId))
                    : resolveSyncTarget(tenantId);
            if (target == null) {
                return;
            }

            Subscription subscription = Subscription.retrieve(target.subscriptionId());
            if (subscription == null || subscription.getItems() == null) {
                return;
            }
            List<SubscriptionItem> items = subscription.getItems().getData();
            if (items == null || items.isEmpty()) {
                return;
            }

            SubscriptionItem item = items.get(0);
            Long currentQuantity = item.getQuantity();
            if (currentQuantity != null && currentQuantity == target.targetQuantity()) {
                return;
            }

            String token = (eventToken != null && !eventToken.isBlank())
                    ? eventToken
                    : UUID.randomUUID().toString();
            RequestOptions opts = RequestOptions.builder()
                    .setIdempotencyKey("billing-qty-" + item.getId() + "-" + target.targetQuantity() + "-" + token)
                    .build();
            SubscriptionItemUpdateParams params = SubscriptionItemUpdateParams.builder()
                    .setQuantity(target.targetQuantity())
                    .build();

            item.update(params, opts);
            log.info("event=billing_location_synced tenant={} subscription={} item={} oldQuantity={} newQuantity={}",
                    tenantId, target.subscriptionId(), item.getId(), currentQuantity, target.targetQuantity());
        } catch (Exception e) {
            if (meterRegistry != null) {
                meterRegistry.counter(FAILED_METRIC).increment();
            }
            log.error("event=billing_location_sync_failed tenant={} reason={}", tenantId, e.getMessage(), e);
        } finally {
            if (priorTenant.isPresent()) {
                TenantContext.set(priorTenant.get());
            } else {
                TenantContext.clear();
            }
        }
    }

    @Nullable
    private SyncTarget resolveSyncTarget(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null || tenant.getStatus() != TenantStatus.ACTIVE) {
            return null;
        }
        BillingPlanMode mode = tenant.getBillingPlanMode();
        if (!StripeBillingService.isPerLocationMode(mode)) {
            return null;
        }
        SubscriptionStatus subStatus = tenant.getSubscriptionStatus();
        if (subStatus != SubscriptionStatus.ACTIVE
                && subStatus != SubscriptionStatus.TRIALING
                && subStatus != SubscriptionStatus.PAST_DUE) {
            return null;
        }
        String subId = tenant.getStripeSubscriptionId();
        if (subId == null || subId.isBlank()) {
            return null;
        }
        long targetQuantity = stripeBillingService.locationQuantity(tenantId, mode);
        return new SyncTarget(subId, mode, targetQuantity);
    }

    private record SyncTarget(String subscriptionId, BillingPlanMode mode, long targetQuantity) {}
}
