-- Envers (Hibernate) audit tables for tracking entity changes
-- These tables store historical versions of audited entities

-- Revision info table (stores transaction metadata)
CREATE TABLE IF NOT EXISTS revinfo (
    rev INTEGER NOT NULL,
    revtstmp BIGINT,
    PRIMARY KEY (rev)
);

-- Revision sequence
CREATE SEQUENCE IF NOT EXISTS revinfo_seq START WITH 1 INCREMENT BY 1;

-- Audit table for shops
CREATE TABLE IF NOT EXISTS shops_aud (
    id UUID NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    tenant_id UUID,
    created_at TIMESTAMPTZ,
    name TEXT,
    address TEXT,
    PRIMARY KEY (id, rev),
    CONSTRAINT fk_shops_aud_revinfo FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

-- Audit table for products
CREATE TABLE IF NOT EXISTS products_aud (
    id UUID NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    tenant_id UUID,
    created_at TIMESTAMPTZ,
    sku TEXT,
    title TEXT,
    ingredients_text TEXT,
    allergen_mask INTEGER,
    PRIMARY KEY (id, rev),
    CONSTRAINT fk_products_aud_revinfo FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

-- Audit table for financial_transactions
CREATE TABLE IF NOT EXISTS financial_transactions_aud (
    id UUID NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    tenant_id UUID,
    created_at TIMESTAMPTZ,
    amount_pennies BIGINT,
    vat_rate TEXT, -- stored as string in audit table
    reference TEXT,
    PRIMARY KEY (id, rev),
    CONSTRAINT fk_financial_transactions_aud_revinfo FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

-- Indexes for performance on audit queries
CREATE INDEX IF NOT EXISTS idx_shops_aud_rev ON shops_aud(rev);
CREATE INDEX IF NOT EXISTS idx_shops_aud_tenant ON shops_aud(tenant_id);
CREATE INDEX IF NOT EXISTS idx_products_aud_rev ON products_aud(rev);
CREATE INDEX IF NOT EXISTS idx_products_aud_tenant ON products_aud(tenant_id);
CREATE INDEX IF NOT EXISTS idx_fin_tx_aud_rev ON financial_transactions_aud(rev);
CREATE INDEX IF NOT EXISTS idx_fin_tx_aud_tenant ON financial_transactions_aud(tenant_id);

-- Apply RLS to audit tables (auditors should see all history for their tenant)
ALTER TABLE shops_aud ENABLE ROW LEVEL SECURITY;
ALTER TABLE products_aud ENABLE ROW LEVEL SECURITY;
ALTER TABLE financial_transactions_aud ENABLE ROW LEVEL SECURITY;

-- RLS policies for audit tables
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE tablename = 'shops_aud' AND policyname = 'shops_aud_rls_policy'
    ) THEN
        CREATE POLICY shops_aud_rls_policy ON shops_aud
            USING (tenant_id = current_tenant_id());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE tablename = 'products_aud' AND policyname = 'products_aud_rls_policy'
    ) THEN
        CREATE POLICY products_aud_rls_policy ON products_aud
            USING (tenant_id = current_tenant_id());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE tablename = 'financial_transactions_aud' AND policyname = 'financial_transactions_aud_rls_policy'
    ) THEN
        CREATE POLICY financial_transactions_aud_rls_policy ON financial_transactions_aud
            USING (tenant_id = current_tenant_id());
    END IF;
END $$;

-- Note: revinfo table is NOT tenant-scoped as it stores global revision metadata
-- Access to revision metadata should be controlled at the application layer
