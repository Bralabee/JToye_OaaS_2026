-- V54: notification consent / suppression governance (Phase 22, COMMS-03, D-03).
--
-- Two tenant-scoped tables back the may-we-send gate the platform lacks:
--
--   notification_suppression — per-category one-click-unsubscribe OPT-OUT rows.
--     A row (tenant, recipient, category) means "do NOT send this recipient this
--     category again". Transactional categories (ORDERS/ONBOARDING/FINANCIAL) are
--     default-ON under legitimate interest, so ONLY the presence of a suppression
--     row silences them.
--
--   marketing_opt_in — explicit marketing OPT-IN rows. Marketing is refused by
--     default (PECR): a MARKETING send is allowed ONLY when an opt-in row exists
--     AND no suppression row exists.
--
-- BOUNDED BY CONSTRUCTION, NOT TIME-PRUNED: both tables carry a UNIQUE key
-- (suppression: tenant_id+recipient+category; opt-in: tenant_id+recipient) so the
-- house `INSERT ... ON CONFLICT DO NOTHING` reserve idiom makes writes idempotent
-- and the row count is bounded by (recipients x categories). A GDPR/PECR opt-out
-- must NOT expire, so — unlike webhook_delivery — there is deliberately no
-- retention window / prune job on suppression (threat T-22-02-04). No `_aud`
-- Envers mirror: a consent/dedup store is not audited (same posture as V47/V50).
--
-- RLS: both tables ENABLE + FORCE ROW LEVEL SECURITY, policy FOR ALL keyed on the
-- SAFE helper current_tenant_id() (V51 form) — NEVER the raw
-- `current_setting('app.current_tenant_id', true)::uuid` cast, which
-- RlsContractTest.noPolicyUsesRawTenantGucCast fails the build on (Issue #113 /
-- 22P02 bug class). recipient is an email address (PII), so FORCE RLS is
-- load-bearing: a cross-tenant read would be a PII disclosure. Proven under the
-- NOSUPERUSER role-downgrade by ConsentTablesRlsPolicyIntegrationTest for EACH
-- table.
--
-- Versioning: head is V51; V52 (shop_staff, Phase 23) and V53 (media_asset,
-- Phase 24) are RESERVED for phases that execute first-in-sequence, so Comms
-- takes V54 under the project-wide spring.flyway.out-of-order=true (already set
-- in all profiles).

-- ============================================================
-- notification_suppression — per-category opt-out
-- ============================================================
CREATE TABLE notification_suppression (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL,
    recipient  TEXT        NOT NULL,
    category   VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, recipient, category)
);

ALTER TABLE notification_suppression ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_suppression FORCE ROW LEVEL SECURITY;

CREATE POLICY notification_suppression_tenant ON notification_suppression
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- ============================================================
-- marketing_opt_in — explicit marketing consent
-- ============================================================
CREATE TABLE marketing_opt_in (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    recipient   TEXT        NOT NULL,
    opted_in_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, recipient)
);

ALTER TABLE marketing_opt_in ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketing_opt_in FORCE ROW LEVEL SECURITY;

CREATE POLICY marketing_opt_in_tenant ON marketing_opt_in
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
