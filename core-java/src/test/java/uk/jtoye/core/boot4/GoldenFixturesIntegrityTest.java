package uk.jtoye.core.boot4;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Byte-integrity guard over the Jackson-2 golden fixtures captured on the Boot 3.5.16 tree
 * (Phase 38, plan 38-01, threat T-38-01).
 *
 * <p>The fixtures under {@code src/test/resources/jackson2-golden} are the oracle every later
 * Jackson-3 compatibility proof trusts (idempotency hashes, stored responses, outbox rows, AMQP
 * bodies, Redis cache values). They were written once by the production serializers of the 3.5
 * tree and must never be re-captured under Jackson 3. This test makes any change to them loud:
 * every {@code MANIFEST.tsv} row ({@code relative-path TAB sha256-hex TAB byte-length}) is
 * recomputed, every fixture must be listed by exactly one manifest, and every recorded idempotency
 * hash must be the SHA-256 of its fixture's bytes.
 *
 * <p><b>Deliberately Jackson-free</b> (no {@code com.fasterxml} and no {@code tools.jackson}
 * import) so it compiles and means the same thing on both Boot lines. It reads the SOURCE tree,
 * not the processed classpath copy, because the source is what is committed. The Gradle test
 * working directory is {@code core-java/}.
 *
 * <p>Fail-closed: a missing tree, zero manifests or zero rows is a failure, never a vacuous pass.
 */
class GoldenFixturesIntegrityTest {

    static final Path ROOT = Path.of("src", "test", "resources", "jackson2-golden");
    private static final String MANIFEST = "MANIFEST.tsv";
    private static final String README = "README.md";
    private static final Path REQUEST_HASHES = Path.of("idempotency", "request-hashes.tsv");

    @Test
    @DisplayName("every MANIFEST.tsv row matches its fixture by sha256 and length, and every fixture is listed exactly once")
    void manifestsCoverEveryFixtureByteForByte() throws IOException {
        List<Path> manifests = manifests();
        assertThat(manifests)
                .as("no %s found under %s (working dir %s) — the golden fixtures are missing",
                        MANIFEST, ROOT, Path.of("").toAbsolutePath())
                .isNotEmpty();

        List<String> problems = new ArrayList<>();
        Map<Path, Integer> listedBy = new TreeMap<>();
        int rows = 0;
        for (Path manifest : manifests) {
            Path base = manifest.getParent();
            List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isEmpty()) {
                    continue;
                }
                String[] cols = line.split("\t", -1);
                if (cols.length != 3) {
                    problems.add(manifest + ":" + (i + 1) + " has " + cols.length + " columns, expected 3: " + line);
                    continue;
                }
                rows++;
                Path file = base.resolve(cols[0]).normalize();
                listedBy.merge(file, 1, Integer::sum);
                if (!file.startsWith(ROOT)) {
                    problems.add(manifest + ":" + (i + 1) + " points outside the golden tree: " + cols[0]);
                    continue;
                }
                if (!Files.isRegularFile(file)) {
                    problems.add(cols[0] + " is listed by " + manifest + " but does not exist");
                    continue;
                }
                byte[] bytes = Files.readAllBytes(file);
                if (!Long.toString(bytes.length).equals(cols[2])) {
                    problems.add(cols[0] + " is " + bytes.length + " bytes, manifest says " + cols[2]);
                }
                String actual = sha256Hex(bytes);
                if (!actual.equals(cols[1])) {
                    problems.add(cols[0] + " sha256 is " + actual + ", manifest says " + cols[1]);
                }
            }
        }
        assertThat(rows).as("the manifests list no fixture at all — a vacuous pass is refused").isGreaterThan(0);

        for (Path fixture : fixtures()) {
            int count = listedBy.getOrDefault(fixture, 0);
            if (count != 1) {
                problems.add(ROOT.relativize(fixture) + " is listed by " + count + " manifest rows, expected exactly 1");
            }
        }
        assertThat(problems).as("golden fixture integrity").isEmpty();
    }

    @Test
    @DisplayName("every idempotency request hash is the sha256 of its request fixture's UTF-8 bytes")
    void requestHashesAreTheSha256OfTheirFixtures() throws IOException {
        Path hashes = ROOT.resolve(REQUEST_HASHES);
        assertThat(Files.isRegularFile(hashes)).as("%s is missing", hashes).isTrue();

        List<String> problems = new ArrayList<>();
        int rows = 0;
        List<String> lines = Files.readAllLines(hashes, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            String[] cols = line.split("\t", -1);
            if (cols.length != 2 || !cols[1].matches("[0-9a-f]{64}")) {
                problems.add(hashes + ":" + (i + 1) + " is not 'endpoint-id TAB 64-hex': " + line);
                continue;
            }
            rows++;
            Path request = ROOT.resolve("idempotency").resolve(cols[0] + ".request.json");
            if (!Files.isRegularFile(request)) {
                problems.add(cols[0] + ": " + request + " does not exist");
                continue;
            }
            String actual = sha256Hex(Files.readAllBytes(request));
            if (!actual.equals(cols[1])) {
                problems.add(cols[0] + ": sha256(" + request.getFileName() + ") is " + actual
                        + ", request-hashes.tsv records " + cols[1]);
            }
        }
        assertThat(rows).as("%s holds no hash row — a vacuous pass is refused", hashes).isGreaterThan(0);
        assertThat(problems).as("idempotency request hashes").isEmpty();
    }

    private static List<Path> manifests() throws IOException {
        if (!Files.isDirectory(ROOT)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(ROOT)) {
            return walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().equals(MANIFEST))
                    .sorted()
                    .toList();
        }
    }

    static List<Path> fixtures() throws IOException {
        if (!Files.isDirectory(ROOT)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(ROOT)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals(MANIFEST)
                            && !p.getFileName().toString().equals(README))
                    .map(Path::normalize)
                    .sorted()
                    .toList();
        }
    }

    /** Lower-case SHA-256 hex, the same digest and alphabet IdempotencyService.sha256Hex produces. */
    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
