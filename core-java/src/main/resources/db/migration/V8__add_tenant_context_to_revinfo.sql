-- Add tenant and user context columns to revinfo table
-- This enables tenant-aware audit trails and user attribution for all revisions

-- Add tenant_id column to track which tenant made the change
ALTER TABLE revinfo ADD COLUMN IF NOT EXISTS tenant_id UUID;

-- Add user_id column to track which user made the change (from JWT subject)
ALTER TABLE revinfo ADD COLUMN IF NOT EXISTS user_id VARCHAR(255);

-- Create indexes for efficient audit queries by tenant and user
CREATE INDEX IF NOT EXISTS idx_revinfo_tenant ON revinfo(tenant_id);
CREATE INDEX IF NOT EXISTS idx_revinfo_user ON revinfo(user_id);

-- Comments for documentation
COMMENT ON COLUMN revinfo.tenant_id IS 'Tenant ID captured from TenantContext at time of revision';
COMMENT ON COLUMN revinfo.user_id IS 'User ID from JWT subject claim, identifies who made the change';
COMMENT ON TABLE revinfo IS 'Envers revision metadata with tenant and user context for audit compliance';
