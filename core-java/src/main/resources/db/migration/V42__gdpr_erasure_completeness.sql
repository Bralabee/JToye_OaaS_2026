-- V42: GDPR erasure completeness (Issue #84 [P1-2]) — schema half.
--
-- Companion to a CODE change (GdprService) that extends the UK-GDPR Article-17
-- erasure flow so it (1) reaches guest storefront orders carrying PII with no
-- customer_id, (2) scrubs pre-erasure PII from the Envers orders_aud/customers_aud
-- history for the subject, (3) physically deletes review photos from S3/MinIO, and
-- (4) persists a durable, PII-free erasure record.
--
-- Three parts, all forward-only and re-run safe:
--   (a) erasure_records — the durable, PII-free proof-of-erasure table (tenant-scoped,
--       FORCE RLS). It is itself the audit record, so it is NOT Envers-audited and has
--       no _aud mirror.
--   (b) SELECT + INSERT RLS policies on erasure_records (V14 current_tenant_id() style).
--   (c) The MISSING tenant-scoped UPDATE policies on orders_aud / customers_aud so the
--       GDPR PII scrub can run under FORCE RLS for the NOSUPERUSER app role. Today those
--       audit tables carry ONLY SELECT + INSERT policies (V35), so a scrub UPDATE is
--       DENIED — this migration adds the deliberate, tenant-scoped exception.

-- ----------------------------------------------------------------------------
-- (a) erasure_records — durable, PII-free proof of erasure
-- ----------------------------------------------------------------------------
-- subject_email_sha256 stores ONLY a one-way SHA-256 hex hash of the erased
-- email — NEVER the plaintext, which would re-introduce the very PII we erase.
CREATE TABLE IF NOT EXISTS erasure_records (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID        NOT NULL,
    subject_customer_id  UUID        NOT NULL,
    subject_email_sha256 CHAR(64),
    orders_anonymised    INT         NOT NULL DEFAULT 0,
    reviews_anonymised   INT         NOT NULL DEFAULT 0,
    aud_rows_scrubbed    INT         NOT NULL DEFAULT 0,
    photos_deleted       INT         NOT NULL DEFAULT 0,
    erased_by            VARCHAR(255),
    erased_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_erasure_records_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id)
);

CREATE INDEX IF NOT EXISTS idx_erasure_records_tenant ON erasure_records(tenant_id);

-- ----------------------------------------------------------------------------
-- (b) erasure_records tenant isolation — ENABLE + FORCE RLS (V35 pattern) with
--     current_tenant_id()-based SELECT + INSERT policies (V2/V14 pattern).
-- ----------------------------------------------------------------------------
ALTER TABLE erasure_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE erasure_records FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'erasure_records' AND policyname = 'erasure_records_select_policy'
    ) THEN
        CREATE POLICY erasure_records_select_policy ON erasure_records
            FOR SELECT
            USING (tenant_id = current_tenant_id());
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'erasure_records' AND policyname = 'erasure_records_insert_policy'
    ) THEN
        CREATE POLICY erasure_records_insert_policy ON erasure_records
            FOR INSERT
            WITH CHECK (tenant_id = current_tenant_id());
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- (c) orders_aud / customers_aud — tenant-scoped UPDATE policies.
--
-- These two audit tables have FORCE ROW LEVEL SECURITY (V35) and carry ONLY
-- SELECT + INSERT policies, so under the NOSUPERUSER app role a scrub UPDATE is
-- silently DENIED. Envers audit history is append-only by design; this is the
-- DELIBERATE GDPR Article-17 exception — a targeted PII-column UPDATE only
-- (name redacted, email/phone/notes nulled). Envers stays fully enabled; we do
-- NOT delete audit rows or disable auditing. The policies are tenant-scoped on
-- current_tenant_id() so an admin of tenant A can never scrub tenant B rows; the
-- service ALSO carries an explicit tenant_id in every scrub WHERE (defense-in-depth).
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'orders_aud' AND policyname = 'orders_aud_update_policy'
    ) THEN
        -- GDPR Art-17 exception: targeted PII scrub of append-only orders_aud history.
        CREATE POLICY orders_aud_update_policy ON orders_aud
            FOR UPDATE
            USING (tenant_id = current_tenant_id())
            WITH CHECK (tenant_id = current_tenant_id());
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'customers_aud' AND policyname = 'customers_aud_update_policy'
    ) THEN
        -- GDPR Art-17 exception: targeted PII scrub of append-only customers_aud history.
        CREATE POLICY customers_aud_update_policy ON customers_aud
            FOR UPDATE
            USING (tenant_id = current_tenant_id())
            WITH CHECK (tenant_id = current_tenant_id());
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- Runtime marker (no dedicated audit/log table exists in this schema)
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    RAISE NOTICE 'V42 GDPR erasure completeness applied: erasure_records table created '
        '(tenant-scoped, FORCE RLS, PII-free SHA-256 email hash), plus tenant-scoped '
        'UPDATE policies on orders_aud/customers_aud enabling the deliberate Article-17 '
        'PII scrub of append-only audit history (Envers stays enabled).';
END $$;
