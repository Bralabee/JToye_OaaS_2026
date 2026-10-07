-- V70: Phase 31.1 / plan 31.1-07 (decision D-19, owner ruling 2026-10-05) — dsar_request gains the
-- subject's address, ENCRYPTED, for as long as the request is open and not one moment longer.
--
-- (Written WITHOUT dollar-brace placeholder syntax anywhere, comments included. Flyway substitutes
-- placeholders inside migration SQL INCLUDING COMMENTS, so naming a property in that form makes the
-- whole migration fail with "No value provided for placeholder" and takes the application down at
-- startup in every environment at once — the trap V61 and V62 record. This file creates no
-- extension either: the encryption happens in the application, with the JDK, and the database only
-- ever sees ciphertext.)
--
-- V70 is the slot 31.1-01 reserved for this change (V68..V75 belong to phase 31.1, in wave order).
--
-- ============================================================================================
-- WHAT THIS AMENDS, AND WHY
-- ============================================================================================
-- V62 stated the privacy property this table rests on: the subject is identified only by a one-way
-- SHA-256 digest, "There is no readable-address column here, and there must never be one". That
-- rule is AMENDED here, by owner ruling D-19, and the amendment is deliberately narrow. It is not
-- relaxed, and the digest stays exactly as it was: matching is still digest against digest, and
-- nothing in this migration lets anyone look a subject up by address.
--
-- What changed is that the worker must be able to REACH the subject, and a one-way digest cannot be
-- reversed into somewhere to send a message. Four things need the address:
--
--   1. D-01: an ACCESS (Article 15/20) request is answered by emailing the subject a link to their
--      export. The link has to be sent to someone.
--   2. D-03: an ERASURE request also deletes the subject's sign-in account, and the identity
--      provider is searched by address, not by our digest.
--   3. #777: the subject is told when their request has been completed.
--   4. An Article 15 request from somebody no vendor holds still needs an answer: "we hold nothing
--      about you" is a reply the controller owes, and it can only be sent to the address the request
--      came from.
--
-- ============================================================================================
-- WHAT IS STORED, WHERE THE KEY IS, AND FOR HOW LONG
-- ============================================================================================
-- STORED: subject_email_ciphertext, the address as typed and trimmed, AES-256-GCM encrypted by
-- uk.jtoye.core.gdpr.DsarCipher. A fresh 12-byte IV per value and a 128-bit tag; the associated data
-- binds the purpose and this row's id, so a ciphertext copied onto another row does not decrypt. The
-- column holds IV || ciphertext-with-tag. The readable address is never stored, logged or returned.
--
-- KEY: outside the database, always. It comes from the property jtoye.gdpr.dsar.encryption-key
-- (environment DSAR_ENCRYPTION_KEY) and the application refuses to start without a valid one. A dump
-- or a backup of this table therefore yields ciphertext and nothing else.
--
-- HOW LONG: until the request reaches a terminal state. The column is set to NULL in the SAME
-- statement that moves the row to COMPLETED or FAILED (DsarFanoutWorker), or to EXPIRED
-- (DsarRequestExpirySweep: an address lodged and never verified must not persist either — before
-- this migration nothing ever wrote EXPIRED). That is not left to the application alone: the CHECK
-- constraint below makes a terminal row that still holds an address something the database refuses
-- to store. The retention schedule (docs/retention-manifest.json) carries the period.
--
-- ============================================================================================
-- THE TWO ALTERNATIVES, AND WHY EACH WAS REJECTED
-- ============================================================================================
--   A. Derive the address from the rows the fan-out matches. Rejected: when NOTHING matches there is
--      no row to take it from, so the one subject who most needs a reply — "we hold nothing" — is
--      the one who could never get one. It would also make the reply depend on whether a vendor
--      held the data, which is the very disclosure V62's opaque acknowledgement exists to prevent.
--   B. Make the verification link double as the download link, so no address is needed later.
--      Rejected: it contradicts D-01. The verification email goes out at intake, before anything has
--      been produced, and a link that is both "prove it is you" and "here is your data" is a bearer
--      credential to the export sitting in a mailbox from the first minute.
--
-- Existing rows need no backfill: every row predating this migration has a NULL ciphertext, which
-- the CHECK admits in every status.

ALTER TABLE dsar_request
    ADD COLUMN subject_email_ciphertext BYTEA;

ALTER TABLE dsar_request
    ADD CONSTRAINT ck_dsar_request_ciphertext_terminal
        CHECK (status NOT IN ('COMPLETED', 'FAILED', 'EXPIRED') OR subject_email_ciphertext IS NULL);

COMMENT ON COLUMN dsar_request.subject_email_ciphertext IS
    'The subject address (as typed, trimmed) as AES-256-GCM ciphertext: IV (12 bytes) || ciphertext '
    'with 128-bit tag; associated data binds the purpose and this row id. The key is held outside '
    'the database. Transient by rule (D-19, amending V62): NULL in every terminal state, enforced by '
    'ck_dsar_request_ciphertext_terminal. Never readable at rest.';
