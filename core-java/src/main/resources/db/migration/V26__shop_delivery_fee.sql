-- V26: Add delivery fee to shops and orders
-- Shops set a flat delivery fee. Orders track the fee separately.

ALTER TABLE shops
    ADD COLUMN delivery_fee_pennies BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN free_delivery_threshold_pennies BIGINT;

ALTER TABLE orders
    ADD COLUMN delivery_fee_pennies BIGINT NOT NULL DEFAULT 0;

-- Backfill: existing orders had no delivery fee
-- (default 0 is correct — no change needed)

-- Drop defaults so future inserts are explicit
ALTER TABLE shops ALTER COLUMN delivery_fee_pennies DROP DEFAULT;

ALTER TABLE orders ALTER COLUMN delivery_fee_pennies DROP DEFAULT;

-- Audit table
ALTER TABLE orders_aud ADD COLUMN delivery_fee_pennies BIGINT;
