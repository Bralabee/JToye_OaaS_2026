-- Orders and Order Items tables with RLS policies
-- Phase 1: Domain Enrichment - Order Management

-- Order status enum
CREATE TYPE order_status AS ENUM (
    'DRAFT',
    'PENDING',
    'CONFIRMED',
    'PREPARING',
    'READY',
    'COMPLETED',
    'CANCELLED'
);

-- Orders table
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    order_number VARCHAR(50) NOT NULL,
    status order_status NOT NULL DEFAULT 'DRAFT',
    customer_name VARCHAR(255),
    customer_email VARCHAR(255),
    customer_phone VARCHAR(50),
    notes TEXT,
    total_amount_pennies BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_orders_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_orders_shop FOREIGN KEY (shop_id) REFERENCES shops(id),
    CONSTRAINT uq_orders_tenant_number UNIQUE (tenant_id, order_number)
);

-- Order items table (line items in an order)
CREATE TABLE order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    order_id UUID NOT NULL,
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price_pennies BIGINT NOT NULL,
    total_price_pennies BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_order_items_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(id)
);

-- Indexes for performance
CREATE INDEX idx_orders_tenant ON orders(tenant_id);
CREATE INDEX idx_orders_shop ON orders(shop_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created ON orders(created_at DESC);
CREATE INDEX idx_orders_number ON orders(order_number);

CREATE INDEX idx_order_items_tenant ON order_items(tenant_id);
CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_order_items_product ON order_items(product_id);

-- Enable RLS on orders and order_items
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_items ENABLE ROW LEVEL SECURITY;

-- RLS policies for orders table
CREATE POLICY orders_select_policy ON orders
    FOR SELECT
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE POLICY orders_insert_policy ON orders
    FOR INSERT
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE POLICY orders_update_policy ON orders
    FOR UPDATE
    USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE POLICY orders_delete_policy ON orders
    FOR DELETE
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

-- RLS policies for order_items table
CREATE POLICY order_items_select_policy ON order_items
    FOR SELECT
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE POLICY order_items_insert_policy ON order_items
    FOR INSERT
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE POLICY order_items_update_policy ON order_items
    FOR UPDATE
    USING (tenant_id::text = current_setting('app.current_tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true));

CREATE POLICY order_items_delete_policy ON order_items
    FOR DELETE
    USING (tenant_id::text = current_setting('app.current_tenant_id', true));

-- Audit tables for orders (Envers)
CREATE TABLE orders_aud (
    id UUID NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    tenant_id UUID,
    shop_id UUID,
    order_number VARCHAR(50),
    status VARCHAR(20), -- stored as string in audit
    customer_name VARCHAR(255),
    customer_email VARCHAR(255),
    customer_phone VARCHAR(50),
    notes TEXT,
    total_amount_pennies BIGINT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev),
    CONSTRAINT fk_orders_aud_revinfo FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

-- Audit table for order_items
CREATE TABLE order_items_aud (
    id UUID NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    tenant_id UUID,
    order_id UUID,
    product_id UUID,
    quantity INTEGER,
    unit_price_pennies BIGINT,
    total_price_pennies BIGINT,
    created_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev),
    CONSTRAINT fk_order_items_aud_revinfo FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

-- Indexes on audit tables
CREATE INDEX idx_orders_aud_rev ON orders_aud(rev);
CREATE INDEX idx_orders_aud_tenant ON orders_aud(tenant_id);
CREATE INDEX idx_order_items_aud_rev ON order_items_aud(rev);
CREATE INDEX idx_order_items_aud_tenant ON order_items_aud(tenant_id);

-- RLS on audit tables
-- Strategy: Allow unrestricted INSERT (Envers writes audit records)
--           but restrict SELECT to current tenant (read isolation)
ALTER TABLE orders_aud ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_items_aud ENABLE ROW LEVEL SECURITY;

-- Orders audit policies
CREATE POLICY orders_aud_select_policy ON orders_aud
    FOR SELECT
    USING (tenant_id = current_tenant_id());

CREATE POLICY orders_aud_insert_policy ON orders_aud
    FOR INSERT
    WITH CHECK (true);  -- Allow Envers to write all audit records

-- Order items audit policies
CREATE POLICY order_items_aud_select_policy ON order_items_aud
    FOR SELECT
    USING (tenant_id = current_tenant_id());

CREATE POLICY order_items_aud_insert_policy ON order_items_aud
    FOR INSERT
    WITH CHECK (true);  -- Allow Envers to write all audit records

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to automatically update updated_at
CREATE TRIGGER update_orders_updated_at
    BEFORE UPDATE ON orders
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Comments for documentation
COMMENT ON TABLE orders IS 'Customer orders with multi-tenant isolation via RLS';
COMMENT ON TABLE order_items IS 'Line items within orders (products, quantities, prices)';
COMMENT ON COLUMN orders.order_number IS 'Human-readable order number, unique per tenant';
COMMENT ON COLUMN orders.total_amount_pennies IS 'Total order amount in pennies (cents) to avoid floating point issues';
COMMENT ON COLUMN order_items.unit_price_pennies IS 'Price per unit in pennies at time of order';
COMMENT ON COLUMN order_items.total_price_pennies IS 'Total line item price (quantity * unit_price)';
