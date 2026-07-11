package uk.jtoye.core.onboarding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.guard.Guard;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Spring StateMachine configuration for the vendor-onboarding workflow
 * (VENDOR_ONBOARDING_STATE_MODEL.md §2.3). Mirrors {@code OrderStateMachineConfig}:
 * stateless (the lifecycle state lives in {@code VendorOnboarding.status}, not in
 * the machine), one machine per transition, tenant-safe.
 *
 * <p>The APPROVE / GO_LIVE / REINSTATE transitions carry gate-precondition
 * {@link Guard}s. Unlike the Order guards (which inject nothing) these query
 * {@link VendorOnboardingGateRepository} for the onboarding's materialised gate
 * rows — the onboarding id is read from the {@code onboardingId} message header
 * that {@link VendorOnboardingStateMachineService} stamps onto the event. Guards
 * return {@code false} (never throw) so a blocked transition surfaces as a
 * non-ACCEPTED result → {@link uk.jtoye.core.exception.InvalidStateTransitionException}.
 *
 * <p>The go-live <em>side effect</em> (flipping {@code Shop.published}) is
 * deliberately NOT here — it lives in {@link VendorOnboardingService} so the DB
 * write happens in the transactional service, exactly as {@code OrderService}
 * performs stock side effects after {@code sendEvent}.
 */
