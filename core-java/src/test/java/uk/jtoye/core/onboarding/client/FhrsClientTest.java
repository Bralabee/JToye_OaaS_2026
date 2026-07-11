package uk.jtoye.core.onboarding.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import uk.jtoye.core.onboarding.OnboardingProperties;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit proof of {@link FhrsClient} WITHOUT any new dependency: the WebClient is
 * built over a stub {@link ExchangeFunction} (returning canned JSON) so we can
 * (a) capture the outgoing {@link ClientRequest} and assert the mandatory
 * {@code x-api-version: 2} header + query params, and (b) assert JSON parsing
 * for FHRS/FHIS/no-match bodies and that a 5xx surfaces (never a silent pass).
 * No WireMock / MockWebServer — the ExchangeFunction stub is sufficient.
 */
class FhrsClientTest {

    private final AtomicReference<ClientRequest> captured = new AtomicReference<>();

    private FhrsClient clientReturning(HttpStatus status, String body) {
        ExchangeFunction stub = request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(status)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .build());
        };
        OnboardingProperties props = new OnboardingProperties();
        props.getFhrs().setBaseUrl("http://fhrs.test");
        props.getFhrs().setApiVersion("2");
        return new FhrsClient(WebClient.builder().exchangeFunction(stub), props);
    }

    @Test
    @DisplayName("sends the mandatory x-api-version:2 header + name/address query params on /Establishments")
    void sendsVersionHeaderAndQueryParams() {
        FhrsClient client = clientReturning(HttpStatus.OK, "{\"establishments\":[]}");

        client.lookup("Mama Put Kitchen", "12 High Street, London");

        ClientRequest req = captured.get();
        assertThat(req.headers().getFirst("x-api-version")).isEqualTo("2");
        assertThat(req.url().getPath()).isEqualTo("/Establishments");
        assertThat(req.url().getQuery()).contains("name=", "address=");
        assertThat(req.url().getQuery()).contains("Mama", "High");
    }

    @Test
    @DisplayName("parses an FHRS 5-rated establishment (id + rating + scheme)")
    void parsesFhrsFive() {
        String body = "{\"establishments\":[{\"FHRSID\":774297,\"RatingValue\":\"5\",\"SchemeType\":\"FHRS\"}]}";
        FhrsClient client = clientReturning(HttpStatus.OK, body);

        List<FhrsEstablishment> result = client.lookup("Shop", "Addr");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).establishmentId()).isEqualTo("774297");
        assertThat(result.get(0).ratingValue()).isEqualTo("5");
        assertThat(result.get(0).schemeType()).isEqualTo("FHRS");
    }

    @Test
    @DisplayName("parses a Scotland FHIS Pass establishment")
    void parsesFhisPass() {
        String body = "{\"establishments\":[{\"FHRSID\":55123,\"RatingValue\":\"Pass\",\"SchemeType\":\"FHIS\"}]}";
        FhrsClient client = clientReturning(HttpStatus.OK, body);

        List<FhrsEstablishment> result = client.lookup("Highland Cafe", "Inverness");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ratingValue()).isEqualTo("Pass");
        assertThat(result.get(0).schemeType()).isEqualTo("FHIS");
    }

    @Test
    @DisplayName("returns an empty list when no establishment matches")
    void emptyOnNoMatch() {
        FhrsClient client = clientReturning(HttpStatus.OK, "{\"establishments\":[]}");

        assertThat(client.lookup("Nothing", "Nowhere")).isEmpty();
    }

    @Test
    @DisplayName("a 5xx surfaces as an exception (never a silent pass) so the gate can degrade to MANUAL_REVIEW")
    void fiveXxSurfaces() {
        FhrsClient client = clientReturning(HttpStatus.INTERNAL_SERVER_ERROR, "upstream boom");

        assertThatThrownBy(() -> client.lookup("Shop", "Addr")).isInstanceOf(RuntimeException.class);
    }
}
