package uk.jtoye.core.order;

import com.stripe.exception.StripeException;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.common.idempotency.IdempotencyService;
import uk.jtoye.core.exception.CustomerCancelNotAllowedException;
import uk.jtoye.core.exception.CustomerCancelNotAllowedException.Reason;
import uk.jtoye.core.exception.CustomerCancelRefundFailedException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.finance.FinancialTransactionService;
import uk.jtoye.core.payment.RefundService;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storefront.dto.CustomerCancelResult;

import java.util.UUID;

/**
 * A customer cancels their OWN order while it is still PENDING (#868, owner ruling 2026-11-04, persona
 * close-out).
 *
 * <h2>Policy (the ruling)</h2>
 * <ul>
 *   <li>Only a PENDING order, i.e. before the kitchen has accepted it. Any later status is a typed 409
 *       ({@link CustomerCancelNotAllowedException}) whose message sends the customer to the shop.</li>
 *   <li>A pay-on-delivery order is simply cancelled. A card-paid order is refunded in FULL through the
 *       existing {@link RefundService} path, and its sale is reversed in the ledger exactly as a void does.</li>
 *   <li>If the refund fails the cancel is rolled back with it: the customer is never cancelled-but-charged.</li>
 * </ul>
 * <b>Not decided by the ruling, and therefore refused (contact the shop):</b> an order paid in parts
 * (deposit and balance, V87) and an order whose payment has not settled either way. Their refund amount is
 * a policy question nobody has answered, and guessing it would move money.
 *
 * <h2>Who may call it</h2>
 * The caller is anonymous. Ownership is proven the way order tracking proves it: the order number AND the
 * email it was placed with, read under the {@code orders_guest_tracking} policy (V17) from transaction-local
 * GUCs. A signed-in customer's email comes from the verified token instead (the controller resolves it);
 * either way this class only ever sees the one proven pair. A wrong pair is the same 404 as tracking, so
 * the endpoint is no oracle for order numbers. Only AFTER the pair matches is the order's tenant pinned,
 * and only then is anything written.
 *
 * <h2>Idempotency</h2>
 * An optional {@code Idempotency-Key} goes through the V50 store: a replay returns the order's current
 * outcome and never refunds twice; the same key on another order is a 422. Without a key the call is
 * still safe to repeat: cancelling a cancelled order is a 200 that changes nothing.
 *
 * <h2>Why it is in {@code order}</h2>
 * It calls {@link OrderService#applyTransition}, the unauthenticated half of the state transition that
 * every staff cancel also runs, so the kitchen board, stock, slot release, outbox event and customer email
 * behave exactly as for any other cancel. That method is package-private so nothing else can reach it.
 */
@Service
public class CustomerOrderCancellationService {

    private static final Logger log = LoggerFactory.getLogger(CustomerOrderCancellationService.class);

    /** The logical operation id stored in {@code idempotency_keys.endpoint}. */
    public static final String ENDPOINT = "storefront.order-cancel";

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final RefundService refundService;
    private final FinancialTransactionService financialTransactionService;
    private final IdempotencyService idempotencyService;
    private final EntityManager entityManager;