@Configuration
@EnableStateMachineFactory(name = "onboardingStateMachineFactory")
public class VendorOnboardingStateMachineConfig
        extends StateMachineConfigurerAdapter<OnboardingState, OnboardingEvent> {

    private static final Logger log = LoggerFactory.getLogger(VendorOnboardingStateMachineConfig.class);

    static final String ONBOARDING_ID_HEADER = "onboardingId";

    private final VendorOnboardingGateRepository gateRepository;

    public VendorOnboardingStateMachineConfig(VendorOnboardingGateRepository gateRepository) {
        this.gateRepository = gateRepository;
    }

    @Override
    public void configure(StateMachineStateConfigurer<OnboardingState, OnboardingEvent> states) throws Exception {
        states
            .withStates()
                .initial(OnboardingState.DRAFT)
                .states(EnumSet.allOf(OnboardingState.class))
                // REJECTED and WITHDRAWN are terminal (VENDOR_ONBOARDING_STATE_MODEL.md §2.2).
                .end(OnboardingState.REJECTED)
                .end(OnboardingState.WITHDRAWN);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OnboardingState, OnboardingEvent> transitions) throws Exception {
        transitions
            // SUBMIT: DRAFT -> VERIFYING (vendor submits; kicks off the gate chain)
            .withExternal()
                .source(OnboardingState.DRAFT)
                .target(OnboardingState.VERIFYING)
                .event(OnboardingEvent.SUBMIT)
                .action(ctx -> log.info("Onboarding submitted; entering VERIFYING"))
                .and()

            // GATES_PASSED: VERIFYING -> PENDING_APPROVAL (all mandatory gates green)
            .withExternal()
                .source(OnboardingState.VERIFYING)
                .target(OnboardingState.PENDING_APPROVAL)
                .event(OnboardingEvent.GATES_PASSED)
                .action(ctx -> log.info("All mandatory gates passed; awaiting approval"))
                .and()

            // GATE_FAILED: VERIFYING -> ACTION_REQUIRED (a mandatory gate failed)
            .withExternal()
                .source(OnboardingState.VERIFYING)
                .target(OnboardingState.ACTION_REQUIRED)
                .event(OnboardingEvent.GATE_FAILED)
                .action(ctx -> log.info("A gate failed; vendor action required"))
                .and()

            // RESUBMIT: ACTION_REQUIRED -> VERIFYING (vendor fixed and retriggers)
            .withExternal()
                .source(OnboardingState.ACTION_REQUIRED)
                .target(OnboardingState.VERIFYING)
                .event(OnboardingEvent.RESUBMIT)
                .action(ctx -> log.info("Onboarding resubmitted; re-verifying"))
                .and()

            // APPROVE: PENDING_APPROVAL -> APPROVED (guarded — all mandatory gates PASSED/WAIVED)
            .withExternal()
                .source(OnboardingState.PENDING_APPROVAL)
                .target(OnboardingState.APPROVED)
                .event(OnboardingEvent.APPROVE)
                .guard(approveGuard())
                .action(ctx -> log.info("Onboarding approved"))
                .and()

            // REJECT: {VERIFYING, ACTION_REQUIRED, PENDING_APPROVAL} -> REJECTED
            .withExternal()
                .source(OnboardingState.VERIFYING)
                .target(OnboardingState.REJECTED)
                .event(OnboardingEvent.REJECT)
                .action(ctx -> log.info("Onboarding rejected from VERIFYING"))
                .and()
            .withExternal()
                .source(OnboardingState.ACTION_REQUIRED)
                .target(OnboardingState.REJECTED)
                .event(OnboardingEvent.REJECT)
                .action(ctx -> log.info("Onboarding rejected from ACTION_REQUIRED"))
                .and()
            .withExternal()
                .source(OnboardingState.PENDING_APPROVAL)
                .target(OnboardingState.REJECTED)
                .event(OnboardingEvent.REJECT)
                .action(ctx -> log.info("Onboarding rejected from PENDING_APPROVAL"))
                .and()

            // GO_LIVE: APPROVED -> LIVE (guarded — allergen data complete; flips published=true in the service)
            .withExternal()
                .source(OnboardingState.APPROVED)
                .target(OnboardingState.LIVE)
                .event(OnboardingEvent.GO_LIVE)
                .guard(goLiveGuard())
                .action(ctx -> log.info("Onboarding going live"))
                .and()

            // SUSPEND: LIVE -> SUSPENDED (flips published=false in the service)
            .withExternal()
                .source(OnboardingState.LIVE)
                .target(OnboardingState.SUSPENDED)
                .event(OnboardingEvent.SUSPEND)
                .action(ctx -> log.info("Onboarding suspended"))
                .and()

            // REINSTATE: SUSPENDED -> LIVE (guarded — allergen still complete; re-flips published=true)
            .withExternal()
                .source(OnboardingState.SUSPENDED)
                .target(OnboardingState.LIVE)
                .event(OnboardingEvent.REINSTATE)
                .guard(goLiveGuard())
                .action(ctx -> log.info("Onboarding reinstated"))
                .and()

            // WITHDRAW: {DRAFT, VERIFYING, ACTION_REQUIRED, PENDING_APPROVAL, APPROVED} -> WITHDRAWN
            .withExternal()
                .source(OnboardingState.DRAFT)
                .target(OnboardingState.WITHDRAWN)
                .event(OnboardingEvent.WITHDRAW)
                .action(ctx -> log.info("Onboarding withdrawn from DRAFT"))
                .and()
            .withExternal()
                .source(OnboardingState.VERIFYING)
                .target(OnboardingState.WITHDRAWN)
                .event(OnboardingEvent.WITHDRAW)
                .action(ctx -> log.info("Onboarding withdrawn from VERIFYING"))
                .and()
            .withExternal()
                .source(OnboardingState.ACTION_REQUIRED)
                .target(OnboardingState.WITHDRAWN)
                .event(OnboardingEvent.WITHDRAW)
                .action(ctx -> log.info("Onboarding withdrawn from ACTION_REQUIRED"))
                .and()
            .withExternal()
                .source(OnboardingState.PENDING_APPROVAL)
                .target(OnboardingState.WITHDRAWN)
                .event(OnboardingEvent.WITHDRAW)
                .action(ctx -> log.info("Onboarding withdrawn from PENDING_APPROVAL"))
                .and()
            .withExternal()
                .source(OnboardingState.APPROVED)
                .target(OnboardingState.WITHDRAWN)
                .event(OnboardingEvent.WITHDRAW)
                .action(ctx -> log.info("Onboarding withdrawn from APPROVED"));
    }

    /**
     * APPROVE guard: true iff the onboarding has at least one mandatory gate row
     * and every mandatory gate row is PASSED or WAIVED. Reads the onboarding id
     * from the {@code onboardingId} message header.
     */
    private Guard<OnboardingState, OnboardingEvent> approveGuard() {
        return ctx -> {
            List<VendorOnboardingGate> mandatory = mandatoryGates(ctx.getMessageHeader(ONBOARDING_ID_HEADER));
            return !mandatory.isEmpty() && mandatory.stream().allMatch(this::passedOrWaived);
        };
    }

    /**
     * GO_LIVE / REINSTATE guard: the APPROVE precondition PLUS a PASSED
     * ALLERGEN_DATA_COMPLETE gate row (a storefront may not go live with
     * incomplete allergen data — Natasha's Law).
     */
    private Guard<OnboardingState, OnboardingEvent> goLiveGuard() {
        return ctx -> {
            Object idHeader = ctx.getMessageHeader(ONBOARDING_ID_HEADER);
            List<VendorOnboardingGate> gates = gatesFor(idHeader);
            List<VendorOnboardingGate> mandatory = gates.stream().filter(VendorOnboardingGate::isMandatory).toList();
            boolean approveOk = !mandatory.isEmpty() && mandatory.stream().allMatch(this::passedOrWaived);
            boolean allergenComplete = gates.stream()
                    .anyMatch(g -> g.getGateType() == GateType.ALLERGEN_DATA_COMPLETE
                            && g.getStatus() == GateStatus.PASSED);
            return approveOk && allergenComplete;
        };
    }

    private List<VendorOnboardingGate> mandatoryGates(Object idHeader) {
        return gatesFor(idHeader).stream().filter(VendorOnboardingGate::isMandatory).toList();
    }

    private List<VendorOnboardingGate> gatesFor(Object idHeader) {
        if (!(idHeader instanceof UUID onboardingId)) {
            return List.of();
        }
        return gateRepository.findByOnboardingId(onboardingId);
    }

    private boolean passedOrWaived(VendorOnboardingGate gate) {
        return gate.getStatus() == GateStatus.PASSED || gate.getStatus() == GateStatus.WAIVED;
    }
}
