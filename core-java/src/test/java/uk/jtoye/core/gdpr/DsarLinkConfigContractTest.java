package uk.jtoye.core.gdpr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * No runtime may email a DSAR link that points at the API or at another runtime's host (#839, D-04,
 * 31.1-20).
 *
 * <p>The verification link used to point at the API in every runtime: {@code localhost:8080} on
 * compose (where nothing listens, so no request lodged there could ever be confirmed) and
 * {@code https://api.olajay.co.uk/...} in the k8s environments (which answers raw JSON to a person
 * who clicks it). Both DSAR links now open web pages — {@code /data-request/confirm} for the
 * verification token and {@code /data-request/download} for the Article 15 export (31.1-16/17) — and
 * each runtime must point them at ITS OWN web origin, the same origin its unsubscribe and tracking
 * links already use.
 *
 * <p>Plain unit test over the files that configure each runtime: the application.yml default (what
 * a runtime that supplies nothing gets), the compose core-java environment, the k8s base configmap
 * and each overlay's patch, and the two rendered goldens (what kustomize actually ships). Every
 * lookup fails closed: a missing file, document or key is a failure, never a skipped runtime.
 */
class DsarLinkConfigContractTest {

    static final String CONFIRM_PATH = "/data-request/confirm";
    static final String DOWNLOAD_PATH = "/data-request/download";

    private static final Pattern SPRING_PLACEHOLDER = Pattern.compile("^\\$\\{[A-Z0-9_]+:(.*)}$");
    private static final Pattern COMPOSE_PLACEHOLDER = Pattern.compile("^\\$\\{[A-Z0-9_]+:-(.*)}$");

    /** The three values that matter in one runtime, and where they came from. */
    record Links(String verify, String download, String webOrigin) {
    }

    enum Runtime {
        APPLICATION_YML_DEFAULT("core-java/src/main/resources/application.yml"),
        COMPOSE("docker-compose.full-stack.yml"),
        K8S_BASE("k8s/base/configmap.yaml"),
        K8S_STAGING("k8s/staging/configmap-patch.yaml"),
        K8S_PRODUCTION("k8s/production/configmap-patch.yaml"),
        K8S_LOCAL("k8s/local/configmap-patch.yaml"),
        GOLDEN_STAGING("k8s/goldens/staging.yaml"),
        GOLDEN_PRODUCTION("k8s/goldens/production.yaml");

        final String file;

        Runtime(String file) {
            this.file = file;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Runtime.class)
    @DisplayName("Both DSAR links open the runtime's own web pages, never the API")
    void dsarLinksOpenThisRuntimesWebPages(Runtime runtime) throws IOException {
        Links links = read(runtime, IntegrationTestSupport.locateRepoFile(runtime.file));

        assertThat(links.verify())
                .as("%s: the verification link base (#839)", runtime.file)
                .doesNotContain("/api/")
                .endsWith(CONFIRM_PATH)
                .doesNotContain("?")
                .doesNotContain("#");
        assertThat(links.download())
                .as("%s: the Article 15 download link base", runtime.file)
                .doesNotContain("/api/")
                .endsWith(DOWNLOAD_PATH)
                .doesNotContain("?")
                .doesNotContain("#");
        assertThat(origin(links.verify()))
                .as("%s: the verification link must share the runtime's web origin %s", runtime.file,
                        links.webOrigin())
                .isEqualTo(origin(links.webOrigin()));
        assertThat(origin(links.download()))
                .as("%s: the download link must share the runtime's web origin %s", runtime.file,
                        links.webOrigin())
                .isEqualTo(origin(links.webOrigin()));
    }

