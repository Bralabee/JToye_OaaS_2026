#!/usr/bin/env bash
# media-bulk-upload.sh — push a folder of photographs through the SAME product-image
# pipeline a vendor uses from the dashboard, from a manifest, repeatably.
#
# WHY THIS EXISTS
#
#   Photographs reach the platform three ways: the owner drops a folder, the owner uploads
#   from the dashboard, a vendor uploads from theirs. The last two already share one path:
#   POST /api/v1/products/{id}/image (quarantine -> normalise -> WebP derivative -> attach,
#   MediaUploadController / MediaProcessingWorker). This script makes the first way the same
#   path. Nothing is processed on the machine that runs it: no resizing, no renaming, no
#   direct object-store write. It only decides WHICH product each file belongs to and WHICH
#   slot it fills, then lets the server do what it does for every upload.
#
#   Two slot modes, straight from the endpoint's own contract:
#     replace  is_primary=true   the product's primary image. The worker repoints the slot
#                                copy-on-write on ACTIVE, so a FAILED upload never removes
#                                the image that was there (D-04a).
#     add      is_primary=false  a gallery slot at sort_order N. Several rows for one product
#                                give it several images; one row keeps it to one.
#
#   Re-running the same manifest creates nothing, and that is the SERVER's doing: the pipeline
#   dedups on (tenant, sha256) at accept time, and placing an asset on a slot that already
#   points at it is a no-op. The Idempotency-Key is therefore scoped to ONE RUN (run id + file
#   + product + slot): it protects a retried request inside a run and nothing more. A key that
#   survived across runs was measured to replay a 202 naming an asset a later placement had
#   released, which left the row PENDING for ever (2026-11-01). The ledger is the record of
#   what each run did, one row per manifest row, appended, never rewritten.
#
# MANIFEST (CSV, header required; blank cells allowed)
#   file,shop_slug,title,mode,sort_order,sku
#     file        path to the photograph, absolute or relative to the manifest's directory
#     shop_slug   the shop the product belongs to (the vendor's public slug)
#     title       the product; matched by exact title (case-insensitive) within the shop,
#                 created as an unpublished draft when absent. Blank => the file name, or
#                 the dish the vision model names when --analyze is on.
#     mode        replace | add   (blank => replace)
#     sort_order  the gallery slot for add; REQUIRED for add. The server REPLACES an occupied
#                 slot: placeAsset repoints the (product, sort_order) row and releases the
#                 displaced asset, deleting it at ref-count 0 (reproduced 2026-11-01). So a
#                 slot is always the author's choice, never guessed from the manifest.
#     sku         SKU for a product this run creates (blank => derived from the title)
#   --init-manifest DIR prints a manifest skeleton for every image under DIR (absolute paths),
#   to edit then run. Every live run writes <base>.resolved.csv beside the ledger with the
#   titles as they ended up (every row, refused ones included); a run started FROM that file
#   finds the same ledger (<base>.ledger.tsv) and rewrites the same resolved file.
#
# PLATFORM: Linux and macOS (bash 4+, curl, jq; sha256sum or shasum). Nothing GNU-only.
#
# AUTH (the e2e suite's convention; nothing is written in this file)
#   MEDIA_VENDOR_USERNAME  (default admin-user)      the vendor realm user
#   MEDIA_VENDOR_PASSWORD  (else KC_SEED_USER_PASSWORD)   its password
#   KEYCLOAK_CLIENT_SECRET                          the core-api confidential client
#   Source the stack's .env for a local run; a staging run takes the same names from the
#   operator's shell and never from a file in the repo.
#
# USAGE
#   scripts/media-bulk-upload.sh --manifest FILE [--ledger FILE] [--dry-run] [--analyze]
#         [--api URL] [--keycloak URL] [--realm NAME] [--client-id ID] [--tenant UUID]
#         [--wait SECONDS] [--poll SECONDS]
#   scripts/media-bulk-upload.sh --init-manifest DIR [--shop SLUG] [--mode replace|add]
#   scripts/media-bulk-upload.sh --selftest
#
#   --dry-run   resolves shops and products, prints every action, sends no POST/PUT.
#   --analyze   POST /{id}/image/analyze before the upload; the result is recorded in the
#               ledger, and a product THIS RUN created with no manifest title takes the
#               model's name, description, ingredients, category and dietary tags. Price
#               stays 0 and the product stays unpublished: the vendor confirms both, and the
#               allergen mask is never set from a model (BulkImportService does the same).
#
# EXIT  0 every row ended ACTIVE (or was planned, under --dry-run)
#       1 at least one row FAILED, timed out, or was refused
#       2 VOID: could not run at all (no token, no API, bad manifest). Never read 2 as 0.
set -uo pipefail

