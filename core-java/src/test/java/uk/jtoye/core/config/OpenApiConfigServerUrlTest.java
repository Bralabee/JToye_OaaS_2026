package uk.jtoye.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * INT-24 (QA council 20260902-134741, docs sweep DOC-9).
 *
 * <p>The served spec advertised {@code http://localhost:8080} as "Local Development" while
 * the API listens on 9090, so Swagger UI's "Try it out" targeted a refused port
 * ({@code HTTP 000 connection refused} against the advertised base; 200 against 9090).
 *
 * <p>This assertion exists because <b>no gate would ever have noticed</b>: both OpenAPI
 * snapshot normalisers strip the servers block as environment-dependent
 * ({@code del(.servers)} in {@code scripts/check-openapi-snapshot-fresh.sh:146} and the
 * equivalent {@code obj.remove("servers")} in {@code OpenApiSnapshotTest.normalize}), and
 * the committed snapshot has {@code .servers == null} to prove it.
 *
 * <p>What it pins is the MECHANISM, not a number: the emitted URL must be whatever the
 * configuration says. A re-hardcoded literal fails this whatever the literal is.
 */
class OpenApiConfigServerUrlTest {

    private OpenAPI buildWith(String configuredLocalServerUrl) {
        OpenApiConfig config = new OpenApiConfig();
        ReflectionTestUtils.setField(config, "issuerUri", "http://localhost:18080/realms/jtoye-test");
        ReflectionTestUtils.setField(config, "localServerUrl", configuredLocalServerUrl);
        return config.jtoyeOpenAPI();
    }

    @Test
    void localServerUrlComesFromConfiguration() {
        OpenAPI api = buildWith("http://api.example.test:12345");

        List<Server> servers = api.getServers();
        Server local = servers.stream()
                .filter(s -> "Local Development".equals(s.getDescription()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the Local Development server entry disappeared"));

        assertEquals("http://api.example.test:12345", local.getUrl(),
                "the Local Development server URL must come from configuration, not a literal");
    }

    /**
     * The direction that actually catches a regression: with a sentinel configured, the
     * refused literal must be absent from EVERY server entry. Asserting only "equals the
     * sentinel" on entry 0 would pass if someone re-added the hardcoded URL alongside it.
     */
    @Test
    void noServerAdvertisesTheHardcodedLocalhost8080() {
        OpenAPI api = buildWith("http://localhost:9090");

        boolean anyHardcoded = api.getServers().stream()
                .anyMatch(s -> "http://localhost:8080".equals(s.getUrl()));

        assertFalse(anyHardcoded,
                "http://localhost:8080 is a refused port for this service - the API binds server.port "
                + "(9090). Any server entry must be config-derived: " + api.getServers());
    }

    /**
     * Incremental betterment: the production entry is a good that already existed and must
     * not be displaced by the fix.
     */
    @Test
    void productionServerEntryIsPreserved() {
        OpenAPI api = buildWith("http://localhost:9090");

        assertTrue(api.getServers().stream()
                        .anyMatch(s -> "Production".equals(s.getDescription())),
                "the Production server entry must survive: " + api.getServers());
    }
}
