package uk.jtoye.core.common.idempotency;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON format of the idempotency store: the request fingerprint and the stored response body.
 *
 * <p>STUB (38-10 RED): plain Jackson-3 defaults. The GREEN commit replaces this mapper.
 */
final class IdempotencyJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private IdempotencyJson() {
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize idempotent payload", e);
        }
    }

    static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize stored idempotent response", e);
        }
    }
}
