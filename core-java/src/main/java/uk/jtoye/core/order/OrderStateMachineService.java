package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.StateMachineEventResult;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import uk.jtoye.core.exception.InvalidStateTransitionException;

import java.util.UUID;

/**
 * Service for managing order state transitions via Spring StateMachine.
 * Provides business logic for validating and executing state changes.
 *
 * Architecture:
 * - Creates stateless state machines configured with current order state
 * - Validates transitions before applying
 * - Returns new state or throws exception on invalid transition
 * - Thread-safe: each transition gets its own StateMachine instance
 */
@Service
public class OrderStateMachineService {
    private static final Logger log = LoggerFactory.getLogger(OrderStateMachineService.class);

    private final StateMachineFactory<OrderStatus, OrderEvent> stateMachineFactory;

    public OrderStateMachineService(StateMachineFactory<OrderStatus, OrderEvent> stateMachineFactory) {
        this.stateMachineFactory = stateMachineFactory;
    }

    /**
     * Execute a state transition for an order.
     *
     * @param orderId Current order ID (for logging/context)
     * @param currentStatus Current order status
     * @param event Event to trigger
     * @return New status after transition
     * @throws InvalidStateTransitionException if the transition is illegal or a guard vetoes it
     */
    public OrderStatus sendEvent(UUID orderId, OrderStatus currentStatus, OrderEvent event) {
        log.debug("Processing event {} for order {} in state {}", event, orderId, currentStatus);

        // Create stateless state machine configured with current state
        StateMachine<OrderStatus, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(UUID.randomUUID());

        // Stop machine if already started
        stateMachine.stopReactively().block();

        // Set current state
        stateMachine.getStateMachineAccessor()
                .doWithAllRegions(accessor ->
                        accessor.resetStateMachineReactively(
                                new DefaultStateMachineContext<>(currentStatus, null, null, null)
                        ).block()
                );

        // Start machine
        stateMachine.startReactively().block();

        // Send event
        Message<OrderEvent> message = MessageBuilder
                .withPayload(event)
                .setHeader("orderId", orderId)
                .build();

        var result = stateMachine.sendEvent(Mono.just(message)).blockLast();

        OrderStatus newStatus = stateMachine.getState().getId();

        // Issue #177 — two ways an event fails to transition:
        //  (1) no matching transition for event+state  -> ResultType DENIED;
        //  (2) a matching transition whose GUARD returned false -> Spring reports
        //      ResultType ACCEPTED (the event was consumed) but the state does NOT
        //      change. Because every order transition moves to a DIFFERENT state
        //      (see OrderStateMachineConfig — no self-transitions), an unchanged
        //      state after an "accepted" event means the guard vetoed it. Both
        //      cases must surface as InvalidStateTransitionException. Ported from
        //      the hardened VendorOnboardingStateMachineService (Phase 18-02).
        boolean notAccepted = result == null
                || result.getResultType() != StateMachineEventResult.ResultType.ACCEPTED;
        boolean guardVetoed = newStatus == currentStatus;
        if (notAccepted || guardVetoed) {
            String errorMsg = String.format(
                    "Invalid state transition for order %s: cannot apply event %s in state %s",
                    orderId, event, currentStatus
            );
            log.warn(errorMsg);
            throw new InvalidStateTransitionException(errorMsg);
        }

        log.info("Order {} transitioned: {} -> {} (event: {})",
                orderId, currentStatus, newStatus, event);

        // Stop machine
        stateMachine.stopReactively().block();

        return newStatus;
    }

    /**
     * Check if a transition is valid without executing it.
     *
     * @param currentStatus Current order status
     * @param event Event to check
     * @return true if transition is valid
     */
    public boolean isTransitionValid(OrderStatus currentStatus, OrderEvent event) {
        try {
            StateMachine<OrderStatus, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(UUID.randomUUID());
            stateMachine.stopReactively().block();

            stateMachine.getStateMachineAccessor()
                    .doWithAllRegions(accessor ->
                            accessor.resetStateMachineReactively(
                                    new DefaultStateMachineContext<>(currentStatus, null, null, null)
                            ).block()
                    );

            stateMachine.startReactively().block();

            Message<OrderEvent> message = MessageBuilder.withPayload(event).build();
            var result = stateMachine.sendEvent(Mono.just(message)).blockLast();

            OrderStatus newStatus = stateMachine.getState().getId();

            stateMachine.stopReactively().block();

            // Issue #177 — same guard-veto detection as sendEvent: an ACCEPTED
            // result with an unchanged state means a guard vetoed the transition,
            // so it is NOT valid. Every order transition targets a different state.
            return result != null
                    && result.getResultType() == StateMachineEventResult.ResultType.ACCEPTED
                    && newStatus != currentStatus;
        } catch (Exception e) {
            log.debug("Transition validation failed for {} + {}: {}", currentStatus, event, e.getMessage());
            return false;
        }
    }
}
