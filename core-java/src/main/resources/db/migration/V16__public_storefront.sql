-- Public storefront: enrich shops and products for customer-facing presentation
-- Adds shop branding/geo/hours, product descriptions/images/categories, and public RLS

-- ============================================================
-- 1. SHOPS — storefront presentation fields
-- ============================================================

-- Slug (URL-safe identifier) — add as nullable, backfill bypassing RLS, then constrain
ALTER TABLE shops ADD COLUMN IF NOT EXISTS slug VARCHAR(100);
ALTER TABLE shops ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE shops ADD COLUMN IF NOT EXISTS logo_url TEXT;
ALTER TABLE shops ADD COLUMN IF NOT EXISTS banner_url TEXT;
ALTER TABLE shops ADD COLUMN IF NOT EXISTS phone VARCHAR(50);
ALTER TABLE shops ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE shops ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION;
ALTER TABLE shops ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;
ALTER TABLE shops ADD COLUMN IF NOT EXISTS opening_hours JSONB DEFAULT '{}';
ALTER TABLE shops ADD COLUMN IF NOT EXISTS delivery_info TEXT;
ALTER TABLE shops ADD COLUMN IF NOT EXISTS minimum_order_pennies BIGINT NOT NULL DEFAULT 0;
ALTER TABLE shops ADD COLUMN IF NOT EXISTS published BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE shops ADD COLUMN IF NOT EXISTS tags VARCHAR(500);

-- Temporarily disable RLS to backfill slugs (migration runs without tenant context)
ALTER TABLE shops DISABLE ROW LEVEL SECURITY;

-- Backfill slugs for existing rows with proper URL-safe names
UPDATE shops
SET slug = COALESCE(
    NULLIF(LOWER(REGEXP_REPLACE(REPLACE(COALESCE(name, 'shop'), ' ', '-'), '[^a-z0-9\-]', '', 'g')), ''),
    'shop'
  ) || '-' || LEFT(id::text, 8)
WHERE slug IS NULL;

-- Re-enable RLS and FORCE it
ALTER TABLE shops ENABLE ROW LEVEL SECURITY;
ALTER TABLE shops FORCE ROW LEVEL SECURITY;

-- Now enforce NOT NULL and uniqueness
ALTER TABLE shops ALTER COLUMN slug SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_shops_slug ON shops(slug);

-- Partial index for public storefront queries
CREATE INDEX IF NOT EXISTS idx_shops_published ON shops(published) WHERE published = true;

-- ============================================================
-- 2. PRODUCTS — rich presentation fields
-- ============================================================

ALTER TABLE products ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS image_url TEXT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS category VARCHAR(100);
ALTER TABLE products ADD COLUMN IF NOT EXISTS display_order INTEGER NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS available BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE products ADD COLUMN IF NOT EXISTS featured BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE products ADD COLUMN IF NOT EXISTS preparation_time_minutes INTEGER;
ALTER TABLE products ADD COLUMN IF NOT EXISTS dietary_tags VARCHAR(255);

-- Indexes for storefront product queries
CREATE INDEX IF NOT EXISTS idx_products_category ON products(category);
CREATE INDEX IF NOT EXISTS idx_products_display_order ON products(display_order);
CREATE INDEX IF NOT EXISTS idx_products_available ON products(available) WHERE available = true;

-- ============================================================
-- 3. PUBLIC RLS POLICY — allow anonymous reads of published shops
-- ============================================================
-- PostgreSQL OR's permissive policies: this new SELECT policy works alongside
-- the existing shops_rls_policy (FOR ALL). Any SELECT matching EITHER policy passes.
-- The existing policy handles INSERT/UPDATE/DELETE (still requires tenant context).

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE tablename = 'shops' AND policyname = 'shops_public_read'
    ) THEN
        CREATE POLICY shops_public_read ON shops
            FOR SELECT
            USING (published = true OR tenant_id = current_tenant_id());
    END IF;
END $$;

-- ============================================================
-- 4. AUDIT TABLES — add matching columns for Hibernate Envers
-- ============================================================

-- shops_aud
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS slug VARCHAR(100);
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS logo_url TEXT;
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS banner_url TEXT;
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS phone VARCHAR(50);
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION;
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS opening_hours JSONB;
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS delivery_info TEXT;
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS minimum_order_pennies BIGINT;
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS published BOOLEAN;
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS tags VARCHAR(500);

-- products_aud
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS image_url TEXT;
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS category VARCHAR(100);
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS display_order INTEGER;
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS available BOOLEAN;
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS featured BOOLEAN;
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS preparation_time_minutes INTEGER;
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS dietary_tags VARCHAR(255);
