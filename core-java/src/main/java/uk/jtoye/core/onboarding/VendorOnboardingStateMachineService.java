package uk.jtoye.core.onboarding;

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
 * Executes vendor-onboarding state transitions via Spring StateMachine — a
 * direct structural copy of {@code OrderStateMachineService}. Each call builds a
 * fresh, stateless machine from the factory, resets it to the caller's current
 * state, sends the event (carrying the {@code onboardingId} header that the
 * APPROVE/GO_LIVE/REINSTATE guards read), and returns the new state id or throws
 * {@link InvalidStateTransitionException} when the machine does not ACCEPT the
 * event (illegal transition OR a guard veto).
 */
@Service
public class VendorOnboardingStateMachineService {

    private static final Logger log = LoggerFactory.getLogger(VendorOnboardingStateMachineService.class);

    private final StateMachineFactory<OnboardingState, OnboardingEvent> stateMachineFactory;

    public VendorOnboardingStateMachineService(
            StateMachineFactory<OnboardingState, OnboardingEvent> stateMachineFactory) {
        this.stateMachineFactory = stateMachineFactory;
    }

    /**
     * Execute a state transition for an onboarding.
     *
     * @param onboardingId onboarding id (carried as a header so guards can query its gate rows)
     * @param current      current onboarding state
     * @param event        event to trigger
     * @return the new state after a successful transition
     * @throws InvalidStateTransitionException if the transition is illegal or a guard vetoes it
     */
    public OnboardingState sendEvent(UUID onboardingId, OnboardingState current, OnboardingEvent event) {
        log.debug("Processing event {} for onboarding {} in state {}", event, onboardingId, current);

        StateMachine<OnboardingState, OnboardingEvent> stateMachine =
                stateMachineFactory.getStateMachine(UUID.randomUUID());

        stateMachine.stopReactively().block();

        stateMachine.getStateMachineAccessor()
                .doWithAllRegions(accessor ->
                        accessor.resetStateMachineReactively(
                                new DefaultStateMachineContext<>(current, null, null, null)
                        ).block());

        stateMachine.startReactively().block();

        Message<OnboardingEvent> message = MessageBuilder
                .withPayload(event)
                .setHeader(VendorOnboardingStateMachineConfig.ONBOARDING_ID_HEADER, onboardingId)
                .build();

        var result = stateMachine.sendEvent(Mono.just(message)).blockLast();

        OnboardingState newState = stateMachine.getState().getId();

        // Two ways an event fails to transition:
        //  (1) no matching transition for event+state  -> ResultType DENIED;
        //  (2) a matching transition whose GUARD returned false -> Spring reports
        //      ResultType ACCEPTED (the event was consumed) but the state does NOT
        //      change. Because every onboarding transition moves to a DIFFERENT
        //      state, an unchanged state after an "accepted" event means the guard
        //      vetoed it. Both cases must surface as InvalidStateTransitionException.
        boolean notAccepted = result == null
                || result.getResultType() != StateMachineEventResult.ResultType.ACCEPTED;
        boolean guardVetoed = newState == current;
        if (notAccepted || guardVetoed) {
            String errorMsg = String.format(
                    "Invalid onboarding state transition for %s: cannot apply event %s in state %s",
                    onboardingId, event, current);
            log.warn(errorMsg);
            throw new InvalidStateTransitionException(errorMsg);
        }

        log.info("Onboarding {} transitioned: {} -> {} (event: {})", onboardingId, current, newState, event);

        stateMachine.stopReactively().block();
        return newState;
    }
}
