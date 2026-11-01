package uk.jtoye.core.payment;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionItemUpdateParams;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.tenant.BillingPlanMode;
import uk.jtoye.core.tenant.SubscriptionStatus;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantRepository;
import uk.jtoye.core.tenant.TenantStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 30 Plan 30-25 Task 3: unit tests for {@link BillingLocationSync}.
 */
@ExtendWith(MockitoExtension.class)
class BillingLocationSyncTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private StripeBillingService stripeBillingService;

    private StripeProperties stripeProperties;
    private SimpleMeterRegistry meterRegistry;
    private BillingLocationSync sync;
    private UUID tenantId;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        stripeProperties = new StripeProperties();
        stripeProperties.setApiKey("sk_test_sync_01");
        meterRegistry = new SimpleMeterRegistry();
        sync = new BillingLocationSync(stripeProperties, tenantRepository, stripeBillingService, meterRegistry);

        tenantId = UUID.randomUUID();
        tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setBillingPlanMode(BillingPlanMode.SAAS_HYBRID_STANDARD);
        tenant.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        tenant.setStripeSubscriptionId("sub_loc_sync_01");
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TenantContext.clear();
    }

    @Test
    @DisplayName("30-25: syncAfterCommit updates SubscriptionItem quantity after commit when location count changed")
    void syncAfterCommit_updatesSubscriptionItemQuantityWithIdempotencyKey() throws Exception {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        AtomicReference<Optional<UUID>> tenantAtQuantityRead = new AtomicReference<>();
        when(stripeBillingService.locationQuantity(tenantId, BillingPlanMode.SAAS_HYBRID_STANDARD))
                .thenAnswer(inv -> {
                    tenantAtQuantityRead.set(TenantContext.get());
                    return 3L;
                });

        SubscriptionItem mockItem = mock(SubscriptionItem.class);
        when(mockItem.getId()).thenReturn("si_loc_01");
        when(mockItem.getQuantity()).thenReturn(2L);
        when(mockItem.update(any(SubscriptionItemUpdateParams.class), any(RequestOptions.class)))
                .thenReturn(mockItem);

        SubscriptionItemCollection itemCollection = new SubscriptionItemCollection();
        itemCollection.setData(List.of(mockItem));
        Subscription mockSub = mock(Subscription.class);
        when(mockSub.getItems()).thenReturn(itemCollection);

        TransactionSynchronizationManager.initSynchronization();
        sync.syncAfterCommit(tenantId, "shop-create-123");

        // Before commit: no Stripe call yet
        try (MockedStatic<Subscription> subStatic = mockStatic(Subscription.class)) {
            subStatic.verifyNoInteractions();

            subStatic.when(() -> Subscription.retrieve("sub_loc_sync_01")).thenReturn(mockSub);
            for (TransactionSynchronization ts : TransactionSynchronizationManager.getSynchronizations()) {
                ts.afterCommit();
            }

            assertThat(tenantAtQuantityRead.get())
                    .as("T-30-98: TenantContext must be pinned to the shop's tenant during afterCommit sync")
                    .contains(tenantId);
            assertThat(TenantContext.get()).as("TenantContext restored after sync").isEmpty();

            ArgumentCaptor<SubscriptionItemUpdateParams> paramsCaptor =
                    ArgumentCaptor.forClass(SubscriptionItemUpdateParams.class);
            ArgumentCaptor<RequestOptions> optsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
            verify(mockItem).update(paramsCaptor.capture(), optsCaptor.capture());
            assertThat(paramsCaptor.getValue().getQuantity()).isEqualTo(3L);
            assertThat(optsCaptor.getValue().getIdempotencyKey())
                    .isEqualTo("billing-qty-si_loc_01-3-shop-create-123");
            assertThat(meterRegistry.counter(BillingLocationSync.FAILED_METRIC).count()).isZero();
        }
    }

    @Test
    @DisplayName("30-25: when Stripe quantity already equals locationQuantity, sync makes no SubscriptionItem.update call")
    void sync_whenQuantityUnchanged_skipsUpdate() throws Exception {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(stripeBillingService.locationQuantity(tenantId, BillingPlanMode.SAAS_HYBRID_STANDARD))
                .thenReturn(2L);

        SubscriptionItem mockItem = mock(SubscriptionItem.class);
        when(mockItem.getQuantity()).thenReturn(2L);
        SubscriptionItemCollection itemCollection = new SubscriptionItemCollection();
        itemCollection.setData(List.of(mockItem));
        Subscription mockSub = mock(Subscription.class);
        when(mockSub.getItems()).thenReturn(itemCollection);

        try (MockedStatic<Subscription> subStatic = mockStatic(Subscription.class)) {
            subStatic.when(() -> Subscription.retrieve("sub_loc_sync_01")).thenReturn(mockSub);

            sync.sync(tenantId, "shop-noop");

            verify(mockItem, never()).update(any(SubscriptionItemUpdateParams.class), any(RequestOptions.class));
        }
    }

    @Test
    @DisplayName("30-25: tenant without an active per-location subscription makes zero Stripe calls")
    void sync_withoutActivePerLocationSubscription_makesNoStripeCall() {
        // Case 1: per-account mode (WALK_SETUP_STEPDOWN)
        tenant.setBillingPlanMode(BillingPlanMode.WALK_SETUP_STEPDOWN);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        try (MockedStatic<Subscription> subStatic = mockStatic(Subscription.class)) {
            sync.sync(tenantId, "shop-walk");
            subStatic.verifyNoInteractions();

            // Case 2: CANCELED subscription on per-location mode
            tenant.setBillingPlanMode(BillingPlanMode.SAAS_HYBRID_STANDARD);
            tenant.setSubscriptionStatus(SubscriptionStatus.CANCELED);
            sync.sync(tenantId, "shop-canceled");
            subStatic.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("30-25 (T-30-99/T-30-100): when Stripe throws during sync, no exception escapes and jtoye.billing.location_sync.failed increments")
    void sync_whenStripeThrows_swallowsExceptionAndIncrementsFailureCounter() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(stripeBillingService.locationQuantity(tenantId, BillingPlanMode.SAAS_HYBRID_STANDARD))
                .thenReturn(4L);

        try (MockedStatic<Subscription> subStatic = mockStatic(Subscription.class)) {
            subStatic.when(() -> Subscription.retrieve("sub_loc_sync_01"))
                    .thenThrow(new ApiConnectionException("Stripe unreachable"));

            sync.sync(tenantId, "shop-fail");

            assertThat(meterRegistry.counter(BillingLocationSync.FAILED_METRIC).count()).isEqualTo(1.0);
        }
    }
}
