-- V55: webhook_subscription — vendor-registered outbound webhook endpoints
-- (COMMS-04). The data + management surface the delivery engine (22-05) and the
-- dashboard UI (22-06) build on.
--
-- RLS: ENABLE + FORCE ROW LEVEL SECURITY, tenant-scoped via the current_tenant_id()
-- helper (V51 form) — NOT the raw `current_setting('app.current_tenant_id', true)::uuid`
-- cast, which RlsContractTest.noPolicyUsesRawTenantGucCast fails the build on
-- (the latent 22P02 bug class removed in V51). A cross-tenant read of this table
-- is proven empty under the NOSUPERUSER role-downgrade by
-- WebhookSubscriptionRlsPolicyIntegrationTest.
--
-- signing_secret is stored PLAINTEXT on purpose: every webhook delivery (22-05)
-- must re-sign its body with HMAC-SHA256, so a one-way hash is unusable. FORCE
-- ROW LEVEL SECURITY is therefore the load-bearing confidentiality boundary for
-- the secret — mirroring the V50 idempotency_keys `response_body` PII rationale.
-- The secret is returned to the caller in plaintext ONLY once (on create + on
-- rotate) and is NEVER re-fetchable via GET/list (the read DTO omits it).
--
-- No `_aud` Envers mirror: like V47/V50, this is operational state, not audited
-- business history. Version is V55 because head is V51 and V52/V53 are reserved
-- for Phases 23/24 (V54 is notification consent, 22-02); the project-wide
-- spring.flyway.out-of-order=true absorbs the gap.

CREATE TABLE webhook_subscription (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID        NOT NULL,
    target_url           TEXT        NOT NULL,                    -- HTTPS-only + SSRF-blocked in the app layer (WebhookUrlValidator)
    event_types          TEXT[]      NOT NULL,                    -- selected WebhookEventType families (D-06)
    signing_secret       TEXT        NOT NULL,                    -- credential → FORCE RLS is load-bearing
    status               VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE / PAUSED / AUTO_PAUSED / REVOKED
    consecutive_failures INT         NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE webhook_subscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_subscription FORCE ROW LEVEL SECURITY;

CREATE POLICY webhook_subscription_tenant ON webhook_subscription
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- The worker (22-05) claims due deliveries by (tenant, status); index that path.
CREATE INDEX idx_webhook_subscription_tenant_status
    ON webhook_subscription (tenant_id, status);
