-- Fix order_status column type to work with Hibernate @Enumerated(EnumType.STRING)
-- Change from custom enum type to varchar

-- First, remove the default that depends on the enum
ALTER TABLE orders ALTER COLUMN status DROP DEFAULT;

-- Change column type to varchar
ALTER TABLE orders ALTER COLUMN status TYPE VARCHAR(20);

-- Re-add the default as a string
ALTER TABLE orders ALTER COLUMN status SET DEFAULT 'DRAFT';

-- Drop the order_status enum type (now unused)
DROP TYPE IF EXISTS order_status CASCADE;

-- Add check constraint to ensure only valid values
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('DRAFT', 'PENDING', 'CONFIRMED', 'PREPARING', 'READY', 'COMPLETED', 'CANCELLED'));
