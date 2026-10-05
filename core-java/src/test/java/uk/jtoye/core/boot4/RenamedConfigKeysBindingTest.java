package uk.jtoye.core.boot4;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.autoconfigure.web.ErrorProperties.IncludeAttribute;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.zipkin.autoconfigure.ZipkinProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.SoftAssertions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keys 38-11 renamed are BOUND at runtime, per profile, with the values they had on Boot 3.5
 * (Phase 38, plan 38-11, BOOT4-11; threats T-38-29 and T-38-30).
 *
 * <p>{@link ConfigKeyContractTest} proves no key is unknown or deprecated. That is a statement about
 * names. This class proves the renamed names carry the values: it builds the environment Boot builds
 * for a profile (the profile yml first, then {@code application.yml}, both through Boot's own
 * {@link YamlPropertySourceLoader}) and binds each renamed key with Boot's {@link Binder} into the
 * type Boot binds it to.
 *
 * <p><b>Why "bound" is asserted, not only the value.</b> Several Boot-3.5 values equal Boot's
 * defaults: prod's {@code NEVER} error detail, the Zipkin default endpoint, the 10MB file size. A
 * value-only assertion on those passes when the key is silently ignored. Every case therefore also
 * asserts that the key itself bound ({@link BindResult#isBound()}), which is exactly what a Boot-3
 * name does not do on Boot 4.
 *
 * <p>The host environment and system properties are removed from the environment, so a
 * {@code ZIPKIN_ENDPOINT} set on a CI runner cannot change the answer. Placeholders resolve from a
 * fixed map. The yml directory defaults to core-java's {@code src/main/resources} and can be
 * replaced by the environment variable {@code JTOYE_BINDING_YML_DIR} (used once, to run this class
 * against the pre-rename files and watch it fail).
 */
class RenamedConfigKeysBindingTest {

    private static final String DIR_ENV = "JTOYE_BINDING_YML_DIR";
    private static final String ZIPKIN_DEFAULT = "http://localhost:9411/api/v2/spans";
    private static final String ZIPKIN_SET = "http://zipkin.test:9411/api/v2/spans";

    // ---------------------------------------------------------------------------------------------
    // spring.web.error.* (was server.error.*)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("base: error message and binding errors ALWAYS")
    void baseErrorDetail() throws IOException {
        SoftAssertions softly = new SoftAssertions();
        Binder binder = binder(null, Map.of());
        assertBound(softly, binder, "spring.web.error.include-message", "spring.web.error.include-binding-errors");
        ErrorProperties error = binder.bind("spring.web.error", ErrorProperties.class).orElseGet(ErrorProperties::new);
        softly.assertThat(error.getIncludeMessage()).as("include-message").isEqualTo(IncludeAttribute.ALWAYS);
        softly.assertThat(error.getIncludeBindingErrors()).as("include-binding-errors").isEqualTo(IncludeAttribute.ALWAYS);
        softly.assertAll();
    }

    @Test
    @DisplayName("staging: message and binding errors ALWAYS, stacktrace ON_PARAM, exception false")
    void stagingErrorDetail() throws IOException {
        SoftAssertions softly = new SoftAssertions();
        Binder binder = binder("staging", Map.of());
        assertBound(softly, binder, "spring.web.error.include-message", "spring.web.error.include-binding-errors",
                "spring.web.error.include-stacktrace", "spring.web.error.include-exception");
        ErrorProperties error = binder.bind("spring.web.error", ErrorProperties.class).orElseGet(ErrorProperties::new);
        softly.assertThat(error.getIncludeMessage()).as("include-message").isEqualTo(IncludeAttribute.ALWAYS);
        softly.assertThat(error.getIncludeBindingErrors()).as("include-binding-errors").isEqualTo(IncludeAttribute.ALWAYS);
        softly.assertThat(error.getIncludeStacktrace()).as("include-stacktrace").isEqualTo(IncludeAttribute.ON_PARAM);
        softly.assertThat(error.isIncludeException()).as("include-exception").isFalse();
        softly.assertAll();
    }

    @Test
    @DisplayName("prod: message, binding errors and stacktrace NEVER, exception false, and every key bound")
    void prodErrorDetail() throws IOException {
        SoftAssertions softly = new SoftAssertions();
        Binder binder = binder("prod", Map.of());
        // NEVER/false are also Boot's defaults, so the bound check is what makes this case able to fail.
        assertBound(softly, binder, "spring.web.error.include-message", "spring.web.error.include-binding-errors",
                "spring.web.error.include-stacktrace", "spring.web.error.include-exception");
        ErrorProperties error = binder.bind("spring.web.error", ErrorProperties.class).orElseGet(ErrorProperties::new);
        softly.assertThat(error.getIncludeMessage()).as("include-message").isEqualTo(IncludeAttribute.NEVER);
        softly.assertThat(error.getIncludeBindingErrors()).as("include-binding-errors").isEqualTo(IncludeAttribute.NEVER);
        softly.assertThat(error.getIncludeStacktrace()).as("include-stacktrace").isEqualTo(IncludeAttribute.NEVER);
        softly.assertThat(error.isIncludeException()).as("include-exception").isFalse();
        softly.assertAll();
    }

    // ---------------------------------------------------------------------------------------------
    // management.tracing.export.zipkin.endpoint (was management.zipkin.tracing.endpoint)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Zipkin endpoint: the placeholder default when ZIPKIN_ENDPOINT is unset, in every profile")
    void zipkinEndpointDefault() throws IOException {
        SoftAssertions softly = new SoftAssertions();
        for (String profile : new String[] {null, "staging", "prod"}) {
            Binder binder = binder(profile, Map.of());
            assertBound(softly, binder, "management.tracing.export.zipkin.endpoint");
            ZipkinProperties zipkin = binder.bind("management.tracing.export.zipkin", ZipkinProperties.class)
                    .orElseGet(ZipkinProperties::new);
            softly.assertThat(zipkin.getEndpoint()).as("profile %s", profile).isEqualTo(ZIPKIN_DEFAULT);
        }
        softly.assertAll();
    }

    @Test
    @DisplayName("Zipkin endpoint: ZIPKIN_ENDPOINT drives it when set, in every profile")
    void zipkinEndpointFromEnvironment() throws IOException {
        SoftAssertions softly = new SoftAssertions();
        for (String profile : new String[] {null, "staging", "prod"}) {
            Binder binder = binder(profile, Map.of("ZIPKIN_ENDPOINT", ZIPKIN_SET));
            ZipkinProperties zipkin = binder.bind("management.tracing.export.zipkin", ZipkinProperties.class)
                    .orElseGet(ZipkinProperties::new);
            softly.assertThat(zipkin.getEndpoint()).as("profile %s", profile).isEqualTo(ZIPKIN_SET);
        }
        softly.assertAll();
    }

    // ---------------------------------------------------------------------------------------------
    // logging.logback.rollingpolicy.* (was logging.file.max-size|max-history|total-size-cap)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("prod log retention: 30 days, 1GB total, 10MB files")
    void prodLogRetention() throws IOException {
        SoftAssertions softly = new SoftAssertions();
        assertRetention(softly, binder("prod", Map.of()), 30, DataSize.ofGigabytes(1), DataSize.ofMegabytes(10));
        softly.assertAll();
    }

    @Test
    @DisplayName("staging log retention: 15 days, 500MB total, 10MB files")
    void stagingLogRetention() throws IOException {
        SoftAssertions softly = new SoftAssertions();
        assertRetention(softly, binder("staging", Map.of()), 15, DataSize.ofMegabytes(500), DataSize.ofMegabytes(10));
        softly.assertAll();
    }

    // ---------------------------------------------------------------------------------------------
    // staging's deleted Boot-2 prometheus key: the base key still applies
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("staging: the Prometheus registry stays enabled through base's management.prometheus.metrics.export.enabled")
    void stagingPrometheusExportEnabled() throws IOException {
        SoftAssertions softly = new SoftAssertions();
        Binder binder = binder("staging", Map.of());
        softly.assertThat(binder.bind("management.prometheus.metrics.export.enabled", Boolean.class).orElse(null)).isTrue();
        softly.assertAll();
    }

    // ---------------------------------------------------------------------------------------------

    private static void assertRetention(SoftAssertions softly, Binder binder, int maxHistory, DataSize totalSizeCap, DataSize maxFileSize) {
        assertBound(softly, binder, "logging.logback.rollingpolicy.max-history", "logging.logback.rollingpolicy.total-size-cap",
                "logging.logback.rollingpolicy.max-file-size");
        softly.assertThat(binder.bind("logging.logback.rollingpolicy.max-history", Integer.class).orElse(null)).as("max-history").isEqualTo(maxHistory);
        softly.assertThat(binder.bind("logging.logback.rollingpolicy.total-size-cap", DataSize.class).orElse(null)).as("total-size-cap").isEqualTo(totalSizeCap);
        softly.assertThat(binder.bind("logging.logback.rollingpolicy.max-file-size", DataSize.class).orElse(null)).as("max-file-size").isEqualTo(maxFileSize);
    }

    private static void assertBound(SoftAssertions softly, Binder binder, String... keys) {
        for (String key : keys) {
            softly.assertThat(binder.bind(key, String.class).isBound())
                    .as("%s must be bound; an unbound key means Boot uses its default", key)
                    .isTrue();
        }
    }

    /** The environment Boot builds for {@code profile}: application-{profile}.yml, then application.yml. */
    private static Binder binder(String profile, Map<String, Object> placeholders) throws IOException {
        Path dir = ymlDirectory();
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        if (profile != null) {
            load(sources, dir.resolve("application-" + profile + ".yml"));
        }
        load(sources, dir.resolve("application.yml"));
        sources.addLast(new MapPropertySource("placeholders", placeholders));
        return Binder.get(environment);
    }

    private static void load(MutablePropertySources sources, Path yml) throws IOException {
        assertThat(yml).as("yml under test").isRegularFile();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load(yml.getFileName().toString(), new FileSystemResource(yml));
        assertThat(loaded).as("%s produced no property source", yml).isNotEmpty();
        loaded.forEach(sources::addLast);
    }

    private static Path ymlDirectory() {
        String override = System.getenv(DIR_ENV);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        Path cwd = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(cwd, cwd.resolve("core-java"))) {
            Path resources = candidate.resolve("src/main/resources");
            if (Files.isRegularFile(resources.resolve("application.yml")) && Files.isRegularFile(candidate.resolve("build.gradle.kts"))) {
                return resources;
            }
        }
        throw new IllegalStateException("cannot locate core-java/src/main/resources from " + cwd);
    }
}
