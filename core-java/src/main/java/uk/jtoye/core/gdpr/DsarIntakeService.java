package uk.jtoye.core.gdpr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import uk.jtoye.core.exception.IdempotencyConflictException;
import uk.jtoye.core.exception.IdempotencyPayloadMismatchException;
import uk.jtoye.core.gdpr.dto.DsarIntakeRequest;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records that a data subject asked — and does nothing else (Phase 31, D-16/D-17).
 *
 * <h2>The boundary this class exists to hold</h2>
 *
 * <b>Intake is a request; execution is background.</b> {@code ShopAccessService} records the
 * standing rule that a request thread never enters the declared-system scope — only background
 * entry points do — and D-17 makes that rule the whole design of the DSAR desk. A single
 * cross-tenant point of contact looks like it needs the cross-tenant operator identity this
 * project has refused twice; it does not, because <em>no human ever holds that reach</em>. This
 * class writes one row and returns. Plan 31-09's scheduled worker is the only thing that reads
 * those rows, and it reaches tenants one at a time by pinning
 * {@code app.current_tenant_id}, under FORCE row-level security exactly like every other caller.
 *
 * <h2>Why the response is a constant</h2>
 *
 * The acknowledgement is IDENTICAL for every accepted request: same fields, same values, no
 * identifier, nothing sourced from a lookup. It has to be. "Which of your vendors holds this
 * person's email address" is precisely the information the tenant wall exists to withhold, and an
 * intake that varies its status code, its body, or even a request id between the match and no-match
 * cases hands that answer to anybody with a browser. So this class performs <em>no lookup at all</em>
 * before responding — there is nothing for a response to be derived from.
 *
 * <h2>Why idempotency is not routed through the shared service</h2>
 *
 * MEASURED, not assumed:
 * {@code IdempotencyService.execute} opens with
 * {@code TenantContext.get().orElseThrow(MissingTenantContextException)}, and its store
 * {@code idempotency_keys} (V50) is keyed {@code (tenant_id, endpoint, idempotency_key)} under
 * FORCE row-level security. An anonymous caller has no tenant, so that path cannot serve this
 * endpoint at all — the request would become a 500 through
 * {@code GlobalExceptionHandler.handleMissingTenantContext} before reaching any storage. The shared
 * service is therefore left alone and no second general-purpose store is created: the constraint
 * lives on {@code dsar_request} itself, on the KEY ALONE (see V62 for why the key must not be
 * paired with the subject digest). The reserve idiom is the house
 * {@code INSERT ... ON CONFLICT DO NOTHING} that {@code IdempotencyService} and
 * {@code OrderStateChangeListener} both use, and the typed outcomes are the same ones the shared
 * contract already publishes, so a client sees one uniform contract regardless of which store
 * backs it.
 *
 * <h2>Deliberately not transactional</h2>
 *
 * The reserve is a single statement and the replay is a single read, so there is nothing to make
 * atomic; a second unique-index waiter blocks on the index and then reads a committed row.
 * {@code dsar_request} carries no row-level security, so no tenant GUC has to be pinned either —
 * and there is no tenant to pin.
 */
@Service
public class DsarIntakeService {

    private static final Logger log = LoggerFactory.getLogger(DsarIntakeService.class);

    /** The logical operation id, in the {@code orders.create} style — not the URL. */
    public static final String ENDPOINT = "gdpr.dsar.lodge";

    private static final int ACCEPTED = 202;
    private static final int MAX_KEY_LENGTH = 64;

