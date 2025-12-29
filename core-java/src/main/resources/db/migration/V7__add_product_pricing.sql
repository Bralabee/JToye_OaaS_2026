-- V7: Add product pricing support
-- This removes the hardcoded $10.00 price and allows per-product pricing

-- Add price_pennies column to products table
ALTER TABLE products
ADD COLUMN price_pennies BIGINT NOT NULL DEFAULT 1000;

-- Add price_pennies to audit table
ALTER TABLE products_aud
ADD COLUMN price_pennies BIGINT;

-- Add helpful comment
COMMENT ON COLUMN products.price_pennies IS 'Product price in pennies (e.g., 1000 = $10.00, 2500 = $25.00)';

-- Add unique constraint on order_number to prevent collisions
ALTER TABLE orders
ADD CONSTRAINT uk_orders_order_number UNIQUE (order_number);

-- Update existing products to have default price (maintains backward compatibility)
UPDATE products SET price_pennies = 1000 WHERE price_pennies IS NULL;

COMMENT ON COLUMN orders.order_number IS 'Unique order number generated at order creation';
