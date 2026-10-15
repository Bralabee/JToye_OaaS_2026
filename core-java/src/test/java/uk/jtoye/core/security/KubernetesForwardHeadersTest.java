package uk.jtoye.core.security;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-37-59 (phase 37 security audit, plan 37-24 follow-up): on Kubernetes the socket peer is still
 * the client unless {@link ClientIpResolver}'s own trusted-proxy list says otherwise.
 *
 * <p><b>The hole this pins.</b> 37-24 left {@code server.forward-headers-strategy} UNSET in the
 * belief that unset means "Tomcat's RemoteIpValve stays off". In Spring Boot 4.1 it does not:
 * {@code TomcatWebServerFactoryCustomizer.getOrDeduceUseForwardHeaders()} answers
 * {@code CloudPlatform.getActive(env).isUsingForwardHeaders()} when the strategy is null, and
 * {@link CloudPlatform#KUBERNETES} — detected from the {@code KUBERNETES_SERVICE_HOST/PORT}
 * variables every pod carries — answers true. The valve then trusts every private and loopback
 * range by default and rewrites {@code getRemoteAddr()} (and the scheme, and the host) from
 * {@code X-Forwarded-*} BEFORE the resolver runs, so the resolver's trust-none default decides
 * nothing on any k8s overlay. Compose has no cloud platform, which is why every compose test,
 * including {@code PublicRateLimitXffSpoofIntegrationTest} (a {@code MockHttpServletRequest}
 * that never passes through Tomcat at all), stayed green over it.
 *
 * <p><b>How this test reaches it.</b> A real embedded Tomcat on a random port, booted with
 * {@code spring.main.cloud-platform=kubernetes} (the documented override of the env-var
 * detection), and real HTTP from loopback — a peer inside the valve's default internal-proxy
 * list and outside the resolver's (empty) trusted list. Untagged and on the {@code test}
 * profile (H2), so it runs in the fast {@code test} task.
 *
 * <p><b>Falsifiability.</b> {@link #theContextReallyRunsAsKubernetes()} is the precondition arm:
 * without it a context that silently fell back to "no platform" would pass the other two for the
 * wrong reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.cloud-platform=kubernetes",
        "management.server.port=0"
})
@ActiveProfiles("test")
class KubernetesForwardHeadersTest {

    static final String PEER_PROBE_PATH = "/public/__t3759/peer";

    @LocalServerPort
    private int port;

    @Autowired
    private WebServerApplicationContext webServerContext;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("precondition: the context boots as Kubernetes, the platform that deduces forward headers ON")
    void theContextReallyRunsAsKubernetes() {
        assertThat(CloudPlatform.getActive(environment))
                .as("spring.main.cloud-platform=kubernetes must make Boot see KUBERNETES")
                .isEqualTo(CloudPlatform.KUBERNETES);
        assertThat(CloudPlatform.KUBERNETES.isUsingForwardHeaders())
                .as("Boot's KUBERNETES platform deduces forward headers ON when the strategy is unset")
                .isTrue();
    }

    @Test
    @DisplayName("T-37-59: the Tomcat engine pipeline carries no RemoteIpValve under Kubernetes")
    void noRemoteIpValveInTheTomcatPipelineUnderKubernetes() {
        assertThat(webServerContext.getWebServer()).isInstanceOf(TomcatWebServer.class);
        TomcatWebServer tomcat = (TomcatWebServer) webServerContext.getWebServer();
        Valve[] valves = tomcat.getTomcat().getEngine().getPipeline().getValves();
        assertThat(Arrays.stream(valves).map(v -> v.getClass().getName()).toList())
                .as("a RemoteIpValve would rewrite getRemoteAddr() from X-Forwarded-For with its own "
                        + "trust list (every private range) before ClientIpResolver runs")
                .noneMatch(name -> name.equals(RemoteIpValve.class.getName()));
    }

    @Test
    @DisplayName("T-37-59: rotating X-Forwarded-* from a loopback peer leaves the socket peer, scheme and host intact")
    void rotatingForwardedHeadersFromALoopbackPeerAreInert() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        List<String> bodies = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PEER_PROBE_PATH))
                    // An attacker-chosen, public, different client address on every request.
                    .header("X-Forwarded-For", (i % 2 == 0 ? "192.0.2." : "198.51.100.") + i)
                    .header("X-Forwarded-Proto", "https")
                    .header("X-Forwarded-Host", "evil.example")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("probe status, body=%s", response.body()).isEqualTo(200);
            bodies.add(response.body());
        }
        assertThat(bodies)
                .as("every request must be attributed to the socket peer (127.0.0.1), on plain http, to "
                        + "the host it connected to: the resolver trusts no proxy by default, so no "
                        + "X-Forwarded-* header may decide any of these")
                .allMatch(body -> body.equals("remote=127.0.0.1|resolved=127.0.0.1|scheme=http|secure=false|host=127.0.0.1"));
    }

    /** Echoes what the application sees of the request, after every container valve and filter. */
    @TestConfiguration
    static class PeerProbeConfig {
        @Bean
        PeerProbeController t3759PeerProbeController(ClientIpResolver resolver) {
            return new PeerProbeController(resolver);
        }
    }

    @RestController
    static class PeerProbeController {
        private final ClientIpResolver resolver;

        PeerProbeController(ClientIpResolver resolver) {
            this.resolver = resolver;
        }

        @GetMapping(PEER_PROBE_PATH)
        String peer(HttpServletRequest request) {
            return "remote=" + request.getRemoteAddr()
                    + "|resolved=" + resolver.resolve(request)
                    + "|scheme=" + request.getScheme()
                    + "|secure=" + request.isSecure()
                    + "|host=" + request.getServerName();
        }
    }
}
