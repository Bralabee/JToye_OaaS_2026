package uk.jtoye.core.gdpr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.gdpr.dto.DsarIntakeRequest;
import uk.jtoye.core.security.access.SystemPrincipal;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LGL-01 / D-16 / D-17 over real Postgres 15: a data subject can lodge a request from the public
 * internet, and the endpoint does not become a cross-tenant enumeration oracle in the process.
 *
 * <h2>The property that shapes every assertion here</h2>
 *
 * "Which of your vendors holds this person's email address" is exactly the information the tenant
 * wall exists to withhold, and an intake that answers it — by status code, by body, or by an
 * identifier sourced from a matched row — hands it to anybody with a browser. So the acceptance
 * test is not "both cases return 202": it is that the two responses are <strong>byte-identical</strong>.
 * A status-only assertion passes happily while the body leaks the answer, which is why
 * {@link #theResponseIsByteIdenticalWhetherOrNotATenantHoldsTheAddress()} compares the raw bytes
 * and the deliberate 404-on-no-match break arm was run against it.
 *
 * <h2>Non-vacuity</h2>
 *
 * "The match case looks like the no-match case" is also satisfied by a test where the match was
 * never seeded — two no-match cases agree trivially. {@link #seedMatchingCustomer} therefore
 * inserts the customer and the test asserts it is really there before comparing, so the agreement
 * is evidence about the endpoint rather than about the fixture.
 *
 * <h2>Per-test client IP</h2>
 *
 * The intake carries its own IP-keyed bucket, and it is a singleton for the whole context. Every
 * test therefore declares its own {@code X-Forwarded-For} value: without that the rate-limit test
 * would exhaust the bucket every other test shares, and the resulting failures would look like
 * product defects. It doubles as proof that the keying is real — independent IPs do not interfere.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class DsarIntakeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    /** The canonical public route. 31-11's privacy notice and 31-13's statement link to this. */
    private static final String INTAKE_PATH = "/api/v1/public/gdpr/dsar";

    /** An admin-only endpoint that predates this plan and must stay admin-only. */
    private static final String ADMIN_EXPORT_PATH = "/api/v1/gdpr/customers/{id}/export";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DsarRequestRepository dsarRequestRepository;

    /**
     * Spied so the intake's own execution can be observed from INSIDE the request, which is the
     * only way to catch a declaration that is restored on exit: {@code SystemPrincipal.asSystem}
     * puts the prior value back in a {@code finally}, so after the call the thread looks identical
     * whether or not it declared system authority mid-flight.
     */
    @SpyBean private DsarIntakeService dsarIntakeService;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM dsar_request");
    }

    // ---- Opacity: the endpoint must not answer "does anyone hold this address?" ---------------

    @Test
    void aLodgedRequestForAnAddressNoTenantHoldsIsAccepted() throws Exception {
        MvcResult result = lodge("nobody-here@example.com", "ERASURE", "198.51.100.10", null)
                .andExpect(status().isAccepted())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isNotBlank();
        assertThat(dsarRequestRepository.count()).isEqualTo(1);
    }

    @Test
    void theResponseIsByteIdenticalWhetherOrNotATenantHoldsTheAddress() throws Exception {
        String heldAddress = "held-subject@example.com";
        seedMatchingCustomer(heldAddress);

        // NON-VACUITY CONTROL: the match really exists, so the agreement below is about the
        // endpoint and not about a fixture that quietly did nothing.
        Integer held = jdbc.queryForObject(
                "SELECT count(*) FROM customers WHERE email = ?", Integer.class, heldAddress);
        assertThat(held)
                .as("the 'match' arm must actually have a matching customer, or both arms are "
                        + "no-match arms and their agreement proves nothing")
                .isEqualTo(1);

        byte[] noMatch = lodge("no-tenant-holds-this@example.com", "ERASURE", "198.51.100.11", null)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsByteArray();

        byte[] match = lodge(heldAddress, "ERASURE", "198.51.100.12", null)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(match)
                .as("the intake must not reveal whether ANY tenant holds the address — not by "
                        + "status, not by any field, and not by an id derived from a matched row. "
                        + "Anything that differs here is a cross-tenant enumeration oracle. "
                        + "no-match=%s match=%s",
                        new String(noMatch, StandardCharsets.UTF_8),
                        new String(match, StandardCharsets.UTF_8))
                .isEqualTo(noMatch);
    }

    // ---- Only a digest is stored --------------------------------------------------------------

    @Test
    void onlyTheDigestOfTheAddressIsPersisted() throws Exception {
        String address = "  MiXeD.Case@Example.COM  ";
        String expectedDigest = sha256Hex(address.trim().toLowerCase());

        lodge(address, "ACCESS", "198.51.100.13", null).andExpect(status().isAccepted());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM dsar_request");

        assertThat(row.get("subject_email_sha256"))
                .as("the digest is taken over the LOWER-CASED, TRIMMED address — plan 31-09's "
                        + "worker recomputes it over customer rows, so both sides must normalise "
                        + "identically or the fan-out silently matches nothing")
                .isEqualTo(expectedDigest);

        String readable = address.trim();
        for (Map.Entry<String, Object> column : row.entrySet()) {
            String value = column.getValue() == null ? "" : column.getValue().toString();
            assertThat(value.toLowerCase())
                    .as("column dsar_request.%s holds the readable address. A privacy feature must "
                            + "not create a new personal-data store — V42's rule is that only the "
                            + "one-way digest is ever persisted.", column.getKey())
                    .doesNotContain(readable.toLowerCase());
        }
    }

    @Test
    void theTableCarriesNoReadableAddressColumn() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'dsar_request'",
                String.class);

        assertThat(columns)
                .as("NON-VACUITY CONTROL: no columns found for public.dsar_request, so the "
                        + "'no readable-address column' result below would be evidence about this "
                        + "query rather than about the schema")
                .isNotEmpty();

        assertThat(columns)
                .as("dsar_request must carry no readable-address column. Found: %s", columns)
                .doesNotContain("email", "subject_email", "subject_email_address", "address");
    }

    // ---- Typed, non-reflecting errors ---------------------------------------------------------

    @Test
    void aMalformedAddressIsRejectedWithATypedProblemThatReflectsNothing() throws Exception {
        String malformed = "not-an-address-at-all";

        MvcResult result = lodge(malformed, "ERASURE", "198.51.100.14", null)
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("a stable RFC 7807 type lets a machine client branch without parsing prose")
                .contains("https://jtoye.uk/errors/validation");
        assertThat(body)
                .as("the error must not echo the submitted value back — a reflected address is a "
                        + "disclosure in logs, in proxies and in the caller's own console")
                .doesNotContain(malformed);
        assertThat(dsarRequestRepository.count())
                .as("a rejected request must not queue a row")
                .isZero();
    }

    @Test
    void aMissingBodyIsFourHundredNotFiveHundred() throws Exception {
        MvcResult result = mockMvc.perform(post(INTAKE_PATH)
                        .header("X-Forwarded-For", "198.51.100.15")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .as("an absent body is a client request-shape error, not a server fault")
                .contains("https://jtoye.uk/errors/");
    }

    // ---- Idempotency --------------------------------------------------------------------------

    @Test
    void aRepeatedIdempotencyKeyReplaysTheOriginalResponseAndQueuesOneRow() throws Exception {
        String key = "dsar-key-" + UUID.randomUUID();

        byte[] first = lodge("repeat@example.com", "ERASURE", "198.51.100.16", key)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsByteArray();

        byte[] replay = lodge("repeat@example.com", "ERASURE", "198.51.100.16", key)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(replay)
                .as("a retried POST must replay the original response verbatim")
                .isEqualTo(first);
        assertThat(dsarRequestRepository.count())
                .as("a retry must never queue a SECOND erasure — that is the whole point of the "
                        + "Idempotency-Key contract on a destructive operation")
                .isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentPayloadIsRejectedRatherThanQueueingASecondRow() throws Exception {
        String key = "dsar-key-" + UUID.randomUUID();

        lodge("first-subject@example.com", "ERASURE", "198.51.100.17", key)
                .andExpect(status().isAccepted());

        MvcResult mismatch = lodge("different-subject@example.com", "ERASURE", "198.51.100.17", key)
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        assertThat(mismatch.getResponse().getContentAsString())
                .contains("https://jtoye.uk/errors/idempotency-payload-mismatch");
        assertThat(dsarRequestRepository.count())
                .as("a key reused with a different payload must not silently queue a second row — "
                        + "the constraint is on the KEY alone precisely so this case collides")
                .isEqualTo(1);
    }

    // ---- Rate limiting ------------------------------------------------------------------------

    @Test
    void exceedingThePerIpLimitReturns429AndQueuesNoFurtherRows() throws Exception {
        String ip = "198.51.100.18";
        int accepted = 0;
        int throttled = 0;

        for (int i = 0; i < 12; i++) {
            int statusCode = lodge("flood-" + i + "@example.com", "ERASURE", ip, null)
                    .andReturn().getResponse().getStatus();
            if (statusCode == 202) {
                accepted++;
            } else if (statusCode == 429) {
                throttled++;
            } else {
                throw new AssertionError("unexpected status " + statusCode + " on attempt " + i);
            }
        }

        assertThat(accepted)
                .as("NON-VACUITY CONTROL: some requests must have been ACCEPTED, or a 429 count "
                        + "would be equally consistent with a broken endpoint that refuses "
                        + "everything")
                .isGreaterThan(0);
        assertThat(throttled)
                .as("an UNVERIFIED erasure request is a destructive action anybody can aim at "
                        + "anybody, so this endpoint carries its own IP-keyed bucket, deliberately "
                        + "far tighter than the 100/min platform default. Accepted=%d throttled=%d",
                        accepted, throttled)
                .isGreaterThan(0);
        assertThat(dsarRequestRepository.count())
                .as("a throttled request must not queue a row")
                .isEqualTo(accepted);
    }

    @Test
    void aDifferentClientIpHasItsOwnBucket() throws Exception {
        String floodedIp = "198.51.100.19";
        for (int i = 0; i < 12; i++) {
            lodge("flood2-" + i + "@example.com", "ERASURE", floodedIp, null);
        }

        // CONTROL: the flooded IP really is throttled, so the fresh IP's success below means the
        // bucket is keyed by client rather than being globally open.
        lodge("still-blocked@example.com", "ERASURE", floodedIp, null)
                .andExpect(status().isTooManyRequests());

        lodge("fresh-client@example.com", "ERASURE", "198.51.100.20", null)
                .andExpect(status().isAccepted());
    }

    // ---- The D-17 boundary: intake is a request, execution is background ----------------------

    @Test
    void theIntakeRequestThreadNeverDeclaresSystemAuthority() throws Exception {
        AtomicReference<Boolean> declaredDuringCall = new AtomicReference<>();
        doAnswer(invocation -> {
            declaredDuringCall.set(SystemPrincipal.isSystem());
            return invocation.callRealMethod();
        }).when(dsarIntakeService).lodge(any(DsarIntakeRequest.class), any());

        lodge("boundary@example.com", "ERASURE", "198.51.100.21", null)
                .andExpect(status().isAccepted());

        assertThat(declaredDuringCall.get())
                .as("the observation must have happened — a null here means the intake never "
                        + "reached the service and the assertion below would be vacuous")
                .isNotNull();
        assertThat(declaredDuringCall.get())
                .as("ShopAccessService records the rule D-17 depends on: a REQUEST thread never "
                        + "enters SystemPrincipal.asSystem — only background entry points do. That "
                        + "is what reconciles one cross-tenant DSAR desk with a project that has "
                        + "twice refused a cross-tenant operator identity: no human ever holds "
                        + "that reach, only plan 31-09's scheduled worker does.")
                .isFalse();
        assertThat(SystemPrincipal.isSystem())
                .as("and nothing was left on the thread for whatever runs next")
                .isFalse();
    }

    // ---- The pre-existing admin surface is unweakened ------------------------------------------

    @Test
    void theAdminOnlyExportEndpointStillRefusesAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(get(ADMIN_EXPORT_PATH, UUID.randomUUID()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void theNewIntakeIsReachableWithoutACredential() throws Exception {
        // The complement of the assertion above, and the reason it is not vacuous: this endpoint
        // IS anonymous, so "4xx on the admin path" is not simply "everything is locked".
        lodge("anonymous-reachable@example.com", "ACCESS", "198.51.100.22", null)
                .andExpect(status().isAccepted());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions lodge(
            String email, String requestType, String clientIp, String idempotencyKey) throws Exception {
        var builder = post(INTAKE_PATH)
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("email", email, "requestType", requestType)));
        if (idempotencyKey != null) {
            builder = builder.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(builder);
    }

    private void seedMatchingCustomer(String email) {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "DSAR Intake Tenant " + tenant);
        jdbc.update("INSERT INTO customers (id, tenant_id, name, email) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), tenant, "Held Subject", email);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
