#!/usr/bin/env bash
# check-media-urls-resolve.sh — every image URL the API can serve resolves on the DELIVERED
# object store, anonymously (Phase 36, BLOB-05 / BLOB-09).
#
# WHY THIS EXISTS
#
#   A green upload test and an HTTP 200 page title say nothing about whether the URLs already
#   sitting in the database point at bytes that exist. Phase 36 moved the object store (the
#   retired object-store origin on :9000 -> Azurite / Azure Blob), rewrote the dev database's
#   stored URLs (plan 36-11) and relies on DemoDataSeeder to re-upload the demo images on the
#   next boot. Each of those steps can go wrong silently: a row the reseed missed still points
#   at the retired origin, a seed object that never landed leaves a URL that 404s, and a
#   derivative whose thumbnail was never written advertises a `_thumb.webp` nobody can fetch.
#   The storefront renders a broken image in every one of those cases while every other gate
#   stays green. This turns "images resolve on the new store" into a count that can fail.
#
# WHAT IS ASSERTED
#
#   U-1  Every stored image URL under the public origin (the running core-java's
#        STORAGE_PUBLIC_URL) answers an ANONYMOUS HEAD with 200. No Authorization header, no
#        SAS, no cookie: a browser fetches these with nothing, so the gate does too.
#   U-2  No stored value points at the RETIRED store, in ANY spelling the residue gate knows.
#        Such a value is a FAILURE, not a skip — it is a URL the reseed should have rewritten
#        and did not. The spellings are NOT kept here: the R-1 pattern is taken from
#        scripts/check-no-object-store-residue.sh --print-retired-pattern (both loopback host
#        spellings of the old :9000 origin, its in-network container name, its cloud hostnames).
#        This gate used to match one literal prefix, so every other spelling of the same dead
#        store was counted "external (not fetched)" and passed (Phase 36 code review WR-05).
#   U-3  DENOMINATOR >= 1. An empty checked set is VOID, never a pass: a wrong tenant scope, a
#        database the seeder never ran against, or an enumeration blinded by RLS all produce
#        exactly "0 failures", and "I checked nothing" must never read as "everything resolves".
#
#   The URL set, enumerated read-only:
#     products.image_url, unnest(products.additional_image_urls), shops.logo_url,
#     shops.banner_url, unnest(reviews.photo_urls), and <public-url>/<object_key> for every
#     ACTIVE media_asset, plus its <id>_thumb.webp sibling when the key is a pipeline
#     derivative (<tenant>/media/<id>.webp) — the same rule as MediaAssetService
#     .thumbnailKeyFor: a V53-backfilled key outside /media/ has no thumbnail sibling, so
#     demanding one would red on correct data.
#
#   Classification of each value:
#     starts with <public-url>/  -> HEADed, must be 200            (counted as CHECKED)
#     absolute http(s) URL matching the residue gate's R-1 -> FAIL "unrewritten old-store URL"
#     any other absolute http(s) URL -> EXTERNAL, reported, not fetched (not ours to assert)
#     a relative path starting with / -> SKIPPED and counted (frontend-served, e.g. /brand/)
#     anything else                   -> FAIL "unrecognised URL shape" (no silent bucket)
#
# WHY THE ENUMERATION RUNS AS THE POSTGRES SUPERUSER
#
#   Every table read here is ENABLE+FORCE RLS. An unpinned query as the application role sees
#   only published rows or nothing at all (memory: RLS blinds the verification query), and a
#   partially blinded enumeration would under-count and pass. The container's own
#   POSTGRES_USER bypasses RLS, and the gate ASSERTS that it does (rolsuper or rolbypassrls)
#   before trusting a single row. The gate writes nothing: every statement is a SELECT.
#
# WHY IT IS A RUNTIME GATE, AND WHERE IT RUNS
#
#   It reads a running Postgres through `docker exec` and fetches from a running Azurite, so
#   on a runner with no stack every precondition below exits 2. It is wired into
#   .github/workflows/e2e-nightly.yml right after the health wait: that is the one CI lane
#   with a live compose stack, a freshly seeded database and a published :10000.
#
# EXIT CODES — uniform with the other ops gates
#   0 = every checked URL answered 200 and nothing points at the retired store
#   1 = at least one did not — each failure is NAMED with its source column and row id
#   2 = VOID (cannot evaluate)
#
#   VOID on: missing docker or curl · the residue gate's R-1 pattern unobtainable, or failing
#   its own controls here (it must match every retired spelling below and must NOT match the
#   public URL or an ordinary external URL) · jtoye-postgres or jtoye-azurite absent or not running ·
#   psql unreachable inside the container · POSTGRES_USER/POSTGRES_DB unreadable · the
#   enumerating role not RLS-exempt · the public URL unresolvable (no running core-java and
#   no --public-url) or ambiguous (two running core-java replicas disagree) · a malformed
#   --only-tenant · the enumeration query failing · a checked set of 0.
#
# SHAPE RULES OBSERVED (each is a recorded failure in this repository)
#   - `docker exec -i`: without -i a stdin-fed psql runs nothing and still exits 0.
#   - rc captured on the SAME statement as its command.
#   - No `cmd | grep -q` (pipefail inverts it on match); tests on data already in hand.
#   - Rows are split with parameter expansion, never `IFS=$'\t' read`: tab is IFS
#     whitespace, so an empty field collapses and every later column shifts.
#   - --only-tenant is validated as a UUID AND passed as a psql variable (:'tenant'), so no
#     argument is ever spliced into SQL text.
#
# USAGE
#   bash scripts/check-media-urls-resolve.sh
#   bash scripts/check-media-urls-resolve.sh --only-tenant 00000000-0000-0000-0000-000000000001
#   bash scripts/check-media-urls-resolve.sh --public-url http://localhost:10000/devstoreaccount1/jtoye-images
#
#   POSTGRES_CONTAINER / AZURITE_CONTAINER override the container names.
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -uo pipefail

