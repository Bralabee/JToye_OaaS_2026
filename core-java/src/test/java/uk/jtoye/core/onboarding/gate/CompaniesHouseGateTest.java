package uk.jtoye.core.onboarding.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.jtoye.core.onboarding.GateResult;
import uk.jtoye.core.onboarding.GateStatus;
import uk.jtoye.core.onboarding.GateType;
import uk.jtoye.core.onboarding.OnboardingModel;
import uk.jtoye.core.onboarding.VendorOnboarding;
import uk.jtoye.core.onboarding.client.CompaniesHouseClient;
import uk.jtoye.core.onboarding.client.CompanyProfile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit proof of the {@code BUSINESS_VERIFIED} gate mapping
 * (VENDOR_ONBOARDING_STATE_MODEL.md §3.1 / §5.4): sole trader (blank number) →
 * WAIVED, {@code active} → PASSED (evidence + external_ref), a non-active status
 * → FAILED, a 404 (no company bears that number) → MANUAL_REVIEW (INT-7 / A14 — it
 * was WAIVED, a fail-open), and any client failure → MANUAL_REVIEW (never a hard fail).
 * Also pins the lookup KEY: Companies House is an exact-key register, so a purely
 * numeric number shorter than 8 characters is left-zero-padded before the call.
 *
 * <p>UNTESTED-IN-RUNTIME: this stack has no {@code COMPANIES_HOUSE_API_KEY}, so the
 * 404 branch is reachable only through this Mockito stub of {@code lookup()}.
 */
@ExtendWith(MockitoExtension.class)
class CompaniesHouseGateTest {

    @Mock
    private CompaniesHouseClient client;

    @InjectMocks
    private CompaniesHouseGate gate;

    private VendorOnboarding onboardingWithCompanyNumber(String number) {
        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setCompanyNumber(number);
        return onboarding;
    }

    @Test
    @DisplayName("gate identity: BUSINESS_VERIFIED, automatic, mandatory")
    void gateIdentity() {
        assertThat(gate.type()).isEqualTo(GateType.BUSINESS_VERIFIED);
        assertThat(gate.isAutomatic()).isTrue();
        assertThat(gate.mandatory(OnboardingModel.MARKETPLACE)).isTrue();
        assertThat(gate.mandatory(OnboardingModel.WHITE_LABEL)).isTrue();
    }

    @Test
    @DisplayName("blank company number -> WAIVED without ever calling the client (sole trader)")
    void blankNumberWaivedWithoutClient() {
        GateResult result = gate.evaluate(onboardingWithCompanyNumber("   "));

        assertThat(result.status()).isEqualTo(GateStatus.WAIVED);
        assertThat(result.reason()).contains("sole trader");
        verify(client, never()).lookup(anyString());
    }

    @Test
    @DisplayName("null company number -> WAIVED without calling the client")
    void nullNumberWaivedWithoutClient() {
        GateResult result = gate.evaluate(onboardingWithCompanyNumber(null));

        assertThat(result.status()).isEqualTo(GateStatus.WAIVED);
        verify(client, never()).lookup(any());
    }

    @Test
    @DisplayName("an active company -> PASSED with evidence + external_ref = company number")
    void activeCompanyPassed() {
        when(client.lookup("12345678")).thenReturn(Optional.of(new CompanyProfile("12345678", "active")));

        GateResult result = gate.evaluate(onboardingWithCompanyNumber("12345678"));

        assertThat(result.status()).isEqualTo(GateStatus.PASSED);
        assertThat(result.externalRef()).isEqualTo("12345678");
        assertThat(result.evidence())
                .containsEntry("company_status", "active")
                .containsEntry("company_number", "12345678");
    }

    /**
     * INT-7 (QA council 20260902-134741, A14): a 404 from an EXACT-KEY register means no
     * company bears that number. Treating it like a sole trader (WAIVED) let a fabricated
     * number clear a mandatory gate, because approve/go-live accept PASSED-or-WAIVED.
     * It now parks for a human — never WAIVED, and never silently FAILED either.
     */
    @Test
    @DisplayName("no Companies House record (empty Optional / 404) -> MANUAL_REVIEW naming the register, never WAIVED")
    void noRecordManualReview() {
        when(client.lookup("00000000")).thenReturn(Optional.empty());

        GateResult result = gate.evaluate(onboardingWithCompanyNumber("00000000"));

        assertThat(result.status()).isEqualTo(GateStatus.MANUAL_REVIEW);
        assertThat(result.status()).isNotEqualTo(GateStatus.WAIVED);
        assertThat(result.reason()).containsIgnoringCase("Companies House register");
    }

    /**
     * A14: Companies House keys are exact (e.g. Tesco is {@code 00445790}); without padding a
     * vendor who types {@code 445790} 404s and would now hard-park for no reason. The
     * service pads on write; the gate pads the LOOKUP key too so rows normalised before this
     * change are still looked up correctly.
     */
    @Test
    @DisplayName("a purely numeric number shorter than 8 is looked up left-zero-padded to 8 (445790 -> 00445790)")
    void numericNumberIsZeroPaddedForLookup() {
        when(client.lookup("00445790")).thenReturn(Optional.of(new CompanyProfile("00445790", "active")));

        GateResult result = gate.evaluate(onboardingWithCompanyNumber("445790"));

        assertThat(result.status()).isEqualTo(GateStatus.PASSED);
        assertThat(result.externalRef()).isEqualTo("00445790");
        assertThat(result.evidence()).containsEntry("company_number", "00445790");
        verify(client, never()).lookup("445790");
    }

    @Test
    @DisplayName("a letter-prefixed number (SC123456) and an already-8-char number (00445790) are looked up unchanged")
    void prefixedAndFullLengthNumbersAreNotPadded() {
        when(client.lookup("SC123456")).thenReturn(Optional.of(new CompanyProfile("SC123456", "active")));
        when(client.lookup("00445790")).thenReturn(Optional.of(new CompanyProfile("00445790", "active")));

        assertThat(gate.evaluate(onboardingWithCompanyNumber("SC123456")).externalRef()).isEqualTo("SC123456");
        assertThat(gate.evaluate(onboardingWithCompanyNumber("00445790")).externalRef()).isEqualTo("00445790");
    }

    @Test
    @DisplayName("a dissolved company -> FAILED naming the status")
    void dissolvedFailed() {
        when(client.lookup("99999999")).thenReturn(Optional.of(new CompanyProfile("99999999", "dissolved")));

        GateResult result = gate.evaluate(onboardingWithCompanyNumber("99999999"));

        assertThat(result.status()).isEqualTo(GateStatus.FAILED);
        assertThat(result.reason()).contains("dissolved");
    }

    @Test
    @DisplayName("client failure (5xx / circuit-open / timeout) -> MANUAL_REVIEW, never FAILED")
    void clientFailureManualReview() {
        when(client.lookup("12345678")).thenThrow(new RuntimeException("companies-house circuit open"));

        GateResult result = gate.evaluate(onboardingWithCompanyNumber("12345678"));

        assertThat(result.status()).isEqualTo(GateStatus.MANUAL_REVIEW);
    }
}
