package uk.jtoye.core.notification.dispatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;
import uk.jtoye.core.notification.consent.PublicUnsubscribeController;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Issue #516 — the unsubscribe URL an email advertises must RESOLVE, in every
 * committed overlay, to a Service that actually serves its path.
 *
 * <h2>Why a string assertion is not enough</h2>
 *
 * {@code NotificationDispatchServiceTest} asserted the string the builder
 * returns and {@code PublicUnsubscribeControllerIntegrationTest} exercised the
 * controller at its own path. Both were green while every unsubscribe link in
 * every email 404'd, because the defect lived in the gap between them: the link
 * was composed as the <b>app</b> origin ({@code notification.unsubscribe.base-url},
 * which is the frontend host in all four overlays) plus the <b>API's</b> path
 * ({@code /api/v1/public/unsubscribe}). Those are two different Services behind
 * one ingress. Measured against the running local stack before the fix:
 *
 * <pre>
 *   GET http://localhost:3000/api/v1/public/unsubscribe?...  -> 404 (Next.js)
 *   GET http://localhost:3000/unsubscribe?...                -> 200 (the page)
 *   GET http://localhost:9090/api/v1/public/unsubscribe?...  -> reaches the controller
 * </pre>
 *
 * <h2>What this test consults</h2>
 *
 * Not a literal. For each overlay it reads the COMMITTED manifests —
 * {@code k8s/base/configmap.yaml} + the overlay's {@code configmap-patch.yaml}
 * for the configured origins, and {@code k8s/base/ingress.yaml} as patched by
 * the overlay for host → Service routing — then asks whether the Service that
 * owns the composed URL's host actually serves the composed URL's path:
 *
 * <ul>
 *   <li>{@code core-java} serves a path iff {@link PublicUnsubscribeController}
 *       declares it (read by REFLECTION off the annotations, so renaming the
 *       mapping moves this test with it);</li>
 *   <li>{@code frontend} serves a path iff the Next.js app-router page file
 *       {@code frontend/app/<path>/page.tsx} exists.</li>
 * </ul>
 *
 * The URLs themselves come from the real {@link NotificationDispatchService}
 * dispatch path (via {@link UnsubscribeLinkFixture}), never re-derived here — a
 * test that rebuilt the URL its own way could not fail when the production
 * builder changed.
 */
class UnsubscribeLinkRoutingTest {

    /** The Ingress that publishes the public hosts; the SSE Ingress is a different object. */
    private static final String PUBLIC_INGRESS = "jtoye-ingress";

    private static final String APP_ORIGIN_KEY = "notification.unsubscribe.base-url";
    private static final String ONE_CLICK_ORIGIN_KEY = "notification.unsubscribe.one-click-base-url";
    /** Fallback source for the API origin: the same value the ConfigMap already publishes. */
    private static final String API_ORIGIN_KEY = "api.url";

    private static final List<String> OVERLAYS = List.of("base", "staging", "production", "local");

