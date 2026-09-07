package uk.jtoye.core.onboarding.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import uk.jtoye.core.onboarding.OnboardingProperties;

import java.time.Duration;
import java.util.Optional;

/**
 * Thin WebClient wrapper over the free Companies House Public Data API
 * (research §6 / VENDOR_ONBOARDING_STATE_MODEL.md §5.4). Confirms a company
 * exists and reads back its {@code company_status} so the
 * {@code BUSINESS_VERIFIED} gate can pass {@code active} companies.
 *
 * <p><strong>Auth (research §6):</strong> HTTP Basic with the API key as the
 * <em>username</em> and an empty password. The key is sourced from
 * {@link OnboardingProperties} (empty default, redacted {@code toString}) and is
 * NEVER written to a log line — only the company number + resulting status are.
 *
 * <p><strong>Resilience (threat T-18-04-D):</strong> the call is guarded by
 * {@code @CircuitBreaker(name = "companies-house")} and blocks with an explicit
 * timeout; a 5xx / circuit-open / timeout propagates a typed failure that the
 * gate maps to {@code MANUAL_REVIEW}. A 404 (no record — e.g. a company number
 * that never existed) is NOT a transport failure: it returns {@link Optional#empty()}
 * and the gate parks it at {@code MANUAL_REVIEW} (INT-7 / A14 — it used to WAIVE, a
 * fail-open on an exact-key register). Callers pass the canonical 8-character key
 * ({@code CompanyNumbers.normalise}); the API does not pad or fuzzy-match for you.
 */
@Component
public class CompaniesHouseClient {

    private static final Logger log = LoggerFactory.getLogger(CompaniesHouseClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    /** Whether an API key is present — no key means we cannot authenticate at all. */
    private final boolean configured;

    /**
     * Production constructor. Builds the WebClient from config; the API key
     * becomes the HTTP Basic username via a default header (applied to every
     * request) so no call site can forget it.
     */
    @Autowired
    public CompaniesHouseClient(OnboardingProperties properties) {
        this(WebClient.builder(), properties);
    }

    /**
     * Test-friendly constructor: the caller supplies a {@link WebClient.Builder}
     * (a unit test wires an in-memory {@code exchangeFunction} onto it) while
     * this class still owns the baseUrl + HTTP Basic header, so those are
     * exercised by the test rather than reconstructed in it.
     */
    CompaniesHouseClient(WebClient.Builder builder, OnboardingProperties properties) {
        OnboardingProperties.CompaniesHouse ch = properties.getCompaniesHouse();
        String apiKey = ch.getApiKey() == null ? "" : ch.getApiKey();
        this.configured = !apiKey.isBlank();
        this.webClient = builder
                .baseUrl(ch.getBaseUrl())
                // HTTP Basic, API key as username, empty password (research §6).
                .defaultHeaders(headers -> headers.setBasicAuth(apiKey, ""))
                .build();
    }

    /**
     * Look up a company by number.
     *
     * @param companyNumber the registered company number (already validated non-blank by the gate)
     * @return the parsed {@link CompanyProfile}, or {@link Optional#empty()} on a 404 (no record)
     * @throws org.springframework.web.reactive.function.client.WebClientResponseException on a non-404 error status
     */
    @CircuitBreaker(name = "companies-house")
    public Optional<CompanyProfile> lookup(String companyNumber) {
        if (!configured) {
            // Fail CLOSED: with no API key we cannot verify anything. Throw so the
            // gate maps this to MANUAL_REVIEW (a human check) rather than making a
            // doomed authenticated-as-nobody call that leaks to the provider, or
            // silently WAIVING a MANDATORY compliance gate (which would let an
            // unverified vendor auto-approve if the key were simply forgotten).
            throw new IllegalStateException("Companies House API key not configured");
        }
        Optional<CompanyProfile> result = webClient.get()
                .uri("/company/{number}", companyNumber)
                .exchangeToMono(response -> {
                    HttpStatusCode status = response.statusCode();
                    if (status.value() == 404) {
                        // No record — release the body and signal "empty", not an error.
                        return response.releaseBody().then(Mono.just(Optional.<CompanyProfile>empty()));
                    }
                    if (status.isError()) {
                        // 4xx (other than 404) / 5xx -> typed failure the breaker counts
                        // and the gate maps to MANUAL_REVIEW.
                        return response.createException().flatMap(Mono::error);
                    }
                    return response.bodyToMono(CompanyProfile.class).map(Optional::of);
                })
                .block(TIMEOUT);

        Optional<CompanyProfile> profile = result == null ? Optional.empty() : result;
        // Log the number + resulting status only — NEVER the API key.
        log.debug("Companies House lookup: number={} -> status={}",
                companyNumber, profile.map(CompanyProfile::companyStatus).orElse("<no-record>"));
        return profile;
    }
}
