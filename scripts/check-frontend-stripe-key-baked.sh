#!/usr/bin/env bash
# check-frontend-stripe-key-baked.sh — the Stripe publishable key is INLINED in the checkout chunks
# the frontend actually serves (issue #957, phase 30 plan 30-01).
#
# WHY
#   NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY is inlined into the browser bundle by `next build`, and only
#   if the name is present in the build environment. For months it was not: frontend/Dockerfile
#   declared no ARG for it and CI passed no build-arg, so every image shipped the un-inlined lookup
#   `t.default.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`, which the browser resolves to nothing. The
#   staging card checkout then created an unpaid DRAFT and silently fell back to the form. Measured
#   on app-staging (image 4f3f3e14): 22 checkout chunks, 0 pk_ literals, while the inlined API
#   origin api-staging.olajay.co.uk was found once in the same scan.
#
#   A green build, a 200 and a rendered checkout look identical either way, so this reads the key
#   out of the RUNNING artifact: the chunks a real browser would load for the checkout route.
#
# USAGE
#   scripts/check-frontend-stripe-key-baked.sh --url BASE --path /shop/<slug>/checkout \
#       --control <string> [--expect-prefix pk_test_]
#   scripts/check-frontend-stripe-key-baked.sh --scan-dir DIR --control <string> [--expect-prefix pk_test_]
#   scripts/check-frontend-stripe-key-baked.sh --selftest
#   STRIPE_KEY_CHECK_SELFTEST_FORMAT=tap scripts/check-frontend-stripe-key-baked.sh --selftest
#
#   --url      fetch BASE+PATH (curl --max-time 20), take every /_next/static/chunks/*.js it
#              references (script src AND the RSC payload's escaped form), and fetch each one.
#   --scan-dir scan every *.js file under DIR (e.g. a `docker cp` of an image's /app/.next/static).
#   --control  REQUIRED. A string that is known to be inlined in the same bundle, e.g. the baked
#              API origin host. Found 0 times means the scan cannot see inlined values: VOID.
#   --expect-prefix  every pk_ literal must start with this (e.g. pk_test_ for staging).
#
#   STRIPE_KEY_CHECK_FETCH_ROOT=<dir> maps every --url fetch to the file <dir><path> instead of the
#   network (selftest only).
#
#   Counted over the fetched/scanned JavaScript:
#     pk literals      pk_(test|live)_[A-Za-z0-9]{8,}         (DISTINCT values)
#     residues         env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY  (a lookup Next did not inline)
#     secret literals  (sk|rk)_(test|live)_[A-Za-z0-9]{8,}     (a secret key in a public bundle)
#     control          occurrences of --control
#   Only each key's PREFIX and LENGTH are printed, never the value.
#
# EXIT
#   0  PASS: exactly one distinct pk literal, every pk literal carries --expect-prefix (if given),
#      0 residues, 0 secret literals, control >= 1
#   1  FAIL: no key, two or more keys, a key with the wrong prefix, a residue, or a secret literal
#   2  VOID: usage error, a fetch failed, no chunk was found, grep errored, or the control count is
#      0 (the scan is blind, so it fails closed — "found nothing" is never "clean")

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF="$SCRIPT_DIR/check-frontend-stripe-key-baked.sh"

PK_RE='pk_(test|live)_[A-Za-z0-9]{8,}'
SECRET_RE='(sk|rk)_(test|live)_[A-Za-z0-9]{8,}'
RESIDUE_RE='env\.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY'
CHUNK_RE='/_next/static/chunks/[^"'"'"'\\[:space:]<>]+\.js'

void() { echo "VOID: $*" >&2; exit 2; }

usage() { sed -n '/^# USAGE/,/^# EXIT/p' "$SELF" | sed '$d' | sed 's/^# \{0,1\}//'; }

# fetch URL OUTFILE -> rc (0 ok). With STRIPE_KEY_CHECK_FETCH_ROOT the URL's path is read from disk.
fetch() {
  local url="$1" out="$2" path
  if [ -n "${STRIPE_KEY_CHECK_FETCH_ROOT-}" ]; then
    path="${url#"$BASE"}"
    [ -f "${STRIPE_KEY_CHECK_FETCH_ROOT}${path}" ] || return 22
    cp "${STRIPE_KEY_CHECK_FETCH_ROOT}${path}" "$out"
    return $?
  fi
  curl -fsS --max-time 20 -o "$out" "$url"
}

