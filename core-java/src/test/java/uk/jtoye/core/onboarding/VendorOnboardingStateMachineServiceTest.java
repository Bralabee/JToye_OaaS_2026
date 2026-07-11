package uk.jtoye.core.onboarding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import uk.jtoye.core.exception.InvalidStateTransitionException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit-level transition proof for {@link VendorOnboardingStateMachineService},
 * mirroring {@code OrderStateMachineServiceTest}. Verifies every legal
 * transition from VENDOR_ONBOARDING_STATE_MODEL.md §2.3 is ACCEPTED, that a
 * representative set of illegal transitions throw
 * {@link InvalidStateTransitionException}, and that the guarded transitions
 * (APPROVE / GO_LIVE / REINSTATE) enforce the gate preconditions.
 *
 * <p>Unlike the Order guards (which inject nothing), the onboarding
 * APPROVE/GO_LIVE/REINSTATE guard beans constructor-inject
 * {@link VendorOnboardingGateRepository}, so we {@link MockBean} it and drive the
 * guard outcome by stubbing {@code findByOnboardingId(...)} per test — otherwise
 * the guard beans in the loaded context would have an unsatisfied dependency (N3).
 */
@SpringBootTest
@ActiveProfiles("test")
class VendorOnboardingStateMachineServiceTest {

    @Autowired
    private VendorOnboardingStateMachineService stateMachineService;

    @MockBean
    private VendorOnboardingGateRepository gateRepository;

    private VendorOnboardingGate gate(GateType type, GateStatus status, boolean mandatory) {
        VendorOnboardingGate g = new VendorOnboardingGate();
        g.setGateType(type);
        g.setStatus(status);
        g.setMandatory(mandatory);
        return g;
    }

    /** All mandatory gates PASSED — satisfies the APPROVE guard. */
    private List<VendorOnboardingGate> allMandatoryPassed() {
        return List.of(gate(GateType.BUSINESS_VERIFIED, GateStatus.PASSED, true));
    }

    /** All mandatory PASSED plus a PASSED allergen row — satisfies the GO_LIVE/REINSTATE guard. */
    private List<VendorOnboardingGate> allMandatoryPassedPlusAllergen() {
        return List.of(
                gate(GateType.BUSINESS_VERIFIED, GateStatus.PASSED, true),
                gate(GateType.ALLERGEN_DATA_COMPLETE, GateStatus.PASSED, true));
    }

