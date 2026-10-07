-- V75: Phase 31.1 / plan 31.1-16 (decision D-01, issue #778 P0) — dsar_access_export, the single-use,
-- expiring, encrypted store behind the link an Article 15 request is answered with.
--
-- (Written WITHOUT dollar-brace placeholder syntax anywhere, comments included. Flyway substitutes
-- placeholders inside migration SQL INCLUDING COMMENTS, so naming a property in that form makes the
-- whole migration fail with "No value provided for placeholder" and takes the application down at
-- startup in every environment at once — the trap V61, V62 and V70 record. No extension, no role, no
-- RLS policy: see below.)
--
-- V75 is the slot 31.1-01 reserved for this change (V68..V75 belong to phase 31.1).
--
-- ============================================================================================
-- WHAT THIS IS FOR
-- ============================================================================================
-- Until this migration every verified ACCESS request sat in dsar_request for ever: the fan-out worker
-- logged "ACCESS delivery is not implemented" each sweep and the statutory one-month clock ran out for
-- every requester. D-01 decided the channel. The worker assembles the subject's data across every
-- tenant into ONE document, stores it here encrypted, and emails the verified address a link carrying
-- a single-use token. The personal data is never sent by email, and never attached. The download
-- endpoint, the page and the purge belong to plan 31.1-17; this table is the contract they read.
--
-- ============================================================================================
-- WHY THIS TABLE IS DELIBERATELY NOT TENANT-SCOPED, AND WHY RLS HERE WOULD BE WORSE
-- ============================================================================================
-- The V62 argument, from the read side:
--
--   1. It is CROSS-TENANT BY CONSTRUCTION. One row is ONE document for ONE subject, holding a section
--      for every vendor that holds them — UK GDPR Article 15 gives the subject one right against the
--      controller, not one per vendor, and Article 15(1)(c) requires the recipients to be named. There
--      is no single tenant_id the row could carry.
--
--   2. It is READ BY AN ANONYMOUS TOKEN HOLDER. The download (31.1-17) is opened from an email link by
--      somebody with no JWT, no TenantContext and no app.current_tenant_id GUC; possession of the
--      token is the only credential. There is no tenant to pin.
--
--   3. A FORCE'd policy would therefore return NOTHING. With no tenant_id there is no predicate to
--      write, so the download would find no row, every link ever emailed would be dead, and every
--      test would stay green because a dead table is indistinguishable from an empty one — the
--      liveness failure RlsContractTest.everyRlsEnabledTableHasAtLeastOnePolicy exists to catch.
--
-- The tenant wall is not weakened. The only code that reads tenant data into this table is the
-- background worker, which reaches each tenant by iterating tenants and pinning the GUC one at a
-- time under FORCE row-level security, like every other caller; no human and no request thread ever
-- holds cross-tenant read (Phase 31 D-17).
--
-- So dsar_access_export is exempted BY ADDITION in RlsContractTest.EXEMPT_TABLES with a written
-- justification. The schema-walk assertion is NOT weakened and no second exemption mechanism is
-- introduced; the exemption is proven load-bearing by removing it and watching the sweep name this
-- table (plan 31.1-16 Task 1).
--
-- ============================================================================================
-- WHAT PROTECTS IT INSTEAD
-- ============================================================================================
--   * The token is stored ONLY as its SHA-256 hex digest (token_sha256), the V62 verification-token
--     rule: a readable token at rest is a bearer credential. The readable token exists only in the
--     email and in the URL fragment of the link, which a browser never sends to a server.
--   * The payload is ENCRYPTED (payload_ciphertext): AES-256-GCM by uk.jtoye.core.gdpr.DsarCipher
--     under purpose ACCESS_EXPORT, with the request id as associated data, the key outside the
--     database (V70). A dump or a backup of this table yields digests and ciphertext.
--   * Single use: consumed_at is stamped and the payload NULLed on the first download (31.1-17). The
--     CHECK below makes "consumed but still holding the data" something the database refuses to
--     store.
--   * Expiry: expires_at (jtoye.gdpr.dsar.export-link-ttl-hours, default 168 hours), and a purge that
--     NULLs the payload of every unconsumed export past it and stamps purged_at (31.1-17).
--   * One export per request: dsar_request_id is UNIQUE. A retry (the email failed to send) REPLACES
--     the token, payload and expiry on the same row, so an old token stops working the moment a new
--     one is issued.
--
-- No Envers _aud mirror, deliberately: a second, long-lived store keyed by a data subject would defeat
-- the purpose of a table whose whole design is to hold the data briefly and then not at all.
--
-- ON DELETE CASCADE: an export cannot outlive the request it answers.

CREATE TABLE dsar_access_export (
    id                  UUID         PRIMARY KEY,

    -- The request this export answers. UNIQUE: a retry replaces, it never accumulates.
    dsar_request_id     UUID         NOT NULL UNIQUE
                                     REFERENCES dsar_request (id) ON DELETE CASCADE,

    -- SHA-256 hex digest of the single-use download token. NEVER the readable token.
    token_sha256        CHAR(64)     NOT NULL UNIQUE,

    -- IV || AES-256-GCM ciphertext-with-tag of the export document (format jtoye-dsar-export/1).
    -- NULL once consumed or purged.
    payload_ciphertext  BYTEA,

    expires_at          TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    consumed_at         TIMESTAMPTZ,
    purged_at           TIMESTAMPTZ,

    CONSTRAINT ck_dsar_access_export_consumed_payload
        CHECK (consumed_at IS NULL OR payload_ciphertext IS NULL)
);

-- The purge (31.1-17) selects unconsumed rows past their expiry.
CREATE INDEX idx_dsar_access_export_expires_at ON dsar_access_export (expires_at);

COMMENT ON TABLE dsar_access_export IS
    'D-01 / #778: the single-use, expiring, encrypted Article 15 export behind an emailed link. Not tenant-scoped by design (one cross-tenant document per subject, read by an anonymous token holder); exempted by addition in RlsContractTest.EXEMPT_TABLES. Token stored only as SHA-256; payload AES-256-GCM (DsarCipher ACCESS_EXPORT).';
COMMENT ON COLUMN dsar_access_export.token_sha256 IS
    'SHA-256 hex of the single-use download token. The readable token is never stored.';
COMMENT ON COLUMN dsar_access_export.payload_ciphertext IS
    'IV || AES-256-GCM ciphertext of the export JSON (purpose ACCESS_EXPORT, request id as associated data). NULL once consumed or purged.';