# grep_o PATTERN FILE OUT -> writes matches (one per line) to OUT; VOIDs on a grep error (rc 2).
grep_o() {
  local rc=0
  command grep -oE -- "$1" "$2" > "$3" || rc=$?
  [ "$rc" -le 1 ] || void "grep failed (rc=$rc) on pattern $1"
}

count_lines() { local n; n=$(wc -l < "$1"); echo $((n + 0)); }

# Assemble the JavaScript to scan into $WORK/all.js and set CHUNKS.
collect_url() {
  local page="$WORK/page.html" src n=0 rc=0
  fetch "${BASE}${PAGE_PATH}" "$page" || rc=$?
  [ "$rc" -eq 0 ] || void "could not fetch the checkout page ${BASE}${PAGE_PATH} (rc=$rc); nothing was scanned"
  grep_o "$CHUNK_RE" "$page" "$WORK/srcs.raw"
  sort -u "$WORK/srcs.raw" > "$WORK/srcs"
  : > "$WORK/all.js"
  while IFS= read -r src; do
    [ -n "$src" ] || continue
    rc=0; fetch "${BASE}${src}" "$WORK/chunk.js" || rc=$?
    # A chunk the page names but we cannot read leaves a hole in the scan: never a partial verdict.
    [ "$rc" -eq 0 ] || void "could not fetch chunk ${src} (rc=$rc); a partial scan proves nothing"
    cat "$WORK/chunk.js" >> "$WORK/all.js"; printf '\n' >> "$WORK/all.js"
    n=$((n + 1))
  done < "$WORK/srcs"
  CHUNKS=$n
}

collect_dir() {
  local f n=0
  [ -d "$SCAN_DIR" ] || void "--scan-dir '$SCAN_DIR' is not a directory"
  : > "$WORK/all.js"
  # A trailing slash on the start point: a symlinked start directory would otherwise yield nothing.
  /usr/bin/find "${SCAN_DIR%/}/" -type f -name '*.js' -print > "$WORK/files" 2>"$WORK/find.err" \
    || void "find failed under $SCAN_DIR: $(head -c 300 "$WORK/find.err")"
  while IFS= read -r f; do
    cat "$f" >> "$WORK/all.js"; printf '\n' >> "$WORK/all.js"
    n=$((n + 1))
  done < "$WORK/files"
  CHUNKS=$n
}

check() {
  local distinct residues secrets control k bad_prefix=0 desc="" reasons=""
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/jtoye-pkcheck.XXXXXXXX")" || void "mktemp -d failed"
  # shellcheck disable=SC2064
  trap "rm -rf '$WORK'" EXIT
  CHUNKS=0
  if [ "$MODE" = url ]; then collect_url; else collect_dir; fi
  [ "$CHUNKS" -gt 0 ] || void "no JavaScript chunk was found to scan (mode=$MODE); the check is blind"

  grep_o "$PK_RE" "$WORK/all.js" "$WORK/pk.raw"
  sort -u "$WORK/pk.raw" > "$WORK/pk"
  distinct=$(count_lines "$WORK/pk")
  grep_o "$RESIDUE_RE" "$WORK/all.js" "$WORK/res"; residues=$(count_lines "$WORK/res")
  grep_o "$SECRET_RE" "$WORK/all.js" "$WORK/sec"; secrets=$(count_lines "$WORK/sec")
  control=$(command grep -oF -- "$CONTROL" "$WORK/all.js" | wc -l); control=$((control + 0))

  while IFS= read -r k; do
    [ -n "$k" ] || continue
    case "$k" in pk_test_*) p=pk_test_ ;; pk_live_*) p=pk_live_ ;; *) p=other ;; esac
    desc="${desc}${desc:+,}${p}(length=${#k})"
    if [ -n "$EXPECT_PREFIX" ]; then
      case "$k" in "$EXPECT_PREFIX"*) ;; *) bad_prefix=$((bad_prefix + 1)) ;; esac
    fi
  done < "$WORK/pk"
  [ -n "$desc" ] || desc=none

  echo "chunks=$CHUNKS distinct_pk=$distinct keys=$desc expect_prefix=${EXPECT_PREFIX:-<any>} residues=$residues secrets=$secrets control=$control"

  if [ "$control" -eq 0 ]; then
    void "the control string was found 0 times in $CHUNKS chunk(s): the scan cannot see an inlined value, so a 0 for the key would mean nothing"
  fi

  [ "$distinct" -eq 1 ] || reasons="${reasons}; expected exactly 1 distinct publishable key, found $distinct"
  [ "$bad_prefix" -eq 0 ] || reasons="${reasons}; $bad_prefix key(s) do not start with $EXPECT_PREFIX"
  [ "$residues" -eq 0 ] || reasons="${reasons}; $residues un-inlined env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY lookup(s) (the build had no key)"
  [ "$secrets" -eq 0 ] || reasons="${reasons}; $secrets SECRET-key-shaped literal(s) (sk_/rk_) in a public bundle — rotate that key"
  if [ -n "$reasons" ]; then
    echo "FAIL: the served checkout bundle does not carry exactly one usable publishable key${reasons}" >&2
    exit 1
  fi
  echo "PASS: one publishable key inlined (${desc}), 0 residues, 0 secret literals, control found $control time(s)"
  exit 0
}

