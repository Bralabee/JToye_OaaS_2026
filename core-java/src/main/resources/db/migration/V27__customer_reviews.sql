-- V27: Customer reviews with photos
-- Reviews are linked to orders (verified purchases only).
-- Separate food_rating and delivery_rating (Glovo-style).

CREATE TABLE reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    shop_id UUID NOT NULL REFERENCES shops(id),
    order_id UUID NOT NULL REFERENCES orders(id),
    customer_email VARCHAR(255) NOT NULL,
    customer_name VARCHAR(255),
    food_rating INTEGER NOT NULL CHECK (food_rating BETWEEN 1 AND 5),
    delivery_rating INTEGER CHECK (delivery_rating BETWEEN 1 AND 5),
    comment TEXT,
    photo_urls TEXT[],
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(order_id)
);

-- One review per order
CREATE INDEX idx_reviews_shop ON reviews(shop_id);
CREATE INDEX idx_reviews_tenant ON reviews(tenant_id);

-- RLS policy: reviews are publicly readable for published shops
ALTER TABLE reviews ENABLE ROW LEVEL SECURITY;

CREATE POLICY reviews_tenant_read ON reviews
    FOR SELECT
    USING (true);

CREATE POLICY reviews_tenant_write ON reviews
    FOR INSERT
    WITH CHECK (
        tenant_id = current_setting('app.tenant_id', true)::UUID
        OR current_setting('app.customer_email', true) = customer_email
    );

-- Aggregate rating view for shop listings
CREATE OR REPLACE VIEW shop_ratings AS
SELECT
    shop_id,
    COUNT(*) AS review_count,
    ROUND(AVG(food_rating)::numeric, 1) AS avg_food_rating,
    ROUND(AVG(delivery_rating)::numeric, 1) AS avg_delivery_rating
FROM reviews
GROUP BY shop_id;
