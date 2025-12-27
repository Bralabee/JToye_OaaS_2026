-- Enable Row Level Security and policies based on app.current_tenant_id

ALTER TABLE shops ENABLE ROW LEVEL SECURITY;
ALTER TABLE products ENABLE ROW LEVEL SECURITY;
ALTER TABLE financial_transactions ENABLE ROW LEVEL SECURITY;

-- Force RLS so that even table owners and superusers are subject to policies (critical for tests and app role)
ALTER TABLE shops FORCE ROW LEVEL SECURITY;
ALTER TABLE products FORCE ROW LEVEL SECURITY;
ALTER TABLE financial_transactions FORCE ROW LEVEL SECURITY;

-- Only allow access to rows where tenant_id = current_tenant_id()
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE tablename = 'shops' AND policyname = 'shops_rls_policy'
    ) THEN
        CREATE POLICY shops_rls_policy ON shops
            USING (tenant_id = current_tenant_id())
            WITH CHECK (tenant_id = current_tenant_id());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE tablename = 'products' AND policyname = 'products_rls_policy'
    ) THEN
        CREATE POLICY products_rls_policy ON products
            USING (tenant_id = current_tenant_id())
            WITH CHECK (tenant_id = current_tenant_id());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE tablename = 'financial_transactions' AND policyname = 'financial_transactions_rls_policy'
    ) THEN
        CREATE POLICY financial_transactions_rls_policy ON financial_transactions
            USING (tenant_id = current_tenant_id())
            WITH CHECK (tenant_id = current_tenant_id());
    END IF;
END $$;

-- Optional: restrict tenants table to superuser roles only (no generic RLS policy)
-- You may manage tenants via privileged admin connection.
