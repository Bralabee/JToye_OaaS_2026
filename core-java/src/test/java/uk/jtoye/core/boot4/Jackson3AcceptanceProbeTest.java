package uk.jtoye.core.boot4;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEMPORARY (plan 38-05 Task 1; deleted by Task 3): the side-by-side acceptance differential of
 * the two JSON mappers the interim Boot-4 tree holds - the bridge's Jackson-2
 * {@code com.fasterxml.jackson.databind.ObjectMapper} (removed by 38-12) and Boot's Jackson-3
 * {@link JsonMapper}.
 *
 * <p>It measures, it does not decide. For a fixed probe set it records accept/reject and the
 * resulting value per mapper; it lists the request DTOs exposed to the flags that differ; and, as
 * the counterfactual for the owner's option B, it measures a Boot {@code JsonMapper} built by
 * Boot's own {@link JacksonAutoConfiguration} with {@code spring.jackson.use-jackson2-defaults=true}
 * (fidelity-controlled: the same runner WITHOUT the property must write every fixture exactly as
 * the injected bean does). Outputs land under {@code build-local/boot4/}.
 *
 * <p>Default (MOCK) web environment, unlike the wire-contract test: the DTO walk needs the live
 * {@link RequestMappingHandlerMapping}, as {@code RequestBodyConstraintEnforcementTest} uses it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Jackson3AcceptanceProbeTest {

    static final Path PROBE_OUT = Path.of("build-local", "boot4", "jackson-acceptance-probe.tsv");
    static final Path EXPOSURE_OUT = Path.of("build-local", "boot4", "request-dto-exposure.tsv");
    static final Path COUNTERFACTUAL_OUT = Path.of("build-local", "boot4", "jackson2-defaults-wire-diff.tsv");
    static final Path COUNTERFACTUAL_WIRE = Path.of("build-local", "boot4", "jackson2-defaults-wire");
    static final Path TARGETED_OUT = Path.of("build-local", "boot4", "jackson-targeted-keys.tsv");
    /** Option C's candidate keys: exactly the two flags the measurement shows reaching this API. */
    static final List<String> TARGETED_KEYS = List.of(
            "spring.jackson.mapper.sort-properties-alphabetically=false",
            "spring.jackson.deserialization.fail-on-trailing-tokens=false");
    static final Path HTTP_OUT = Path.of("build-local", "boot4", "jackson-trailing-token-http.txt");
    static final Path FACTS_OUT = Path.of("build-local", "boot4", "jackson-mapper-facts.txt");
    static final Path INVENTORY_3802 = Path.of("..", ".planning", "phases", "38-spring-boot-4-1-migration",
            "evidence", "38-02-request-body-constraints-boot35.tsv");

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper jackson2;

    @Autowired
    private JsonMapper jackson3;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------ probe target types

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

        @Override
        public String toString() {
            return "PojoPrim[quantity=" + quantity + "]";
        }
    }

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

    /** One input read into one type. */
    record Probe(String id, String json, Class<?> type) {
    }

    static final List<Probe> PROBES = List.of(
            new Probe("trailing-object", "{\"name\":\"a\"} {\"name\":\"b\"}", Named.class),
            new Probe("trailing-brace", "{\"name\":\"a\"}}", Named.class),
            new Probe("null-into-record-int-long", "{\"i\":null,\"l\":null}", Prims.class),
            new Probe("absent-record-int-long", "{}", Prims.class),
            new Probe("null-into-pojo-int-setter", "{\"quantity\":null}", PojoPrim.class),
            new Probe("unknown-property", "{\"name\":\"a\",\"bogus\":1}", Named.class),
            new Probe("enum-by-name", "{\"tag\":\"ALPHA\"}", Tagged.class),
            new Probe("enum-by-toString", "{\"tag\":\"alpha-label\"}", Tagged.class),
            new Probe("datetime-epoch-decimal", "{\"at\":1791117296.123456789}", When.class),
            new Probe("datetime-iso-plus-0100", "{\"at\":\"2026-10-04T13:34:56.123456789+01:00\"}", When.class),
            new Probe("single-value-for-list", "{\"items\":\"a\"}", Items.class),
            new Probe("float-into-int", "{\"count\":1.5}", Count.class));

    @Test
    @DisplayName("acceptance differential: Jackson-2 bridge vs Boot Jackson-3 (and Boot with use-jackson2-defaults)")
    void acceptanceDifferential() throws Exception {
        AtomicReference<JsonMapper> j2defaults = new AtomicReference<>();
        AtomicReference<JsonMapper> runnerDefault = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(ctx -> runnerDefault.set(ctx.getBean(JsonMapper.class)));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withPropertyValues("spring.jackson.use-jackson2-defaults=true")
                .run(ctx -> j2defaults.set(ctx.getBean(JsonMapper.class)));
        assertThat(runnerDefault.get()).isNotNull();
        assertThat(j2defaults.get()).isNotNull();

        List<String> lines = new ArrayList<>();
        lines.add("probe\tinput\tjackson2_bridge\tjackson3_boot\tjackson3_boot_use_jackson2_defaults"
                + "\tdiffers_bridge_vs_boot\tdiffers_bridge_vs_use_jackson2_defaults");
        int differing = 0;
        int differingCounterfactual = 0;
        for (Probe p : PROBES) {
            String j2 = outcome(() -> jackson2.readValue(p.json(), p.type()));
            String j3 = outcome(() -> jackson3.readValue(p.json(), p.type()));
            String j3c = outcome(() -> j2defaults.get().readValue(p.json(), p.type()));
            boolean differs = !verdict(j2).equals(verdict(j3));
            boolean differsC = !verdict(j2).equals(verdict(j3c));
            differing += differs ? 1 : 0;
            differingCounterfactual += differsC ? 1 : 0;
            lines.add(p.id() + "\t" + p.json() + "\t" + j2 + "\t" + j3 + "\t" + j3c + "\t" + differs + "\t" + differsC);
        }
        // Serialisation-side enum probe: the two WRITE_ENUMS_USING_TO_STRING defaults.
        String w2 = outcome(() -> jackson2.writeValueAsString(new Tagged(Labelled.ALPHA)));
        String w3 = outcome(() -> jackson3.writeValueAsString(new Tagged(Labelled.ALPHA)));
        String w3c = outcome(() -> j2defaults.get().writeValueAsString(new Tagged(Labelled.ALPHA)));
        boolean wDiffers = !verdict(w2).equals(verdict(w3));
        boolean wDiffersC = !verdict(w2).equals(verdict(w3c));
        differing += wDiffers ? 1 : 0;
        differingCounterfactual += wDiffersC ? 1 : 0;
        lines.add("write-enum-with-toString\tnew Tagged(ALPHA)\t" + w2 + "\t" + w3 + "\t" + w3c + "\t" + wDiffers + "\t" + wDiffersC);
        lines.add("# probes_differing_bridge_vs_boot=" + differing
                + " probes_differing_bridge_vs_use_jackson2_defaults=" + differingCounterfactual
                + " (an outcome is ACCEPT plus its value, or REJECT; the exception text is not compared)");
        write(PROBE_OUT, lines);

        assertThat(lines).hasSize(PROBES.size() + 3);

        // Fidelity control for the counterfactual, and the counterfactual itself.
        Jackson3WireContractTest wire = new Jackson3WireContractTest();
        Field mapperField = Jackson3WireContractTest.class.getDeclaredField("jsonMapper");
        mapperField.setAccessible(true);
        mapperField.set(wire, jackson3);

        List<Jackson3WireContractTest.Comparison> injected = wire.compareAll(jackson3::writeValueAsBytes);
        List<Jackson3WireContractTest.Comparison> runner = wire.compareAll(runnerDefault.get()::writeValueAsBytes);
        List<String> fidelity = new ArrayList<>();
        for (int i = 0; i < injected.size(); i++) {
            if (!java.util.Arrays.equals(injected.get(i).actual(), runner.get(i).actual())) {
                fidelity.add(injected.get(i).fixture());
            }
        }
        List<Jackson3WireContractTest.Comparison> counterfactual =
                wire.compareAll(j2defaults.get()::writeValueAsBytes);
        Jackson3WireContractTest.writeReport(counterfactual, COUNTERFACTUAL_OUT, COUNTERFACTUAL_WIRE);
        List<Jackson3WireContractTest.Comparison> bridge = wire.compareAll(o -> {
            try {
                return jackson2.writeValueAsBytes(o);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        // Which mapper the MVC message converter (REST bodies) actually uses.
        List<String> converters = new ArrayList<>();
        boolean mvcUsesBootBean = false;
        for (HttpMessageConverter<?> c : handlerAdapter.getMessageConverters()) {
            converters.add(c.getClass().getName());
            if (c instanceof JacksonJsonHttpMessageConverter j && j.getMapper() == jackson3) {
                mvcUsesBootBean = true;
            }
        }

        List<String> facts = new ArrayList<>();
        facts.add("probes_differing_bridge_vs_boot=" + differing);
        facts.add("probes_differing_bridge_vs_use_jackson2_defaults=" + differingCounterfactual);
        facts.add("runner_default_vs_injected_bean_byte_mismatches=" + fidelity);
        facts.add("use_jackson2_defaults_raw_equal=" + counterfactual.stream().filter(Jackson3WireContractTest.Comparison::rawEqual).count()
                + "/" + counterfactual.size());
        facts.add("use_jackson2_defaults_tree_equal=" + counterfactual.stream().filter(Jackson3WireContractTest.Comparison::treeEqual).count()
                + "/" + counterfactual.size());
        facts.add("use_jackson2_defaults_raw_unequal=" + counterfactual.stream().filter(c -> !c.rawEqual())
                .map(Jackson3WireContractTest.Comparison::tsv).toList());
        facts.add("jackson2_bridge_raw_equal=" + bridge.stream().filter(Jackson3WireContractTest.Comparison::rawEqual).count()
                + "/" + bridge.size());
        facts.add("jackson2_bridge_raw_unequal=" + bridge.stream().filter(c -> !c.rawEqual())
                .map(Jackson3WireContractTest.Comparison::fixture).toList());
        facts.add("mvc_jackson_converter_mapper_is_boot_jsonMapper_bean=" + mvcUsesBootBean);
        facts.add("mvc_converters=" + converters);
        write(FACTS_OUT, facts);

        assertThat(fidelity).as("the runner-built Boot mapper writes every fixture as the injected bean does").isEmpty();
        assertThat(counterfactual).hasSize(injected.size());
    }

    @Test
    @DisplayName("counterfactual for option C: Boot's mapper with only the two targeted keys")
    void targetedKeysCounterfactual() throws Exception {
        AtomicReference<JsonMapper> targeted = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withPropertyValues(TARGETED_KEYS.toArray(String[]::new))
                .run(ctx -> targeted.set(ctx.getBean(JsonMapper.class)));
        assertThat(targeted.get()).isNotNull();

        Jackson3WireContractTest wire = new Jackson3WireContractTest();
        Field mapperField = Jackson3WireContractTest.class.getDeclaredField("jsonMapper");
        mapperField.setAccessible(true);
        mapperField.set(wire, jackson3);
        List<Jackson3WireContractTest.Comparison> wireRows = wire.compareAll(targeted.get()::writeValueAsBytes);

        List<String> lines = new ArrayList<>();
        lines.add("# keys=" + TARGETED_KEYS);
        lines.add("# raw_equal=" + wireRows.stream().filter(Jackson3WireContractTest.Comparison::rawEqual).count()
                + "/" + wireRows.size() + " tree_equal="
                + wireRows.stream().filter(Jackson3WireContractTest.Comparison::treeEqual).count() + "/" + wireRows.size());
        wireRows.stream().map(Jackson3WireContractTest.Comparison::tsv).forEach(lines::add);
        int differing = 0;
        lines.add("probe\tjackson2_bridge\tjackson3_targeted\tdiffers");
        for (Probe p : PROBES) {
            String j2 = outcome(() -> jackson2.readValue(p.json(), p.type()));
            String jt = outcome(() -> targeted.get().readValue(p.json(), p.type()));
            boolean differs = !verdict(j2).equals(verdict(jt));
            differing += differs ? 1 : 0;
            lines.add(p.id() + "\t" + j2 + "\t" + jt + "\t" + differs);
        }
        lines.add("# probes_differing_bridge_vs_targeted=" + differing);
        write(TARGETED_OUT, lines);
        assertThat(wireRows).isNotEmpty();
    }

    @Test
    @DisplayName("real HTTP path: does a trailing token after a request body reach Boot's mapper flag?")
    void trailingTokenOnTheRealHttpPath() throws Exception {
        // 38-02's guest-order body, valid except that customerEmail is absent, so a body that is
        // READ is answered by validation (errors/validation) and one that is NOT read by the
        // unreadable-body handler (errors/unreadable-request).
        String body = "{\"customerName\":\"Ada Test\",\"customerPhone\":\"07700900000\","
                + "\"fulfilmentType\":\"COLLECTION\","
                + "\"items\":[{\"productId\":\"00000000-0000-0000-0000-000000000001\",\"quantity\":1}]}";
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> c : Map.of(
                "control-no-trailing-token", body,
                "trailing-object", body + " {\"x\":1}").entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            MvcResult r = mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post("/public/shops/any-slug/orders")
                                    .contentType(MediaType.APPLICATION_JSON).content(c.getValue()))
                    .andReturn();
            out.add(c.getKey() + "\tstatus=" + r.getResponse().getStatus() + "\tbody="
                    + r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
        write(HTTP_OUT, out);
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).as("the control reaches validation").contains("errors/validation");
    }

    @Test
    @DisplayName("request DTOs exposed to FAIL_ON_NULL_FOR_PRIMITIVES / enum toString / date handling")
    void requestDtoExposure() throws IOException {
        // (IOException covers the inventory read, the classpath scan and the writes.)
        Set<String> inventoryNames = Files.readAllLines(INVENTORY_3802, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.startsWith("#") && !l.isBlank())
                .map(l -> l.split("\t")[1])
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(inventoryNames).as("38-02 inventory DTO names").isNotEmpty();

        Map<Class<?>, Set<String>> endpointsByType = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod hm = e.getValue();
            if (!hm.getBeanType().getName().startsWith("uk.jtoye.core.")) {
                continue;
            }
            for (MethodParameter p : hm.getMethodParameters()) {
                if (!p.hasParameterAnnotation(RequestBody.class)) {
                    continue;
                }
                collect(p.getGenericParameterType(), hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName(),
                        endpointsByType, new java.util.HashSet<>());
            }
        }
        assertThat(endpointsByType).as("@RequestBody types reachable from the handler mapping").isNotEmpty();

        Map<String, String> rows = new TreeMap<>();
        int primitiveExposed = 0;
        int enumExposed = 0;
        int enumToStringDiffers = 0;
        for (Map.Entry<Class<?>, Set<String>> e : endpointsByType.entrySet()) {
            Class<?> type = e.getKey();
            List<String> prims = new ArrayList<>();
            List<String> enums = new ArrayList<>();
            List<String> temporals = new ArrayList<>();
            for (Map.Entry<String, Class<?>> prop : properties(type).entrySet()) {
                Class<?> t = prop.getValue();
                if (t.isPrimitive()) {
                    prims.add(prop.getKey() + ":" + t.getName());
                } else if (t.isEnum()) {
                    boolean differs = false;
                    for (Object c : t.getEnumConstants()) {
                        if (!c.toString().equals(((Enum<?>) c).name())) {
                            differs = true;
                        }
                    }
                    enums.add(prop.getKey() + ":" + t.getSimpleName() + (differs ? "(toString!=name)" : "(toString==name)"));
                    if (differs) {
                        enumToStringDiffers++;
                    }
                } else if (Temporal.class.isAssignableFrom(t)) {
                    temporals.add(prop.getKey() + ":" + t.getSimpleName());
                }
            }
            if (!prims.isEmpty()) {
                primitiveExposed++;
            }
            if (!enums.isEmpty()) {
                enumExposed++;
            }
            String simple = type.getSimpleName();
            rows.put(type.getName(), simple + "\t" + inventoryNames.contains(simple) + "\t"
                    + (prims.isEmpty() ? "-" : String.join(",", prims)) + "\t"
                    + (enums.isEmpty() ? "-" : String.join(",", enums)) + "\t"
                    + (temporals.isEmpty() ? "-" : String.join(",", temporals)) + "\t"
                    + String.join(",", new TreeSet<>(e.getValue())));
        }
        List<String> lines = new ArrayList<>();
        lines.add("# request_body_types=" + rows.size() + " in_38_02_inventory="
                + rows.values().stream().filter(r -> r.split("\t")[1].equals("true")).count()
                + " (of " + inventoryNames.size() + " names) primitive_exposed=" + primitiveExposed
                + " enum_exposed=" + enumExposed + " enum_constants_with_toString_differing_types=" + enumToStringDiffers);
        lines.add("dto\tin_38_02_inventory\tprimitive_properties\tenum_properties\ttemporal_properties\thandlers");
        lines.addAll(rows.values());
        write(EXPOSURE_OUT, lines);

        // Every enum in the codebase, request side or not: an enum whose toString differs from its
        // name is the only kind the two enum flags can change (write and read).
        List<String> mainEnums = new ArrayList<>();
        List<String> mainEnumsToStringDiffers = new ArrayList<>();
        List<String> testEnumsToStringDiffers = new ArrayList<>();
        org.springframework.core.io.support.PathMatchingResourcePatternResolver resolver =
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        for (org.springframework.core.io.Resource r : resolver.getResources("classpath*:uk/jtoye/core/**/*.class")) {
            String url = r.getURL().toString();
            boolean main = url.contains("/classes/java/main/");
            boolean test = url.contains("/classes/java/test/");
            if (!main && !test) {
                continue;
            }
            String root = main ? "/classes/java/main/" : "/classes/java/test/";
            String name = url.substring(url.indexOf(root) + root.length(), url.length() - ".class".length()).replace('/', '.');
            Class<?> c;
            try {
                c = Class.forName(name, false, getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError ex) {
                continue;
            }
            if (!c.isEnum()) {
                continue;
            }
            boolean differs = false;
            for (Object k : c.getEnumConstants()) {
                differs |= !k.toString().equals(((Enum<?>) k).name());
            }
            if (main) {
                mainEnums.add(c.getName());
                if (differs) {
                    mainEnumsToStringDiffers.add(c.getName());
                }
            } else if (differs) {
                testEnumsToStringDiffers.add(c.getName());
            }
        }
        List<String> enumLines = new ArrayList<>(lines);
        enumLines.add("# main_enums_scanned=" + mainEnums.size() + " main_enums_with_toString_differing_from_name="
                + mainEnumsToStringDiffers + " control_test_enums_with_toString_differing=" + testEnumsToStringDiffers);
        write(EXPOSURE_OUT, enumLines);

        Set<String> walked = rows.values().stream().map(r -> r.split("\t")[0]).collect(Collectors.toSet());
        assertThat(walked).as("every 38-02 inventory DTO is reached by the walk").containsAll(inventoryNames);
        // Positive controls: the property walk sees primitives and enums when they are there, and the
        // enum scan sees a toString override when one exists (this class's own Labelled).
        assertThat(properties(Prims.class)).containsEntry("i", int.class).containsEntry("l", long.class);
        assertThat(properties(PojoPrim.class)).containsEntry("quantity", int.class);
        assertThat(properties(Tagged.class)).containsEntry("tag", Labelled.class);
        assertThat(mainEnums).as("main enums scanned").isNotEmpty();
        assertThat(testEnumsToStringDiffers).contains(Labelled.class.getName());
    }

    // ------------------------------------------------------------------------------ helpers

    @FunctionalInterface
    interface Read {
        Object get() throws Exception;
    }

    private static String outcome(Read read) {
        try {
            Object v = read.get();
            if (v instanceof When w && w.at() != null) {
                return "ACCEPT " + w + " instant=" + w.at().toInstant() + " offset=" + w.at().getOffset();
            }
            return "ACCEPT " + v;
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()).lines().findFirst().orElse("");
            return "REJECT " + e.getClass().getSimpleName() + ": " + (msg.length() > 160 ? msg.substring(0, 160) : msg);
        }
    }

    /** ACCEPT plus value is compared in full; any REJECT is one outcome whatever its message. */
    private static String verdict(String outcome) {
        return outcome.startsWith("REJECT") ? "REJECT" : outcome;
    }

    private static void write(Path out, List<String> lines) throws IOException {
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    /** Collect this type and every uk.jtoye type reachable through its properties. */
    private static void collect(Type generic, String handler, Map<Class<?>, Set<String>> out, Set<Class<?>> seen) {
        for (Class<?> c : rawTypes(generic)) {
            if (!c.getName().startsWith("uk.jtoye.") || c.isEnum() || !seen.add(c)) {
                continue;
            }
            out.computeIfAbsent(c, k -> new TreeSet<>()).add(handler);
            for (Type t : genericProperties(c).values()) {
                collect(t, handler, out, seen);
            }
        }
    }

    private static List<Class<?>> rawTypes(Type t) {
        List<Class<?>> out = new ArrayList<>();
        if (t instanceof Class<?> c) {
            out.add(c.isArray() ? c.getComponentType() : c);
        } else if (t instanceof ParameterizedType pt) {
            out.add((Class<?>) pt.getRawType());
            for (Type a : pt.getActualTypeArguments()) {
                out.addAll(rawTypes(a));
            }
        }
        return out;
    }

    private static Map<String, Type> genericProperties(Class<?> type) {
        Map<String, Type> props = new LinkedHashMap<>();
        if (type.isRecord()) {
            for (RecordComponent rc : type.getRecordComponents()) {
                props.put(rc.getName(), rc.getGenericType());
            }
            return props;
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                    props.putIfAbsent(f.getName(), f.getGenericType());
                }
            }
        }
        return props;
    }

    private static Map<String, Class<?>> properties(Class<?> type) {
        Map<String, Class<?>> props = new LinkedHashMap<>();
        for (Map.Entry<String, Type> e : genericProperties(type).entrySet()) {
            Type t = e.getValue();
            Class<?> raw = t instanceof Class<?> c ? c : t instanceof ParameterizedType pt ? (Class<?>) pt.getRawType() : Object.class;
            if (Collection.class.isAssignableFrom(raw) && t instanceof ParameterizedType pt
                    && pt.getActualTypeArguments()[0] instanceof Class<?> el && el.isEnum()) {
                raw = el;
            }
            props.put(e.getKey(), raw);
        }
        return props;
    }
}
