package uk.jtoye.core.onboarding.gate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
 *   <li>404 / no record → {@code WAIVED};</li>
 *   <li>a present-but-non-active status (e.g. {@code dissolved}) → {@code FAILED};</li>
 *   <li>a blank/absent status or any client failure (5xx / circuit-open / timeout)
 *       → {@code MANUAL_REVIEW} (threat T-18-04-T: only {@code active} passes; unknown
 *       / error routes to a human, never a silent pass).</li>
 * </ul>
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
        String companyNumber = onboarding.getCompanyNumber();
        if (companyNumber == null || companyNumber.isBlank()) {
            // Sole traders have no Companies House record — waive rather than block.
            return GateResult.waived("no company number — sole trader");
        }
        String number = companyNumber.trim();

        try {
            Optional<CompanyProfile> lookup = client.lookup(number);
            if (lookup.isEmpty()) {
                // 404 — the number does not resolve to a record; waive, do not fail.
                return GateResult.waived("no Companies House record");
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
