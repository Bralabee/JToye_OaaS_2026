#!/usr/bin/env bash
# check-media-content-types.sh — #488's urgent limb, asserted against the DELIVERED object store.
#
# WHY THIS EXISTS
#
#   #488 describes three properties of pre-#479 media objects: raw bytes, EXIF GPS, and a
#   SPOOFABLE STORED CONTENT-TYPE on a bucket that is anonymously readable by design. The third
#   is the urgent one, and it is urgent for a specific reason: MinIO serves an object with the
#   Content-Type it was stored with. An object stored as `text/html` on a public origin is a
#   stored-XSS primitive on the storefront's own origin, whatever its bytes actually are.
#
#   PR #479 closed the WRITE path — every upload now goes through MediaNormalizer's magic-byte
#   sniff, so the client's declared Content-Type is never trusted. That is a forward-only fix.
#   It says nothing about objects already sitting in the bucket, and nothing prevents a future
#   code path, a manual `mc cp`, or a restored backup from putting one back.
#
#   Measured 2026-08-10 against the live bucket: 0 of 768 stored objects carry a Content-Type
#   outside the allowlist. So the remediation #488 asks for has an EMPTY input set, and the
#   honest deliverable is not a re-pipeline run over zero objects — it is a gate that stops the
#   count drifting off zero silently. That is what this is. The measurement and the deferred
#   half are recorded in docs/security/MEDIA-BACKFILL-PLAN-2026-08-10.md.
#
# WHAT IS ASSERTED, AND WHY IN THIS FORM
#
#   A-1  RELATION, not census. "N of M stored objects carry a Content-Type outside the
#        allowlist", failing when N > 0.
#
#        Deliberately NOT "the bucket holds 768 objects". That equality reds on the next
#        legitimate vendor upload, and a gate that fails on correct data teaches people to
#        ignore it — which costs more than the coverage it buys. M is REPORTED, never asserted.
#
#   A-2  DENOMINATOR >= 1. An absence-only predicate passes when the filter matches nothing.
#        A wrong bucket name, an empty bucket, a credential that can authenticate but not list,
#        or a listing that silently truncated would each report a clean zero over a bucket this
#        gate never actually read. M == 0 is a VOID, not a pass: "I could not find any objects"
#        must never read as "every object is fine".
#
#   A-3  THE ALLOWLIST IS READ FROM THE CODE THAT ENFORCES IT, at runtime, out of
#        MediaNormalizer.LEGACY_SYNC_INPUT_TYPES. The plan asked for the values to be named in
#        a comment "so a future divergence is visible"; parsing them is strictly stronger, so
#        the substitution is RECORDED HERE rather than made silently — a divergence is then
#        CAUGHT, not merely visible. At the time of writing that set parses to:
#
#            image/jpeg  image/png  image/webp  image/gif
#
#        If that comment and the parsed set ever disagree, the parsed set wins and the
#        disagreement is printed. LEGACY_SYNC_INPUT_TYPES is used rather than
#        STRICT_INPUT_TYPES because it is the WIDER of MediaNormalizer's two sets — the widest
#        thing the normaliser will ever admit — so the gate cannot red on an object the
#        application legitimately accepted, while still catching every non-image type
#        (text/html, image/svg+xml, application/octet-stream) that makes the bucket dangerous.
#        A gif is transcoded to a static WebP derivative rather than stored raw, so a stored
#        image/gif is not expected; admitting it costs nothing and removes a false-red.
#
# WHY THE ENUMERATION IS CREDENTIALED, AND WHY THAT IS LOAD-BEARING SEQUENCING
#
#   The bucket currently grants anonymous s3:ListBucket as well as s3:GetObject, so this listing
#   could be taken with no credential at all. It deliberately is not. Plan 28-09 removes the
#   anonymous LIST grant; an anonymously-enumerating gate would start VOIDing the moment that
#   fix landed, and a VOID arriving with an unrelated change reads as a broken gate rather than
#   as a fixed bucket. The credentialed path survives that fix unchanged.
#
# HOW THE CLIENT IS REACHED — A RECORDED SUBSTITUTION
#
#   The plan specified a one-shot `minio/mc` container attached to the compose network. This
#   uses the `mc` that already ships INSIDE the MinIO image (/usr/bin/mc, verified on
#   RELEASE.2025-08-13) via `docker exec`, which is the same credentialed client reached a
#   shorter way. The reason is a recorded trap in this repository: a compose network's name is
#   derived from the PROJECT DIRECTORY NAME (here `jtoye_oaas_2026_jtoye-network`), so a gate
#   naming it breaks when the checkout is renamed and when it is run from a git worktree — the
#   same mechanism that makes check-runtime-freshness.sh unrunnable from a worktree. `docker
#   exec` has no network-name dependency and no image-pull dependency. Both forms are
#   credentialed; that property, not the transport, is what 28-09 depends on.
#
# EXIT CODES — uniform with the other ops gates
#   0 = every stored object's Content-Type is inside the allowlist
#   1 = at least one is not — the offending keys are NAMED
#   2 = VOID (cannot evaluate)
#
#   VOID on: missing docker · missing jq · the MinIO container absent or not running · no `mc`
#   inside it · credentials unresolvable · MediaNormalizer.java missing or its allowlist
#   unparseable · the listing command failing · ANY error line in the listing · an EMPTY
#   listing. "Found nothing" is never "clean".
#
# SHAPE RULES OBSERVED (each is a recorded failure in this repository)
#   - No `cmd | grep -q`: under pipefail that INVERTS on match via SIGPIPE promoted to 141, and
#     has already made a guard in this repo fail OPEN. Counts are captured as VALUES.
#   - `grep -c` exits 1 on a ZERO count, i.e. on the desired state of an absence check, so every
#     count is taken with `|| true` and compared as a number.
#   - `rc` is captured on the SAME statement as its command. `$?` read after an intervening echo
#     reports the echo, which is 0 essentially always.
#   - No `| head` on the listing. A truncating filter used to prove an absence MANUFACTURES that
#     absence; that is exactly how a "no nvidia runtime" conclusion was reached on a host that
#     had one.
#   - NO CREDENTIAL VALUE APPEARS ON ANY COMMAND LINE. The alias is handed to the container as
#     `docker exec -e MC_HOST_mediagate` — the NAME only — so docker copies the value from this
#     script's own environment. Nothing lands in `ps` output or in shell history.
#
# WHY THIS IS NOT IN CI
#   It inspects the contents of a running MinIO bucket through `docker exec`. A GitHub-hosted
#   runner has no MinIO container and no bucket, so every precondition above would exit 2 there
#   on every run, and a permanently-VOID required job trains people to add `|| true`. Its whole
#   purpose is to prove a property of the DELIVERED object store, which is by definition not a
#   property of a source checkout. Declared in scripts/gates/gate-enforcement.conf with that
#   reason.
#
# USAGE
#   bash scripts/check-media-content-types.sh
#   MEDIA_BUCKET=some-bucket bash scripts/check-media-content-types.sh
#   MINIO_CONTAINER=jtoye-minio bash scripts/check-media-content-types.sh
#
#   MINIO_ROOT_USER / MINIO_ROOT_PASSWORD are taken from the environment when exported, and
#   otherwise read from the running container the same way check-live-shop-coordinates.sh reads
#   POSTGRES_USER. No value is hardcoded here or in the conf.
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -uo pipefail

