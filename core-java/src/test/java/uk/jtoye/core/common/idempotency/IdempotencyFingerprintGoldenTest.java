package uk.jtoye.core.common.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.jtoye.core.boot4.GoldenSamples;
import uk.jtoye.core.customer.CustomerController;
import uk.jtoye.core.media.MediaAcceptDto;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.webhook.dto.WebhookDeliveryView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BOOT4-08 (Phase 38, plan 38-10): the idempotency store's JSON format is the Boot-3.5 format,
 * proven against bytes a Boot-3.5 pod wrote, for every adopter.
 *
 * <p>{@code IdempotencyService} persists {@code request_hash = sha256(json(requestBody))}. A key a
 * client reserved against a Boot-3.5 pod is matched, after the deploy, against the hash a Boot-4
 * pod computes for the retried body. If the two JSON forms differ by one byte, the retry is
 * refused as "same key, different body" (422) until the reservation expires.
 *
 * <p>The oracle is {@code jackson2-golden/} (38-01, capture commit {@code e12177e1}):
 * <ul>
 *   <li>{@code idempotency/{endpoint}.request.json} is the exact fingerprint Boot 3.5's
 *       {@code ObjectMapper} wrote, and {@code request-hashes.tsv} holds the {@code request_hash}
 *       {@code IdempotencyService} stored for it (7 rows);</li>
 *   <li>{@code responses/{OrderDto,CustomerDto,MediaAcceptDto,WebhookDeliveryView}.json} are the
 *       bodies the four body-storing adopters stored. They must cross the deploy both ways: a
 *       Boot-4 pod reads what a Boot-3.5 pod stored, and a Boot-3.5 pod can replay a row a Boot-4
 *       pod stored, which byte identity guarantees.</li>
 * </ul>
 * The sample objects are rebuilt by {@link GoldenSamples}, which compiles on both Boot lines.
 *
 * <p><b>Fail direction</b> (38-10 evidence): with {@code IdempotencyJson} on plain Jackson-3
 * defaults ({@code JsonMapper.builder().build()}), the hash rows and the byte-identity rows go red.
 */
class IdempotencyFingerprintGoldenTest {

    static final Path GOLDEN = Path.of("src", "test", "resources", "jackson2-golden");
    static final Path IDEMPOTENCY = GOLDEN.resolve("idempotency");

    /**
     * Every {@code request-hashes.tsv} endpoint id, with the request body its adopter passes to
     * {@code IdempotencyService}. {@code storefront.guest-order} and its {@code .legacy} twin are the
     * {@code requestBody} and {@code legacyRequestBody} of {@code storefront.orders.create}.
     */
    static final Map<String, Supplier<Object>> REQUEST_SAMPLES;

    static {
        Map<String, Supplier<Object>> samples = new LinkedHashMap<>();
        samples.put("customers.create", GoldenSamples::createCustomerRequest);
        samples.put("media.reprocess", GoldenSamples::redriveRequest);
        samples.put("media.upload", GoldenSamples::mediaUploadRequest);
        samples.put("orders.create", GoldenSamples::createOrderRequest);
        samples.put("storefront.guest-order", GoldenSamples::guestCheckoutIdentity);
        samples.put("storefront.guest-order.legacy", GoldenSamples::guestOrderRequest);
        samples.put("webhooks.replay", GoldenSamples::replayRequest);
        REQUEST_SAMPLES = Map.copyOf(samples);
    }

    /**
     * The four stored response types: orders.create, customers.create, media.upload and
     * media.reprocess (one type), webhooks.replay. {@code storefront.orders.create} stores no body.
     */
    static Stream<Arguments> storedResponses() {
        return Stream.of(
                Arguments.of("OrderDto", OrderDto.class, (Supplier<Object>) GoldenSamples::orderDto),
                Arguments.of("CustomerDto", CustomerController.CustomerDto.class,
                        (Supplier<Object>) GoldenSamples::customerDto),
                Arguments.of("MediaAcceptDto", MediaAcceptDto.class, (Supplier<Object>) GoldenSamples::mediaAcceptDto),
                Arguments.of("WebhookDeliveryView", WebhookDeliveryView.class,
                        (Supplier<Object>) GoldenSamples::webhookDeliveryView));
    }

    static Stream<String> requestRows() throws IOException {
        return goldenHashes().keySet().stream();
    }

    @ParameterizedTest(name = "request hash {0}")
    @MethodSource("requestRows")
    @DisplayName("each adopter's fingerprint: IdempotencyJson writes the Boot-3.5 bytes, so the stored hash matches")
    void fingerprintMatchesTheBoot35Hash(String endpoint) throws IOException {
        Supplier<Object> sample = REQUEST_SAMPLES.get(endpoint);
        assertThat(sample).as("request-hashes.tsv row %s has no sample in this test", endpoint).isNotNull();

        String written = IdempotencyJson.write(sample.get());

        assertThat(sha256Hex(written))
                .as("sha256 of IdempotencyJson's %s fingerprint vs the hash a Boot-3.5 pod stored", endpoint)
                .isEqualTo(goldenHashes().get(endpoint));
        assertThat(written)
                .as("the %s fingerprint string, byte for byte", endpoint)
                .isEqualTo(read(IDEMPOTENCY.resolve(endpoint + ".request.json")));
    }

