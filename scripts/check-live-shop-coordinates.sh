#!/usr/bin/env bash
# check-live-shop-coordinates.sh — #460 link 3, asserted against the DELIVERED RUNTIME.
#
# WHY THIS EXISTS
#
#   Phase 33's headline claim is that a customer can be shown real published shops ordered by
#   real distance. Every link in that chain sits on ONE fact: shops have coordinates. Control
#   arm CA-1 (.planning/phases/33-the-consumer-product/33-CONTROL-ARMS.md) recorded the
#   pre-state on 2026-08-08 — 5 shops, 0 with a latitude — and nothing in the phase re-read the
#   live database after population. Testcontainers tests over a 7-row fixture prove the CODE;
#   they do not prove the running stack. Proof Standard #2: verify the running thing.
#
#   The specific silent degradation this closes: if postcode_centroid were empty, or the
#   backfill never ran, every coordinate would stay NULL, the radius query would correctly
#   return nothing, the located row would render its empty state — and every automated verify
#   in the phase would stay GREEN while the feature did nothing at all. The only symptom is a
#   customer seeing "no kitchens near you", forever.
#
# WHAT IS ASSERTED, AND WHY IN THIS FORM
#
#   A-1  RELATION, not census. Every published shop whose address yields a postcode that IS
#        PRESENT in postcode_centroid must have a non-NULL coordinate. Read as the superuser.
#
#        Deliberately NOT "total = 5". That equality reds on any sixth shop, including one an
#        E2E run or a manual test creates, and a gate that fails on legitimate data teaches
#        people to ignore it — which costs more than the coverage it buys.
#
#        Deliberately ALSO not the wider form "every published shop whose address yields a
#        postcode at all". SUBSTITUTION RECORDED RATHER THAN MADE SILENTLY: the plan specified
#        that wider predicate, and it is strictly stronger, but it reds on CORRECT data. Code-
#        Point Open is GB-only, so a Northern Ireland vendor's postcode is real, extractable,
#        and permanently absent from the table (33-02 SOURCE.md records this as a licence-
#        containment choice, not a bug) — that vendor keeps their storefront and is simply
#        absent from distance results. The wider predicate would red the platform for having
#        exactly the behaviour it was designed to have. Same species of defect as "total = 5",
#        one level deeper. The narrower relation is used, and the wider one is REPORTED below
#        so nothing is hidden.
#
#   A-2  DENOMINATOR >= 1. An absence-only predicate passes when the filter matches nothing: a
#        broken postcode-extraction pattern, or an emptied shops table, would report a clean
#        zero over a fully-NULL database. So the count of published shops matching the filter
#        must itself be at least 1, in the same query.
#
#   A-3  THE SEEDED SHOPS, BY NAME. The three curated demo storefronts are OUR data, not a
#        vendor's, and all three must geocode. This limb keeps the plan's full strength exactly
#        where the false-positive risk is zero: it catches a runtime that shipped with a
#        postcode that does not exist (the defect 33-05 Task 2 fixed) without being able to red
#        on a shop we do not control. Its own denominator is asserted too — if the seeder
#        stopped running, three missing rows must VOID, not pass.
#
#   A-4  NULL ISLAND. Zero rows anywhere with latitude = 0 AND longitude = 0, as the superuser.
#        A shop at (0,0) is nearer the origin than any real GB shop, so under a distance sort it
#        becomes the nearest kitchen to every customer on the platform.
#
#   A-5  THE REFERENCE TABLE IS POPULATED. Without this line an empty postcode_centroid is
#        indistinguishable from a broken geocoder, and it is the exact silent degradation above.
#
#   Both DATABASE ROLES are read and both are recorded, exactly as CA-1 does. As jtoye_app with
#   no tenant GUC the shops_public_read policy reduces to `published = true`, so the app role
#   cannot see unpublished rows at all — reporting that figure alone records 3 where the truth
#   is 5. Any statement about an unpublished shop MUST name the superuser or it is vacuous.
#
# EXIT CODES — uniform with the other ops gates
#   0 = the relation holds · 1 = it does not · 2 = VOID (cannot evaluate)
#
#   VOID on: missing docker · the postgres container not running · the superuser role name
#   unreadable from the container · any query returning empty · a zero denominator on A-2 or
#   A-3. "Found nothing" is never "clean".
#
# SHAPE RULES OBSERVED (each is a recorded failure in this repository)
#   - Row COUNTS are read, never exit codes. `docker exec` WITHOUT `-i` does not deliver a
#     heredoc to psql and still exits 0 having done nothing; every call below uses `-i -c`.
#   - `rc` is captured on the SAME statement as its command. `$?` after an intervening echo
#     reports the echo, which is 0 essentially always.
#   - No `cmd | grep -q`: under pipefail that INVERTS on match via SIGPIPE -> 141.
#   - No command line carries a literal password value. The superuser is referenced by name,
#     read from the container's own POSTGRES_USER, and psql authenticates via the local socket.
#
# WHY THIS IS NOT IN CI
#   It needs a running Postgres reachable through `docker exec` with the dev data seeded. A
#   GitHub-hosted runner has no such stack, so this could only ever exit 2 there, and a
#   permanently-VOID required job trains people to add `|| true`. Declared in
#   scripts/gates/gate-enforcement.conf with that reason.
#
# USAGE
#   bash scripts/check-live-shop-coordinates.sh
#   POSTGRES_CONTAINER=jtoye-postgres bash scripts/check-live-shop-coordinates.sh
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -uo pipefail

