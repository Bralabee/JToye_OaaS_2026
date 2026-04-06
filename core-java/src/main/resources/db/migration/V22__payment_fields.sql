-- V22: Add payment tracking fields to orders for Stripe integration
-- payment_status tracks the Stripe payment lifecycle
-- payment_reference stores the Stripe PaymentIntent ID for reconciliation
-- payment_method stores a human-readable payment method identifier (e.g., "card ending 4242")

ALTER TABLE orders
    ADD COLUMN payment_status VARCHAR(20) DEFAULT 'NONE',
    ADD COLUMN payment_reference VARCHAR(255),
    ADD COLUMN payment_method VARCHAR(100);

-- Update audit table to match
ALTER TABLE orders_aud
    ADD COLUMN payment_status VARCHAR(20),
    ADD COLUMN payment_reference VARCHAR(255),
    ADD COLUMN payment_method VARCHAR(100);

-- Index for looking up orders by Stripe PaymentIntent ID (webhook handling)
CREATE INDEX idx_orders_payment_reference ON orders(payment_reference) WHERE payment_reference IS NOT NULL;
