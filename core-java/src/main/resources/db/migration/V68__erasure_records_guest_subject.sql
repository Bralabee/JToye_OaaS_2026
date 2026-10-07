-- V68: Phase 31.1 / issue #777 (P0, decision D-02) — an Article-17 evidence row may name no customer.
--
-- WHY THIS EXISTS. Storefront guest checkout never creates a customers row: the subject's name,
-- address and phone live only on orders (and on reviews). Until #777 the DSAR fan-out matched the
-- subject digest against customers alone, so for every storefront subject it erased nothing and still
-- marked the request COMPLETED with tenantsErased=0. GdprService now also matches the addresses on
-- orders and reviews in each pinned tenant and anonymises them through the same routine the admin
-- erasure uses. That erasure must leave its PII-free evidence row like every other, but V42 declared
-- erasure_records.subject_customer_id NOT NULL, so the insert for a guest subject would throw, roll the
-- whole tenant's erasure back, and the worker would retry it until FAILED.
--
-- WHAT IT CHANGES. subject_customer_id becomes nullable. NULL means "the subject had no customers row
-- in this tenant": a guest storefront subject erased by the DSAR fan-out. Such a record carries the
-- DSAR subject digest (DsarSubjectDigest: trimmed, Locale.ROOT lower-cased, UTF-8 SHA-256) in
-- subject_email_sha256, so it can still be matched to the request that caused it.
--
-- WHAT IT DOES NOT CHANGE.
--   * No backfill and no DEFAULT. Every existing record names a real customer and keeps it; a record
--     never gains or loses a customer id after it is written.
--   * No _aud change: erasure_records is itself the audit artifact and has no Envers mirror (V42),
--     which DsarGuestErasureRlsIntegrationTest re-reads from pg_tables.
--   * No RLS change: the V42 SELECT/INSERT policies and the V67 photo-count UPDATE policy and write-once
--     guard are keyed on tenant_id and photos_deleted, never on subject_customer_id.
--   * No role, no extension, no data change.

ALTER TABLE erasure_records ALTER COLUMN subject_customer_id DROP NOT NULL;

COMMENT ON COLUMN erasure_records.subject_customer_id IS
    'The erased customers row, or NULL when the subject had no customers row in this tenant (a guest storefront subject erased by the DSAR fan-out, issue 777). A NULL-customer record carries the DSAR subject digest in subject_email_sha256.';

DO $$
BEGIN
    RAISE NOTICE 'V68 erasure_records guest subject applied: subject_customer_id is nullable '
        '(NULL = the subject had no customers row in this tenant); no row was backfilled.';
END $$;