CONTAINER="${POSTGRES_CONTAINER:-jtoye-postgres}"

VOID=2
FAIL=1

void() {
    echo "VOID: $*" >&2
    echo "  (a result that cannot be evaluated is never a clean bill)" >&2
    exit "$VOID"
}

# ---- Preconditions ---------------------------------------------------------------------

command -v docker >/dev/null 2>&1 || void "docker is not on PATH — nothing to inspect"

STATE=$(docker inspect -f '{{.State.Status}}' "$CONTAINER" 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] || void "container '$CONTAINER' does not exist (docker inspect rc=$rc)"
[ "$STATE" = "running" ] || void "container '$CONTAINER' is '$STATE', not running — the stack is down"

SUP=$(docker exec "$CONTAINER" printenv POSTGRES_USER 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] || void "could not read POSTGRES_USER from '$CONTAINER' (rc=$rc)"
[ -n "$SUP" ] || void "POSTGRES_USER is empty in '$CONTAINER'"

DB=$(docker exec "$CONTAINER" printenv POSTGRES_DB 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] || void "could not read POSTGRES_DB from '$CONTAINER' (rc=$rc)"
[ -n "$DB" ] || void "POSTGRES_DB is empty in '$CONTAINER'"

# Every query goes through here. `-i` is load-bearing; `-tA` gives bare pipe-separated values.
q() {
    docker exec -i "$CONTAINER" psql -U "$SUP" -d "$DB" -tA -c "$1" 2>/dev/null
}

# The same, as the application role. PGOPTIONS sets the role at CONNECT rather than with a
# `SET ROLE` statement, because `-c "SET ROLE x; SELECT …"` prepends the literal command tag
# `SET` to the output — a second line that a naive read of "the first line" would report as
# the census. The role is echoed back in the value below so the reading names its own role.
q_app() {
    docker exec -i -e PGOPTIONS='-c role=jtoye_app' "$CONTAINER" \
        psql -U "$SUP" -d "$DB" -tA -c "$1" 2>/dev/null
}

# One value, or VOID. An empty result is never silently treated as zero.
q1() {
    local out rc
    out=$(q "$1"); rc=$?
    [ "$rc" -eq 0 ] || void "query failed (rc=$rc): $2"
    [ -n "$out" ] || void "query returned EMPTY output: $2"
    printf '%s' "$out"
}

# The postcode-extraction predicate, mirroring PostcodeGeocoder.TRAILING_POSTCODE: a permissive
# candidate at the END of the address. The table decides correctness, not the pattern.
POSTCODE_RE='[A-Za-z]{1,2}[0-9]{1,2}[A-Za-z]?[[:space:]]{0,4}[0-9][A-Za-z]{2}[[:space:]]{0,8}$'
# The same address normalised to a postcode_centroid key: trailing postcode, spaces stripped,
# uppercased — exactly what PostcodeGeocoder builds before its primary-key lookup.
POSTCODE_KEY="upper(replace(substring(address from '${POSTCODE_RE}'), ' ', ''))"

# The three curated demo storefronts (DemoDataSeeder). Named, because A-3 is about OUR data.
SEEDED_SLUGS="'mama-ades-kitchen','peckham-jollof-co','brixton-village-grill'"

echo "=============================================================================="
echo " check-live-shop-coordinates — #460 link 3, against the DELIVERED RUNTIME"
echo " container=$CONTAINER  db=$DB  superuser=$SUP"
echo "=============================================================================="

