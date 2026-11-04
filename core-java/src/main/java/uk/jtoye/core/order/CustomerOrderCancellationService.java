package uk.jtoye.core.order;

import com.stripe.exception.StripeException;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.jtoye.core.common.idempotency.IdempotencyService;
import uk.jtoye.core.exception.CustomerCancelNotAllowedException;
import uk.jtoye.core.exception.CustomerCancelNotAllowedException.Reason;
import uk.jtoye.core.exception.CustomerCancelRefundFailedException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.finance.FinancialTransactionService;
import uk.jtoye.core.payment.RefundService;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storefront.dto.CustomerCancelResult;

import java.util.List;
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
 *   <li>The order is cancelled and its refund RESERVED in one committed transaction; Stripe is called only
 *       afterwards (see "Order of effects"). A refund that does not complete leaves a cancelled order whose
 *       customer is told so and whose retry finishes the refund.</li>
 * </ul>
 * An order paid in parts (deposit and balance, V87) is refunded what was ACTUALLY PAID (owner ruling
 * 2026-11-04): the captured deposit, or the deposit and the balance when both were paid, as one refund per
 * captured PaymentIntent ({@link RefundService#cancelRefundLegs}), each with its own stable Stripe key.
 *
 * <b>Refused (contact the shop):</b> an order whose payment has not settled either way (PENDING, AUTHORIZED,
 * FAILED), and a deposit order paid in both stages before V90 recorded the deposit intent: the deposit leg
 * cannot be refunded without its intent, and refunding only the balance would be a guess about money.
 *
 * <h2>Order of effects (review of PR #1120, A1/A2)</h2>
 * <ol>
 *   <li><b>One transaction, committed:</b> the cancel transition (kitchen board, stock, slot, outbox event),
 *       the Refund row as CREATING, and the ledger reversal. A kitchen that accepted a moment ago (the order's
 *       {@code @Version}) fails HERE, before any money moves, and rolls everything back.</li>
 *   <li><b>Stripe, with no transaction open,</b> under the row's own stored idempotency key. A card refund can
 *       no longer succeed for an order the database still shows as PENDING and fulfillable, and the
 *       {@code metadata.refund_id} Stripe holds always names a committed row for the {@code refund.*} webhook.</li>
 *   <li><b>One short transaction</b> recording the outcome (Stripe id on the row, payment status REFUNDED).
 *       If it fails, or Stripe's answer is unknown, the committed row is still CREATING: repeating the cancel
 *       (the same request, same {@code Idempotency-Key} or none) re-sends the identical Stripe request, which
 *       Stripe answers with the refund it already made, and finishes the local record. Only a request Stripe
 *       definitively REFUSED is marked {@code failed}; the next attempt is then a new row with a new key.</li>
 * </ol>
 * The price of that order: when Stripe refuses, the order is CANCELLED and the card is not yet refunded, which
 * the customer is told in so many words (502 {@code refund-failed}) and the shop sees as a {@code failed} refund.
 * The alternative (refund first) would let a refunded order stay PENDING for the kitchen to cook.
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
    private final TransactionTemplate tx;

    public CustomerOrderCancellationService(OrderRepository orderRepository,
                                            OrderService orderService,
                                            RefundService refundService,
                                            FinancialTransactionService financialTransactionService,
                                            IdempotencyService idempotencyService,
                                            EntityManager entityManager,
                                            PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.refundService = refundService;
        this.financialTransactionService = financialTransactionService;
        this.idempotencyService = idempotencyService;
        this.entityManager = entityManager;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** The idempotency identity: the key is valid for ONE order, whoever proves it. */
    record CancelIdentity(String orderNumber) { }

    /**
     * What the first transaction decided. {@code result} is the answer when nothing remains to do;
     * {@code refundIds} lists the committed Refund rows (one per captured PaymentIntent) that still need their Stripe call.
     */
    private record Plan(String orderNumber, UUID orderId, UUID tenantId, List<UUID> refundIds, CustomerCancelResult result) {
        static Plan done(Order order, CustomerCancelResult result) {
            return new Plan(order.getOrderNumber(), order.getId(), order.getTenantId(), List.of(), result);
        }

        static Plan refunding(Order order, List<UUID> refundIds) {
            return new Plan(order.getOrderNumber(), order.getId(), order.getTenantId(), refundIds, null);
        }
    }

    /**
     * @param orderNumber    the order to cancel
     * @param email          the proven address: the request body's for a guest, the verified token's for a
     *                       signed-in customer
     * @param idempotencyKey the optional {@code Idempotency-Key}
     */
    public CustomerCancelResult cancel(String orderNumber, String email, String idempotencyKey) {
        if (orderNumber == null || orderNumber.isBlank() || email == null || email.isBlank()) {
            throw new IllegalArgumentException("Order number and email are required.");
        }
        // Transaction 1: ownership, the cancel, the reserved refund and the ledger reversal. Commits here.
        Plan plan = tx.execute(status -> decide(orderNumber, email, idempotencyKey));
        if (plan.refundIds().isEmpty()) {
            return plan.result();
        }

        // Stripe, then transaction 3 (recording). The cancel is already committed; see "Order of effects".
        TenantContext.set(plan.tenantId());
        try {
            try {
                refundService.issueCancelRefunds(plan.refundIds());
            } catch (StripeException | io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
                log.warn("Refund for customer cancel of order {} did not complete: {}", plan.orderNumber(), e.getMessage());
                throw new CustomerCancelRefundFailedException(plan.orderNumber(), e);
            } catch (RuntimeException e) {
                // Stripe may well have refunded and our own recording failed. The row is committed and a
                // repeat finishes it, so say "not confirmed yet", not a stack trace.
                log.error("Recording the refund for customer cancel of order {} failed: {}", plan.orderNumber(), e.toString());
                throw new CustomerCancelRefundFailedException(plan.orderNumber(), e);
            }
            return tx.execute(status -> describe(loadOrder(plan.orderId())));
        } finally {
            TenantContext.clear();
        }
    }

    /** Transaction 1. Runs inside {@link #tx}; clears the tenant before returning, as the commit needs only the GUC. */
    private Plan decide(String orderNumber, String email, String idempotencyKey) {
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
            // A replay never cancels twice, but it DOES finish a refund an earlier attempt left unfinished.
            return idempotencyService.executeWithoutStoringResponse(
                    ENDPOINT, idempotencyKey.trim(), new CancelIdentity(found.getOrderNumber()),
                    () -> cancelOwned(orderId),
                    () -> resumeOrDescribe(loadOrder(orderId))).value();
        } finally {
            TenantContext.clear();
        }
    }

    private Plan cancelOwned(UUID orderId) {
        Order order = loadOrder(orderId);

        // Already done: a double tap, a retry, or the kitchen got there first. Said truthfully, changes nothing,
        // except that a cancelled order whose card refund was left unfinished gets it finished.
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.REFUNDED) {
            return resumeOrDescribe(order);
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
        PaymentStatus payment = order.getPaymentStatus() == null ? PaymentStatus.NONE : order.getPaymentStatus();
        boolean hasReference = order.getPaymentReference() != null && !order.getPaymentReference().isBlank();
        boolean paidInParts = order.getDepositAmountPennies() != null
                || (order.getBalanceDuePennies() != null && order.getBalanceDuePennies() > 0);
        boolean cashOrder = payment == PaymentStatus.NONE && !hasReference && !paidInParts;
        // Card: what was actually paid, leg by leg. No determinable leg (e.g. both stages paid before V90) is
        // a refusal, never a guess.
        boolean cardCaptured = payment == PaymentStatus.CAPTURED && hasReference
                && !RefundService.cancelRefundLegs(order).isEmpty();
        if (!(cashOrder || cardCaptured)) {
            throw new CustomerCancelNotAllowedException(order.getOrderNumber(), order.getStatus().name(), Reason.PAYMENT_STATE);
        }

        // The cancel goes first and is flushed, so a kitchen that accepted a moment ago (the order's
        // @Version moved) fails HERE, before any money moves.
        orderService.applyTransition(order, OrderEvent.CANCEL, CancelReason.CUSTOMER_REQUEST);
        orderRepository.flush();

        if (!cardCaptured) {
            log.info("Order {} cancelled by its customer (refunded=false)", order.getOrderNumber());
            return Plan.done(order, describe(loadOrder(order.getId())));
        }
        // Everything local that a card cancel needs is written, flushed and committed BEFORE Stripe is asked:
        // the Refund row (CREATING) and the ledger reversal. After this transaction only Stripe's answer and
        // its recording are left, and neither can resurrect a PENDING order.
        List<UUID> refundIds = refundService.reserveCancelRefunds(order.getId());
        financialTransactionService.findSaleForOrder(order.getId()).ifPresent(sale ->
                financialTransactionService.createReversal(sale, "Reversal of " + order.getOrderNumber()));
        orderRepository.flush();
        log.info("Order {} cancelled by its customer; refunds {} reserved", order.getOrderNumber(), refundIds);
        return Plan.refunding(order, refundIds);
    }

    /**
     * A CANCELLED order whose card payment still reads CAPTURED and that has a refund attempt of its own
     * customer's cancellation is a half-finished refund: hand it back for another try. Anything else is
     * described as it stands (a staff-cancelled order with no such attempt is never refunded from here).
     */
    private Plan resumeOrDescribe(Order order) {
        boolean unfinishedCardRefund = order.getStatus() == OrderStatus.CANCELLED
                && order.getPaymentStatus() == PaymentStatus.CAPTURED
                && order.getPaymentReference() != null && !order.getPaymentReference().isBlank()
                && refundService.hasCancelRefund(order.getId());
        if (unfinishedCardRefund) {
            List<UUID> refundIds = refundService.reserveCancelRefunds(order.getId());
            log.info("Order {} was cancelled with its refund unfinished; resuming refunds {}", order.getOrderNumber(), refundIds);
            return Plan.refunding(order, refundIds);
        }
        return Plan.done(order, describe(order));
    }

    private Order loadOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found. Check your order number and email address."));
    }

    private CustomerCancelResult describe(Order order) {
        boolean refunded = order.getPaymentStatus() == PaymentStatus.REFUNDED;
        // What was actually refunded: a deposit order's customer got the deposit (and the balance if paid),
        // not necessarily the order total. An order refunded some other way keeps reporting its total.
        long refundedByCancel = refunded ? refundService.cancelRefundedPennies(order.getId()) : 0L;
        return new CustomerCancelResult(
                order.getOrderNumber(),
                order.getStatus().name(),
                order.getPaymentStatus() == null ? PaymentStatus.NONE.name() : order.getPaymentStatus().name(),
                refunded,
                refunded ? (refundedByCancel > 0 ? refundedByCancel : order.getTotalAmountPennies()) : null);
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
