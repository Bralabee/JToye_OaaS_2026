-- Customers table with allergen tracking
-- Phase 1: Domain Enrichment - Customer Management

CREATE TABLE customers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(50),
    allergen_restrictions INTEGER DEFAULT 0,
    notes TEXT,
    CONSTRAINT fk_customers_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT uq_customers_tenant_email UNIQUE (tenant_id, email)
);

-- Indexes for performance
CREATE INDEX idx_customers_tenant ON customers(tenant_id);
CREATE INDEX idx_customers_email ON customers(email);
CREATE INDEX idx_customers_phone ON customers(phone);

-- Enable RLS with FORCE for security
ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers FORCE ROW LEVEL SECURITY;

-- RLS policies for customers
CREATE POLICY customers_select_policy ON customers
    FOR SELECT
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE POLICY customers_insert_policy ON customers
    FOR INSERT
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE POLICY customers_update_policy ON customers
    FOR UPDATE
    USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE POLICY customers_delete_policy ON customers
    FOR DELETE
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

-- Audit table for customers
CREATE TABLE customers_aud (
    id UUID NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    tenant_id UUID,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    name VARCHAR(255),
    email VARCHAR(255),
    phone VARCHAR(50),
    allergen_restrictions INTEGER,
    notes TEXT,
    PRIMARY KEY (id, rev),
    CONSTRAINT fk_customers_aud_revinfo FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

-- Indexes on audit table
CREATE INDEX idx_customers_aud_rev ON customers_aud(rev);
CREATE INDEX idx_customers_aud_tenant ON customers_aud(tenant_id);

-- RLS on audit table (same pattern: unrestricted INSERT, tenant-scoped SELECT)
ALTER TABLE customers_aud ENABLE ROW LEVEL SECURITY;

CREATE POLICY customers_aud_select_policy ON customers_aud
    FOR SELECT
    USING (tenant_id = current_tenant_id());

CREATE POLICY customers_aud_insert_policy ON customers_aud
    FOR INSERT
    WITH CHECK (true);  -- Allow Envers to write all audit records

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_customer_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to automatically update updated_at
CREATE TRIGGER update_customers_updated_at
    BEFORE UPDATE ON customers
    FOR EACH ROW
    EXECUTE FUNCTION update_customer_updated_at();

-- Optional: Add customer_id to orders table for relationship (nullable for backward compatibility)
ALTER TABLE orders ADD COLUMN IF NOT EXISTS customer_id UUID;
ALTER TABLE orders ADD CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id);
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders(customer_id);

-- Comments for documentation
COMMENT ON TABLE customers IS 'Customer records with allergen tracking for safety compliance';
COMMENT ON COLUMN customers.allergen_restrictions IS 'Bitmask of allergen restrictions (matches Product.allergen_mask)';
COMMENT ON COLUMN customers.notes IS 'Additional customer notes, preferences, or special requirements';
COMMENT ON COLUMN orders.customer_id IS 'Optional FK to customers table (nullable for backward compatibility)';
