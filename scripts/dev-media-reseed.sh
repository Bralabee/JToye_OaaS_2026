#!/usr/bin/env bash
# ---------------------------------------------------------------------------------
# dev-media-reseed.sh — Phase 36 (plan 36-11, BLOB-05 data half)
#
# Points the SHARED LOCAL DEV database at the Azurite object store after the move off
# the retired S3-API store. It RESEEDS rather than migrates (D-04), and it goes THROUGH
# the tenant wall, never around it (D-05).
#
# DEV ONLY. NEVER A FLYWAY MIGRATION.
#   A migration runs on every database the application ever meets, real stores included.
#   There this script's step (3) would mark real vendors' live media FAILED, and step (4)
#   would delete their image URLs. The old origin it rewrites (localhost:9000) only ever
#   existed in local dev, so the whole operation is dev data maintenance. The guards below
#   refuse anything that is not the local dev container and database.
#
# WHAT IT DOES (one transaction)
#   (1) Visibility proof. As jtoye_app, with the tenant GUC CLEARED, products must count 0,
#       which shows FORCE RLS is enforcing. With the GUC pinned per tenant the total must be
#       > 0, which shows the loop can SEE rows. A bare UPDATE on a FORCE-RLS table matches
#       ZERO rows and reports success; V25 -> V44 -> V57 each paid for that.
#       Coverage proof: ANALYZE, then each table's physical row total (pg_class.reltuples,
#       exact on a table this small) must equal what the tenant loop reached. products and
#       media_asset have no foreign key to tenants, so a row under an unregistered tenant id
#       would otherwise be skipped silently.
#   (2) Before-counts per column and tenant of values starting with <old-origin>/. If the
#       total over the five URL columns is 0 it REFUSES: wrong --old-origin, or already
#       reseeded.
#   (3) media_asset rows that are ACTIVE or PENDING and created before --cutover become
#       FAILED. The failure_reason names the Phase 36 reseed, quarantine_reclaimed_at is set
#       and version is bumped (the V59 @Version lock). Their bytes lived in the old store's
#       on-disk format and did not carry over. This step is NOT optional:
#       ProductService.resolveAssetFirst serves a product's primary ACTIVE asset AHEAD of
#       products.image_url, so an ACTIVE asset with dead bytes would shadow even a correctly
#       rewritten URL. FAILED shows it honestly in the vendor review queue with Re-upload
#       (IMG-04).
#   (4) URL columns: products.image_url, products.additional_image_urls[], shops.logo_url,
#       shops.banner_url and reviews.photo_urls[]. A value under <old-origin>/ that contains
#       /products/seed/ gets its prefix swapped for <new-public-url>. DemoDataSeeder
#       re-uploads the same deterministic seed keys on its next boot. Any OTHER
#       <old-origin>/ value is set to NULL, or dropped from its array: its bytes are gone
#       (D-04). A value not under <old-origin>/ (e.g. /brand/logo-*.png) is untouched by
#       construction, and an md5 over those values must be identical before and after.
#   (5) After-counts. Every column must hold 0 old-origin values. New-origin values must
#       equal before + seed rewrites. Each UPDATE's row count must equal the rows the loop
#       SAW carrying old values, so an UPDATE blinded by RLS (reviews has NO UPDATE policy)
#       refuses loudly and never "succeeds" on zero rows.
#   Every statement also filters tenant_id = <pinned tenant>. shops_public_read and
#   reviews_tenant_read show PUBLISHED rows to every tenant, so the GUC alone would count
#   them twice. _aud tables are append-only history and are left as they are. No blob is
#   deleted: this rewrites rows only (nothing reaches StorageService.delete and its D-09
#   tenant guard), and the old object-store volume stays on disk (D-04).
#
#   --dry-run (the default) runs the SAME statements and ends with ROLLBACK. --apply ends
#   with COMMIT, then re-counts in a fresh transaction. The only thing a dry run leaves
#   behind is the ANALYZE planner statistics, which are not data.
#
# ORDERING — run it BEFORE the first core-java boot on the Blob seam.
#   On its first boot against Azurite, DemoDataSeeder rewrites demo product image URLs to
#   the new origin (it overwrites foreign/legacy URLs). If it runs first, this script's
#   counts no longer describe the pre-cutover state, and the stale media_asset rows are
#   never failed. Bring up only postgres (and azurite):
#     docker compose -f docker-compose.full-stack.yml up -d postgres azurite
#
# USAGE
#   scripts/dev-media-reseed.sh --cutover 2026-09-29T01:00:00Z            # dry run
#   scripts/dev-media-reseed.sh --apply --cutover 2026-09-29T01:00:00Z    # after owner approval
#   Flags:
#     --dry-run | --apply     default --dry-run; both together is refused
#     --cutover <ISO-8601>    REQUIRED in both modes, with an explicit zone (Z or +hh:mm).
#                             Only media_asset rows with created_at < cutover are touched.
#     --old-origin <url>      default http://localhost:9000/jtoye-images
#     --new-public-url <url>  default http://localhost:10000/devstoreaccount1/jtoye-images
#   Environment (for exercising the guards; the guards still apply to whatever is named):
#     PG_CONTAINER   default jtoye-postgres
#     RESEED_DB      default jtoye (anything else is refused)
#
# GUARDS
#   docker missing -> VOID. The docker endpoint (DOCKER_HOST, else the current context) is
#   not a local unix:// socket -> refused. The container is missing or not running -> VOID.
#   The container is not the postgres service of a J'Toye dev compose file -> refused. The
#   connected database is not jtoye, or the role is not a plain jtoye_app (superuser or
#   BYPASSRLS) -> refused. A --cutover later than the database clock -> refused.
#
# All SQL goes through ONE helper:
#   docker exec -i <container> psql -U jtoye_app -d <db> -X -v ON_ERROR_STOP=1
# The -i is load-bearing: without it a heredoc never reaches psql, and the run "succeeds"
# having done nothing. ON_ERROR_STOP makes a failed statement abort the transaction
# (server-side ROLLBACK) and return non-zero. The superuser is never used.
#
# EXIT CODES — uniform with this repo's other scripts
#   0 = dry run previewed and rolled back, or apply committed and re-verified at 0 residue
#   1 = refused or failed (a guard, nothing to reseed, a visibility/coverage/row-count/
#       after-count check); the transaction rolled back and nothing was written
#   2 = VOID — could not evaluate (no docker, no container, no connection). Never treat as 0.
# ---------------------------------------------------------------------------------
set -uo pipefail

