-- V36: Phase 17 — refunds table + Envers audit + orders CHECK rewrite + outbox exchange column.
--
-- Three concerns bundled into one atomic migration (per Phase 17 CONTEXT decisions):
--   1. refunds + refunds_aud tables with stored-first idempotency_key.
--   2. orders_status_check rewritten to include REFUNDED (V6 landmine).
--   3. payment_event_outbox.exchange column for per-row routing (UC-2 LOCKED).
--
-- Forward-only. RLS uses canonical `app.current_tenant_id` GUC (CORRECTION-3).

-- ============================================================
-- 1. refunds — stored-first idempotency entity
-- ============================================================
CREATE TABLE refunds (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL,
    order_id            UUID         NOT NULL REFERENCES orders(id),
    payment_intent_id   VARCHAR(255) NOT NULL,
    stripe_refund_id    VARCHAR(255),
    idempotency_key     VARCHAR(64)  NOT NULL,
    amount_pennies      BIGINT       NOT NULL CHECK (amount_pennies > 0),
    currency            VARCHAR(3)   NOT NULL DEFAULT 'gbp',
    reason              VARCHAR(64),
    reason_note         TEXT,
    status              VARCHAR(32)  NOT NULL DEFAULT 'CREATING',
    failure_reason      VARCHAR(255),
    requested_by        UUID,
    requested_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT refunds_idem_unique UNIQUE (tenant_id, idempotency_key)
);

CREATE UNIQUE INDEX idx_refunds_stripe_id
    ON refunds (stripe_refund_id)
    WHERE stripe_refund_id IS NOT NULL;
CREATE INDEX idx_refunds_order_id ON refunds (order_id);
CREATE INDEX idx_refunds_tenant   ON refunds (tenant_id);

-- RLS — canonical GUC per Phase 16.1 CORRECTION-3 + AUDIT-W0-04.
ALTER TABLE refunds ENABLE ROW LEVEL SECURITY;
ALTER TABLE refunds FORCE ROW LEVEL SECURITY;
CREATE POLICY refunds_tenant_policy ON refunds
    FOR ALL
    USING (tenant_id = current_setting('app.current_tenant_id', true)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true)::UUID);

-- ============================================================
-- 2. refunds_aud — Envers audit table (mirrors V11 pattern)
-- ============================================================
CREATE TABLE refunds_aud (
    id                  UUID         NOT NULL,
    rev                 INT          NOT NULL REFERENCES revinfo(rev),
    revtype             SMALLINT,
    tenant_id           UUID,
    order_id            UUID,
    payment_intent_id   VARCHAR(255),
    stripe_refund_id    VARCHAR(255),
    idempotency_key     VARCHAR(64),
    amount_pennies      BIGINT,
    currency            VARCHAR(3),
    reason              VARCHAR(64),
    reason_note         TEXT,
    status              VARCHAR(32),
    failure_reason      VARCHAR(255),
    requested_by        UUID,
    requested_at        TIMESTAMP WITH TIME ZONE,
    updated_at          TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id, rev)
);

-- Audit RLS per AUDIT-W0-05 / Phase 16.1: ENABLE + FORCE on every _aud table.
ALTER TABLE refunds_aud ENABLE ROW LEVEL SECURITY;
ALTER TABLE refunds_aud FORCE ROW LEVEL SECURITY;
CREATE POLICY refunds_aud_tenant_policy ON refunds_aud
    FOR ALL
    USING (tenant_id IS NULL OR tenant_id = current_setting('app.current_tenant_id', true)::UUID)
    WITH CHECK (tenant_id IS NULL OR tenant_id = current_setting('app.current_tenant_id', true)::UUID);

-- ============================================================
-- 3. orders_status_check — drop V6 constraint, recreate with REFUNDED
--     (V6 landmine: state-machine transitions to REFUNDED otherwise fail
--      with 23514 CHECK violation at the DB layer.)
-- ============================================================
ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('DRAFT', 'PENDING', 'CONFIRMED', 'PREPARING', 'READY', 'COMPLETED', 'CANCELLED', 'REFUNDED'));

-- ============================================================
-- 4. payment_event_outbox.exchange — per-row routing column
--     (UC-2 LOCKED: reuse single outbox table, route by exchange.)
--     Default 'payment.events' preserves existing behaviour for any
--     in-flight rows; 17-02 will write 'order.events' for refund rows.
-- ============================================================
ALTER TABLE payment_event_outbox
    ADD COLUMN exchange VARCHAR(128) NOT NULL DEFAULT 'payment.events';
