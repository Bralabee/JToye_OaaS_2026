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
 * (VENDOR_ONBOARDING_STATE_MODEL.md §3.1 / §5.4): sole-trader / no-record →
 * WAIVED, {@code active} → PASSED (evidence + external_ref), a non-active status
 * → FAILED, and any client failure → MANUAL_REVIEW (never a hard fail).
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

    @Test
    @DisplayName("no Companies House record (empty Optional) -> WAIVED")
    void noRecordWaived() {
        when(client.lookup("00000000")).thenReturn(Optional.empty());

        GateResult result = gate.evaluate(onboardingWithCompanyNumber("00000000"));

        assertThat(result.status()).isEqualTo(GateStatus.WAIVED);
        assertThat(result.reason()).contains("no Companies House record");
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
