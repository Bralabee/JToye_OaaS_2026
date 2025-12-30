-- Add customer_id column to orders_aud table
-- Fix for Envers audit tracking of orders.customer_id relationship

ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS customer_id UUID;

-- Add index for performance
CREATE INDEX IF NOT EXISTS idx_orders_aud_customer ON orders_aud(customer_id);

-- Comment for documentation
COMMENT ON COLUMN orders_aud.customer_id IS 'Audit history of customer_id FK from orders table';
