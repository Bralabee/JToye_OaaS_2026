package uk.jtoye.core.onboarding.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import uk.jtoye.core.onboarding.OnboardingProperties;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit proof of {@link CompaniesHouseClient} using an in-memory
 * {@link ExchangeFunction} stub — no external HTTP mock-server library and no
 * new gradle dependency (threat T-18-04-SC). The stub both (a) captures the outgoing
 * {@link ClientRequest} so we can assert the HTTP Basic header decodes to
 * {@code <apiKey>:} (key-as-username, empty password — research §6) and
 * (b) returns canned Companies House bodies (active / dissolved / 404 / 5xx).
 */
class CompaniesHouseClientTest {

    private static final String CH_KEY = "SECRET_CH_KEY_abc123"; // gitleaks:allow — fake fixture key, never a real credential

    /** Build a client whose WebClient exchanges through {@code stub}, capturing the request. */
    private CompaniesHouseClient clientReturning(String apiKey,
                                                 ClientResponse response,
                                                 AtomicReference<ClientRequest> captured) {
        ExchangeFunction stub = request -> {
            if (captured != null) {
                captured.set(request);
            }
            return Mono.just(response);
        };
        OnboardingProperties props = new OnboardingProperties();
        props.getCompaniesHouse().setBaseUrl("https://ch.test.local");
        props.getCompaniesHouse().setApiKey(apiKey);
        return new CompaniesHouseClient(WebClient.builder().exchangeFunction(stub), props);
    }

    private ClientResponse jsonResponse(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    @Test
    @DisplayName("request uses HTTP Basic with the API key as username and empty password, GET /company/{number}")
    void basicAuthKeyAsUsername() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        CompaniesHouseClient client = clientReturning(
                CH_KEY,
                jsonResponse(HttpStatus.OK, "{\"company_number\":\"12345678\",\"company_status\":\"active\"}"),
                captured);

        client.lookup("12345678");

        ClientRequest request = captured.get();
        assertThat(request).as("exchange function was invoked").isNotNull();
        assertThat(request.url().getPath()).isEqualTo("/company/12345678");

        String auth = request.headers().getFirst(HttpHeaders.AUTHORIZATION);
        assertThat(auth).isNotNull().startsWith("Basic ");
        String decoded = new String(
                Base64.getDecoder().decode(auth.substring("Basic ".length())), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo(CH_KEY + ":");
    }

    @Test
    @DisplayName("an active profile parses (lenient — unknown fields ignored)")
    void activeBodyParses() {
        CompaniesHouseClient client = clientReturning(
                CH_KEY,
                jsonResponse(HttpStatus.OK,
                        "{\"company_number\":\"12345678\",\"company_status\":\"active\","
                                + "\"company_name\":\"ACME LTD\",\"type\":\"ltd\"}"),
                null);

        Optional<CompanyProfile> result = client.lookup("12345678");

        assertThat(result).isPresent();
        assertThat(result.get().companyStatus()).isEqualTo("active");
        assertThat(result.get().companyNumber()).isEqualTo("12345678");
    }

    @Test
    @DisplayName("a dissolved profile parses")
    void dissolvedBodyParses() {
        CompaniesHouseClient client = clientReturning(
                CH_KEY,
                jsonResponse(HttpStatus.OK, "{\"company_number\":\"99999999\",\"company_status\":\"dissolved\"}"),
                null);

        Optional<CompanyProfile> result = client.lookup("99999999");

        assertThat(result).isPresent();
        assertThat(result.get().companyStatus()).isEqualTo("dissolved");
    }

    @Test
    @DisplayName("a 404 (no record) yields Optional.empty — never an exception")
    void notFoundYieldsEmpty() {
        CompaniesHouseClient client = clientReturning(
                CH_KEY, ClientResponse.create(HttpStatus.NOT_FOUND).build(), null);

        Optional<CompanyProfile> result = client.lookup("00000000");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("a 5xx propagates a typed failure (the gate maps this to MANUAL_REVIEW)")
    void serverErrorPropagates() {
        CompaniesHouseClient client = clientReturning(
                CH_KEY, ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build(), null);

        assertThatThrownBy(() -> client.lookup("12345678"))
                .isInstanceOf(WebClientResponseException.class);
    }

    @Test
    @DisplayName("an unconfigured API key fails CLOSED — throws without any network call")
    void unconfiguredKeyFailsClosed() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        CompaniesHouseClient client = clientReturning(
                "",
                jsonResponse(HttpStatus.OK, "{\"company_number\":\"12345678\",\"company_status\":\"active\"}"),
                captured);

        assertThatThrownBy(() -> client.lookup("12345678"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(captured.get())
                .as("no request should ever be exchanged when the API key is unconfigured")
                .isNull();
    }

    @Test
    @DisplayName("the real active-company payload shape (J'Toye Digital Ltd, no. 16471464) parses via the lenient projection")
    void realActiveCompanyContractParses() {
        // Recorded contract fixture: a representative Companies House Public Data
        // API `GET /company/{number}` body for the platform operator's own ACTIVE
        // company, verified against the public register on 2026-07-15
        // (find-and-update.company-information.service.gov.uk/company/16471464).
        // The other tests use 2-field bodies; this pins the lenient CompanyProfile
        // projection against a realistic FULL payload, proving the many extra
        // provider fields (name, address, SIC, accounts, confirmation statement)
        // are tolerated (@JsonIgnoreProperties) rather than breaking the
        // BUSINESS_VERIFIED lookup when the upstream shape grows.
        String realBody = """
                {
                  "company_name": "J'TOYE DIGITAL LTD",
                  "company_number": "16471464",
                  "company_status": "active",
                  "type": "ltd",
                  "date_of_creation": "2025-05-23",
                  "jurisdiction": "england-wales",
                  "sic_codes": ["62020"],
                  "registered_office_address": {
                    "address_line_1": "Crispins Manor Farm Lane",
                    "locality": "Michelmersh",
                    "region": "Romsey",
                    "postal_code": "SO51 0NT",
                    "country": "England"
                  },
                  "accounts": {
                    "next_due": "2027-02-23",
                    "accounting_reference_date": { "day": "31", "month": "05" }
                  },
                  "confirmation_statement": {
                    "next_due": "2027-06-05",
                    "next_made_up_to": "2027-05-22"
                  }
                }
                """;
        CompaniesHouseClient client = clientReturning(
                CH_KEY, jsonResponse(HttpStatus.OK, realBody), null);

        Optional<CompanyProfile> result = client.lookup("16471464");

        assertThat(result).isPresent();
        assertThat(result.get().companyNumber()).isEqualTo("16471464");
        assertThat(result.get().companyStatus())
                .as("only \"active\" passes the BUSINESS_VERIFIED gate")
                .isEqualTo("active");
    }
}
