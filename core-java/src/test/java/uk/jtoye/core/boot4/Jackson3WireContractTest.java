package uk.jtoye.core.boot4;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 38 (BOOT4-04, plan 38-05): the wire contract between Boot 4's Jackson-3
 * {@link JsonMapper} and the Jackson-2 golden fixtures captured on Boot 3.5.16 (plan 38-01).
 *
 * <p>Boot's {@code JsonMapper} writes every REST response, the DSAR acknowledgement and the 401
 * problem body, and (from 38-08) the outbox payloads and the webhook envelope vendors verify. For
 * each fixture under {@code jackson2-golden/responses} and {@code jackson2-golden/outbox} the
 * matching {@link GoldenSamples} instance is serialised with the injected mapper and compared with
 * the Jackson-2 bytes two ways:
 * <ul>
 *   <li><b>raw</b>: byte equality;</li>
 *   <li><b>tree</b>: both sides parsed with the same Jackson-3 mapper; objects compare
 *       order-insensitively, numbers by value. Every difference is reported as a JSON pointer with
 *       both values.</li>
 * </ul>
 * The comparison table is written to {@code build-local/boot4/jackson-wire-diff.tsv}
 * ({@code fixture TAB raw_equal TAB tree_equal TAB differing pointers}) and Boot's bytes to
 * {@code build-local/boot4/jackson3-wire/}.
 *
 * <p>Task 1 of 38-05 asserts the measurement invariants only; Task 3 sets the contract the owner
 * decides. Untagged and on H2, so the fast {@code test} task runs it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class Jackson3WireContractTest {

    static final Path GOLDEN_ROOT = Path.of("src", "test", "resources", "jackson2-golden");
    static final Path DIFF_OUT = Path.of("build-local", "boot4", "jackson-wire-diff.tsv");
    static final Path WIRE_OUT = Path.of("build-local", "boot4", "jackson3-wire");

    /** The two families this contract covers. */
    static final List<String> FAMILIES = List.of("responses", "outbox");

    /**
     * Fixture (family/file) to the factory its bytes were captured from. Every fixture file on disk
     * must have exactly one entry here; a file without a factory fails the test.
     */
    static final Map<String, Supplier<Object>> SAMPLES = samples();

    @Autowired
    private JsonMapper jsonMapper;

    /** One fixture compared with one mapper's output. */
    record Comparison(String fixture, boolean rawEqual, boolean treeEqual, List<String> differences,
                      byte[] actual) {
        String tsv() {
            return fixture + "\t" + rawEqual + "\t" + treeEqual + "\t"
                    + (differences.isEmpty() ? "-" : String.join(" | ", differences));
        }
    }

    @Test
    @DisplayName("every responses/ and outbox/ fixture is compared with Boot's JsonMapper and the diff is written")
    void bootJsonMapperIsComparedWithEveryJackson2Fixture() throws IOException {
        List<String> onDisk = fixtureFiles();
        assertThat(onDisk).as("fixture files under %s %s", GOLDEN_ROOT.toAbsolutePath(), FAMILIES).isNotEmpty();
        assertThat(SAMPLES.keySet())
                .as("every fixture file has exactly one GoldenSamples factory, and every factory a file")
                .containsExactlyInAnyOrderElementsOf(onDisk);

        List<Comparison> comparisons = compareAll(this::serialize);
        writeReport(comparisons, DIFF_OUT, WIRE_OUT);

        // Task 1 asserts the measurement only: every fixture compared, the report written. The
        // RED first run asserted raw equality for every fixture and failed on 4 of 15 (evidence
        // 38-05-jackson-wire-diff.md); Task 3 replaces this with the owner-decided contract.
        assertThat(comparisons).hasSize(onDisk.size());
        assertThat(DIFF_OUT).exists();
        assertThat(Files.readAllLines(DIFF_OUT, StandardCharsets.UTF_8)).hasSize(onDisk.size() + 1);
    }

    @Test
    @DisplayName("the tree diff reports a value change by pointer, and ignores key order and number spelling")
    void treeDiffCanFail() {
        List<String> changed = new ArrayList<>();
        diff("", jsonMapper.readTree("{\"a\":{\"b\":[1,\"x\"]},\"c\":true}"),
                jsonMapper.readTree("{\"c\":true,\"a\":{\"b\":[1,\"y\"]}}"), changed);
        assertThat(changed).containsExactly("/a/b/1 expected=\"x\" actual=\"y\"");

        List<String> missing = new ArrayList<>();
        diff("", jsonMapper.readTree("{\"a\":1,\"b/c\":2}"), jsonMapper.readTree("{\"a\":1}"), missing);
        assertThat(missing).containsExactly("/b~1c expected=2 actual=<absent>");

        List<String> same = new ArrayList<>();
        diff("", jsonMapper.readTree("{\"n\":1.50,\"m\":[2]}"), jsonMapper.readTree("{\"m\":[2.0],\"n\":1.5}"), same);
        assertThat(same).isEmpty();
    }

    // ------------------------------------------------------------------------------ harness

    private byte[] serialize(Object sample) {
        return jsonMapper.writeValueAsBytes(sample);
    }

    List<Comparison> compareAll(Function<Object, byte[]> writer) throws IOException {
        List<Comparison> out = new ArrayList<>();
        for (String fixture : fixtureFiles()) {
            byte[] expected = Files.readAllBytes(GOLDEN_ROOT.resolve(fixture));
            Supplier<Object> factory = SAMPLES.get(fixture);
            if (factory == null) {
                throw new IllegalStateException("no GoldenSamples factory for " + fixture);
            }
            byte[] actual = writer.apply(factory.get());
            out.add(compare(fixture, expected, actual));
        }
        return out;
    }

    Comparison compare(String fixture, byte[] expected, byte[] actual) {
        JsonNode expectedTree = jsonMapper.readTree(expected);
        JsonNode actualTree = jsonMapper.readTree(actual);
        List<String> differences = new ArrayList<>();
        diff("", expectedTree, actualTree, differences);
        return new Comparison(fixture, Arrays.equals(expected, actual), differences.isEmpty(), differences, actual);
    }

    static void writeReport(List<Comparison> comparisons, Path tsv, Path wireDir) throws IOException {
        Files.createDirectories(tsv.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("fixture\traw_equal\ttree_equal\tdiffering_pointers");
        comparisons.stream().map(Comparison::tsv).forEach(lines::add);
        Files.writeString(tsv, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        for (Comparison c : comparisons) {
            Path file = wireDir.resolve(c.fixture());
            Files.createDirectories(file.getParent());
            Files.write(file, c.actual());
        }
    }

    /**
     * Order-insensitive structural diff. Objects: the union of property names, each compared;
     * arrays: element by element; numbers: by value ({@code BigDecimal.compareTo}); anything else:
     * node equality. Each difference is {@code pointer expected=X actual=Y}.
     */
    static void diff(String pointer, JsonNode expected, JsonNode actual, List<String> out) {
        if (expected.isNumber() && actual.isNumber()) {
            if (expected.decimalValue().compareTo(actual.decimalValue()) != 0) {
                out.add(describe(pointer, expected, actual));
            }
            return;
        }
        if (expected.isObject() && actual.isObject()) {
            TreeSet<String> names = new TreeSet<>(expected.propertyNames());
            names.addAll(actual.propertyNames());
            for (String name : names) {
                JsonNode e = expected.get(name);
                JsonNode a = actual.get(name);
                String child = pointer + "/" + name.replace("~", "~0").replace("/", "~1");
                if (e == null || a == null) {
                    out.add(describe(child, e, a));
                } else {
                    diff(child, e, a, out);
                }
            }
            return;
        }
        if (expected.isArray() && actual.isArray()) {
            int n = Math.max(expected.size(), actual.size());
            for (int i = 0; i < n; i++) {
                JsonNode e = i < expected.size() ? expected.get(i) : null;
                JsonNode a = i < actual.size() ? actual.get(i) : null;
                if (e == null || a == null) {
                    out.add(describe(pointer + "/" + i, e, a));
                } else {
                    diff(pointer + "/" + i, e, a, out);
                }
            }
            return;
        }
        if (!expected.equals(actual)) {
            out.add(describe(pointer, expected, actual));
        }
    }

    private static String describe(String pointer, JsonNode expected, JsonNode actual) {
        return (pointer.isEmpty() ? "/" : pointer)
                + " expected=" + (expected == null ? "<absent>" : expected.toString())
                + " actual=" + (actual == null ? "<absent>" : actual.toString());
    }

    /** {@code family/file} for every regular file of the covered families, sorted. */
    static List<String> fixtureFiles() throws IOException {
        List<String> files = new ArrayList<>();
        for (String family : FAMILIES) {
            Path dir = GOLDEN_ROOT.resolve(family);
            if (!Files.isDirectory(dir)) {
                throw new IllegalStateException("fixture family directory missing: " + dir.toAbsolutePath());
            }
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isRegularFile)
                        .map(p -> family + "/" + p.getFileName())
                        .forEach(files::add);
            }
        }
        return files.stream().sorted().collect(Collectors.toList());
    }

    private static Map<String, Supplier<Object>> samples() {
        Map<String, Supplier<Object>> m = new LinkedHashMap<>();
        m.put("responses/CustomerDto.json", GoldenSamples::customerDto);
        m.put("responses/DsarIntakeAck.json", GoldenSamples::dsarIntakeAck);
        m.put("responses/MediaAcceptDto.json", GoldenSamples::mediaAcceptDto);
        m.put("responses/OrderDto.json", GoldenSamples::orderDto);
        m.put("responses/ProblemDetail-401.json", GoldenSamples::problemDetail401);
        m.put("responses/ProductDto.json", GoldenSamples::productDto);
        m.put("responses/ShopDto.json", GoldenSamples::shopDto);
        m.put("responses/WebhookDeliveryView.json", GoldenSamples::webhookDeliveryView);
        m.put("responses/WebhookEventEnvelope.json", GoldenSamples::webhookEventEnvelope);
        m.put("outbox/MediaProcessingEvent.json", GoldenSamples::mediaProcessingEvent);
        m.put("outbox/OnboardingStateChangeEvent.json", GoldenSamples::onboardingStateChangeEvent);
        m.put("outbox/OrderStateChangeEvent.json", GoldenSamples::orderStateChangeEvent);
        m.put("outbox/OrderStateChangeEvent-offset.json", GoldenSamples::orderStateChangeEventOffset);
        m.put("outbox/PaymentEvent.json", GoldenSamples::paymentEvent);
        m.put("outbox/RefundEvent.json", GoldenSamples::refundEvent);
        return Map.copyOf(m);
    }
}