    public CustomerOrderCancellationService(OrderRepository orderRepository,
                                            OrderService orderService,
                                            RefundService refundService,
                                            FinancialTransactionService financialTransactionService,
                                            IdempotencyService idempotencyService,
                                            EntityManager entityManager) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.refundService = refundService;
        this.financialTransactionService = financialTransactionService;
        this.idempotencyService = idempotencyService;
        this.entityManager = entityManager;
    }

    /** The idempotency identity: the key is valid for ONE order, whoever proves it. */
    record CancelIdentity(String orderNumber) { }

    /**
     * @param orderNumber    the order to cancel
     * @param email          the proven address: the request body's for a guest, the verified token's for a
     *                       signed-in customer
     * @param idempotencyKey the optional {@code Idempotency-Key}
     */
    @Transactional
    public CustomerCancelResult cancel(String orderNumber, String email, String idempotencyKey) {
        if (orderNumber == null || orderNumber.isBlank() || email == null || email.isBlank()) {
            throw new IllegalArgumentException("Order number and email are required.");
        }
        pinTrackingPair(orderNumber, email);
        Order found = orderRepository.findByOrderNumberAndCustomerEmail(orderNumber, email)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found. Check your order number and email address."));
        UUID orderId = found.getId();

        // Ownership is proven. From here the order's own tenant is pinned for every read and write; it is
        // cleared in the finally and no repository call follows it (the commit flush needs the GUC to be
        // still set, as in PublicStorefrontService.createGuestOrder).
        TenantContext.set(found.getTenantId());
        try {
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                return cancelOwned(orderId);
            }
            return idempotencyService.executeWithoutStoringResponse(
                    ENDPOINT, idempotencyKey.trim(), new CancelIdentity(found.getOrderNumber()),
                    () -> cancelOwned(orderId),
                    () -> describe(loadOrder(orderId))).value();
        } finally {
            TenantContext.clear();
        }
    }

    private CustomerCancelResult cancelOwned(UUID orderId) {
        Order order = loadOrder(orderId);

        // Already done: a double tap, a retry, or the kitchen got there first. Said truthfully, changes nothing.
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.REFUNDED) {
            return describe(order);
        }
        if (order.isVoided()) {
            throw new CustomerCancelNotAllowedException(order.getOrderNumber(), order.getStatus().name(), Reason.PAYMENT_STATE);
        }
        if (order.getStatus() == OrderStatus.DRAFT) {
            throw new CustomerCancelNotAllowedException(order.getOrderNumber(), order.getStatus().name(), Reason.NOT_PLACED);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new CustomerCancelNotAllowedException(order.getOrderNumber(), order.getStatus().name(), Reason.ALREADY_ACCEPTED);
        }

        // PENDING. Work out whether money moved, and refuse what the ruling did not cover.
        boolean paidInParts = order.getDepositAmountPennies() != null
                || (order.getBalanceDuePennies() != null && order.getBalanceDuePennies() > 0);
        PaymentStatus payment = order.getPaymentStatus() == null ? PaymentStatus.NONE : order.getPaymentStatus();
        boolean hasReference = order.getPaymentReference() != null && !order.getPaymentReference().isBlank();
        boolean cashOrder = payment == PaymentStatus.NONE && !hasReference;
        boolean cardCaptured = payment == PaymentStatus.CAPTURED && hasReference;
        if (paidInParts || !(cashOrder || cardCaptured)) {
            throw new CustomerCancelNotAllowedException(order.getOrderNumber(), order.getStatus().name(), Reason.PAYMENT_STATE);
        }

        // The cancel goes first and is flushed, so a kitchen that accepted a moment ago (the order's
        // @Version moved) fails HERE, before any money moves. Then the refund: if Stripe refuses, the
        // exception rolls the cancel and its outbox event back with it.
        orderService.applyTransition(order, OrderEvent.CANCEL, CancelReason.CUSTOMER_REQUEST);
        orderRepository.flush();

        if (cardCaptured) {
            try {
                refundService.refundCancelledOrderInFull(order.getId());
            } catch (StripeException | io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
                log.warn("Refund for customer cancel of order {} failed: {}", order.getOrderNumber(), e.getMessage());
                throw new CustomerCancelRefundFailedException(order.getOrderNumber(), e);
            }
            financialTransactionService.findSaleForOrder(order.getId()).ifPresent(sale ->
                    financialTransactionService.createReversal(sale, "Reversal of " + order.getOrderNumber()));
        }
        log.info("Order {} cancelled by its customer (refunded={})", order.getOrderNumber(), cardCaptured);
        return describe(loadOrder(order.getId()));
    }

    private Order loadOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found. Check your order number and email address."));
    }

    private static CustomerCancelResult describe(Order order) {
        boolean refunded = order.getPaymentStatus() == PaymentStatus.REFUNDED;
        return new CustomerCancelResult(
                order.getOrderNumber(),
                order.getStatus().name(),
                order.getPaymentStatus() == null ? PaymentStatus.NONE.name() : order.getPaymentStatus().name(),
                refunded,
                refunded ? order.getTotalAmountPennies() : null);
    }

    /** The guest-tracking GUCs (V17): the proof of ownership the RLS policy reads. Transaction-local. */
    private void pinTrackingPair(String orderNumber, String email) {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.tracking_order_number', ?, true)")) {
                stmt.setString(1, orderNumber);
                stmt.execute();
            }
            try (var stmt = connection.prepareStatement("SELECT set_config('app.tracking_email', ?, true)")) {
                stmt.setString(1, email);
                stmt.execute();
            }
        });
    }
}
