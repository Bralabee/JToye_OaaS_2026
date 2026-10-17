package uk.jtoye.core.payment;

import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Event;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.onboarding.OnboardingModel;
import uk.jtoye.core.onboarding.VendorOnboardingRepository;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.payment.dto.ConnectAccountDto;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.tenant.StripeConnectStatus;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantRepository;
import uk.jtoye.core.tenant.TenantStatus;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Stripe Connect integration (issue #102 [P2-11] AC2, ADR-0001 Decision 2,
 * extended by ADR-0007 / GAP-GTM-01):
 * Express connected-account creation + onboarding links, the
 * {@code account.updated} capability sync, and multi-model settlement routing
 * consumed by {@link PaymentService#createPaymentIntent}.
 *
 * <p><b>Money flow (ADR-0001 Decision 2 &amp; ADR-0007 §1):</b>
 * <ul>
 *   <li>{@link SettlementPolicyMode#DIRECT_CHARGE_VENDOR_MOR} (default for
 *       {@link OnboardingModel#WHITE_LABEL}, Cohort A &amp; B): creates a direct
 *       charge on the vendor's connected Stripe account ({@code Stripe-Account}
 *       header on {@code RequestOptions}) with {@code application_fee_amount};
 *       the Vendor is the legal Merchant of Record.</li>
 *   <li>{@link SettlementPolicyMode#DESTINATION_CHARGE_PLATFORM_MOR} (default for
 *       {@link OnboardingModel#MARKETPLACE}, Cohort D): creates a platform charge
 *       with {@code transfer_data[destination]} and {@code application_fee_amount};
 *       J'Toye Platform is the Merchant of Record.</li>
 *   <li>{@link SettlementPolicyMode#SPLIT_DEPOSIT_VENDOR_MOR} (Cohort C catering):
 *       creates a direct charge on the vendor's connected Stripe account for
 *       {@code deposit_bps} of the order total and records
 *       {@code depositAmountPennies} + {@code balanceDuePennies}.</li>
 * </ul>
 *
 * <p>All Stripe calls go through the SDK statics (house pattern — see
 * {@code PaymentService}); the API key is set once per JVM by
 * {@code PaymentService.init()}. No live Stripe is available in dev (keys are
 * empty), so behaviour is proven by unit tests with {@code MockedStatic}.
 */
@Service
public class StripeConnectService {
    private static final Logger log = LoggerFactory.getLogger(StripeConnectService.class);

    private final StripeProperties stripeProperties;
    private final TenantRepository tenantRepository;
    private final VendorOnboardingRepository vendorOnboardingRepository;
    private final ShopRepository shopRepository;

    public StripeConnectService(StripeProperties stripeProperties,
                                TenantRepository tenantRepository,
                                VendorOnboardingRepository vendorOnboardingRepository) {
        this(stripeProperties, tenantRepository, vendorOnboardingRepository, null);
    }

    @Autowired
    public StripeConnectService(StripeProperties stripeProperties,
                                TenantRepository tenantRepository,
                                VendorOnboardingRepository vendorOnboardingRepository,
                                ShopRepository shopRepository) {
        this.stripeProperties = stripeProperties;
        this.tenantRepository = tenantRepository;
        this.vendorOnboardingRepository = vendorOnboardingRepository;
        this.shopRepository = shopRepository;
    }

    // ------------------------------------------------------------------
    // Express account creation + onboarding link
    // ------------------------------------------------------------------

    /**
     * Create the tenant's Express connected account if none is linked yet
     * (idempotent: an already-linked tenant reuses its account), then mint a
     * fresh single-use onboarding link. Only ACTIVE tenants may connect.
     */
    @CircuitBreaker(name = "stripe")
    @Transactional
    public ConnectAccountDto createOrResumeExpressOnboarding(UUID tenantId) throws StripeException {
        if (stripeProperties.getApiKey() == null || stripeProperties.getApiKey().isBlank()) {
            throw new IllegalStateException("Stripe is not configured — cannot create a connected account");
        }
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Tenant is " + tenant.getStatus() + " — only ACTIVE tenants can connect Stripe");
        }

        String accountId = tenant.getStripeAccountId();
        if (accountId == null || accountId.isBlank()) {
            AccountCreateParams.Builder params = AccountCreateParams.builder()
                    .setType(AccountCreateParams.Type.EXPRESS)
                    .setCountry(stripeProperties.getConnect().getCountry())
                    .putMetadata("tenant_id", tenantId.toString());
            if (tenant.getContactEmail() != null && !tenant.getContactEmail().isBlank()) {
                params.setEmail(tenant.getContactEmail());
            }
            Account account = Account.create(params.build());
            accountId = account.getId();
            tenant.setStripeAccountId(accountId);
            tenant.setStripeConnectStatus(StripeConnectStatus.PENDING);
            tenant.setUpdatedAt(OffsetDateTime.now());
            tenantRepository.save(tenant);
            log.info("event=stripe_connect_account_created tenant={} account={}", tenantId, accountId);
        } else {
            log.info("event=stripe_connect_account_reused tenant={} account={}", tenantId, accountId);
        }

        AccountLink link = AccountLink.create(AccountLinkCreateParams.builder()
                .setAccount(accountId)
                .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                .setReturnUrl(stripeProperties.getConnect().getReturnUrl())
                .setRefreshUrl(stripeProperties.getConnect().getRefreshUrl())
                .build());

        return new ConnectAccountDto(accountId, tenant.getStripeConnectStatus(), link.getUrl());
    }

    // ------------------------------------------------------------------
    // account.updated webhook — capability sync
    // ------------------------------------------------------------------

    /**
     * Sync the tenant's cached {@link StripeConnectStatus} from an
     * {@code account.updated} event. Called from
     * {@link PaymentService#handleWebhookEvent} AFTER signature verification
     * and the {@code processed_stripe_events} idempotency guard (same
     * transaction), so re-deliveries short-circuit before reaching here.
     * Unknown account ids are logged and skipped — never an error (Stripe may
     * replay events for accounts unlinked since).
     */
    @Transactional
    public void handleAccountUpdated(Event event) {
        Account account = (Account) event.getDataObjectDeserializer().getObject()
                .orElseThrow(() -> new IllegalStateException("Failed to deserialize Account"));

        Optional<Tenant> tenantOpt = tenantRepository.findByStripeAccountId(account.getId());
        if (tenantOpt.isEmpty()) {
            log.warn("account.updated for unknown connected account {} — skipping", account.getId());
            return;
        }
        Tenant tenant = tenantOpt.get();
        StripeConnectStatus newStatus = deriveStatus(account);
        if (newStatus == tenant.getStripeConnectStatus()) {
            log.debug("account.updated for tenant {} — status unchanged ({})", tenant.getId(), newStatus);
            return;
        }
        StripeConnectStatus previous = tenant.getStripeConnectStatus();
        tenant.setStripeConnectStatus(newStatus);
        tenant.setUpdatedAt(OffsetDateTime.now());
        tenantRepository.save(tenant);
        log.info("event=stripe_connect_status_changed tenant={} account={} {} -> {}",
                tenant.getId(), account.getId(), previous, newStatus);
    }

    /**
     * Capability mapping: {@code charges_enabled} → ENABLED;
     * {@code requirements.disabled_reason} present → DISABLED; otherwise the
     * account is still onboarding → PENDING.
     */
    static StripeConnectStatus deriveStatus(Account account) {
        if (Boolean.TRUE.equals(account.getChargesEnabled())) {
            return StripeConnectStatus.ENABLED;
        }
        if (account.getRequirements() != null && account.getRequirements().getDisabledReason() != null) {
            return StripeConnectStatus.DISABLED;
        }
        return StripeConnectStatus.PENDING;
    }

    // ------------------------------------------------------------------
    // Settlement routing decisions (consumed by PaymentService)
    // ------------------------------------------------------------------

    /**
     * Resolves the multi-model {@link SettlementRoutingDecision} for an {@link Order}
     * (ADR-0007 / GAP-GTM-01).
     *
     * <p>Looks up the order's {@link Shop} (when {@code shopRepository} and {@code order.getShopId()}
     * are available) to honour shop-level {@link SettlementPolicyMode} and {@code depositBps}
     * overrides before falling back to the tenant's {@link OnboardingModel}.
     */
    @Transactional(readOnly = true)
    public Optional<SettlementRoutingDecision> resolveSettlementRouting(Order order) {
        if (order == null || order.getTenantId() == null) {
            return Optional.empty();
        }
        Shop shop = (shopRepository != null && order.getShopId() != null)
                ? shopRepository.findById(order.getShopId()).orElse(null)
                : null;
        long totalPennies = order.getTotalAmountPennies() != null ? order.getTotalAmountPennies() : 0L;
        return resolveSettlementRouting(order.getTenantId(), shop, totalPennies);
    }

    /**
     * Resolves the multi-model {@link SettlementRoutingDecision} for a tenant, optional shop,
     * and order total in pennies (ADR-0007 / GAP-GTM-01).
     *
     * <ul>
     *   <li>If the shop explicitly sets {@link Shop#getSettlementPolicyMode()}, that mode wins.</li>
     *   <li>Otherwise {@link OnboardingModel#WHITE_LABEL} maps to
     *       {@link SettlementPolicyMode#DIRECT_CHARGE_VENDOR_MOR} (Vendor is Merchant of Record)
     *       and {@link OnboardingModel#MARKETPLACE} maps to
     *       {@link SettlementPolicyMode#DESTINATION_CHARGE_PLATFORM_MOR} (Platform is MoR).</li>
     *   <li>Returns {@link Optional#empty()} when the tenant has no {@code ENABLED} connected
     *       Stripe account or no onboarding model/shop policy is configured.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Optional<SettlementRoutingDecision> resolveSettlementRouting(UUID tenantId,
                                                                        Shop shop,
                                                                        long orderTotalPennies) {
        SettlementPolicyMode mode = (shop != null) ? shop.getSettlementPolicyMode() : null;
        if (mode == null) {
            Optional<OnboardingModel> modelOpt = vendorOnboardingRepository.findByTenantId(tenantId)
                    .map(uk.jtoye.core.onboarding.VendorOnboarding::getModel);
            if (modelOpt.isEmpty()) {
                return Optional.empty();
            }
            mode = (modelOpt.get() == OnboardingModel.MARKETPLACE)
                    ? SettlementPolicyMode.DESTINATION_CHARGE_PLATFORM_MOR
                    : SettlementPolicyMode.DIRECT_CHARGE_VENDOR_MOR;
        }

        Optional<String> accountIdOpt = tenantRepository.findById(tenantId)
                .filter(t -> t.getStripeConnectStatus() == StripeConnectStatus.ENABLED)
                .map(Tenant::getStripeAccountId)
                .filter(accountId -> accountId != null && !accountId.isBlank());
        if (accountIdOpt.isEmpty()) {
            return Optional.empty();
        }
        String accountId = accountIdOpt.get();

        if (mode == SettlementPolicyMode.SPLIT_DEPOSIT_VENDOR_MOR) {
            int depositBps = (shop != null && shop.getDepositBps() != null
                    && shop.getDepositBps() >= 1000 && shop.getDepositBps() <= 10_000)
                    ? shop.getDepositBps()
                    : 10_000;
            long depositAmount = (depositBps >= 10_000 || orderTotalPennies <= 0)
                    ? orderTotalPennies
                    : Math.max(1L, orderTotalPennies * depositBps / 10_000L);
            long balanceDue = Math.max(0L, orderTotalPennies - depositAmount);
            long fee = applicationFeePennies(depositAmount);
            return Optional.of(new SettlementRoutingDecision(
                    mode, accountId, depositAmount, fee, depositAmount, balanceDue));
        }

        long fee = applicationFeePennies(orderTotalPennies);
        return Optional.of(new SettlementRoutingDecision(
                mode, accountId, orderTotalPennies, fee, null, null));
    }

    /**
     * Returns the connected-account id to route a destination charge to, or
     * empty for "keep today's pooled behaviour". Non-empty ONLY when the
     * tenant's onboarding model is MARKETPLACE <em>and</em> the tenant has a
     * linked account whose charges are ENABLED. WHITE_LABEL and unlinked/
     * not-yet-enabled tenants always resolve empty (ADR-0001 Decision 2).
     *
     * <p>The {@code vendor_onboarding} lookup runs under FORCE RLS: callers
     * (guest checkout) hold a TenantContext resolved from the shop slug, so
     * the row is visible. If no tenant GUC is bound the lookup returns empty
     * and the charge safely falls back to pooled behaviour.
     */
    @Transactional(readOnly = true)
    public Optional<String> resolveDestinationAccount(UUID tenantId) {
        boolean marketplace = vendorOnboardingRepository.findByTenantId(tenantId)
                .map(onboarding -> onboarding.getModel() == OnboardingModel.MARKETPLACE)
                .orElse(false);
        if (!marketplace) {
            return Optional.empty();
        }
        return tenantRepository.findById(tenantId)
                .filter(t -> t.getStripeConnectStatus() == StripeConnectStatus.ENABLED)
                .map(Tenant::getStripeAccountId)
                .filter(accountId -> accountId != null && !accountId.isBlank());
    }

    /**
     * Platform application fee for a connected charge, in pennies:
     * {@code amount * platformFeeBps / 10_000}, floored (long integer math —
     * never fractional pennies). Returns 0 when no fee is configured.
     */
    public long applicationFeePennies(long amountPennies) {
        int bps = stripeProperties.getPlatformFeeBps();
        if (bps <= 0 || amountPennies <= 0) {
            return 0L;
        }
        return amountPennies * bps / 10_000L;
    }
}
