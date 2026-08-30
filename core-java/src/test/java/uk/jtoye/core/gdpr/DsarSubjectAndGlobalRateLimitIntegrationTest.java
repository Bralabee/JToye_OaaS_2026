package uk.jtoye.core.gdpr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA-council cluster S1 (SEC-1) — {@code DsarIntakeRateLimiter}'s ONLY bucket was keyed on
 * {@link uk.jtoye.core.security.ClientIpResolver#resolveClientIp(jakarta.servlet.http.HttpServletRequest)},
 * which trusts the FIRST, client-controllable {@code X-Forwarded-For} hop. Rotating that header on
 * every request defeats the bucket entirely: each "new" IP gets its own fresh 5/hour allowance, so
 * an attacker can drive an unbounded number of real verification emails at any address it names —
 * an email-bombing amplifier hiding behind a rate limiter.
 *
 * <p>DELIBERATELY SEPARATE from {@code DsarIntakeIntegrationTest}: the global ceiling under test
 * here is a single process-wide bucket, so this class gets its OWN Testcontainers instance and
 * hence its OWN fresh Spring context / fresh {@code DsarIntakeRateLimiter} bean, never perturbed by
 * that file's dozen IP-bucket tests (or vice versa). Both new buckets' sizes are overridden here to
 * small, test-declared numbers via {@code @DynamicPropertySource} — proving the values are read
 * from config, never a hardcoded literal — and kept small so the flood loops stay short.
 *
 * <h2>Why the two tests below are explicitly ordered</h2>
 *
 * The global bucket is, by design, shared across every request in the whole class — that is the
 * property under test in the second method. {@code checkAllowed} gates sequentially with an early
 * exit (IP, then email, then global), so a request already refused by an earlier gate never debits
 * a later one; the per-email test's OWN accepted requests are therefore the only tokens it draws
 * from the shared global bucket, and exactly {@link #EMAIL_LIMIT_PER_DAY} of them. Running it FIRST
 * (deterministically, not by hoping for JUnit's default order) makes the global test's own
 * expected-accepted count computable rather than a race against whichever method the runner
 * happens to try first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DsarSubjectAndGlobalRateLimitIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    private static final int EMAIL_LIMIT_PER_DAY = 3;
    private static final int GLOBAL_LIMIT_PER_HOUR = 20;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("jtoye.gdpr.dsar.rate-limit.requests-per-email-per-day",
                () -> String.valueOf(EMAIL_LIMIT_PER_DAY));
        registry.add("jtoye.gdpr.dsar.rate-limit.global-requests-per-hour",
                () -> String.valueOf(GLOBAL_LIMIT_PER_HOUR));
        // The IP bucket's own default (5/hour) is not the axis under test in either method below —
        // each request uses a UNIQUE synthetic ip specifically to take that axis out of contention.
    }

    private static final String INTAKE_PATH = "/api/v1/public/gdpr/dsar";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // SEC-1: same email, rotating XFF — the (defeated) IP bucket must not be the only defence
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void sameEmailOverRotatingClientIpsIsStillBoundedByThePerEmailBucket() throws Exception {
        String targetEmail = "victim-xff-rotation@example.com";
        int accepted = 0;
        int throttled = 0;

        // Each request carries a DIFFERENT synthetic client IP (defeating the IP bucket) but the
        // SAME target email — exactly the amplification vector: an attacker rotates XFF, the IP
        // bucket sees a "new" client every time, and only a per-email axis can still say no.
        for (int i = 0; i < EMAIL_LIMIT_PER_DAY + 5; i++) {
            int status = lodge(targetEmail, "203.0.113." + (i + 1)).andReturn().getResponse().getStatus();
            if (status == 202) {
                accepted++;
            } else if (status == 429) {
                throttled++;
            } else {
                throw new AssertionError("unexpected status " + status + " on attempt " + i);
            }
        }

        assertThat(accepted)
                .as("the per-email bucket, not the (defeated) per-IP bucket, must be the ceiling")
                .isEqualTo(EMAIL_LIMIT_PER_DAY);
        assertThat(throttled)
                .as("every request past the per-email allowance must be refused, even from a "
                        + "brand-new IP each time")
                .isEqualTo(5);
    }

    // ------------------------------------------------------------------
    // SEC-1: BOTH axes rotate — only the global ceiling can still bound the flood
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    void rotatingBothIpAndEmailIsStillBoundedByTheGlobalCeiling() throws Exception {
        // Runs SECOND (@Order): the per-email test above already drew exactly EMAIL_LIMIT_PER_DAY
        // tokens from this same process-wide global bucket (its accepted requests, and only those —
        // see the class Javadoc on the early-exit gate order), so that many fewer are available here.
        int expectedAccepted = GLOBAL_LIMIT_PER_HOUR - EMAIL_LIMIT_PER_DAY;
        int accepted = 0;
        int throttled = 0;

        // A DIFFERENT email AND a DIFFERENT ip on every request defeats both the IP bucket and the
        // per-email bucket simultaneously — the attack the plan calls out explicitly ("an attacker
        // rotating BOTH XFF and email still gets a fresh per-address bucket"). Only a single,
        // un-keyed, process-wide ceiling can still refuse.
        for (int i = 0; i < expectedAccepted + 5; i++) {
            int status = lodge("global-flood-" + i + "@example.com", "198.51.100." + (i + 1))
                    .andReturn().getResponse().getStatus();
            if (status == 202) {
                accepted++;
            } else if (status == 429) {
                throttled++;
            } else {
                throw new AssertionError("unexpected status " + status + " on attempt " + i);
            }
        }

        assertThat(accepted)
                .as("NON-VACUITY CONTROL: some requests must succeed, or a global 429 would be "
                        + "equally consistent with a broken endpoint")
                .isEqualTo(expectedAccepted);
        assertThat(throttled)
                .as("with both the IP and the email axes defeated, the aggregate ceiling must still "
                        + "bound the flood")
                .isEqualTo(5);
    }

    // ---- helpers -------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions lodge(String email, String clientIp)
            throws Exception {
        return mockMvc.perform(post(INTAKE_PATH)
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("email", email, "requestType", "ERASURE"))));
    }
}
