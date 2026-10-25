package uk.jtoye.core.config;

import com.stripe.exception.ApiConnectionException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;
import uk.jtoye.core.payment.PaymentService;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledCleanupServiceTest {

    private static final UUID TEST_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock private OrderRepository orderRepository;
    @Mock private EntityManager entityManager;
    @Mock private Query tenantQuery;
    // QA-council M1: the service now runs per-tenant work via a TransactionTemplate
    // built from this manager. A bare mock is not a CallbackPreferringPTM, so the
    // template's execute() runs the callback directly (getTransaction/commit are
    // no-ops), keeping these unit tests behaviour-preserving.
    @Mock private PlatformTransactionManager transactionManager;
    // issue #1038: a stale card draft's PaymentIntent is cancelled through this
    // bean before its order may be deleted.
    @Mock private PaymentService paymentService;
    @InjectMocks private ScheduledCleanupService cleanupService;

    @BeforeEach
    void setUp() throws Exception {
        Field f = ScheduledCleanupService.class.getDeclaredField("staleDraftHours");
        f.setAccessible(true);
        f.set(cleanupService, 24);

        // Mock tenant lookup so cleanupStaleDraftOrders iterates one test tenant
        when(entityManager.createNativeQuery("SELECT id FROM tenants")).thenReturn(tenantQuery);
        when(tenantQuery.getResultList()).thenReturn(List.of(TEST_TENANT));

        // issue #1038: Stripe configured, platform-account routing, unless a test says otherwise.
        lenient().when(paymentService.isConfigured()).thenReturn(true);
        lenient().when(paymentService.directChargeAccountFor(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("Deletes stale DRAFT orders older than threshold")
    void cleanupStaleDraftOrders_deletesOldDrafts() {
        Order staleDraft = new Order();
        staleDraft.setStatus(OrderStatus.DRAFT);
        // Set createdAt to 48 hours ago via reflection
        try {
            Field f = Order.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(staleDraft, OffsetDateTime.now().minusHours(48));
        } catch (Exception e) { throw new RuntimeException(e); }

        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of(staleDraft));

        cleanupService.cleanupStaleDraftOrders();

        verify(orderRepository).deleteAll(List.of(staleDraft));
    }

    @Test
    @DisplayName("Skips recent DRAFT orders")
    void cleanupStaleDraftOrders_skipsRecentDrafts() {
        Order recentDraft = new Order();
        recentDraft.setStatus(OrderStatus.DRAFT);
        try {
            Field f = Order.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(recentDraft, OffsetDateTime.now().minusMinutes(30));
        } catch (Exception e) { throw new RuntimeException(e); }

        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of(recentDraft));

        cleanupService.cleanupStaleDraftOrders();

        verify(orderRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("No-op when no DRAFT orders exist")
    void cleanupStaleDraftOrders_noOpWhenEmpty() {
        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of());

        cleanupService.cleanupStaleDraftOrders();

        verify(orderRepository, never()).deleteAll(any());
    }

    // ------------------------------------------------------------------
    // issue #1038: a stale card draft's PaymentIntent is cancelled at Stripe
    // BEFORE its order is deleted; anything with money on it, or in doubt, is kept.
    // ------------------------------------------------------------------

    private static final String CARD_INTENT = "pi_test_1038";

    private static Order staleDraft(String paymentReference, PaymentStatus paymentStatus) {
        Order order = new Order();
        order.setStatus(OrderStatus.DRAFT);
        order.setOrderNumber("ORD-1038-" + UUID.randomUUID().toString().substring(0, 8));
        order.setPaymentReference(paymentReference);
        order.setPaymentStatus(paymentStatus);
        try {
            Field id = Order.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(order, UUID.randomUUID());
            Field created = Order.class.getDeclaredField("createdAt");
            created.setAccessible(true);
            created.set(order, OffsetDateTime.now().minusHours(48));
        } catch (Exception e) { throw new RuntimeException(e); }
        return order;
    }

    private void assertKept(Order draft) {
        verify(orderRepository, never()).delete(any(Order.class));
        verify(orderRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("issue #1038: a draft with no payment is deleted without asking Stripe")
    void noPaymentDraft_isDeletedWithoutAskingStripe() throws Exception {
        Order draft = staleDraft(null, PaymentStatus.NONE);
        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of(draft));

        cleanupService.cleanupStaleDraftOrders();

        verify(orderRepository).deleteAll(List.of(draft));
        verify(paymentService, never()).cancelAbandonedPaymentIntent(any(), any());
    }

    @Test
    @DisplayName("issue #1038: a card draft's intent is cancelled at Stripe before the order is deleted")
    void cardDraft_isCancelledAtStripeBeforeItIsDeleted() throws Exception {
        Order draft = staleDraft(CARD_INTENT, PaymentStatus.PENDING);
        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of(draft));
        when(paymentService.cancelAbandonedPaymentIntent(CARD_INTENT, null))
                .thenReturn(PaymentService.STRIPE_STATUS_CANCELED);
        when(orderRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

        cleanupService.cleanupStaleDraftOrders();

        InOrder inOrder = inOrder(paymentService, orderRepository);
        inOrder.verify(paymentService).cancelAbandonedPaymentIntent(CARD_INTENT, null);
        inOrder.verify(orderRepository).delete(draft);
        verify(orderRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("issue #1038: a direct-charge draft's intent is cancelled on the vendor's connected account")
    void cardDraft_directCharge_cancelsOnTheConnectedAccount() throws Exception {
        Order draft = staleDraft(CARD_INTENT, PaymentStatus.PENDING);
        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of(draft));
        when(paymentService.directChargeAccountFor(draft)).thenReturn(Optional.of("acct_vendor_1038"));
        when(paymentService.cancelAbandonedPaymentIntent(CARD_INTENT, "acct_vendor_1038"))
                .thenReturn(PaymentService.STRIPE_STATUS_CANCELED);
        when(orderRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

        cleanupService.cleanupStaleDraftOrders();

        verify(paymentService).cancelAbandonedPaymentIntent(CARD_INTENT, "acct_vendor_1038");
        verify(paymentService, never()).cancelAbandonedPaymentIntent(CARD_INTENT, null);
        verify(orderRepository).delete(draft);
    }

    @ParameterizedTest(name = "intent {0} -> order kept")
    @ValueSource(strings = {"succeeded", "processing", "requires_capture"})
    @DisplayName("issue #1038: a card draft whose intent has taken or is holding money is kept")
    void cardDraft_moneyMovedOrHeld_isKept(String stripeStatus) throws Exception {
        Order draft = staleDraft(CARD_INTENT, PaymentStatus.PENDING);
        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of(draft));
        when(paymentService.cancelAbandonedPaymentIntent(CARD_INTENT, null)).thenReturn(stripeStatus);

        cleanupService.cleanupStaleDraftOrders();

        verify(paymentService).cancelAbandonedPaymentIntent(CARD_INTENT, null);
        assertKept(draft);
    }

    @Test
    @DisplayName("issue #1038: a card draft is kept when the Stripe call fails")
    void cardDraft_stripeError_isKept() throws Exception {
        Order draft = staleDraft(CARD_INTENT, PaymentStatus.PENDING);
        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of(draft));
        when(paymentService.cancelAbandonedPaymentIntent(CARD_INTENT, null))
                .thenThrow(new ApiConnectionException("simulated outage"));

        assertThatCode(() -> cleanupService.cleanupStaleDraftOrders()).doesNotThrowAnyException();

        verify(paymentService).cancelAbandonedPaymentIntent(CARD_INTENT, null);
        assertKept(draft);
    }

    @Test
    @DisplayName("issue #1038: a card draft is kept when the stripe circuit breaker is open")
    void cardDraft_circuitOpen_isKept() throws Exception {
        Order draft = staleDraft(CARD_INTENT, PaymentStatus.PENDING);
        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of(draft));
        when(paymentService.cancelAbandonedPaymentIntent(CARD_INTENT, null))
                .thenThrow(CallNotPermittedException.createCallNotPermittedException(
                        CircuitBreaker.ofDefaults("stripe")));

        assertThatCode(() -> cleanupService.cleanupStaleDraftOrders()).doesNotThrowAnyException();

        verify(paymentService).cancelAbandonedPaymentIntent(CARD_INTENT, null);
        assertKept(draft);
    }

    @Test
    @DisplayName("issue #1038: a card draft is kept, and Stripe never called, when Stripe is not configured")
    void cardDraft_stripeNotConfigured_isKeptWithoutCallingStripe() throws Exception {
        Order draft = staleDraft(CARD_INTENT, PaymentStatus.PENDING);
        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of(draft));
        when(paymentService.isConfigured()).thenReturn(false);

        cleanupService.cleanupStaleDraftOrders();

        verify(paymentService, never()).cancelAbandonedPaymentIntent(any(), any());
        assertKept(draft);
    }

    @ParameterizedTest(name = "reference={0}, paymentStatus={1} -> kept")
    @CsvSource(value = {"plink_test_1,PENDING", "NULL,PENDING", "NULL,AUTHORIZED", "NULL,CAPTURED", "NULL,FAILED"},
            nullValues = "NULL")
    @DisplayName("issue #1038: a draft whose payment is not a recognisable intent is kept, Stripe never called")
    void unrecognisedPayment_isKeptWithoutCallingStripe(String reference, PaymentStatus paymentStatus)
            throws Exception {
        Order draft = staleDraft(reference, paymentStatus);
        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of(draft));

        cleanupService.cleanupStaleDraftOrders();

        verify(paymentService, never()).cancelAbandonedPaymentIntent(any(), any());
        assertKept(draft);
    }

    @Test
    @DisplayName("issue #1038: a card draft that changed between the cancel and the delete is kept")
    void cardDraft_changedAfterCancel_isKept() throws Exception {
        Order draft = staleDraft(CARD_INTENT, PaymentStatus.PENDING);
        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of(draft));
        when(paymentService.cancelAbandonedPaymentIntent(CARD_INTENT, null))
                .thenReturn(PaymentService.STRIPE_STATUS_CANCELED);
        // The re-read finds the order moved on (a webhook made it a real order meanwhile).
        Order reread = staleDraft(CARD_INTENT, PaymentStatus.CAPTURED);
        reread.setStatus(OrderStatus.PENDING);
        when(orderRepository.findById(draft.getId())).thenReturn(Optional.of(reread));

        cleanupService.cleanupStaleDraftOrders();

        verify(orderRepository).findById(draft.getId());
        assertKept(draft);
    }

    @Test
    @DisplayName("issue #1038: a card draft gone by the time of the delete is skipped without an error")
    void cardDraft_goneAfterCancel_isKeptQuietly() throws Exception {
        Order draft = staleDraft(CARD_INTENT, PaymentStatus.PENDING);
        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of(draft));
        when(paymentService.cancelAbandonedPaymentIntent(CARD_INTENT, null))
                .thenReturn(PaymentService.STRIPE_STATUS_CANCELED);
        when(orderRepository.findById(draft.getId())).thenReturn(Optional.empty());

        assertThatCode(() -> cleanupService.cleanupStaleDraftOrders()).doesNotThrowAnyException();

        verify(orderRepository).findById(draft.getId());
        assertKept(draft);
    }
}
