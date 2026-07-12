package uk.jtoye.core.integration;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI snapshot contract (issue #97 AC3 — CI fails on unreviewed OpenAPI
 * breaking change).
 *
 * <p>Boots the FULL Spring context against a throwaway Testcontainers
 * Postgres (same infra as every other {@code @Tag("testcontainers")} class)
 * and fetches the real springdoc output from {@code /v3/api-docs} via
 * MockMvc — so the spec reflects exactly what production serves, including
 * the WebConfig {@code /api/v1} prefixing and springdoc annotations. This is
 * deliberately NOT the springdoc-openapi-gradle-plugin: that boots the app
 * out-of-band (needs a live DB and port juggling in CI), whereas this reuses
 * the proven integration-test bootstrap.
 *
 * <p><b>Determinism:</b> springdoc's raw JSON is normalized before
 * comparison/writing — see {@link #normalize(String)}:
 * <ul>
 *   <li>every JSON object's keys are sorted (springdoc emits paths/schemas in
 *       scan order, which is not guaranteed stable),</li>
 *   <li>the environment-dependent {@code servers} block is stripped,</li>
 *   <li>the root {@code tags} array is sorted by name,</li>
 *   <li>output is pretty-printed with 2-space indent, {@code \n} line ends
 *       and a trailing newline, so the committed snapshot diffs cleanly.</li>
 * </ul>
 *
 * <p><b>Modes</b> (system property {@code jtoye.openapi.mode}):
 * <dl>
 *   <dt>{@code check} (default — runs in {@code integrationTest} / CI)</dt>
 *   <dd>writes {@code build-local/openapi/openapi-current.json} and asserts
 *       byte-equality with the committed {@code docs/api/openapi-snapshot.json}.</dd>
 *   <dt>{@code generate} ({@code ./gradlew :core-java:generateOpenApiSpec})</dt>
 *   <dd>writes the build artifact only — the CI {@code openapi-compat} job
 *       then classifies drift with oasdiff (breaking vs regenerate-please).</dd>
 *   <dt>{@code update} ({@code ./gradlew :core-java:updateOpenApiSnapshot})</dt>
 *   <dd>rewrites the committed snapshot. Run this when you INTENTIONALLY
 *       change the API surface, and commit the snapshot diff in the same PR
 *       so the change is visible to reviewers.</dd>
 * </dl>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("testcontainers")
class OpenApiSnapshotTest {

    /** Committed snapshot location, relative to the repo root. */
    private static final String SNAPSHOT_PATH = "docs/api/openapi-snapshot.json";

    /** Regenerated-spec artifact, relative to the core-java project dir (Gradle's test workingDir). */
    private static final String ARTIFACT_PATH = "build-local/openapi/openapi-current.json";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsMatchCommittedSnapshot() throws Exception {
        String raw = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String normalized = normalize(raw);

        // Always emit the build artifact — the CI openapi-compat job diffs it
        // against the committed snapshot with oasdiff.
        Path artifact = Path.of(ARTIFACT_PATH);
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, normalized, StandardCharsets.UTF_8);

        String mode = System.getProperty("jtoye.openapi.mode", "check");
        Path snapshot = repoRoot().resolve(SNAPSHOT_PATH);
        switch (mode) {
            case "update" -> {
                Files.createDirectories(snapshot.getParent());
                Files.writeString(snapshot, normalized, StandardCharsets.UTF_8);
            }
            case "generate" -> {
                // artifact-only: the CI gate owns the comparison
            }
            default -> {
                assertThat(snapshot)
                        .as("Committed OpenAPI snapshot must exist — generate it with "
                                + "./gradlew :core-java:updateOpenApiSnapshot")
                        .exists();
                String committed = Files.readString(snapshot, StandardCharsets.UTF_8);
                assertThat(normalized)
                        .as("The OpenAPI spec served by /v3/api-docs no longer matches the reviewed "
                                + "snapshot at %s. If this API change is intentional, regenerate the "
                                + "snapshot with `./gradlew :core-java:updateOpenApiSnapshot` and commit "
                                + "it in the same PR so reviewers see the contract diff. The regenerated "
                                + "spec was written to core-java/%s for inspection.",
                                SNAPSHOT_PATH, ARTIFACT_PATH)
                        .isEqualTo(committed);
            }
        }
    }

    // ------------------------------------------------------------------
    // Normalization
    // ------------------------------------------------------------------

    /**
     * Renders springdoc's JSON byte-stable: strips the env-dependent
     * {@code servers} block, sorts the root {@code tags} array by name,
     * then re-serializes with all object keys sorted, 2-space indent,
     * {@code \n} line endings and a trailing newline.
     */
    static String normalize(String rawJson) throws Exception {
        ObjectMapper reader = new ObjectMapper()
                // BigDecimal keeps "0.1" as 0.1 instead of a double artifact.
                .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);
        JsonNode root = reader.readTree(rawJson);

        if (root instanceof ObjectNode obj) {
            // Server URLs differ per environment (localhost vs api.jtoye.uk)
            // and springdoc can synthesize one from the request — volatile,
            // and irrelevant to the API contract this gate protects.
            obj.remove("servers");
            sortTagsByName(obj);
        }

        // JSON object key order is semantically irrelevant; sorting every map
        // removes springdoc's scan-order instability. Arrays are left in
        // emitted order (they are semantically ordered: required, enum, ...).
        ObjectMapper writer = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(indenter)
                .withArrayIndenter(indenter);
        Object plain = writer.treeToValue(root, Object.class);
        return writer.writer(printer).writeValueAsString(plain) + "\n";
    }

    private static void sortTagsByName(ObjectNode root) {
        JsonNode tags = root.get("tags");
        if (!(tags instanceof ArrayNode tagsArray)) {
            return;
        }
        List<JsonNode> sorted = new ArrayList<>();
        tagsArray.forEach(sorted::add);
        sorted.sort(Comparator.comparing(t -> t.path("name").asText()));
        tagsArray.removeAll();
        sorted.forEach(tagsArray::add);
    }

    /** Walks up from the test workingDir (core-java/) to the dir containing settings.gradle.kts. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("Could not locate repo root (settings.gradle.kts) above "
                    + Path.of("").toAbsolutePath());
        }
        return dir;
    }
}
