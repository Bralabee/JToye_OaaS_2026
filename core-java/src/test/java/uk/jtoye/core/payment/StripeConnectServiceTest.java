package uk.jtoye.core.payment;

import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.onboarding.OnboardingModel;
import uk.jtoye.core.onboarding.VendorOnboarding;
import uk.jtoye.core.onboarding.VendorOnboardingRepository;
import uk.jtoye.core.payment.dto.ConnectAccountDto;
import uk.jtoye.core.tenant.StripeConnectStatus;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantRepository;
import uk.jtoye.core.tenant.TenantStatus;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StripeConnectService} (issue #102, ADR-0001 Decision 2).
 * The dev stack has EMPTY Stripe keys — there is no live Stripe to hit — so the
 * SDK statics are stubbed with {@code MockedStatic}, mirroring the existing
 * {@code PaymentServiceTest} pattern.
 */
@ExtendWith(MockitoExtension.class)
class StripeConnectServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private VendorOnboardingRepository vendorOnboardingRepository;

    private StripeProperties stripeProperties;
    private StripeConnectService service;

    private UUID tenantId;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        stripeProperties = new StripeProperties();
        stripeProperties.setApiKey("sk_test_123");
        stripeProperties.setPlatformFeeBps(250); // 2.5%
        stripeProperties.getConnect().setCountry("GB");
        stripeProperties.getConnect().setReturnUrl("http://localhost:3000/return");
        stripeProperties.getConnect().setRefreshUrl("http://localhost:3000/refresh");

        service = new StripeConnectService(stripeProperties, tenantRepository, vendorOnboardingRepository);

        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setName("Vendor " + tenantId);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setContactEmail("vendor@example.com");
    }

    // ------------------------------------------------------------------
    // Express account creation + onboarding link
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createOrResumeExpressOnboarding creates an Express account, links it PENDING, returns the onboarding URL")
    void createExpressAccount_newAccount() throws Exception {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Account mockAccount = mock(Account.class);
        when(mockAccount.getId()).thenReturn("acct_new_1");
        AccountLink mockLink = mock(AccountLink.class);
        when(mockLink.getUrl()).thenReturn("https://connect.stripe.com/setup/x");

        try (MockedStatic<Account> accountMock = mockStatic(Account.class);
             MockedStatic<AccountLink> linkMock = mockStatic(AccountLink.class)) {
            accountMock.when(() -> Account.create(any(AccountCreateParams.class))).thenReturn(mockAccount);
            linkMock.when(() -> AccountLink.create(any(AccountLinkCreateParams.class))).thenReturn(mockLink);

            ConnectAccountDto dto = service.createOrResumeExpressOnboarding(tenantId);

            assertEquals("acct_new_1", dto.stripeAccountId());
            assertEquals(StripeConnectStatus.PENDING, dto.connectStatus());
            assertEquals("https://connect.stripe.com/setup/x", dto.onboardingUrl());

            // Tenant row now carries the linkage
            assertEquals("acct_new_1", tenant.getStripeAccountId());
            assertEquals(StripeConnectStatus.PENDING, tenant.getStripeConnectStatus());
            verify(tenantRepository).save(tenant);

            // Account params: Express, GB, contact email, tenant_id metadata (all config/data-driven)
            ArgumentCaptor<AccountCreateParams> accountCaptor = ArgumentCaptor.forClass(AccountCreateParams.class);
            accountMock.verify(() -> Account.create(accountCaptor.capture()));
            assertEquals(AccountCreateParams.Type.EXPRESS, accountCaptor.getValue().getType());
            assertEquals("GB", accountCaptor.getValue().getCountry());
            assertEquals("vendor@example.com", accountCaptor.getValue().getEmail());
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> metadata =
                    (java.util.Map<String, String>) accountCaptor.getValue().getMetadata();
            assertEquals(tenantId.toString(), metadata.get("tenant_id"));

            // Link params: account onboarding with the config-injected URLs
            ArgumentCaptor<AccountLinkCreateParams> linkCaptor = ArgumentCaptor.forClass(AccountLinkCreateParams.class);
            linkMock.verify(() -> AccountLink.create(linkCaptor.capture()));
            assertEquals("acct_new_1", linkCaptor.getValue().getAccount());
            assertEquals(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING, linkCaptor.getValue().getType());
            assertEquals("http://localhost:3000/return", linkCaptor.getValue().getReturnUrl());
            assertEquals("http://localhost:3000/refresh", linkCaptor.getValue().getRefreshUrl());
        }
    }

    @Test
    @DisplayName("createOrResumeExpressOnboarding reuses an already-linked account (idempotent) and only mints a new link")
    void createExpressAccount_existingAccount_reused() throws Exception {
        tenant.setStripeAccountId("acct_existing");
        tenant.setStripeConnectStatus(StripeConnectStatus.PENDING);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        AccountLink mockLink = mock(AccountLink.class);
        when(mockLink.getUrl()).thenReturn("https://connect.stripe.com/setup/y");

        try (MockedStatic<Account> accountMock = mockStatic(Account.class);
             MockedStatic<AccountLink> linkMock = mockStatic(AccountLink.class)) {
            linkMock.when(() -> AccountLink.create(any(AccountLinkCreateParams.class))).thenReturn(mockLink);

            ConnectAccountDto dto = service.createOrResumeExpressOnboarding(tenantId);

            assertEquals("acct_existing", dto.stripeAccountId());
            accountMock.verifyNoInteractions(); // no second Stripe account created
            verify(tenantRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("createOrResumeExpressOnboarding rejects when Stripe is not configured")
    void createExpressAccount_stripeNotConfigured_rejected() {
        stripeProperties.setApiKey("");
        assertThrows(IllegalStateException.class,
                () -> service.createOrResumeExpressOnboarding(tenantId));
        verifyNoInteractions(tenantRepository);
    }

    @Test
    @DisplayName("createOrResumeExpressOnboarding rejects a non-ACTIVE tenant")
    void createExpressAccount_suspendedTenant_rejected() {
        tenant.setStatus(TenantStatus.SUSPENDED);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        assertThrows(IllegalStateException.class,
                () -> service.createOrResumeExpressOnboarding(tenantId));
    }

    @Test
    @DisplayName("createOrResumeExpressOnboarding 404s an unknown tenant")
    void createExpressAccount_unknownTenant_notFound() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.createOrResumeExpressOnboarding(tenantId));
    }

    // ------------------------------------------------------------------
    // account.updated capability sync
    // ------------------------------------------------------------------

    private Event accountUpdatedEvent(Account account) {
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(account));
        Event event = mock(Event.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        return event;
    }

    @Test
    @DisplayName("handleAccountUpdated flips the tenant to ENABLED when charges are enabled")
    void handleAccountUpdated_chargesEnabled_enablesTenant() {
        tenant.setStripeAccountId("acct_1");
        tenant.setStripeConnectStatus(StripeConnectStatus.PENDING);
        when(tenantRepository.findByStripeAccountId("acct_1")).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Account account = mock(Account.class);
        when(account.getId()).thenReturn("acct_1");
        when(account.getChargesEnabled()).thenReturn(true);

        service.handleAccountUpdated(accountUpdatedEvent(account));

        assertEquals(StripeConnectStatus.ENABLED, tenant.getStripeConnectStatus());
        verify(tenantRepository).save(tenant);
    }

    @Test
    @DisplayName("handleAccountUpdated flips the tenant to DISABLED when Stripe disables the account")
    void handleAccountUpdated_disabledReason_disablesTenant() {
        tenant.setStripeAccountId("acct_1");
        tenant.setStripeConnectStatus(StripeConnectStatus.ENABLED);
        when(tenantRepository.findByStripeAccountId("acct_1")).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Account.Requirements requirements = mock(Account.Requirements.class);
        when(requirements.getDisabledReason()).thenReturn("requirements.past_due");
        Account account = mock(Account.class);
        when(account.getId()).thenReturn("acct_1");
        when(account.getChargesEnabled()).thenReturn(false);
        when(account.getRequirements()).thenReturn(requirements);

        service.handleAccountUpdated(accountUpdatedEvent(account));

        assertEquals(StripeConnectStatus.DISABLED, tenant.getStripeConnectStatus());
    }

    @Test
    @DisplayName("handleAccountUpdated is a no-op when the status is unchanged (idempotent re-apply)")
    void handleAccountUpdated_unchangedStatus_noSave() {
        tenant.setStripeAccountId("acct_1");
        tenant.setStripeConnectStatus(StripeConnectStatus.PENDING);
        when(tenantRepository.findByStripeAccountId("acct_1")).thenReturn(Optional.of(tenant));

        Account account = mock(Account.class);
        when(account.getId()).thenReturn("acct_1");
        when(account.getChargesEnabled()).thenReturn(false);
        when(account.getRequirements()).thenReturn(null); // still onboarding → PENDING

        service.handleAccountUpdated(accountUpdatedEvent(account));

        verify(tenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("handleAccountUpdated skips (never throws on) an unknown connected account")
    void handleAccountUpdated_unknownAccount_skipped() {
        when(tenantRepository.findByStripeAccountId("acct_ghost")).thenReturn(Optional.empty());

        Account account = mock(Account.class);
        when(account.getId()).thenReturn("acct_ghost");

        assertDoesNotThrow(() -> service.handleAccountUpdated(accountUpdatedEvent(account)));
        verify(tenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("deriveStatus maps charges_enabled/disabled_reason/otherwise to ENABLED/DISABLED/PENDING")
    void deriveStatus_mapping() {
        Account enabled = mock(Account.class);
        when(enabled.getChargesEnabled()).thenReturn(true);
        assertEquals(StripeConnectStatus.ENABLED, StripeConnectService.deriveStatus(enabled));

        Account.Requirements req = mock(Account.Requirements.class);
        when(req.getDisabledReason()).thenReturn("rejected.fraud");
        Account disabled = mock(Account.class);
        when(disabled.getChargesEnabled()).thenReturn(false);
        when(disabled.getRequirements()).thenReturn(req);
        assertEquals(StripeConnectStatus.DISABLED, StripeConnectService.deriveStatus(disabled));

        Account pending = mock(Account.class);
        when(pending.getChargesEnabled()).thenReturn(null);
        when(pending.getRequirements()).thenReturn(null);
        assertEquals(StripeConnectStatus.PENDING, StripeConnectService.deriveStatus(pending));
    }

    // ------------------------------------------------------------------
    // Destination routing decision
    // ------------------------------------------------------------------

    private VendorOnboarding onboarding(OnboardingModel model) {
        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setModel(model);
        return onboarding;
    }

    @Test
    @DisplayName("resolveDestinationAccount routes MARKETPLACE + ENABLED linked account")
    void resolveDestination_marketplaceEnabled_routes() {
        tenant.setStripeAccountId("acct_1");
        tenant.setStripeConnectStatus(StripeConnectStatus.ENABLED);
        when(vendorOnboardingRepository.findByTenantId(tenantId))
                .thenReturn(Optional.of(onboarding(OnboardingModel.MARKETPLACE)));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertEquals(Optional.of("acct_1"), service.resolveDestinationAccount(tenantId));
    }

    @Test
    @DisplayName("resolveDestinationAccount NEVER routes WHITE_LABEL — even with an ENABLED linked account")
    void resolveDestination_whiteLabel_neverRoutes() {
        tenant.setStripeAccountId("acct_1");
        tenant.setStripeConnectStatus(StripeConnectStatus.ENABLED);
        when(vendorOnboardingRepository.findByTenantId(tenantId))
                .thenReturn(Optional.of(onboarding(OnboardingModel.WHITE_LABEL)));

        assertEquals(Optional.empty(), service.resolveDestinationAccount(tenantId));
        // Short-circuits on model — the tenant registry is not even consulted.
        verify(tenantRepository, never()).findById(any());
    }

    @Test
    @DisplayName("resolveDestinationAccount does not route a MARKETPLACE tenant whose account is not ENABLED")
    void resolveDestination_marketplaceNotEnabled_noRoute() {
        tenant.setStripeAccountId("acct_1");
        tenant.setStripeConnectStatus(StripeConnectStatus.PENDING);
        when(vendorOnboardingRepository.findByTenantId(tenantId))
                .thenReturn(Optional.of(onboarding(OnboardingModel.MARKETPLACE)));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertEquals(Optional.empty(), service.resolveDestinationAccount(tenantId));
    }

    @Test
    @DisplayName("resolveDestinationAccount does not route a tenant with no onboarding record")
    void resolveDestination_noOnboarding_noRoute() {
        when(vendorOnboardingRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        assertEquals(Optional.empty(), service.resolveDestinationAccount(tenantId));
    }

    // ------------------------------------------------------------------
    // Fee math
    // ------------------------------------------------------------------

    @Test
    @DisplayName("applicationFeePennies floors bps math to whole pennies and never goes negative")
    void applicationFee_math() {
        // 250 bps = 2.5%
        assertEquals(37L, service.applicationFeePennies(1500L));   // 37.5 → 37 (floor)
        assertEquals(250L, service.applicationFeePennies(10_000L)); // exact
        assertEquals(0L, service.applicationFeePennies(39L));       // 0.975 → 0
        assertEquals(0L, service.applicationFeePennies(0L));
        assertEquals(0L, service.applicationFeePennies(-100L));

        stripeProperties.setPlatformFeeBps(0);
        assertEquals(0L, service.applicationFeePennies(1500L));
    }
}
