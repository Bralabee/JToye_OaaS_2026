package uk.jtoye.core.payment;

import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import uk.jtoye.core.payment.dto.BillingSubscriptionDto;
import uk.jtoye.core.payment.dto.BillingSubscriptionRequest;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.tenant.BillingPlanMode;
import uk.jtoye.core.tenant.SubscriptionStatus;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantRepository;
import uk.jtoye.core.tenant.TenantStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StripeBillingService} covering all four GAP-GTM-05 / PAY-02 / B8 / B9 / D-B9
 * billing tiers, self-serve cancellation, and Stripe billing webhook handlers.
 */
@ExtendWith(MockitoExtension.class)
class StripeBillingServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private StripeConnectService stripeConnectService;

    @Mock
    private ShopRepository shopRepository;

    private StripeProperties stripeProperties;
    private StripeBillingService billingService;
    private UUID tenantId;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        stripeProperties = new StripeProperties();
        stripeProperties.setApiKey("sk_test_phase30_mock");
        stripeProperties.setCurrency("gbp");

        lenient().when(shopRepository.countByTenantId(any())).thenReturn(1L);
        billingService = new StripeBillingService(stripeProperties, tenantRepository, stripeConnectService, shopRepository);

        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setName("Mama Put Kitchen");
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setContactEmail("billing@mamaput.co.uk");
    }

    @Test
    @DisplayName("SAAS_HYBRID_STANDARD creates £39/mo Stripe subscription and sets 0.5% (50 bps) fee tier")
    void subscribeOrUpdatePlan_hybridStandard_creates39PoundsSubscription() throws Exception {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Customer mockCustomer = mock(Customer.class);
        when(mockCustomer.getId()).thenReturn("cus_hybrid_01");
        PaymentMethod mockPm = mock(PaymentMethod.class);
        when(mockPm.getId()).thenReturn("pm_card_visa");
        Price mockPrice = mock(Price.class);
        when(mockPrice.getId()).thenReturn("price_hybrid_3900");
        Subscription mockSub = mock(Subscription.class);
        when(mockSub.getId()).thenReturn("sub_hybrid_01");
        when(mockSub.getStatus()).thenReturn("active");
        when(mockSub.getLatestInvoice()).thenReturn("in_hybrid_01");

        try (MockedStatic<Customer> customerStatic = mockStatic(Customer.class);
             MockedStatic<PaymentMethod> pmStatic = mockStatic(PaymentMethod.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class);
             MockedStatic<Subscription> subStatic = mockStatic(Subscription.class)) {

            customerStatic.when(() -> Customer.create(any(CustomerCreateParams.class))).thenReturn(mockCustomer);
            customerStatic.when(() -> Customer.retrieve("cus_hybrid_01")).thenReturn(mockCustomer);
            when(mockCustomer.update(any(CustomerUpdateParams.class))).thenReturn(mockCustomer);
            pmStatic.when(() -> PaymentMethod.retrieve("pm_card_visa")).thenReturn(mockPm);
            when(mockPm.attach(any(PaymentMethodAttachParams.class))).thenReturn(mockPm);
            priceStatic.when(() -> Price.create(any(PriceCreateParams.class))).thenReturn(mockPrice);
            priceStatic.when(() -> Price.create(any(PriceCreateParams.class), any(RequestOptions.class))).thenReturn(mockPrice);
            subStatic.when(() -> Subscription.create(any(SubscriptionCreateParams.class))).thenReturn(mockSub);
            subStatic.when(() -> Subscription.create(any(SubscriptionCreateParams.class), any(RequestOptions.class))).thenReturn(mockSub);

            BillingSubscriptionRequest req = new BillingSubscriptionRequest(
                    BillingPlanMode.SAAS_HYBRID_STANDARD, "pm_card_visa", null);
            BillingSubscriptionDto dto = billingService.subscribeOrUpdatePlan(tenantId, req);

            assertThat(dto.billingPlanMode()).isEqualTo(BillingPlanMode.SAAS_HYBRID_STANDARD);
            assertThat(dto.subscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(dto.stripeCustomerId()).isEqualTo("cus_hybrid_01");
            assertThat(dto.stripeSubscriptionId()).isEqualTo("sub_hybrid_01");
            assertThat(dto.latestStripeInvoiceId()).isEqualTo("in_hybrid_01");
            assertThat(dto.monthlyAmountPennies()).isEqualTo(3900L);
            assertThat(dto.effectiveFeeBps()).isEqualTo(50);

            ArgumentCaptor<PriceCreateParams> priceCaptor = ArgumentCaptor.forClass(PriceCreateParams.class);
            priceStatic.verify(() -> Price.create(priceCaptor.capture(), any(RequestOptions.class)));
            assertThat(priceCaptor.getValue().getUnitAmount()).isEqualTo(3900L);
            assertThat(priceCaptor.getValue().getCurrency()).isEqualTo("gbp");
        }
    }

    @Test
    @DisplayName("SAAS_FLAT_PREDICTABLE creates £79/mo subscription and sets 0% (0 bps) transaction fee")
    void subscribeOrUpdatePlan_flatPredictable_setsZeroFeeBps() throws Exception {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Customer mockCustomer = mock(Customer.class);
        when(mockCustomer.getId()).thenReturn("cus_flat_01");
        Price mockPrice = mock(Price.class);
        when(mockPrice.getId()).thenReturn("price_flat_7900");
        Subscription mockSub = mock(Subscription.class);
        when(mockSub.getId()).thenReturn("sub_flat_01");
        when(mockSub.getStatus()).thenReturn("active");

        try (MockedStatic<Customer> customerStatic = mockStatic(Customer.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class);
             MockedStatic<Subscription> subStatic = mockStatic(Subscription.class)) {

            customerStatic.when(() -> Customer.create(any(CustomerCreateParams.class))).thenReturn(mockCustomer);
            priceStatic.when(() -> Price.create(any(PriceCreateParams.class))).thenReturn(mockPrice);
            priceStatic.when(() -> Price.create(any(PriceCreateParams.class), any(RequestOptions.class))).thenReturn(mockPrice);
            subStatic.when(() -> Subscription.create(any(SubscriptionCreateParams.class))).thenReturn(mockSub);
            subStatic.when(() -> Subscription.create(any(SubscriptionCreateParams.class), any(RequestOptions.class))).thenReturn(mockSub);

            BillingSubscriptionRequest req = new BillingSubscriptionRequest(
                    BillingPlanMode.SAAS_FLAT_PREDICTABLE, null, null);
            BillingSubscriptionDto dto = billingService.subscribeOrUpdatePlan(tenantId, req);

            assertThat(dto.billingPlanMode()).isEqualTo(BillingPlanMode.SAAS_FLAT_PREDICTABLE);
            assertThat(dto.monthlyAmountPennies()).isEqualTo(7900L);
            assertThat(dto.effectiveFeeBps()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("BATCH_PAYG_STARTER sets £0/mo fixed fee and 6% (600 bps) per-order fee without Stripe subscription")
    void subscribeOrUpdatePlan_batchPaygStarter_setsZeroMonthlyAnd600Bps() throws Exception {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Customer mockCustomer = mock(Customer.class);
        when(mockCustomer.getId()).thenReturn("cus_payg_01");

        try (MockedStatic<Customer> customerStatic = mockStatic(Customer.class)) {
            customerStatic.when(() -> Customer.create(any(CustomerCreateParams.class))).thenReturn(mockCustomer);

            BillingSubscriptionRequest req = new BillingSubscriptionRequest(
                    BillingPlanMode.BATCH_PAYG_STARTER, null, null);
            BillingSubscriptionDto dto = billingService.subscribeOrUpdatePlan(tenantId, req);

            assertThat(dto.billingPlanMode()).isEqualTo(BillingPlanMode.BATCH_PAYG_STARTER);
            assertThat(dto.subscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(dto.monthlyAmountPennies()).isEqualTo(0L);
            assertThat(dto.effectiveFeeBps()).isEqualTo(600);
            assertThat(dto.stripeSubscriptionId()).isNull();
        }
    }

    @Test
    @DisplayName("WALK_SETUP_STEPDOWN bills £195/mo during setup, then steps down on completeWalkSetup to 2.5% capped at £39/mo (D-B9)")
    void walkSetupStepdown_startsAt195PoundsAndStepsDownToCappedOngoingFee() throws Exception {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Customer mockCustomer = mock(Customer.class);
        when(mockCustomer.getId()).thenReturn("cus_walk_01");
        Price mockPrice = mock(Price.class);
        when(mockPrice.getId()).thenReturn("price_walk_19500");
        Subscription mockSub = mock(Subscription.class);
        when(mockSub.getId()).thenReturn("sub_walk_setup_01");
        when(mockSub.getStatus()).thenReturn("active");
        when(mockSub.cancel()).thenReturn(mockSub);

        try (MockedStatic<Customer> customerStatic = mockStatic(Customer.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class);
             MockedStatic<Subscription> subStatic = mockStatic(Subscription.class)) {

            customerStatic.when(() -> Customer.create(any(CustomerCreateParams.class))).thenReturn(mockCustomer);
            priceStatic.when(() -> Price.create(any(PriceCreateParams.class))).thenReturn(mockPrice);
            priceStatic.when(() -> Price.create(any(PriceCreateParams.class), any(RequestOptions.class))).thenReturn(mockPrice);
            subStatic.when(() -> Subscription.create(any(SubscriptionCreateParams.class))).thenReturn(mockSub);
            subStatic.when(() -> Subscription.create(any(SubscriptionCreateParams.class), any(RequestOptions.class))).thenReturn(mockSub);
            subStatic.when(() -> Subscription.retrieve("sub_walk_setup_01")).thenReturn(mockSub);

            BillingSubscriptionRequest req = new BillingSubscriptionRequest(
                    BillingPlanMode.WALK_SETUP_STEPDOWN, null, null);
            BillingSubscriptionDto setupDto = billingService.subscribeOrUpdatePlan(tenantId, req);

            assertThat(setupDto.billingPlanMode()).isEqualTo(BillingPlanMode.WALK_SETUP_STEPDOWN);
            assertThat(setupDto.monthlyAmountPennies()).isEqualTo(19500L);
            assertThat(setupDto.effectiveFeeBps()).isEqualTo(0);
            assertThat(setupDto.walkSetupCompleted()).isFalse();

            BillingSubscriptionDto ongoingDto = billingService.completeWalkSetup(tenantId);

            assertThat(ongoingDto.walkSetupCompleted()).isTrue();
            assertThat(ongoingDto.stripeSubscriptionId()).isNull();
            assertThat(ongoingDto.monthlyAmountPennies()).isEqualTo(0L);
            assertThat(ongoingDto.effectiveFeeBps()).isEqualTo(250);
            assertThat(ongoingDto.monthlyFeeCapPennies()).isEqualTo(3900L);
        }
    }

    @Test
    @DisplayName("Self-serve cancelSubscription (B8) supports both cancel-at-period-end and immediate cancellation")
    void cancelSubscription_supportsPeriodEndAndImmediate() throws Exception {
        tenant.setBillingPlanMode(BillingPlanMode.SAAS_HYBRID_STANDARD);
        tenant.setStripeCustomerId("cus_cancel_01");
        tenant.setStripeSubscriptionId("sub_cancel_01");
        tenant.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Subscription mockSub = mock(Subscription.class);
        when(mockSub.getStatus()).thenReturn("active");
        when(mockSub.update(any(SubscriptionUpdateParams.class))).thenReturn(mockSub);
        when(mockSub.cancel()).thenReturn(mockSub);

        try (MockedStatic<Subscription> subStatic = mockStatic(Subscription.class)) {
            subStatic.when(() -> Subscription.retrieve("sub_cancel_01")).thenReturn(mockSub);

            BillingSubscriptionDto periodEndDto = billingService.cancelSubscription(tenantId, false);
            assertThat(periodEndDto.cancelAtPeriodEnd()).isTrue();
            assertThat(periodEndDto.subscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);

            BillingSubscriptionDto immediateDto = billingService.cancelSubscription(tenantId, true);
            assertThat(immediateDto.subscriptionStatus()).isEqualTo(SubscriptionStatus.CANCELED);
            assertThat(immediateDto.cancelAtPeriodEnd()).isFalse();
        }
    }

    @Test
    @DisplayName("Webhook handlers transition tenant subscription status on invoice.paid, invoice.payment_failed, and customer.subscription.deleted")
    void webhookHandlers_updateTenantSubscriptionStatus() {
        tenant.setStripeCustomerId("cus_webhook_01");
        tenant.setStripeSubscriptionId("sub_webhook_01");
        tenant.setSubscriptionStatus(SubscriptionStatus.NONE);
        tenant.setMonthlyFeeChargedPennies(2500L);
        when(tenantRepository.findByStripeCustomerId("cus_webhook_01")).thenReturn(Optional.of(tenant));
        when(tenantRepository.findByStripeSubscriptionId("sub_webhook_01")).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Invoice invoice = mock(Invoice.class);
        when(invoice.getId()).thenReturn("in_webhook_01");
        when(invoice.getCustomer()).thenReturn("cus_webhook_01");

        Event paidEvent = mock(Event.class);
        EventDataObjectDeserializer paidDeser = mock(EventDataObjectDeserializer.class);
        when(paidEvent.getDataObjectDeserializer()).thenReturn(paidDeser);
        when(paidDeser.getObject()).thenReturn(Optional.of(invoice));

        billingService.handleInvoicePaid(paidEvent);
        assertThat(tenant.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(tenant.getMonthlyFeeChargedPennies()).isZero();
        assertThat(tenant.getLatestStripeInvoiceId()).isEqualTo("in_webhook_01");

        billingService.handleInvoicePaymentFailed(paidEvent);
        assertThat(tenant.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);

        Subscription deletedSub = mock(Subscription.class);
        when(deletedSub.getId()).thenReturn("sub_webhook_01");
        Event delEvent = mock(Event.class);
        EventDataObjectDeserializer delDeser = mock(EventDataObjectDeserializer.class);
        when(delEvent.getDataObjectDeserializer()).thenReturn(delDeser);
        when(delDeser.getObject()).thenReturn(Optional.of(deletedSub));

        billingService.handleSubscriptionDeleted(delEvent);
        assertThat(tenant.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        verify(tenantRepository, times(3)).save(tenant);
    }

    // ------------------------------------------------------------------
    // 30-25 (PAY-02 / D-04): per-location quantity + Stripe idempotency keys
    // ------------------------------------------------------------------

    @Test
    @DisplayName("30-25: per-location modes (SAAS_HYBRID_STANDARD, SAAS_FLAT_PREDICTABLE) set item quantity = tenant shop count and pass idempotency keys")
    void subscribeOrUpdatePlan_perLocationModes_setsLocationQuantityAndIdempotencyKeys() throws Exception {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(shopRepository.countByTenantId(tenantId)).thenReturn(3L);

        for (BillingPlanMode mode : java.util.List.of(
                BillingPlanMode.SAAS_HYBRID_STANDARD, BillingPlanMode.SAAS_FLAT_PREDICTABLE)) {
            tenant.setStripeSubscriptionId(null);
            Customer mockCustomer = mock(Customer.class);
            lenient().when(mockCustomer.getId()).thenReturn("cus_loc_03");
            Price mockPrice = mock(Price.class);
            when(mockPrice.getId()).thenReturn("price_" + mode.name());
            Subscription mockSub = mock(Subscription.class);
            when(mockSub.getId()).thenReturn("sub_" + mode.name());
            when(mockSub.getStatus()).thenReturn("active");

            try (MockedStatic<Customer> customerStatic = mockStatic(Customer.class);
                 MockedStatic<Price> priceStatic = mockStatic(Price.class);
                 MockedStatic<Subscription> subStatic = mockStatic(Subscription.class)) {

                customerStatic.when(() -> Customer.create(any(CustomerCreateParams.class))).thenReturn(mockCustomer);
                priceStatic.when(() -> Price.create(any(PriceCreateParams.class), any(RequestOptions.class)))
                        .thenReturn(mockPrice);
                subStatic.when(() -> Subscription.create(any(SubscriptionCreateParams.class), any(RequestOptions.class)))
                        .thenReturn(mockSub);

                billingService.subscribeOrUpdatePlan(tenantId, new BillingSubscriptionRequest(mode, null, null));

                ArgumentCaptor<RequestOptions> priceOpts = ArgumentCaptor.forClass(RequestOptions.class);
                priceStatic.verify(() -> Price.create(any(PriceCreateParams.class), priceOpts.capture()));
                assertThat(priceOpts.getValue().getIdempotencyKey())
                        .startsWith("billing-price-" + tenantId + "-" + mode.name() + "-")
                        .hasSizeGreaterThan(("billing-price-" + tenantId + "-" + mode.name() + "-").length());

                ArgumentCaptor<SubscriptionCreateParams> subParams = ArgumentCaptor.forClass(SubscriptionCreateParams.class);
                ArgumentCaptor<RequestOptions> subOpts = ArgumentCaptor.forClass(RequestOptions.class);
                subStatic.verify(() -> Subscription.create(subParams.capture(), subOpts.capture()));
                assertThat(subParams.getValue().getItems()).hasSize(1);
                assertThat(subParams.getValue().getItems().get(0).getQuantity()).isEqualTo(3L);
                assertThat(subOpts.getValue().getIdempotencyKey())
                        .isEqualTo("billing-subscription-" + tenantId + "-price_" + mode.name());
            }
        }
    }

    @Test
    @DisplayName("30-25: per-account mode (WALK_SETUP_STEPDOWN) sets item quantity = 1 regardless of shop count; zero shops on per-location mode sets quantity = 0")
    void subscribeOrUpdatePlan_walkSetupUsesQuantity1_andZeroShopsUsesQuantity0() throws Exception {
        when(shopRepository.countByTenantId(tenantId)).thenReturn(5L);

        assertThat(billingService.locationQuantity(tenantId, BillingPlanMode.WALK_SETUP_STEPDOWN)).isEqualTo(1L);
        assertThat(billingService.locationQuantity(tenantId, BillingPlanMode.SAAS_HYBRID_STANDARD)).isEqualTo(5L);

        when(shopRepository.countByTenantId(tenantId)).thenReturn(0L);
        assertThat(billingService.locationQuantity(tenantId, BillingPlanMode.SAAS_HYBRID_STANDARD))
                .as("zero shops bills quantity 0, never fabricated as 1")
                .isZero();
    }

    @Test
    @DisplayName("30-25 (T-30-96): when the location count query fails, subscribeOrUpdatePlan fails before any Price.create or Subscription.create call")
    void subscribeOrUpdatePlan_countQueryFailure_propagatesWithoutCallingStripe() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(shopRepository.countByTenantId(tenantId))
                .thenThrow(new DataAccessResourceFailureException("db connection lost"));

        Customer mockCustomer = mock(Customer.class);
        lenient().when(mockCustomer.getId()).thenReturn("cus_err_01");

        try (MockedStatic<Customer> customerStatic = mockStatic(Customer.class);
             MockedStatic<Price> priceStatic = mockStatic(Price.class);
             MockedStatic<Subscription> subStatic = mockStatic(Subscription.class)) {

            customerStatic.when(() -> Customer.create(any(CustomerCreateParams.class))).thenReturn(mockCustomer);

            assertThatThrownBy(() -> billingService.subscribeOrUpdatePlan(
                    tenantId, new BillingSubscriptionRequest(BillingPlanMode.SAAS_HYBRID_STANDARD, null, null)))
                    .isInstanceOf(DataAccessResourceFailureException.class)
                    .hasMessageContaining("db connection lost");

            priceStatic.verifyNoInteractions();
            subStatic.verifyNoInteractions();
        }
    }
}