CONTAINER="${MINIO_CONTAINER:-jtoye-minio}"
BUCKET="${MEDIA_BUCKET:-jtoye-images}"

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

[ "${#ALLOW[@]}" -gt 0 ] || void "the parsed allowlist is EMPTY — every object would be reported as offending, which is a broken parse, not a finding"

# Sanity anchor. Every derivative the pipeline stores is WebP, so a parse that loses image/webp
# has read the wrong declaration; without this line such a parse would red the whole bucket and
# look like a catastrophic finding.
webp_present=0
for t in "${ALLOW[@]}"; do
    [ "$t" = "image/webp" ] && webp_present=1
done
[ "$webp_present" -eq 1 ] || void "the parsed allowlist does not contain image/webp (${ALLOW[*]}) — that is a broken parse of MediaNormalizer, not a property of the bucket"

# The values this header claims. Printed beside the parsed set so a divergence is visible even
# though the parsed set is what is actually enforced.
DOCUMENTED="image/gif image/jpeg image/png image/webp"

# ---- Preconditions: the running object store ----------------------------------------------

STATE=$(docker inspect -f '{{.State.Status}}' "$CONTAINER" 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] || void "container '$CONTAINER' does not exist (docker inspect rc=$rc) — the object store is not deployed here"
[ "$STATE" = "running" ] || void "container '$CONTAINER' is '$STATE', not running — the object store is down, so nothing can be asserted about its contents"

docker exec "$CONTAINER" sh -c 'command -v mc' >/dev/null 2>&1; rc=$?
[ "$rc" -eq 0 ] || void "no 'mc' client inside '$CONTAINER' (rc=$rc) — the MinIO image no longer ships one, so this gate needs a one-shot minio/mc container instead"

# ---- Credentials: environment first, then the container's own env --------------------------

