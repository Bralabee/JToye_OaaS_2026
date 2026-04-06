-- V28: Shop configuration for server-driven content
-- Announcements, featured products, and promotions — configurable per shop
-- without code changes.

ALTER TABLE shops
    ADD COLUMN announcements TEXT[],
    ADD COLUMN featured_product_ids UUID[];

CREATE TABLE shop_promotions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    shop_id UUID NOT NULL REFERENCES shops(id),
    label VARCHAR(255) NOT NULL,
    discount_percent INTEGER CHECK (discount_percent BETWEEN 1 AND 100),
    category VARCHAR(100),
    valid_from TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_until TIMESTAMPTZ NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shop_promotions_shop ON shop_promotions(shop_id);
CREATE INDEX idx_shop_promotions_active ON shop_promotions(shop_id, active) WHERE active = true;

-- RLS
ALTER TABLE shop_promotions ENABLE ROW LEVEL SECURITY;

CREATE POLICY shop_promotions_read ON shop_promotions
    FOR SELECT USING (true);

CREATE POLICY shop_promotions_write ON shop_promotions
    FOR ALL USING (tenant_id = current_setting('app.tenant_id', true)::UUID);
