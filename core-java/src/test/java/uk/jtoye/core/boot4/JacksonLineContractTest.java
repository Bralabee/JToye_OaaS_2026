package uk.jtoye.core.boot4;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Jackson line of Phase 38 at its end state (D-01, D-02; BOOT4-02, BOOT4-04; plan 38-12).
 *
 * <p><b>Why this exists.</b> 38-03 kept Boot's Jackson-2 auto-configuration module alive behind an
 * {@code INTERIM-JACKSON2-BRIDGE} build line so the tree stayed bootable while 38-06..38-10 moved
 * the main code and 38-19 moved the tests to Jackson 3. 38-12 removed that line. Nothing else would
 * notice it coming back: a Jackson-2 mapper bean beside Boot's Jackson-3 {@code JsonMapper} boots
 * fine, every test stays green, and the application quietly has two JSON behaviours again. The same
 * is true of a main class going back to Jackson 2, of the deprecated converter switch, and of the
 * "classic" starter, whose gRPC auto-configuration throws {@code NoClassDefFoundError} on any filter
 * chain that keeps CSRF on (D-02). This class is the invariant 38-03 promised.
 *
 * <p><b>What it checks.</b>
 * <ul>
 *   <li><b>Production classpath.</b> No jar on {@code -Djtoye.productionRuntimeClasspath} (the
 *       resolved {@code runtimeClasspath}, set by core-java's {@code tasks.test}) has a name starting
 *       with one of {@link #FORBIDDEN_JAR_PREFIXES}. Positive controls: Boot's core jar and a
 *       transitive Jackson-2 databind jar are both listed (Jackson 2 stays transitively for
 *       springdoc's swagger-core and others, which proves the list carries transitive jars). The
 *       property absent fails closed.</li>
 *   <li><b>Main code.</b> No file under {@code src/main/java} uses Jackson 2: no
 *       {@code com.fasterxml.jackson.*} package other than {@code com.fasterxml.jackson.annotation}
 *       (Jackson 3 still reads those annotations), and no package-qualified Spring
 *       {@code Jackson2*}, {@code MappingJackson2*} or {@code GenericJackson2*} adapter. Positive
 *       control: the same walk finds at least one annotation import, so it read the tree and the
 *       annotation exemption is exercised.</li>
 *   <li><b>Configuration.</b> No {@code application*.yml}/{@code .yaml}/{@code .properties} under
 *       {@code src/main/resources} or {@code src/test/resources} sets a {@code preferred-json-mapper}
 *       to {@code jackson2} or any key with a {@code jackson2} segment ({@code spring.jackson2.*}).
 *       Keys are compared in relaxed form, so camelCase and underscores are caught as well.</li>
 *   <li><b>Test code.</b> The test files that use Jackson 2 (the same pattern as the main scan) are
 *       EXACTLY {@link #DELIBERATE_JACKSON2_LIST}, the closed list 38-19 recorded in
 *       {@code evidence/38-19-test-jackson3-sweep.txt}, and each carries a
 *       {@code DELIBERATE-JACKSON2: <reason>} line. Any other test on Jackson 2 fails, and so does a
 *       listed file that no longer uses it (the list shrinks with the tree, it never goes stale).</li>
 * </ul>
 *
 * <p><b>Why the adapters are in the pattern.</b> A test can reach Jackson 2 with no
 * {@code com.fasterxml} import at all, through Spring's deprecated adapter classes
 * ({@code Jackson2*} message converters, the {@code Jackson2*} object-mapper builder, the
 * {@code GenericJackson2*} Redis serializer). 38-19 found five such test files that an import-only
 * scan could not see; {@code AmqpJackson2CompatibilityTest} is on the list only through one. The
 * adapter half matches the package-qualified name an import or an FQCN use must carry, because a
 * bare class name also matches Javadoc history.
 *
 * <p><b>It can fail</b> ({@code evidence/38-12-jackson-end-state.txt}): it was RED on the bridge
 * line before 38-12 removed it, and arms that re-add the bridge, a main-code Jackson-2 import, the
 * converter switch, and a Spring Jackson-2 adapter in a test file each turn it red, naming the
 * offender. This file deliberately names no Jackson-2 package or adapter by its qualified name, so it
 * never matches its own scan.
 */
class JacksonLineContractTest {

    private static final String PRODUCTION_CLASSPATH_PROPERTY = "jtoye.productionRuntimeClasspath";

    /**
     * Jar-name prefixes that must never be on the production runtime classpath: Boot's Jackson-2
     * auto-configuration module (D-01), the classic starter and auto-configure modules, and Boot's
     * gRPC modules (D-02). The two starter spellings are added beside the plan's four literals.
     */
    static final List<String> FORBIDDEN_JAR_PREFIXES = List.of(
            "spring-boot-jackson2",
            "spring-boot-starter-jackson2",
            "spring-boot-starter-classic",
            "spring-boot-autoconfigure-classic",
            "spring-boot-grpc",
            "spring-boot-starter-grpc");

    /**
     * The closed DELIBERATE-JACKSON2-LIST, verbatim from 38-19's evidence: repository-relative paths
     * of the only test files allowed to use Jackson 2.
     * <ul>
     *   <li>AmqpJackson2CompatibilityTest: emulates Boot-3.5 pods during a rolling deploy; a Boot-3.5
     *       Jackson-2 message converter reads every message the Boot-4 converter writes (38-08).</li>
     *   <li>OutboxPayloadCompatibilityTest: emulates Boot-3.5 pods during a rolling deploy; a mapper
     *       built like Boot 3.5's reads the outbox rows Boot 4 writes (38-08).</li>
     *   <li>OpenApiSnapshotTest: byte-stable normalizer of springdoc's swagger-core (Jackson-2)
     *       output; its writer decides the committed snapshot's bytes, and 38-14 regenerates that
     *       snapshot.</li>
     * </ul>
     */
    static final Set<String> DELIBERATE_JACKSON2_LIST = Set.of(
            "core-java/src/test/java/uk/jtoye/core/boot4/AmqpJackson2CompatibilityTest.java",
            "core-java/src/test/java/uk/jtoye/core/boot4/OutboxPayloadCompatibilityTest.java",
            "core-java/src/test/java/uk/jtoye/core/integration/OpenApiSnapshotTest.java");

    /** A Jackson-2 use: any com.fasterxml.jackson package but annotation, or a qualified Spring Jackson-2 adapter. */
    static final Pattern JACKSON2_USE = Pattern.compile(
            "com\\.fasterxml\\.jackson\\.(?!annotation\\b)[a-z]"
                    + "|org\\.springframework\\.[a-z0-9.]+\\.(?:Jackson2|MappingJackson2|GenericJackson2)[A-Za-z]*");

    private static final Pattern ANNOTATION_IMPORT = Pattern.compile("^import com\\.fasterxml\\.jackson\\.annotation\\.");
    private static final Pattern DELIBERATE_REASON = Pattern.compile("DELIBERATE-JACKSON2:\\s*\\S.{10,}");
    private static final Pattern CONFIG_FILE = Pattern.compile("application.*\\.(yml|yaml|properties)");
    private static final Pattern BOOT_CORE_JAR = Pattern.compile("spring-boot-4\\.[0-9.]+\\.jar");
    private static final Pattern JACKSON2_DATABIND_JAR = Pattern.compile("jackson-databind-2\\.[0-9.]+\\.jar");

    private static Path coreJava;
    private static Path repoRoot;

    @BeforeAll
    static void locate() {
        coreJava = locateCoreJava();
        repoRoot = coreJava.getParent() == null ? coreJava : coreJava.getParent();
    }

    @Test
    @DisplayName("no Jackson-2, classic or gRPC auto-configuration module is on the production runtime classpath")
    void productionClasspathCarriesNoForbiddenModule() {
        String classpath = System.getProperty(PRODUCTION_CLASSPATH_PROPERTY);
        // Fail closed: without the property this test could only judge the test classpath.
        assertThat(classpath).as("-D" + PRODUCTION_CLASSPATH_PROPERTY
                + " is set by core-java/build.gradle.kts tasks.test; run this class through Gradle").isNotBlank();
        List<String> jarNames = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                jarNames.add(Path.of(entry).getFileName().toString());
            }
        }
        assertThat(jarNames)
                .as("positive control: Boot's core jar must be listed, or the list is not the production classpath")
                .anyMatch(name -> BOOT_CORE_JAR.matcher(name).matches());
        assertThat(jarNames)
                .as("positive control: a transitive Jackson-2 databind jar must be listed, or the list does not "
                        + "carry transitive jars and the absence below proves nothing")
                .anyMatch(name -> JACKSON2_DATABIND_JAR.matcher(name).matches());

        List<String> forbidden = jarNames.stream()
                .filter(name -> FORBIDDEN_JAR_PREFIXES.stream().anyMatch(name::startsWith))
                .sorted()
                .toList();
        assertThat(forbidden)
                .as("forbidden module jar(s) on the production runtime classpath. A spring-boot-jackson2 jar "
                        + "means a Jackson-2 ObjectMapper bean and converters beside Boot's Jackson-3 JsonMapper "
                        + "(D-01: the INTERIM-JACKSON2-BRIDGE line was removed by 38-12, do not re-add it); a classic "
                        + "or gRPC jar breaks CSRF-enabled filter chains (D-02)")
                .isEmpty();
    }

    @Test
    @DisplayName("main code uses no Jackson 2 (annotations excepted), and the scan read the tree")
    void mainCodeHasNoJackson2Use() throws IOException {
        List<Path> sources = javaSources(coreJava.resolve("src/main/java"));
        assertThat(sources).as("no main sources found under " + coreJava.resolve("src/main/java")).isNotEmpty();

        TreeMap<String, List<String>> offenders = new TreeMap<>();
        int annotationImports = 0;
        for (Path source : sources) {
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (ANNOTATION_IMPORT.matcher(lines.get(i)).find()) {
                    annotationImports++;
                }
                if (JACKSON2_USE.matcher(lines.get(i)).find()) {
                    offenders.computeIfAbsent(relative(source), k -> new ArrayList<>())
                            .add((i + 1) + ": " + lines.get(i).strip());
                }
            }
        }
        assertThat(annotationImports)
                .as("positive control: the main tree has com.fasterxml.jackson.annotation imports, and the walk "
                        + "found none, so it did not read the tree and its zero below would prove nothing")
                .isPositive();
        assertThat(offenders)
                .as("main code must be on Jackson 3 (tools.jackson; D-01, BOOT4-04). Inject Boot's "
                        + "tools.jackson.databind.json.JsonMapper bean instead of each use listed here")
                .isEmpty();
    }

    @Test
    @DisplayName("no application config sets the Jackson-2 converter switch or a jackson2 key, and the scan read it")
    void configurationSetsNoJackson2Switch() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path dir : List.of(coreJava.resolve("src/main/resources"), coreJava.resolve("src/test/resources"))) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> CONFIG_FILE.matcher(p.getFileName().toString()).matches())
                            .sorted()
                            .forEach(files::add);
                }
            }
        }
        assertThat(files.stream().map(JacksonLineContractTest::relative))
                .as("positive control: the base application.yml and the test-resources application-test.yml must be scanned")
                .contains("core-java/src/main/resources/application.yml",
                        "core-java/src/test/resources/application-test.yml");

        List<String> violations = new ArrayList<>();
        Set<String> keys = new TreeSet<>();
        for (Path file : files) {
            for (PropertySource<?> source : load(relative(file), new FileSystemResource(file))) {
                keys.addAll(List.of(((EnumerablePropertySource<?>) source).getPropertyNames()));
                violations.addAll(jackson2Settings(relative(file), source));
            }
        }
        assertThat(keys)
                .as("positive control: the loader must read real keys out of the files, or zero violations proves nothing")
                .contains("spring.application.name");
        assertThat(violations)
                .as("D-01: do not set the deprecated Jackson-2 converter switch or any jackson2 key; "
                        + "the application runs on Boot's Jackson-3 JsonMapper only")
                .isEmpty();
    }

    @Test
    @DisplayName("control: the config check flags the switch and jackson2 keys in every relaxed spelling, and nothing else")
    void configCheckCanFail() throws IOException {
        String yaml = """
                spring:
                  http:
                    converters:
                      preferred-json-mapper: jackson2
                    codecs:
                      preferredJsonMapper: JACKSON2
                  jackson2:
                    date-format: yyyy
                  jackson:
                    use-jackson2-defaults: false
                  websocket:
                    messaging:
                      preferred_json_mapper: jackson
                management:
                  endpoints:
                    jackson2:
                      isolated-object-mapper: false
                """;
        List<String> flagged = new ArrayList<>();
        for (PropertySource<?> source : load("control.yml", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)))) {
            flagged.addAll(jackson2Settings("control.yml", source));
        }
        assertThat(flagged).containsExactlyInAnyOrder(
                "control.yml: spring.http.converters.preferred-json-mapper=jackson2",
                "control.yml: spring.http.codecs.preferredJsonMapper=JACKSON2",
                "control.yml: spring.jackson2.date-format=yyyy",
                "control.yml: management.endpoints.jackson2.isolated-object-mapper=false");
    }

    @Test
    @DisplayName("the test files on Jackson 2 are exactly the closed DELIBERATE-JACKSON2 list, each with its reason")
    void testCodeJackson2UsersAreExactlyTheAllowlist() throws IOException {
        List<Path> sources = javaSources(coreJava.resolve("src/test/java"));
        assertThat(sources).as("no test sources found under " + coreJava.resolve("src/test/java")).isNotEmpty();

        TreeMap<String, List<String>> users = new TreeMap<>();
        TreeSet<String> withReason = new TreeSet<>();
        for (Path source : sources) {
            String file = relative(source);
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (JACKSON2_USE.matcher(lines.get(i)).find()) {
                    users.computeIfAbsent(file, k -> new ArrayList<>()).add((i + 1) + ": " + lines.get(i).strip());
                }
                if (DELIBERATE_REASON.matcher(lines.get(i)).find()) {
                    withReason.add(file);
                }
            }
        }

        TreeMap<String, List<String>> unlisted = new TreeMap<>(users);
        unlisted.keySet().removeAll(DELIBERATE_JACKSON2_LIST);
        assertThat(unlisted)
                .as("test file(s) on Jackson 2 outside the closed DELIBERATE-JACKSON2 list (38-19). Move them to "
                        + "Boot's tools.jackson JsonMapper or the Jackson-3 Spring adapters; the list is not widened "
                        + "to make a test pass")
                .isEmpty();
        assertThat(new TreeSet<>(users.keySet()))
                .as("every listed file must still use Jackson 2 (positive control for both halves of the pattern: "
                        + "AmqpJackson2CompatibilityTest matches only through a Spring adapter); a listed file that "
                        + "moved to Jackson 3 comes off the list")
                .isEqualTo(new TreeSet<>(DELIBERATE_JACKSON2_LIST));
        assertThat(withReason)
                .as("each listed file carries a 'DELIBERATE-JACKSON2: <reason>' line")
                .containsAll(DELIBERATE_JACKSON2_LIST);
    }

    /** Every setting in {@code source} that turns on Jackson 2, as {@code file: key=value}; keys compared in relaxed form. */
    static List<String> jackson2Settings(String file, PropertySource<?> source) {
        List<String> found = new ArrayList<>();
        EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
        for (String key : enumerable.getPropertyNames()) {
            String relaxed = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replaceAll("\\[\\d+]", "");
            String[] segments = relaxed.split("\\.");
            String value = String.valueOf(enumerable.getProperty(key)).trim();
            boolean jackson2Segment = Stream.of(segments).anyMatch("jackson2"::equals);
            boolean jackson2Mapper = segments[segments.length - 1].equals("preferredjsonmapper")
                    && value.toLowerCase(Locale.ROOT).equals("jackson2");
            if (jackson2Segment || jackson2Mapper) {
                found.add(file + ": " + key + "=" + value);
            }
        }
        return found;
    }

    private static List<PropertySource<?>> load(String name, Resource resource) throws IOException {
        PropertySourceLoader loader = name.endsWith(".properties")
                ? new PropertiesPropertySourceLoader() : new YamlPropertySourceLoader();
        return loader.load(name, resource);
    }

    private static List<Path> javaSources(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String relative(Path path) {
        return repoRoot.relativize(path.toAbsolutePath()).toString().replace(File.separatorChar, '/');
    }

    private static Path locateCoreJava() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(cwd, cwd.resolve("core-java"))) {
            if (Files.isRegularFile(candidate.resolve("src/main/resources/application.yml"))
                    && Files.isRegularFile(candidate.resolve("build.gradle.kts"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot locate core-java from " + cwd + "; run this class through Gradle");
    }
}
