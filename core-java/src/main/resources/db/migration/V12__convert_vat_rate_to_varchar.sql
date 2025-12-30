-- V12: Convert vat_rate column from enum to VARCHAR with CHECK constraint
-- Reason: Hibernate EnumType.STRING mapping works better with VARCHAR than PostgreSQL native enum
-- Pattern: Same approach used for order_status in V6__fix_order_status_type.sql

-- Step 1: Add new VARCHAR column with CHECK constraint
ALTER TABLE financial_transactions
    ADD COLUMN vat_rate_temp VARCHAR(20) CHECK (vat_rate_temp IN ('ZERO', 'REDUCED', 'STANDARD', 'EXEMPT'));

-- Step 2: Copy data from enum column to VARCHAR column
UPDATE financial_transactions
SET vat_rate_temp = vat_rate::TEXT;

-- Step 3: Drop the old enum column
ALTER TABLE financial_transactions
    DROP COLUMN vat_rate;

-- Step 4: Rename the new column
ALTER TABLE financial_transactions
    RENAME COLUMN vat_rate_temp TO vat_rate;

-- Step 5: Make column NOT NULL
ALTER TABLE financial_transactions
    ALTER COLUMN vat_rate SET NOT NULL;

-- Note: We keep the vat_rate_enum type in the database for backward compatibility
-- but it's no longer actively used. Can be dropped in future cleanup migration if needed.