MU="${MINIO_ROOT_USER:-}"
MP="${MINIO_ROOT_PASSWORD:-}"
if [ -z "$MU" ]; then MU=$(docker exec "$CONTAINER" printenv MINIO_ROOT_USER 2>/dev/null); fi
if [ -z "$MP" ]; then MP=$(docker exec "$CONTAINER" printenv MINIO_ROOT_PASSWORD 2>/dev/null); fi
[ -n "$MU" ] || void "MINIO_ROOT_USER is neither exported nor readable from '$CONTAINER' — refusing to fall back to an anonymous listing (see the header: 28-09 removes that grant)"
[ -n "$MP" ] || void "MINIO_ROOT_PASSWORD is neither exported nor readable from '$CONTAINER' — refusing to fall back to an anonymous listing"

# Name-only passthrough below; the value never reaches a command line.
export MC_HOST_mediagate="http://${MU}:${MP}@localhost:9000"

echo "=============================================================================="
echo " check-media-content-types — #488 urgent limb, against the DELIVERED object store"
echo " container=$CONTAINER  bucket=$BUCKET  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
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

LISTING=$(docker exec -e MC_HOST_mediagate "$CONTAINER" \
    mc stat --recursive --json "mediagate/${BUCKET}" 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] || void "listing '$BUCKET' failed (mc rc=$rc). A bucket that cannot be listed is not a bucket with no offending objects"
[ -n "$LISTING" ] || void "the listing of '$BUCKET' is EMPTY. A-2: a zero denominator makes the absence below vacuous — refusing to report clean"

# Every line must be a parseable success record. mc reports a missing bucket as JSON on stdout
# with rc=1, but a PARTIAL failure mid-listing is the case this catches: half a bucket read as
# a whole one would under-report offenders and pass.
BAD_LINES=$(printf '%s\n' "$LISTING" | jq -r 'select(.status != "success") | .status' 2>/dev/null | wc -l)
UNPARSEABLE=$(printf '%s\n' "$LISTING" | jq -e . >/dev/null 2>&1; echo $?)
[ "$BAD_LINES" -eq 0 ] || void "the listing contains $BAD_LINES non-success record(s) — it is an error report, not an inventory"
[ "$UNPARSEABLE" -eq 0 ] || void "the listing did not parse as JSON — refusing to count offenders out of output this script does not understand"

M=$(printf '%s\n' "$LISTING" | jq -r 'select(.status == "success") | .name' 2>/dev/null | wc -l)
[ "$M" -gt 0 ] || void "A-2: the listing parsed to ZERO objects. A broken filter and an empty bucket report exactly this — refusing to report clean"

# ---- CONTEXT: the census, recorded, never asserted -----------------------------------------

echo
echo "CONTEXT — stored Content-Type census (recorded, NOT asserted; pinning it reds on any upload)"
printf '%s\n' "$LISTING" \
    | jq -r 'select(.status == "success") | .metadata["Content-Type"] // "(none)"' \
    | sort | uniq -c | sort -rn | sed 's/^/       /'

# ---- A-1 + A-2: the relation, and its denominator ------------------------------------------

ALLOW_JSON=$(printf '%s\n' "${ALLOW[@]}" | jq -R . | jq -s .)

OFFENDERS=$(printf '%s\n' "$LISTING" | jq -r --argjson allow "$ALLOW_JSON" '
    select(.status == "success")
    | (.metadata["Content-Type"] // "(none)") as $ct
    | select($allow | index($ct) | not)
    | "       " + $ct + "   " + .name
' 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] || void "the offender filter failed (jq rc=$rc) — an unevaluated filter is not an empty result"

N=0
if [ -n "$OFFENDERS" ]; then
    N=$(printf '%s\n' "$OFFENDERS" | wc -l)
fi

echo
echo "A-1/A-2  the relation"
echo "       objects enumerated (M, denominator, must be >= 1) ....... $M"
echo "       ...whose Content-Type is OUTSIDE the allowlist (N) ...... $N   (must be 0)"

echo
echo "------------------------------------------------------------------------------"
if [ "$N" -ne 0 ]; then
    echo "FAIL (A-1): $N of $M stored object(s) carry a Content-Type outside the allowlist" >&2
    echo "  MediaNormalizer enforces on upload (${ALLOW[*]})." >&2
    echo "  MinIO serves an object with the Content-Type it was STORED with, and this bucket is" >&2
    echo "  a public origin, so a non-image type here is a stored-XSS primitive on the" >&2
    echo "  storefront's own origin regardless of the bytes. The offending keys:" >&2
    printf '%s\n' "$OFFENDERS" >&2
    echo "  Re-pipeline or delete each one; do not simply rewrite the Content-Type header," >&2
    echo "  which would leave unvalidated bytes on the origin under an image label." >&2
    exit "$FAIL"
fi

echo "PASS: 0 of $M stored objects carry a Content-Type outside the allowlist."
echo "------------------------------------------------------------------------------"
exit 0
