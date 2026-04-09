-- V30: Add product_name to order_items for kitchen display readability
-- Denormalized: stored at order time so name is preserved even if product renamed later
ALTER TABLE order_items ADD COLUMN product_name VARCHAR(255);

-- Backfill from products table where possible
UPDATE order_items oi
SET product_name = p.name
FROM products p
WHERE oi.product_id = p.id AND oi.product_name IS NULL;

-- Set NOT NULL with default for any items without matching product
UPDATE order_items SET product_name = 'Unknown Product' WHERE product_name IS NULL;
ALTER TABLE order_items ALTER COLUMN product_name SET NOT NULL;
ALTER TABLE order_items ALTER COLUMN product_name SET DEFAULT 'Unknown Product';