PG_CONTAINER="${POSTGRES_CONTAINER:-jtoye-postgres}"
AZ_CONTAINER="${AZURITE_CONTAINER:-jtoye-azurite}"
RESIDUE_GATE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/check-no-object-store-residue.sh"
CURL_TIMEOUT="${MEDIA_URL_TIMEOUT:-10}"

VOID=2
FAIL=1

void() {
    echo "VOID: $*" >&2
    echo "  (a result that cannot be evaluated is never a clean bill)" >&2
    exit "$VOID"
}

usage() { sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'; exit 0; }

ONLY_TENANT=""
FLAG_PUBLIC_URL=""
while [ $# -gt 0 ]; do
    case "$1" in
        --only-tenant)
            [ $# -ge 2 ] || void "--only-tenant needs a value"
            ONLY_TENANT="$2"; shift 2 ;;
        --public-url)
            [ $# -ge 2 ] || void "--public-url needs a value"
            FLAG_PUBLIC_URL="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) void "unknown argument: $1 (try --help)" ;;
    esac
done

if [ -n "$ONLY_TENANT" ]; then
    [[ "$ONLY_TENANT" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
        || void "--only-tenant '$ONLY_TENANT' is not a UUID"
fi

# ---- Preconditions: tooling and containers --------------------------------------------------

command -v docker >/dev/null 2>&1 || void "docker is not on PATH — there is no stack to inspect"
command -v curl >/dev/null 2>&1 || void "curl is not on PATH — nothing can be fetched"

# ---- U-2's pattern: the residue gate's R-1, never a second hand-kept list (WR-05) ------------

[ -f "$RESIDUE_GATE" ] || void "the residue gate is missing ($RESIDUE_GATE) — U-2 has no pattern"
RETIRED_RE=$(bash "$RESIDUE_GATE" --print-retired-pattern 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] && [ -n "$RETIRED_RE" ] \
    || void "could not obtain the retired-store pattern (R-1) from $RESIDUE_GATE (rc=$rc)"

# is_retired <url> — 0 when the URL matches R-1 (case-insensitive PCRE, as the residue scan
# matches), 1 when not; a grep error is a VOID, never a quiet "not retired".
is_retired() {
    local rc=0
    grep -qiP -e "$RETIRED_RE" <<< "$1" || rc=$?
    case "$rc" in
        0) return 0 ;;
        1) return 1 ;;
        *) void "grep -P failed (rc=$rc) matching the retired-store pattern" ;;
    esac
}
# The engine here must see what the residue gate's self-test sees. One sample per spelling
# class this gate once missed; a miss means U-2 is blind, which is a VOID, not a pass.
for sample in "http://localhost:9000/jtoye-images/t/x.webp" "http://127.0.0.1:9000/jtoye-images/t/x.webp" \
              "http://minio:9000/jtoye-images/t/x.webp" "https://s3.eu-west-2.amazonaws.com/jtoye-images/t/x.webp" \
              "https://jtoye-images.s3.eu-west-2.amazonaws.com/t/x.webp"; do
    is_retired "$sample" || void "the retired-store pattern does not match '$sample' here — U-2 would pass it as external"
done
is_retired "https://cdn.example.com/images/x.jpg" \
    && void "the retired-store pattern matches an ordinary external URL — U-2 would fail legitimate data"

container_running() { # <name> <role>
    local state rc
    state=$(docker inspect -f '{{.State.Status}}' "$1" 2>/dev/null); rc=$?
    [ "$rc" -eq 0 ] || void "container '$1' ($2) does not exist (docker inspect rc=$rc)"
    [ "$state" = "running" ] || void "container '$1' ($2) is '$state', not running"
}
container_running "$PG_CONTAINER" "the database"
container_running "$AZ_CONTAINER" "the object store"

PGU=$(docker exec "$PG_CONTAINER" printenv POSTGRES_USER 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] && [ -n "$PGU" ] || void "could not read POSTGRES_USER from '$PG_CONTAINER' (rc=$rc)"
PGDB=$(docker exec "$PG_CONTAINER" printenv POSTGRES_DB 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] && [ -n "$PGDB" ] || void "could not read POSTGRES_DB from '$PG_CONTAINER' (rc=$rc)"

# Every query goes through stdin so psql interpolates :'var' — the only way a value reaches SQL.
psql_q() { # SQL on stdin; extra args are -v bindings
    docker exec -i "$PG_CONTAINER" psql -U "$PGU" -d "$PGDB" -X -q -tA -v ON_ERROR_STOP=1 "$@"
}

EXEMPT=$(printf '%s\n' "SELECT (rolsuper OR rolbypassrls)::text FROM pg_roles WHERE rolname = current_user;" \
    | psql_q 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] || void "psql is unreachable inside '$PG_CONTAINER' as '$PGU' (rc=$rc)"
[ "$EXEMPT" = "true" ] || void "role '$PGU' is not RLS-exempt (rolsuper/rolbypassrls='$EXEMPT') — every table read here is FORCE RLS, so its enumeration could be silently blinded"

# ---- The public URL: from the RUNNING core-java, --public-url only as a fallback -------------

PUBLIC_URL=""
PUBLIC_SRC=""
CORE_IDS=$(docker ps -q --filter label=com.docker.compose.service=core-java --filter status=running 2>/dev/null)
if [ -n "$CORE_IDS" ]; then
    SEEN_VALUES=""
    for cid in $CORE_IDS; do
        env_dump=$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$cid" 2>/dev/null) \
            || void "could not inspect the running core-java container $cid"
        v=""
        while IFS= read -r line; do
            case "$line" in STORAGE_PUBLIC_URL=*) v="${line#STORAGE_PUBLIC_URL=}" ;; esac
        done <<< "$env_dump"
        [ -n "$v" ] || continue
        case " $SEEN_VALUES " in *" $v "*) ;; *) SEEN_VALUES="${SEEN_VALUES:+$SEEN_VALUES }$v" ;; esac
    done
    n_values=$(wc -w <<< "$SEEN_VALUES")
    [ "$n_values" -le 1 ] || void "running core-java replicas disagree on STORAGE_PUBLIC_URL ($SEEN_VALUES)"
    if [ "$n_values" -eq 1 ]; then
        PUBLIC_URL="$SEEN_VALUES"; PUBLIC_SRC="running core-java STORAGE_PUBLIC_URL"
    fi
