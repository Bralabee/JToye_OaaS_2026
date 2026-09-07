package uk.jtoye.core.onboarding.gate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.jtoye.core.onboarding.CompanyNumbers;
import uk.jtoye.core.onboarding.GateResult;
import uk.jtoye.core.onboarding.GateType;
import uk.jtoye.core.onboarding.OnboardingGate;
import uk.jtoye.core.onboarding.OnboardingModel;
import uk.jtoye.core.onboarding.VendorOnboarding;
import uk.jtoye.core.onboarding.client.CompaniesHouseClient;
import uk.jtoye.core.onboarding.client.CompanyProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code BUSINESS_VERIFIED} onboarding gate (VENDOR_ONBOARDING_STATE_MODEL.md
 * §3.1 / §5.4). Being a {@code @Component} that implements {@link OnboardingGate}
 * is all it takes to auto-register into the 18-02
 * {@code GateChainRunner List<OnboardingGate>} registry — no runner edit.
 *
 * <p>Mapping (research §6 — keep a human fallback, never hard-fail an ambiguous case):
 * <ul>
 *   <li>no company number (sole trader) → {@code WAIVED} — the client is never called;</li>
 *   <li>company {@code company_status == "active"} → {@code PASSED} with evidence
 *       ({@code company_status} + {@code company_number}) and external_ref = number;</li>
 *   <li>404 / no record → {@code MANUAL_REVIEW} (see the reversal note below);</li>
 *   <li>a present-but-non-active status (e.g. {@code dissolved}) → {@code FAILED};</li>
 *   <li>a blank/absent status or any client failure (5xx / circuit-open / timeout)
 *       → {@code MANUAL_REVIEW} (threat T-18-04-T: only {@code active} passes; unknown
 *       / error routes to a human, never a silent pass).</li>
 * </ul>
 *
 * <p><strong>Reversal of the Phase 18 "sole-trader/404 → WAIVED" decision (INT-7, QA council
 * 20260902-134741, adjudication A14).</strong> {@code 18-VERIFICATION.md:40} recorded the 404
 * branch as verified behaviour and commit {@code fb44ff40} shipped it deliberately, but no
 * design artefact authorised it: the state model's footnote waives BUSINESS_VERIFIED for
 * <em>sole traders, who have no Companies House record</em> — that is the blank-number branch
 * above — and its §9 item 4 left the substitution rule an OPEN question. The Companies House
 * Public Data API {@code GET /company/{companyNumber}} is an exact-key lookup (200/401/404, no
 * fuzzy search), so a 404 means <em>no company bears this number</em>. Mapping that to WAIVED
 * was a fail-open: {@code approveGuard}/{@code goLiveGuard} accept PASSED-or-WAIVED, so a
 * fabricated registration number cleared a mandatory compliance gate exactly as a genuine
 * sole trader does. The 404 now parks at MANUAL_REVIEW rather than FAILED because the register
 * key is exact and most real 404s are formatting slips ({@code 445790} for {@code 00445790},
 * an unexpected prefix), which the same change also mitigates by normalising the lookup key
 * through {@link uk.jtoye.core.onboarding.CompanyNumbers}; a human can PASS after checking the
 * register by name, and the vendor can still correct the number and re-run. It is never
 * WAIVED: a waiver is reserved for the blank number. Publish-safety: no existing WAIVED row is
 * touched — {@code resubmit()} resets only FAILED/MANUAL_REVIEW rows and the runner
 * re-evaluates only PENDING rows (both proven in {@code OnboardingResubmitIntegrationTest}).
 *
 * <p><strong>UNTESTED-IN-RUNTIME:</strong> this stack has no {@code COMPANIES_HOUSE_API_KEY},
 * so {@code lookup()} throws before any HTTP call and the gate takes the client-failure
 * MANUAL_REVIEW branch. The 404 remap is provable only by the Mockito stub in
 * {@code CompaniesHouseGateTest}; it has not been exercised against the live register.
 */
@Component
public class CompaniesHouseGate implements OnboardingGate {

    private static final Logger log = LoggerFactory.getLogger(CompaniesHouseGate.class);
    private static final String ACTIVE_STATUS = "active";

    private final CompaniesHouseClient client;

    public CompaniesHouseGate(CompaniesHouseClient client) {
        this.client = client;
    }

    @Override
    public GateType type() {
        return GateType.BUSINESS_VERIFIED;
    }

    @Override
    public boolean isAutomatic() {
        return true;
    }

    @Override
    public boolean mandatory(OnboardingModel model) {
        // Mandatory for BOTH commercial models this slice (state model §3.1); the
        // model parameter exists so slice-2 model-specific gates fit (IN-09).
        return true;
    }

    @Override
    public GateResult evaluate(VendorOnboarding onboarding) {
        // INT-7 / A14: the lookup key is the canonical register key (trim, upper-case,
        // zero-pad a purely numeric value to 8). The service normalises on write, but rows
        // written before that padding existed still carry the short form — pad here too so
        // a legacy "445790" is looked up as "00445790" instead of 404-ing.
        String number = CompanyNumbers.normalise(onboarding.getCompanyNumber());
        if (number == null) {
            // Sole traders have no Companies House record — waive rather than block.
            return GateResult.waived("no company number — sole trader");
        }

        try {
            Optional<CompanyProfile> lookup = client.lookup(number);
            if (lookup.isEmpty()) {
                // 404 on an exact-key register: no company bears this number. NOT a waiver
                // (that is reserved for the blank/sole-trader case above) — park for a human.
                // See the class Javadoc for why this reverses the Phase 18 mapping.
                return GateResult.manualReview(
                        "No company with that number was found on the Companies House register"
                                + " — a reviewer will check this manually");
            }

            String status = lookup.get().companyStatus();
            if (status == null || status.isBlank()) {
                // Garbled / missing status — inconclusive, send to a human.
                return GateResult.manualReview("Companies House returned no company_status");
            }
            if (ACTIVE_STATUS.equalsIgnoreCase(status)) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("company_status", status);
                evidence.put("company_number", number);
                return GateResult.passed(evidence, number);
            }
            return GateResult.failed("company status is '" + status + "' (not active)");
        } catch (Exception e) {
            // 5xx / circuit-open / timeout — never hard-fail a vendor on an API wobble.
            log.warn("Companies House lookup failed for company {} — routing to MANUAL_REVIEW: {}",
                    number, e.getMessage());
            // IN-05: persist a FIXED, human-readable reason — the raw exception text
            // (upstream URLs, HTTP statuses, circuit-breaker names) is vendor-visible
            // via GateDto.reason and belongs in the WARN log above, not on the row.
            return GateResult.manualReview(
                    "Business register temporarily unavailable — a reviewer will check this manually");
        }
    }
}