    @Test
    @DisplayName("A missing runtime file fails the contract rather than skipping it")
    void aMissingFileFailsClosed() {
        assertThatThrownBy(() -> IntegrationTestSupport.locateRepoFile("k8s/nowhere/configmap-patch.yaml"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("A runtime that lacks a key fails the contract rather than passing by absence")
    void aMissingKeyFailsClosed() {
        assertThatThrownBy(() -> requireString(Map.of(), "gdpr.dsar.verify-base-url", "a ConfigMap"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("gdpr.dsar.verify-base-url");
    }

    // ---- reading each runtime ------------------------------------------------------------------

    private static Links read(Runtime runtime, Path file) throws IOException {
        return switch (runtime) {
            case APPLICATION_YML_DEFAULT -> readApplicationYml(file);
            case COMPOSE -> readCompose(file);
            default -> readConfigMap(file);
        };
    }

    /** The in-code default each key falls back to when the environment supplies nothing. */
    private static Links readApplicationYml(Path file) throws IOException {
        Map<String, Object> root = single(loadAll(file), file);
        Map<String, Object> dsar = map(map(map(root, "jtoye", file), "gdpr", file), "dsar", file);
        Map<String, Object> email = map(map(root, "notification", file), "email", file);
        return new Links(
                fallback(SPRING_PLACEHOLDER, requireString(dsar, "verify-base-url", file.toString())),
                fallback(SPRING_PLACEHOLDER, requireString(dsar, "export-download-base-url", file.toString())),
                fallback(SPRING_PLACEHOLDER, requireString(email, "tracking-base-url", file.toString())));
    }

    /** The core-java service's environment, with any {@code ${VAR:-default}} resolved to its default. */
    private static Links readCompose(Path file) throws IOException {
        Map<String, Object> root = single(loadAll(file), file);
        Map<String, Object> env = map(map(map(root, "services", file), "core-java", file), "environment", file);
        return new Links(
                composeValue(requireString(env, "DSAR_VERIFY_BASE_URL", file.toString())),
                composeValue(requireString(env, "DSAR_EXPORT_DOWNLOAD_BASE_URL", file.toString())),
                composeValue(requireString(env, "NOTIFICATION_EMAIL_TRACKING_BASE_URL", file.toString())));
    }

    /**
     * The one {@code app-config} ConfigMap in a base file, an overlay patch or a rendered golden
     * (kustomize may suffix the name with a hash, so the prefix is matched).
     */
    private static Links readConfigMap(Path file) throws IOException {
        List<Map<String, Object>> appConfigs = new ArrayList<>();
        for (Map<String, Object> doc : loadAll(file)) {
            if (!"ConfigMap".equals(doc.get("kind"))) {
                continue;
            }
            Object metadata = doc.get("metadata");
            Object name = metadata instanceof Map<?, ?> m ? m.get("name") : null;
            if (name instanceof String s && s.startsWith("app-config")) {
                appConfigs.add(doc);
            }
        }
        if (appConfigs.size() != 1) {
            fail("%s: expected exactly one app-config ConfigMap, found %d", file, appConfigs.size());
        }
        Map<String, Object> data = map(appConfigs.get(0), "data", file);
        return new Links(
                requireString(data, "gdpr.dsar.verify-base-url", file.toString()),
                requireString(data, "gdpr.dsar.export-download-base-url", file.toString()),
                requireString(data, "notification.unsubscribe.base-url", file.toString()));
    }

    // ---- helpers, every one fail-closed -------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> loadAll(Path file) throws IOException {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        List<Map<String, Object>> docs = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            for (Object doc : yaml.loadAll(reader)) {
                if (doc instanceof Map<?, ?> m) {
                    docs.add((Map<String, Object>) m);
                }
            }
        }
        if (docs.isEmpty()) {
            fail("%s: no YAML mapping documents", file);
        }
        return docs;
    }

    private static Map<String, Object> single(List<Map<String, Object>> docs, Path file) {
        if (docs.size() != 1) {
            fail("%s: expected one YAML document, found %d", file, docs.size());
        }
        return docs.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key, Path file) {
        Object value = parent.get(key);
        if (!(value instanceof Map<?, ?>)) {
            fail("%s: '%s' is missing or is not a mapping", file, key);
        }
        return (Map<String, Object>) value;
    }

    static String requireString(Map<String, Object> parent, String key, String where) {
        Object value = parent.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new AssertionError(where + ": key '" + key + "' is missing or empty");
        }
        return s.trim();
    }

    private static String fallback(Pattern placeholder, String expression) {
        Matcher m = placeholder.matcher(expression);
        if (!m.matches() || m.group(1).isBlank()) {
            fail("'%s' is not a placeholder with a non-empty default", expression);
        }
        return m.group(1);
    }

    /** A compose value is either a literal or a {@code ${VAR:-default}}; a bare {@code ${VAR}} has no value here. */
    private static String composeValue(String expression) {
        if (expression.startsWith("${")) {
            return fallback(COMPOSE_PLACEHOLDER, expression);
        }
        return expression;
    }

    private static String origin(String url) {
        URI uri = URI.create(url);
        if (uri.getScheme() == null || uri.getHost() == null) {
            fail("'%s' is not an absolute URL", url);
        }
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }
}
