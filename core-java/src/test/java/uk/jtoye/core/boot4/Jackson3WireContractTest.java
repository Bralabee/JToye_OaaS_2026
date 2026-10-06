package uk.jtoye.core.boot4;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 38 (BOOT4-04, plan 38-05): the PERMANENT wire contract of Boot 4's Jackson-3
 * {@link JsonMapper}, against the Jackson-2 golden fixtures captured on Boot 3.5.16 (plan 38-01).
 *
 * <p><b>Owner decision, 2026-10-05 (38-05 Task 2): "jackson3-defaults (Recommended)".</b> Boot's
 * mapper keeps Jackson 3's defaults; no {@code spring.jackson.*} key is set. The measurement it was
 * taken from is {@code .planning/phases/38-spring-boot-4-1-migration/evidence/38-05-jackson-wire-diff.md}.
 * This class locks the decided relationship, so a future default drift (a Jackson upgrade, a
 * {@code spring.jackson.*} key, a customizer) on a published shape turns it red:
 * <ul>
 *   <li><b>Every fixture is TREE-equal</b> to Boot's output: same keys, same values, numbers by
 *       value. No published value changes.</li>
 *   <li><b>Records are BYTE-equal</b>: the 6 outbox payloads, the webhook envelope vendors verify
 *       the HMAC over, and every record response keep their exact bytes.</li>
 *   <li><b>Class-based types are written in alphabetical key order</b> (Jackson 3's
 *       {@code SORT_PROPERTIES_ALPHABETICALLY}). Exactly those fixtures are raw-unequal, and they are
 *       listed in {@link #ACCEPTED_ORDERING_DIFFERENCES}: the first of the two contract changes this
 *       decision accepts.</li>
 *   <li><b>Acceptance</b>: the decided reading behaviour of Boot's mapper, asserted on this mapper
 *       alone (the Jackson-2 comparison column disappears with the bridge in 38-12). The one change
 *       that reaches the API is the second accepted contract change: trailing content after a
 *       request body is a 400 {@code errors/unreadable-request} instead of being ignored.</li>
 * </ul>
 * The REST message converter is asserted to hold this very mapper, so the mapper-level assertions
 * are the REST behaviour. The comparison table is still written to
 * {@code build-local/boot4/jackson-wire-diff.tsv} and Boot's bytes to
 * {@code build-local/boot4/jackson3-wire/}. Untagged and on H2, so the fast {@code test} task runs it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Jackson3WireContractTest {

    static final Path GOLDEN_ROOT = Path.of("src", "test", "resources", "jackson2-golden");
    static final Path DIFF_OUT = Path.of("build-local", "boot4", "jackson-wire-diff.tsv");
    static final Path WIRE_OUT = Path.of("build-local", "boot4", "jackson3-wire");

    /** The two families this contract covers. */
    static final List<String> FAMILIES = List.of("responses", "outbox");

    /**
     * The fixtures whose bytes Boot's mapper does NOT reproduce, each an accepted ORDERING
     * difference (owner decision 2026-10-05): the class-based types, whose properties Jackson 3
     * writes alphabetically. Measured in 38-05 Task 1 (equal byte length, equal trees). A fixture
     * leaving this set (the order reverted) or joining it (a record's bytes changed) is a contract
     * change and turns this test red.
     */
    static final Set<String> ACCEPTED_ORDERING_DIFFERENCES = Set.of(
            "responses/OrderDto.json",
            "responses/ProblemDetail-401.json",
            "responses/ProductDto.json",
            "responses/ShopDto.json");

    /**
     * Fixture (family/file) to the factory its bytes were captured from. Every fixture file on disk
     * must have exactly one entry here; a file without a factory fails the test.
     */
    static final Map<String, Supplier<Object>> SAMPLES = samples();

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

    @Autowired
    private MockMvc mockMvc;

    /** One fixture compared with one mapper's output. */
    record Comparison(String fixture, boolean rawEqual, boolean treeEqual, List<String> differences,
                      byte[] actual) {
        String tsv() {
            return fixture + "\t" + rawEqual + "\t" + treeEqual + "\t"
                    + (differences.isEmpty() ? "-" : String.join(" | ", differences));
        }
    }

    // ------------------------------------------------------------------ the wire contract

    @Test
    @DisplayName("every responses/ and outbox/ fixture is tree-equal to Boot's output; only the accepted ordering differences are raw-unequal")
    void everyFixtureIsTreeEqualAndOnlyTheAcceptedOrderingDifferencesAreRawUnequal() throws IOException {
        List<String> onDisk = fixtureFiles();
        assertThat(onDisk).as("fixture files under %s %s", GOLDEN_ROOT.toAbsolutePath(), FAMILIES).isNotEmpty();
        assertThat(SAMPLES.keySet())
                .as("every fixture file has exactly one GoldenSamples factory, and every factory a file")
                .containsExactlyInAnyOrderElementsOf(onDisk);

        List<Comparison> comparisons = compareAll(this::serialize);
        writeReport(comparisons, DIFF_OUT, WIRE_OUT);
        assertThat(comparisons).hasSize(onDisk.size());

        assertThat(comparisons.stream().filter(c -> !c.treeEqual()).map(Comparison::tsv).toList())
                .as("fixtures whose parsed JSON (keys, values, numbers by value) differs from Boot's output")
                .isEmpty();
        assertThat(comparisons.stream().filter(c -> !c.rawEqual()).map(Comparison::fixture).collect(Collectors.toSet()))
                .as("fixtures whose bytes Boot does not reproduce must be exactly the accepted ordering differences")
                .isEqualTo(ACCEPTED_ORDERING_DIFFERENCES);
    }

    @Test
    @DisplayName("records keep their bytes; class-based types are written in alphabetical key order")
    void recordsKeepTheirBytesAndClassBasedTypesAreAlphabetical() throws IOException {
        Set<String> classBased = new TreeSet<>();
        List<String> violations = new ArrayList<>();
        for (Comparison c : compareAll(this::serialize)) {
            Object sample = SAMPLES.get(c.fixture()).get();
            if (sample.getClass().isRecord()) {
                if (!c.rawEqual()) {
                    violations.add(c.fixture() + " is a record and its bytes changed");
                }
                continue;
            }
            classBased.add(c.fixture());
            List<String> actualOrder = new ArrayList<>(jsonMapper.readTree(c.actual()).propertyNames());
            List<String> sorted = new ArrayList<>(new TreeSet<>(
                    jsonMapper.readTree(Files.readAllBytes(GOLDEN_ROOT.resolve(c.fixture()))).propertyNames()));
            if (!actualOrder.equals(sorted)) {
                violations.add(c.fixture() + " top-level keys " + actualOrder + " are not the fixture's keys sorted " + sorted);
            }
        }
        assertThat(violations).as("record byte identity and class-based alphabetical order").isEmpty();
        assertThat(classBased)
                .as("the class-based samples are exactly the accepted ordering differences (positive control: both kinds exist)")
                .isEqualTo(ACCEPTED_ORDERING_DIFFERENCES);
    }

    @Test
    @DisplayName("the REST message converter writes and reads with this very JsonMapper")
    void restBodiesUseThisMapper() {
        List<String> converters = new ArrayList<>();
        boolean found = false;
        for (HttpMessageConverter<?> c : handlerAdapter.getMessageConverters()) {
            converters.add(c.getClass().getName());
            if (c instanceof JacksonJsonHttpMessageConverter j && j.getMapper() == jsonMapper) {
                found = true;
            }
        }
        assertThat(found).as("a JacksonJsonHttpMessageConverter holding Boot's JsonMapper bean among %s", converters).isTrue();
    }

    // ------------------------------------------------------------- the decided acceptance

    record Named(String name) {
    }

    record Prims(int i, long l) {
    }

    /** A setter-based POJO with a primitive, the shape of the Lombok request DTOs. */
    static class PojoPrim {
        private int quantity;

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }
    }

    /** An enum whose toString differs from its name: the only kind the enum flags can change. */
    enum Labelled {
        ALPHA;

        @Override
        public String toString() {
            return "alpha-label";
        }
    }

    record Tagged(Labelled tag) {
    }

    record When(OffsetDateTime at) {
    }

    record Items(List<String> items) {
    }

    record Count(int count) {
    }

    @Test
    @DisplayName("trailing content after a value is rejected (FAIL_ON_TRAILING_TOKENS: accepted contract change)")
    void trailingTokensAreRejected() {
        assertThatThrownBy(() -> jsonMapper.readValue("{\"name\":\"a\"} {\"name\":\"b\"}", Named.class))
                .isInstanceOf(JacksonException.class).hasMessageContaining("Trailing token");
        assertThatThrownBy(() -> jsonMapper.readValue("{\"name\":\"a\"}}", Named.class))
                .isInstanceOf(JacksonException.class).hasMessageContaining("Unexpected close marker");
        assertThat(jsonMapper.readValue("{\"name\":\"a\"}", Named.class)).isEqualTo(new Named("a"));
    }

    @Test
    @DisplayName("a trailing token after a request body is a 400 errors/unreadable-request on the real HTTP path")
    void trailingTokenAfterARequestBodyIsUnreadableRequest() throws Exception {
        // 38-02's guest-order body, valid except that customerEmail is absent: a body that is READ
        // is answered by validation, one that is NOT read by the unreadable-body handler.
        String body = "{\"customerName\":\"Ada Test\",\"customerPhone\":\"07700900000\","
                + "\"fulfilmentType\":\"COLLECTION\","
                + "\"items\":[{\"productId\":\"00000000-0000-0000-0000-000000000001\",\"quantity\":1}]}";

        JsonNode control = postGuestOrder(body);
        assertThat(control.path("status").asInt()).as("control status").isEqualTo(400);
        assertThat(control.path("type").asString()).as("control: the body is read and validated")
                .isEqualTo("https://jtoye.uk/errors/validation");

        JsonNode trailing = postGuestOrder(body + " {\"x\":1}");
        assertThat(trailing.path("status").asInt()).as("trailing-token status").isEqualTo(400);
        assertThat(trailing.path("type").asString()).as("trailing token: the body is not read at all")
                .isEqualTo("https://jtoye.uk/errors/unreadable-request");
    }

    @Test
    @DisplayName("JSON null, or an absent record component, into an int/long is rejected (FAIL_ON_NULL_FOR_PRIMITIVES)")
    void nullOrAbsentIntoAPrimitiveIsRejected() {
        assertThatThrownBy(() -> jsonMapper.readValue("{\"i\":null,\"l\":null}", Prims.class))
                .isInstanceOf(JacksonException.class).hasMessageContaining("Cannot map `null` into type `int`");
        assertThatThrownBy(() -> jsonMapper.readValue("{}", Prims.class))
                .isInstanceOf(JacksonException.class).hasMessageContaining("Cannot map `null` into type `int`");
        assertThatThrownBy(() -> jsonMapper.readValue("{\"quantity\":null}", PojoPrim.class))
                .isInstanceOf(JacksonException.class).hasMessageContaining("Cannot map `null` into type `int`");
        assertThat(jsonMapper.readValue("{\"i\":1,\"l\":2}", Prims.class)).isEqualTo(new Prims(1, 2L));
    }

    @Test
    @DisplayName("an unknown property is ignored")
    void unknownPropertiesAreIgnored() {
        assertThat(jsonMapper.readValue("{\"name\":\"a\",\"bogus\":1}", Named.class)).isEqualTo(new Named("a"));
    }

    @Test
    @DisplayName("enums are read and written by toString (READ_/WRITE_ENUMS_USING_TO_STRING)")
    void enumsUseToString() {
        assertThat(jsonMapper.readValue("{\"tag\":\"alpha-label\"}", Tagged.class)).isEqualTo(new Tagged(Labelled.ALPHA));
        assertThatThrownBy(() -> jsonMapper.readValue("{\"tag\":\"ALPHA\"}", Tagged.class))
                .isInstanceOf(JacksonException.class).hasMessageContaining("not one of the values accepted");
        assertThat(jsonMapper.writeValueAsString(new Tagged(Labelled.ALPHA))).isEqualTo("{\"tag\":\"alpha-label\"}");
    }

    @Test
    @DisplayName("an epoch decimal and an ISO +01:00 string read to the same instant, normalised to UTC")
    void datesReadToTheSameInstantInUtc() {
        Instant expected = Instant.parse("2026-10-04T12:34:56.123456789Z");
        for (String json : List.of("{\"at\":1791117296.123456789}", "{\"at\":\"2026-10-04T13:34:56.123456789+01:00\"}")) {
            OffsetDateTime at = jsonMapper.readValue(json, When.class).at();
            assertThat(at.toInstant()).as("instant of %s", json).isEqualTo(expected);
            assertThat(at.getOffset()).as("offset of %s", json).isEqualTo(ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("a single value is not accepted where a List is expected")
    void aSingleValueIsNotAList() {
        assertThatThrownBy(() -> jsonMapper.readValue("{\"items\":\"a\"}", Items.class))
                .isInstanceOf(JacksonException.class);
        assertThat(jsonMapper.readValue("{\"items\":[\"a\"]}", Items.class)).isEqualTo(new Items(List.of("a")));
    }

    @Test
    @DisplayName("a float into an int is truncated")
    void aFloatIntoAnIntIsTruncated() {
        assertThat(jsonMapper.readValue("{\"count\":1.5}", Count.class)).isEqualTo(new Count(1));
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

    private JsonNode postGuestOrder(String body) throws Exception {
        MvcResult r = mockMvc.perform(post("/public/shops/any-slug/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as("HTTP status for %s", body).isEqualTo(400);
        return jsonMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

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
