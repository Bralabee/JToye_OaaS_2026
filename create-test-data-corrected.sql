-- Create Test Tenants and Sample Data (Corrected Schema)
-- This script creates two tenants (A and B) with shops and products to test RLS isolation

BEGIN;

-- Cre tenant A
INSERT INTO tenants (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Tenant A Corp')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

-- Create Tenant B
INSERT INTO tenants (id, name)
VALUES ('00000000-0000-0000-0000-000000000002', 'Tenant B Ltd')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

-- Create shops and products for Tenant A
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000001';

INSERT INTO shops (id, tenant_id, name, address)
VALUES
    ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'Tenant A - Main Store', '123 Main St, City A'),
    ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'Tenant A - Outlet Store', '456 Outlet Rd, City A')
ON CONFLICT (id) DO NOTHING;

INSERT INTO products (id, tenant_id, sku, title, ingredients_text, allergen_mask)
VALUES
    ('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'TENANT-A-SKU-001', 'Tenant A - Premium Bread', 'Wheat flour, water, yeast, salt', 1),
    ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'TENANT-A-SKU-002', 'Tenant A - Croissant', 'Wheat flour, butter, sugar, eggs', 3),
    ('20000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'TENANT-A-SKU-003', 'Tenant A - Chocolate Cake', 'Flour, cocoa, eggs, milk, sugar', 7)
ON CONFLICT (id) DO NOTHING;

RESET app.current_tenant_id;

-- Create shops and products for Tenant B
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000002';

INSERT INTO shops (id, tenant_id, name, address)
VALUES
    ('11000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 'Tenant B - Flagship Store', '789 High St, City B'),
    ('11000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002', 'Tenant B - Pop-up Shop', '321 Market Ave, City B')
ON CONFLICT (id) DO NOTHING;

INSERT INTO products (id, tenant_id, sku, title, ingredients_text, allergen_mask)
VALUES
    ('21000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 'TENANT-B-SKU-001', 'Tenant B - Artisan Sourdough', 'Organic flour, water, salt, sourdough culture', 1),
    ('21000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002', 'TENANT-B-SKU-002', 'Tenant B - Gluten-Free Muffin', 'Rice flour, eggs, milk, sugar', 6),
    ('21000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002', 'TENANT-B-SKU-003', 'Tenant B - Vegan Cookie', 'Oat flour, coconut oil, maple syrup', 0)
ON CONFLICT (id) DO NOTHING;

RESET app.current_tenant_id;

COMMIT;

-- Verification queries
\echo ''
\echo '=== Verification Results ==='
\echo ''

BEGIN;

\echo 'Tenant A Data (with RLS context):'
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000001';
SELECT COUNT(*) as shops_count FROM shops;
SELECT COUNT(*) as products_count FROM products;
RESET app.current_tenant_id;

\echo ''
\echo 'Tenant B Data (with RLS context):'
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000002';
SELECT COUNT(*) as shops_count FROM shops;
SELECT COUNT(*) as products_count FROM products;
RESET app.current_tenant_id;

COMMIT;

-- Verify RLS isolation works
\echo ''
\echo '=== RLS Isolation Test ==='
\echo 'Querying as Tenant A (should see only Tenant A data):'

BEGIN;
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000001';
SELECT name FROM shops ORDER BY name;
SELECT sku, title FROM products ORDER BY sku;
COMMIT;

\echo ''
\echo 'Querying as Tenant B (should see only Tenant B data):'

BEGIN;
SET LOCAL app.current_tenant_id = '00000000-0000-0000-0000-000000000002';
SELECT name FROM shops ORDER BY name;
SELECT sku, title FROM products ORDER BY sku;
COMMIT;
