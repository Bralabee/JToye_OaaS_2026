package uk.jtoye.core.gdpr;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A UK-GDPR data-subject request lodged from the public internet (Phase 31, D-16/D-17).
 *
 * <p><b>Intake is a request; execution is background.</b> A row here records only that somebody
 * asked. Nothing is looked up, matched or erased on the request thread — {@code ShopAccessService}
 * records the standing rule that a request thread never enters
 * {@link uk.jtoye.core.security.access.SystemPrincipal#asSystem}, and D-17 honours it exactly: the
 * scheduled fan-out worker (plan 31-09) is the only thing that reads these rows and the only thing
 * that reaches across tenants, one pinned tenant at a time. That is how a single cross-tenant DSAR
 * desk exists in a codebase that has twice refused a cross-tenant operator identity — no human
 * ever holds that reach.
 *
 * <p><b>Not tenant-scoped, on purpose.</b> {@code dsar_request} (V62) carries no {@code tenant_id}
 * and no RLS policy: an anonymous subject lodges the request before any tenant is known, and the
 * request exists to be actioned across all of them. It is exempted BY ADDITION in
 * {@code RlsContractTest.EXEMPT_TABLES} with a written justification — the schema-walk sweep is
 * never weakened. The migration states why RLS here would be worse than none: with no
 * {@code tenant_id} there is no predicate to write, so a FORCE'd policy would return zero rows to
 * the very worker that must read them.
 *
 * <p><b>No readable address is ever stored.</b> The subject is identified only by
 * {@link #getSubjectEmailSha256()}, the SHA-256 hex digest of the <em>lower-cased, trimmed,
 * UTF-8</em> address — the rule V42 established for {@link ErasureRecord}, and the single most
 * important property of this table, because an intake keyed by an address is otherwise a new
 * personal-data store created by a privacy feature. {@link DsarIntakeService} owns the
 * normalisation; anything matching these digests against customer rows must reproduce it exactly.
 *
 * <p>Deliberately NOT {@code @Audited}: {@link ErasureRecord} is already the Article-17 proof row,
 * and an Envers mirror of this queue would be a second long-lived store keyed by a data subject.
 */
@Entity
@Table(name = "dsar_request")
public class DsarRequest {

    /** What the subject asked for. Mirrors the {@code ck_dsar_request_type} check constraint. */
    public enum RequestType {
        /** UK GDPR Articles 15 / 20 — access and portability. */
        ACCESS,
        /** UK GDPR Article 17 — erasure. */
        ERASURE
    }

    /**
     * Where the request has got to. Mirrors the {@code ck_dsar_request_status} check constraint.
     *
     * <p>{@link #PENDING_VERIFICATION} is the entry state and it is load-bearing: an
     * <em>unverified</em> erasure request is a destructive action anybody on the internet can aim
     * at anybody else (threat T-31-05-02), so control of the address is proven before the fan-out
     * worker will touch it.
     */
    public enum Status {
        PENDING_VERIFICATION,
        VERIFIED,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        EXPIRED
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** One-way SHA-256 hex digest of the lower-cased, trimmed address — never the readable form. */
    @Column(name = "subject_email_sha256", nullable = false, length = 64)
    private String subjectEmailSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 16)
    private RequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    /** Digest of the verification token, for the same reason the address is a digest. */
    @Column(name = "verification_token_sha256", length = 64)
    private String verificationTokenSha256;

    @Column(name = "verification_expires_at")
    private OffsetDateTime verificationExpiresAt;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    /**
     * The client-supplied {@code Idempotency-Key}, unique across the endpoint (see V62's
     * {@code uq_dsar_request_idempotency_key}). Null when the caller supplied none.
     */
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    /** SHA-256 hex of the canonical request payload — a same-key/different-payload reuse is 422. */
    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    /** The opaque acknowledgement, replayed verbatim on an {@code Idempotency-Key} repeat. */
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "process_attempts", nullable = false)
    private int processAttempts;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "last_error")
    private String lastError;

    /** JPA no-arg constructor. */
    protected DsarRequest() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSubjectEmailSha256() {
        return subjectEmailSha256;
    }

    public void setSubjectEmailSha256(String subjectEmailSha256) {
        this.subjectEmailSha256 = subjectEmailSha256;
    }

    public RequestType getRequestType() {
        return requestType;
    }

    public void setRequestType(RequestType requestType) {
        this.requestType = requestType;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getVerificationTokenSha256() {
        return verificationTokenSha256;
    }

    public void setVerificationTokenSha256(String verificationTokenSha256) {
        this.verificationTokenSha256 = verificationTokenSha256;
    }

    public OffsetDateTime getVerificationExpiresAt() {
        return verificationExpiresAt;
    }

    public void setVerificationExpiresAt(OffsetDateTime verificationExpiresAt) {
        this.verificationExpiresAt = verificationExpiresAt;
    }

    public OffsetDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(OffsetDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public int getProcessAttempts() {
        return processAttempts;
    }

    public void setProcessAttempts(int processAttempts) {
        this.processAttempts = processAttempts;
    }

    public OffsetDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(OffsetDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public OffsetDateTime getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(OffsetDateTime claimedAt) {
        this.claimedAt = claimedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
