-- V62: Phase 31 / plan 31-05 (LGL-01, decisions D-16 + D-17) — dsar_request, the intake queue
-- behind the published single point of contact for data-subject requests.
--
-- D-16 says the DSAR path is BUILT in this phase rather than promised: a published commitment to
-- a single point of contact, backed by nothing that can execute it, is the fail-open shape this
-- project keeps paying for. D-17 draws the boundary this table sits on, and it is the whole
-- design: INTAKE IS A REQUEST, EXECUTION IS BACKGROUND. A request thread lodges a row here and
-- stops. A scheduled worker (plan 31-09) reads the row, iterates tenants, and does the work. No
-- human ever holds cross-tenant read — which is how a single cross-tenant DSAR desk is reconciled
-- with a project that has refused a cross-tenant operator identity twice.
--
-- (Written WITHOUT dollar-brace placeholder syntax anywhere, comments included. Flyway substitutes
-- placeholders inside migration SQL INCLUDING COMMENTS, so naming a property in that form makes
-- the whole migration fail with "No value provided for placeholder" and takes the application down
-- at startup in every environment at once. V61 records the same trap, reproduced twice in one
-- session. This file also creates no extension, for the reason V61 states at length and
-- scripts/check-no-create-extension.sh enforces across the whole directory.)
--
-- V62 is the next free slot (head is V61) and is sequential, so the project-wide
-- spring.flyway.out-of-order=true does not interact. Plan 31-10 owns V63.
--
-- ============================================================================================
-- WHY THIS TABLE IS DELIBERATELY NOT TENANT-SCOPED, AND WHY RLS HERE WOULD BE WORSE
-- ============================================================================================
-- Every other table this project has added since V2 is tenant-scoped under ENABLE + FORCE ROW
-- LEVEL SECURITY, and the RlsContractTest schema walk exists precisely so that "we added a table
-- and forgot RLS" breaks the build. This table is an exception, and the exception is argued rather
-- than assumed:
--
--   1. It is PRE-TENANT BY CONSTRUCTION. An anonymous data subject lodges a request from the
--      public internet before any tenant is known — there is no JWT, no TenantContext, and no
--      app.current_tenant_id GUC on the connection. There is no value to put in a tenant_id
--      column at insert time, because the identity of the tenants concerned is exactly what the
--      background sweep is being asked to discover.
--
--   2. Its PURPOSE is to be actioned across every tenant. UK GDPR Articles 15 and 17 give the
--      subject one right against the controller, not one right per vendor they happened to buy
--      from. Splitting the row per tenant at intake would require the intake to already know the
--      answer.
--
--   3. Adding RLS here would not be "safer" — it would silently DISABLE the feature. With no
--      tenant_id there is no predicate to write, so a FORCE'd policy would return zero rows to
--      the very worker that must read them: the intake would keep returning 202, the queue would
--      keep filling, nothing would ever be actioned, and every test would stay green because a
--      dead table is indistinguishable from an empty one. That is the identical argument the
--      postcode_centroid entry records from the other direction, and the liveness failure mode
--      RlsContractTest.everyRlsEnabledTableHasAtLeastOnePolicy was added to catch.
--
-- So dsar_request is exempted BY ADDITION in RlsContractTest.EXEMPT_TABLES with a written
-- justification. The schema-walk assertion itself is NOT weakened, and no second exemption
-- mechanism is introduced. The exemption was proven load-bearing by removing it and watching the
-- sweep name this table.
--
-- ============================================================================================
-- THE PRIVACY PROPERTY THIS TABLE RESTS ON: ONLY A HASH IS EVER STORED
-- ============================================================================================
-- The intake is keyed by an email address, which makes this the single most important rule in the
-- schema: an intake table full of readable addresses is a NEW personal-data store created by a
-- privacy feature. V42 stated the rule for erasure_records — the address is kept only as a one-way
-- SHA-256 hex digest, never in readable form — and this table follows it verbatim. There is no
-- readable-address column here, and there must never be one; a lookup is performed by hashing the
-- supplied address and comparing digests.
--
-- The normalisation is part of the contract, because two systems must agree on it: the digest is
-- taken over the LOWER-CASED, TRIMMED address, UTF-8 encoded. DsarIntakeService owns that
-- normalisation so there is exactly one place that can get it wrong, and plan 31-09's worker must
-- compute the same digest over each tenant's customer rows to find matches.
--
-- response_body below is the OPAQUE acknowledgement returned to the caller. It is a constant: it
-- carries no request identifier, no address, and nothing derived from whether any tenant holds a
-- match. Storing a per-subject reference there would re-introduce the disclosure this endpoint's
-- whole shape exists to avoid.
--
-- No Envers _aud mirror, deliberately. erasure_records is already the Article-17 proof row and it
-- is itself the audit artifact; this table is an operational queue, and mirroring it would create
-- a SECOND long-lived store keyed by a data subject — the opposite of what a privacy feature
-- should leave behind.

