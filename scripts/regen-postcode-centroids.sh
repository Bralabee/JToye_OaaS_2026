#!/usr/bin/env bash
#
# Regenerate core-java/src/main/resources/geo/postcode-centroids.csv.gz from OS Code-Point Open.
#
# THIS SCRIPT IS THE ONLY SANCTIONED WAY THE ARTEFACT IS PRODUCED. A hand-built artefact has no
# provenance: nothing ties it to a published md5, nothing proves the Null-Island rows were filtered,
# and nothing records which release it came from.
#
# Licence: OS Code-Point Open is published under the Open Government Licence v3 — commercial use
# permitted, no share-alike. Confirmed against primary sources 2026-08-08; see
# .planning/phases/33-the-consumer-product/33-CONTROL-ARMS.md section "A1 — licence confirmation".
# The licence obliges attribution, which is rendered by frontend/components/public/public-footer.tsx
# and gated by scripts/check-geo-attribution.sh.
#
# Cadence: ANNUAL. Postcode centroids are stable; a quarterly refresh buys nothing and costs ~15 MB
# of git history each time.
#
# Usage:
#   scripts/regen-postcode-centroids.sh
#
# Environment:
#   CODEPO_ZIP_OVERRIDE  Use a local archive instead of downloading. The md5 verification still runs
#                        against the API-published md5 — this supplies the input, it does not skip
#                        the check. Exists so the fail-closed arm can be exercised without a 14 MB
#                        re-download.
#
# Exit codes:  0 success   1 verification failed / bad data   2 VOID (missing tooling, unreachable
# API, unparseable response) — "could not check" is never "clean".

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

GEO_DIR="$REPO_ROOT/core-java/src/main/resources/geo"
ARTEFACT="$GEO_DIR/postcode-centroids.csv.gz"
SOURCE_MD="$GEO_DIR/SOURCE.md"
TRANSFORM="$SCRIPT_DIR/osgb36-to-wgs84.awk"

# Overridable so the VOID branch is reachable in a test, and so a mirror can be pointed at without
# editing the script. Defaults to the OS Data Hub.
PRODUCT_API="${CODEPO_PRODUCT_API:-https://api.os.uk/downloads/v1/products/CodePointOpen}"
ARCHIVE_NAME="codepo_gb.zip"
MIN_ROWS=1700000
MAX_TRANSFORM_ERROR_M=10

die()  { echo "FAIL: $*" >&2; exit 1; }
void() { echo "VOID: $*" >&2; exit 2; }
log()  { echo "  $*"; }

# ---- dependencies: missing tooling is VOID, never a silent pass -------------------------------
for tool in curl jq unzip awk gzip md5sum sort; do
    command -v "$tool" >/dev/null 2>&1 || void "required tool '$tool' is not installed"
done
[ -f "$TRANSFORM" ] || void "transform not found at $TRANSFORM"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Regenerating postcode centroids from OS Code-Point Open"

# ---- 1. resolve the current release and its published md5 -------------------------------------
meta="$(curl -sS --fail --max-time 60 "$PRODUCT_API")" || void "product API unreachable: $PRODUCT_API"
VERSION="$(jq -r '.version // empty' <<< "$meta")"
[ -n "$VERSION" ] || void "product API response carried no .version"

downloads="$(curl -sS --fail --max-time 60 "$PRODUCT_API/downloads")" \
    || void "downloads API unreachable"
EXPECTED_MD5="$(jq -r --arg f "$ARCHIVE_NAME" '.[] | select(.fileName==$f) | .md5 // empty' <<< "$downloads")"
EXPECTED_SIZE="$(jq -r --arg f "$ARCHIVE_NAME" '.[] | select(.fileName==$f) | .size // empty' <<< "$downloads")"
DOWNLOAD_URL="$(jq -r --arg f "$ARCHIVE_NAME" '.[] | select(.fileName==$f) | .url // empty' <<< "$downloads")"
[ -n "$EXPECTED_MD5" ]  || void "downloads API published no md5 for $ARCHIVE_NAME"
[ -n "$DOWNLOAD_URL" ]  || void "downloads API published no url for $ARCHIVE_NAME"

log "release           : $VERSION"
log "published md5     : $EXPECTED_MD5"
log "published size    : ${EXPECTED_SIZE:-unknown} bytes"

