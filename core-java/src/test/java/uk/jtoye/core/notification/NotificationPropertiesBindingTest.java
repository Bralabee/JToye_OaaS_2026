package uk.jtoye.core.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Issue #516 — the unsubscribe URL must come from CONFIGURATION, and this pins
 * the two links in that chain that a green URL test cannot see.
 *
 * <ol>
 *   <li><b>The key path really binds.</b> Bind the exact
 *       {@code notification.unsubscribe.*} keys an operator would set and assert
 *       the fields change. A field wired to the wrong prefix would keep its Java
 *       default and every other test would still pass, because they never set
 *       the property.</li>
 *   <li><b>{@code application.yml} declares those keys, as {@code ${ENV:default}}.</b>
 *       Read from the CLASSPATH — i.e. the processed resource that ships in the
 *       jar, not the source tree. A typo in a key here is invisible at runtime:
 *       relaxed binding ignores unknown properties, the Java default silently
 *       applies, and the environment variable an operator sets reaches nothing.
 *       That is the exact failure mode #516 is about — a value that looks
 *       configurable but is not the one in force.</li>
 * </ol>
 */
class NotificationPropertiesBindingTest {

    private static final String PREFIX = "notification";

    @Test
    @DisplayName("#516 — the notification.unsubscribe.* keys bind to the fields that compose the URLs")
    void unsubscribeKeysBind() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("notification.unsubscribe.base-url", "https://app.example.test");
        properties.put("notification.unsubscribe.page-path", "/opt-out");
        properties.put("notification.unsubscribe.one-click-base-url", "https://api.example.test");
        properties.put("notification.unsubscribe.one-click-path", "/api/v9/unsub");
        properties.put("notification.unsubscribe.signing-secret", "s3cret");

        ConfigurationPropertySource source = new MapConfigurationPropertySource(properties);
        NotificationProperties bound = new Binder(source)
                .bind(PREFIX, Bindable.of(NotificationProperties.class))
                .orElseThrow(() -> new AssertionError("nothing bound under '" + PREFIX + "'"));

        assertThat(bound.getUnsubscribe().getBaseUrl()).isEqualTo("https://app.example.test");
        assertThat(bound.getUnsubscribe().getPagePath()).isEqualTo("/opt-out");
        assertThat(bound.getUnsubscribe().getOneClickBaseUrl()).isEqualTo("https://api.example.test");
        assertThat(bound.getUnsubscribe().getOneClickPath()).isEqualTo("/api/v9/unsub");
        assertThat(bound.configured()).isTrue();
        assertThat(bound.getUnsubscribe().oneClickConfigured()).isTrue();
        assertThat(bound.toString())
                .as("the redacted toString must never print the signing secret")
                .doesNotContain("s3cret");
    }

    @Test
    @DisplayName("#516 — a blank one-click origin means NOT configured, so nothing one-click is advertised")
    void blankOneClickOriginIsNotConfigured() {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(
                Map.of("notification.unsubscribe.one-click-base-url", "   "));
        NotificationProperties bound = new Binder(source)
                .bind(PREFIX, Bindable.of(NotificationProperties.class))
                .orElseThrow(() -> new AssertionError("nothing bound under '" + PREFIX + "'"));

        assertThat(bound.getUnsubscribe().oneClickConfigured()).isFalse();
    }

    @Test
    @DisplayName("#516 — application.yml (as packaged) declares every unsubscribe key as ${ENV:default}")
    void packagedApplicationYamlDeclaresTheKeys() {
        Map<String, Object> unsubscribe = unsubscribeBlockFromPackagedYaml();

        assertThat(unsubscribe.keySet())
                .as("keys under notification.unsubscribe in the packaged application.yml")
                .contains("base-url", "page-path", "one-click-base-url", "one-click-path", "signing-secret");

        // Every one must be environment-overridable — a bare literal here would be
        // the GLOBAL_RULE_6 violation this issue is a case study in.
        unsubscribe.forEach((key, value) -> assertThat(String.valueOf(value))
                .as("notification.unsubscribe.%s must be a ${ENV:default} placeholder", key)
                .matches("\\$\\{[A-Z0-9_]+:.*}"));

        // The defaults must be the ones the code assumes, or the two drift apart
        // silently: the YAML wins at runtime and the Java default only shows up in
        // unit tests that never load it.
        NotificationProperties javaDefaults = new NotificationProperties();
        assertThat(defaultOf(unsubscribe.get("page-path")))
                .isEqualTo(javaDefaults.getUnsubscribe().getPagePath());
        assertThat(defaultOf(unsubscribe.get("one-click-path")))
                .isEqualTo(javaDefaults.getUnsubscribe().getOneClickPath());
        assertThat(defaultOf(unsubscribe.get("one-click-base-url")))
                .as("an origin default here would put a wrong/loopback host in production mail (D-19)")
                .isEmpty();
    }

    /** The default half of a {@code ${VAR:default}} placeholder. */
    private static String defaultOf(Object placeholder) {
        String s = String.valueOf(placeholder);
        int colon = s.indexOf(':');
        if (!s.startsWith("${") || !s.endsWith("}") || colon < 0) {
            fail("not a ${VAR:default} placeholder: %s", s);
        }
        return s.substring(colon + 1, s.length() - 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> unsubscribeBlockFromPackagedYaml() {
        ClassPathResource resource = new ClassPathResource("application.yml");
        if (!resource.exists()) {
            fail("application.yml is not on the test classpath — this test cannot be satisfied vacuously");
        }
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Object notification = root.get("notification");
            Object unsubscribe = notification instanceof Map ? ((Map<String, Object>) notification).get("unsubscribe") : null;
            if (!(unsubscribe instanceof Map)) {
                fail("no notification.unsubscribe block in the packaged application.yml");
            }
            return (Map<String, Object>) unsubscribe;
        } catch (IOException e) {
            fail("could not read the packaged application.yml: %s", e.getMessage());
            return Map.of();
        }
    }
}
