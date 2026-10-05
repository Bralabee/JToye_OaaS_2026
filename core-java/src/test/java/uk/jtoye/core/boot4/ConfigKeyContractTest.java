package uk.jtoye.core.boot4;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.configurationmetadata.ConfigurationMetadataProperty;
import org.springframework.boot.configurationmetadata.ConfigurationMetadataRepositoryJsonBuilder;
import org.springframework.boot.configurationmetadata.Deprecation;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every Spring Boot configuration key and every auto-configuration exclude in core-java is one
 * that Boot 4 actually honours (Phase 38, plan 38-11, BOOT4-11).
 *
 * <p><b>Why this exists.</b> Boot does not fail on a key it no longer binds: the application
 * starts and the setting silently reverts to its default. The Boot 4 move left 18 such keys in
 * {@code application*.yml} (the Zipkin endpoint, the error-detail levels, prod's log retention),
 * and no test caught one of them. The same is true of a {@code spring.autoconfigure.exclude}
 * naming a Boot-3 class that no longer exists: Boot only rejects an exclude whose class IS on the
 * classpath, so a stale name is a no-op and the auto-configuration runs after all.
 *
 * <p><b>What it checks.</b>
 * <ul>
 *   <li>Every {@code spring.*}, {@code management.*}, {@code server.*} and {@code logging.*} key in
 *       every {@code application*.yml} under core-java's main and test resources is known to the
 *       Boot configuration metadata and is not deprecated at any level. A key that is absent from the
 *       metadata passes only when an ancestor is a Map- or List-typed property
 *       ({@code logging.level.*}, {@code spring.jpa.properties.*}).</li>
 *   <li>Every {@code spring.autoconfigure.exclude} value in those files, and every string literal
 *       naming an {@code org.springframework.boot...AutoConfiguration} class in core-java's test
 *       sources, is listed in an {@code AutoConfiguration.imports} file.</li>
 * </ul>
 *
 * <p><b>Which classpath.</b> The metadata, and the imports for the main yml, are read from a class
 * loader built ONLY from {@code -Djtoye.productionRuntimeClasspath} (set by core-java's
 * {@code tasks.test}). The test classpath carries test auto-configure modules whose metadata could
 * declare a key the shipped jar does not bind. The test fails closed when the property is absent.
 * Test sources and the test-resources yml are judged against the test classpath, which is where they
 * run.
 *
 * <p><b>It cannot pass on nothing.</b> Zero yml files, zero checked keys, zero metadata files or
 * zero exclude literals in the test sources each fail, and each writes a {@code VOID} row into the
 * report, which {@code scripts/check-boot-config-keys.sh} reads as exit 2 rather than as a clean
 * run. The directories scanned for yml can be replaced by the environment variable
 * {@code JTOYE_CONFIG_KEY_DIRS} (path-separator list) to drive that VOID arm.
 *
 * <p><b>The report.</b> {@code core-java/build-local/boot4/config-key-report.tsv}: one row per key,
 * exclude and literal, columns {@code file}, {@code key}, {@code verdict} ({@code OK},
 * {@code UNKNOWN}, {@code DEPRECATED}, {@code EXCLUDE-MISSING} or {@code VOID}) and {@code detail}.
 * It is written in {@code @BeforeAll}, before any assertion, so a red run still leaves it behind.
 */
class ConfigKeyContractTest {

    private static final String PRODUCTION_CLASSPATH_PROPERTY = "jtoye.productionRuntimeClasspath";
    private static final String DIRS_ENV = "JTOYE_CONFIG_KEY_DIRS";
    private static final String IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final Pattern CHECKED_KEY = Pattern.compile("^(spring|management|server|logging)\\..+");
    private static final Pattern EXCLUDE_KEY = Pattern.compile("^spring\\.autoconfigure\\.exclude(\\[\\d+])?$");
    /** A Boot auto-configuration FQCN, as the plan's literal scan defines it. */
    static final Pattern AUTOCONFIG_FQCN =
            Pattern.compile("org\\.springframework\\.boot\\.[a-z.]+\\.[A-Za-z]+AutoConfiguration");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    enum Verdict { OK, UNKNOWN, DEPRECATED, EXCLUDE_MISSING, VOID }

    record Row(String file, String key, Verdict verdict, String detail) {
        String tsv() {
            return file + "\t" + key + "\t" + verdict.name().replace('_', '-') + "\t" + detail;
        }
    }

    private static URLClassLoader production;
    private static Map<String, ConfigurationMetadataProperty> metadata;
    private static int metadataFiles;
    private static Set<String> productionImports;
    private static Set<String> testImports;
    private static Path coreJava;
    private static final List<Path> ymlFiles = new ArrayList<>();
    private static final List<Row> keyRows = new ArrayList<>();
    private static final List<Row> excludeRows = new ArrayList<>();
    private static final List<Row> literalRows = new ArrayList<>();
    private static final List<Row> voidRows = new ArrayList<>();

    @BeforeAll
    static void scan() throws IOException {
        coreJava = locateCoreJava();
        production = productionRuntimeLoader();
        metadata = readMetadata(production);
        productionImports = readImports(production);
        testImports = readImports(Thread.currentThread().getContextClassLoader());

        for (Path dir : scanDirectories()) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().matches("application.*\\.ya?ml"))
                        .sorted()
                        .forEach(ymlFiles::add);
            }
        }
        for (Path yml : ymlFiles) {
            scanYaml(yml);
        }
        scanTestSourceLiterals(coreJava.resolve("src/test/java"));

        if (metadataFiles == 0) {
            voidRows.add(new Row("-", "-", Verdict.VOID, "no spring-configuration-metadata.json on the production classpath"));
        }
        if (ymlFiles.isEmpty()) {
            voidRows.add(new Row("-", "-", Verdict.VOID, "zero application*.yml files in " + scanDirectories()));
        }
        if (keyRows.isEmpty()) {
            voidRows.add(new Row("-", "-", Verdict.VOID, "zero spring/management/server/logging keys checked"));
        }
        if (literalRows.isEmpty()) {
            voidRows.add(new Row("-", "-", Verdict.VOID, "zero AutoConfiguration string literals in src/test/java"));
        }
        writeReport();
    }

    @AfterAll
    static void close() throws IOException {
        if (production != null) {
            production.close();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Instrument controls: the engine can see a good key, a bogus key, a Boot-3 key and a bogus key
    // under a non-map parent. Without these a clean report could be a blind engine.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("positive control: metadata comes from the production classpath and knows spring.datasource.url")
    void metadataIsReadFromTheProductionClasspath() {
        assertThat(metadataFiles).as("spring-configuration-metadata.json files on the production classpath").isGreaterThan(10);
        assertThat(classify("spring.datasource.url").verdict()).isEqualTo(Verdict.OK);
        assertThat(classify("logging.level.uk.jtoye").verdict())
                .as("a key under a Map-typed property (logging.level) is legitimately absent from the metadata")
                .isEqualTo(Verdict.OK);
        assertThat(productionImports)
                .as("the production AutoConfiguration.imports were read")
                .contains("org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration");
    }

    @Test
    @DisplayName("a bogus key is reported unknown")
    void bogusKeyIsUnknown() {
        assertThat(classify("spring.bogus.key").verdict()).isEqualTo(Verdict.UNKNOWN);
    }

    @Test
    @DisplayName("a Boot-3 key Boot 4 no longer binds is reported deprecated, with its replacement")
    void boot3KeyIsDeprecatedWithReplacement() {
        Row row = classify("server.error.include-message");
        assertThat(row.verdict()).isEqualTo(Verdict.DEPRECATED);
        assertThat(row.detail()).contains("replacement=spring.web.error.include-message");
    }

    @Test
    @DisplayName("a bogus key under a NON-map parent is reported unknown")
    void bogusKeyUnderNonMapParentIsUnknown() {
        assertThat(classify("management.endpoint.health.bogus").verdict()).isEqualTo(Verdict.UNKNOWN);
    }

    @Test
    @DisplayName("the literal scan recognises an auto-configuration FQCN and ignores other strings")
    void literalPatternControl() {
        // The Boot-3 name is assembled from pieces so that this file's own literals do not trip the
        // scan below: no single literal here spells a stale auto-configuration name.
        String boot3RedisName = "org.springframework.boot.autoconfigure." + "data.redis." + "Redis" + "AutoConfiguration";
        assertThat(AUTOCONFIG_FQCN.matcher("org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration").find()).isTrue();
        assertThat(AUTOCONFIG_FQCN.matcher(boot3RedisName).find()).isTrue();
        assertThat(AUTOCONFIG_FQCN.matcher("uk.jtoye.core.config.CacheConfig").find()).isFalse();
        assertThat(testImports)
                .as("the Boot-3 name is gone on Boot 4, so an exclude naming it is a no-op")
                .doesNotContain(boot3RedisName)
                .contains("org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration");
    }

    // ---------------------------------------------------------------------------------------------
    // The contract.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the scan saw files and keys (never clean over nothing)")
    void scanIsNotVacuous() {
        assertThat(voidRows).as("VOID: the instrument saw nothing to judge").isEmpty();
        assertThat(ymlFiles).isNotEmpty();
        assertThat(keyRows).isNotEmpty();
        assertThat(literalRows).isNotEmpty();
    }

    @Test
    @DisplayName("every spring/management/server/logging key is known to Boot 4 and not deprecated")
    void everyKeyIsKnownAndNotDeprecated() {
        assertThat(keyRows).as("keys checked").isNotEmpty();
        assertThat(keyRows.stream().filter(r -> r.verdict() != Verdict.OK).map(Row::tsv).toList())
                .as("keys Boot 4 does not bind (file, key, verdict, detail)")
                .isEmpty();
    }

    @Test
    @DisplayName("every spring.autoconfigure.exclude value names an auto-configuration on the relevant classpath")
    void everyYamlExcludeIsAnImportedAutoConfiguration() {
        assertThat(excludeRows.stream().filter(r -> r.verdict() != Verdict.OK).map(Row::tsv).toList())
                .as("excludes that name no AutoConfiguration.imports entry are silent no-ops")
                .isEmpty();
    }

    @Test
    @DisplayName("every AutoConfiguration string literal in the test sources names an imported auto-configuration")
    void everyTestSourceLiteralIsAnImportedAutoConfiguration() {
        assertThat(literalRows).as("the literal scan found nothing; it cannot be trusted").isNotEmpty();
        assertThat(literalRows.stream().filter(r -> r.verdict() != Verdict.OK).map(Row::tsv).toList())
                .as("AutoConfiguration literals that name no AutoConfiguration.imports entry")
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Engine
    // ---------------------------------------------------------------------------------------------

    static Row classify(String rawKey) {
        String key = rawKey.replaceAll("\\[\\d+]", "");
        ConfigurationMetadataProperty property = metadata.get(key);
        if (property == null) {
            String ancestor = key;
            while (ancestor.contains(".")) {
                ancestor = ancestor.substring(0, ancestor.lastIndexOf('.'));
                ConfigurationMetadataProperty parent = metadata.get(ancestor);
                if (parent != null && isMapOrList(parent)) {
                    if (parent.isDeprecated()) {
                        return new Row("", rawKey, Verdict.DEPRECATED, "map ancestor " + ancestor + " " + describe(parent.getDeprecation()));
                    }
                    return new Row("", rawKey, Verdict.OK, "under " + parent.getType() + " " + ancestor);
                }
            }
            return new Row("", rawKey, Verdict.UNKNOWN, "not in the Boot 4 configuration metadata and no Map/List ancestor");
        }
        if (property.isDeprecated()) {
            return new Row("", rawKey, Verdict.DEPRECATED, describe(property.getDeprecation()));
        }
        return new Row("", rawKey, Verdict.OK, property.getType() == null ? "" : property.getType());
    }

    private static boolean isMapOrList(ConfigurationMetadataProperty p) {
        String type = p.getType();
        return type != null && (type.startsWith("java.util.Map") || type.startsWith("java.util.List")
                || type.startsWith("java.util.Set"));
    }

    private static String describe(Deprecation d) {
        if (d == null) {
            return "deprecated";
        }
        return "level=" + (d.getLevel() == null ? "warning" : d.getLevel().name().toLowerCase())
                + " replacement=" + d.getReplacement();
    }

    private static void scanYaml(Path yml) throws IOException {
        String file = coreJava.getParent() == null ? yml.toString() : coreJava.getParent().relativize(yml).toString();
        boolean testSource = yml.toString().contains(File.separator + "src" + File.separator + "test" + File.separator);
        Set<String> imports = testSource ? testImports : productionImports;
        String classpathName = testSource ? "test classpath" : "production classpath";
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(file, new FileSystemResource(yml))) {
            EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
            for (String key : enumerable.getPropertyNames()) {
                if (!CHECKED_KEY.matcher(key).matches()) {
                    continue;
                }
                Row verdict = classify(key);
                keyRows.add(new Row(file, key, verdict.verdict(), verdict.detail()));
                if (EXCLUDE_KEY.matcher(key).matches()) {
                    Object value = enumerable.getProperty(key);
                    for (String fqcn : String.valueOf(value).split(",")) {
                        String name = fqcn.trim();
                        if (name.isEmpty()) {
                            continue;
                        }
                        excludeRows.add(new Row(file, name, imports.contains(name) ? Verdict.OK : Verdict.EXCLUDE_MISSING,
                                (imports.contains(name) ? "listed in AutoConfiguration.imports on the " : "NOT listed in any AutoConfiguration.imports on the ")
                                        + classpathName + " (" + key + ")"));
                    }
                }
            }
        }
    }

    private static void scanTestSourceLiterals(Path testJava) throws IOException {
        if (!Files.isDirectory(testJava)) {
            return;
        }
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(testJava)) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        Path root = coreJava.getParent() == null ? coreJava : coreJava.getParent();
        for (Path source : sources) {
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher literal = STRING_LITERAL.matcher(lines.get(i));
                while (literal.find()) {
                    Matcher fqcn = AUTOCONFIG_FQCN.matcher(literal.group(1));
                    while (fqcn.find()) {
                        String name = fqcn.group();
                        boolean listed = testImports.contains(name);
                        literalRows.add(new Row(root.relativize(source) + ":" + (i + 1), name,
                                listed ? Verdict.OK : Verdict.EXCLUDE_MISSING,
                                listed ? "listed in AutoConfiguration.imports on the test classpath"
                                        : "NOT listed in any AutoConfiguration.imports on the test classpath"));
                    }
                }
            }
        }
    }

    private static List<Path> scanDirectories() {
        String override = System.getenv(DIRS_ENV);
        if (override != null && !override.isBlank()) {
            List<Path> dirs = new ArrayList<>();
            for (String entry : override.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    dirs.add(Path.of(entry).toAbsolutePath());
                }
            }
            return dirs;
        }
        return List.of(coreJava.resolve("src/main/resources"), coreJava.resolve("src/test/resources"));
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

    private static URLClassLoader productionRuntimeLoader() throws MalformedURLException {
        String classpath = System.getProperty(PRODUCTION_CLASSPATH_PROPERTY);
        // Fail closed: without the property this class could only judge the test classpath.
        assertThat(classpath).as("-D" + PRODUCTION_CLASSPATH_PROPERTY
                + " is set by core-java/build.gradle.kts tasks.test; run this class through Gradle").isNotBlank();
        List<URL> urls = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        return new URLClassLoader("production-runtime", urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static Map<String, ConfigurationMetadataProperty> readMetadata(ClassLoader loader) throws IOException {
        ConfigurationMetadataRepositoryJsonBuilder builder = ConfigurationMetadataRepositoryJsonBuilder.create();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(loader);
        int count = 0;
        for (String pattern : List.of("classpath*:META-INF/spring-configuration-metadata.json",
                "classpath*:META-INF/additional-spring-configuration-metadata.json")) {
            for (Resource resource : resolver.getResources(pattern)) {
                try (InputStream in = resource.getInputStream()) {
                    builder.withJsonResource(in, StandardCharsets.UTF_8);
                }
                if (pattern.contains("/spring-configuration-metadata.json")) {
                    count++;
                }
            }
        }
        metadataFiles = count;
        return builder.build().getAllProperties();
    }

    private static Set<String> readImports(ClassLoader loader) throws IOException {
        Set<String> names = new TreeSet<>();
        Enumeration<URL> resources = loader.getResources(IMPORTS);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String name = line.contains("#") ? line.substring(0, line.indexOf('#')).trim() : line.trim();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    private static void writeReport() throws IOException {
        Path report = coreJava.resolve("build-local/boot4/config-key-report.tsv");
        Files.createDirectories(report.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("file\tkey\tverdict\tdetail");
        Stream.of(voidRows, keyRows, excludeRows, literalRows).flatMap(List::stream).map(Row::tsv).forEach(lines::add);
        Files.write(report, lines, StandardCharsets.UTF_8);
    }
}
