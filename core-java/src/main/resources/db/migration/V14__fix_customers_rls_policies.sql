-- Fix customers RLS policies to use current_tenant_id() function
-- This aligns customers table policies with shops, products, and orders tables
-- Root cause: V9 used current_setting() with text comparison instead of current_tenant_id()

-- Drop existing customers RLS policies
DROP POLICY IF EXISTS customers_select_policy ON customers;
DROP POLICY IF EXISTS customers_insert_policy ON customers;
DROP POLICY IF EXISTS customers_update_policy ON customers;
DROP POLICY IF EXISTS customers_delete_policy ON customers;

-- Recreate policies using current_tenant_id() function (returns UUID)
-- This matches the pattern used in V2 for shops, products, financial_transactions
CREATE POLICY customers_select_policy ON customers
    FOR SELECT
    USING (tenant_id = current_tenant_id());

CREATE POLICY customers_insert_policy ON customers
    FOR INSERT
    WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY customers_update_policy ON customers
    FOR UPDATE
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY customers_delete_policy ON customers
    FOR DELETE
    USING (tenant_id = current_tenant_id());

-- Verification: Ensure RLS is still enabled and forced
-- (Should already be set from V9, but this is defensive)
ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers FORCE ROW LEVEL SECURITY;

-- Comments for documentation
COMMENT ON POLICY customers_select_policy ON customers IS 'RLS policy: Users can only SELECT customers from their tenant';
COMMENT ON POLICY customers_insert_policy ON customers IS 'RLS policy: Users can only INSERT customers for their tenant';
COMMENT ON POLICY customers_update_policy ON customers IS 'RLS policy: Users can only UPDATE customers from their tenant';
COMMENT ON POLICY customers_delete_policy ON customers IS 'RLS policy: Users can only DELETE customers from their tenant';
