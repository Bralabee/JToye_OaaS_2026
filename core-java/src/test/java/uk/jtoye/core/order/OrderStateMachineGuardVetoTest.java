package uk.jtoye.core.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineBuilder;
import org.springframework.statemachine.config.StateMachineFactory;
import uk.jtoye.core.exception.InvalidStateTransitionException;

import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #177 regression — guard-vetoed transitions must NOT report success.
 *
 * <p>Spring StateMachine reports a guard-denied transition as
 * {@code ResultType.ACCEPTED} (the event was consumed) while leaving the state
 * unchanged. The original {@link OrderStateMachineService} checked only the
 * ResultType, so a vetoed transition silently "succeeded" and returned the
 * old state. The fix (ported from {@code VendorOnboardingStateMachineService},
 * which hit this exact bug via a RED test in Phase 18-02) throws
 * {@link InvalidStateTransitionException} when the post-event state equals the
 * pre-event state.
 *
 * <p>The production {@code OrderStateMachineConfig} deliberately carries no
 * guards today (the bug is latent), so this test feeds the REAL service a
 * machine that mirrors the order transition table but attaches a controllable
 * guard to PENDING → CONFIRMED — the same technique the onboarding RED test
 * used. No Spring context: the service is constructed directly against a
 * builder-backed {@link StateMachineFactory}, exercising the exact production
 * {@code sendEvent}/{@code isTransitionValid} code paths.
 */
class OrderStateMachineGuardVetoTest {

    /** Controls the PENDING → CONFIRMED guard: true = allow, false = veto. */
    private final AtomicBoolean confirmGuardAllows = new AtomicBoolean(true);

    private OrderStateMachineService stateMachineService;

    @BeforeEach
    void setUp() {
        stateMachineService = new OrderStateMachineService(guardedFactory());
    }

    @Test
    @DisplayName("Issue #177: guard-vetoed transition throws InvalidStateTransitionException instead of reporting success")
    void guardVetoedTransition_throwsInsteadOfSilentlySucceeding() {
        confirmGuardAllows.set(false);

        // Before the fix this returned PENDING (ResultType ACCEPTED, state
        // unchanged) — a silent success for a transition that never happened.
        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachineService.sendEvent(UUID.randomUUID(), OrderStatus.PENDING, OrderEvent.CONFIRM));
    }

    @Test
    @DisplayName("Issue #177: guard-permitted transition still returns the target state (no false positive)")
    void guardPermittedTransition_returnsTargetState() {
        confirmGuardAllows.set(true);

        OrderStatus newStatus = stateMachineService.sendEvent(
                UUID.randomUUID(), OrderStatus.PENDING, OrderEvent.CONFIRM);

        assertEquals(OrderStatus.CONFIRMED, newStatus);
    }

    @Test
    @DisplayName("Issue #177: unguarded transitions on the same machine are unaffected by the equality check")
    void unguardedTransition_unaffected() {
        // The veto flag only gates PENDING -> CONFIRMED; DRAFT -> PENDING has
        // no guard and must keep working even while the flag is false.
        confirmGuardAllows.set(false);

        OrderStatus newStatus = stateMachineService.sendEvent(
                UUID.randomUUID(), OrderStatus.DRAFT, OrderEvent.SUBMIT);

        assertEquals(OrderStatus.PENDING, newStatus);
    }

    @Test
    @DisplayName("Issue #177: isTransitionValid reports a guard-vetoed transition as invalid")
    void isTransitionValid_reflectsGuardOutcome() {
        confirmGuardAllows.set(false);
        assertFalse(stateMachineService.isTransitionValid(OrderStatus.PENDING, OrderEvent.CONFIRM),
                "guard-vetoed transition must not be reported as valid");

        confirmGuardAllows.set(true);
        assertTrue(stateMachineService.isTransitionValid(OrderStatus.PENDING, OrderEvent.CONFIRM));
    }

    /**
     * A factory whose machines mirror the production order states with a
     * guarded PENDING → CONFIRMED transition. A fresh machine is built per
     * call because the service stops each machine after use.
     */
    private StateMachineFactory<OrderStatus, OrderEvent> guardedFactory() {
        return new StateMachineFactory<>() {
            @Override
            public StateMachine<OrderStatus, OrderEvent> getStateMachine() {
                return build();
            }

            @Override
            public StateMachine<OrderStatus, OrderEvent> getStateMachine(String machineId) {
                return build();
            }

            @Override
            public StateMachine<OrderStatus, OrderEvent> getStateMachine(UUID uuid) {
                return build();
            }

            private StateMachine<OrderStatus, OrderEvent> build() {
                try {
                    StateMachineBuilder.Builder<OrderStatus, OrderEvent> builder =
                            StateMachineBuilder.builder();
                    builder.configureConfiguration()
                            .withConfiguration()
                            .autoStartup(false);
                    builder.configureStates()
                            .withStates()
                            .initial(OrderStatus.DRAFT)
                            .states(EnumSet.allOf(OrderStatus.class));
                    builder.configureTransitions()
                            .withExternal()
                                .source(OrderStatus.DRAFT)
                                .target(OrderStatus.PENDING)
                                .event(OrderEvent.SUBMIT)
                                .and()
                            .withExternal()
                                .source(OrderStatus.PENDING)
                                .target(OrderStatus.CONFIRMED)
                                .event(OrderEvent.CONFIRM)
                                .guard(ctx -> confirmGuardAllows.get());
                    return builder.build();
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to build guarded test machine", e);
                }
            }
        };
    }
}