# ---- 2. fetch ---------------------------------------------------------------------------------
ZIP="$WORK/$ARCHIVE_NAME"
if [ -n "${CODEPO_ZIP_OVERRIDE:-}" ]; then
    [ -f "$CODEPO_ZIP_OVERRIDE" ] || void "CODEPO_ZIP_OVERRIDE set but not a file: $CODEPO_ZIP_OVERRIDE"
    log "source            : CODEPO_ZIP_OVERRIDE=$CODEPO_ZIP_OVERRIDE (md5 check still applies)"
    cp -- "$CODEPO_ZIP_OVERRIDE" "$ZIP"
else
    log "downloading       : $DOWNLOAD_URL"
    curl -sS --fail -L --max-time 600 -o "$ZIP" "$DOWNLOAD_URL" || void "download failed"
fi

# ---- 3. VERIFY, AND FAIL CLOSED ---------------------------------------------------------------
# Nothing below this line may run on unverified bytes, and no artefact is written on mismatch.
ACTUAL_MD5="$(md5sum "$ZIP" | cut -d' ' -f1)"
ACTUAL_SIZE="$(stat -c%s "$ZIP")"
log "downloaded md5    : $ACTUAL_MD5"
if [ "$ACTUAL_MD5" != "$EXPECTED_MD5" ]; then
    die "md5 MISMATCH — expected $EXPECTED_MD5, got $ACTUAL_MD5. No artefact written."
fi
if [ -n "$EXPECTED_SIZE" ] && [ "$ACTUAL_SIZE" != "$EXPECTED_SIZE" ]; then
    die "size MISMATCH — expected $EXPECTED_SIZE, got $ACTUAL_SIZE. No artefact written."
fi
log "md5 verified against the OS-published value"

# ---- 4. unpack --------------------------------------------------------------------------------
unzip -q "$ZIP" -d "$WORK/x" || die "archive did not unpack"
CSV_DIR="$WORK/x/Data/CSV"
[ -d "$CSV_DIR" ] || void "expected $CSV_DIR inside the archive; layout changed"

UPSTREAM_ROWS="$(cat "$CSV_DIR"/*.csv | wc -l)"
[ "$UPSTREAM_ROWS" -gt 0 ] || void "archive contained zero data rows"
log "upstream rows     : $UPSTREAM_ROWS"

# Attribution year, read out of the shipped licence rather than assumed from the release string.
LICENCE_TXT="$WORK/x/Doc/licence.txt"
ATTRIBUTION_YEAR=""
if [ -f "$LICENCE_TXT" ]; then
    ATTRIBUTION_YEAR="$(LC_ALL=C sed -n 's/.*database right \([0-9][0-9][0-9][0-9]\).*/\1/p' "$LICENCE_TXT" | head -1)"
fi
[ -n "$ATTRIBUTION_YEAR" ] || void "could not read the attribution year out of Doc/licence.txt"
log "attribution year  : $ATTRIBUTION_YEAR"

