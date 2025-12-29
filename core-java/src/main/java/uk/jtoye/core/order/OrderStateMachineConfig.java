package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

/**
 * Spring StateMachine configuration for order workflow.
 * Defines valid state transitions, guards, and actions.
 *
 * Architecture:
 * - Stateless: state stored in Order.status, not in StateMachine
 * - Tenant-safe: operates on Order entity which is tenant-scoped
 * - Non-invasive: Order entity unchanged, just status field usage
 */
@Configuration
@EnableStateMachineFactory(name = "orderStateMachineFactory")
public class OrderStateMachineConfig extends StateMachineConfigurerAdapter<OrderStatus, OrderEvent> {
    private static final Logger log = LoggerFactory.getLogger(OrderStateMachineConfig.class);

    @Override
    public void configure(StateMachineStateConfigurer<OrderStatus, OrderEvent> states) throws Exception {
        states
            .withStates()
                .initial(OrderStatus.DRAFT)
                .states(EnumSet.allOf(OrderStatus.class))
                .end(OrderStatus.COMPLETED)
                .end(OrderStatus.CANCELLED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderStatus, OrderEvent> transitions) throws Exception {
        transitions
            // DRAFT → PENDING (submit order)
            .withExternal()
                .source(OrderStatus.DRAFT)
                .target(OrderStatus.PENDING)
                .event(OrderEvent.SUBMIT)
                .action(ctx -> log.info("Order submitted for processing"))
                .and()

            // PENDING → CONFIRMED (confirm order)
            .withExternal()
                .source(OrderStatus.PENDING)
                .target(OrderStatus.CONFIRMED)
                .event(OrderEvent.CONFIRM)
                .action(ctx -> log.info("Order confirmed"))
                .and()

            // CONFIRMED → PREPARING (start preparation)
            .withExternal()
                .source(OrderStatus.CONFIRMED)
                .target(OrderStatus.PREPARING)
                .event(OrderEvent.START_PREP)
                .action(ctx -> log.info("Order preparation started"))
                .and()

            // PREPARING → READY (mark ready)
            .withExternal()
                .source(OrderStatus.PREPARING)
                .target(OrderStatus.READY)
                .event(OrderEvent.MARK_READY)
                .action(ctx -> log.info("Order marked as ready"))
                .and()

            // READY → COMPLETED (complete order)
            .withExternal()
                .source(OrderStatus.READY)
                .target(OrderStatus.COMPLETED)
                .event(OrderEvent.COMPLETE)
                .action(ctx -> log.info("Order completed"))
                .and()

            // CANCEL transitions (from any non-terminal state)
            .withExternal()
                .source(OrderStatus.DRAFT)
                .target(OrderStatus.CANCELLED)
                .event(OrderEvent.CANCEL)
                .action(ctx -> log.info("Draft order cancelled"))
                .and()

            .withExternal()
                .source(OrderStatus.PENDING)
                .target(OrderStatus.CANCELLED)
                .event(OrderEvent.CANCEL)
                .action(ctx -> log.info("Pending order cancelled"))
                .and()

            .withExternal()
                .source(OrderStatus.CONFIRMED)
                .target(OrderStatus.CANCELLED)
                .event(OrderEvent.CANCEL)
                .action(ctx -> log.info("Confirmed order cancelled"))
                .and()

            .withExternal()
                .source(OrderStatus.PREPARING)
                .target(OrderStatus.CANCELLED)
                .event(OrderEvent.CANCEL)
                .action(ctx -> log.info("Preparing order cancelled"))
                .and()

            .withExternal()
                .source(OrderStatus.READY)
                .target(OrderStatus.CANCELLED)
                .event(OrderEvent.CANCEL)
                .action(ctx -> log.info("Ready order cancelled"));
    }
}
