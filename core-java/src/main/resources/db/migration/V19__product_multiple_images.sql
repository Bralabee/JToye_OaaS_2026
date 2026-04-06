-- V19: Support multiple images per product
-- Food retail needs multiple angles: front, inside, serving suggestion, etc.

-- Add array column for additional images (primary image stays in image_url)
ALTER TABLE products ADD COLUMN IF NOT EXISTS additional_image_urls TEXT[] DEFAULT '{}';

-- Mirror in audit table for Envers
ALTER TABLE products_aud ADD COLUMN IF NOT EXISTS additional_image_urls TEXT[];
