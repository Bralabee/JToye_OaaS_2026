package uk.jtoye.core.config;

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
 * One registered office, one source, every runtime (#794, D-14, Pitfall 13 — phase 31.1 plan 27).
 *
 * <p>The platform's registered office has two consumers: the frontend legal pages, which inline
 * {@code NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE} at BUILD time, and the core-java order-email
 * footer (31.1-25), which reads {@code COMPANY_REGISTERED_OFFICE}. Two names are two places to
 * drift, so this test pins that both are fed from ONE source in each runtime, and that every
 * runtime carries the value the owner confirmed (2026-10-07) from the Companies House record for
 * company 16471464 — never the dissolved namesake 13434105.
 *
 * <p>Plain unit test over the repository files that configure each runtime. Every lookup fails
 * closed: a missing file, document, key or line is a failure, never a skipped runtime.
 */
class RegisteredOfficeParityTest {

    /** The owner-confirmed string (31.1-27 Task 1, 2026-10-07: "Approved as shown"). */
    static final String CONFIRMED_OFFICE = "Crispins Manor Farm Lane, Michelmersh, Romsey, England, SO51 0NT";

    static final String SOURCE_VARIABLE = "NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE";
    static final String CORE_JAVA_ENV = "COMPANY_REGISTERED_OFFICE";
    static final String CONFIGMAP_KEY = "platform.registered-office";
    static final String DISSOLVED_NAMESAKE = "13434105";

    /** A compose expression {@code ${NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE:-default}}, nothing else. */
    private static final Pattern COMPOSE_SOURCE =
            Pattern.compile("^\\$\\{" + SOURCE_VARIABLE + ":-(.*)}$");

    // ---- compose: both consumers from one variable ---------------------------------------------

    @Test
    @DisplayName("Compose feeds the frontend build arg and core-java's env from the same variable and default")
    void composeFeedsBothConsumersFromOneVariable() throws IOException {
        Path file = repoFile("docker-compose.full-stack.yml");
        Map<String, Object> services = map(single(loadAll(file), file), "services", file);

        String coreJava = requireString(
                map(map(services, "core-java", file), "environment", file), CORE_JAVA_ENV, file.toString());
        String frontend = requireString(
                map(map(map(services, "frontend", file), "build", file), "args", file),
                SOURCE_VARIABLE, file.toString());

        assertThat(coreJava)
                .as("%s: core-java %s must read %s, the variable the frontend build arg reads", file,
                        CORE_JAVA_ENV, SOURCE_VARIABLE)
                .isEqualTo(frontend);
        assertThat(composeDefault(coreJava))
                .as("%s: the compose default must be the owner-confirmed registered office", file)
                .isEqualTo(CONFIRMED_OFFICE);
    }

    // ---- every runtime carries the confirmed value ---------------------------------------------

    enum Runtime {
        ENV_EXAMPLE(".env.example"),
        K8S_BASE("k8s/base/configmap.yaml"),
        GOLDEN_STAGING("k8s/goldens/staging.yaml"),
        GOLDEN_PRODUCTION("k8s/goldens/production.yaml");

        final String file;

        Runtime(String file) {
            this.file = file;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Runtime.class)
    @DisplayName("Every runtime carries the owner-confirmed registered office")
    void everyRuntimeCarriesTheConfirmedValue(Runtime runtime) throws IOException {
        Path file = repoFile(runtime.file);
        String value = runtime == Runtime.ENV_EXAMPLE ? readEnvExample(file) : readConfigMap(file);

        assertThat(value)
                .as("%s: the registered office", file)
                .isEqualTo(CONFIRMED_OFFICE)
                .doesNotContain(DISSOLVED_NAMESAKE);
    }

    @Test
    @DisplayName(".env.example declares no second key for core-java: the one variable is the only source")
    void envExampleHasNoSecondSource() throws IOException {
        Path file = repoFile(".env.example");
        for (String line : Files.readAllLines(file)) {
            assertThat(line.trim())
                    .as("%s: a separate %s key would be a second source that can drift", file, CORE_JAVA_ENV)
                    .doesNotStartWith(CORE_JAVA_ENV + "=");
        }
    }

    // ---- core-java reads the key the runtimes supply -------------------------------------------

    @Test
    @DisplayName("core-java binds jtoye.platform.registered-office to COMPANY_REGISTERED_OFFICE")
    void coreJavaReadsTheSuppliedEnv() throws IOException {
        Path file = repoFile("core-java/src/main/resources/application.yml");
        Map<String, Object> platform =
                map(map(single(loadAll(file), file), "jtoye", file), "platform", file);
        assertThat(requireString(platform, "registered-office", file.toString()))
                .as("%s: the footer value must come from the env every runtime supplies", file)
                .isEqualTo("${" + CORE_JAVA_ENV + ":}");
    }

    @Test
    @DisplayName("The k8s core-java Deployment reads COMPANY_REGISTERED_OFFICE from the app-config key")
    void k8sCoreJavaReadsTheConfigMapKey() throws IOException {
        Path file = repoFile("k8s/base/core-java-deployment.yaml");
        Map<String, Object> ref = null;
        for (Map<String, Object> doc : loadAll(file)) {
            if (!"Deployment".equals(doc.get("kind"))) {
                continue;
            }
            Map<String, Object> podSpec = map(map(map(doc, "spec", file), "template", file), "spec", file);
            for (Object container : list(podSpec, "containers", file)) {
                for (Object env : list(asMap(container, file), "env", file)) {
                    Map<String, Object> entry = asMap(env, file);
                    if (CORE_JAVA_ENV.equals(entry.get("name"))) {
                        ref = map(map(entry, "valueFrom", file), "configMapKeyRef", file);
                    }
                }
            }
        }
        if (ref == null) {
            fail("%s: no %s env entry in the core-java Deployment", file, CORE_JAVA_ENV);
        }
        assertThat(ref.get("name")).as("%s: ConfigMap name", file).isEqualTo("app-config");
        assertThat(ref.get("key")).as("%s: ConfigMap key", file).isEqualTo(CONFIGMAP_KEY);
    }

    // ---- the frontend build channels pass the same variable ------------------------------------

    @Test
    @DisplayName("CI and k8s-local-up pass NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE as a frontend build arg")
    void buildChannelsPassTheVariable() throws IOException {
        assertThat(lines("frontend/Dockerfile"))
                .as("frontend/Dockerfile must declare the build ARG")
                .contains("ARG " + SOURCE_VARIABLE);
        assertThat(lines(".github/workflows/ci-cd.yaml"))
                .as("ci-cd.yaml must pass the repository variable as the build arg")
                .contains(SOURCE_VARIABLE + "=${{ vars.FRONTEND_PUBLIC_COMPANY_REGISTERED_OFFICE }}");
        assertThat(lines("scripts/k8s-local-up.sh"))
                .as("k8s-local-up.sh must read the same .env key, falling back to the compose default")
                .contains("BA_REGISTERED_OFFICE=\"${" + SOURCE_VARIABLE
                        + ":-$(compose_default " + SOURCE_VARIABLE + ")}\"")
                .contains("--build-arg \"" + SOURCE_VARIABLE + "=${BA_REGISTERED_OFFICE}\" \\");
    }

    // ---- fail-closed controls -------------------------------------------------------------------

    @Test
    @DisplayName("A missing runtime file fails the parity check rather than skipping it")
    void aMissingFileFailsClosed() {
        assertThatThrownBy(() -> repoFile("k8s/nowhere/configmap.yaml"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("A runtime that lacks the key fails the parity check rather than passing by absence")
    void aMissingKeyFailsClosed() {
        assertThatThrownBy(() -> requireString(Map.of(), CONFIGMAP_KEY, "a ConfigMap"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(CONFIGMAP_KEY);
    }

    @Test
    @DisplayName("A compose consumer on a different variable is refused, not read as a value")
    void aDifferentVariableIsRefused() {
        assertThatThrownBy(() -> composeDefault("${COMPANY_REGISTERED_OFFICE:-" + CONFIRMED_OFFICE + "}"))
                .isInstanceOf(AssertionError.class);
    }

    // ---- readers ---------------------------------------------------------------------------------

    /** The single {@code NEXT_PUBLIC_COMPANY_REGISTERED_OFFICE=} assignment, outer quotes removed. */
    private static String readEnvExample(Path file) throws IOException {
        List<String> values = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.startsWith(SOURCE_VARIABLE + "=")) {
                values.add(line.substring(SOURCE_VARIABLE.length() + 1));
            }
        }
        if (values.size() != 1) {
            fail("%s: expected exactly one %s= line, found %d", file, SOURCE_VARIABLE, values.size());
        }
        String value = values.get(0).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isBlank()) {
            fail("%s: %s is empty", file, SOURCE_VARIABLE);
        }
        return value;
    }

    /** The one {@code app-config} ConfigMap's registered-office key (name may carry a hash suffix). */
    private static String readConfigMap(Path file) throws IOException {
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
        return requireString(map(appConfigs.get(0), "data", file), CONFIGMAP_KEY, file.toString());
    }

    /**
     * A file at the repository ROOT, never a nearer namesake: walking up from core-java finds
     * core-java/.env.example before the root .env.example (measured — the first run of this test
     * read the wrong file). The root is the directory holding docker-compose.full-stack.yml.
     */
    static Path repoFile(String relativePath) {
        Path root = IntegrationTestSupport.locateRepoFile("docker-compose.full-stack.yml").getParent();
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Repository file '" + relativePath + "' not found under " + root
                    + " — refusing to pass over a missing runtime.");
        }
        return file;
    }

    private static String composeDefault(String expression) {
        Matcher m = COMPOSE_SOURCE.matcher(expression);
        if (!m.matches()) {
            throw new AssertionError("'" + expression + "' is not ${" + SOURCE_VARIABLE + ":-<default>}");
        }
        return m.group(1);
    }

    private static List<String> lines(String relativePath) throws IOException {
        return Files.readAllLines(repoFile(relativePath)).stream()
                .map(String::trim)
                .toList();
    }

    // ---- helpers, every one fail-closed --------------------------------------------------------

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

    private static Map<String, Object> map(Map<String, Object> parent, String key, Path file) {
        return asMap(parent.get(key), file, key);
    }

    private static Map<String, Object> asMap(Object value, Path file) {
        return asMap(value, file, "<list item>");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, Path file, String what) {
        if (!(value instanceof Map<?, ?>)) {
            fail("%s: '%s' is missing or is not a mapping", file, what);
        }
        return (Map<String, Object>) value;
    }

    private static List<?> list(Map<String, Object> parent, String key, Path file) {
        Object value = parent.get(key);
        if (!(value instanceof List<?> l) || l.isEmpty()) {
            fail("%s: '%s' is missing or is not a non-empty list", file, key);
            return List.of();
        }
        return l;
    }

    static String requireString(Map<String, Object> parent, String key, String where) {
        Object value = parent.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new AssertionError(where + ": key '" + key + "' is missing or empty");
        }
        return s.trim();
    }
}
