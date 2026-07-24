-- V53: Phase 24 — Copy-on-write media_asset model + product_media join (IMG-01).
--
-- Establishes the durable structural fix behind safe vendor image sharing/dedup:
--   1. media_asset     — the ref-counted, tenant-scoped, sha256-deduped asset. A
--                        product references an asset and never owns bytes. @Audited
--                        (media_asset_aud mirror).
--   2. product_media   — the (product <-> asset) join carrying both the primary
--                        image (is_primary) and the ordered gallery (sort_order),
--                        per D-01. Copy-on-write repoint = UPDATE product_media SET
--                        asset_id=<new> on the one affected row. Un-audited
--                        (RESEARCH Open-Q2) — high-churn derived link, no _aud.
--   3. media_asset_aud — Envers mirror (all columns nullable, PK (id, rev),
--                        FK rev->revinfo; RLS predicate admits NULL tenant_id).
--
-- All tenant tables carry ENABLE + FORCE RLS with policies gated through the SAFE
-- helper current_tenant_id() (V51) — NEVER the raw GUC `...::uuid` cast. Copying
-- the V43/V47/V50 raw-cast form would (a) fail
-- RlsContractTest#noPolicyUsesRawTenantGucCast and (b) reintroduce the 22P02
-- empty-GUC crash class (Issue #113). Uses the V43 idempotent DO-block DDL style.
--
-- ============================================================================
-- OUT-OF-ORDER APPLICATION
-- ============================================================================
-- HEAD is V56 (Phase 22 notifications shipped V54/V55/V56); V53 was RESERVED for
-- this media_asset slice, so spring.flyway.out-of-order=true is already set in all
-- profiles (see V44 header) — no Flyway config change here. Fresh databases apply
-- V1..V52,V53,V54..V56 in order; deployed DBs already past V56 apply V53 out of
-- order.
--
-- ============================================================================
-- BACKFILL RLS SAFETY (trap_rls_migration_backfill — recurring V25->V44->V57)
-- ============================================================================
-- products carries ENABLE + FORCE ROW LEVEL SECURITY, so a bare INSERT..SELECT
-- backfill would run as the RLS-bound migration role with NO tenant GUC set, see
-- ZERO rows, and silently ship an empty asset model on every non-fresh DB (while
-- fresh Testcontainers DBs stay green — MediaBackfillMigrationIntegrationTest
-- reproduces + guards). The loop below walks the tenants registry (no RLS on
-- tenants) and sets the standard tenant GUC transaction-locally per tenant,
-- exactly like the application does and V44 did — every product is reached WITH
-- the policies enforced.
--
-- D-03: existing objects are wrapped as status='ACTIVE' media_asset rows AS-IS
-- pointing at the current object_key, WITHOUT re-running the pipeline (already
-- trusted/working). D-01a: products.image_url -> the is_primary=true row;
-- products.additional_image_urls[] -> sort_order rows preserving array order.
-- SPEC D1: seed images (/products/seed/) stay on the flat path — NOT wrapped.

-- ============================================================
-- 1. media_asset — ref-counted, tenant-scoped, sha256-deduped asset
-- ============================================================
CREATE TABLE IF NOT EXISTS media_asset (
    id             UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    object_key     TEXT        NOT NULL,          -- <tenant>/media/<id>.webp (ACTIVE) or quarantine key (PENDING)
    sha256         CHAR(64)    NOT NULL,          -- of the RAW upload — tenant-unique dedup
    content_type   VARCHAR(32) NOT NULL,
    width          INT,
    height         INT,
    bytes          BIGINT,
    status         VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','ACTIVE','FAILED')),
    flagged        BOOLEAN     NOT NULL DEFAULT false,   -- IMG-03 content-relevance (ACTIVE + flagged)
    failure_reason TEXT,                                  -- IMG-03 vendor-visible reason
    uploaded_by    UUID,
    -- Pending-placement intent (D-04a): captured at accept (24-03), consumed by the
    -- async worker (24-04) to create/repoint the product_media row ONLY on ACTIVE.
    -- NULL on backfilled ACTIVE rows (their product_media rows are created directly).
    product_id     UUID,
    is_primary     BOOLEAN,
    sort_order     INT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Per-tenant sha256 dedup: an identical raw upload reuses the existing asset.
CREATE UNIQUE INDEX IF NOT EXISTS uq_media_asset_tenant_sha ON media_asset (tenant_id, sha256);

ALTER TABLE media_asset ENABLE ROW LEVEL SECURITY;
ALTER TABLE media_asset FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='media_asset' AND policyname='media_asset_tenant_policy') THEN
    CREATE POLICY media_asset_tenant_policy ON media_asset
        FOR ALL
        USING      (tenant_id = current_tenant_id())     -- V51 safe helper, NOT ::uuid cast
        WITH CHECK (tenant_id = current_tenant_id());
  END IF;
END $$;

-- ============================================================
-- 2. product_media — the (product <-> asset) join (D-01). tenant_id carried
--     deliberately so the join row is itself RLS-scoped (do NOT lean on the FK
--     to products for isolation). Un-audited (Open-Q2).
-- ============================================================
CREATE TABLE IF NOT EXISTS product_media (
    id         UUID PRIMARY KEY,
    tenant_id  UUID    NOT NULL,
    product_id UUID    NOT NULL REFERENCES products(id),
    asset_id   UUID    NOT NULL REFERENCES media_asset(id),
    is_primary BOOLEAN NOT NULL DEFAULT false,
    sort_order INT     NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_product_media_asset ON product_media (asset_id);   -- ref-count query
-- At most one primary image per product; also serves the asset-first dual-read
-- resolver's (product_id AND is_primary) lookup.
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_media_primary
    ON product_media (product_id) WHERE is_primary;

ALTER TABLE product_media ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_media FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='product_media' AND policyname='product_media_tenant_policy') THEN
    CREATE POLICY product_media_tenant_policy ON product_media
        FOR ALL
        USING      (tenant_id = current_tenant_id())     -- V51 safe helper, NOT ::uuid cast
        WITH CHECK (tenant_id = current_tenant_id());
  END IF;
END $$;

-- ============================================================
-- 3. media_asset_aud — Envers mirror (all cols nullable, PK (id, rev),
--     FK rev->revinfo). The _aud RLS predicate admits NULL tenant_id yet still
--     tenant-filters on read (V43/V51/V52 shop_staff_aud pattern).
-- ============================================================
CREATE TABLE IF NOT EXISTS media_asset_aud (
    id             UUID     NOT NULL,
    rev            INT      NOT NULL REFERENCES revinfo(rev),
    revtype        SMALLINT,
    tenant_id      UUID,
    object_key     TEXT,
    sha256         CHAR(64),
    content_type   VARCHAR(32),
    width          INT,
    height         INT,
    bytes          BIGINT,
    status         VARCHAR(16),
    flagged        BOOLEAN,
    failure_reason TEXT,
    uploaded_by    UUID,
    product_id     UUID,
    is_primary     BOOLEAN,
    sort_order     INT,
    created_at     TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

ALTER TABLE media_asset_aud ENABLE ROW LEVEL SECURITY;
ALTER TABLE media_asset_aud FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='media_asset_aud' AND policyname='media_asset_aud_tenant_policy') THEN
    CREATE POLICY media_asset_aud_tenant_policy ON media_asset_aud
        FOR ALL
        USING      (tenant_id IS NULL OR tenant_id = current_tenant_id())
        WITH CHECK (tenant_id IS NULL OR tenant_id = current_tenant_id());
  END IF;
END $$;

-- ============================================================
-- 4. Per-tenant backfill (D-01a / D-03 / D-03b) — MIRRORS the V44 loop EXACTLY.
--     A bare INSERT..SELECT here would migrate ZERO rows under FORCE RLS.
-- ============================================================
DO $$
DECLARE
    t                RECORD;
    p                RECORD;
    g                RECORD;
    v_key            TEXT;
    v_pos            INT;
    v_sha            CHAR(64);
    v_ct             VARCHAR(32);
    v_asset_id       UUID;
    assets_created   BIGINT := 0;
    links_created    BIGINT := 0;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        -- Same GUC the app sets (TenantSetLocalAspect); transaction-local, so it
        -- vanishes when Flyway commits this migration.
        PERFORM set_config('app.current_tenant_id', t.id::text, true);

        -- ---- Primary images: products.image_url -> product_media(is_primary=true) ----
        FOR p IN
            SELECT id AS product_id, image_url
            FROM products
            WHERE tenant_id = t.id
              AND image_url IS NOT NULL
              AND image_url <> ''
              AND position('/products/seed/' IN image_url) = 0   -- SPEC D1: seed images stay flat
        LOOP
            v_pos := position(t.id::text || '/' IN p.image_url);
            IF v_pos > 0 THEN
                v_key := substring(p.image_url FROM v_pos);       -- <tenant>/products/<pid>/<uuid>.<ext>
            ELSE
                v_key := p.image_url;                             -- defensive: store URL as-is (external)
            END IF;
            v_sha := encode(sha256(convert_to(v_key, 'UTF8')), 'hex');
            v_ct  := CASE
                        WHEN v_key ILIKE '%.png'  THEN 'image/png'
                        WHEN v_key ILIKE '%.webp' THEN 'image/webp'
                        WHEN v_key ILIKE '%.gif'  THEN 'image/gif'
                        ELSE 'image/jpeg'
                     END;

            INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type, status, flagged, created_at)
            VALUES (gen_random_uuid(), t.id, v_key, v_sha, v_ct, 'ACTIVE', false, now())
            ON CONFLICT (tenant_id, sha256) DO NOTHING;          -- dedup: reuse existing asset
            GET DIAGNOSTICS v_pos = ROW_COUNT;                   -- reuse v_pos as a scratch counter
            assets_created := assets_created + v_pos;

            SELECT id INTO v_asset_id FROM media_asset WHERE tenant_id = t.id AND sha256 = v_sha;

            INSERT INTO product_media (id, tenant_id, product_id, asset_id, is_primary, sort_order)
            VALUES (gen_random_uuid(), t.id, p.product_id, v_asset_id, true, 0);
            links_created := links_created + 1;
        END LOOP;

        -- ---- Gallery images: additional_image_urls[] -> product_media(sort_order=n) ----
        FOR g IN
            SELECT pr.id AS product_id, u.url AS image_url, u.ord AS sort_order
            FROM products pr
            CROSS JOIN LATERAL unnest(pr.additional_image_urls) WITH ORDINALITY AS u(url, ord)
            WHERE pr.tenant_id = t.id
              AND pr.additional_image_urls IS NOT NULL
              AND u.url IS NOT NULL
              AND u.url <> ''
              AND position('/products/seed/' IN u.url) = 0
        LOOP
            v_pos := position(t.id::text || '/' IN g.image_url);
            IF v_pos > 0 THEN
                v_key := substring(g.image_url FROM v_pos);
            ELSE
                v_key := g.image_url;
            END IF;
            v_sha := encode(sha256(convert_to(v_key, 'UTF8')), 'hex');
            v_ct  := CASE
                        WHEN v_key ILIKE '%.png'  THEN 'image/png'
                        WHEN v_key ILIKE '%.webp' THEN 'image/webp'
                        WHEN v_key ILIKE '%.gif'  THEN 'image/gif'
                        ELSE 'image/jpeg'
                     END;

            INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type, status, flagged, created_at)
            VALUES (gen_random_uuid(), t.id, v_key, v_sha, v_ct, 'ACTIVE', false, now())
            ON CONFLICT (tenant_id, sha256) DO NOTHING;
            GET DIAGNOSTICS v_pos = ROW_COUNT;
            assets_created := assets_created + v_pos;

            SELECT id INTO v_asset_id FROM media_asset WHERE tenant_id = t.id AND sha256 = v_sha;

            INSERT INTO product_media (id, tenant_id, product_id, asset_id, is_primary, sort_order)
            VALUES (gen_random_uuid(), t.id, g.product_id, v_asset_id, false, g.sort_order::int);
            links_created := links_created + 1;
        END LOOP;
    END LOOP;

    -- Defensive reset so no later statement in this transaction inherits the last
    -- tenant's GUC (V44:129).
    PERFORM set_config('app.current_tenant_id', '', true);

    RAISE NOTICE 'V53: backfilled % media_asset row(s) and % product_media link(s) across all tenants.',
                 assets_created, links_created;
END $$;
