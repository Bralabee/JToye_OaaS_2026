-- V58: dedicated media_event_outbox for the safe async image pipeline (IMG-02, Phase 24 / 24-03).
--
-- WHY A DEDICATED TABLE (not the shared payment_event_outbox): the shared
-- PaymentEventOutboxFlusher.publishRow deserializes each row by a CLOSED SET of
-- exchanges (order.events / onboarding.events / else -> PaymentEvent). A media
-- payload would fall into the else branch, be cast to PaymentEvent, throw
-- JsonProcessingException, and be poison-dead-lettered (outbox_flusher_dispatch_trap,
-- recurring V25->V44->V57 class in spirit / RESEARCH Pitfall 3). A DEDICATED outbox
-- has exactly ONE destination exchange (media.events), so its flusher needs NO
-- dispatch branch and the trap cannot occur — and the hardened PaymentEventOutboxFlusher
-- is left byte-for-byte untouched.
--
-- Table shape mirrors payment_event_outbox (V31 + V46 reliability hardening: attempts /
-- next_attempt_at exponential backoff / poison / last_error / sent_at) with an asset_id
-- instead of the payment event_type/routing_key/exchange columns (single fixed exchange +
-- routing key). RLS posture mirrors V33 payment_event_outbox: ENABLE + FORCE RLS gated
-- through the SAFE current_tenant_id() helper (NEVER a raw ::uuid cast — the V51 hardening
-- guarded by RlsContractTest.noPolicyUsesRawTenantGucCast). No _aud mirror: like the payment
-- outbox this is a high-churn transient dispatch table, not audited business state.

CREATE TABLE IF NOT EXISTS media_event_outbox (
    id              UUID         PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    asset_id        UUID         NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    poison          BOOLEAN      NOT NULL DEFAULT false,
    last_error      TEXT,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Backs the flusher's claim query: WHERE status='PENDING' AND next_attempt_at <= now()
-- ORDER BY created_at ASC ... FOR UPDATE SKIP LOCKED.
CREATE INDEX IF NOT EXISTS idx_media_event_outbox_claim
    ON media_event_outbox (status, next_attempt_at, created_at);

ALTER TABLE media_event_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE media_event_outbox FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'media_event_outbox' AND policyname = 'media_event_outbox_tenant'
  ) THEN
    CREATE POLICY media_event_outbox_tenant ON media_event_outbox
        FOR ALL
        USING      (tenant_id = current_tenant_id())   -- V51 safe helper, NOT a raw ::uuid cast
        WITH CHECK (tenant_id = current_tenant_id());
  END IF;
END $$;
