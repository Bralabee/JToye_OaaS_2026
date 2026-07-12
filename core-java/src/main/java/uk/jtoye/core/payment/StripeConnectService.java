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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.onboarding.OnboardingModel;
import uk.jtoye.core.onboarding.VendorOnboardingRepository;
import uk.jtoye.core.payment.dto.ConnectAccountDto;
import uk.jtoye.core.tenant.StripeConnectStatus;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantRepository;
import uk.jtoye.core.tenant.TenantStatus;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Stripe Connect integration (issue #102 [P2-11] AC2, ADR-0001 Decision 2):
 * Express connected-account creation + onboarding links, the
 * {@code account.updated} capability sync, and the destination-charge routing
 * decision consumed by {@link PaymentService#createPaymentIntent}.
 *
 * <p><b>Money flow (ADR-0001 Decision 2):</b> MARKETPLACE → destination
 * charges (platform is merchant of record; funds route to the vendor's
 * connected account, platform keeps an application fee). WHITE_LABEL → direct
 * charges + application fee on the vendor's OWN account — NOT implemented in
 * this slice (J'Toye must never hold white-label customer money, so
 * white-label tenants deliberately get NO routing here and keep today's
 * behaviour until the direct-charge flow ships).
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

    public StripeConnectService(StripeProperties stripeProperties,
                                TenantRepository tenantRepository,
                                VendorOnboardingRepository vendorOnboardingRepository) {
        this.stripeProperties = stripeProperties;
        this.tenantRepository = tenantRepository;
        this.vendorOnboardingRepository = vendorOnboardingRepository;
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
    // Destination-charge routing decision (consumed by PaymentService)
    // ------------------------------------------------------------------

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
     * Platform application fee for a destination charge, in pennies:
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
