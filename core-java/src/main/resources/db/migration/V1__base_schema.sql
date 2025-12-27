-- Base schema for J'Toye OaaS (Multi-tenant shared schema)
-- Requirements:
--  - All food entities must have ingredients_text and allergen_mask
--  - All financials must have vat_rate_enum

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- VAT rate enum (align with HMRC categories as needed)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'vat_rate_enum') THEN
        CREATE TYPE vat_rate_enum AS ENUM ('ZERO', 'REDUCED', 'STANDARD', 'EXEMPT');
    END IF;
END$$;

-- Helper function to read the current tenant id from the session setting
CREATE OR REPLACE FUNCTION current_tenant_id() RETURNS uuid
LANGUAGE plpgsql AS $$
DECLARE
    v uuid;
BEGIN
    BEGIN
        v := current_setting('app.current_tenant_id', true)::uuid;
    EXCEPTION WHEN others THEN
        v := NULL;
    END;
    RETURN v;
END;
$$;

-- Core tables (shared schema)
CREATE TABLE IF NOT EXISTS tenants (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    created_at timestamptz NOT NULL DEFAULT now(),
    name text NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS shops (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    name text NOT NULL,
    address text,
    CONSTRAINT fk_shops_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id)
);

-- Master products registry (shared across tenants but still RLS-protected by design in this PoC)
CREATE TABLE IF NOT EXISTS products (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    sku text NOT NULL,
    title text NOT NULL,
    ingredients_text text NOT NULL,
    allergen_mask integer NOT NULL DEFAULT 0
);

-- Financial transactions
CREATE TABLE IF NOT EXISTS financial_transactions (
    id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    amount_pennies bigint NOT NULL,
    vat_rate vat_rate_enum NOT NULL,
    reference text
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_shops_tenant ON shops(tenant_id);
CREATE INDEX IF NOT EXISTS idx_products_tenant ON products(tenant_id);
CREATE INDEX IF NOT EXISTS idx_fin_tx_tenant ON financial_transactions(tenant_id);
