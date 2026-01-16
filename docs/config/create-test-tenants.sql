-- Create Test Tenants and Sample Data
-- This script creates two tenants (A and B) with shops and products to test RLS isolation

\c jtoye

-- Enable row level security on tables
ALTER TABLE shops ENABLE ROW LEVEL SECURITY;
ALTER TABLE products ENABLE ROW LEVEL SECURITY;
ALTER TABLE shops_aud ENABLE ROW LEVEL SECURITY;
ALTER TABLE products_aud ENABLE ROW LEVEL SECURITY;

-- Create Tenant A
INSERT INTO tenants (id, name, created_at, updated_at)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'Tenant A Corp', NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

-- Create Tenant B
INSERT INTO tenants (id, name, created_at, updated_at)
VALUES
    ('00000000-0000-0000-0000-000000000002', 'Tenant B Ltd', NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

-- Set tenant context for Tenant A operations
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000001';

-- Create shops for Tenant A
INSERT INTO shops (id, tenant_id, name, created_at, updated_at)
VALUES
    ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'Tenant A - Main Store', NOW(), NOW()),
    ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'Tenant A - Outlet Store', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Create products for Tenant A
INSERT INTO products (id, tenant_id, shop_id, name, price, created_at, updated_at)
VALUES
    ('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Tenant A - Product 1', 99.99, NOW(), NOW()),
    ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Tenant A - Product 2', 149.99, NOW(), NOW()),
    ('20000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 'Tenant A - Outlet Product', 79.99, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Reset tenant context
RESET app.current_tenant_id;

-- Set tenant context for Tenant B operations
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000002';

-- Create shops for Tenant B
INSERT INTO shops (id, tenant_id, name, created_at, updated_at)
VALUES
    ('11000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 'Tenant B - Flagship Store', NOW(), NOW()),
    ('11000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002', 'Tenant B - Pop-up Shop', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Create products for Tenant B
INSERT INTO products (id, tenant_id, shop_id, name, price, created_at, updated_at)
VALUES
    ('21000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000001', 'Tenant B - Premium Item', 299.99, NOW(), NOW()),
    ('21000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000001', 'Tenant B - Standard Item', 199.99, NOW(), NOW()),
    ('21000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000002', 'Tenant B - Limited Edition', 399.99, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Reset tenant context
RESET app.current_tenant_id;

-- Verification queries
\echo ''
\echo '=== Tenant A Data ==='
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000001';
SELECT 'Tenant A Shops:' as info, COUNT(*) as count FROM shops;
SELECT 'Tenant A Products:' as info, COUNT(*) as count FROM products;
RESET app.current_tenant_id;

\echo ''
\echo '=== Tenant B Data ==='
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000002';
SELECT 'Tenant B Shops:' as info, COUNT(*) as count FROM shops;
SELECT 'Tenant B Products:' as info, COUNT(*) as count FROM products;
RESET app.current_tenant_id;

\echo ''
\echo '=== Total Data (no tenant context) ==='
SELECT 'Total Shops:' as info, COUNT(*) as count FROM shops;
SELECT 'Total Products:' as info, COUNT(*) as count FROM products;
