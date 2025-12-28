-- Add unique constraints to enforce data integrity

-- Ensure SKU is unique per tenant
CREATE UNIQUE INDEX IF NOT EXISTS idx_products_tenant_sku ON products(tenant_id, sku);

-- Ensure shop names are unique per tenant (optional but recommended)
CREATE UNIQUE INDEX IF NOT EXISTS idx_shops_tenant_name ON shops(tenant_id, name);
