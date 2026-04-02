-- Guest order tracking: allow public lookup by order_number + customer_email
-- Uses session variables for secure RLS bypass — both must match

-- Add permissive SELECT policy for guest order tracking
-- PostgreSQL OR's permissive policies: this works alongside existing orders RLS
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE tablename = 'orders' AND policyname = 'orders_guest_tracking'
    ) THEN
        CREATE POLICY orders_guest_tracking ON orders
            FOR SELECT
            USING (
                tenant_id = current_tenant_id()
                OR (
                    order_number IS NOT NULL
                    AND customer_email IS NOT NULL
                    AND order_number = current_setting('app.tracking_order_number', true)
                    AND customer_email = current_setting('app.tracking_email', true)
                )
            );
    END IF;
END $$;

-- Index for fast order number lookups (used by tracking endpoint)
CREATE INDEX IF NOT EXISTS idx_orders_order_number ON orders(order_number);
