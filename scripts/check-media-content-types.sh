#!/usr/bin/env bash
# check-media-content-types.sh — #488's urgent limb, asserted against the DELIVERED object store.
#
# WHY THIS EXISTS
#
#   #488 describes three properties of pre-#479 media objects: raw bytes, EXIF GPS, and a
#   SPOOFABLE STORED CONTENT-TYPE on a container that is anonymously readable by design. The
#   third is the urgent one, and it is urgent for a specific reason: the object store serves a
#   blob with the Content-Type it was STORED with (Azure Blob and the Azurite emulator alike).
#   A blob stored as `text/html` on a public origin is a stored-XSS primitive on the
#   storefront's own origin, whatever its bytes actually are.
#
#   PR #479 closed the WRITE path — every upload now goes through MediaNormalizer's magic-byte
#   sniff, so the client's declared Content-Type is never trusted. That is a forward-only fix.
#   It says nothing about objects already sitting in the container, and nothing prevents a
#   future code path, a manual upload, or a restored backup from putting one back.
#
#   Measured 2026-08-10 against the retired pre-Phase-36 store: 0 of 768 stored objects carried
#   a Content-Type outside the allowlist, so the honest deliverable was a gate that stops the
#   count drifting off zero silently (docs/security/MEDIA-BACKFILL-PLAN-2026-08-10.md). Phase 36
#   (plan 36-12) moved the store to Azure Blob / Azurite and re-targeted this gate at the new
#   store's PUBLIC container; the assertions A-1..A-3 are unchanged.
#
# WHAT IS ASSERTED, AND WHY IN THIS FORM
#
#   A-1  RELATION, not census. "N of M stored blobs carry a Content-Type outside the
#        allowlist", failing when N > 0.
#
#        Deliberately NOT "the container holds M blobs". That equality reds on the next
#        legitimate vendor upload, and a gate that fails on correct data teaches people to
#        ignore it — which costs more than the coverage it buys. M is REPORTED, never asserted.
#
#   A-2  DENOMINATOR >= 1. An absence-only predicate passes when the filter matches nothing.
#        A wrong container name, an empty container, a credential that can authenticate but not
#        list, or a listing that silently truncated would each report a clean zero over a
#        container this gate never actually read. M == 0 is a VOID, not a pass: "I could not
#        find any blobs" must never read as "every blob is fine".
#
#   A-3  THE ALLOWLIST IS READ FROM THE CODE THAT ENFORCES IT, at runtime, out of
#        MediaNormalizer.LEGACY_SYNC_INPUT_TYPES. Parsing is strictly stronger than naming the
#        values in a comment: a divergence is CAUGHT, not merely visible. At the time of writing
#        that set parses to:
#
#            image/jpeg  image/png  image/webp  image/gif
#
#        If that comment and the parsed set ever disagree, the parsed set wins and the
#        disagreement is printed. LEGACY_SYNC_INPUT_TYPES is used rather than
#        STRICT_INPUT_TYPES because it is the WIDER of MediaNormalizer's two sets — the widest
#        thing the normaliser will ever admit — so the gate cannot red on a blob the
#        application legitimately accepted, while still catching every non-image type
#        (text/html, image/svg+xml, application/octet-stream) that makes the origin dangerous.
#
# SCOPE: THE PUBLIC CONTAINER ONLY, BY DESIGN
#
#   Only the public container (jtoye-images, access level `blob`) is an origin a browser can
#   load from. The quarantine container is PRIVATE and holds the RAW bytes of uploads not yet
#   processed; a non-image upload is legitimately stored there as application/octet-stream
#   (MediaAssetService, before the worker fails it). Listing it here would red on correct
#   data, and a type nobody can fetch is not an XSS primitive.
#
# HOW THE LISTING IS TAKEN, AND WHY IT IS CREDENTIALED
#
#   The host `az` CLI, `az storage blob list`, against the published Blob endpoint. The public
#   container is level `blob`: anonymous GET of a named blob, NO anonymous LIST (#626), so an
#   enumeration has to be credentialed. The connection string defaults to the emulator's
#   well-known `UseDevelopmentStorage=true` (Azurite on 127.0.0.1:10000; the az CLI supplies
#   the emulator account's published key itself, so no key literal appears here — measured in
#   plan 36-12, assumption A8). It is handed to az through AZURE_STORAGE_CONNECTION_STRING in
#   the ENVIRONMENT, never on argv, so it never lands in `ps` output or shell history. An
#   exported AZURE_STORAGE_CONNECTION_STRING overrides the default (e.g. an explicit emulator
#   form built at runtime). This gate is a LOCAL / emulator gate; it is not pointed at a real
#   storage account.
#
# EXIT CODES — uniform with the other ops gates
#   0 = every stored blob's Content-Type is inside the allowlist
#   1 = at least one is not — the offending blob names are NAMED
#   2 = VOID (cannot evaluate)
#
#   VOID on: missing docker, az or jq · the Azurite container absent or not running ·
#   MediaNormalizer.java missing or its allowlist unparseable · the listing command failing
#   (a 403 there means the credential is wrong, not that the container is clean) · output that
#   does not parse as a JSON array · an EMPTY listing. "Found nothing" is never "clean".
#
# SHAPE RULES OBSERVED (each is a recorded failure in this repository)
#   - No `cmd | grep -q`: under pipefail that INVERTS on match via SIGPIPE promoted to 141, and
#     has already made a guard in this repo fail OPEN. Counts are captured as VALUES.
#   - `rc` is captured on the SAME statement as its command. `$?` read after an intervening echo
#     reports the echo, which is 0 essentially always.
#   - No `| head` on the listing. A truncating filter used to prove an absence MANUFACTURES that
#     absence.
#   - NO CREDENTIAL ON ANY COMMAND LINE: the connection string reaches az through its
#     environment only.
#
# WHERE IT RUNS
#   Wired into .github/workflows/e2e-nightly.yml after the stored-URL gate: the nightly brings
#   up a live Azurite on 127.0.0.1:10000 and the GitHub-hosted Ubuntu image ships the az CLI,
#   so the property now has standing coverage. On a runner with no stack every precondition
#   above exits 2, which is why it is not in the per-PR lanes.
#
# USAGE
#   bash scripts/check-media-content-types.sh
#   MEDIA_CONTAINER=some-container bash scripts/check-media-content-types.sh
#   AZURITE_CONTAINER=jtoye-azurite bash scripts/check-media-content-types.sh
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -uo pipefail

