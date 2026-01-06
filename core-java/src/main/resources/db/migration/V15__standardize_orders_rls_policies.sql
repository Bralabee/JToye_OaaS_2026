-- Standardize orders and order_items RLS policies to use current_tenant_id() function
-- This completes the RLS policy standardization across all tables
-- Root cause: V5 (orders) used current_setting() with text comparison instead of current_tenant_id()

-- ============================================================================
-- ORDERS TABLE RLS POLICY STANDARDIZATION
-- ============================================================================

-- Drop existing orders RLS policies
DROP POLICY IF EXISTS orders_select_policy ON orders;
DROP POLICY IF EXISTS orders_insert_policy ON orders;
DROP POLICY IF EXISTS orders_update_policy ON orders;
DROP POLICY IF EXISTS orders_delete_policy ON orders;

-- Recreate policies using current_tenant_id() function (returns UUID)
-- This matches the pattern used in shops, products, financial_transactions, customers
CREATE POLICY orders_select_policy ON orders
    FOR SELECT
    USING (tenant_id = current_tenant_id());

CREATE POLICY orders_insert_policy ON orders
    FOR INSERT
    WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY orders_update_policy ON orders
    FOR UPDATE
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY orders_delete_policy ON orders
    FOR DELETE
    USING (tenant_id = current_tenant_id());

-- ============================================================================
-- ORDER_ITEMS TABLE RLS POLICY STANDARDIZATION
-- ============================================================================

-- Drop existing order_items RLS policies
DROP POLICY IF EXISTS order_items_select_policy ON order_items;
DROP POLICY IF EXISTS order_items_insert_policy ON order_items;
DROP POLICY IF EXISTS order_items_update_policy ON order_items;
DROP POLICY IF EXISTS order_items_delete_policy ON order_items;

-- Recreate policies using current_tenant_id() function (returns UUID)
CREATE POLICY order_items_select_policy ON order_items
    FOR SELECT
    USING (tenant_id = current_tenant_id());

CREATE POLICY order_items_insert_policy ON order_items
    FOR INSERT
    WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY order_items_update_policy ON order_items
    FOR UPDATE
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

CREATE POLICY order_items_delete_policy ON order_items
    FOR DELETE
    USING (tenant_id = current_tenant_id());

-- ============================================================================
-- VERIFICATION AND DOCUMENTATION
-- ============================================================================

-- Ensure RLS is still enabled and forced (defensive check)
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders FORCE ROW LEVEL SECURITY;

ALTER TABLE order_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_items FORCE ROW LEVEL SECURITY;

-- Add documentation comments
COMMENT ON POLICY orders_select_policy ON orders IS 'RLS policy: Users can only SELECT orders from their tenant (standardized to use current_tenant_id())';
COMMENT ON POLICY orders_insert_policy ON orders IS 'RLS policy: Users can only INSERT orders for their tenant (standardized to use current_tenant_id())';
COMMENT ON POLICY orders_update_policy ON orders IS 'RLS policy: Users can only UPDATE orders from their tenant (standardized to use current_tenant_id())';
COMMENT ON POLICY orders_delete_policy ON orders IS 'RLS policy: Users can only DELETE orders from their tenant (standardized to use current_tenant_id())';

COMMENT ON POLICY order_items_select_policy ON order_items IS 'RLS policy: Users can only SELECT order items from their tenant (standardized to use current_tenant_id())';
COMMENT ON POLICY order_items_insert_policy ON order_items IS 'RLS policy: Users can only INSERT order items for their tenant (standardized to use current_tenant_id())';
COMMENT ON POLICY order_items_update_policy ON order_items IS 'RLS policy: Users can only UPDATE order items from their tenant (standardized to use current_tenant_id())';
COMMENT ON POLICY order_items_delete_policy ON order_items IS 'RLS policy: Users can only DELETE order items from their tenant (standardized to use current_tenant_id())';

-- ============================================================================
-- STANDARDIZATION COMPLETE
-- ============================================================================
-- All core tables now use consistent RLS policy pattern:
--   - shops, products, financial_transactions (original V2)
--   - customers (standardized in V14)
--   - orders, order_items (standardized in V15)
--
-- Pattern: tenant_id = current_tenant_id()
-- Benefits:
--   1. Consistent UUID comparison (no type casting needed)
--   2. Centralized logic in current_tenant_id() function
--   3. Easier to maintain and understand
--   4. Better query planner optimization
-- ============================================================================
