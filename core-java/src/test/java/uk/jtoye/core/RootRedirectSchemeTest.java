package uk.jtoye.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SW-CLR-10 (swarm epic #964): behind the TLS-terminating ingress, {@code GET /} sent the browser
 * to {@code http://…/swagger-ui.html}, a scheme downgrade on the API's front door.
 *
 * <p><b>Why a real Tomcat.</b> The downgrade is made by the container, not by the controller:
 * {@code RedirectView} calls {@code sendRedirect("/swagger-ui.html")}, and Tomcat (with Boot's
 * {@code server.tomcat.use-relative-redirects=false} default) turns that into an absolute URL from
 * its OWN connector scheme, which is {@code http} behind the ingress. The
 * {@code TrustedProxyForwardedProtoFilter} wrapper that restores {@code https} for the application
 * is invisible to that step. A {@code MockMvc} test keeps the relative URL as given and would pass on
 * the broken code, so this boots the server on a random port and reads the raw {@code Location}.
 *
 * <p>The loopback peer is declared a trusted proxy here, as the ingress pod range is on staging, so
 * the request is exactly the staging shape: a trusted peer saying {@code X-Forwarded-Proto: https}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jtoye.security.trusted-proxies=127.0.0.1/32",
        "management.server.port=0"
})
@ActiveProfiles("test")
class RootRedirectSchemeTest {

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Forwarded-Proto", "https")
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("SW-CLR-10: the API root redirects to the docs without naming http behind a TLS proxy")
    void rootRedirectKeepsTheClientsScheme() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).as("the root is a redirect").isBetween(301, 308);
        String location = response.headers().firstValue("Location").orElse("");
        assertThat(location).as("Location must be present").isNotBlank();
        assertThat(location)
                .as("a relative Location keeps the client's https; an absolute one must be https, never http")
                .satisfiesAnyOf(
                        l -> assertThat(l).startsWith("/swagger-ui.html"),
                        l -> assertThat(l).startsWith("https://"));
        assertThat(location).doesNotStartWith("http://");
    }

    /**
     * The second hop of the same journey. springdoc answers with a relative Location of its own
     * ({@code /swagger-ui/index.html}, measured), so this was already safe before SW-CLR-10 and is
     * kept as a pin on that dependency behaviour: a springdoc upgrade that absolutised it would
     * re-open the downgrade one hop later.
     */
    @Test
    @DisplayName("SW-CLR-10: the docs page's own redirect (springdoc) does not downgrade either")
    void docsPageRedirectKeepsTheClientsScheme() throws Exception {
        HttpResponse<String> response = get("/swagger-ui.html");

        String location = response.headers().firstValue("Location").orElse("");
        assertThat(response.statusCode()).as("springdoc answers /swagger-ui.html with a redirect, Location=%s", location)
                .isBetween(301, 308);
        assertThat(location).doesNotStartWith("http://");
    }
}