CONTAINER="${AZURITE_CONTAINER:-jtoye-azurite}"
BLOB_CONTAINER="${MEDIA_CONTAINER:-jtoye-images}"

VOID=2
FAIL=1

void() {
    echo "VOID: $*" >&2
    echo "  (a result that cannot be evaluated is never a clean bill)" >&2
    exit "$VOID"
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
NORMALIZER="$REPO_ROOT/core-java/src/main/java/uk/jtoye/core/media/MediaNormalizer.java"

# ---- Preconditions: tooling ---------------------------------------------------------------

command -v docker >/dev/null 2>&1 || void "docker is not on PATH — there is no object store to inspect"
command -v az >/dev/null 2>&1 || void "the az CLI is not on PATH — the Blob listing is taken with it"
command -v jq >/dev/null 2>&1 || void "jq is not on PATH. The listing is JSON and a hand-rolled parse that silently under-reads would fail OPEN — refusing to guess"

# ---- A-3: the allowlist, read out of the code that enforces it -----------------------------

[ -f "$NORMALIZER" ] || void "MediaNormalizer.java not found at $NORMALIZER — the allowlist has no source"

# Newlines folded first: the declaration wraps across two lines in the Java source.
ALLOW_RAW="$(tr '\n' ' ' < "$NORMALIZER" \
    | sed -n 's/.*LEGACY_SYNC_INPUT_TYPES[^=]*=[[:space:]]*Set\.of(\([^)]*\)).*/\1/p')"
[ -n "$ALLOW_RAW" ] || void "could not parse LEGACY_SYNC_INPUT_TYPES out of MediaNormalizer.java — the allowlist would have to be invented, and an invented allowlist proves nothing about the upload path"

ALLOW=()
while IFS= read -r t; do
    [ -n "$t" ] && ALLOW+=("$t")
done < <(printf '%s\n' "$ALLOW_RAW" | grep -oE '"[^"]+"' | tr -d '"' | sort -u)

[ "${#ALLOW[@]}" -gt 0 ] || void "the parsed allowlist is EMPTY — every blob would be reported as offending, which is a broken parse, not a finding"

# Sanity anchor. Every derivative the pipeline stores is WebP, so a parse that loses image/webp
# has read the wrong declaration; without this line such a parse would red the whole container
# and look like a catastrophic finding.
webp_present=0
for t in "${ALLOW[@]}"; do
    [ "$t" = "image/webp" ] && webp_present=1
done
[ "$webp_present" -eq 1 ] || void "the parsed allowlist does not contain image/webp (${ALLOW[*]}) — that is a broken parse of MediaNormalizer, not a property of the container"

# The values this header claims. Printed beside the parsed set so a divergence is visible even
# though the parsed set is what is actually enforced.
DOCUMENTED="image/gif image/jpeg image/png image/webp"

# ---- Preconditions: the running object store ----------------------------------------------

STATE=$(docker inspect -f '{{.State.Status}}' "$CONTAINER" 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] || void "container '$CONTAINER' does not exist (docker inspect rc=$rc) — the object store is not deployed here"
[ "$STATE" = "running" ] || void "container '$CONTAINER' is '$STATE', not running — the object store is down, so nothing can be asserted about its contents"

# ---- Credential: environment only ---------------------------------------------------------

CONN_SOURCE="exported AZURE_STORAGE_CONNECTION_STRING"
if [ -z "${AZURE_STORAGE_CONNECTION_STRING:-}" ]; then
    AZURE_STORAGE_CONNECTION_STRING="UseDevelopmentStorage=true"
    CONN_SOURCE="default UseDevelopmentStorage=true (Azurite on 127.0.0.1:10000)"
fi
export AZURE_STORAGE_CONNECTION_STRING
# Warnings (e.g. the CLI's own upgrade notices) must not be mistaken for listing output.
export AZURE_CORE_ONLY_SHOW_ERRORS=true
export AZURE_CORE_COLLECT_TELEMETRY=false

echo "=============================================================================="
echo " check-media-content-types — #488 urgent limb, against the DELIVERED object store"
echo " azurite=$CONTAINER  container=$BLOB_CONTAINER  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo " credential: $CONN_SOURCE (via the environment, never argv)"
echo "=============================================================================="
echo
echo "A-3  allowlist, parsed from MediaNormalizer.LEGACY_SYNC_INPUT_TYPES"
echo "       parsed     : ${ALLOW[*]}"
echo "       documented : $DOCUMENTED"
if [ "$(printf '%s\n' "${ALLOW[@]}" | sort | tr '\n' ' ')" != "$(printf '%s\n' $DOCUMENTED | sort | tr '\n' ' ')" ]; then
    echo "       NOTE: the parsed set and this script's header comment DISAGREE."
    echo "             The parsed set is what is enforced below; update the header."
fi

# ---- The listing --------------------------------------------------------------------------

ERRF="$(mktemp)"
trap 'rm -f "$ERRF"' EXIT
LISTING=$(az storage blob list -c "$BLOB_CONTAINER" --num-results '*' \
    --query '[].{name: name, ct: properties.contentSettings.contentType}' -o json 2>"$ERRF"); rc=$?
if [ "$rc" -ne 0 ]; then
    echo "  az stderr:" >&2
    sed 's/^/    /' "$ERRF" >&2
    void "listing '$BLOB_CONTAINER' failed (az rc=$rc). A container that cannot be listed is not a container with no offending blobs (a 403 means the credential is wrong)"
fi
[ -n "$LISTING" ] || void "the listing of '$BLOB_CONTAINER' is EMPTY output. A-2: a zero denominator makes the absence below vacuous — refusing to report clean"

KIND=$(jq -r 'type' <<< "$LISTING" 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] && [ "$KIND" = "array" ] || void "the listing did not parse as a JSON array (type='$KIND') — refusing to count offenders out of output this script does not understand"

M=$(jq -r 'length' <<< "$LISTING"); rc=$?
[ "$rc" -eq 0 ] || void "could not count the listing (jq rc=$rc)"
[ "$M" -gt 0 ] || void "A-2: the listing of '$BLOB_CONTAINER' holds ZERO blobs. A wrong container and an empty one report exactly this — refusing to report clean"

# ---- CONTEXT: the census, recorded, never asserted -----------------------------------------

echo
echo "CONTEXT — stored Content-Type census (recorded, NOT asserted; pinning it reds on any upload)"
jq -r '.[] | (.ct // "(none)")' <<< "$LISTING" | sort | uniq -c | sort -rn | sed 's/^/       /'

# ---- A-1 + A-2: the relation, and its denominator ------------------------------------------

ALLOW_JSON=$(printf '%s\n' "${ALLOW[@]}" | jq -R . | jq -s .)

OFFENDERS=$(jq -r --argjson allow "$ALLOW_JSON" '
    .[]
    | (.ct // "(none)") as $ct
    | select($allow | index($ct) | not)
    | "       " + $ct + "   " + .name
' <<< "$LISTING" 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] || void "the offender filter failed (jq rc=$rc) — an unevaluated filter is not an empty result"

N=0
if [ -n "$OFFENDERS" ]; then
    N=$(printf '%s\n' "$OFFENDERS" | wc -l)
fi

echo
echo "A-1/A-2  the relation"
echo "       blobs enumerated (M, denominator, must be >= 1) ......... $M"
echo "       ...whose Content-Type is OUTSIDE the allowlist (N) ...... $N   (must be 0)"

echo
echo "------------------------------------------------------------------------------"
if [ "$N" -ne 0 ]; then
    echo "FAIL (A-1): $N of $M stored blob(s) carry a Content-Type outside the allowlist" >&2
    echo "  MediaNormalizer enforces on upload (${ALLOW[*]})." >&2
    echo "  The store serves a blob with the Content-Type it was STORED with, and this container" >&2
    echo "  is a public origin, so a non-image type here is a stored-XSS primitive on the" >&2
    echo "  storefront's own origin regardless of the bytes. The offending blobs:" >&2
    printf '%s\n' "$OFFENDERS" >&2
    echo "  Re-pipeline or delete each one; do not simply rewrite the Content-Type header," >&2
    echo "  which would leave unvalidated bytes on the origin under an image label." >&2
    exit "$FAIL"
fi

echo "PASS: 0 of $M stored blobs carry a Content-Type outside the allowlist."
echo "------------------------------------------------------------------------------"
exit 0
