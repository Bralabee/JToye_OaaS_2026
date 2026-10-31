package uk.jtoye.core.payment;

import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.jtoye.core.exception.InvalidStateTransitionException;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderEvent;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStateMachineService;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.payment.dto.CreateRefundRequest;
import uk.jtoye.core.payment.dto.RefundDto;
import uk.jtoye.core.security.TenantContext;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RefundService} with all Stripe / JPA dependencies
 * mocked. Verifies the stored-first idempotency contract LOCKED in
 * Phase 17 CONTEXT.md.
 */
@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock private RefundRepository refundRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderStateMachineService stateMachineService;
    @Mock private RefundMapper refundMapper;
    @Mock private StripeRefundClient stripeRefundClient;
    @Mock private RefundEventPublisher refundEventPublisher;
    @Mock private StripeConnectService stripeConnectService;

    private RefundService refundService;

    private UUID tenantId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        refundService = new RefundService(
                refundRepository, orderRepository, stateMachineService,
                refundMapper, stripeRefundClient, refundEventPublisher,
                stripeConnectService
        );
        tenantId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        TenantContext.set(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Order buildOrder(OrderStatus status, long total, String paymentRef) {
        Order order = new Order();
        try {
            Field idField = Order.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, orderId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        order.setTenantId(tenantId);
        order.setShopId(UUID.randomUUID());
        order.setOrderNumber("ORD-TEST");
        order.setStatus(status);
        order.setTotalAmountPennies(total);
        order.setPaymentReference(paymentRef);
        return order;
    }

    private com.stripe.model.Refund stripeRefund(String id, String status) {
        com.stripe.model.Refund r = new com.stripe.model.Refund();
        r.setId(id);
        r.setStatus(status);
        return r;
    }

    private CreateRefundRequest req(Long amount, RefundReason reason, String note) {
        return new CreateRefundRequest(amount, reason, note);
    }

    private RefundDto stubDto(UUID id) {
        return new RefundDto(
                id, tenantId, orderId, "re_stripe", "idem",
                500L, "gbp", RefundReason.REQUESTED_BY_CUSTOMER, null,
                RefundStatus.succeeded, null, null, null
        );
    }

    // ------------------------------------------------------------------
    // Happy-path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Happy path: persists CREATING then succeeded, calls Stripe once, transitions order")
    void createRefund_happyPath_persistsCreatingThenSucceededAndTransitionsOrder() throws StripeException {
        Order order = buildOrder(OrderStatus.CONFIRMED, 1000L, "pi_test_3ABC");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(refundRepository.sumLiveAmountByOrderId(orderId)).thenReturn(0L);

        // Snapshot the Refund state at saveAndFlush time — production code mutates
        // the same instance in-place after Stripe returns, so a plain captor would
        // observe the post-mutation state, not the CREATING insert moment.
        java.util.concurrent.atomic.AtomicReference<RefundStatus> statusAtFlush = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> idemKeyAtFlush = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Long> amountAtFlush = new java.util.concurrent.atomic.AtomicReference<>();
        when(refundRepository.saveAndFlush(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            statusAtFlush.set(r.getStatus());
            idemKeyAtFlush.set(r.getIdempotencyKey());
            amountAtFlush.set(r.getAmountPennies());
            try {
                Field idField = Refund.class.getDeclaredField("id");
                idField.setAccessible(true);
                if (r.getId() == null) idField.set(r, UUID.randomUUID());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            return r;
        });
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stripeRefundClient.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(stripeRefund("re_3XYZ", "succeeded"));
        when(stateMachineService.sendEvent(eq(orderId), eq(OrderStatus.CONFIRMED), eq(OrderEvent.REFUND_REQUESTED)))
                .thenReturn(OrderStatus.REFUNDED);
        when(refundMapper.toDto(any(Refund.class))).thenAnswer(inv -> stubDto(((Refund) inv.getArgument(0)).getId()));

        RefundDto dto = refundService.createRefund(orderId, req(500L, RefundReason.REQUESTED_BY_CUSTOMER, "Note"), null);

        assertThat(dto).isNotNull();
        verify(refundRepository, times(1)).saveAndFlush(any(Refund.class));   // CREATING insert
        verify(refundRepository, times(1)).save(any(Refund.class));            // post-Stripe update
        verify(stripeRefundClient, times(1)).create(any(RefundCreateParams.class), any(RequestOptions.class));
        verify(stateMachineService, times(1))
                .sendEvent(orderId, OrderStatus.CONFIRMED, OrderEvent.REFUND_REQUESTED);
        verify(orderRepository, times(1)).save(any(Order.class));

        // Snapshot at the saveAndFlush moment proves the row was inserted as CREATING
        assertThat(statusAtFlush.get())
                .as("Refund must be persisted as CREATING BEFORE the Stripe call")
                .isEqualTo(RefundStatus.CREATING);
        assertThat(amountAtFlush.get()).isEqualTo(500L);
        assertThat(idemKeyAtFlush.get()).isNotBlank();

        // After Stripe returns, the row should be updated to succeeded with the Stripe ID
        ArgumentCaptor<Refund> updatedCap = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(updatedCap.capture());
        assertThat(updatedCap.getValue().getStatus()).isEqualTo(RefundStatus.succeeded);
        assertThat(updatedCap.getValue().getStripeRefundId()).isEqualTo("re_3XYZ");

        // Stripe call carried the same idempotency key we stored
        ArgumentCaptor<RequestOptions> optsCap = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeRefundClient).create(any(RefundCreateParams.class), optsCap.capture());
        assertThat(optsCap.getValue().getIdempotencyKey())
                .as("Stripe Idempotency-Key must match the stored Refund.idempotency_key")
                .isEqualTo(idemKeyAtFlush.get());
    }

    // ------------------------------------------------------------------
    // Idempotency: client-supplied X-Idempotency-Key
    // ------------------------------------------------------------------

    @Test
    @DisplayName("X-Idempotency-Key replay returns existing Refund without calling Stripe")
    void createRefund_clientIdempotencyKeyMatchesExistingRefund_returnsExistingNoStripeCall() throws StripeException {
        String clientKey = "client-idem-key-xyz";
        Refund existing = new Refund(tenantId, orderId, "pi_test", clientKey, 500L,
                RefundReason.REQUESTED_BY_CUSTOMER, null);
        existing.setStatus(RefundStatus.succeeded);
        when(refundRepository.findByTenantIdAndIdempotencyKey(tenantId, clientKey))
                .thenReturn(Optional.of(existing));
        when(refundMapper.toDto(existing)).thenReturn(stubDto(UUID.randomUUID()));

        RefundDto dto = refundService.createRefund(
                orderId, req(500L, RefundReason.REQUESTED_BY_CUSTOMER, null), clientKey);

        assertThat(dto).isNotNull();
        verify(stripeRefundClient, never()).create(any(), any());
        verifyNoInteractions(stateMachineService);
        verify(refundRepository, never()).saveAndFlush(any(Refund.class));
        verify(orderRepository, never()).findById(any());
    }

    // ------------------------------------------------------------------
    // Idempotency: order already REFUNDED
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Order already REFUNDED short-circuits, returns latest existing Refund")
    void createRefund_orderAlreadyRefunded_returnsLatestRefundNoStripeCall() throws StripeException {
        Order order = buildOrder(OrderStatus.REFUNDED, 1000L, "pi_test");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        Refund existing = new Refund(tenantId, orderId, "pi_test", "stored-key", 1000L,
                RefundReason.REQUESTED_BY_CUSTOMER, null);
        existing.setStatus(RefundStatus.succeeded);
        when(refundRepository.findByOrderIdOrderByRequestedAtDesc(orderId))
                .thenReturn(List.of(existing));
        when(refundMapper.toDto(existing)).thenReturn(stubDto(UUID.randomUUID()));

        RefundDto dto = refundService.createRefund(
                orderId, req(500L, RefundReason.REQUESTED_BY_CUSTOMER, null), null);

        assertThat(dto).isNotNull();
        verifyNoInteractions(stripeRefundClient);
        verifyNoInteractions(stateMachineService);
        verify(refundRepository, never()).saveAndFlush(any(Refund.class));
    }

    @Test
    @DisplayName("Order REFUNDED but no Refund row throws — data drift")
    void createRefund_refundedOrderWithNoRefundRow_throwsIllegalState() {
        Order order = buildOrder(OrderStatus.REFUNDED, 1000L, "pi_test");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(refundRepository.findByOrderIdOrderByRequestedAtDesc(orderId)).thenReturn(List.of());

        assertThatThrownBy(() -> refundService.createRefund(
                orderId, req(500L, RefundReason.REQUESTED_BY_CUSTOMER, null), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data drift");
    }

    // ------------------------------------------------------------------
    // Refundable status checks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Order in DRAFT status throws InvalidStateTransition")
    void createRefund_orderInDraftStatus_throwsInvalidStateTransition() {
        Order order = buildOrder(OrderStatus.DRAFT, 1000L, "pi_test");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> refundService.createRefund(
                orderId, req(500L, RefundReason.REQUESTED_BY_CUSTOMER, null), null))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    @DisplayName("Order with no Stripe payment_intent throws InvalidStateTransition")
    void createRefund_orderHasNoPaymentReference_throwsInvalidStateTransition() {
        Order order = buildOrder(OrderStatus.CONFIRMED, 1000L, null);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> refundService.createRefund(
                orderId, req(500L, RefundReason.REQUESTED_BY_CUSTOMER, null), null))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("no Stripe payment_intent");
    }

    // ------------------------------------------------------------------
    // Amount validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("amountPennies exceeds remaining throws IllegalArgumentException")
    void createRefund_amountExceedsRemaining_throwsIllegalArgument() {
        Order order = buildOrder(OrderStatus.CONFIRMED, 1000L, "pi_test");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        // Simulate prior refunds totalling 1000 — nothing left
        when(refundRepository.sumLiveAmountByOrderId(orderId)).thenReturn(1000L);

        assertThatThrownBy(() -> refundService.createRefund(
                orderId, req(500L, RefundReason.REQUESTED_BY_CUSTOMER, null), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing left to refund");
    }

    @Test
    @DisplayName("amountPennies > remaining (partial-refund overflow) throws IllegalArgumentException")
    void createRefund_remainingZeroAfterPriorRefund_throwsIllegalArgument() {
        Order order = buildOrder(OrderStatus.CONFIRMED, 1000L, "pi_test");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        // 600 already refunded → 400 remaining; client requests 500
        when(refundRepository.sumLiveAmountByOrderId(orderId)).thenReturn(600L);

        assertThatThrownBy(() -> refundService.createRefund(
                orderId, req(500L, RefundReason.REQUESTED_BY_CUSTOMER, null), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds remaining refundable");
    }

    @Test
    @DisplayName("Null amountPennies refunds full remaining and persists that amount")
    void createRefund_nullAmount_passesNullToStripeAndPersistsRemaining() throws StripeException {
        Order order = buildOrder(OrderStatus.CONFIRMED, 1000L, "pi_test");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(refundRepository.sumLiveAmountByOrderId(orderId)).thenReturn(0L);
        when(refundRepository.saveAndFlush(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            try {
                Field idField = Refund.class.getDeclaredField("id");
                idField.setAccessible(true);
                if (r.getId() == null) idField.set(r, UUID.randomUUID());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            return r;
        });
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stripeRefundClient.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(stripeRefund("re_full", "succeeded"));
        when(stateMachineService.sendEvent(eq(orderId), eq(OrderStatus.CONFIRMED), eq(OrderEvent.REFUND_REQUESTED)))
                .thenReturn(OrderStatus.REFUNDED);
        when(refundMapper.toDto(any(Refund.class))).thenAnswer(inv -> stubDto(((Refund) inv.getArgument(0)).getId()));

        refundService.createRefund(orderId, req(null, RefundReason.REQUESTED_BY_CUSTOMER, null), null);

        ArgumentCaptor<Refund> creatingCap = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).saveAndFlush(creatingCap.capture());
        assertThat(creatingCap.getValue().getAmountPennies())
                .as("Null amount must default to full remaining (1000 - 0 = 1000)")
                .isEqualTo(1000L);
    }

    // ------------------------------------------------------------------
    // Stripe failure handling
    // ------------------------------------------------------------------

    @Test
    @DisplayName("StripeException during create marks Refund failed via REQUIRES_NEW and rethrows")
    void createRefund_stripeExceptionDuringCreate_marksRefundFailedAndRethrows() throws StripeException {
        Order order = buildOrder(OrderStatus.CONFIRMED, 1000L, "pi_test");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(refundRepository.sumLiveAmountByOrderId(orderId)).thenReturn(0L);

        UUID assignedId = UUID.randomUUID();
        when(refundRepository.saveAndFlush(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            try {
                Field idField = Refund.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(r, assignedId);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            return r;
        });
        // For markRefundFailed REQUIRES_NEW path
        Refund stub = new Refund(tenantId, orderId, "pi_test", "k", 500L,
                RefundReason.REQUESTED_BY_CUSTOMER, null);
        try {
            Field idField = Refund.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(stub, assignedId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        lenient().when(refundRepository.findById(assignedId)).thenReturn(Optional.of(stub));

        StripeException stripeFail = new InvalidRequestException(
                "amount too high", null, null, null, 400, null);
        when(stripeRefundClient.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(stripeFail);

        assertThatThrownBy(() -> refundService.createRefund(
                orderId, req(500L, RefundReason.REQUESTED_BY_CUSTOMER, null), null))
                .isSameAs(stripeFail);

        // saveAndFlush(CREATING) + save(failed via markRefundFailed)
        verify(refundRepository, times(1)).saveAndFlush(any(Refund.class));
        verify(refundRepository, times(1)).save(any(Refund.class));
        // State machine and order save NEVER called when Stripe failed
        verifyNoInteractions(stateMachineService);
        verify(orderRepository, never()).save(any(Order.class));

        ArgumentCaptor<Refund> failedCap = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(failedCap.capture());
        assertThat(failedCap.getValue().getStatus()).isEqualTo(RefundStatus.failed);
        assertThat(failedCap.getValue().getFailureReason()).contains("amount too high");
    }

    // ------------------------------------------------------------------
    // Tenant context
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Missing TenantContext throws IllegalState (security configuration error)")
    void createRefund_noTenantContext_throwsIllegalState() {
        TenantContext.clear();

        assertThatThrownBy(() -> refundService.createRefund(
                orderId, req(500L, RefundReason.REQUESTED_BY_CUSTOMER, null), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tenant context not set");
    }

    // ------------------------------------------------------------------
    // findRefund — single-resource GET (issue #97 tail)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("findRefund returns the mapped DTO when the refund belongs to the given order")
    void findRefund_belongsToOrder_returnsDto() {
        UUID refundId = UUID.randomUUID();
        Refund refund = new Refund(tenantId, orderId, "pi_test_3ABC", "idem-1",
                500L, RefundReason.REQUESTED_BY_CUSTOMER, null);
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(refund));
        RefundDto dto = stubDto(refundId);
        when(refundMapper.toDto(refund)).thenReturn(dto);

        assertThat(refundService.findRefund(orderId, refundId)).isSameAs(dto);
    }

    @Test
    @DisplayName("findRefund throws ResourceNotFound when no refund exists with that id")
    void findRefund_missing_throwsResourceNotFound() {
        UUID refundId = UUID.randomUUID();
        when(refundRepository.findById(refundId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refundService.findRefund(orderId, refundId))
                .isInstanceOf(uk.jtoye.core.exception.ResourceNotFoundException.class)
                .hasMessageContaining(refundId.toString());
    }

    @Test
    @DisplayName("findRefund throws ResourceNotFound when the refund belongs to a DIFFERENT order (honest URI hierarchy)")
    void findRefund_wrongOrder_throwsResourceNotFound() {
        UUID refundId = UUID.randomUUID();
        Refund refund = new Refund(tenantId, UUID.randomUUID() /* other order */, "pi_test_3ABC",
                "idem-2", 500L, RefundReason.REQUESTED_BY_CUSTOMER, null);
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(refund));

        assertThatThrownBy(() -> refundService.findRefund(orderId, refundId))
                .isInstanceOf(uk.jtoye.core.exception.ResourceNotFoundException.class)
                .hasMessageContaining(orderId.toString());

        verifyNoInteractions(refundMapper);
    }

    @Test
    @DisplayName("createRefund routes DIRECT_CHARGE_VENDOR_MOR refunds to the connected account via RequestOptions.stripeAccount (PAY-03)")
    void createRefund_directChargeVendorMor_setsStripeAccountHeader() throws StripeException {
        Order order = buildOrder(OrderStatus.COMPLETED, 2000L, "pi_direct_01");
        when(stripeConnectService.resolveSettlementRouting(order)).thenReturn(Optional.of(
                new SettlementRoutingDecision(
                        SettlementPolicyMode.DIRECT_CHARGE_VENDOR_MOR,
                        "acct_vendor_direct_01",
                        2000L, 10L, null, null
                )
        ));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(refundRepository.sumLiveAmountByOrderId(orderId)).thenReturn(0L);
        when(refundRepository.saveAndFlush(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            try {
                Field idField = Refund.class.getDeclaredField("id");
                idField.setAccessible(true);
                if (r.getId() == null) idField.set(r, UUID.randomUUID());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            return r;
        });
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stripeRefundClient.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(stripeRefund("re_direct_01", "succeeded"));
        when(stateMachineService.sendEvent(orderId, OrderStatus.COMPLETED, OrderEvent.REFUND_REQUESTED))
                .thenReturn(OrderStatus.REFUNDED);
        when(refundMapper.toDto(any(Refund.class))).thenReturn(stubDto(UUID.randomUUID()));

        refundService.createRefund(orderId, req(2000L, RefundReason.REQUESTED_BY_CUSTOMER, "Direct refund"), "idem-direct-1");

        ArgumentCaptor<RefundCreateParams> paramsCaptor = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> optsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeRefundClient).create(paramsCaptor.capture(), optsCaptor.capture());

        assertThat(optsCaptor.getValue().getStripeAccount()).isEqualTo("acct_vendor_direct_01");
        assertThat(paramsCaptor.getValue().getReverseTransfer()).isNull();
    }

    @Test
    @DisplayName("createRefund sets reverseTransfer=true on DESTINATION_CHARGE_PLATFORM_MOR connected-account refunds (PAY-03)")
    void createRefund_destinationChargePlatformMor_setsReverseTransferTrue() throws StripeException {
        Order order = buildOrder(OrderStatus.COMPLETED, 3000L, "pi_dest_01");
        when(stripeConnectService.resolveSettlementRouting(order)).thenReturn(Optional.of(
                new SettlementRoutingDecision(
                        SettlementPolicyMode.DESTINATION_CHARGE_PLATFORM_MOR,
                        "acct_vendor_dest_01",
                        3000L, 15L, null, null
                )
        ));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(refundRepository.sumLiveAmountByOrderId(orderId)).thenReturn(0L);
        when(refundRepository.saveAndFlush(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            try {
                Field idField = Refund.class.getDeclaredField("id");
                idField.setAccessible(true);
                if (r.getId() == null) idField.set(r, UUID.randomUUID());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            return r;
        });
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stripeRefundClient.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(stripeRefund("re_dest_01", "succeeded"));
        when(stateMachineService.sendEvent(orderId, OrderStatus.COMPLETED, OrderEvent.REFUND_REQUESTED))
                .thenReturn(OrderStatus.REFUNDED);
        when(refundMapper.toDto(any(Refund.class))).thenReturn(stubDto(UUID.randomUUID()));

        refundService.createRefund(orderId, req(3000L, RefundReason.REQUESTED_BY_CUSTOMER, "Dest refund"), "idem-dest-1");

        ArgumentCaptor<RefundCreateParams> paramsCaptor = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> optsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeRefundClient).create(paramsCaptor.capture(), optsCaptor.capture());

        assertThat(optsCaptor.getValue().getStripeAccount()).isNull();
        assertThat(paramsCaptor.getValue().getReverseTransfer()).isTrue();
    }

    // --- PAY-03 (plan 30-12) Task 2: a refund carries its environment ---

    private RefundCreateParams createRefundAndCaptureParams(RefundService service) throws StripeException {
        Order order = buildOrder(OrderStatus.CONFIRMED, 1000L, "pi_env_refund");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(refundRepository.sumLiveAmountByOrderId(orderId)).thenReturn(0L);
        when(refundRepository.saveAndFlush(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            try {
                Field idField = Refund.class.getDeclaredField("id");
                idField.setAccessible(true);
                if (r.getId() == null) idField.set(r, UUID.randomUUID());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            return r;
        });
        when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stripeRefundClient.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(stripeRefund("re_env_01", "succeeded"));
        when(stateMachineService.sendEvent(orderId, OrderStatus.CONFIRMED, OrderEvent.REFUND_REQUESTED))
                .thenReturn(OrderStatus.REFUNDED);
        when(refundMapper.toDto(any(Refund.class))).thenReturn(stubDto(UUID.randomUUID()));

        service.createRefund(orderId, req(500L, RefundReason.REQUESTED_BY_CUSTOMER, null), "idem-env-1");

        ArgumentCaptor<RefundCreateParams> paramsCaptor = ArgumentCaptor.forClass(RefundCreateParams.class);
        verify(stripeRefundClient).create(paramsCaptor.capture(), any(RequestOptions.class));
        return paramsCaptor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> metadataOf(RefundCreateParams params) {
        return (java.util.Map<String, String>) params.getMetadata();
    }

    @Test
    @DisplayName("PAY-03: a refund created with no environment declaration carries metadata environment=unset")
    void createRefund_undeclaredEnvironment_writesUnset() throws StripeException {
        RefundCreateParams params = createRefundAndCaptureParams(refundService);

        assertThat(metadataOf(params)).containsEntry("environment", "unset");
        assertThat(metadataOf(params)).containsKeys("refund_id", "tenant_id", "order_id");
    }

    @Test
    @DisplayName("PAY-03: a refund carries metadata environment = stripe.environment-tag of the wired StripeProperties")
    void createRefund_declaredEnvironment_writesTheTag() throws StripeException {
        StripeProperties props = new StripeProperties();
        props.setEnvironmentTag("staging");
        RefundService tagged = new RefundService(refundRepository, orderRepository, stateMachineService,
                refundMapper, stripeRefundClient, refundEventPublisher, stripeConnectService, props);

        RefundCreateParams params = createRefundAndCaptureParams(tagged);

        assertThat(metadataOf(params)).containsEntry("environment", "staging");
    }
}