API="${MEDIA_API_URL:-http://localhost:9090}"
KEYCLOAK="${MEDIA_KEYCLOAK_URL:-http://localhost:8085}"
REALM="${MEDIA_REALM:-jtoye-dev}"
CLIENT_ID="${MEDIA_CLIENT_ID:-core-api}"
TENANT="${MEDIA_TENANT_ID:-}"
USERNAME="${MEDIA_VENDOR_USERNAME:-admin-user}"
PASSWORD="${MEDIA_VENDOR_PASSWORD:-${KC_SEED_USER_PASSWORD:-}}"
CLIENT_SECRET="${KEYCLOAK_CLIENT_SECRET:-}"
WAIT_S=120
POLL_S=3
MANIFEST=""
LEDGER=""
DRY=0
ANALYZE=0
INIT_DIR=""
INIT_SHOP=""
INIT_MODE="replace"
SELFTEST=0
PRINT_KEY=0; KEY_ARGS=()

usage() { sed -n '2,60p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --manifest)      MANIFEST="$2"; shift 2 ;;
    --ledger)        LEDGER="$2"; shift 2 ;;
    --api)           API="$2"; shift 2 ;;
    --keycloak)      KEYCLOAK="$2"; shift 2 ;;
    --realm)         REALM="$2"; shift 2 ;;
    --client-id)     CLIENT_ID="$2"; shift 2 ;;
    --tenant)        TENANT="$2"; shift 2 ;;
    --wait)          WAIT_S="$2"; shift 2 ;;
    --poll)          POLL_S="$2"; shift 2 ;;
    --dry-run)       DRY=1; shift ;;
    --analyze)       ANALYZE=1; shift ;;
    --init-manifest) INIT_DIR="$2"; shift 2 ;;
    --shop)          INIT_SHOP="$2"; shift 2 ;;
    --mode)          INIT_MODE="$2"; shift 2 ;;
    --selftest)      SELFTEST=1; shift ;;
    --print-key)     PRINT_KEY=1; shift; KEY_ARGS=("$@"); break ;;
    -h|--help)       usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

void() { echo "VOID: $*" >&2; exit 2; }
say()  { printf '%s\n' "$*"; }

# ---------------------------------------------------------------------------------
# Pure helpers (no network) — the --selftest arms cover these.
# ---------------------------------------------------------------------------------