PG_CONTAINER="${PG_CONTAINER:-jtoye-postgres}"
RESEED_DB="${RESEED_DB:-jtoye}"
REQUIRED_DB="jtoye"
FAILED_REASON='Bytes not carried over in the Phase 36 dev reseed (D-04) -- re-upload'

MODE=""
CUTOVER=""
OLD_ORIGIN="http://localhost:9000/jtoye-images"
NEW_PUBLIC_URL="http://localhost:10000/devstoreaccount1/jtoye-images"

void()   { echo "VOID: $*" >&2; exit 2; }
refuse() { echo "REFUSED: $*" >&2; exit 1; }
usage()  { sed -n '/^# USAGE/,/^# GUARDS/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'; }

set_mode() {
  if [ -n "$MODE" ] && [ "$MODE" != "$1" ]; then
    refuse "--dry-run and --apply are mutually exclusive"
  fi
  MODE="$1"
}

need_value() { [ "$#" -ge 2 ] && [ -n "$2" ] || refuse "$1 needs a value (see --help)"; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run)          set_mode dry-run; shift ;;
    --apply)            set_mode apply; shift ;;
    --cutover)          need_value "$@"; CUTOVER="$2"; shift 2 ;;
    --cutover=*)        CUTOVER="${1#*=}"; shift ;;
    --old-origin)       need_value "$@"; OLD_ORIGIN="$2"; shift 2 ;;
    --old-origin=*)     OLD_ORIGIN="${1#*=}"; shift ;;
    --new-public-url)   need_value "$@"; NEW_PUBLIC_URL="$2"; shift 2 ;;
    --new-public-url=*) NEW_PUBLIC_URL="${1#*=}"; shift ;;
    -h|--help)          usage; exit 0 ;;
    *)                  refuse "unknown argument: $1 (see --help)" ;;
  esac
done
MODE="${MODE:-dry-run}"

# --- Argument validation (all refusals: the input is wrong, nothing was evaluated) -----
[ -n "$CUTOVER" ] || refuse "--cutover <ISO-8601 with zone> is required in both modes"
ISO_RE='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?(Z|[+-][0-9]{2}:[0-9]{2})$'
[[ "$CUTOVER" =~ $ISO_RE ]] || refuse "--cutover '$CUTOVER' is not ISO-8601 with an explicit zone (e.g. 2026-09-29T01:00:00Z)"

OLD_ORIGIN="${OLD_ORIGIN%/}"
NEW_PUBLIC_URL="${NEW_PUBLIC_URL%/}"
# scheme://host[:port]/segment[/segment...] — at least one path segment (the container),
# no quotes, spaces or backslashes. The values also travel as psql variables, so SQL
# quoting is psql's job; this is about refusing a malformed origin early.
URL_RE='^https?://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~%-]+)+$'
[[ "$OLD_ORIGIN" =~ $URL_RE ]]     || refuse "--old-origin '$OLD_ORIGIN' is not scheme://host[:port]/container"
[[ "$NEW_PUBLIC_URL" =~ $URL_RE ]] || refuse "--new-public-url '$NEW_PUBLIC_URL' is not scheme://host[:port]/container"
[ "$OLD_ORIGIN" != "$NEW_PUBLIC_URL" ] || refuse "--old-origin and --new-public-url are the same"
# If one were a path prefix of the other, a rewritten value would still start with the old
# origin (the after-count could never reach 0), or an old value would count as new.
case "$NEW_PUBLIC_URL/" in "$OLD_ORIGIN/"*) refuse "--new-public-url lies under --old-origin";; esac
case "$OLD_ORIGIN/" in "$NEW_PUBLIC_URL/"*) refuse "--old-origin lies under --new-public-url";; esac