    @ParameterizedTest(name = "stored response {0}")
    @MethodSource("storedResponses")
    @DisplayName("each stored response type crosses the deploy both ways")
    void storedResponseCrossesTheDeployBothWays(String name, Class<?> type, Supplier<Object> sample) throws IOException {
        String fixture = read(GOLDEN.resolve("responses").resolve(name + ".json"));

        assertThat((Object) IdempotencyJson.read(fixture, type))
                .as("%s stored by a Boot-3.5 pod, read back by IdempotencyJson (OffsetDateTime by instant)", name)
                .usingRecursiveComparison()
                .withComparatorForType(BY_INSTANT, OffsetDateTime.class)
                .isEqualTo(sample.get());
        assertThat(IdempotencyJson.write(sample.get()))
                .as("%s as IdempotencyJson stores it vs the Boot-3.5 bytes (an old pod can replay it)", name)
                .isEqualTo(fixture);
    }

    @Test
    @DisplayName("coverage: the hash rows are exactly the seven adopter fingerprints")
    void everyHashRowHasASampleAndEverySampleARow() throws IOException {
        assertThat(goldenHashes().keySet())
                .as("request-hashes.tsv endpoint ids vs the samples this test hashes")
                .containsExactlyInAnyOrderElementsOf(REQUEST_SAMPLES.keySet())
                .hasSize(7);
    }

    @Test
    @DisplayName("control: the instant comparator still fails on a one-nanosecond shift")
    void instantComparisonIsNotVacuous() {
        OrderDto shifted = GoldenSamples.orderDto();
        shifted.setUpdatedAt(shifted.getUpdatedAt().plusNanos(1));

        assertThatThrownBy(() -> assertThat(shifted)
                .usingRecursiveComparison()
                .withComparatorForType(BY_INSTANT, OffsetDateTime.class)
                .isEqualTo(GoldenSamples.orderDto()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("updatedAt");
    }

    // ------------------------------------------------------------------ constructor-only types (review WR-02)

    /**
     * A DTO of the shape no current adopter has: no setters, no default constructor, no
     * {@code @JsonCreator} or {@code @JsonProperty}, only an all-args constructor whose parameter
     * names come from {@code -parameters} (the Spring Boot Gradle plugin sets it on every compile).
     * Its constructor order ({@code alpha, zeta}) differs from its field order ({@code zeta, alpha}),
     * so which order the mapper writes shows whether it detected the constructor as a creator.
     */
    public static final class ConstructorOnlyDto {
        private final String zeta;
        private final int alpha;

        public ConstructorOnlyDto(int alpha, String zeta) {
            this.alpha = alpha;
            this.zeta = zeta;
        }

        public String getZeta() {
            return zeta;
        }

        public int getAlpha() {
            return alpha;
        }
    }

    /**
     * The bytes Boot 3.5's mapper writes for {@link ConstructorOnlyDto}. NOT a 38-01 capture: no
     * constructor-only type existed when the golden set was taken, so there is no Boot-3.5 fixture for
     * one. Anchored instead to (a) Boot 3.5.16's {@code spring-boot-starter-json}, which ships
     * {@code jackson-module-parameter-names}, registered by {@code Jackson2ObjectMapperBuilder}; and
     * (b) a Jackson 2.21.7 mapper with that module and Boot 3.5's other settings, measured out of tree
     * on this DTO: {@code {"alpha":1,"zeta":"z"}}, and {@code {"zeta":"z","alpha":1}} plus an
     * {@code InvalidDefinitionException} on read without the module (Phase 38 REVIEW-FIX, WR-02).
     */
    static final String BOOT35_CONSTRUCTOR_ONLY_BYTES = "{\"alpha\":1,\"zeta\":\"z\"}";

    @Test
    @DisplayName("WR-02: a constructor-only DTO round-trips (Boot 3.5 detected parameter names)")
    void constructorOnlyTypeRoundTrips() {
        String written = IdempotencyJson.write(new ConstructorOnlyDto(1, "z"));

        ConstructorOnlyDto back = IdempotencyJson.read(written, ConstructorOnlyDto.class);

        assertThat(back.getAlpha()).isEqualTo(1);
        assertThat(back.getZeta()).isEqualTo("z");
    }

    @Test
    @DisplayName("WR-02: creator properties are written first, in constructor order, as Boot 3.5 wrote them")
    void constructorOnlyTypeWritesTheBoot35Bytes() {
        assertThat(IdempotencyJson.write(new ConstructorOnlyDto(1, "z")))
                .as("constructor order (alpha, zeta), not field order (zeta, alpha)")
                .isEqualTo(BOOT35_CONSTRUCTOR_ONLY_BYTES);
    }

    private static final Comparator<OffsetDateTime> BY_INSTANT = Comparator.comparing(OffsetDateTime::toInstant);

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

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
