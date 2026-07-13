package uk.jtoye.core.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.IdempotencyConflictException;
import uk.jtoye.core.exception.IdempotencyPayloadMismatchException;
import uk.jtoye.core.exception.MissingTenantContextException;
import uk.jtoye.core.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Generic, tenant-scoped {@code Idempotency-Key} orchestrator (Issue #204 /
 * AI-2) — the reusable mechanism behind the {@link Idempotent} contract.
 *
 * <p><b>Reserve-first, the house pattern.</b> {@link #execute} runs a
 * {@code INSERT ... ON CONFLICT DO NOTHING} into {@code idempotency_keys}
 * (V50, FORCE RLS), mirroring {@code OrderStateChangeListener} and
 * {@code PaymentService.handleWebhookEvent}. 1 row inserted ⇒ this is the
 * first request: run the supplied work, then stamp {@code response_status} +
 * serialized {@code response_body} onto the reserved row. 0 rows inserted ⇒
 * replay: re-read the row and return the stored response (or 409 while the
 * first request is still in-flight, 422 on a payload mismatch).
 *
 * <p><b>Why the reserve joins the create's transaction (do NOT use
 * REQUIRES_NEW).</b> {@code execute} is {@code @Transactional}; it invokes the
 * class-level {@code @Transactional} create service ({@code OrderService},
 * {@code CustomerService}) which joins the outer transaction
 * ({@code Propagation.REQUIRED}). So reserve → create → complete all commit or
 * roll back together on ONE connection. If the create throws, the reserved key
 * rolls back too — the V47 "successfully processed AT LEAST once" semantic — so
 * a genuine retry with the same key later succeeds instead of being blocked by
 * an orphan reservation. A {@code REQUIRES_NEW} reservation would leave that
 * orphan and defeat retries.
 *
 * <p><b>Defensive GUC pin (RESEARCH pitfall #1 — the #1 trap).</b> The store is
 * FORCE RLS and is touched via raw {@link JdbcTemplate}, which bypasses the JPA
 * {@code TenantSetLocalAspect}. In the web path {@code JwtTenantFilter} +
 * {@code TenantSetLocalAspect} SHOULD set {@code app.current_tenant_id} on the
 * connection, but aspect ordering for this new path is untested, and this
 * codebase has already shipped one live RLS-hiding bug on exactly the "the
 * aspect will set it" assumption (the OrderStateChangeListener N1 fix). So we
 * ALSO issue an explicit {@code SELECT set_config('app.current_tenant_id', ?,
 * true)} at the top of the transaction (mirroring
 * {@code OrderStateChangeListener}). Trivial cost; removes the entire class of
 * silent-RLS-hide failures.
 *
 * <p><b>Status.</b> All current adopters are creates, so a first request stamps
 * 201; a replay echoes the stored status. Parameterizing a non-201 status is a
 * documented follow-up (docs/idempotency.md).
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** Hardcoded this slice — every current adopter is a 201-returning create. */
    private static final int CREATE_STATUS = 201;

    private static final int MAX_KEY_LENGTH = 64;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public IdempotencyService(JdbcTemplate jdbcTemplate,
                              ObjectMapper objectMapper,
                              EntityManager entityManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    /**
     * Execute {@code work} under the {@code Idempotency-Key} contract.
     *
     * @param endpoint     the logical operation id stored in {@code endpoint}
     *                     (e.g. {@code "orders.create"})
     * @param key          the client-supplied {@code Idempotency-Key} (1..64 chars)
     * @param requestBody  the request payload — hashed to detect same-key/different-body reuse
     * @param responseType the response DTO type, for replay deserialization
     * @param work         the create to run exactly once for this key
     * @param <T>          the response DTO type
     * @return the fresh or replayed outcome (status + value)
     * @throws IllegalArgumentException           blank key or key longer than 64 chars
     * @throws IdempotencyConflictException       first request for this key still in-flight (409)
     * @throws IdempotencyPayloadMismatchException same key reused with a different body (422)
     */
    @Transactional
    public <T> IdempotencyOutcome<T> execute(String endpoint,
                                             String key,
                                             Object requestBody,
                                             Class<T> responseType,
                                             Supplier<T> work) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key must be 1.." + MAX_KEY_LENGTH + " chars");
        }

        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new MissingTenantContextException(
                        "Tenant context not set for idempotent operation " + endpoint));

        // Defensive GUC pin — see class Javadoc (RESEARCH pitfall #1).
        pinTenantGuc(tenantId);

        String requestHash = sha256Hex(serialize(requestBody));

        int inserted = jdbcTemplate.update(
                "INSERT INTO idempotency_keys (tenant_id, endpoint, idempotency_key, request_hash) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                tenantId, endpoint, key, requestHash);

        if (inserted == 1) {
            // First request: run the work inside this transaction, then stamp the
            // response onto the reserved row. Both commit/roll back together.
            T result = work.get();
            jdbcTemplate.update(
                    "UPDATE idempotency_keys SET response_status = ?, response_body = ? "
                            + "WHERE tenant_id = ? AND endpoint = ? AND idempotency_key = ?",
                    CREATE_STATUS, serialize(result), tenantId, endpoint, key);
            log.info("Idempotency reserve+complete: endpoint={} key={} status={}",
                    endpoint, key, CREATE_STATUS);
            return new IdempotencyOutcome<>(CREATE_STATUS, result);
        }

        // Replay: the row already exists (RLS-scoped to this tenant).
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT request_hash, response_status, response_body FROM idempotency_keys "
                        + "WHERE tenant_id = ? AND endpoint = ? AND idempotency_key = ?",
                tenantId, endpoint, key);

        Object storedStatus = row.get("response_status");
        if (storedStatus == null) {
            log.info("Idempotency in-flight race: endpoint={} key={} — first request not yet complete",
                    endpoint, key);
            throw new IdempotencyConflictException("Idempotency-Key request already in progress");
        }

        String storedHash = (String) row.get("request_hash");
        if (storedHash != null && !storedHash.equals(requestHash)) {
            log.info("Idempotency payload mismatch: endpoint={} key={} — different request body", endpoint, key);
            throw new IdempotencyPayloadMismatchException(
                    "Idempotency-Key reused with a different request payload");
        }

        int status = ((Number) storedStatus).intValue();
        String storedBody = (String) row.get("response_body");
        T value = deserialize(storedBody, responseType);
        log.info("Idempotency replay: endpoint={} key={} status={}", endpoint, key, status);
        return new IdempotencyOutcome<>(status, value);
    }

    /**
     * Explicitly set {@code app.current_tenant_id} on the transaction's
     * connection, mirroring {@code OrderStateChangeListener} — belt-and-braces
     * against the JdbcTemplate-bypasses-the-aspect trap.
     */
    private void pinTenantGuc(UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.toString());
                stmt.execute();
            }
        });
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize idempotent payload", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize stored idempotent response", e);
        }
    }

    /** SHA-256 hex (64 chars) of the given string. Byte-identical bodies match. */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