# csv_field LINE N -> prints field N (1-based) of a CSV line with optional double quotes
# ("" inside quotes is a literal quote). Pure bash: mawk, the Ubuntu default awk, has no FPAT.
csv_field() {
  local line="$1" n="$2" i c field="" idx=1 inq=0
  for ((i = 0; i < ${#line}; i++)); do
    c="${line:i:1}"
    if ((inq)); then
      if [[ $c == '"' ]]; then
        if [[ ${line:i+1:1} == '"' ]]; then field+='"'; ((i++)); else inq=0; fi
      else field+="$c"; fi
    else
      if [[ $c == '"' ]]; then inq=1
      elif [[ $c == ',' ]]; then ((idx == n)) && break; ((idx++)); field=""
      else field+="$c"; fi
    fi
  done
  ((idx == n)) || field=""
  field="${field#"${field%%[![:space:]]*}"}"; field="${field%"${field##*[![:space:]]}"}"
  printf '%s' "$field"
}

# sha256_of FILE -> 64 hex (sha256sum on Linux, shasum on macOS)
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -c1-64; else shasum -a 256 "$1" | cut -c1-64; fi
}

# title_from_file PATH -> "egusi-soup_02.jpg" => "Egusi Soup 02"; "mama-ade's.jpg" => "Mama Ade's".
# Pure bash: words split on - _ and space, first letter of each word upper-cased, nothing else touched.
title_from_file() {
  local b w out=""
  b="$(basename "$1")"; b="${b%.*}"; b="${b//[-_]/ }"
  for w in $b; do out+="${out:+ }${w^}"; done
  printf '%s' "$out"
}

# sku_for TITLE FILE_SHA -> "Egusi Soup" => "EGUSI-SOUP-<4 hex of the FILE's sha>": BulkImportService's
# shape, deterministic so a re-run derives the same SKU, and keyed by the FILE so two files that
# share a stem (menu-sides.jpg and menu-sides.jpeg, measured 2026-11-01) never collide on the
# tenant-unique SKU.
sku_for() {
  local base; base="$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]' | sed -E 's/[^A-Z0-9]+/-/g; s/^-//; s/-$//' | cut -c1-15)"
  printf '%s-%s' "$base" "$(printf '%s' "$2" | cut -c1-4 | tr '[:lower:]' '[:upper:]')"
}

# idem_key SHA PRODUCT MODE SLOT -> the Idempotency-Key for this RUN (<= 64 chars is what the
# server stores): stable within the run, different in the next one (see the header).
RUN_ID="${MEDIA_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
idem_key() { printf 'media-bulk-%s' "$(printf '%s|%s|%s|%s|%s' "$RUN_ID" "$1" "$2" "$3" "$4" | { if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi; } | cut -c1-40)"; }

json_escape() { jq -Rn --arg v "$1" '$v'; }

# ledger_product_lookup LEDGER SHA SHOP -> the product this file was last placed on in this shop
# (the newest ledger row for the pair that names a product), or nothing. The ledger is the
# run's memory: a draft the model renamed is no longer findable by the manifest's blank title.
ledger_product_lookup() {
  [ -r "$1" ] || return 0
  awk -F'\t' -v sha="$2" -v shop="$3" 'NR>1 && $3==sha && $4==shop && $5!="-" && $5!="" {p=$5} END{if (p!="") print p}' "$1"
}

if [ "$PRINT_KEY" = 1 ]; then idem_key "${KEY_ARGS[@]}"; echo; exit 0; fi   # selftest helper: a key under a given MEDIA_RUN_ID

if [ "$SELFTEST" = 1 ]; then
  fails=0
  check() { if [ "$2" = "$3" ]; then printf 'PASS  %s\n' "$1"; else printf 'FAIL  %s: got [%s] want [%s]\n' "$1" "$2" "$3"; fails=$((fails+1)); fi; }
  check "csv plain field"      "$(csv_field 'a.jpg,mama-ades-kitchen,Jollof Rice,replace,,' 3)" "Jollof Rice"
  check "csv quoted comma"     "$(csv_field 'a.jpg,s,"Rice, Peas",add,2,' 3)" "Rice, Peas"
  check "csv blank field"      "$(csv_field 'a.jpg,s,,add,2,' 3)" ""
  check "csv trailing blank"   "$(csv_field 'a.jpg,s,T,add,2,' 6)" ""
  check "title from file"      "$(title_from_file '/x/egusi-soup_02.jpg')" "Egusi Soup 02"
  check "title keeps apostrophe" "$(title_from_file "/x/mama-ade's kitchen.jpg")" "Mama Ade's Kitchen"
  check "sku shape"            "$(sku_for 'Egusi Soup' abcd1234)" "EGUSI-SOUP-ABCD"
  check "sku deterministic"    "$(sku_for 'Egusi Soup' abcd1234)" "$(sku_for 'Egusi Soup' abcd1234)"
  check "sku differs by file"  "$([ "$(sku_for 'Menu Sides' 1111aaaa)" != "$(sku_for 'Menu Sides' 2222bbbb)" ] && echo differ)" "differ"
  check "ledger reuse picks the row's product" "$(printf 'at\tsource\tsha256\tshop\tproduct_id\tcreated\tmode\tslot\tkey\tasset\tstatus\nt\tf\tSHA1\tshop-a\tPID-1\tyes\treplace\t0\tk\ta\tACTIVE\nt\tf\tSHA1\tshop-b\tPID-2\tyes\treplace\t0\tk\ta\tACTIVE\nt\tf\tSHA1\tshop-a\t-\tno\treplace\t0\tk\t-\tREFUSED\n' | ledger_product_lookup /dev/stdin SHA1 shop-a)" "PID-1"
  check "idem stable in a run" "$(idem_key abc p1 replace 0)" "$(idem_key abc p1 replace 0)"
  check "idem differs by run"  "$([ "$(MEDIA_RUN_ID=r1 bash "$0" --print-key abc p1 replace 0)" != "$(MEDIA_RUN_ID=r2 bash "$0" --print-key abc p1 replace 0)" ] && echo differ)" "differ"
  k1="$(idem_key abc p1 replace 0)"; k2="$(idem_key abc p1 add 1)"
  check "idem differs by slot" "$([ "$k1" != "$k2" ] && echo differ)" "differ"
  check "idem length <= 64"    "$([ ${#k1} -le 64 ] && echo ok)" "ok"
  [ "$fails" = 0 ] && exit 0 || exit 1
fi

# ---------------------------------------------------------------------------------
# --init-manifest: a skeleton for a folder, one row per image, to edit and then run.
# ---------------------------------------------------------------------------------
if [ -n "$INIT_DIR" ]; then
  [ -d "$INIT_DIR" ] || void "no directory at $INIT_DIR"
  say "file,shop_slug,title,mode,sort_order,sku"
  INIT_DIR="$(cd "$INIT_DIR" && pwd)"   # absolute: the run resolves a relative path against the MANIFEST's directory, not the shell's
  /usr/bin/find "$INIT_DIR" -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.webp' \) | LC_ALL=C sort | while read -r f; do
    say "\"${f//\"/\"\"}\",$INIT_SHOP,,$INIT_MODE,,"
  done
  exit 0
fi

# ---------------------------------------------------------------------------------
# Live run
# ---------------------------------------------------------------------------------
[ -n "$MANIFEST" ] || void "--manifest FILE is required (or --init-manifest DIR, or --selftest)"
[ -f "$MANIFEST" ] || void "no manifest at $MANIFEST"
command -v jq >/dev/null 2>&1 || void "jq is not installed"
command -v curl >/dev/null 2>&1 || void "curl is not installed"
MANIFEST_DIR="$(cd "$(dirname "$MANIFEST")" && pwd)"
BASE="${MANIFEST%.csv}"; BASE="${BASE%.resolved}"     # X.csv and X.resolved.csv share one ledger and one resolved file
[ -n "$LEDGER" ] || LEDGER="$BASE.ledger.tsv"

header="$(head -n1 "$MANIFEST" | tr -d '\r')"
[ "$header" = "file,shop_slug,title,mode,sort_order,sku" ] || void "manifest header must be exactly: file,shop_slug,title,mode,sort_order,sku (got: $header)"
total_rows=$(( $(grep -c . "$MANIFEST") - 1 ))
[ "$total_rows" -ge 1 ] || void "manifest has no rows"

[ -n "$PASSWORD" ] || void "no vendor password: set MEDIA_VENDOR_PASSWORD or KC_SEED_USER_PASSWORD (source the stack's .env)"
[ -n "$CLIENT_SECRET" ] || void "no KEYCLOAK_CLIENT_SECRET (source the stack's .env)"

# One token for the run. The password grant is what the e2e suite and staging-seed use;
# the secret never appears on a command line (curl reads it from a data file).
secret_f="$(mktemp)"; pass_f="$(mktemp)"; trap 'rm -f "$secret_f" "$pass_f"' EXIT
printf '%s' "$CLIENT_SECRET" > "$secret_f"; printf '%s' "$PASSWORD" > "$pass_f"
TOKEN="$(curl -sS --max-time 20 -X POST "$KEYCLOAK/realms/$REALM/protocol/openid-connect/token" \
  --data-urlencode grant_type=password --data-urlencode "client_id=$CLIENT_ID" --data-urlencode "username=$USERNAME" \
  --data-urlencode "client_secret@$secret_f" --data-urlencode "password@$pass_f" | jq -r '.access_token // empty')"
rm -f "$secret_f" "$pass_f"
[ -n "$TOKEN" ] || void "no access token from $KEYCLOAK realm $REALM for $USERNAME (wrong password/secret, or Keycloak down)"
if [ -z "$TENANT" ]; then
  TENANT="$(printf '%s' "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | { p=$(cat); printf '%s' "$p"; case $(( ${#p} % 4 )) in 2) printf '==';; 3) printf '=';; esac; } | base64 -d 2>/dev/null | jq -r '.tenant_id // empty' 2>/dev/null)"
fi
[ -n "$TENANT" ] || void "no tenant id: pass --tenant UUID (the token carries no tenant_id claim)"

AUTH=(-H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT")

api_get()  { curl -sS --max-time 30 "${AUTH[@]}" -H 'Accept: application/json' "$API$1"; }
# api_call METHOD PATH IDEMKEY [curl args...] -> sets HTTP (status) and RESP (body) in THIS shell.
# Deliberately not "$(...)"-shaped: a status set inside a command substitution dies with it.
HTTP=000; RESP=""
api_call() {
  local method="$1" path="$2" idem="$3"; shift 3
  local tmp; tmp="$(mktemp)"
  HTTP="$(curl -sS --max-time 120 -o "$tmp" -w '%{http_code}' -X "$method" "${AUTH[@]}" -H 'Accept: application/json' ${idem:+-H "Idempotency-Key: $idem"} "$@" "$API$path")" || HTTP=000
  RESP="$(cat "$tmp")"; rm -f "$tmp"
}

# Shops: slug -> id, once.
shops_json="$(api_get "/api/v1/shops?size=100")" || void "GET /shops failed"
shop_id_for() { printf '%s' "$shops_json" | jq -r --arg s "$1" '(.content // .)[] | select(.slug == $s) | .id' | head -n1; }

# Products: exact title within a shop (case-insensitive) over the shop's FULL listing, page by
# page. Not the FTS search: that is an unsorted prefix match capped at one page, so an exact
# title can fall off it and a stop-word title never matches at all.
product_id_for() {
  local shop="$1" title="$2" page=0 body id last
  while :; do
    body="$(api_get "/api/v1/products?shopId=$shop&size=100&page=$page")" || return 0
    id="$(printf '%s' "$body" | jq -r --arg t "$title" '(.content // [])[] | select((.title | ascii_downcase) == ($t | ascii_downcase)) | .id' | head -n1)"
    [ -n "$id" ] && { printf '%s' "$id"; return 0; }
    last="$(printf '%s' "$body" | jq -r '.last // true')"
    [ "$last" = true ] && return 0
    page=$((page+1))
  done
}

create_product() {   # shop title sku -> sets PID (empty on failure), HTTP, RESP
  local body
  body="$(jq -n --arg shop "$1" --arg title "$2" --arg sku "$3" '{
      shopId: $shop, title: $title, sku: $sku, pricePennies: 0, allergenMask: 0, available: false, featured: false,
      ingredientsText: "To be confirmed by the kitchen" }')"
  # No Idempotency-Key: POST /products does not read one. The guard against a double create is
  # the tenant-unique SKU, which is why sku_for is keyed by the file.
  api_call POST /api/v1/products "" -H 'Content-Type: application/json' --data-binary "$body"
  PID="$(printf '%s' "$RESP" | jq -r '.id // empty' 2>/dev/null)"
}

analyze_file() {   # product file -> sets ANALYSIS (JSON, or empty), HTTP
  # One retry: the local vision runner's cold start (model load + first prefill) exceeds the
  # service's 30 s call timeout, which answers 422; warm, the same call takes ~4-5 s (measured
  # 2026-11-01, gemma3:12b on the dev stack). A second miss is recorded, not retried again.
  local attempt
  for attempt in 1 2; do
    api_call POST "/api/v1/products/$1/image/analyze" "" -F "file=@\"${2//\"/\\\"}\""
    if [ "$HTTP" = 200 ]; then ANALYSIS="$RESP"; return; fi
    [ "$attempt" = 1 ] && sleep "$POLL_S"
  done
  ANALYSIS=""
}

RESOLVED="$BASE.resolved.csv"; RESOLVED_TMP="$(mktemp)"
printf 'file,shop_slug,title,mode,sort_order,sku\n' > "$RESOLVED_TMP"
# Every row goes in, refused and failed ones with whatever title they had, so the file is the
# whole manifest as resolved so far. It is moved into place only when the run completes, and
# a --dry-run never writes it (a planning pass must not destroy a curated file).
resolved() { printf '"%s",%s,"%s",%s,%s,%s\n' "${1//\"/\"\"}" "$2" "${3//\"/\"\"}" "$4" "$5" "$6" >> "$RESOLVED_TMP"; }
[ -f "$LEDGER" ] || printf 'at\tsource\tsha256\tshop\tproduct_id\tcreated\tmode\tslot\tidempotency_key\tasset_id\tstatus\turl\tai_name\tai_confidence\tnote\n' > "$LEDGER"
ledger() { printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date -u +%FT%TZ)" "$@" >> "$LEDGER"; }

say "media-bulk-upload: api=$API realm=$REALM user=$USERNAME tenant=$TENANT rows=$total_rows ledger=$LEDGER dry_run=$DRY analyze=$ANALYZE"

row=0; n_active=0; n_failed=0; n_planned=0; n_created=0
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  row=$((row+1)); [ "$row" = 1 ] && continue
  [ -z "$line" ] && continue
  file="$(csv_field "$line" 1)"; slug="$(csv_field "$line" 2)"; title="$(csv_field "$line" 3)"
  mode="$(csv_field "$line" 4)"; slot="$(csv_field "$line" 5)"; sku="$(csv_field "$line" 6)"
  [ -n "$mode" ] || mode=replace
  case "$file" in /*) ;; *) file="$MANIFEST_DIR/$file" ;; esac
  tag="row $row ($(basename "$file"))"

  if [ ! -f "$file" ]; then say "REFUSED $tag: no such file"; ledger "$file" - "$slug" - - "$mode" "$slot" - - - REFUSED - - - "no such file"; resolved "$file" "$slug" "$title" "$mode" "$slot" "$sku"; n_failed=$((n_failed+1)); continue; fi
  case "$mode" in replace|add) ;; *) say "REFUSED $tag: mode must be replace|add (got $mode)"; ledger "$file" - "$slug" - - "$mode" "$slot" - - - REFUSED - - - "bad mode"; resolved "$file" "$slug" "$title" "$mode" "$slot" "$sku"; n_failed=$((n_failed+1)); continue ;; esac
  if [ "$mode" = add ] && [ -z "$slot" ]; then say "REFUSED $tag: add needs an explicit sort_order (the server replaces an occupied slot and releases what was there)"; ledger "$file" - "$slug" - - add - - - - REFUSED - - - "add without sort_order"; resolved "$file" "$slug" "$title" "$mode" "$slot" "$sku"; n_failed=$((n_failed+1)); continue; fi
  shop="$(shop_id_for "$slug")"
  if [ -z "$shop" ]; then say "REFUSED $tag: no shop with slug '$slug' visible to $USERNAME"; ledger "$file" - "$slug" - - "$mode" "$slot" - - - REFUSED - - - "unknown shop"; resolved "$file" "$slug" "$title" "$mode" "$slot" "$sku"; n_failed=$((n_failed+1)); continue; fi
  sha="$(sha256_of "$file")"

  title_source=manifest; pid=""; created=no
  # The ledger first, for EVERY row: this file was placed on a product of this shop before, and
  # that product may since have been renamed by the model or share its title with others (three
  # "Suya" drafts, measured). A manifest title that differs from that product's title is the
  # author moving the file on purpose, and then the title governs.
  prev="$(ledger_product_lookup "$LEDGER" "$sha" "$slug")"
  if [ -n "$prev" ]; then
    api_call GET "/api/v1/products/$prev" ""
    if [ "$HTTP" = 200 ]; then
      prev_title="$(printf '%s' "$RESP" | jq -r '.title // empty')"
      if [ -z "$title" ] || [ "${title,,}" = "${prev_title,,}" ]; then pid="$prev"; title="$prev_title"; title_source=ledger; fi
    fi
  fi
  if [ -z "$pid" ] && [ -z "$title" ]; then title="$(title_from_file "$file")"; title_source=filename; fi
  [ -n "$pid" ] || pid="$(product_id_for "$shop" "$title")"
  ai_name=-; ai_conf=-; analysis=""

  if [ -z "$pid" ]; then
    if [ "$DRY" = 1 ]; then
      say "PLAN    $tag: create draft '$title' in $slug, then $mode (slot ${slot:-auto})"
      ledger "$file" "$sha" "$slug" - would-create "$mode" "${slot:-0}" - - PLANNED - - - "dry-run"; n_planned=$((n_planned+1)); continue
    fi
    [ -n "$sku" ] || sku="$(sku_for "$title" "$sha")"
    create_product "$shop" "$title" "$sku"; pid="$PID"
    if [ -z "$pid" ]; then say "FAILED  $tag: create '$title' -> HTTP $HTTP"; ledger "$file" "$sha" "$slug" - create-failed "$mode" "$slot" - - FAILED - - - "HTTP $HTTP"; resolved "$file" "$slug" "$title" "$mode" "$slot" "$sku"; n_failed=$((n_failed+1)); continue; fi
    created=yes; n_created=$((n_created+1))
    say "CREATED $tag: draft '$title' ($pid) in $slug"
  fi

  if [ "$ANALYZE" = 1 ] && [ "$DRY" = 0 ]; then
    analyze_file "$pid" "$file"; analysis="$ANALYSIS"
    if [ -n "$analysis" ]; then
      ai_name="$(printf '%s' "$analysis" | jq -r '.identifiedName // "-"')"
      ai_conf="$(printf '%s' "$analysis" | jq -r '.confidence // "-"')"
      if [ "$created" = yes ] && [ "$title_source" = filename ] && [ "$ai_name" != "-" ]; then
        # The model names a product this run created from a bare file name. Re-send the
        # create shape with the model's fields; price and publication stay the vendor's.
        body="$(printf '%s' "$analysis" | jq --arg shop "$shop" --arg sku "$sku" '{
            shopId: $shop, sku: $sku, title: .identifiedName, pricePennies: 0, allergenMask: 0, available: false, featured: false,
            ingredientsText: (.ingredients // "To be confirmed by the kitchen"), description: .description, category: .category,
            dietaryTags: ((.dietaryTags // []) | join(", ") | if . == "" then null else . end) }')"
        api_call PUT "/api/v1/products/$pid" "" -H 'Content-Type: application/json' --data-binary "$body"
        [ "$HTTP" = 200 ] && { say "NAMED   $tag: '$title' -> '$ai_name' (confidence $ai_conf)"; title="$ai_name"; } || say "note    $tag: PUT with the model's fields -> HTTP $HTTP (kept '$title')"
      fi
    else
      say "note    $tag: analyze -> HTTP $HTTP (no analysis recorded)"
    fi
  fi

  if [ "$mode" = add ]; then primary=false; else slot=0; primary=true; fi
  key="$(idem_key "$sha" "$pid" "$mode" "$slot")"

  if [ "$DRY" = 1 ]; then
    say "PLAN    $tag: $mode on '$title' ($pid) slot $slot key $key"
    ledger "$file" "$sha" "$slug" "$pid" "$created" "$mode" "$slot" "$key" - PLANNED - "$ai_name" "$ai_conf" "dry-run"; n_planned=$((n_planned+1)); continue
  fi

  api_call POST "/api/v1/products/$pid/image" "$key" -F "file=@\"${file//\"/\\\"}\"" -F "is_primary=$primary" -F "sort_order=$slot"; accept="$RESP"
  if [ "$HTTP" != 202 ]; then
    say "FAILED  $tag: upload -> HTTP $HTTP $(printf '%s' "$accept" | jq -r '.detail // .title // empty' 2>/dev/null)"
    ledger "$file" "$sha" "$slug" "$pid" "$created" "$mode" "$slot" "$key" - FAILED - "$ai_name" "$ai_conf" "HTTP $HTTP"; resolved "$file" "$slug" "$title" "$mode" "$slot" "$sku"; n_failed=$((n_failed+1)); continue
  fi
  asset="$(printf '%s' "$accept" | jq -r '.assetId // empty')"
  status="$(printf '%s' "$accept" | jq -r '.status // empty')"
  url=-; note=-; m=""
  deadline=$(( $(date +%s) + WAIT_S ))
  while [ "$status" = PENDING ] && [ "$(date +%s)" -lt "$deadline" ]; do
    sleep "$POLL_S"
    m="$(api_get "/api/v1/products/$pid" | jq -c --arg a "$asset" '(.media // [])[] | select(.assetId == $a)' | head -n1)"
    [ -n "$m" ] || continue
    status="$(printf '%s' "$m" | jq -r '.status')"
  done
  # A file already ACTIVE in this tenant is shared at accept time (the (tenant, sha256) dedup),
  # so the 202 itself says ACTIVE and the poll never ran: read the placed row once for its URL.
  if [ -z "$m" ] && [ "$status" != PENDING ]; then
    m="$(api_get "/api/v1/products/$pid" | jq -c --arg a "$asset" '(.media // [])[] | select(.assetId == $a)' | head -n1)"
  fi
  case "$status" in
    ACTIVE) url="$(printf '%s' "$m" | jq -r '.url // "-"')"; n_active=$((n_active+1)); say "ACTIVE  $tag: $mode on '$title' slot $slot -> $asset" ;;
    FAILED) note="$(printf '%s' "$m" | jq -r '.failureReason // "-"')"; n_failed=$((n_failed+1)); say "FAILED  $tag: $asset: $note" ;;
    *)      note="still $status after ${WAIT_S}s"; status=TIMEOUT; n_failed=$((n_failed+1)); say "TIMEOUT $tag: $asset $note" ;;
  esac
  ledger "$file" "$sha" "$slug" "$pid" "$created" "$mode" "$slot" "$key" "$asset" "$status" "$url" "$ai_name" "$ai_conf" "$note"
  resolved "$file" "$slug" "$title" "$mode" "$slot" "$sku"
done < "$MANIFEST"

if [ "$DRY" = 1 ]; then rm -f "$RESOLVED_TMP"; RESOLVED="(not written under --dry-run)"; else mv "$RESOLVED_TMP" "$RESOLVED"; fi
say "media-bulk-upload: rows=$total_rows active=$n_active failed=$n_failed planned=$n_planned created=$n_created ledger=$LEDGER resolved=$RESOLVED"
[ "$n_failed" = 0 ] && exit 0 || exit 1
