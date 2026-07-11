-- V46: Outbox reliability (Issue #93 [P2-2]).
--
-- Three defects in the V31/V36 outbox drain path:
--   1. The @Scheduled flusher ran on every replica with a plain SELECT —
--      two replicas could read the same PENDING rows and double-publish.
--      Fixed in code with FOR UPDATE SKIP LOCKED; this migration adds the
--      claim-path index that query needs.
--   2. MAX_ATTEMPTS=5 x 5s tick meant ~25s of broker outage permanently
--      flipped rows to FAILED with no resurrection. next_attempt_at gives
--      the flusher an exponential-backoff schedule, and a scheduled
--      resurrection pass returns non-poison FAILED rows to PENDING.
--   3. FAILED conflated "broker kept timing out" (retryable) with "payload
--      is corrupt" (never retryable). poison=TRUE marks the latter so the
--      resurrection pass cannot loop on unrecoverable rows.
--
-- RLS: payment_event_outbox already has ENABLE+FORCE RLS with the
-- tenant-scoped policy from V33; new columns inherit it. The entity is not
-- Envers-audited, so no _aud mirror is required.

ALTER TABLE payment_event_outbox
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW();

ALTER TABLE payment_event_outbox
    ADD COLUMN poison BOOLEAN NOT NULL DEFAULT FALSE;

-- Claim path: PENDING rows whose backoff window has elapsed, oldest first.
-- Partial index keeps it tiny — SENT/FAILED rows never qualify.
CREATE INDEX idx_payment_event_outbox_claim
    ON payment_event_outbox (next_attempt_at, created_at)
    WHERE status = 'PENDING';

-- Historical FAILED rows that died on payload corruption must not be
-- resurrected into a deserialize-fail loop. Any other pre-V46 FAILED row
-- (retry exhaustion) is left poison=FALSE so the resurrection pass gives it
-- a fresh lease. This UPDATE runs as the migration role; on FORCE-RLS
-- tables that only works for superuser/BYPASSRLS roles, so belt-and-braces:
-- rows missed here are self-healing — resurrection retries them once, the
-- flusher re-hits the deserialization failure and poisons them permanently.
UPDATE payment_event_outbox
SET poison = TRUE
WHERE status = 'FAILED'
  AND (last_error LIKE 'payload deserialization failed%'
       OR last_error LIKE '%serialization failed%');
