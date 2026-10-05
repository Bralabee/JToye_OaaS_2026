package uk.jtoye.core.common.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.boot4.GoldenSamples;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BOOT4-08 (Phase 38, plan 38-10): the idempotency store's JSON format is the Boot-3.5 format,
 * proven against bytes a Boot-3.5 pod wrote.
 *
 * <p>{@code IdempotencyService} persists {@code request_hash = sha256(json(requestBody))}. A key a
 * client reserved against a Boot-3.5 pod is matched, after the deploy, against the hash a Boot-4
 * pod computes for the retried body. If the two JSON forms differ by one byte, the retry is
 * refused as "same key, different body" (422) until the reservation expires.
 *
 * <p>The oracle is {@code jackson2-golden/idempotency/} (38-01, capture commit {@code e12177e1}):
 * each {@code {endpoint}.request.json} is the exact string Boot 3.5's {@code ObjectMapper} wrote,
 * and {@code request-hashes.tsv} holds the {@code request_hash} {@code IdempotencyService} stored
 * for it. The sample objects are rebuilt by {@link GoldenSamples}, which compiles on both Boot lines.
 */
class IdempotencyFingerprintGoldenTest {

    static final Path GOLDEN = Path.of("src", "test", "resources", "jackson2-golden");
    static final Path IDEMPOTENCY = GOLDEN.resolve("idempotency");

    @Test
    @DisplayName("orders.create: IdempotencyJson writes the Boot-3.5 bytes, so the stored hash matches")
    void ordersCreateFingerprintMatchesTheBoot35Hash() throws IOException {
        String written = IdempotencyJson.write(GoldenSamples.createOrderRequest());

        assertThat(sha256Hex(written))
                .as("sha256 of IdempotencyJson's orders.create fingerprint vs the hash a Boot-3.5 pod stored")
                .isEqualTo(goldenHashes().get("orders.create"));
        assertThat(written)
                .as("the fingerprint string, byte for byte")
                .isEqualTo(Files.readString(IDEMPOTENCY.resolve("orders.create.request.json"), StandardCharsets.UTF_8));
    }

    /** {@code request-hashes.tsv}: {@code endpoint-id TAB sha256}, in file order. */
    static Map<String, String> goldenHashes() throws IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(IDEMPOTENCY.resolve("request-hashes.tsv"), StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] cols = line.split("\t", -1);
            assertThat(cols).as("request-hashes.tsv row '%s'", line).hasSize(2);
            assertThat(hashes.put(cols[0], cols[1])).as("duplicate row for %s", cols[0]).isNull();
        }
        assertThat(hashes).as("request-hashes.tsv rows").isNotEmpty();
        return hashes;
    }

    static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
