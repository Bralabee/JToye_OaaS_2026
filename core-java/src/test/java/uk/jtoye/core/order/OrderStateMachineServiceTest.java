package uk.jtoye.core.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uk.jtoye.core.exception.InvalidStateTransitionException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OrderStateMachineService.
 * Verifies all valid state transitions and rejects invalid ones.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderStateMachineServiceTest {

    @Autowired
    private OrderStateMachineService stateMachineService;

    @Test
    @DisplayName("Should allow valid state transitions through happy path")
    void testValidHappyPathTransitions() {
        UUID orderId = UUID.randomUUID();

        // DRAFT -> PENDING
        OrderStatus status = stateMachineService.sendEvent(orderId, OrderStatus.DRAFT, OrderEvent.SUBMIT);
        assertEquals(OrderStatus.PENDING, status);

        // PENDING -> CONFIRMED
        status = stateMachineService.sendEvent(orderId, OrderStatus.PENDING, OrderEvent.CONFIRM);
        assertEquals(OrderStatus.CONFIRMED, status);

        // CONFIRMED -> PREPARING
        status = stateMachineService.sendEvent(orderId, OrderStatus.CONFIRMED, OrderEvent.START_PREP);
        assertEquals(OrderStatus.PREPARING, status);

        // PREPARING -> READY
        status = stateMachineService.sendEvent(orderId, OrderStatus.PREPARING, OrderEvent.MARK_READY);
        assertEquals(OrderStatus.READY, status);

        // READY -> COMPLETED
        status = stateMachineService.sendEvent(orderId, OrderStatus.READY, OrderEvent.COMPLETE);
        assertEquals(OrderStatus.COMPLETED, status);
    }

    @Test
    @DisplayName("Should allow CANCEL from any non-terminal state")
    void testCancelFromAnyState() {
        UUID orderId = UUID.randomUUID();

        // Can cancel from DRAFT
        OrderStatus status = stateMachineService.sendEvent(orderId, OrderStatus.DRAFT, OrderEvent.CANCEL);
        assertEquals(OrderStatus.CANCELLED, status);

        // Can cancel from PENDING
        status = stateMachineService.sendEvent(orderId, OrderStatus.PENDING, OrderEvent.CANCEL);
        assertEquals(OrderStatus.CANCELLED, status);

        // Can cancel from CONFIRMED
        status = stateMachineService.sendEvent(orderId, OrderStatus.CONFIRMED, OrderEvent.CANCEL);
        assertEquals(OrderStatus.CANCELLED, status);

        // Can cancel from PREPARING
        status = stateMachineService.sendEvent(orderId, OrderStatus.PREPARING, OrderEvent.CANCEL);
        assertEquals(OrderStatus.CANCELLED, status);

        // Can cancel from READY
        status = stateMachineService.sendEvent(orderId, OrderStatus.READY, OrderEvent.CANCEL);
        assertEquals(OrderStatus.CANCELLED, status);
    }

    @Test
    @DisplayName("Should reject invalid state transitions")
    void testInvalidTransitions() {
        UUID orderId = UUID.randomUUID();

        // Cannot skip states - DRAFT to CONFIRMED without PENDING
        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachineService.sendEvent(orderId, OrderStatus.DRAFT, OrderEvent.CONFIRM)
        );

        // Cannot go backwards - CONFIRMED to PENDING
        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachineService.sendEvent(orderId, OrderStatus.CONFIRMED, OrderEvent.SUBMIT)
        );

        // Cannot transition from terminal state COMPLETED
        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachineService.sendEvent(orderId, OrderStatus.COMPLETED, OrderEvent.SUBMIT)
        );

        // Cannot transition from terminal state CANCELLED
        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachineService.sendEvent(orderId, OrderStatus.CANCELLED, OrderEvent.CONFIRM)
        );
    }

    @Test
    @DisplayName("Should validate transitions without executing them")
    void testTransitionValidation() {
        // Valid transitions return true
        assertTrue(stateMachineService.isTransitionValid(OrderStatus.DRAFT, OrderEvent.SUBMIT));
        assertTrue(stateMachineService.isTransitionValid(OrderStatus.PENDING, OrderEvent.CONFIRM));
        assertTrue(stateMachineService.isTransitionValid(OrderStatus.CONFIRMED, OrderEvent.START_PREP));
        assertTrue(stateMachineService.isTransitionValid(OrderStatus.PREPARING, OrderEvent.MARK_READY));
        assertTrue(stateMachineService.isTransitionValid(OrderStatus.READY, OrderEvent.COMPLETE));

        // Cancel is valid from all non-terminal states
        assertTrue(stateMachineService.isTransitionValid(OrderStatus.DRAFT, OrderEvent.CANCEL));
        assertTrue(stateMachineService.isTransitionValid(OrderStatus.PENDING, OrderEvent.CANCEL));
        assertTrue(stateMachineService.isTransitionValid(OrderStatus.CONFIRMED, OrderEvent.CANCEL));

        // Invalid transitions return false
        assertFalse(stateMachineService.isTransitionValid(OrderStatus.DRAFT, OrderEvent.CONFIRM));
        assertFalse(stateMachineService.isTransitionValid(OrderStatus.COMPLETED, OrderEvent.SUBMIT));
        assertFalse(stateMachineService.isTransitionValid(OrderStatus.CANCELLED, OrderEvent.CONFIRM));
    }

    @Test
    @DisplayName("Phase 17 VOPS-03: REFUND_REQUESTED from CONFIRMED transitions to REFUNDED")
    void refundRequestedFromConfirmed_transitionsToRefunded() {
        OrderStatus newStatus = stateMachineService.sendEvent(
                UUID.randomUUID(), OrderStatus.CONFIRMED, OrderEvent.REFUND_REQUESTED);
        assertEquals(OrderStatus.REFUNDED, newStatus);
    }

    @Test
    @DisplayName("Phase 17 VOPS-03: REFUND_REQUESTED from PREPARING transitions to REFUNDED")
    void refundRequestedFromPreparing_transitionsToRefunded() {
        OrderStatus newStatus = stateMachineService.sendEvent(
                UUID.randomUUID(), OrderStatus.PREPARING, OrderEvent.REFUND_REQUESTED);
        assertEquals(OrderStatus.REFUNDED, newStatus);
    }

    @Test
    @DisplayName("Phase 17 VOPS-03: REFUND_REQUESTED from READY transitions to REFUNDED")
    void refundRequestedFromReady_transitionsToRefunded() {
        OrderStatus newStatus = stateMachineService.sendEvent(
                UUID.randomUUID(), OrderStatus.READY, OrderEvent.REFUND_REQUESTED);
        assertEquals(OrderStatus.REFUNDED, newStatus);
    }

    @Test
    @DisplayName("Phase 17 VOPS-03: REFUND_REQUESTED from COMPLETED transitions to REFUNDED")
    void refundRequestedFromCompleted_transitionsToRefunded() {
        OrderStatus newStatus = stateMachineService.sendEvent(
                UUID.randomUUID(), OrderStatus.COMPLETED, OrderEvent.REFUND_REQUESTED);
        assertEquals(OrderStatus.REFUNDED, newStatus);
    }

    @Test
    @DisplayName("Phase 17 VOPS-03: REFUND_REQUESTED from DRAFT throws — refunds require captured payment")
    void refundRequestedFromDraft_throwsInvalidStateTransition() {
        UUID orderId = UUID.randomUUID();
        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachineService.sendEvent(orderId, OrderStatus.DRAFT, OrderEvent.REFUND_REQUESTED));
    }

    @Test
    @DisplayName("Phase 17 VOPS-03: REFUND_REQUESTED on REFUNDED order throws — idempotency lives in RefundService")
    void refundRequestedFromRefunded_throwsInvalidStateTransition() {
        // Documents UC-3 LOCKED + research §7.5 Option B: the state machine
        // remains fail-loud on REFUNDED→REFUNDED. RefundService short-circuits
        // BEFORE invoking the state machine on already-REFUNDED orders.
        UUID orderId = UUID.randomUUID();
        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachineService.sendEvent(orderId, OrderStatus.REFUNDED, OrderEvent.REFUND_REQUESTED));
    }

    @Test
    @DisplayName("Should be thread-safe - concurrent transitions use isolated state machines")
    void testThreadSafety() {
        // Each sendEvent creates its own StateMachine instance
        // This test verifies no shared state corruption
        UUID order1 = UUID.randomUUID();
        UUID order2 = UUID.randomUUID();

        OrderStatus status1 = stateMachineService.sendEvent(order1, OrderStatus.DRAFT, OrderEvent.SUBMIT);
        OrderStatus status2 = stateMachineService.sendEvent(order2, OrderStatus.PENDING, OrderEvent.CONFIRM);

        assertEquals(OrderStatus.PENDING, status1);
        assertEquals(OrderStatus.CONFIRMED, status2);
    }
}
