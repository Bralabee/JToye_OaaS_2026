-- V20: Add per-shop product assignment and inventory tracking
-- Fixes: Products currently show on ALL shops within a tenant (no shop_id FK).
-- Adds: Stock quantity tracking for inventory management.

-- 1. Add shop_id to products (nullable for backward compat — existing products remain tenant-wide)
ALTER TABLE products ADD COLUMN shop_id UUID REFERENCES shops(id) ON DELETE SET NULL;
CREATE INDEX idx_products_shop ON products(shop_id);
COMMENT ON COLUMN products.shop_id IS 'Optional FK to shop — NULL means product is available across all tenant shops';

-- 2. Add inventory tracking
ALTER TABLE products ADD COLUMN quantity_in_stock INTEGER DEFAULT NULL;
COMMENT ON COLUMN products.quantity_in_stock IS 'NULL = unlimited stock (no tracking). 0 = out of stock. Positive = available units.';

-- 3. Add shop_id to products_aud for Envers audit trail
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS shop_id UUID;
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS quantity_in_stock INTEGER;