    // ------------------------------------------------------------------
    // The assertions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#516 — in EVERY overlay the clickable unsubscribe link resolves to a Service that serves its path")
    void clickableLinkResolvesToAServiceThatServesIt() {
        List<Overlay> overlays = loadOverlays();
        // Guard against a vacuous pass: "found nothing" is never "clean".
        assertThat(overlays).as("committed overlays discovered under k8s/").hasSize(OVERLAYS.size());

        for (Overlay overlay : overlays) {
            String appOrigin = overlay.require(APP_ORIGIN_KEY);
            String url = UnsubscribeLinkFixture.pageUrl(appOrigin, apiOrigin(overlay));

            Resolution r = resolve(overlay, url);
            assertThat(r.servedBy)
                    .as("%s: the unsubscribe link %s routes to Service '%s', which does not serve path '%s'. "
                                    + "Paths that Service does serve: %s",
                            overlay.name, url, r.service, r.path, servedPaths(r.service))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("#516 — in EVERY overlay the RFC 8058 one-click target resolves to the core-java controller")
    void oneClickTargetResolvesToTheController() {
        List<Overlay> overlays = loadOverlays();
        assertThat(overlays).hasSize(OVERLAYS.size());

        for (Overlay overlay : overlays) {
            String oneClickOrigin = apiOrigin(overlay);
            String url = UnsubscribeLinkFixture.oneClickUrl(overlay.require(APP_ORIGIN_KEY), oneClickOrigin);

            Resolution r = resolve(overlay, url);
            assertThat(r.service)
                    .as("%s: an RFC 8058 one-click POST target must be served by the API, not the frontend "
                            + "(a Next.js page answers 405 to a POST). URL: %s", overlay.name, url)
                    .isEqualTo("core-java");
            assertThat(r.servedBy)
                    .as("%s: the one-click target %s routes to Service '%s', which does not serve path '%s'. "
                                    + "Paths that Service does serve: %s",
                            overlay.name, url, r.service, r.path, servedPaths(r.service))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("#516 control — the OLD composition (app origin + the API's path) resolves to a Service that does NOT serve it")
    void oldCompositionIsProvablyUnroutable() {
        // The fail-direction, kept permanently. If this ever passes, the routing
        // oracle above has stopped being able to see the defect it exists for
        // (e.g. an /api/v1 rewrite appeared on the app host, or the oracle broke).
        List<Overlay> overlays = loadOverlays();
        assertThat(overlays).hasSize(OVERLAYS.size());

        for (Overlay overlay : overlays) {
            String broken = trimTrailingSlash(overlay.require(APP_ORIGIN_KEY)) + "/api/v1/public/unsubscribe?tenant=x";
            Resolution r = resolve(overlay, broken);
            assertThat(r.servedBy)
                    .as("%s: %s must NOT be routable — that composition is the #516 defect. "
                            + "If the app host now serves the API path, delete this control and say why.",
                            overlay.name, broken)
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Routing oracle
    // ------------------------------------------------------------------

    /** The API origin an overlay would use: its explicit key if wired, else the ConfigMap's api.url. */
    private static String apiOrigin(Overlay overlay) {
        String explicit = overlay.configMap.get(ONE_CLICK_ORIGIN_KEY);
        return explicit != null && !explicit.isBlank() ? explicit : overlay.require(API_ORIGIN_KEY);
    }

    private record Resolution(String service, String path, boolean servedBy) {
    }

    private Resolution resolve(Overlay overlay, String url) {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null) {
            fail("%s: composed URL has no host: %s", overlay.name, url);
        }
        String service = overlay.hostToService.get(host);
        if (service == null) {
            fail("%s: no ingress rule publishes host '%s' (published: %s). URL: %s",
                    overlay.name, host, overlay.hostToService.keySet(), url);
        }
        String path = uri.getPath();
        return new Resolution(service, path, servedPaths(service).contains(path));
    }

    /** Which paths a Service actually serves — asked of the code/tree, never hardcoded. */
    private Set<String> servedPaths(String service) {
        return switch (service) {
            case "core-java" -> controllerPaths();
            case "frontend" -> frontendPagePaths();
            default -> Set.of();
        };
    }

    /**
     * The unsubscribe paths core-java really exposes, read off
     * {@link PublicUnsubscribeController}'s annotations. Reflection, not a
     * literal: if the mapping moves, this test moves with it instead of
     * certifying a path that no longer exists.
     */
    private static Set<String> controllerPaths() {
        Class<?> c = PublicUnsubscribeController.class;
        String[] prefixes = c.getAnnotation(RequestMapping.class).value();
        Set<String> paths = new LinkedHashSet<>();
        for (var m : c.getDeclaredMethods()) {
            List<String> suffixes = new ArrayList<>();
            GetMapping get = m.getAnnotation(GetMapping.class);
            PostMapping post = m.getAnnotation(PostMapping.class);
            if (get != null) {
                suffixes.addAll(List.of(get.value()));
            }
            if (post != null) {
                suffixes.addAll(List.of(post.value()));
            }
            for (String prefix : prefixes) {
                for (String suffix : suffixes) {
                    paths.add(prefix + suffix);
                }
            }
        }
        if (paths.isEmpty()) {
            fail("read ZERO paths off %s — the reflection oracle is broken, not the routing", c.getSimpleName());
        }
        return paths;
    }

    /**
     * The Next.js app-router pages the frontend Service serves, discovered from
     * the tree ({@code frontend/app/**}{@code /page.tsx}). Only static segments
     * are listed — a dynamic segment cannot be an unsubscribe target anyway.
     */
    private static Set<String> frontendPagePaths() {
        Path appDir = repoRoot().resolve("frontend/app");
        if (!Files.isDirectory(appDir)) {
            fail("frontend/app not found at %s — cannot tell what the frontend serves", appDir);
        }
        Set<String> paths = new LinkedHashSet<>();
        try (var stream = Files.walk(appDir)) {
            stream.filter(p -> p.getFileName().toString().equals("page.tsx"))
                    .forEach(p -> paths.add("/" + appDir.relativize(p.getParent()).toString().replace('\\', '/')));
        } catch (IOException e) {
            fail("could not walk %s: %s", appDir, e.getMessage());
        }
        paths.remove("/."); // the root page
        paths.add("/");
        if (paths.size() < 2) {
            fail("discovered %d frontend pages — the discovery is broken, not the routing", paths.size());
        }
        return paths;
    }

    // ------------------------------------------------------------------
    // Overlay loading (ConfigMap data + the Ingress host -> Service map)
    // ------------------------------------------------------------------

    private record Overlay(String name, Map<String, String> configMap, Map<String, String> hostToService) {
        String require(String key) {
            String v = configMap.get(key);
            if (v == null || v.isBlank()) {
                fail("%s: ConfigMap key '%s' is missing — the test cannot be satisfied vacuously", name, key);
            }
            return v;
        }
    }

    private List<Overlay> loadOverlays() {
        Path k8s = repoRoot().resolve("k8s");
        Map<String, String> baseConfig = configMapData(k8s.resolve("base/configmap.yaml"));
        Map<String, String> baseHosts = ingressHosts(loadIngress(k8s.resolve("base/ingress.yaml")));

        List<Overlay> overlays = new ArrayList<>();
        for (String name : OVERLAYS) {
            if (name.equals("base")) {
                overlays.add(new Overlay(name, baseConfig, baseHosts));
                continue;
            }
            Path dir = k8s.resolve(name);
            Map<String, String> config = new TreeMap<>(baseConfig);
            Map<String, String> hosts = new LinkedHashMap<>(baseHosts);

            for (PatchEntry patch : patchesOf(dir)) {
                for (Object doc : loadAll(patch.file)) {
                    if (doc instanceof Map<?, ?> map) {
                        if ("ConfigMap".equals(map.get("kind"))) {
                            config.putAll(asStringMap(map.get("data")));
                        } else if ("Ingress".equals(map.get("kind")) && PUBLIC_INGRESS.equals(nameOf(map))) {
                            Map<String, Object> spec = asMap(map.get("spec"));
                            // Ingress.spec.rules carries no patchMergeKey, so a strategic
                            // merge REPLACES the whole list (k8s/local/ingress-patch.yaml
                            // documents this).
                            if (spec.get("rules") instanceof List<?> rules && !rules.isEmpty()) {
                                hosts = ingressHosts(rules);
                            }
                        }
                    } else if (doc instanceof List<?> ops && PUBLIC_INGRESS.equals(patch.targetName)) {
                        hosts = applyHostOps(baseHosts, loadIngress(k8s.resolve("base/ingress.yaml")), ops);
                    }
                }
            }
            overlays.add(new Overlay(name, config, hosts));
        }
        return overlays;
    }

    /** A {@code patches:} entry from a kustomization: the file plus its declared target name (JSON6902 needs it). */
    private record PatchEntry(Path file, String targetName) {
    }

    private static List<PatchEntry> patchesOf(Path overlayDir) {
        Path kustomization = overlayDir.resolve("kustomization.yaml");
        if (!Files.isRegularFile(kustomization)) {
            fail("no kustomization.yaml in %s", overlayDir);
        }
        Map<String, Object> doc = asMap(loadAll(kustomization).get(0));
        List<PatchEntry> entries = new ArrayList<>();
        if (doc.get("patches") instanceof List<?> patches) {
            for (Object p : patches) {
                Map<String, Object> patch = asMap(p);
                Object path = patch.get("path");
                if (path == null) {
                    continue;
                }
                String target = patch.get("target") instanceof Map<?, ?> t ? String.valueOf(t.get("name")) : null;
                entries.add(new PatchEntry(overlayDir.resolve(String.valueOf(path)), target));
            }
        }
        if (entries.isEmpty()) {
            fail("%s declares no patches — overlay loading is broken, not the routing", kustomization);
        }
        return entries;
    }

    /** Apply the {@code replace /spec/rules/<i>/host} ops an overlay uses to re-host the base rules. */
    private static Map<String, String> applyHostOps(Map<String, String> baseHosts, List<?> baseRules, List<?> ops) {
        List<String> orderedHosts = new ArrayList<>();
        for (Object rule : baseRules) {
            orderedHosts.add(String.valueOf(asMap(rule).get("host")));
        }
        for (Object o : ops) {
            Map<String, Object> op = asMap(o);
            String path = String.valueOf(op.get("path"));
            if (!"replace".equals(op.get("op")) || !path.matches("/spec/rules/\\d+/host")) {
                continue;
            }
            int idx = Integer.parseInt(path.split("/")[3]);
            if (idx >= orderedHosts.size()) {
                fail("patch targets /spec/rules/%d/host but the base has %d rules", idx, orderedHosts.size());
            }
            orderedHosts.set(idx, String.valueOf(op.get("value")));
        }
        Map<String, String> hosts = new LinkedHashMap<>();
        for (int i = 0; i < baseRules.size(); i++) {
            hosts.put(orderedHosts.get(i), serviceOf(baseRules.get(i)));
        }
        if (hosts.equals(baseHosts)) {
            fail("host ops changed nothing — the JSON6902 patch reader is broken, not the routing");
        }
        return hosts;
    }

    private static List<?> loadIngress(Path file) {
        for (Object doc : loadAll(file)) {
            if (doc instanceof Map<?, ?> map
                    && "Ingress".equals(map.get("kind"))
                    && PUBLIC_INGRESS.equals(nameOf(map))
                    && asMap(map.get("spec")).get("rules") instanceof List<?> rules) {
                return rules;
            }
        }
        fail("no Ingress named %s with rules in %s", PUBLIC_INGRESS, file);
        return List.of();
    }

    private static Map<String, String> ingressHosts(List<?> rules) {
        Map<String, String> hosts = new LinkedHashMap<>();
        for (Object rule : rules) {
            hosts.put(String.valueOf(asMap(rule).get("host")), serviceOf(rule));
        }
        if (hosts.isEmpty()) {
            fail("an Ingress with zero host rules cannot prove anything");
        }
        return hosts;
    }

    /** The Service behind a rule's {@code /} Prefix path (every rule in this repo routes the whole host). */
    private static String serviceOf(Object rule) {
        Map<String, Object> http = asMap(asMap(rule).get("http"));
        List<?> paths = (List<?>) http.get("paths");
        for (Object p : paths) {
            Map<String, Object> entry = asMap(p);
            if ("/".equals(entry.get("path"))) {
                return String.valueOf(asMap(asMap(entry.get("backend")).get("service")).get("name"));
            }
        }
        fail("rule %s has no '/' Prefix path — the routing oracle assumes whole-host rules", rule);
        return "";
    }

    private static Map<String, String> configMapData(Path file) {
        for (Object doc : loadAll(file)) {
            if (doc instanceof Map<?, ?> map && "ConfigMap".equals(map.get("kind"))) {
                Map<String, String> data = new TreeMap<>(asStringMap(map.get("data")));
                if (data.isEmpty()) {
                    fail("ConfigMap in %s has no data", file);
                }
                return data;
            }
        }
        fail("no ConfigMap in %s", file);
        return Map.of();
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private static List<Object> loadAll(Path file) {
        if (!Files.isRegularFile(file)) {
            fail("expected manifest %s does not exist", file);
        }
        List<Object> docs = new ArrayList<>();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            new Yaml().loadAll(r).forEach(d -> {
                if (d != null) {
                    docs.add(d);
                }
            });
        } catch (IOException e) {
            fail("could not read %s: %s", file, e.getMessage());
        }
        if (docs.isEmpty()) {
            fail("%s parsed to zero documents", file);
        }
        return docs;
    }

    private static String nameOf(Map<?, ?> resource) {
        return String.valueOf(asMap(resource.get("metadata")).get("name"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static Map<String, String> asStringMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        asMap(o).forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
        return out;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * The repository root, found by walking up from the Gradle working directory
     * until the committed manifests are visible. Fails loudly rather than
     * skipping: a routing test that silently opts out is worse than none.
     */
    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("k8s/base/ingress.yaml"))) {
                return p;
            }
        }
        fail("could not locate the repository root (no k8s/base/ingress.yaml above %s)", dir);
        return dir;
    }
}
