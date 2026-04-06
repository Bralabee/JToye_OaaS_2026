-- V25: Add PostgreSQL full-text search for products and shops
-- Uses GIN indexes on tsvector columns for fast, ranked search with typo tolerance.

-- Products: searchable by title, description, category, ingredients, dietary tags
ALTER TABLE products ADD COLUMN search_vector tsvector;

UPDATE products SET search_vector =
    setweight(to_tsvector('english', COALESCE(title, '')), 'A') ||
    setweight(to_tsvector('english', COALESCE(category, '')), 'B') ||
    setweight(to_tsvector('english', COALESCE(description, '')), 'C') ||
    setweight(to_tsvector('english', COALESCE(ingredients_text, '')), 'C') ||
    setweight(to_tsvector('english', COALESCE(dietary_tags, '')), 'D');

CREATE INDEX idx_products_search ON products USING GIN (search_vector);

-- Auto-update search_vector on INSERT/UPDATE
CREATE OR REPLACE FUNCTION products_search_vector_update() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', COALESCE(NEW.title, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.category, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'C') ||
        setweight(to_tsvector('english', COALESCE(NEW.ingredients_text, '')), 'C') ||
        setweight(to_tsvector('english', COALESCE(NEW.dietary_tags, '')), 'D');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_products_search_vector
    BEFORE INSERT OR UPDATE OF title, description, category, ingredients_text, dietary_tags
    ON products
    FOR EACH ROW EXECUTE FUNCTION products_search_vector_update();

-- Shops: searchable by name, description, tags, address
ALTER TABLE shops ADD COLUMN search_vector tsvector;

UPDATE shops SET search_vector =
    setweight(to_tsvector('english', COALESCE(name, '')), 'A') ||
    setweight(to_tsvector('english', COALESCE(tags, '')), 'B') ||
    setweight(to_tsvector('english', COALESCE(description, '')), 'C') ||
    setweight(to_tsvector('english', COALESCE(address, '')), 'D');

CREATE INDEX idx_shops_search ON shops USING GIN (search_vector);

CREATE OR REPLACE FUNCTION shops_search_vector_update() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', COALESCE(NEW.name, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.tags, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'C') ||
        setweight(to_tsvector('english', COALESCE(NEW.address, '')), 'D');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_shops_search_vector
    BEFORE INSERT OR UPDATE OF name, description, tags, address
    ON shops
    FOR EACH ROW EXECUTE FUNCTION shops_search_vector_update();