# First stdout line, by contract: a later run (the apply, the post-apply proof) reads the
# approved cutover back from the saved dry-run output.
echo "cutover=$CUTOVER"
echo "dev-media-reseed  mode=$MODE  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  old-origin     : $OLD_ORIGIN"
echo "  new-public-url : $NEW_PUBLIC_URL"
echo "  container      : $PG_CONTAINER"
echo "  database       : $RESEED_DB (role jtoye_app)"

# --- Guard: docker, and a LOCAL docker endpoint ---------------------------------------
command -v docker >/dev/null 2>&1 || void "docker not on PATH"
if [ -n "${DOCKER_HOST:-}" ]; then
  ENDPOINT="$DOCKER_HOST"
else
  ENDPOINT=$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null); rc=$?
  [ "$rc" -eq 0 ] && [ -n "$ENDPOINT" ] || void "cannot resolve the docker endpoint of the current context (rc=$rc)"
fi
case "$ENDPOINT" in
  unix://*) echo "  docker endpoint: $ENDPOINT (local)" ;;
  *) refuse "docker endpoint '$ENDPOINT' is not a local unix:// socket; this script only touches a local dev container" ;;
esac

# --- Guard: the container is this repo's running dev postgres -------------------------
# One inspect, three fields. The separator is '|' and not a tab, because a whitespace IFS
# collapses empty fields and shifts the later ones.
CINFO=$(docker inspect -f '{{.State.Running}}|{{index .Config.Labels "com.docker.compose.service"}}|{{index .Config.Labels "com.docker.compose.project.config_files"}}' "$PG_CONTAINER" 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] || void "container $PG_CONTAINER does not exist; start it: docker compose -f docker-compose.full-stack.yml up -d postgres azurite"
IFS='|' read -r C_RUNNING C_SERVICE C_CONFIG <<< "$CINFO"
[ "$C_RUNNING" = "true" ] || void "container $PG_CONTAINER is not running; start it: docker compose -f docker-compose.full-stack.yml up -d postgres azurite"
[ "$C_SERVICE" = "postgres" ] || refuse "container $PG_CONTAINER is compose service '${C_SERVICE:-<none>}', not the dev 'postgres' service"
case "$C_CONFIG" in
  *docker-compose.full-stack.yml*|*infra/docker-compose.yml*) ;;
  *) refuse "container $PG_CONTAINER was not created from a J'Toye dev compose file (config_files='${C_CONFIG:-<none>}')" ;;
esac

# --- The ONE SQL helper ------------------------------------------------------------------
run_sql() {
  docker exec -i "$PG_CONTAINER" psql -U jtoye_app -d "$RESEED_DB" -X -v ON_ERROR_STOP=1 "$@"
}

# --- Guard: the connected database and role -------------------------------------------
IDENT=$(run_sql -tA 2>&1 <<'SQL'
SELECT current_database() || '|' || current_user || '|' || rolsuper || '|' || rolbypassrls
  FROM pg_roles WHERE rolname = current_user;
SQL
); rc=$?
[ "$rc" -eq 0 ] || void "cannot query database '$RESEED_DB' as jtoye_app (psql rc=$rc): $(printf '%s' "$IDENT" | tail -1)"
IFS='|' read -r I_DB I_ROLE I_SUPER I_BYPASS <<< "$IDENT"
[ -n "$I_DB" ] || void "identity query returned nothing"
[ "$I_DB" = "$REQUIRED_DB" ] || refuse "connected database is '$I_DB', not the dev database '$REQUIRED_DB'"
[ "$I_ROLE" = "jtoye_app" ] || refuse "connected as '$I_ROLE', not jtoye_app"
[ "$I_SUPER" = "false" ] && [ "$I_BYPASS" = "false" ] \
  || refuse "role $I_ROLE bypasses RLS (rolsuper=$I_SUPER rolbypassrls=$I_BYPASS); the rewrite must go through the tenant wall"
echo "  identity       : database=$I_DB role=$I_ROLE rolsuper=$I_SUPER rolbypassrls=$I_BYPASS"
echo

if [ "$MODE" = "apply" ]; then APPLY=true; else APPLY=false; fi

