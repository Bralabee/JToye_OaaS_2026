-- V29: Vendor marketing -- discount types and announcement entity

-- Step 1: Extend shop_promotions with discount type support
ALTER TABLE shop_promotions
    ADD COLUMN discount_type VARCHAR(20) NOT NULL DEFAULT 'PERCENTAGE',
    ADD COLUMN discount_amount_pennies INTEGER;

-- Step 2: Create shop_announcements table
CREATE TABLE shop_announcements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    shop_id UUID NOT NULL REFERENCES shops(id),
    title VARCHAR(200) NOT NULL,
    body TEXT,
    valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shop_announcements_shop ON shop_announcements(shop_id);
CREATE INDEX idx_shop_announcements_active ON shop_announcements(shop_id, active) WHERE active = true;

-- Step 3: RLS (using correct GUC name matching TenantSetLocalAspect)
ALTER TABLE shop_announcements ENABLE ROW LEVEL SECURITY;

CREATE POLICY shop_announcements_read ON shop_announcements
    FOR SELECT USING (true);

CREATE POLICY shop_announcements_write ON shop_announcements
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true));

-- Step 4: Migrate existing TEXT[] announcements to new table
INSERT INTO shop_announcements (tenant_id, shop_id, title, active, valid_from, valid_until)
SELECT s.tenant_id, s.id, unnest(s.announcements), true, NOW(), '9999-12-31T23:59:59Z'::timestamptz
FROM shops s
WHERE s.announcements IS NOT NULL AND array_length(s.announcements, 1) > 0;

-- Step 5: Drop announcements column from shops
ALTER TABLE shops DROP COLUMN announcements;

-- Step 6: Fix V28 shop_promotions RLS to use correct GUC name
DROP POLICY IF EXISTS shop_promotions_write ON shop_promotions;
CREATE POLICY shop_promotions_write ON shop_promotions
    FOR ALL USING (tenant_id::text = current_setting('app.current_tenant_id', true));
