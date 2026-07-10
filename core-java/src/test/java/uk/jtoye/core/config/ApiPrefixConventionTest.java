package uk.jtoye.core.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the whole "invisible /api/v1 prefix" bug class
 * (issue #97 [P2-6]).
 *
 * <p>{@link WebConfig#configurePathMatch} silently prepends
 * {@link WebConfig#API_V1_PREFIX} to every controller in
 * {@link WebConfig#API_V1_PACKAGES}. That made it easy to hand-build response
 * paths ({@code URI.create("/shops/" + id)}) that point at the UNPREFIXED
 * path and therefore 404 — six Location headers shipped broken this way.
 *
 * <p>This test statically scans the {@code @RestController} sources under the
 * prefixed packages and fails on the two known failure modes:
 * <ol>
 *   <li>hand-built root-relative URIs via {@code URI.create(} — use
 *       {@code ServletUriComponentsBuilder.fromCurrentRequest()} instead, which
 *       inherits the real (prefixed) request path;</li>
 *   <li>mappings that already contain {@code "/api/v1} — those would be served
 *       double-prefixed at {@code /api/v1/api/v1/...}.</li>
 * </ol>
 * It also fails if a listed package stops containing any {@code @RestController}
 * (e.g. after a package rename), which would silently drop the prefix for the
 * moved controllers.
 *
 * <p>Runtime dereferencability of Location headers is separately proven by
 * {@code LocationHeaderContractTest} (POST → Location → GET → 200).
 */
class ApiPrefixConventionTest {

    /**
     * Resolves the main source root whether the test runs from the module dir
     * (Gradle default working dir) or the repository root (some IDE setups).
     */
    private static Path sourceRoot() {
        Path fromModule = Path.of("src", "main", "java");
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        Path fromRepoRoot = Path.of("core-java", "src", "main", "java");
        assertTrue(Files.isDirectory(fromRepoRoot),
                "Cannot locate core-java main source root from working dir " + Path.of("").toAbsolutePath());
        return fromRepoRoot;
    }

    private record ControllerSource(Path file, String content) {
    }

    /** All @RestController sources under the WebConfig-prefixed packages. */
    private static List<ControllerSource> prefixedControllers() throws IOException {
        Path root = sourceRoot();
        List<ControllerSource> controllers = new ArrayList<>();
        for (String pkg : WebConfig.API_V1_PACKAGES) {
            Path pkgDir = root.resolve(pkg.replace('.', '/'));
            assertTrue(Files.isDirectory(pkgDir),
                    "Package " + pkg + " listed in WebConfig.API_V1_PACKAGES does not exist at " + pkgDir
                            + " — update WebConfig.API_V1_PACKAGES or this will silently drop the /api/v1 prefix.");
            try (Stream<Path> files = Files.walk(pkgDir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String content = Files.readString(file);
                    if (content.contains("@RestController")) {
                        controllers.add(new ControllerSource(file, content));
                    }
                }
            }
        }
        return controllers;
    }

    @Test
    void everyPrefixedPackageContainsAtLeastOneRestController() throws IOException {
        Path root = sourceRoot();
        for (String pkg : WebConfig.API_V1_PACKAGES) {
            Path pkgDir = root.resolve(pkg.replace('.', '/'));
            boolean hasController;
            try (Stream<Path> files = Files.walk(pkgDir)) {
                hasController = files
                        .filter(p -> p.toString().endsWith(".java"))
                        .anyMatch(p -> {
                            try {
                                return Files.readString(p).contains("@RestController");
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
            assertTrue(hasController,
                    "Package " + pkg + " is listed in WebConfig.API_V1_PACKAGES but contains no @RestController."
                            + " If its controllers moved, update WebConfig.API_V1_PACKAGES — otherwise the moved"
                            + " controllers silently lose the /api/v1 prefix.");
        }
    }

    @Test
    void prefixedControllersMustNotHandBuildUris() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (ControllerSource controller : prefixedControllers()) {
            if (controller.content().contains("URI.create(")) {
                offenders.add(controller.file().toString());
            }
        }
        assertEquals(List.of(), offenders,
                "Controllers in WebConfig.API_V1_PACKAGES are served under " + WebConfig.API_V1_PREFIX
                        + ", so a hand-built URI.create(\"/...\") points at a path that 404s (issue #97)."
                        + " Build self-referencing URIs (e.g. Location headers) with"
                        + " ServletUriComponentsBuilder.fromCurrentRequest() instead. Offending files: " + offenders);
    }

    @Test
    void prefixedControllersMustNotHardcodeTheApiPrefixInMappings() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (ControllerSource controller : prefixedControllers()) {
            if (controller.content().contains("\"" + WebConfig.API_V1_PREFIX)) {
                offenders.add(controller.file().toString());
            }
        }
        assertEquals(List.of(), offenders,
                "Controllers in WebConfig.API_V1_PACKAGES already get " + WebConfig.API_V1_PREFIX
                        + " prepended by WebConfig.configurePathMatch — a literal \"" + WebConfig.API_V1_PREFIX
                        + "...\" string in these files is either a double-prefixed mapping"
                        + " (/api/v1/api/v1/...) or a hand-built path that will drift. Offending files: "
                        + offenders);
    }
}
