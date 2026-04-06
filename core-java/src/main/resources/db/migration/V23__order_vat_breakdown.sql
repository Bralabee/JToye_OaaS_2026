-- V23: Add VAT breakdown to orders
-- Orders now track subtotal, VAT rate, and VAT amount separately.
-- total_amount_pennies remains as the grand total (subtotal + VAT).

-- Step 1: Add columns with defaults so existing rows get populated immediately
ALTER TABLE orders
    ADD COLUMN subtotal_pennies BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN vat_rate VARCHAR(20) NOT NULL DEFAULT 'ZERO' CHECK (vat_rate IN ('ZERO', 'REDUCED', 'STANDARD', 'EXEMPT')),
    ADD COLUMN vat_amount_pennies BIGINT NOT NULL DEFAULT 0;

-- Step 2: Backfill — set subtotal to match existing total (pre-VAT orders)
UPDATE orders SET subtotal_pennies = total_amount_pennies WHERE subtotal_pennies = 0 AND total_amount_pennies > 0;

-- Step 3: Remove defaults so future inserts must provide values explicitly
ALTER TABLE orders
    ALTER COLUMN subtotal_pennies DROP DEFAULT,
    ALTER COLUMN vat_rate DROP DEFAULT,
    ALTER COLUMN vat_amount_pennies DROP DEFAULT;

-- Also add to audit table
ALTER TABLE orders_aud
    ADD COLUMN subtotal_pennies BIGINT,
    ADD COLUMN vat_rate VARCHAR(20),
    ADD COLUMN vat_amount_pennies BIGINT;
