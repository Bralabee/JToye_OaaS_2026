-- V21: Add denormalized item_count to orders
-- Fixes: "0 items" bug on tracking pages caused by lazy-loading order_items
-- through RLS without tenant context. Denormalized count avoids the join entirely.

ALTER TABLE orders ADD COLUMN item_count INTEGER NOT NULL DEFAULT 0;
COMMENT ON COLUMN orders.item_count IS 'Denormalized count of order items — avoids lazy-load RLS issues on public endpoints';

-- Backfill existing orders with actual item counts
UPDATE orders o SET item_count = (
    SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.id
);

-- Add to audit table
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS item_count INTEGER;