fi
if [ -z "$PUBLIC_URL" ] && [ -n "$FLAG_PUBLIC_URL" ]; then
    PUBLIC_URL="$FLAG_PUBLIC_URL"; PUBLIC_SRC="--public-url (no running core-java carries one)"
fi
[ -n "$PUBLIC_URL" ] || void "no public URL: no running core-java carries STORAGE_PUBLIC_URL and --public-url was not given"
if [ -n "$FLAG_PUBLIC_URL" ] && [ "$PUBLIC_SRC" != "${PUBLIC_SRC#running}" ] && [ "${FLAG_PUBLIC_URL%/}" != "${PUBLIC_URL%/}" ]; then
    echo "NOTE: --public-url '$FLAG_PUBLIC_URL' ignored; the running core-java says '$PUBLIC_URL'" >&2
fi
PUBLIC_URL="${PUBLIC_URL%/}"
case "$PUBLIC_URL" in http://*|https://*) ;; *) void "public URL '$PUBLIC_URL' is not an http(s) URL" ;; esac
is_retired "$PUBLIC_URL/" \
    && void "the public URL '$PUBLIC_URL' itself matches the retired-store pattern — the delivered store and the retired one cannot be told apart"

# ---- Enumeration (read-only) ----------------------------------------------------------------

SQL=$(cat <<'SQL'
WITH scope AS (SELECT NULLIF(:'tenant', '') AS t)
SELECT 'products.image_url' || E'\t' || p.id::text || E'\t' || p.image_url
  FROM products p, scope WHERE p.image_url IS NOT NULL AND p.image_url <> ''
   AND (scope.t IS NULL OR p.tenant_id::text = scope.t)
UNION ALL
SELECT 'products.additional_image_urls' || E'\t' || p.id::text || E'\t' || u
  FROM products p CROSS JOIN LATERAL unnest(p.additional_image_urls) AS u, scope
 WHERE u IS NOT NULL AND u <> '' AND (scope.t IS NULL OR p.tenant_id::text = scope.t)
UNION ALL
SELECT 'shops.logo_url' || E'\t' || s.id::text || E'\t' || s.logo_url
  FROM shops s, scope WHERE s.logo_url IS NOT NULL AND s.logo_url <> ''
   AND (scope.t IS NULL OR s.tenant_id::text = scope.t)
UNION ALL
SELECT 'shops.banner_url' || E'\t' || s.id::text || E'\t' || s.banner_url
  FROM shops s, scope WHERE s.banner_url IS NOT NULL AND s.banner_url <> ''
   AND (scope.t IS NULL OR s.tenant_id::text = scope.t)
UNION ALL
SELECT 'reviews.photo_urls' || E'\t' || r.id::text || E'\t' || u
  FROM reviews r CROSS JOIN LATERAL unnest(r.photo_urls) AS u, scope
 WHERE u IS NOT NULL AND u <> '' AND (scope.t IS NULL OR r.tenant_id::text = scope.t)
UNION ALL
SELECT 'media_asset.object_key' || E'\t' || m.id::text || E'\t' || :'pub' || '/' || m.object_key
  FROM media_asset m, scope WHERE m.status = 'ACTIVE' AND m.object_key IS NOT NULL
   AND (scope.t IS NULL OR m.tenant_id::text = scope.t)
UNION ALL
SELECT 'media_asset.thumbnail' || E'\t' || m.id::text || E'\t' || :'pub' || '/'
       || left(m.object_key, length(m.object_key) - 5) || '_thumb.webp'
  FROM media_asset m, scope WHERE m.status = 'ACTIVE' AND m.object_key IS NOT NULL
   AND strpos(m.object_key, '/media/') > 0 AND right(m.object_key, 5) = '.webp'
   AND (scope.t IS NULL OR m.tenant_id::text = scope.t);
SQL
)