    @Test
    @DisplayName("SUBMIT: DRAFT -> VERIFYING")
    void submitFromDraft() {
        assertEquals(OnboardingState.VERIFYING,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.DRAFT, OnboardingEvent.SUBMIT));
    }

    @Test
    @DisplayName("GATES_PASSED: VERIFYING -> PENDING_APPROVAL (unguarded machine transition)")
    void gatesPassedFromVerifying() {
        assertEquals(OnboardingState.PENDING_APPROVAL,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.VERIFYING, OnboardingEvent.GATES_PASSED));
    }

    @Test
    @DisplayName("GATE_FAILED: VERIFYING -> ACTION_REQUIRED")
    void gateFailedFromVerifying() {
        assertEquals(OnboardingState.ACTION_REQUIRED,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.VERIFYING, OnboardingEvent.GATE_FAILED));
    }

    @Test
    @DisplayName("RESUBMIT: ACTION_REQUIRED -> VERIFYING")
    void resubmitFromActionRequired() {
        assertEquals(OnboardingState.VERIFYING,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.ACTION_REQUIRED, OnboardingEvent.RESUBMIT));
    }

    @Test
    @DisplayName("APPROVE (guard satisfied): PENDING_APPROVAL -> APPROVED")
    void approveWhenAllMandatoryPassed() {
        when(gateRepository.findByOnboardingId(any(UUID.class))).thenReturn(allMandatoryPassed());
        assertEquals(OnboardingState.APPROVED,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.PENDING_APPROVAL, OnboardingEvent.APPROVE));
    }

    @Test
    @DisplayName("REJECT: VERIFYING / ACTION_REQUIRED / PENDING_APPROVAL -> REJECTED")
    void rejectFromEachSource() {
        assertEquals(OnboardingState.REJECTED,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.VERIFYING, OnboardingEvent.REJECT));
        assertEquals(OnboardingState.REJECTED,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.ACTION_REQUIRED, OnboardingEvent.REJECT));
        assertEquals(OnboardingState.REJECTED,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.PENDING_APPROVAL, OnboardingEvent.REJECT));
    }

    @Test
    @DisplayName("GO_LIVE (guard satisfied): APPROVED -> LIVE")
    void goLiveWhenAllergenPassed() {
        when(gateRepository.findByOnboardingId(any(UUID.class))).thenReturn(allMandatoryPassedPlusAllergen());
        assertEquals(OnboardingState.LIVE,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.APPROVED, OnboardingEvent.GO_LIVE));
    }

    @Test
    @DisplayName("SUSPEND: LIVE -> SUSPENDED")
    void suspendFromLive() {
        assertEquals(OnboardingState.SUSPENDED,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.LIVE, OnboardingEvent.SUSPEND));
    }

    @Test
    @DisplayName("REINSTATE (guard satisfied): SUSPENDED -> LIVE")
    void reinstateWhenAllergenPassed() {
        when(gateRepository.findByOnboardingId(any(UUID.class))).thenReturn(allMandatoryPassedPlusAllergen());
        assertEquals(OnboardingState.LIVE,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.SUSPENDED, OnboardingEvent.REINSTATE));
    }

    @Test
    @DisplayName("WITHDRAW: allowed from every pre-live state")
    void withdrawFromEachSource() {
        assertEquals(OnboardingState.WITHDRAWN,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.DRAFT, OnboardingEvent.WITHDRAW));
        assertEquals(OnboardingState.WITHDRAWN,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.VERIFYING, OnboardingEvent.WITHDRAW));
        assertEquals(OnboardingState.WITHDRAWN,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.ACTION_REQUIRED, OnboardingEvent.WITHDRAW));
        assertEquals(OnboardingState.WITHDRAWN,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.PENDING_APPROVAL, OnboardingEvent.WITHDRAW));
        assertEquals(OnboardingState.WITHDRAWN,
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.APPROVED, OnboardingEvent.WITHDRAW));
    }

    @Test
    @DisplayName("Illegal transitions throw InvalidStateTransitionException")
    void illegalTransitionsThrow() {
        // GO_LIVE from DRAFT — cannot skip the whole flow
        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.DRAFT, OnboardingEvent.GO_LIVE));
        // SUBMIT from LIVE — already live
        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.LIVE, OnboardingEvent.SUBMIT));
        // APPROVE from DRAFT — not in PENDING_APPROVAL
        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.DRAFT, OnboardingEvent.APPROVE));
        // GATES_PASSED from DRAFT — gates only run after SUBMIT
        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.DRAFT, OnboardingEvent.GATES_PASSED));
        // WITHDRAW from a terminal state
        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.REJECTED, OnboardingEvent.WITHDRAW));
    }

    @Test
    @DisplayName("APPROVE guard rejects when a mandatory gate is still PENDING")
    void approveRejectedWhenMandatoryGatePending() {
        when(gateRepository.findByOnboardingId(any(UUID.class)))
                .thenReturn(List.of(gate(GateType.BUSINESS_VERIFIED, GateStatus.PENDING, true)));
        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.PENDING_APPROVAL, OnboardingEvent.APPROVE));
    }

    @Test
    @DisplayName("GO_LIVE guard rejects when ALLERGEN_DATA_COMPLETE is not PASSED")
    void goLiveRejectedWhenAllergenNotPassed() {
        // Mandatory business gate PASSED, but the allergen row is still PENDING.
        when(gateRepository.findByOnboardingId(any(UUID.class))).thenReturn(List.of(
                gate(GateType.BUSINESS_VERIFIED, GateStatus.PASSED, true),
                gate(GateType.ALLERGEN_DATA_COMPLETE, GateStatus.PENDING, true)));
        assertThrows(InvalidStateTransitionException.class, () ->
                stateMachineService.sendEvent(UUID.randomUUID(), OnboardingState.APPROVED, OnboardingEvent.GO_LIVE));
    }
}
