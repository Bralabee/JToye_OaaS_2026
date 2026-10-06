package uk.jtoye.core.common.idempotency;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The FROZEN JSON format of the idempotency store: the request fingerprint
 * ({@code idempotency_keys.request_hash = sha256(write(requestBody))}) and the stored response body
 * ({@code response_body = write(result)}, read back with {@link #read}).
 *
 * <h2>Why it is frozen (Phase 38, Spring Boot 4.1 migration)</h2>
 *
 * Both values are PERSISTED and outlive the build that wrote them. A client that reserved a key
 * against one pod retries against another, possibly one deployed later. Until Phase 38 the format
 * was whatever the app-wide {@code ObjectMapper} happened to write; Jackson 3 changes those bytes
 * (alphabetical property order among others), so following the app-wide mapper would turn every
 * pre-deploy reservation into a 422 "same key, different body" until it expired, on order and
 * customer creation and the MCP write tools. The format is therefore owned HERE, by one mapper that
 * no {@code spring.jackson.*} key, Boot default or app-wide decision (38-05) can reach.
 *
 * <h2>What it reproduces</h2>
 *
 * The bytes of Boot 3.5's auto-configured Jackson-2 {@code ObjectMapper}: Jackson 3's own
 * Jackson-2-defaults builder, plus the settings Boot 3.5 applied on top of raw Jackson 2:
 * <ul>
 *   <li>constructor parameter names detected ({@code MapperFeature.DETECT_PARAMETER_NAMES}), which is
 *       what Boot 3.5's registered {@code ParameterNamesModule} did. It decides whether a
 *       constructor-only DTO can be read at all, and puts its creator properties first, in
 *       constructor order;</li>
 *   <li>dates and durations as ISO strings, not timestamps;</li>
 *   <li>unknown properties ignored;</li>
 *   <li>no default view inclusion.</li>
 * </ul>
 * {@code IdempotencyFingerprintGoldenTest} proves it against fixtures a Boot 3.5.16 pod wrote
 * (jackson2-golden, 38-01 capture commit {@code e12177e1}): every adopter's request hash, and every
 * stored response type byte for byte in both directions. It also pins a constructor-only DTO, a shape
 * no 38-01 fixture covers, against bytes measured from a Jackson-2 mapper with
 * {@code ParameterNamesModule}.
 *
 * <h2>The rule for any future change</h2>
 *
 * Never edit this mapper in place: a changed byte silently invalidates every stored hash. A new
 * format needs a deliberate dual-hash window, in which a second accepted hash is checked beside the
 * old one until every reservation written in the old format has expired, and only then the old
 * hash is dropped. Any edit here must keep {@code IdempotencyFingerprintGoldenTest} green.
 *
 * <p><b>The one in-place edit, and why it was allowed.</b> {@code DETECT_PARAMETER_NAMES} was added
 * in Phase 38 review (WR-02), BEFORE this mapper shipped: no Boot-4 pod had stored a hash or body
 * with it, and the golden test stayed green with all 7 stored hashes and all 4 stored bodies
 * unchanged, so the edit moved no byte any stored row depends on. It made the mapper MORE faithful
 * to the Boot-3.5 format it claims, not different from it. From the first Boot-4 release onward the
 * rule above applies without exception.
 */
final class IdempotencyJson {

    private static final JsonMapper MAPPER = JsonMapper.builderWithJackson2Defaults()
            // Boot 3.5 registered ParameterNamesModule (spring-boot-starter-json ships it;
            // Jackson2ObjectMapperBuilder registers it), so constructor parameter names were detected.
            // builderWithJackson2Defaults() turns this off; without it a constructor-only DTO cannot
            // be read and writes its properties in field order instead of creator order (WR-02).
            .enable(MapperFeature.DETECT_PARAMETER_NAMES)
            // Boot 3.5 JacksonAutoConfiguration: java.time values as ISO strings, not timestamps.
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            // Boot 3.5 JacksonAutoConfiguration: a stored body with an extra property still reads.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Boot 3.5 JacksonAutoConfiguration: properties without @JsonView are not in every view.
            .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
            .build();

    private IdempotencyJson() {
    }

    /** The fingerprint / stored-body string. Unchecked {@link JacksonException} is wrapped as before. */
    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize idempotent payload", e);
        }
    }

    /** A stored response body, read back into its DTO for a replay. */
    static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize stored idempotent response", e);
        }
    }
}
