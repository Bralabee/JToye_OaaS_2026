package uk.jtoye.core.payment;

import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.payment.dto.BillingSubscriptionDto;
import uk.jtoye.core.payment.dto.BillingSubscriptionRequest;
import uk.jtoye.core.tenant.BillingPlanMode;
import uk.jtoye.core.tenant.SubscriptionStatus;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantRepository;
import uk.jtoye.core.tenant.TenantStatus;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Plan-configurable recurring subscription billing & per-plan fee metering
 * (ADR-0007 §1.4 / {@code GAP-GTM-05}, {@code PAY-02}, and
 * {@code BUSINESS-ALIGNMENT-2026-10.md} {@code B8}, {@code B9}, {@code D-B9}).
 *
 * <p>Supported tiers (all monetary amounts and bps resolved from {@link StripeProperties.Billing}):
 * <ul>
 *   <li>{@link BillingPlanMode#SAAS_HYBRID_STANDARD}: £39/location/mo + 50 bps (0.5%)</li>
 *   <li>{@link BillingPlanMode#SAAS_FLAT_PREDICTABLE}: £79–£149/location/mo + 0 bps (0%)</li>
 *   <li>{@link BillingPlanMode#BATCH_PAYG_STARTER}: £0/mo + 600 bps (6.0%)</li>
 *   <li>{@link BillingPlanMode#WALK_SETUP_STEPDOWN}: £195/mo during setup, stepping down
 *       on {@link #completeWalkSetup(UUID)} to 250 bps (2.5%) of direct orders capped at
 *       £39/mo per account (£0 in a quiet month).</li>
 * </ul>
 */
@Service
public class StripeBillingService {

    private static final Logger log = LoggerFactory.getLogger(StripeBillingService.class);

    private final StripeProperties stripeProperties;
    private final TenantRepository tenantRepository;
    private final StripeConnectService stripeConnectService;

    public StripeBillingService(StripeProperties stripeProperties,
                                TenantRepository tenantRepository,
                                StripeConnectService stripeConnectService) {
        this.stripeProperties = stripeProperties;
        this.tenantRepository = tenantRepository;
        this.stripeConnectService = stripeConnectService;
    }

    @Transactional(readOnly = true)
    public BillingSubscriptionDto getSubscription(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
        return toDto(tenant);
    }

    /**
     * Subscribe a tenant to a {@link BillingPlanMode} or switch their existing plan.
     */
    @CircuitBreaker(name = "stripe")
    @Transactional
    public BillingSubscriptionDto subscribeOrUpdatePlan(UUID tenantId, BillingSubscriptionRequest request)
            throws StripeException {
        if (request == null || request.planMode() == null) {
            throw new IllegalArgumentException("planMode is required");
        }
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Tenant is " + tenant.getStatus() + " — only ACTIVE tenants can subscribe");
        }

        BillingPlanMode mode = request.planMode();
        tenant.setBillingPlanMode(mode);
        if (mode == BillingPlanMode.WALK_SETUP_STEPDOWN) {
            tenant.setWalkSetupCompletedAt(null);
            tenant.setMonthlyFeeChargedPennies(0L);
        }

        boolean walkSetupDone = tenant.getWalkSetupCompletedAt() != null;
        long monthlyPennies = stripeProperties.getBilling().monthlyAmountPenniesFor(mode, walkSetupDone);

        if (stripeProperties.getApiKey() != null && !stripeProperties.getApiKey().isBlank()) {
            String customerId = ensureStripeCustomer(tenant, request.paymentMethodId(), request.testClockId());

            // If switching away from an existing recurring subscription, cancel the prior one first
            if (tenant.getStripeSubscriptionId() != null && !tenant.getStripeSubscriptionId().isBlank()) {
                Subscription existing = Subscription.retrieve(tenant.getStripeSubscriptionId());
                if (!"canceled".equalsIgnoreCase(existing.getStatus())) {
                    existing.cancel();
                }
            }

            if (monthlyPennies > 0) {
                Price price = Price.create(PriceCreateParams.builder()
                        .setCurrency(stripeProperties.getCurrency())
                        .setUnitAmount(monthlyPennies)
                        .setRecurring(PriceCreateParams.Recurring.builder()
                                .setInterval(PriceCreateParams.Recurring.Interval.MONTH)
                                .build())
                        .setProductData(PriceCreateParams.ProductData.builder()
                                .setName("J'Toye OaaS — " + mode.name())
                                .build())
                        .build());

                SubscriptionCreateParams.Builder subParams = SubscriptionCreateParams.builder()
                        .setCustomer(customerId)
                        .addItem(SubscriptionCreateParams.Item.builder()
                                .setPrice(price.getId())
                                .build())
                        .putMetadata("tenant_id", tenantId.toString())
                        .putMetadata("billing_plan_mode", mode.name());
                if (request.paymentMethodId() != null && !request.paymentMethodId().isBlank()) {
                    subParams.setDefaultPaymentMethod(request.paymentMethodId());
                }

                Subscription subscription = Subscription.create(subParams.build());
                tenant.setStripeSubscriptionId(subscription.getId());
                tenant.setSubscriptionStatus(mapStatus(subscription.getStatus()));
                tenant.setSubscriptionCancelAtPeriodEnd(
                        Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()));
                tenant.setLatestStripeInvoiceId(subscription.getLatestInvoice());
            } else {
                // £0/mo recurring tier (BATCH_PAYG_STARTER or stepped-down WALK_SETUP_STEPDOWN)
                tenant.setStripeSubscriptionId(null);
                tenant.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
                tenant.setSubscriptionCancelAtPeriodEnd(false);
            }
        } else {
            tenant.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
            tenant.setSubscriptionCancelAtPeriodEnd(false);
        }

        tenant.setUpdatedAt(OffsetDateTime.now());
        tenant = tenantRepository.save(tenant);
        log.info("event=tenant_billing_plan_updated tenant={} plan={} status={} invoice={}",
                tenantId, mode, tenant.getSubscriptionStatus(), tenant.getLatestStripeInvoiceId());
        return toDto(tenant);
    }

    /**
     * Transition a {@link BillingPlanMode#WALK_SETUP_STEPDOWN} tenant from the £195/mo
     * setup phase to the ongoing 2.5%-of-direct-orders phase capped at £39/mo
     * ({@code B9} / {@code D-B9}: "on 'book complete' the plan switches").
     */
    @CircuitBreaker(name = "stripe")
    @Transactional
    public BillingSubscriptionDto completeWalkSetup(UUID tenantId) throws StripeException {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
        if (tenant.getBillingPlanMode() != BillingPlanMode.WALK_SETUP_STEPDOWN) {
            throw new IllegalStateException(
                    "Tenant " + tenantId + " is not on WALK_SETUP_STEPDOWN (current=" + tenant.getBillingPlanMode() + ")");
        }

        if (stripeProperties.getApiKey() != null && !stripeProperties.getApiKey().isBlank()
                && tenant.getStripeSubscriptionId() != null && !tenant.getStripeSubscriptionId().isBlank()) {
            Subscription existing = Subscription.retrieve(tenant.getStripeSubscriptionId());
            if (!"canceled".equalsIgnoreCase(existing.getStatus())) {
                existing.cancel();
            }
        }

        tenant.setWalkSetupCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        tenant.setStripeSubscriptionId(null);
        tenant.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        tenant.setSubscriptionCancelAtPeriodEnd(false);
        tenant.setMonthlyFeeChargedPennies(0L);
        tenant.setUpdatedAt(OffsetDateTime.now());
        tenant = tenantRepository.save(tenant);
        log.info("event=walk_setup_completed tenant={} feeBps={} capPennies={}",
                tenantId,
                stripeProperties.getBilling().getWalkOngoingFeeBps(),
                stripeProperties.getBilling().getWalkMonthlyCapPennies());
        return toDto(tenant);
    }

    /**
     * Record platform application fee pennies charged against a tenant's monthly cap
     * ({@code B9} / {@code D-B9}: 2.5% of direct orders capped at £39/mo across the account,
     * £0 in a quiet month). Returns the fee pennies charged for this order.
     */
    @Transactional
    public long recordDirectOrderFee(UUID tenantId, long directOrderAmountPennies) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
        long fee = stripeConnectService.applicationFeePennies(tenant, directOrderAmountPennies);
        if (fee > 0) {
            tenant.setMonthlyFeeChargedPennies(tenant.getMonthlyFeeChargedPennies() + fee);
            tenant.setUpdatedAt(OffsetDateTime.now());
            tenantRepository.save(tenant);
        }
        return fee;
    }

    /**
     * Self-serve subscription cancellation ({@code B8}: written exit terms built in,
     * no exit fee, no charge in the following billing cycle).
     */
    @CircuitBreaker(name = "stripe")
    @Transactional
    public BillingSubscriptionDto cancelSubscription(UUID tenantId, boolean immediate) throws StripeException {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (stripeProperties.getApiKey() != null && !stripeProperties.getApiKey().isBlank()
                && tenant.getStripeSubscriptionId() != null && !tenant.getStripeSubscriptionId().isBlank()) {
            Subscription sub = Subscription.retrieve(tenant.getStripeSubscriptionId());
            if (immediate) {
                sub = sub.cancel();
                tenant.setSubscriptionStatus(SubscriptionStatus.CANCELED);
                tenant.setSubscriptionCancelAtPeriodEnd(false);
            } else {
                sub = sub.update(SubscriptionUpdateParams.builder()
                        .setCancelAtPeriodEnd(true)
                        .build());
                tenant.setSubscriptionStatus(mapStatus(sub.getStatus()));
                tenant.setSubscriptionCancelAtPeriodEnd(true);
            }
        } else {
            tenant.setSubscriptionStatus(SubscriptionStatus.CANCELED);
            tenant.setSubscriptionCancelAtPeriodEnd(false);
        }

        tenant.setUpdatedAt(OffsetDateTime.now());
        tenant = tenantRepository.save(tenant);
        log.info("event=tenant_subscription_canceled tenant={} immediate={} status={}",
                tenantId, immediate, tenant.getSubscriptionStatus());
        return toDto(tenant);
    }

    // ------------------------------------------------------------------
    // Webhook handlers (invoked from PaymentService.handleWebhookEvent)
    // ------------------------------------------------------------------

    @Transactional
    public void handleInvoicePaid(Event event) {
        Optional<Invoice> invoiceOpt = event.getDataObjectDeserializer().getObject()
                .filter(Invoice.class::isInstance)
                .map(Invoice.class::cast);
        if (invoiceOpt.isEmpty()) {
            log.warn("invoice.paid event {} could not deserialize Invoice", event.getId());
            return;
        }
        Invoice invoice = invoiceOpt.get();
        findTenantForInvoice(invoice).ifPresent(tenant -> {
            tenant.setLatestStripeInvoiceId(invoice.getId());
            tenant.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
            tenant.setMonthlyFeeChargedPennies(0L);
            if (invoice.getPeriodEnd() != null) {
                tenant.setSubscriptionCurrentPeriodEnd(
                        OffsetDateTime.ofInstant(Instant.ofEpochSecond(invoice.getPeriodEnd()), ZoneOffset.UTC));
            }
            tenant.setUpdatedAt(OffsetDateTime.now());
            tenantRepository.save(tenant);
            log.info("event=stripe_invoice_paid tenant={} invoice={}", tenant.getId(), invoice.getId());
        });
    }

    @Transactional
    public void handleInvoicePaymentFailed(Event event) {
        Optional<Invoice> invoiceOpt = event.getDataObjectDeserializer().getObject()
                .filter(Invoice.class::isInstance)
                .map(Invoice.class::cast);
        if (invoiceOpt.isEmpty()) {
            log.warn("invoice.payment_failed event {} could not deserialize Invoice", event.getId());
            return;
        }
        Invoice invoice = invoiceOpt.get();
        findTenantForInvoice(invoice).ifPresent(tenant -> {
            tenant.setLatestStripeInvoiceId(invoice.getId());
            tenant.setSubscriptionStatus(SubscriptionStatus.PAST_DUE);
            tenant.setUpdatedAt(OffsetDateTime.now());
            tenantRepository.save(tenant);
            log.warn("event=stripe_invoice_payment_failed tenant={} invoice={}", tenant.getId(), invoice.getId());
        });
    }

    @Transactional
    public void handleSubscriptionDeleted(Event event) {
        Optional<Subscription> subOpt = event.getDataObjectDeserializer().getObject()
                .filter(Subscription.class::isInstance)
                .map(Subscription.class::cast);
        if (subOpt.isEmpty()) {
            log.warn("customer.subscription.deleted event {} could not deserialize Subscription", event.getId());
            return;
        }
        Subscription sub = subOpt.get();
        Optional<Tenant> tenantOpt = tenantRepository.findByStripeSubscriptionId(sub.getId())
                .or(() -> sub.getCustomer() != null
                        ? tenantRepository.findByStripeCustomerId(sub.getCustomer())
                        : Optional.empty());
        tenantOpt.ifPresent(tenant -> {
            tenant.setSubscriptionStatus(SubscriptionStatus.CANCELED);
            tenant.setSubscriptionCancelAtPeriodEnd(false);
            tenant.setUpdatedAt(OffsetDateTime.now());
            tenantRepository.save(tenant);
            log.info("event=stripe_subscription_deleted tenant={} subscription={}", tenant.getId(), sub.getId());
        });
    }

    private Optional<Tenant> findTenantForInvoice(Invoice invoice) {
        if (invoice.getCustomer() != null) {
            Optional<Tenant> byCust = tenantRepository.findByStripeCustomerId(invoice.getCustomer());
            if (byCust.isPresent()) {
                return byCust;
            }
        }
        return Optional.empty();
    }

    private String ensureStripeCustomer(Tenant tenant, String paymentMethodId, String testClockId)
            throws StripeException {
        String customerId = tenant.getStripeCustomerId();
        if (customerId == null || customerId.isBlank()) {
            CustomerCreateParams.Builder builder = CustomerCreateParams.builder()
                    .setName(tenant.getName())
                    .putMetadata("tenant_id", tenant.getId().toString());
            if (tenant.getContactEmail() != null && !tenant.getContactEmail().isBlank()) {
                builder.setEmail(tenant.getContactEmail());
            }
            if (testClockId != null && !testClockId.isBlank()) {
                builder.setTestClock(testClockId);
            }
            Customer created = Customer.create(builder.build());
            customerId = created.getId();
            tenant.setStripeCustomerId(customerId);
        }
        if (paymentMethodId != null && !paymentMethodId.isBlank()) {
            PaymentMethod pm = PaymentMethod.retrieve(paymentMethodId);
            pm.attach(PaymentMethodAttachParams.builder()
                    .setCustomer(customerId)
                    .build());
            Customer customer = Customer.retrieve(customerId);
            customer.update(CustomerUpdateParams.builder()
                    .setInvoiceSettings(CustomerUpdateParams.InvoiceSettings.builder()
                            .setDefaultPaymentMethod(pm.getId())
                            .build())
                    .build());
        }
        return customerId;
    }

    static SubscriptionStatus mapStatus(String stripeStatus) {
        if (stripeStatus == null) {
            return SubscriptionStatus.NONE;
        }
        return switch (stripeStatus.toLowerCase()) {
            case "active" -> SubscriptionStatus.ACTIVE;
            case "trialing" -> SubscriptionStatus.TRIALING;
            case "past_due", "unpaid", "incomplete" -> SubscriptionStatus.PAST_DUE;
            case "canceled", "incomplete_expired" -> SubscriptionStatus.CANCELED;
            default -> SubscriptionStatus.NONE;
        };
    }

    private BillingSubscriptionDto toDto(Tenant tenant) {
        BillingPlanMode mode = tenant.getBillingPlanMode();
        boolean walkSetupDone = tenant.getWalkSetupCompletedAt() != null;
        long monthlyPennies = mode != null
                ? stripeProperties.getBilling().monthlyAmountPenniesFor(mode, walkSetupDone)
                : 0L;
        int feeBps = mode != null
                ? stripeProperties.getBilling().feeBpsFor(mode, walkSetupDone)
                : stripeProperties.getPlatformFeeBps();
        long capPennies = (mode == BillingPlanMode.WALK_SETUP_STEPDOWN && walkSetupDone)
                ? stripeProperties.getBilling().getWalkMonthlyCapPennies()
                : 0L;
        return new BillingSubscriptionDto(
                tenant.getId(),
                mode,
                tenant.getSubscriptionStatus(),
                tenant.getStripeCustomerId(),
                tenant.getStripeSubscriptionId(),
                tenant.getLatestStripeInvoiceId(),
                monthlyPennies,
                feeBps,
                capPennies,
                tenant.getMonthlyFeeChargedPennies(),
                walkSetupDone,
                tenant.isSubscriptionCancelAtPeriodEnd(),
                tenant.getSubscriptionCurrentPeriodEnd()
        );
    }
}
