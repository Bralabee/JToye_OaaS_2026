package uk.jtoye.core.onboarding.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import uk.jtoye.core.onboarding.OnboardingProperties;

import java.time.Duration;
import java.util.List;

/**
 * Queries the free FSA Food Hygiene Rating Scheme (FHRS) Open Data API to find the
 * establishment(s) matching a shop's name + address. Two hard-won behaviours:
 *
 * <ul>
 *   <li><strong>Mandatory version header:</strong> every request carries
 *       {@code x-api-version} = {@link OnboardingProperties.Fhrs#getApiVersion()}
 *       ("2"). Omitting it makes the API return NO data (silent empty), which would
 *       masquerade as "no match" — see {@code docs/vendor-onboarding-research.md} §6.</li>
 *   <li><strong>Circuit breaker + timeout:</strong> the call is guarded by
 *       {@code @CircuitBreaker(name = "fhrs")} (configured in {@code application.yml})
 *       and an explicit {@code block()} timeout. On a 5xx, open circuit, or timeout
 *       the exception PROPAGATES so {@code FhrsGate} can catch it and degrade to
 *       MANUAL_REVIEW — the client never swallows a failure into a silent pass.</li>
 * </ul>
 *
 * <p>The {@link WebClient} is built from the injected auto-configured
 * {@link WebClient.Builder} (mirroring {@code ai/ImageAnalysisService}'s blocking
 * external-HTTP usage) so tests can construct it over an in-memory
 * {@code ExchangeFunction} stub with zero new dependencies.
 */
@Component
public class FhrsClient {

    private static final Logger log = LoggerFactory.getLogger(FhrsClient.class);
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final OnboardingProperties properties;

    public FhrsClient(WebClient.Builder webClientBuilder, OnboardingProperties properties) {
        this.properties = properties;
        this.webClient = webClientBuilder
                .baseUrl(properties.getFhrs().getBaseUrl())
                .build();
    }

    /**
     * Look up FSA establishments matching the given name + address. Returns the
     * (possibly empty) list of matches; the caller decides pass/fail/ambiguity.
     *
     * @throws RuntimeException on 5xx / open circuit / timeout (mapped to
     *         MANUAL_REVIEW by the gate — never a silent pass).
     */
    @CircuitBreaker(name = "fhrs")
    public List<FhrsEstablishment> lookup(String name, String address) {
        FhrsResponse response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/Establishments")
                        .queryParam("name", name)
                        .queryParam("address", address)
                        .build())
                .header("x-api-version", properties.getFhrs().getApiVersion())
                .retrieve()
                .bodyToMono(FhrsResponse.class)
                .timeout(LOOKUP_TIMEOUT)
                .block();

        if (response == null || response.establishments() == null) {
            log.debug("FHRS lookup for '{}' returned no establishments payload", name);
            return List.of();
        }
        return response.establishments();
    }

    /** Minimal wrapper for the FSA {@code /Establishments} response envelope. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FhrsResponse(List<FhrsEstablishment> establishments) {
    }
}
