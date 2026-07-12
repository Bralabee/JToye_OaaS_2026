package uk.jtoye.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #98 [P2-7] item 4: proves the management-port split that hardens the prod
 * metrics surface.
 *
 * <ul>
 *   <li>/actuator/prometheus is served (200, Prometheus exposition text) on the
 *       separate internal management port, reachable WITHOUT auth so the
 *       cluster-internal Prometheus scrape + kubelet probes work.</li>
 *   <li>/actuator/prometheus is NOT served on the public app port — with a
 *       separate management port Spring Boot binds the actuator endpoints to that
 *       port only, so the app port exposes no metrics surface (T-t6b-02).</li>
 * </ul>
 *
 * The app-port JWT posture is unchanged (anyRequest().authenticated() in
 * {@link SecurityConfig} is untouched; only the /actuator/prometheus permitAll was
 * made unconditional, and in prod that endpoint only exists on the management port).
 *
 * <p>Runs untagged (H2, no Testcontainers) so it executes under the fast {@code test}
 * task. Uses a random app port + random management port (management.server.port=0)
 * to avoid colliding with the cohabiting live stack's fixed 9090/9091.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "management.endpoints.web.exposure.include=health,info,prometheus",
        "management.endpoint.health.probes.enabled=true"
})
@ActiveProfiles("test")
class ManagementPortMetricsIntegrationTest {

    @LocalServerPort
    private int appPort;

    @Value("${local.management.port}")
    private int managementPort;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("/actuator/prometheus is served (200, exposition text) on the management port")
    void prometheus_servedOnManagementPort() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "management port must serve /actuator/prometheus without auth (scrape + probe surface)");
        String body = resp.getBody();
        assertTrue(body != null && (body.contains("jvm_") || body.contains("# HELP")),
                "management-port /actuator/prometheus must return Prometheus exposition text");
    }

    @Test
    @DisplayName("/actuator/prometheus is NOT served on the public app port")
    void prometheus_notServedOnAppPort() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "http://localhost:" + appPort + "/actuator/prometheus", String.class);

        assertNotEquals(HttpStatus.OK, resp.getStatusCode(),
                "public app port must NOT expose /actuator/prometheus (served only on the management port)");
    }
}
