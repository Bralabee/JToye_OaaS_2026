package uk.jtoye.core.websocket;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Covers the one residual the construction-time guard cannot see (27-04, D-10).
 *
 * <p>{@link StompDestinations#assertPublishable} catches a malformed destination where it is
 * BUILT. It is therefore blind to a caller that bypasses {@link StompDestinations} entirely,
 * hand-builds a destination string and publishes it. That gap is closed here by asserting the
 * bypass cannot exist: exactly one STOMP publish call site in main source, and it must reference
 * {@link StompDestinations}.
 *
 * <p><b>Why a source scan rather than an ArchUnit rule.</b> An ArchUnit rule would have to name
 * the forbidden construct, which in this repo means the rule text itself matches the thing it
 * forbids — a known vacuous shape here. This asserts a positive count instead, so it cannot fire
 * on its own definition, and adding a second hand-built call site turns it RED.
 *
 * <p><b>Scoping matters and is easy to get wrong.</b> {@code convertAndSend(} appears four times
 * in main source, but three are {@code rabbitTemplate.convertAndSend(...)} — AMQP publishes with
 * an entirely different destination grammar — plus one javadoc mention. A bare count would assert
 * 4 and mean nothing. The scan is therefore restricted to files that reference
 * {@code SimpMessagingTemplate}, which is the STOMP template and the only one whose destinations
 * this guard governs.
 */
class StompPublishCallSiteTest {

    private static final String STOMP_TEMPLATE = "SimpMessagingTemplate";
    private static final String PUBLISH_CALL = "convertAndSend(";
    private static final String BUILDER = "StompDestinations.";

    /**
     * Resolves {@code core-java/src/main/java} by walking up from the working directory.
     * Gradle runs tests with the working directory set to the subproject, but that is a build
     * detail; walking up makes the test independent of it.
     */
    private static Path mainSourceRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path direct = dir.resolve("src/main/java");
            if (Files.isDirectory(direct) && dir.getFileName().toString().equals("core-java")) {
                return direct;
            }
            Path nested = dir.resolve("core-java/src/main/java");
            if (Files.isDirectory(nested)) {
                return nested;
            }
        }
        return fail("could not locate core-java/src/main/java from " + Paths.get("").toAbsolutePath()
                + " — a scan that cannot find its source tree is VOID, not clean");
    }

    /** Code lines only: a javadoc or {@code //} mention of a call is not a call. */
    private static boolean isCode(String line) {
        String t = line.trim();
        return !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*");
    }

    private record CallSite(Path file, int line, String text) {
    }

    private static List<CallSite> stompPublishCallSites(Path root) {
        List<CallSite> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                List<String> lines;
                try {
                    lines = Files.readAllLines(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                // Only files that actually use the STOMP template — rabbitTemplate publishes
                // are a different transport with a different destination grammar.
                boolean usesStompTemplate = lines.stream().filter(StompPublishCallSiteTest::isCode)
                        .anyMatch(l -> l.contains(STOMP_TEMPLATE));
                if (!usesStompTemplate) {
                    return;
                }
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (isCode(line) && line.contains(PUBLISH_CALL)) {
                        found.add(new CallSite(p, i + 1, line.trim()));
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    @Test
    void exactlyOneStompPublishCallSiteExistsAndItUsesTheBuilder() throws IOException {
        Path root = mainSourceRoot();

        List<CallSite> callSites = stompPublishCallSites(root);

        // An empty result is a BROKEN LOCATOR, not a clean tree. Asserting `isEmpty()` would be
        // satisfied by a scan that read nothing at all, which is the failure this must not have.
        assertThat(callSites)
                .as("a scan finding ZERO STOMP publish call sites is VOID — the app does publish to "
                        + "the KDS topic, so zero means the scan is broken, not that the code is clean")
                .isNotEmpty();

        assertThat(callSites)
                .as("exactly one STOMP publish call site may exist; a second one could hand-build a "
                        + "destination and bypass StompDestinations.assertPublishable entirely")
                .hasSize(1);

        CallSite only = callSites.get(0);
        String body = Files.readString(only.file());
        assertThat(body)
                .as("the sole STOMP publish call site (%s:%d) must derive its destination from "
                        + "StompDestinations, or the construction-time guard never runs for it",
                        only.file().getFileName(), only.line())
                .contains(BUILDER);
    }
}