CREATE TABLE dsar_request (
    id                        UUID         PRIMARY KEY,

    -- One-way SHA-256 hex digest of the lower-cased, trimmed subject address. NEVER readable.
    subject_email_sha256      CHAR(64)     NOT NULL,

    -- ACCESS (Article 15/20) or ERASURE (Article 17).
    request_type              VARCHAR(16)  NOT NULL,

    -- Lifecycle for the background worker. A request is not actionable until the subject has
    -- proven control of the address (T-31-05-02): an unverified erasure request is a weapon.
    status                    VARCHAR(32)  NOT NULL DEFAULT 'PENDING_VERIFICATION',

    -- The verification token is held as a digest with an expiry, for the same reason the address
    -- is: a readable token in the database is a bearer credential at rest.
    verification_token_sha256 CHAR(64),
    verification_expires_at   TIMESTAMPTZ,
    verified_at               TIMESTAMPTZ,

    -- Idempotency (see the unique index below). request_hash is the SHA-256 hex of the canonical
    -- request payload, so the same key reused with a DIFFERENT payload is rejected rather than
    -- silently queueing a second erasure.
    idempotency_key           VARCHAR(64),
    request_hash              CHAR(64),
    response_status           INT,
    response_body             TEXT,

    -- Worker bookkeeping. process_attempts mirrors media_asset.process_attempts (V60): it lets a
    -- sweep tell "never attempted" from "attempted and stalled" instead of guessing from age.
    process_attempts          INT          NOT NULL DEFAULT 0,
    received_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    claimed_at                TIMESTAMPTZ,
    completed_at              TIMESTAMPTZ,
    last_error                TEXT,

    CONSTRAINT ck_dsar_request_type
        CHECK (request_type IN ('ACCESS', 'ERASURE')),
    CONSTRAINT ck_dsar_request_status
        CHECK (status IN ('PENDING_VERIFICATION', 'VERIFIED', 'IN_PROGRESS',
                          'COMPLETED', 'FAILED', 'EXPIRED'))
);

COMMENT ON TABLE dsar_request IS
    'Platform-level UK-GDPR data-subject-request intake queue (Phase 31, D-16/D-17). '
    'Deliberately NOT tenant-scoped: an anonymous subject lodges a request before any tenant is '
    'known, and the request must be actioned across every tenant. No tenant_id, no RLS policy, no '
    'audit mirror; exempted BY ADDITION in RlsContractTest.EXEMPT_TABLES with a written '
    'justification, so the schema-walk sweep itself is never weakened. Holds no readable personal '
    'data: the subject is identified only by a one-way SHA-256 digest.';

COMMENT ON COLUMN dsar_request.subject_email_sha256 IS
    'SHA-256 hex digest of the LOWER-CASED, TRIMMED, UTF-8 encoded subject address. The readable '
    'address is never stored (the V42 erasure_records rule). Normalisation is owned by '
    'DsarIntakeService — any consumer matching against customer rows must reproduce it exactly.';

COMMENT ON COLUMN dsar_request.response_body IS
    'The OPAQUE acknowledgement returned to the caller, replayed verbatim on an Idempotency-Key '
    'repeat. Constant by design: it carries no request identifier and nothing derived from whether '
    'any tenant holds a match, so the endpoint cannot be used to enumerate which vendors hold an '
    'address.';

-- --------------------------------------------------------------------------------------------
-- Idempotency: enforced HERE, not in the shared idempotency_keys store.
-- --------------------------------------------------------------------------------------------
-- MEASURED, not assumed: IdempotencyService.execute() opens with
-- TenantContext.get().orElseThrow(MissingTenantContextException), and idempotency_keys (V50) is
-- keyed (tenant_id, endpoint, idempotency_key) under FORCE ROW LEVEL SECURITY. A tenant-less
-- caller therefore cannot be served by it at all — the request would 500 through
-- GlobalExceptionHandler.handleMissingTenantContext before reaching any storage. Weakening that
-- service to accept a null tenant would put a FORCE-RLS store into a state where its policy
-- predicate cannot match, which is the failure mode described above; creating a second
-- general-purpose idempotency store would leave two contracts to keep in step. So the constraint
-- lives on this table.
--
-- THE KEY ALONE, NOT (subject digest, key) — a deliberate, strictly-stronger substitution.
-- Keying the constraint with the subject digest would let the SAME Idempotency-Key carry a
-- DIFFERENT address and insert a second row: precisely the "silent second row" the contract
-- forbids. Under this index that case collides, the stored request_hash differs, and the caller
-- gets the typed payload-mismatch error instead. In the shared store the key is scoped by the
-- caller dimension (tenant_id); this endpoint has no caller dimension at all, so the key must be
-- unique for the endpoint outright.
CREATE UNIQUE INDEX uq_dsar_request_idempotency_key
    ON dsar_request (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- --------------------------------------------------------------------------------------------
-- The worker's claim query (plan 31-09): outstanding requests, oldest first.
-- --------------------------------------------------------------------------------------------
-- Partial on the outstanding rows, the shape V60 used for the media quarantine sweep. Completed
-- requests are retained as the record that the request was answered, but they are the bulk of the
-- table over time and never appear in a claim, so indexing them only enlarges the index.
CREATE INDEX idx_dsar_request_outstanding
    ON dsar_request (received_at)
    WHERE completed_at IS NULL;