# ---------------------------------------------------------------------------
# --selftest: fixture bundles through --scan-dir and --url (via STRIPE_KEY_CHECK_FETCH_ROOT)
# ---------------------------------------------------------------------------
RESULTS=()
result() { RESULTS+=("$1"); }
render_results() {
  local fmt="$1" i=0 r verdict rest
  if [ "$fmt" = tap ]; then
    echo "TAP version 13"
    echo "1..${#RESULTS[@]}"
    for r in "${RESULTS[@]}"; do
      i=$((i + 1)); verdict="${r%% *}"; rest="${r#* }"
      if [ "$verdict" = PASS ]; then
        echo "ok $i - ${rest%% *}"
      else
        echo "not ok $i - ${rest%% *}"
        echo "  ---"
        echo "  message: '$(printf '%s' "${rest#* }" | tr "'\n" '" ')'"
        echo "  ..."
      fi
    done
  else
    for r in "${RESULTS[@]}"; do printf '%s\n' "$r"; done
  fi
}

# st_dir TAG DIR [ARGS...] -> rc; output in $ST/TAG.out
st_dir() {
  local tag="$1" dir="$2" rc=0; shift 2
  bash "$SELF" --scan-dir "$dir" "$@" > "$ST/$tag.out" 2>&1 < /dev/null || rc=$?
  echo "$rc"
}
# st_url TAG ROOT PATH [ARGS...] -> rc; output in $ST/TAG.out
st_url() {
  local tag="$1" root="$2" path="$3" rc=0; shift 3
  STRIPE_KEY_CHECK_FETCH_ROOT="$root" bash "$SELF" --url https://app.selftest.invalid --path "$path" "$@" \
    > "$ST/$tag.out" 2>&1 < /dev/null || rc=$?
  echo "$rc"
}
# mkchunk DIR NAME CONTENT
mkchunk() { mkdir -p "$1"; printf '%s\n' "$3" > "$1/$2"; }
# arm NAME WANT_RC GOT_RC TAG [WANT_SUBSTRING]
arm() {
  local name="$1" want="$2" got="$3" tag="$4" needle="${5-}" why=""
  [ "$got" = "$want" ] || why="rc=$got (want $want): $(tail -n 2 "$ST/$tag.out" | tr '\n' ' ')"
  if [ -z "$why" ] && [ -n "$needle" ] && ! command grep -qF -- "$needle" "$ST/$tag.out"; then
    why="output lacks '$needle': $(tail -n 2 "$ST/$tag.out" | tr '\n' ' ')"
  fi
  if [ -z "$why" ]; then result "PASS $name rc=$got"; else result "FAIL $name $why"; FAILS=$((FAILS + 1)); fi
}

selftest() {
  local fmt="${STRIPE_KEY_CHECK_SELFTEST_FORMAT:-plain}" rc ctl key key2 live secret why f
  FAILS=0
  ST="$(mktemp -d "${TMPDIR:-/tmp}/jtoye-pkcheck-st.XXXXXXXX")" || void "mktemp -d failed"
  trap 'rm -rf "$ST"' EXIT
  # Fixture values are COMPOSED at run time so no secret scanner sees a key-shaped literal in this file.
  ctl="api-selftest.olajay.invalid"
  key="pk_""test_""SelfTestFixture0123456789"
  key2="pk_""test_""SecondFixtureKeyABCDEFGH"
  live="pk_""live_""LiveFixtureKey0123456789"
  secret="s""k_""test_""SecretFixtureKey01234567"

  # 1. key-present: one pk_test_ key + control -> PASS rc 0
  mkchunk "$ST/present" a.js "var o=\"https://${ctl}\";"
  mkchunk "$ST/present" b.js "var k=\"${key}\";var x=(0,s.loadStripe)(k);"
  rc=$(st_dir present "$ST/present" --expect-prefix pk_test_ --control "$ctl")
  arm key-present 0 "$rc" present "distinct_pk=1"

  # 2. key-absent: control only -> FAIL rc 1
  mkchunk "$ST/absent" a.js "var o=\"https://${ctl}\";var k=\"\";"
  rc=$(st_dir absent "$ST/absent" --expect-prefix pk_test_ --control "$ctl")
  arm key-absent 1 "$rc" absent "distinct_pk=0"

  # 3. residue: a key AND an un-inlined lookup -> FAIL rc 1
  mkchunk "$ST/residue" a.js "var o=\"https://${ctl}\";var k=\"${key}\";var e=t.default.env.NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY;"
  rc=$(st_dir residue "$ST/residue" --expect-prefix pk_test_ --control "$ctl")
  arm residue 1 "$rc" residue "residues=1"

  # 4. live-under-test-prefix: a pk_live_ key where pk_test_ is required -> FAIL rc 1
  mkchunk "$ST/live" a.js "var o=\"https://${ctl}\";var k=\"${live}\";"
  rc=$(st_dir live "$ST/live" --expect-prefix pk_test_ --control "$ctl")
  arm live-under-test-prefix 1 "$rc" live "do not start with pk_test_"

  # 5. secret-literal: an sk_ literal beside a good key -> FAIL rc 1
  mkchunk "$ST/secret" a.js "var o=\"https://${ctl}\";var k=\"${key}\";var s=\"${secret}\";"
  rc=$(st_dir secret "$ST/secret" --expect-prefix pk_test_ --control "$ctl")
  arm secret-literal 1 "$rc" secret "secrets=1"

  # 6. two-distinct-keys -> FAIL rc 1 (the same key twice is still ONE distinct key: see 1/9)
  mkchunk "$ST/two" a.js "var o=\"https://${ctl}\";var k=\"${key}\";var j=\"${key}\";"
  mkchunk "$ST/two" b.js "var k2=\"${key2}\";"
  rc=$(st_dir two "$ST/two" --expect-prefix pk_test_ --control "$ctl")
  arm two-distinct-keys 1 "$rc" two "distinct_pk=2"

  # 7. control-absent: a key but no control -> VOID rc 2 (a blind scan never passes)
  mkchunk "$ST/noctl" a.js "var k=\"${key}\";"
  rc=$(st_dir noctl "$ST/noctl" --expect-prefix pk_test_ --control "$ctl")
  arm control-absent 2 "$rc" noctl "VOID"

  # 8. fetch-failure: the page exists and names a chunk that does not -> VOID rc 2; and no page at all -> VOID rc 2
  mkdir -p "$ST/fetch/shop/s"
  printf '%s\n' '<html><script src="/_next/static/chunks/missing.js"></script></html>' > "$ST/fetch/shop/s/checkout"
  rc=$(st_url fetchchunk "$ST/fetch" /shop/s/checkout --expect-prefix pk_test_ --control "$ctl"); why=""
  [ "$rc" = 2 ] || why="missing chunk rc=$rc (want 2)"
  rc=$(st_url fetchpage "$ST/fetch" /shop/other/checkout --expect-prefix pk_test_ --control "$ctl")
  [ -z "$why" ] && [ "$rc" != 2 ] && why="missing page rc=$rc (want 2)"
  if [ -z "$why" ]; then result "PASS fetch-failure rc=2"; else result "FAIL fetch-failure $why"; FAILS=$((FAILS + 1)); fi

  # 9. html-mode: the key-present and key-absent fixtures served through --url. One chunk is named
  #    by a script tag, the other only in the RSC payload's escaped form; both must be fetched.
  mkdir -p "$ST/web/shop/s" "$ST/web/_next/static/chunks"
  cp "$ST/present/a.js" "$ST/web/_next/static/chunks/a.js"
  cp "$ST/present/b.js" "$ST/web/_next/static/chunks/b.js"
  printf '%s\n' '<html><script src="/_next/static/chunks/a.js" async=""></script>' \
    '<script>self.__next_f.push([1,"0:[\"$\",\"script\",{\"src\":\"/_next/static/chunks/b.js\"}]"])</script></html>' \
    > "$ST/web/shop/s/checkout"
  rc=$(st_url htmlok "$ST/web" /shop/s/checkout --expect-prefix pk_test_ --control "$ctl"); why=""
  [ "$rc" = 0 ] || why="key-present via --url rc=$rc (want 0): $(tail -n 2 "$ST/htmlok.out" | tr '\n' ' ')"
  [ -z "$why" ] && ! command grep -qF 'chunks=2 ' "$ST/htmlok.out" && why="did not fetch both chunks: $(head -n 1 "$ST/htmlok.out")"
  mkdir -p "$ST/web2/shop/s" "$ST/web2/_next/static/chunks"
  cp "$ST/absent/a.js" "$ST/web2/_next/static/chunks/a.js"
  cp "$ST/web/shop/s/checkout" "$ST/web2/shop/s/checkout"
  printf '%s\n' 'var nothing=1;' > "$ST/web2/_next/static/chunks/b.js"
  rc=$(st_url htmlabsent "$ST/web2" /shop/s/checkout --expect-prefix pk_test_ --control "$ctl")
  [ -z "$why" ] && [ "$rc" != 1 ] && why="key-absent via --url rc=$rc (want 1)"
  if [ -z "$why" ]; then result "PASS html-mode rc=0/1"; else result "FAIL html-mode $why"; FAILS=$((FAILS + 1)); fi

  # 10. no-chunks: an empty directory -> VOID rc 2
  mkdir -p "$ST/empty"
  rc=$(st_dir empty "$ST/empty" --expect-prefix pk_test_ --control "$ctl")
  arm no-chunks 2 "$rc" empty "VOID"

  # 11. no-echo: no arm's output contains any fixture key or secret VALUE (prefix and length only)
  why=""
  for f in "$ST"/*.out; do
    for v in "$key" "$key2" "$live" "$secret"; do
      if command grep -qF -- "$v" "$f"; then why="$(basename "$f") contains a fixture value"; break 2; fi
    done
  done
  # Non-vacuity: the outputs exist and do carry the prefix, so the absence above is about the value.
  [ -z "$why" ] && ! command grep -qF 'pk_test_(length=' "$ST/present.out" && why="present.out does not show the key prefix+length, so the scan above proves nothing"
  if [ -z "$why" ]; then result "PASS no-echo"; else result "FAIL no-echo $why"; FAILS=$((FAILS + 1)); fi

  render_results "$fmt"
  [ "$fmt" = tap ] && printf "# "; echo "SELFTEST: $(( ${#RESULTS[@]} - FAILS ))/${#RESULTS[@]}"
  [ "$FAILS" -eq 0 ] || exit 1
  exit 0
}

MODE=""; BASE=""; PAGE_PATH=""; SCAN_DIR=""; CONTROL=""; EXPECT_PREFIX=""
[ "$#" -gt 0 ] || { echo "usage: $0 --url BASE --path PATH --control S [--expect-prefix P] | --scan-dir DIR --control S [--expect-prefix P] | --selftest" >&2; exit 2; }
while [ "$#" -gt 0 ]; do
  case "$1" in
    --selftest) [ "$#" -eq 1 ] || { echo "USAGE ERROR: --selftest takes no other argument" >&2; exit 2; }; selftest ;;
    -h|--help) usage; exit 0 ;;
    --url) [ -n "${2-}" ] || { echo "USAGE ERROR: --url needs a base URL" >&2; exit 2; }; MODE=url; BASE="${2%/}"; shift 2 ;;
    --path) [ -n "${2-}" ] || { echo "USAGE ERROR: --path needs a path" >&2; exit 2; }; PAGE_PATH="$2"; shift 2 ;;
    --scan-dir) [ -n "${2-}" ] || { echo "USAGE ERROR: --scan-dir needs a directory" >&2; exit 2; }; MODE=dir; SCAN_DIR="$2"; shift 2 ;;
    --control) [ -n "${2-}" ] || { echo "USAGE ERROR: --control needs a non-empty string" >&2; exit 2; }; CONTROL="$2"; shift 2 ;;
    --expect-prefix) [ -n "${2-}" ] || { echo "USAGE ERROR: --expect-prefix needs a value" >&2; exit 2; }; EXPECT_PREFIX="$2"; shift 2 ;;
    *) echo "USAGE ERROR: unexpected argument '$1'" >&2; exit 2 ;;
  esac
done
[ -n "$MODE" ] || { echo "USAGE ERROR: one of --url or --scan-dir is required" >&2; exit 2; }
[ -n "$CONTROL" ] || { echo "USAGE ERROR: --control is required (a string known to be inlined, e.g. the API origin host)" >&2; exit 2; }
if [ "$MODE" = url ]; then
  [ -n "$PAGE_PATH" ] || { echo "USAGE ERROR: --url needs --path /shop/<slug>/checkout" >&2; exit 2; }
  case "$PAGE_PATH" in /*) ;; *) echo "USAGE ERROR: --path must start with /" >&2; exit 2 ;; esac
fi
check
