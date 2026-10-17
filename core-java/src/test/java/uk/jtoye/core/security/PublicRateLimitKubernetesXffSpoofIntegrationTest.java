package uk.jtoye.core.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import uk.jtoye.core.config.DatabaseConfigurationValidator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-37-59: the UXT-006 rotation attack, replayed THROUGH Tomcat on a Kubernetes-booted context.
 *
 * <p>{@code PublicRateLimitXffSpoofIntegrationTest} proves the resolver and the limiter with a
 * {@code MockHttpServletRequest} handed straight to {@code preHandle} — which never passes through
 * the servlet container, so it cannot see a container valve rewriting the peer first. On every k8s
 * overlay Boot 4.1 deduced {@code server.forward-headers-strategy} ON from the cloud platform and
 * installed Tomcat's {@code RemoteIpValve}, which believes {@code X-Forwarded-For} from any
 * private or loopback peer; each rotated header then became a different {@code getRemoteAddr()}
 * and minted a fresh public bucket. This test sends real HTTP from loopback to a real public
 * route, with the platform forced to {@code kubernetes}, and asserts the same contract as the
 * compose test: exactly the configured capacity is allowed, and the first 429 lands at
 * capacity+1. A flood that never sees a 429 fails it, so a limiter that never fires cannot pass.
 *
 * <p>Profile {@code dev}, real Redis and Postgres, the established pattern of the compose test (the
 * {@code test} profile switches the limiter off).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("dev")
@TestPropertySource(properties = {"storage.blob.validate-on-startup=false",
        "jtoye.gdpr.dsar.encryption-key=${random.value}${random.value}",
        "jtoye.storefront.client-ip-hmac-key=${random.value}${random.value}",
        // The trigger: Boot's documented override of the KUBERNETES_SERVICE_HOST/PORT detection.
        "spring.main.cloud-platform=kubernetes",
        "management.server.port=0"})
@Tag("testcontainers")
class PublicRateLimitKubernetesXffSpoofIntegrationTest {

    private static final int PUBLIC_REQUESTS_PER_MINUTE = 5;
    private static final int PUBLIC_BURST = 2;
    private static final int PUBLIC_CAPACITY = PUBLIC_REQUESTS_PER_MINUTE + PUBLIC_BURST;
    private static final int FLOOD = 40;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8.10-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
        registry.add("spring.data.redis.timeout", () -> "1500ms");

        registry.add("rate-limiting.enabled", () -> "true");
        registry.add("rate-limiting.public.requests-per-minute", () -> String.valueOf(PUBLIC_REQUESTS_PER_MINUTE));
        registry.add("rate-limiting.public.burst", () -> String.valueOf(PUBLIC_BURST));
        registry.add("rate-limiting.public.window-seconds", () -> "60");

        // jtoye.security.trusted-proxies deliberately NOT set: trust none is the posture under test.

        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @MockitoBean
    private DatabaseConfigurationValidator databaseConfigurationValidator;

    @Test
    void rotatingXffThroughTomcatOnKubernetesSharesOneBucket_andIs429OnceTheBurstIsSpent() throws Exception {
        assertThat(CloudPlatform.getActive(environment))
                .as("precondition: the context must run as KUBERNETES, the platform that deduced the valve ON")
                .isEqualTo(CloudPlatform.KUBERNETES);

        HttpClient client = HttpClient.newHttpClient();
        List<Integer> statuses = new ArrayList<>(FLOOD);
        for (int i = 1; i <= FLOOD; i++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/public/shops"))
                    .header("X-Forwarded-For", (i % 2 == 0 ? "192.0.2." : "198.51.100.") + i)
                    .GET()
                    .build();
            statuses.add(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        }

        long allowed = statuses.stream().filter(s -> s == 200).count();
        long throttled = statuses.stream().filter(s -> s == 429).count();

        assertThat(statuses)
                .as("every response is a 200 or a 429, never another status: %s", statuses)
                .allMatch(s -> s == 200 || s == 429);
        assertThat(allowed)
                .as("exactly the configured capacity (%d) is allowed from one loopback peer through Tomcat, "
                        + "however X-Forwarded-For is rotated: %s", PUBLIC_CAPACITY, statuses)
                .isEqualTo(PUBLIC_CAPACITY);
        assertThat(throttled)
                .as("every request past the capacity is refused: %s", statuses)
                .isEqualTo(FLOOD - PUBLIC_CAPACITY);
        assertThat(statuses.indexOf(429))
                .as("the first 429 lands at 0-based index %d", PUBLIC_CAPACITY)
                .isEqualTo(PUBLIC_CAPACITY);
    }
}
