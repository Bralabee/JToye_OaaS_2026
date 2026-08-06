package uk.jtoye.core.notification.dispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #516 — closes the gap between the two components that were each green
 * while the feature was broken: the builder that COMPOSES the unsubscribe URL
 * and the controller that SERVES it.
 *
 * <p>{@code NotificationDispatchServiceTest} asserted the composed string and
 * {@code PublicUnsubscribeControllerIntegrationTest} called the controller at a
 * path it typed out by hand. Neither ever fed one to the other. Here the URL is
 * composed by the real dispatch path under a STAGING-SHAPED config (app origin
 * ≠ API origin — the arrangement in which the defect bit), and then the path and
 * query taken FROM THAT URL are replayed as a real request through the whole
 * security filter chain against real Postgres with RLS live, and the opt-out row
 * is counted.
 *
 * <p>The complementary half — that the app origin's host is a different Service
 * which does not serve the API path — is
 * {@link UnsubscribeLinkRoutingTest} (ingress + frontend tree), and was measured
 * against the running stack: {@code GET http://localhost:3000/api/v1/public/unsubscribe}
 * returned 404 from Next.js while {@code /unsubscribe} returned 200.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class UnsubscribeLinkReachesControllerIntegrationTest {

    private static final String APP_ORIGIN = "https://app-staging.olajay.co.uk";
    private static final String API_ORIGIN = "https://api-staging.olajay.co.uk";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // The app must verify a token minted by the fixture, so both sides share one secret.
        registry.add("notification.unsubscribe.signing-secret", () -> UnsubscribeLinkFixture.SECRET);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seedTenant() {
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                UnsubscribeLinkFixture.TENANT, "test-516-" + UUID.randomUUID());
        jdbc.update("DELETE FROM notification_suppression WHERE tenant_id = ?", UnsubscribeLinkFixture.TENANT);
    }

    private int suppressionCount() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM notification_suppression WHERE tenant_id = ? AND recipient = ?",
                Integer.class, UnsubscribeLinkFixture.TENANT, UnsubscribeLinkFixture.VENDOR_EMAIL);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("#516 — the RFC 8058 one-click URL the dispatcher composes reaches this controller and records the opt-out")
    void composedOneClickUrl_reachesTheController_andWritesTheOptOut() throws Exception {
        URI url = URI.create(UnsubscribeLinkFixture.oneClickUrl(APP_ORIGIN, API_ORIGIN));

        assertThat(url.getHost())
                .as("the one-click target must be advertised on the API origin, which the ingress routes to core-java")
                .isEqualTo(URI.create(API_ORIGIN).getHost());

        // Replay EXACTLY what a mail provider does with that URL: RFC 8058 §3.1
        // fixes the body, so the identifying fields can only be in the URI.
        // The URI overload is deliberate — the String overload is a URI TEMPLATE
        // and re-encodes, which turns the already-encoded %2B in the address into
        // %252B and makes a correct token look forged.
        mockMvc.perform(post(relative(url))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("List-Unsubscribe=One-Click"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unsubscribed"));

        assertThat(suppressionCount())
                .as("the composed one-click URL must actually opt the recipient out")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("#516 — the clickable page URL's path is NOT served by the API (it belongs to the frontend Service)")
    void composedPageUrl_isNotAnApiPath() throws Exception {
        URI url = URI.create(UnsubscribeLinkFixture.pageUrl(APP_ORIGIN, API_ORIGIN));

        assertThat(url.getHost())
                .as("the clickable link must stay on the app origin, which serves the branded page")
                .isEqualTo(URI.create(APP_ORIGIN).getHost());

        // The mirror image of the defect: just as the frontend does not serve the
        // API's path, core-java does not serve the frontend's. This is why one
        // origin cannot carry both, and why the two URLs are configured separately.
        MvcResult result = mockMvc.perform(get(relative(url))).andReturn();
        int status = result.getResponse().getStatus();
        assertThat(status < 200 || status >= 300)
                .as("core-java must NOT answer the frontend's page path %s (status was %d)", url.getPath(), status)
                .isTrue();
    }

    /** Path + query of the composed URL, byte-for-byte, as the origin's server would receive it. */
    private static URI relative(URI absolute) {
        return URI.create(absolute.getRawPath() + "?" + absolute.getRawQuery());
    }

    @Test
    @DisplayName("#516 — the two URLs carry the SAME signed identity, so either route opts the same recipient out")
    void bothUrlsCarryTheSameSignedIdentity() {
        String page = UnsubscribeLinkFixture.pageUrl(APP_ORIGIN, API_ORIGIN);
        String oneClick = UnsubscribeLinkFixture.oneClickUrl(APP_ORIGIN, API_ORIGIN);

        assertThat(URI.create(page).getRawQuery()).isEqualTo(URI.create(oneClick).getRawQuery());
    }
}