# --- The transaction ---------------------------------------------------------------------
# Quoted heredoc: bash expands nothing inside. The values arrive as psql variables
# (:'name' quotes them as SQL literals). psql does not interpolate inside a dollar-quoted
# DO body, so they are handed on as transaction-local custom settings.
OUT=$(run_sql -q \
  -v "old_origin=$OLD_ORIGIN" \
  -v "new_url=$NEW_PUBLIC_URL" \
  -v "cutover=$CUTOVER" \
  -v "failed_reason=$FAILED_REASON" \
  -v "apply=$APPLY" 2>&1 <<'SQL'
\pset footer off
\pset null '<null>'
BEGIN;
SET LOCAL TimeZone = 'UTC';
SET LOCAL lock_timeout = '10s';
\o /dev/null
SELECT set_config('jtoye_reseed.old_origin', :'old_origin', true),
       set_config('jtoye_reseed.new_url',    :'new_url', true),
       set_config('jtoye_reseed.cutover',    (:'cutover')::timestamptz::text, true),
       set_config('jtoye_reseed.reason',     :'failed_reason', true);
\o

-- Nothing else writes these tables while the counts and the rewrite run, so the row counts
-- compared below describe one state.
LOCK TABLE tenants IN SHARE MODE;
LOCK TABLE products, shops, reviews, media_asset IN SHARE ROW EXCLUSIVE MODE;
-- Physical row totals for the coverage proof. On a table smaller than
-- 300 x default_statistics_target pages, ANALYZE reads every block, so reltuples is exact.
ANALYZE products;
ANALYZE shops;
ANALYZE reviews;
ANALYZE media_asset;

CREATE TEMP TABLE _rs_cols (ord int, tbl text, col text, is_arr boolean) ON COMMIT DROP;
INSERT INTO _rs_cols VALUES
  (1, 'products', 'image_url',             false),
  (2, 'products', 'additional_image_urls', true),
  (3, 'shops',    'logo_url',              false),
  (4, 'shops',    'banner_url',            false),
  (5, 'reviews',  'photo_urls',            true);
CREATE TEMP TABLE _rs_vis     (tenant uuid, tbl text, n bigint) ON COMMIT DROP;
CREATE TEMP TABLE _rs_counts  (stage text, tenant uuid, ord int, subject text,
                               rows_old bigint, old_seed bigint, old_other bigint,
                               new_vals bigint, untouched bigint, untouched_md5 text) ON COMMIT DROP;
CREATE TEMP TABLE _rs_media   (stage text, tenant uuid, n bigint) ON COMMIT DROP;
CREATE TEMP TABLE _rs_rows    (tenant uuid, subject text, updated bigint) ON COMMIT DROP;
CREATE TEMP TABLE _rs_samples (tenant uuid, ord int, subject text, tbl text, col text,
                               rid text, before_value text, after_value text) ON COMMIT DROP;

-- Per (table, column, tenant): rows carrying an old-origin value, old-origin values with and
-- without the seed marker, new-origin values, and every other non-null value with an md5
-- over them (these must come through byte-identical). Array columns count ELEMENTS.
CREATE FUNCTION pg_temp.rs_count(tbl text, col text, is_arr boolean, tid uuid,
    OUT rows_old bigint, OUT old_seed bigint, OUT old_other bigint,
    OUT new_vals bigint, OUT untouched bigint, OUT untouched_md5 text)
LANGUAGE plpgsql AS $f$
DECLARE
  pfx  text := current_setting('jtoye_reseed.old_origin') || '/';
  npfx text := current_setting('jtoye_reseed.new_url') || '/';
  src  text;
BEGIN
  IF is_arr THEN
    src := format('SELECT x.id::text AS rid, u.v FROM %I x CROSS JOIN LATERAL unnest(x.%I) AS u(v) WHERE x.tenant_id = $1', tbl, col);
  ELSE
    src := format('SELECT x.id::text AS rid, x.%I AS v FROM %I x WHERE x.tenant_id = $1', col, tbl);
  END IF;
  EXECUTE format($q$
    SELECT count(DISTINCT rid) FILTER (WHERE starts_with(v, $2)),
           count(*) FILTER (WHERE starts_with(v, $2) AND strpos(v, '/products/seed/') > 0),
           count(*) FILTER (WHERE starts_with(v, $2) AND strpos(v, '/products/seed/') = 0),
           count(*) FILTER (WHERE starts_with(v, $3)),
           count(*) FILTER (WHERE v IS NOT NULL AND NOT starts_with(v, $2) AND NOT starts_with(v, $3)),
           md5(coalesce(string_agg(rid || ':' || v, E'\n' ORDER BY rid, v)
                 FILTER (WHERE v IS NOT NULL AND NOT starts_with(v, $2) AND NOT starts_with(v, $3)), ''))
      FROM (%s) s$q$, src)
    INTO rows_old, old_seed, old_other, new_vals, untouched, untouched_md5
    USING tid, pfx, npfx;
END $f$;

-- Up to three rows per (table, column, tenant) that carry an old-origin value, as samples.
CREATE FUNCTION pg_temp.rs_sample(tbl text, col text, is_arr boolean, tid uuid)
RETURNS TABLE (rid text, val text)
LANGUAGE plpgsql AS $f$
DECLARE
  pfx text := current_setting('jtoye_reseed.old_origin') || '/';
BEGIN
  IF is_arr THEN
    RETURN QUERY EXECUTE format(
      'SELECT x.id::text, x.%1$I::text FROM %2$I x WHERE x.tenant_id = $1
          AND EXISTS (SELECT 1 FROM unnest(x.%1$I) AS w(v) WHERE starts_with(w.v, $2))
        ORDER BY x.id LIMIT 3', col, tbl) USING tid, pfx;
  ELSE
    RETURN QUERY EXECUTE format(
      'SELECT x.id::text, x.%1$I::text FROM %2$I x WHERE x.tenant_id = $1
          AND starts_with(x.%1$I, $2) ORDER BY x.id LIMIT 3', col, tbl) USING tid, pfx;
  END IF;
END $f$;

-- Step (4) for one (table, column, tenant). Returns the UPDATE's own row count.
CREATE FUNCTION pg_temp.rs_rewrite(tbl text, col text, is_arr boolean, tid uuid)
RETURNS bigint
LANGUAGE plpgsql AS $f$
DECLARE
  old_o text := current_setting('jtoye_reseed.old_origin');
  new_o text := current_setting('jtoye_reseed.new_url');
  n     bigint;
BEGIN
  IF is_arr THEN
    -- Keep order; swap the prefix of seed values; drop other old-origin values; keep the rest
    -- (NULL elements included).
    EXECUTE format($q$
      UPDATE %1$I x
         SET %2$I = (SELECT coalesce(array_agg(CASE WHEN starts_with(u.v, $2)
                                                    THEN $4 || substr(u.v, length($3) + 1)
                                                    ELSE u.v END ORDER BY u.ord), '{}'::text[])
                       FROM unnest(x.%2$I) WITH ORDINALITY AS u(v, ord)
                      WHERE NOT (coalesce(starts_with(u.v, $2), false)
                                 AND strpos(u.v, '/products/seed/') = 0))
       WHERE x.tenant_id = $1
         AND EXISTS (SELECT 1 FROM unnest(x.%2$I) AS w(v) WHERE starts_with(w.v, $2))$q$, tbl, col)
      USING tid, old_o || '/', old_o, new_o;
  ELSE
    EXECUTE format($q$
      UPDATE %1$I x
         SET %2$I = CASE WHEN strpos(x.%2$I, '/products/seed/') > 0
                         THEN $4 || substr(x.%2$I, length($3) + 1) END
       WHERE x.tenant_id = $1 AND starts_with(x.%2$I, $2)$q$, tbl, col)
      USING tid, old_o || '/', old_o, new_o;
  END IF;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $f$;

DO $rs$
DECLARE
  cut        timestamptz := current_setting('jtoye_reseed.cutover')::timestamptz;
  reason     text        := current_setting('jtoye_reseed.reason');
  sample_cap bigint      := 300 * current_setting('default_statistics_target')::bigint;
  t          record;
  c          record;
  r          record;
  n          bigint;
  exp_n      bigint;
  v          text;
  unpinned   bigint;
  pinned     bigint;
  url_before bigint;
  flipped    bigint;
BEGIN
  -- Identity, re-asserted inside the transaction (the shell checked it too).
  IF current_database() <> 'jtoye' THEN
    RAISE EXCEPTION 'RESEED-REFUSED: connected to database %, not jtoye', current_database();
  END IF;
  IF current_user <> 'jtoye_app' OR EXISTS (SELECT 1 FROM pg_roles
       WHERE rolname = current_user AND (rolsuper OR rolbypassrls)) THEN
    RAISE EXCEPTION 'RESEED-REFUSED: % is not a plain jtoye_app; the rewrite must go through RLS', current_user;
  END IF;
  IF cut > now() THEN
    RAISE EXCEPTION 'RESEED-REFUSED: --cutover % is later than the database clock %', cut, now();
  END IF;

  -- (1) Visibility, unpinned: with the GUC cleared FORCE RLS must hide every product.
  PERFORM set_config('app.current_tenant_id', '', true);
  SELECT count(*) INTO unpinned FROM products;
  INSERT INTO _rs_vis VALUES (NULL, 'products', unpinned);
  IF unpinned <> 0 THEN
    RAISE EXCEPTION 'RESEED-REFUSED: RLS not enforcing; investigate before rewriting (% product(s) visible with the tenant GUC cleared)', unpinned;
  END IF;

  -- (1)+(2) Loop 1, pinned per tenant: visibility and before-counts. Nothing is written yet.
  FOR t IN SELECT id FROM tenants ORDER BY id LOOP
    PERFORM set_config('app.current_tenant_id', t.id::text, true);
    INSERT INTO _rs_vis SELECT t.id, 'products',    count(*) FROM products    WHERE tenant_id = t.id;
    INSERT INTO _rs_vis SELECT t.id, 'shops',       count(*) FROM shops       WHERE tenant_id = t.id;
    INSERT INTO _rs_vis SELECT t.id, 'reviews',     count(*) FROM reviews     WHERE tenant_id = t.id;
    INSERT INTO _rs_vis SELECT t.id, 'media_asset', count(*) FROM media_asset WHERE tenant_id = t.id;
    FOR c IN SELECT * FROM _rs_cols ORDER BY ord LOOP
      INSERT INTO _rs_counts
        SELECT 'before', t.id, c.ord, c.tbl || '.' || c.col, s.*
          FROM pg_temp.rs_count(c.tbl, c.col, c.is_arr, t.id) s
        RETURNING rows_old, old_seed, old_other INTO r;
      RAISE NOTICE 'before tenant=% column=%.% rows_with_old=% old_seed_values=% old_other_values=%',
        t.id, c.tbl, c.col, r.rows_old, r.old_seed, r.old_other;
      INSERT INTO _rs_samples (tenant, ord, subject, tbl, col, rid, before_value)
        SELECT t.id, c.ord, c.tbl || '.' || c.col, c.tbl, c.col, s.rid, s.val
          FROM pg_temp.rs_sample(c.tbl, c.col, c.is_arr, t.id) s;
    END LOOP;
    INSERT INTO _rs_media SELECT 'before: ACTIVE/PENDING created < cutover (to FAIL)', t.id, count(*)
      FROM media_asset WHERE tenant_id = t.id AND status IN ('ACTIVE', 'PENDING') AND created_at < cut;
    INSERT INTO _rs_media SELECT 'before: ACTIVE/PENDING created >= cutover (kept)', t.id, count(*)
      FROM media_asset WHERE tenant_id = t.id AND status IN ('ACTIVE', 'PENDING') AND created_at >= cut;
    INSERT INTO _rs_media SELECT 'before: carrying the Phase 36 reason', t.id, count(*)
      FROM media_asset WHERE tenant_id = t.id AND failure_reason = reason;
  END LOOP;
  PERFORM set_config('app.current_tenant_id', '', true);

  SELECT coalesce(sum(_rs_vis.n), 0) INTO pinned FROM _rs_vis WHERE tenant IS NOT NULL AND tbl = 'products';
  RAISE NOTICE 'visibility: unpinned products=% pinned products total=%', unpinned, pinned;
  IF pinned = 0 THEN
    RAISE EXCEPTION 'RESEED-REFUSED: RLS blind or wrong database: 0 products visible with the tenant GUC pinned';
  END IF;

  -- Coverage: the tenant loop must have reached every physical row.
  FOR r IN SELECT vis.tbl, sum(vis.n) AS reached, pc.reltuples::bigint AS physical, pc.relpages
             FROM _rs_vis vis JOIN pg_class pc ON pc.oid = vis.tbl::regclass
            WHERE vis.tenant IS NOT NULL
            GROUP BY vis.tbl, pc.reltuples, pc.relpages LOOP
    IF r.relpages > sample_cap THEN
      RAISE EXCEPTION 'RESEED-REFUSED: % has % pages; ANALYZE samples at most %, so the coverage count would not be exact', r.tbl, r.relpages, sample_cap;
    END IF;
    IF r.reached <> r.physical THEN
      RAISE EXCEPTION 'RESEED-REFUSED: % holds % row(s) but the tenant loop reached % -- rows under an unregistered tenant id would be skipped', r.tbl, r.physical, r.reached;
    END IF;
  END LOOP;

  SELECT coalesce(sum(old_seed + old_other), 0) INTO url_before FROM _rs_counts WHERE stage = 'before';
  IF url_before = 0 THEN
    RAISE EXCEPTION 'RESEED-REFUSED: nothing references the old origin % -- wrong --old-origin, or already reseeded',
      current_setting('jtoye_reseed.old_origin');
  END IF;

  -- (3)+(4) Loop 2, pinned per tenant: the rewrite. Every UPDATE must match exactly the rows
  -- loop 1 saw, or it is blind.
  FOR t IN SELECT id FROM tenants ORDER BY id LOOP
    PERFORM set_config('app.current_tenant_id', t.id::text, true);

    UPDATE media_asset
       SET status = 'FAILED',
           failure_reason = reason,
           quarantine_reclaimed_at = coalesce(quarantine_reclaimed_at, now()),
           version = version + 1
     WHERE tenant_id = t.id AND status IN ('ACTIVE', 'PENDING') AND created_at < cut;
    GET DIAGNOSTICS n = ROW_COUNT;
    SELECT m.n INTO exp_n FROM _rs_media m
     WHERE m.tenant = t.id AND m.stage = 'before: ACTIVE/PENDING created < cutover (to FAIL)';
    IF n <> exp_n THEN
      RAISE EXCEPTION 'RESEED-FAILED: media_asset for tenant % matched % row(s) but % were visible -- the UPDATE is blind', t.id, n, exp_n;
    END IF;
    INSERT INTO _rs_rows VALUES (t.id, 'media_asset -> FAILED', n);

    FOR c IN SELECT * FROM _rs_cols ORDER BY ord LOOP
      n := pg_temp.rs_rewrite(c.tbl, c.col, c.is_arr, t.id);
      SELECT k.rows_old INTO exp_n FROM _rs_counts k WHERE k.stage = 'before' AND k.tenant = t.id AND k.ord = c.ord;
      IF n <> exp_n THEN
        RAISE EXCEPTION 'RESEED-FAILED: %.% for tenant % updated % row(s) but % carried old-origin values -- the UPDATE is blind (no UPDATE policy? GUC not applied?)',
          c.tbl, c.col, t.id, n, exp_n;
      END IF;
      INSERT INTO _rs_rows VALUES (t.id, c.tbl || '.' || c.col, n);
    END LOOP;
  END LOOP;
  PERFORM set_config('app.current_tenant_id', '', true);

  -- (5) Loop 3, pinned per tenant: after-counts, read back from the rows themselves.
  FOR t IN SELECT id FROM tenants ORDER BY id LOOP
    PERFORM set_config('app.current_tenant_id', t.id::text, true);
    FOR c IN SELECT * FROM _rs_cols ORDER BY ord LOOP
      INSERT INTO _rs_counts
        SELECT 'after', t.id, c.ord, c.tbl || '.' || c.col, s.*
          FROM pg_temp.rs_count(c.tbl, c.col, c.is_arr, t.id) s;
    END LOOP;
    FOR r IN SELECT * FROM _rs_samples WHERE tenant = t.id LOOP
      EXECUTE format('SELECT x.%I::text FROM %I x WHERE x.id::text = $1 AND x.tenant_id = $2', r.col, r.tbl)
        INTO v USING r.rid, t.id;
      UPDATE _rs_samples SET after_value = v WHERE tenant = r.tenant AND ord = r.ord AND rid = r.rid;
    END LOOP;
    INSERT INTO _rs_media SELECT 'after: ACTIVE/PENDING created < cutover', t.id, count(*)
      FROM media_asset WHERE tenant_id = t.id AND status IN ('ACTIVE', 'PENDING') AND created_at < cut;
    INSERT INTO _rs_media SELECT 'after: carrying the Phase 36 reason', t.id, count(*)
      FROM media_asset WHERE tenant_id = t.id AND failure_reason = reason;
  END LOOP;
  PERFORM set_config('app.current_tenant_id', '', true);

  FOR r IN SELECT b.tenant, b.subject, b.old_seed, b.new_vals, b.untouched, b.untouched_md5,
                  a.old_seed + a.old_other AS a_old, a.new_vals AS a_new,
                  a.untouched AS a_unt, a.untouched_md5 AS a_md5
             FROM _rs_counts b
             JOIN _rs_counts a ON a.stage = 'after' AND a.tenant = b.tenant AND a.ord = b.ord
            WHERE b.stage = 'before' LOOP
    IF r.a_old <> 0 THEN
      RAISE EXCEPTION 'RESEED-FAILED: % for tenant % still holds % old-origin value(s) after the rewrite', r.subject, r.tenant, r.a_old;
    END IF;
    IF r.a_new <> r.new_vals + r.old_seed THEN
      RAISE EXCEPTION 'RESEED-FAILED: % for tenant % has % new-origin value(s); expected % + % seed rewrites', r.subject, r.tenant, r.a_new, r.new_vals, r.old_seed;
    END IF;
    IF r.a_unt <> r.untouched OR r.a_md5 <> r.untouched_md5 THEN
      RAISE EXCEPTION 'RESEED-FAILED: % for tenant %: values outside both origins changed (count % -> %, md5 % -> %)', r.subject, r.tenant, r.untouched, r.a_unt, r.untouched_md5, r.a_md5;
    END IF;
  END LOOP;

  FOR t IN SELECT id FROM tenants ORDER BY id LOOP
    SELECT m.n INTO n FROM _rs_media m WHERE m.tenant = t.id AND m.stage = 'after: ACTIVE/PENDING created < cutover';
    IF n <> 0 THEN
      RAISE EXCEPTION 'RESEED-FAILED: tenant % still has % ACTIVE/PENDING media_asset row(s) from before the cutover', t.id, n;
    END IF;
    SELECT rr.updated INTO flipped FROM _rs_rows rr WHERE rr.tenant = t.id AND rr.subject = 'media_asset -> FAILED';
    SELECT a.n - b.n INTO n
      FROM _rs_media a JOIN _rs_media b ON b.tenant = a.tenant AND b.stage = 'before: carrying the Phase 36 reason'
     WHERE a.tenant = t.id AND a.stage = 'after: carrying the Phase 36 reason';
    IF n <> flipped THEN
      RAISE EXCEPTION 'RESEED-FAILED: tenant % gained % row(s) with the Phase 36 reason but % were flipped', t.id, n, flipped;
    END IF;
  END LOOP;
END $rs$;

\echo
\echo '== visibility: rows counted as jtoye_app under FORCE RLS; tenant (GUC cleared) must be 0 =='
SELECT coalesce(tenant::text, '(GUC cleared)') AS tenant, tbl AS "table", n AS rows_visible
  FROM _rs_vis ORDER BY tenant NULLS FIRST, tbl;
\echo '== coverage: rows the tenant loop reached vs the physical total (ANALYZE) =='
SELECT vis.tbl AS "table", sum(vis.n) AS reached_by_loop, pc.reltuples::bigint AS physical_rows
  FROM _rs_vis vis JOIN pg_class pc ON pc.oid = vis.tbl::regclass
 WHERE vis.tenant IS NOT NULL GROUP BY vis.tbl, pc.reltuples ORDER BY 1;
\echo '== URL columns per tenant: before = as found, after = read back inside this transaction =='
SELECT stage, tenant, subject AS "column", rows_old AS rows_with_old,
       old_seed AS old_seed_values, old_other AS old_other_values,
       new_vals AS new_origin_values, untouched AS other_values, left(untouched_md5, 12) AS other_md5
  FROM _rs_counts ORDER BY (stage = 'after'), tenant, ord;
\echo '== URL column totals =='
SELECT stage || '-total' AS stage, sum(rows_old) AS rows_with_old, sum(old_seed) AS old_seed_values,
       sum(old_other) AS old_other_values, sum(new_vals) AS new_origin_values, sum(untouched) AS other_values
  FROM _rs_counts GROUP BY stage ORDER BY (stage = 'after');
\echo '== media_asset per tenant =='
SELECT stage, tenant, n FROM _rs_media ORDER BY (stage LIKE 'after%'), stage, tenant;
\echo '== rows updated per statement (each equals the rows seen carrying old values) =='
SELECT tenant, subject AS statement, updated AS rows_updated FROM _rs_rows ORDER BY tenant, subject;
\echo '== samples: before -> after, read back from the row =='
SELECT subject AS "column", before_value AS "before", after_value AS "after"
  FROM _rs_samples ORDER BY ord, tenant, rid;

\if :apply
COMMIT;
\echo 'RESULT: COMMITTED'
BEGIN;
SET LOCAL TimeZone = 'UTC';
\o /dev/null
SELECT set_config('jtoye_reseed.old_origin', :'old_origin', true),
       set_config('jtoye_reseed.new_url',    :'new_url', true);
\o
DO $post$
DECLARE t record; c record; n bigint; total bigint := 0;
BEGIN
  FOR t IN SELECT id FROM tenants ORDER BY id LOOP
    PERFORM set_config('app.current_tenant_id', t.id::text, true);
    FOR c IN SELECT * FROM (VALUES ('products', 'image_url', false), ('products', 'additional_image_urls', true),
                                   ('shops', 'logo_url', false), ('shops', 'banner_url', false),
                                   ('reviews', 'photo_urls', true)) AS k(tbl, col, is_arr) LOOP
      SELECT s.old_seed + s.old_other INTO n FROM pg_temp.rs_count(c.tbl, c.col, c.is_arr, t.id) s;
      total := total + n;
    END LOOP;
  END LOOP;
  PERFORM set_config('app.current_tenant_id', '', true);
  RAISE NOTICE 'post-commit (fresh transaction, pinned per tenant): old-origin values remaining=%', total;
  IF total <> 0 THEN
    RAISE EXCEPTION 'RESEED-FAILED: % old-origin value(s) remain after COMMIT', total;
  END IF;
END $post$;
COMMIT;
\else
ROLLBACK;
\echo 'RESULT: ROLLED BACK (dry run) -- nothing was written'
\endif
SQL
); rc=$?
printf '%s\n' "$OUT"

case "$rc" in
  0) exit 0 ;;
  2) void "the database connection was lost (psql rc=2); the transaction did not commit" ;;
  *)
    if grep -q 'RESEED-REFUSED' <<< "$OUT"; then
      refuse "the transaction refused and rolled back (psql rc=$rc); see the ERROR line above"
    fi
    refuse "the transaction failed and rolled back (psql rc=$rc); see the ERROR line above"
    ;;
esac
