-- V47: processed_order_events — idempotent ORDER_STATE_CHANGED consumption
-- (QA-council run disc-20260712-010550, FIX-2 / finding H1).
--
-- The transactional outbox is at-least-once BY DESIGN (a crash between AMQP
-- publish and commit re-publishes the row; PaymentEventOutboxFlusher javadoc:
-- "consumers must be idempotent"). OrderStateChangeListener had no dedup, so
-- every redelivery re-sent the customer email, re-broadcast the KDS STOMP
-- topic and re-incremented business metrics — the amplifier that turned the
-- C1 flusher defect into a several-hundred-email customer-facing storm.
--
-- The event carries no id, but a SEMANTIC key exists: the guard-veto-hardened
-- order state machine (#177) never revisits a state, so
-- (tenant_id, order_id, new_status) occurs at most once per legitimate order
-- lifecycle. The listener INSERTs this key ON CONFLICT DO NOTHING at the top
-- of its transaction (mirroring the processed_stripe_events precedent, V35);
-- 0 rows inserted => duplicate delivery => skip ALL side effects. The INSERT
-- lives INSIDE the listener transaction on purpose: if a side effect throws,
-- the dedup row rolls back too and broker redelivery retries cleanly (the DLQ
-- bounds it) — "successfully processed at least once", not "seen once".
--
-- RLS: unlike processed_stripe_events (infrastructure-scoped, no tenant), the
-- key here IS tenant data, so the table is ENABLE+FORCE RLS with the standard
-- tenant policy (V33 pattern) — project non-negotiable.
--
-- Growth: one row per real state transition. Pruning is deferred to the
-- existing scheduled-cleanup housekeeping (ops note, not a blocker).

CREATE TABLE processed_order_events (
    tenant_id    UUID         NOT NULL,
    order_id     UUID         NOT NULL,
    new_status   VARCHAR(32)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, order_id, new_status)
);

ALTER TABLE processed_order_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE processed_order_events FORCE ROW LEVEL SECURITY;

CREATE POLICY processed_order_events_tenant ON processed_order_events
    FOR ALL
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