    /**
     * The opaque acknowledgement. A single immutable instance, so it is impossible for a future
     * edit to accidentally make one caller's body differ from another's.
     *
     * <p>The prose is deliberately careful about two things: it does not promise a confirmation
     * email this slice does not yet send, and it states plainly that the answer carries no
     * information about whether data is held — which is both honest and the point.
     */
    private static final DsarIntakeAck ACK = new DsarIntakeAck(
            "received",
            "Your request has been recorded. Data-subject requests are verified before they are "
                    + "actioned, and we respond within the acknowledgement window shown. For your "
                    + "protection this response does not indicate whether any personal data is held.",
            30);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final String INSERT_SQL = """
            INSERT INTO dsar_request
                (id, subject_email_sha256, request_type, status, verification_token_sha256,
                 verification_expires_at, idempotency_key, request_hash, response_status, response_body)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String RESERVE_SQL = INSERT_SQL
            + " ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DsarVerificationMailer verificationMailer;

    /**
     * How long a subject has to prove control of the address before the request lapses. Injected,
     * never a hardcoded literal, because it is a published retention-adjacent period.
     */
    @Value("${jtoye.gdpr.dsar.verification-ttl-hours:168}")
    private long verificationTtlHours;

    public DsarIntakeService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                             DsarVerificationMailer verificationMailer) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.verificationMailer = verificationMailer;
    }

    /**
     * Lodge a request. Always returns the same acknowledgement, or throws one of the two typed
     * idempotency outcomes the shared contract already publishes.
     *
     * @param request        the validated payload
     * @param idempotencyKey the client-supplied {@code Idempotency-Key}, or {@code null}
     * @throws IdempotencyPayloadMismatchException the key was reused with a different payload (422)
     * @throws IdempotencyConflictException        the first request for this key is still in flight (409)
     */
    public DsarIntakeAck lodge(DsarIntakeRequest request, String idempotencyKey) {
        String subjectDigest = DsarSubjectDigest.of(request.email());

        // Hashed from the DIGEST, never from the readable address: this value is persisted, and a
        // payload fingerprint that could be reversed to an address would defeat the whole point of
        // storing only a digest in the first place.
        String requestHash = sha256Hex(subjectDigest + "|" + request.requestType().name());

        // The readable token exists only on this stack and in the subject's mailbox. Only its
        // digest is persisted — a readable token at rest is a bearer credential at rest.
        String verificationToken = freshVerificationToken();
        String verificationTokenDigest = sha256Hex(verificationToken);
        OffsetDateTime verificationExpiry = OffsetDateTime.now().plusHours(verificationTtlHours);

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            jdbcTemplate.update(INSERT_SQL,
                    UUID.randomUUID(), subjectDigest, request.requestType().name(),
                    DsarRequest.Status.PENDING_VERIFICATION.name(),
                    verificationTokenDigest, verificationExpiry,
                    null, requestHash, ACCEPTED, serialize(ACK));
            log.info("DSAR lodged: endpoint={} type={} keyed=false", ENDPOINT, request.requestType());
            deliverVerification(request, verificationToken);
            return ACK;
        }

        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be 1.." + MAX_KEY_LENGTH + " chars");
        }

        int inserted = jdbcTemplate.update(RESERVE_SQL,
                UUID.randomUUID(), subjectDigest, request.requestType().name(),
                DsarRequest.Status.PENDING_VERIFICATION.name(),
                verificationTokenDigest, verificationExpiry,
                idempotencyKey, requestHash, ACCEPTED, serialize(ACK));

        if (inserted == 1) {
            log.info("DSAR lodged: endpoint={} type={} keyed=true", ENDPOINT, request.requestType());
            deliverVerification(request, verificationToken);
            return ACK;
        }

        return replay(idempotencyKey, requestHash);
    }

    /**
     * Send the token, and ONLY on a row that was genuinely inserted (plan 31-09).
     *
     * <h2>Why this does not undo the opacity above</h2>
     *
     * The mail goes to the address the caller named, unconditionally, with content that does not
     * vary — no lookup happens here either, so there is still nothing for a response or a message to
     * be derived from. An attacker who lodges a request against somebody else's address learns
     * nothing: the token lands in the victim's mailbox, and the HTTP answer is the same constant it
     * has always been.
     *
     * <h2>Why it is gated on the insert</h2>
     *
     * An {@code Idempotency-Key} replay must not mint a second live token, or a retried POST would
     * quietly double the number of valid credentials pointing at one request. A replay returns the
     * stored acknowledgement and sends nothing.
     *
     * <h2>Why a failure here does not fail the request</h2>
     *
     * The row is already committed. {@link DsarVerificationMailer} swallows mail errors, as every
     * other mail path in this codebase does, so an SMTP outage leaves a recoverable state (the
     * subject can lodge again and receive a fresh token) rather than a 500 over work that succeeded.
     */
    private void deliverVerification(DsarIntakeRequest request, String verificationToken) {
        verificationMailer.sendVerification(
                request.email(), verificationToken, request.requestType(), verificationTtlHours);
    }

    /**
     * Zero rows inserted means the key is already taken. Re-read the reserved row and answer from
     * it — the original response verbatim, or the typed refusal.
     */
    private DsarIntakeAck replay(String idempotencyKey, String requestHash) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT request_hash, response_status, response_body FROM dsar_request "
                        + "WHERE idempotency_key = ?",
                idempotencyKey);

        if (rows.isEmpty()) {
            // Defence in depth. Unreachable while the reserve stamps the response in the SAME
            // statement (a second waiter blocks on the unique index and then reads a committed
            // row), but if that ever splits into reserve-then-complete, an unfinished row must
            // produce the honest 409 rather than a 500 from an empty result.
            throw new IdempotencyConflictException("Idempotency-Key request already in progress");
        }

        Map<String, Object> row = rows.get(0);
        Object storedStatus = row.get("response_status");
        if (storedStatus == null) {
            throw new IdempotencyConflictException("Idempotency-Key request already in progress");
        }

        String storedHash = (String) row.get("request_hash");
        if (storedHash != null && !storedHash.equals(requestHash)) {
            log.info("DSAR idempotency payload mismatch: endpoint={}", ENDPOINT);
            throw new IdempotencyPayloadMismatchException(
                    "Idempotency-Key reused with a different request payload");
        }

        log.info("DSAR idempotency replay: endpoint={} status={}", ENDPOINT, storedStatus);
        return deserialize((String) row.get("response_body"));
    }

    /**
     * The normalisation contract, in one place because two systems must agree on it: plan 31-09's
     * worker recomputes this digest over each tenant's customer rows, and a mismatch would make the
     * fan-out silently match nothing while every test stayed green.
     *
     * <p>31-09 moved the implementation into {@link DsarSubjectDigest} and this delegates to it.
     * The move is the point: a written contract is a rule two files can drift away from, whereas a
     * single shared implementation makes agreement STRUCTURAL. Nothing about the value changed —
     * still trim, then lower-case under {@code Locale.ROOT}, then SHA-256 over UTF-8.
     */
    static String normaliseAddress(String email) {
        return DsarSubjectDigest.normalise(email);
    }

    /**
     * A fresh high-entropy verification token. Only its digest is stored — a readable token at rest
     * is a bearer credential at rest, which is the same mistake as a readable address at rest.
     */
    private static String freshVerificationToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String serialize(DsarIntakeAck ack) {
        try {
            return objectMapper.writeValueAsString(ack);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize the DSAR acknowledgement", e);
        }
    }

    private DsarIntakeAck deserialize(String json) {
        try {
            return objectMapper.readValue(json, DsarIntakeAck.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize the stored DSAR acknowledgement", e);
        }
    }

    /**
     * Lowercase hex SHA-256. Delegates to {@link DsarSubjectDigest} so the intake and 31-09's
     * fan-out worker cannot drift apart — see {@link #normaliseAddress}.
     */
    private static String sha256Hex(String input) {
        return DsarSubjectDigest.sha256Hex(input);
    }

    /**
     * The opaque acknowledgement, and the entire response contract of this endpoint.
     *
     * <p>There is deliberately no request reference here. One would be convenient for a support
     * conversation and it is exactly what must not exist: an identifier minted per request is
     * harmless, but the moment anything is sourced from a match the response becomes an oracle, and
     * a field that is "usually constant" is a field a future edit will quietly make conditional.
     * The safe shape is a body with nothing in it that could vary.
     */
    @Schema(description = "Opaque acknowledgement. Identical for every accepted request — it "
            + "carries no reference and reveals nothing about whether data is held.")
    public record DsarIntakeAck(
            @Schema(description = "Always 'received'.", example = "received")
            String status,
            @Schema(description = "Human-readable acknowledgement.")
            String detail,
            @Schema(description = "Days within which the request will be answered.", example = "30")
            int acknowledgementWindowDays
    ) {
    }
}
