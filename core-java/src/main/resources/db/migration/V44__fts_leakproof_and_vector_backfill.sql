-- V44: FTS tail of Issue #96 — LEAKPROOF ts_match_vq + search_vector backfill
--
-- This fills the version slot RESERVED for #96 since PR #172. Two defects:
--
--   DEFECT 1 (GIN index unreachable under RLS): Postgres only allows LEAKPROOF
--     operators as index quals beneath a row-security barrier, and ts_match_vq
--     (the function behind tsvector @@ tsquery) ships proleakproof=false. For
--     the RLS-bound app role every FTS query (ProductRepository.searchFullText,
--     ShopRepository.fullTextSearchPublished) planner-degrades to a tenant-
--     filtered seq scan; the identical SQL is served by a Bitmap Index Scan on
--     idx_products_search / idx_shops_search the moment ts_match_vq is marked
--     LEAKPROOF (proven empirically both ways on postgres:15 — see
--     ProductSearchFtsIntegrationTest's pinned plans). ts_match_vq only
--     compares a tsvector against a tsquery and raises no value-revealing
--     errors, so marking it LEAKPROOF does not open a side channel.
--
--   DEFECT 2 (NULL search_vector rows invisible to search): the live dev DB
--     had 24/25 products with NULL search_vector. Root cause: V25's own
--     backfill UPDATE ran as the RLS-bound migration role with NO tenant GUC
--     set, so under FORCE RLS it saw zero rows and silently no-opped — and any
--     row loaded via a trigger-bypassing path (pg_restore --disable-triggers,
--     ETL with session_replication_role=replica) stays NULL forever. Such rows
--     can never match a tsquery, i.e. they are invisible to search.
--
-- ============================================================================
-- OUT-OF-ORDER APPLICATION — READ BEFORE TOUCHING FLYWAY CONFIG
-- ============================================================================
-- Deployed databases are already stamped PAST this version (live dev DB is at
-- V46). With Flyway's default outOfOrder=false this file would fail validation
-- on every such database ("resolved migration not applied"), bricking boot.
-- spring.flyway.out-of-order=true is therefore set in application.yml (base)
-- and application-staging/prod.yml as part of the same change. Fresh databases
-- (Testcontainers, new installs) are unaffected: they apply V1..V44..V46 in
-- order. Once every deployed environment shows a success row for version 44 in
-- flyway_schema_history, out-of-order MAY be reverted to false.
-- FlywayV44OutOfOrderIntegrationTest proves both halves on real Postgres: a DB
-- stamped to V46 without V44 (a) fails validation with outOfOrder=false and
-- (b) applies exactly this migration with outOfOrder=true.
--
-- ============================================================================
-- PRIVILEGES
-- ============================================================================
-- ALTER FUNCTION pg_catalog.ts_match_vq ... LEAKPROOF requires SUPERUSER (only
-- superusers may mark functions leakproof, and pg_catalog objects are owned by
-- the bootstrap role). Flyway runs as:
--   * Testcontainers  — container bootstrap role = superuser  -> ALTER applies.
--   * compose dev/k8s — jtoye_app, deliberately NOSUPERUSER   -> ALTER fails.
-- The DO block below catches insufficient_privilege and downgrades to a LOUD
-- WARNING instead of failing the migration chain: search stays correct (RLS
-- still filters; the planner just keeps seq-scanning) and the index unlocks as
-- soon as a DBA runs the one-liner from the WARNING as superuser. The
-- search_vector backfill below does NOT need superuser and always runs.
--
-- ============================================================================
-- RLS SAFETY OF THE BACKFILL
-- ============================================================================
-- products and shops carry ENABLE + FORCE ROW LEVEL SECURITY (V2/V16), so the
-- V25 mistake (bare UPDATE, no GUC, zero rows visible) must not be repeated.
-- Instead of the V16 precedent (DISABLE ROW LEVEL SECURITY around the UPDATE —
-- owner-only and momentarily drops the barrier), the loop below walks the
-- tenants registry (no RLS on tenants) and sets the standard tenant GUC
-- transaction-locally per tenant, exactly like the application does. Every row
-- is reached WITH the policies enforced, no policy is altered or bypassed, and
-- the statement works for any role with UPDATE privilege (owner or not).
-- Re-running is a no-op (WHERE search_vector IS NULL).
--
-- The vector expressions below are byte-for-byte the V25 trigger expressions
-- (products_search_vector_update / shops_search_vector_update), so backfilled
-- rows are indistinguishable from trigger-produced ones. The triggers do NOT
-- fire here: they are declared BEFORE INSERT OR UPDATE OF <content columns>,
-- and this UPDATE touches only search_vector.

-- ----------------------------------------------------------------------------
-- 1. DEFECT 1 — mark ts_match_vq LEAKPROOF (superuser only, graceful degrade)
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    ALTER FUNCTION pg_catalog.ts_match_vq(tsvector, tsquery) LEAKPROOF;
    RAISE NOTICE 'V44: pg_catalog.ts_match_vq is now LEAKPROOF — GIN FTS indexes (idx_products_search, idx_shops_search) are reachable under FORCE RLS.';
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE WARNING 'V44: could not mark pg_catalog.ts_match_vq LEAKPROOF — the migration role (%) is not a superuser. '
                      'Full-text search remains CORRECT but planner-degrades to tenant-filtered seq scans under RLS. '
                      'MANUAL STEP (as a superuser, once): ALTER FUNCTION pg_catalog.ts_match_vq(tsvector, tsquery) LEAKPROOF;',
                      current_user;
END $$;

-- ----------------------------------------------------------------------------
-- 2. DEFECT 2 — idempotent backfill of NULL search_vector (products + shops)
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    t                   RECORD;
    n                   BIGINT;
    products_backfilled BIGINT := 0;
    shops_backfilled    BIGINT := 0;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        -- Same GUC the app sets (TenantSetLocalAspect); transaction-local, so
        -- it vanishes when Flyway commits this migration.
        PERFORM set_config('app.current_tenant_id', t.id::text, true);

        -- V25 products trigger expression, verbatim.
        UPDATE products SET search_vector =
            setweight(to_tsvector('english', COALESCE(title, '')), 'A') ||
            setweight(to_tsvector('english', COALESCE(category, '')), 'B') ||
            setweight(to_tsvector('english', COALESCE(description, '')), 'C') ||
            setweight(to_tsvector('english', COALESCE(ingredients_text, '')), 'C') ||
            setweight(to_tsvector('english', COALESCE(dietary_tags, '')), 'D')
        WHERE tenant_id = t.id
          AND search_vector IS NULL;
        GET DIAGNOSTICS n = ROW_COUNT;
        products_backfilled := products_backfilled + n;

        -- V25 shops trigger expression, verbatim.
        UPDATE shops SET search_vector =
            setweight(to_tsvector('english', COALESCE(name, '')), 'A') ||
            setweight(to_tsvector('english', COALESCE(tags, '')), 'B') ||
            setweight(to_tsvector('english', COALESCE(description, '')), 'C') ||
            setweight(to_tsvector('english', COALESCE(address, '')), 'D')
        WHERE tenant_id = t.id
          AND search_vector IS NULL;
        GET DIAGNOSTICS n = ROW_COUNT;
        shops_backfilled := shops_backfilled + n;
    END LOOP;

    -- Defensive reset so no later statement in this transaction inherits the
    -- last tenant's GUC.
    PERFORM set_config('app.current_tenant_id', '', true);

    RAISE NOTICE 'V44: backfilled search_vector on % product row(s) and % shop row(s).',
                 products_backfilled, shops_backfilled;
END $$;
