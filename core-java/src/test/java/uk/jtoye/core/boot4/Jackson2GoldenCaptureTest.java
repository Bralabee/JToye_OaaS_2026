package uk.jtoye.core.boot4;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import uk.jtoye.core.common.idempotency.IdempotencyService;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ONE-SHOT generator of the {@code jackson2-golden} fixtures (Phase 38, plan 38-01). It runs on
 * the Boot 3.5.16 / Jackson 2 tree only, and is deleted once its output is committed: the
 * README names the commit that ran it, and re-running it on a Boot-4 tree would re-capture the
 * fixtures under Jackson 3 and defeat their purpose.
 *
 * <p>Gated by {@code JTOYE_GOLDEN_CAPTURE=true} so a normal test run never writes. Untagged, on
 * the H2 {@code test} profile, which sets no {@code spring.jackson.*} key, so the injected
 * {@link ObjectMapper} is the same auto-configured bean production injects.
 *
 * <p><b>Production serializers only.</b> The mapper is asserted to be the very instance
 * {@link IdempotencyService} holds, and the idempotency hash is computed by invoking
 * IdempotencyService's own {@code serialize} and {@code sha256Hex} — never a re-implementation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "JTOYE_GOLDEN_CAPTURE", matches = "true")
class Jackson2GoldenCaptureTest {

    private static final Path ROOT = GoldenFixturesIntegrityTest.ROOT;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdempotencyService idempotencyService;

    private IdempotencyService idempotencyTarget;

    /** endpoint id -> sha256 of the request fingerprint, rewritten whole on every run. */
    private final Map<String, String> requestHashes = new TreeMap<>();

    @Test
    @DisplayName("capture every Jackson-2 golden fixture from the Boot 3.5 production serializers")
    void capture() throws Exception {
        // IdempotencyService is @Transactional, so the bean is a CGLIB proxy whose own fields are
        // empty; its private serialize/sha256Hex must run on the target the proxy delegates to.
        idempotencyTarget = AopTestUtils.getUltimateTargetObject(idempotencyService);
        assertThat(field(idempotencyTarget, "objectMapper"))
                .as("the capture must use the exact ObjectMapper bean IdempotencyService injects")
                .isSameAs(objectMapper);

        captureRequest("orders.create", GoldenSamples.createOrderRequest());

        writeRequestHashes();
        writeManifest();
    }

    private void captureRequest(String endpointId, Object requestBody) throws Exception {
        String json = (String) invoke(idempotencyTarget, "serialize", new Class<?>[]{Object.class}, requestBody);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        write(Path.of("idempotency", endpointId + ".request.json"), bytes);
        String hash = (String) invoke(null, "sha256Hex", new Class<?>[]{String.class}, json);
        assertThat(hash).as("IdempotencyService.sha256Hex must equal the sha256 of the written bytes")
                .isEqualTo(GoldenFixturesIntegrityTest.sha256Hex(bytes));
        requestHashes.put(endpointId, hash);
    }

    private void writeRequestHashes() throws IOException {
        StringBuilder tsv = new StringBuilder();
        requestHashes.forEach((id, hash) -> tsv.append(id).append('\t').append(hash).append('\n'));
        write(Path.of("idempotency", "request-hashes.tsv"), tsv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** MANIFEST.tsv from a sorted walk of the tree (MANIFEST.tsv and README.md excluded). */
    private static void writeManifest() throws IOException {
        StringBuilder tsv = new StringBuilder();
        for (Path fixture : GoldenFixturesIntegrityTest.fixtures()) {
            byte[] bytes = Files.readAllBytes(fixture);
            String relative = ROOT.relativize(fixture).toString().replace('\\', '/');
            tsv.append(relative).append('\t').append(GoldenFixturesIntegrityTest.sha256Hex(bytes))
                    .append('\t').append(bytes.length).append('\n');
        }
        Files.write(ROOT.resolve("MANIFEST.tsv"), tsv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path relative, byte[] bytes) throws IOException {
        Path target = ROOT.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args)
            throws ReflectiveOperationException {
        Method m = IdempotencyService.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }
}
