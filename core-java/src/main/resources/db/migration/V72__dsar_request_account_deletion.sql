-- V72: Phase 31.1 / plan 31.1-11 (decision D-03, issue #777) — dsar_request records whether the
-- subject's sign-in account has been deleted, so an ERASURE request is reported complete only once
-- that is confirmed.
--
-- (Written WITHOUT dollar-brace placeholder syntax anywhere, comments included: Flyway substitutes
-- placeholders inside migration SQL INCLUDING COMMENTS, so naming a property that way aborts startup
-- in every environment — the trap V61, V62 and V70 record. No extension, no role, no RLS change.)
--
-- V72 is the slot 31.1-01 reserved for this change (V68..V75 belong to phase 31.1, in wave order).
--
-- ============================================================================================
-- WHY
-- ============================================================================================
-- After #777's data fix (V68, GdprService.eraseSubjectByDigest) an erased customer could still sign
-- in with their customer-realm Keycloak account and see their now-anonymised orders. D-03: erasure
-- DELETES that account, after the data erasure has committed and best-effort, the way #102/V49
-- deprovisions a vendor user — a Keycloak outage must never roll back the erasure. The request is
-- not complete until the deletion is confirmed or the account is proven absent; otherwise it is
-- recorded as outstanding and retried on the next sweep.
--
-- ============================================================================================
-- THE COLUMNS
-- ============================================================================================
-- account_deletion_status — the outcome of the LAST attempt, written by DsarFanoutWorker:
--   DELETED         the customer-realm account(s) whose stored email equals the verified address
--                   were deleted (a 404 on the delete counts: the account is already gone);
--   NONE_FOUND      the customer realm holds no account for the verified address;
--   OUTSTANDING     the attempt failed (Keycloak unreachable, an error response, or no readable
--                   subject address); the request is released for retry, and parked FAILED after
--                   the configured maximum number of attempts;
--   NOT_CONFIGURED  the Keycloak admin seam is switched off in this runtime, so the deletion could
--                   not be attempted. Treated as outstanding, NEVER as complete.
--   NULL            not an erasure, or no attempt yet. Every row predating V72 is NULL.
-- account_deletion_attempts — how many times the deletion step has run for this request.
--
-- Only DELETED and NONE_FOUND accompany COMPLETED. The CHECK below constrains the vocabulary; the
-- pairing with status is the worker's, and DsarAccountDeletionIntegrationTest proves it.
--
-- Nothing here identifies the subject: no address, no Keycloak user id. The account is looked up
-- by the address held encrypted in subject_email_ciphertext (V70), which is dropped at the
-- terminal state as before.
--
-- NO BACKFILL: the status is nullable and historic rows stay NULL ("not attempted"); attempts
-- defaults to 0, which is true of every historic row. dsar_request is deliberately not
-- tenant-scoped (V62), so no RLS policy changes and there is no _aud mirror to extend.

ALTER TABLE dsar_request
    ADD COLUMN account_deletion_status VARCHAR(16);

ALTER TABLE dsar_request
    ADD CONSTRAINT ck_dsar_request_account_deletion_status
        CHECK (account_deletion_status IS NULL
               OR account_deletion_status IN ('DELETED', 'NONE_FOUND', 'OUTSTANDING', 'NOT_CONFIGURED'));

ALTER TABLE dsar_request
    ADD COLUMN account_deletion_attempts INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN dsar_request.account_deletion_status IS
    'D-03: outcome of the last customer-realm sign-in account deletion attempt for an ERASURE '
    'request. DELETED or NONE_FOUND accompany COMPLETED; OUTSTANDING and NOT_CONFIGURED leave the '
    'request open for retry. NULL = not an erasure, or not yet attempted.';

COMMENT ON COLUMN dsar_request.account_deletion_attempts IS
    'D-03: how many times the sign-in account deletion step has run for this request.';