# ---- A-5: the reference table is populated ----------------------------------------------

CENTROIDS=$(q1 "SELECT count(*) FROM postcode_centroid;" "postcode_centroid row count")
echo
echo "A-5  postcode_centroid rows ......................... $CENTROIDS"
if [ "$CENTROIDS" -eq 0 ]; then
    echo
    echo "FAIL (A-5): postcode_centroid is EMPTY." >&2
    echo "  Every coordinate will be NULL and the geocoder is not at fault — the reference" >&2
    echo "  table never loaded. Check PostcodeCentroidImporter and" >&2
    echo "  jtoye.geo.postcode-import.enabled. Without this line an empty table is" >&2
    echo "  indistinguishable from a broken geocoder." >&2
    exit "$FAIL"
fi

# ---- The census, as CONTEXT (never as an assertion) --------------------------------------

CENSUS_SUP=$(q1 "SELECT count(*)||'|'||count(latitude)||'|'||count(*) FILTER (WHERE published) FROM shops;" \
    "superuser shop census")
CENSUS_APP=$(q_app "SELECT current_user||'|'||count(*)||'|'||count(latitude)||'|'||count(*) FILTER (WHERE published) FROM shops;"); rc=$?
[ "$rc" -eq 0 ] || void "jtoye_app shop census failed (rc=$rc)"
[ -n "$CENSUS_APP" ] || void "jtoye_app shop census returned EMPTY output"
case "$CENSUS_APP" in
    jtoye_app\|*) : ;;
    *) void "the app-role census was read as '${CENSUS_APP%%|*}', not jtoye_app — the role
  downgrade did not take, so this reading is the superuser's and proves nothing about RLS" ;;
esac

echo
echo "CONTEXT — the day's census, recorded, NOT asserted (pinning it reds on any new shop)"
echo "  as superuser $SUP (the TRUE state)   total|with_latitude|published = $CENSUS_SUP"
echo "  as ${CENSUS_APP%%|*}, NO tenant GUC        total|with_latitude|published = ${CENSUS_APP#*|}"
echo
echo "  The two roles disagree BY DESIGN and recording only the first would be a false green."
echo "  shops is ENABLE+FORCE RLS and shops_public_read reads"
echo "  ((published = true) OR (tenant_id = current_tenant_id())). With no tenant GUC that"
echo "  reduces to published = true, so the app role cannot see unpublished rows at all."
echo "  Any statement about an unpublished shop MUST name the superuser or it is vacuous."

echo
echo "  per-shop truth (superuser):"
PER_SHOP=$(q "SELECT '    '||rpad(slug,26)||' published='||published||'  lat='||coalesce(latitude::text,'NULL')||'  '||coalesce(address,'(none)') FROM shops ORDER BY slug;"); rc=$?
[ "$rc" -eq 0 ] || void "per-shop listing failed (rc=$rc)"
[ -n "$PER_SHOP" ] || void "per-shop listing returned EMPTY — the shops table is empty"
echo "$PER_SHOP"

# ---- A-1 + A-2: the relation, and its denominator ----------------------------------------