# ---- 5. transform -----------------------------------------------------------------------------
CSV="$WORK/postcode-centroids.csv"
cat "$CSV_DIR"/*.csv | awk -f "$TRANSFORM" | LC_ALL=C sort > "$CSV" || die "transform failed"

ROWS="$(wc -l < "$CSV")"
FILTERED=$(( UPSTREAM_ROWS - ROWS ))
log "rows after filter : $ROWS  (dropped $FILTERED)"
[ "$ROWS" -gt "$MIN_ROWS" ] || die "only $ROWS rows, expected more than $MIN_ROWS"

# No header row, deliberately. `awk '($2+0)==0 && ($3+0)==0'` treats a header as Null Island —
# ("lat"+0)==0 is TRUE — so a header makes a correct dataset fail its own check.
FIRST_FIELD="$(awk -F, 'NR==1{print $1}' "$CSV")"
grep -qE '^[A-Z]{1,2}[0-9][A-Z0-9]?[0-9][A-Z]{2}$' <<< "$FIRST_FIELD" \
    || die "first line is not a data row (header present?): '$FIRST_FIELD'"

NULL_ISLAND="$(awk -F, '($2+0)==0 && ($3+0)==0' "$CSV" | wc -l)"
grep -qx 0 <<< "$NULL_ISLAND" || die "$NULL_ISLAND Null-Island (0,0) rows survived the filter"
log "Null-Island rows  : 0"

# ---- 6. validate the transform against an INDEPENDENT reference -------------------------------
# The transform is only trustworthy if something outside it agrees. Sampled across the full extent
# of the National Grid — Aberdeen to the Isles of Scilly — because the Helmert fit is weakest at
# the edges, so a central-England-only sample would flatter it.
SAMPLE="AB101AB EH11AD NE14ST M11AE B11BB SW1A1AA SE155BS CF101EP EX43PQ TR210LP"
REF="$WORK/ref.csv"; MINE="$WORK/mine.csv"
: > "$REF"; : > "$MINE"
for pc in $SAMPLE; do
    line="$(LC_ALL=C grep -m1 "^$pc," "$CSV" || true)"
    [ -n "$line" ] || continue
    j="$(curl -sS --fail --max-time 30 "https://api.postcodes.io/postcodes/$pc" 2>/dev/null || true)"
    lat="$(jq -r '.result.latitude // empty' <<< "${j:-}" 2>/dev/null || true)"
    lon="$(jq -r '.result.longitude // empty' <<< "${j:-}" 2>/dev/null || true)"
    [ -n "$lat" ] && [ -n "$lon" ] || continue
    echo "$line" >> "$MINE"
    echo "$pc,$lat,$lon" >> "$REF"
done
PAIRS="$(wc -l < "$MINE")"
if [ "$PAIRS" -lt 5 ]; then
    void "only $PAIRS reference points resolved — cannot validate the transform. An unvalidated transform is never 'clean'."
fi
ACCURACY="$(join -t, "$MINE" "$REF" | awk -F, -v lim="$MAX_TRANSFORM_ERROR_M" '
  BEGIN{ PI=3.14159265358979; R=6371008.8; max=0 }
  { la1=$2*PI/180; lo1=$3*PI/180; la2=$4*PI/180; lo2=$5*PI/180
    h=sin((la2-la1)/2)^2 + cos(la1)*cos(la2)*sin((lo2-lo1)/2)^2
    d=2*R*atan2(sqrt(h),sqrt(1-h)); if(d>max)max=d; s+=d; n++ }
  END{ printf "%d %.2f %.2f", n, s/n, max; if (max > lim) exit 1 }')" \
  || die "transform accuracy exceeded ${MAX_TRANSFORM_ERROR_M} m against the independent reference — the transform is wrong, not the reference"
set -- $ACCURACY
ACC_N="$1"; ACC_MEAN="$2"; ACC_MAX="$3"
log "accuracy          : n=$ACC_N mean=${ACC_MEAN} m max=${ACC_MAX} m (gate ${MAX_TRANSFORM_ERROR_M} m)"

# ---- 7. write the artefact ATOMICALLY ---------------------------------------------------------
mkdir -p "$GEO_DIR"
# -n is load-bearing, not cosmetic: without it gzip stores the temp file's mtime in the header, so
# two runs over IDENTICAL data produce different bytes. That would add ~15 MB to git history on every
# regeneration even when nothing changed — the exact cost Q-1 weighed and bounded to an annual
# refresh. Measured: without -n the same input gave md5 d51eb59d… then 2b702661…; with -n it is
# byte-identical across runs.
gzip -9 -n -c "$CSV" > "$WORK/out.gz" || die "gzip failed"
gzip -t "$WORK/out.gz" || die "gzip artefact did not verify"
mv -f "$WORK/out.gz" "$ARTEFACT"
ARTEFACT_BYTES="$(stat -c%s "$ARTEFACT")"
log "artefact          : $ARTEFACT ($ARTEFACT_BYTES bytes)"

# ---- 8. rewrite SOURCE.md ---------------------------------------------------------------------
TODAY="$(date -u +%Y-%m-%d)"
cat > "$SOURCE_MD" <<EOF
# Postcode centroid dataset — provenance

<!-- GENERATED by scripts/regen-postcode-centroids.sh. Do not hand-edit: a hand-edited provenance
     record is indistinguishable from a fabricated one. Re-run the script instead. -->

| | |
|---|---|
| **Dataset** | OS Code-Point Open (GB) |
| **Release** | \`$VERSION\` |
| **Source archive** | \`$ARCHIVE_NAME\`, $ACTUAL_SIZE bytes |
| **md5** | \`$ACTUAL_MD5\` — verified against the value published by \`$PRODUCT_API/downloads\` |
| **Upstream rows** | $UPSTREAM_ROWS |
| **Rows after filter** | **$ROWS** (dropped $FILTERED) |
| **Committed artefact** | \`postcode-centroids.csv.gz\`, $ARTEFACT_BYTES bytes |
| **Attribution year** | **$ATTRIBUTION_YEAR** |
| **Refresh cadence** | Annual |
| **Regenerated** | $TODAY |

## Attribution — required by the licence

OS Code-Point Open is published under the **Open Government Licence v3**: commercial use is
permitted, including inside a proprietary product, and there is **no share-alike clause**. The one
obligation is attribution. Licence identity confirmed against primary sources 2026-08-08 and
recorded in \`.planning/phases/33-the-consumer-product/33-CONTROL-ARMS.md\`, section
"A1 — licence confirmation".

These three lines, read verbatim out of the archive's own \`Doc/licence.txt\`, must render where a
user can reach them. They do, in \`frontend/components/public/public-footer.tsx\`, and
\`scripts/check-geo-attribution.sh\` fails CI if any line goes missing or the year drifts from this
file:

    Contains Ordnance Survey data © Crown copyright and database right $ATTRIBUTION_YEAR
    Contains Royal Mail data © Royal Mail copyright and database right $ATTRIBUTION_YEAR
    Contains National Statistics data © Crown copyright and database right $ATTRIBUTION_YEAR

## Limitations — state these, do not let them be found later as bugs

**Great Britain only.** Code-Point Open does not cover Northern Ireland. A Northern Ireland vendor
**will not geocode**, will keep their storefront, and will be **absent from distance-ranked
results**. This is a licence-containment choice, not an oversight: ONSPD ships WGS84 already
computed and would have been more convenient, but its Northern Ireland data carries an
internal-business-use-only EUL requiring a separate commercial licence from Land and Property
Services — exactly the sixth commercial decision D-1 exists to avoid. The remedy is an LPS licence,
which is a business decision.

**Postcode-centroid accuracy, ~100 m — not door-level.** A postcode unit resolves to the centroid of
its delivery points, so two shops on the same street resolve to the same point. Acceptable for
"shops near me" ranking; **not** acceptable for turn-by-turn directions or delivery-fee banding at
street granularity.

**Null Island is filtered, and the filter reads two columns.** $FILTERED rows in this release carry
\`positional_quality_indicator = 90\` with eastings/northings \`0,0\`. The sentinel is in a
*different column* from the coordinates, so a single-column filter misses it — and a surviving row
becomes the nearest shop to every customer on the platform. The transform keeps only
\`PQ != 90 AND easting != 0\`, and this script fails if any \`(0,0)\` row survives.

**No header row, deliberately.** The Null-Island assertion is
\`awk -F, '(\$2+0)==0 && (\$3+0)==0'\`, and \`("lat"+0)==0\` is **true** — a header would make a
perfectly correct dataset fail its own check. This script asserts the first line is a data row.

## Coordinate transform

OSGB36 eastings/northings → WGS84 latitude/longitude, by inverse Transverse Mercator on Airy 1830
followed by a 7-parameter Helmert at H=0, implemented in \`scripts/osgb36-to-wgs84.awk\`. The
Helmert parameters are the negation of OS's documented WGS84→OSGB36 set.

Validated on every regeneration against an **independent** reference (\`api.postcodes.io\`,
ONSPD-derived WGS84), sampled from Aberdeen to the Isles of Scilly because the Helmert fit is
weakest at the extremes of the National Grid:

| | |
|---|---|
| Sample size | $ACC_N postcodes |
| Mean error | **${ACC_MEAN} m** |
| Max error | **${ACC_MAX} m** |
| Gate | fails closed above ${MAX_TRANSFORM_ERROR_M} m |

The transform error is roughly two orders of magnitude below the ~100 m centroid error the product
already accepts, so it is not a meaningful error source.

## Regeneration

    scripts/regen-postcode-centroids.sh

The script verifies the OS-published md5 and **fails closed** on mismatch — non-zero exit, no
artefact written, never a warning. It also fails if the row count collapses, if any \`(0,0)\` row
survives, if a header appears, or if the transform drifts past the accuracy gate. Missing tooling or
an unreachable API exits **2 (VOID)**, because "could not check" is never "clean".
EOF

echo
echo "PASS: $ROWS rows, 0 Null-Island, md5 $ACTUAL_MD5 verified, accuracy max ${ACC_MAX} m"
