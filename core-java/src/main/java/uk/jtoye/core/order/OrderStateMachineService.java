package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
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
     * @throws InvalidStateTransitionException if transition is not valid
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

        if (result == null || result.getResultType() != org.springframework.statemachine.StateMachineEventResult.ResultType.ACCEPTED) {
            String errorMsg = String.format(
                    "Invalid state transition for order %s: cannot apply event %s in state %s",
                    orderId, event, currentStatus
            );
            log.warn(errorMsg);
            throw new InvalidStateTransitionException(errorMsg);
        }

        OrderStatus newStatus = stateMachine.getState().getId();
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

            stateMachine.stopReactively().block();

            return result != null && result.getResultType() == org.springframework.statemachine.StateMachineEventResult.ResultType.ACCEPTED;
        } catch (Exception e) {
            log.debug("Transition validation failed for {} + {}: {}", currentStatus, event, e.getMessage());
            return false;
        }
    }
}
