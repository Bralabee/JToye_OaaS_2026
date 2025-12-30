-- Fix RLS policies on audit tables to allow DELETE operations
-- When Envers creates a DELETE audit record (revtype=2), all entity fields are NULL
-- The INSERT policy must allow these NULL tenant_id values for DELETE records

-- Drop existing restrictive INSERT policies
DROP POLICY IF EXISTS shops_aud_insert_policy ON shops_aud;
DROP POLICY IF EXISTS products_aud_insert_policy ON products_aud;
DROP POLICY IF EXISTS financial_transactions_aud_insert_policy ON financial_transactions_aud;

-- Create permissive INSERT policies that allow:
-- 1. Normal INSERT/UPDATE audit records (revtype 0 or 1) with valid tenant_id
-- 2. DELETE audit records (revtype 2) with NULL tenant_id
-- The WITH CHECK clause uses true to allow all inserts from the application

CREATE POLICY shops_aud_insert_policy ON shops_aud
    FOR INSERT
    WITH CHECK (true);  -- Allow Envers to write all audit records including DELETEs

CREATE POLICY products_aud_insert_policy ON products_aud
    FOR INSERT
    WITH CHECK (true);  -- Allow Envers to write all audit records including DELETEs

CREATE POLICY financial_transactions_aud_insert_policy ON financial_transactions_aud
    FOR INSERT
    WITH CHECK (true);  -- Allow Envers to write all audit records including DELETEs

-- Note: SELECT policies remain restrictive and enforce tenant isolation
-- Only INSERTs are permissive to accommodate Envers behavior
