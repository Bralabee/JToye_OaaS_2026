-- V56: webhook_delivery — one durable row per (subscription, event) delivery
-- attempt (COMMS-05). The dedicated per-(subscription,event) delivery log the
-- @Scheduled WebhookDeliveryWorker (22-05) drains: a hostile/failing endpoint's
-- rows back off independently so it can NEVER head-of-line block deliveries to a
-- healthy subscription (this is why it is NOT the single ordered payment outbox).
--
-- RLS: ENABLE + FORCE ROW LEVEL SECURITY, tenant-scoped via the current_tenant_id()
-- helper (V51 form) — NOT the raw tenant-GUC ::uuid cast, which
-- RlsContractTest.noPolicyUsesRawTenantGucCast fails the build on (the latent
-- 22P02 bug class removed in V51). The payload column carries a full-entity
-- snapshot (OrderDto etc. — customer PII), so FORCE RLS is the load-bearing
-- cross-tenant confidentiality boundary; a cross-tenant read is proven empty
-- under the NOSUPERUSER role-downgrade by WebhookDeliveryRlsPolicyIntegrationTest.
--
-- No `_aud` Envers mirror: like V47/V50/V55 this is operational delivery state,
-- not audited business history — and it is pruned by a bounded retention window
-- (WebhookRetentionCleanup, #107) so it cannot grow unbounded. Version is V56
-- because head is V55 (V52/V53 reserved for Phases 23/24, V54 = notification
-- consent); the project-wide spring.flyway.out-of-order=true absorbs the gap.
--
-- Status lifecycle: PENDING (inserted by WebhookFanoutListener per matching
-- ACTIVE subscription) -> DELIVERED (2xx) | RETRYING (transient failure, backed
-- off via next_attempt_at) | FAILED (max-attempts exhausted). A manual replay
-- inserts a NEW row (is_replay=true, replay_of = original id) leaving the
-- original row's history intact.

CREATE TABLE webhook_delivery (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL,
    subscription_id  UUID        NOT NULL,
    event_id         UUID        NOT NULL,                     -- envelope id (receiver dedupe key)
    event_type       VARCHAR(48) NOT NULL,                     -- e.g. order.state.changed
    payload          TEXT        NOT NULL,                     -- the EXACT signed bytes (serialize-once, Pitfall 6)
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',   -- PENDING / DELIVERED / RETRYING / FAILED
    attempt_count    INT         NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),       -- backoff schedule; row invisible to the claim until due
    last_http_status INT,                                      -- last response code (status only, never the secret)
    last_error       TEXT,
    is_replay        BOOLEAN     NOT NULL DEFAULT FALSE,
    replay_of        UUID,                                     -- original delivery id when is_replay
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),       -- retention prunes by this column
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE webhook_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_delivery FORCE ROW LEVEL SECURITY;

CREATE POLICY webhook_delivery_tenant ON webhook_delivery
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The worker claims due rows by (status, next_attempt_at) — index that path.
CREATE INDEX idx_webhook_delivery_claim ON webhook_delivery (status, next_attempt_at);
-- Retention prune scans by created_at.
CREATE INDEX idx_webhook_delivery_created_at ON webhook_delivery (created_at);
-- Delivery-log browse + replay lookups filter by subscription.
CREATE INDEX idx_webhook_delivery_subscription ON webhook_delivery (subscription_id);
