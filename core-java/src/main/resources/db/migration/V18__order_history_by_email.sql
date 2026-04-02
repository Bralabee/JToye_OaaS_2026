-- Allow customers to list their orders by email (for order history)
-- Extends the tracking RLS with a broader email-based lookup

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE tablename = 'orders' AND policyname = 'orders_customer_history'
    ) THEN
        CREATE POLICY orders_customer_history ON orders
            FOR SELECT
            USING (
                tenant_id = current_tenant_id()
                OR (
                    customer_email IS NOT NULL
                    AND customer_email = current_setting('app.customer_email', true)
                )
            );
    END IF;
END $$;

-- Index for email-based order lookups
CREATE INDEX IF NOT EXISTS idx_orders_customer_email ON orders(customer_email);
