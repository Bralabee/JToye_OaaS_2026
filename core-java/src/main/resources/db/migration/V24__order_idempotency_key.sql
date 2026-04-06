-- V24: Add idempotency key to orders to prevent double-submit
-- Unique constraint scoped to tenant_id + idempotency_key.

ALTER TABLE orders
    ADD COLUMN idempotency_key VARCHAR(64);

CREATE UNIQUE INDEX idx_orders_idempotency
    ON orders (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ALTER TABLE orders_aud
    ADD COLUMN idempotency_key VARCHAR(64);