ROWS=$(printf '%s\n' "$SQL" | psql_q -v tenant="$ONLY_TENANT" -v pub="$PUBLIC_URL" 2>&1); rc=$?
[ "$rc" -eq 0 ] || void "the enumeration query failed (psql rc=$rc): $ROWS"

echo "=============================================================================="
echo " check-media-urls-resolve — stored image URLs vs the DELIVERED object store"
echo " db=$PGDB as $PGU (RLS-exempt)  store=$AZ_CONTAINER  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo " public URL : $PUBLIC_URL  [$PUBLIC_SRC]"
echo " scope      : ${ONLY_TENANT:-all tenants}"
echo "=============================================================================="

CHECKED=0 EXTERNAL=0 SKIPPED=0 OLD=0 UNRECOG=0 BAD=0
FAILURES=()
declare -A HEAD_CACHE=()

while IFS= read -r row; do
    [ -n "$row" ] || continue
    src="${row%%$'\t'*}"; rest="${row#*$'\t'}"
    rid="${rest%%$'\t'*}"; url="${rest#*$'\t'}"
    case "$url" in
        "$PUBLIC_URL"/*)
            CHECKED=$((CHECKED + 1))
            if [ -z "${HEAD_CACHE[$url]+set}" ]; then
                code=$(curl -sS -I -o /dev/null -w '%{http_code}' --max-time "$CURL_TIMEOUT" "$url" 2>/dev/null)
                HEAD_CACHE[$url]="${code:-000}"
            fi
            code="${HEAD_CACHE[$url]}"
            if [ "$code" != "200" ]; then
                BAD=$((BAD + 1))
                FAILURES+=("HTTP $code  $src  id=$rid  $url")
            fi
            ;;
        http://*|https://*)
            if is_retired "$url"; then
                OLD=$((OLD + 1))
                FAILURES+=("unrewritten old-store URL  $src  id=$rid  $url")
            else
                EXTERNAL=$((EXTERNAL + 1))
                echo "  external (not fetched): $src  id=$rid  $url"
            fi
            ;;
        /*)
            SKIPPED=$((SKIPPED + 1))
            ;;
        *)
            UNRECOG=$((UNRECOG + 1))
            FAILURES+=("unrecognised URL shape  $src  id=$rid  $url")
            ;;
    esac
done <<< "$ROWS"

echo
echo "  references checked against $PUBLIC_URL (U-3 denominator, must be >= 1) .. $CHECKED"
echo "    ...distinct URLs HEADed anonymously ..................................... ${#HEAD_CACHE[@]}"
echo "    ...not answering 200 (U-1, must be 0) ..................................... $BAD"
echo "  retired-store references, any R-1 spelling (U-2, must be 0) ................ $OLD"
echo "  unrecognised URL shapes (must be 0) ......................................... $UNRECOG"
echo "  external absolute URLs (reported, not fetched) .............................. $EXTERNAL"
echo "  relative paths skipped (frontend-served, e.g. /brand/) ...................... $SKIPPED"

echo
echo "------------------------------------------------------------------------------"
if [ "${#FAILURES[@]}" -gt 0 ]; then
    echo "FAIL: ${#FAILURES[@]} stored image reference(s) do not resolve on the delivered store:" >&2
    for f in "${FAILURES[@]}"; do echo "        $f" >&2; done
    echo "  A browser renders each of these as a broken image. Re-upload the object, re-run the" >&2
    echo "  seeder (restart core-java), or rewrite the row — never mark the gate green around it." >&2
    exit "$FAIL"
fi

[ "$CHECKED" -gt 0 ] || void "U-3: 0 references under $PUBLIC_URL were found (scope: ${ONLY_TENANT:-all tenants}). An empty checked set is indistinguishable from a broken enumeration"

echo "PASS: $CHECKED reference(s) (${#HEAD_CACHE[@]} distinct URLs) answer 200 anonymously; 0 point at the retired store."
echo "------------------------------------------------------------------------------"
exit 0