REL=$(q1 "
  SELECT
    count(*) FILTER (WHERE pc.postcode IS NOT NULL)
    || '|' ||
    count(*) FILTER (WHERE pc.postcode IS NOT NULL AND s.latitude IS NULL)
    || '|' ||
    count(*) FILTER (WHERE s.key IS NOT NULL)
    || '|' ||
    count(*) FILTER (WHERE s.key IS NOT NULL AND s.latitude IS NULL)
  FROM (
    SELECT latitude, ${POSTCODE_KEY} AS key
    FROM shops WHERE published
  ) s
  LEFT JOIN postcode_centroid pc ON pc.postcode = s.key;
" "published-shop coordinate relation")

DENOM_REAL=$(cut -d'|' -f1 <<< "$REL")
VIOLATING=$(cut -d'|' -f2 <<< "$REL")
DENOM_ANY=$(cut -d'|' -f3 <<< "$REL")
VIOLATING_ANY=$(cut -d'|' -f4 <<< "$REL")

echo
echo "A-1/A-2  the relation, as superuser $SUP"
echo "  published shops whose postcode IS IN postcode_centroid ....... $DENOM_REAL   (denominator, must be >= 1)"
echo "  ...of which latitude IS NULL ................................. $VIOLATING   (must be 0)"
echo "  published shops with ANY extractable postcode ................ $DENOM_ANY   (context)"
echo "  ...of which latitude IS NULL ................................. $VIOLATING_ANY   (context — see the header on why this is not the assertion)"

UNRESOLVABLE=$(q "
  SELECT '    '||rpad(slug,26)||' postcode='||coalesce(${POSTCODE_KEY},'(none)')
  FROM shops s
  WHERE published
    AND ${POSTCODE_KEY} IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM postcode_centroid pc WHERE pc.postcode = ${POSTCODE_KEY});
"); rc=$?
[ "$rc" -eq 0 ] || void "unresolvable-postcode listing failed (rc=$rc)"
if [ -n "$UNRESOLVABLE" ]; then
    echo
    echo "  REPORTED, not failed — published shops whose postcode is NOT in the reference table."
    echo "  They keep their storefront and are absent from distance results. Expected for"
    echo "  Northern Ireland (the dataset is GB-only); anything else is a typo worth chasing:"
    echo "$UNRESOLVABLE"
fi

# ---- A-3: the seeded shops, by name ------------------------------------------------------

SEEDED=$(q1 "SELECT count(*)||'|'||count(latitude) FROM shops WHERE slug IN (${SEEDED_SLUGS});" \
    "seeded curated shop coordinates")
SEEDED_FOUND=$(cut -d'|' -f1 <<< "$SEEDED")
SEEDED_WITH_LAT=$(cut -d'|' -f2 <<< "$SEEDED")

echo
echo "A-3  the three curated demo storefronts, read as superuser $SUP"
echo "  found ........................................................ $SEEDED_FOUND   (denominator, must be 3)"
echo "  ...with a coordinate ......................................... $SEEDED_WITH_LAT   (must equal found)"

# ---- A-4: Null Island --------------------------------------------------------------------

NULL_ISLAND=$(q1 "SELECT count(*) FROM shops WHERE latitude = 0 AND longitude = 0;" "Null Island count")
echo
echo "A-4  shops at Null Island (0,0), as superuser $SUP ... $NULL_ISLAND   (must be 0)"

# ---- Verdict ------------------------------------------------------------------------------

echo
echo "------------------------------------------------------------------------------"
failed=0

if [ "$DENOM_REAL" -lt 1 ]; then
    void "A-2: ZERO published shops carry a postcode present in postcode_centroid. The
  absence in A-1 would be vacuous — a broken extraction pattern or an emptied shops table
  reports exactly this. Refusing to report clean."
fi

if [ "$VIOLATING" -ne 0 ]; then
    echo "FAIL (A-1): $VIOLATING published shop(s) carry a REAL postcode and still have a NULL" >&2
    echo "  coordinate, out of a denominator of $DENOM_REAL. The write path, the seeder or the" >&2
    echo "  backfill did not reach them — or the running image predates the change." >&2
    failed=1
else
    echo "PASS (A-1/A-2): 0 of $DENOM_REAL published shops with a real postcode lack a coordinate."
fi

if [ "$SEEDED_FOUND" -ne 3 ]; then
    void "A-3: expected 3 curated demo storefronts, found $SEEDED_FOUND. The seeder did not run,
  or the slugs changed. A missing row must not be reported as a clean result."
fi

if [ "$SEEDED_WITH_LAT" -ne "$SEEDED_FOUND" ]; then
    echo "FAIL (A-3): $((SEEDED_FOUND - SEEDED_WITH_LAT)) of the $SEEDED_FOUND curated demo shops have" >&2
    echo "  no coordinate. These are our own data — every one of them must geocode. A shop whose" >&2
    echo "  seeded postcode does not exist is the defect this limb exists to catch." >&2
    failed=1
else
    echo "PASS (A-3): all $SEEDED_FOUND curated demo storefronts carry a coordinate."
fi

if [ "$NULL_ISLAND" -ne 0 ]; then
    echo "FAIL (A-4): $NULL_ISLAND shop row(s) sit at (0,0). Null Island is nearer the origin than" >&2
    echo "  any real GB shop, so such a row becomes the nearest kitchen to EVERY customer." >&2
    failed=1
else
    echo "PASS (A-4): no shop sits at Null Island."
fi

echo "PASS (A-5): postcode_centroid holds $CENTROIDS rows."
echo "------------------------------------------------------------------------------"

if [ "$failed" -ne 0 ]; then
    echo "RESULT: FAIL" >&2
    exit "$FAIL"
fi
echo "RESULT: PASS — the delivered runtime satisfies the relation."
exit 0
