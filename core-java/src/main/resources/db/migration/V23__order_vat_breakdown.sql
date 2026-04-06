-- V23: Add VAT breakdown to orders
-- Orders now track subtotal, VAT rate, and VAT amount separately.
-- total_amount_pennies remains as the grand total (subtotal + VAT).

ALTER TABLE orders
    ADD COLUMN subtotal_pennies BIGINT,
    ADD COLUMN vat_rate VARCHAR(20) DEFAULT 'ZERO' CHECK (vat_rate IN ('ZERO', 'REDUCED', 'STANDARD', 'EXEMPT')),
    ADD COLUMN vat_amount_pennies BIGINT DEFAULT 0;

-- Backfill: existing orders have no VAT applied, so subtotal = total
UPDATE orders SET subtotal_pennies = total_amount_pennies, vat_amount_pennies = 0 WHERE subtotal_pennies IS NULL;

ALTER TABLE orders
    ALTER COLUMN subtotal_pennies SET NOT NULL,
    ALTER COLUMN vat_rate SET NOT NULL,
    ALTER COLUMN vat_amount_pennies SET NOT NULL;

-- Also add to audit table
ALTER TABLE orders_aud
    ADD COLUMN subtotal_pennies BIGINT,
    ADD COLUMN vat_rate VARCHAR(20),
    ADD COLUMN vat_amount_pennies BIGINT;
