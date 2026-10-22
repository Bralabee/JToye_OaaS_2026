#!/usr/bin/env bash
# check-cors-preflight.sh — the LIVE api edge admits the headers the browser sends (issue #1025).
#
# WHY THIS EXISTS
#
#   Staging's edge (Traefik reading the ingress-nginx annotations) answers a CORS preflight
#   from the Ingress's own cors-allow-headers list and never consults core-java. Base carried
#   ingress-nginx's DEFAULT list, which has no Idempotency-Key, so every browser request that
#   carries it failed its preflight on https://app-staging.olajay.co.uk and never reached
#   core: every storefront order POST was dead. INV-24 in k8s/scripts/check-render-invariants.sh
#   proves the RENDER lists every browser header; only a request to the real host proves the
#   edge ANSWERS with it. This script sends that request, read-only.
#
# WHAT IT ASSERTS
#
#   An OPTIONS preflight to URL from ORIGIN, asking for POST with REQUIRE as
#   Access-Control-Request-Headers, is answered with:
#     a. a 2xx status (a browser treats any other preflight status as a CORS failure);
#     b. exactly ONE Access-Control-Allow-Origin, equal to ORIGIN;
#     c. Access-Control-Allow-Credentials exactly `true` (the dashboard and storefront send
#        credentials, and the browser refuses a credentialed response without it);
#     d. Access-Control-Allow-Methods naming POST (the method asked for), or `*` is refused;
#     e. exactly ONE Access-Control-Allow-Headers, not "*" (invalid with credentials), naming
#        every header in REQUIRE (case-insensitive, comma-separated, trimmed).
#   A repeated ACAO or ACAH line is a FAIL, not "last one wins": a browser joins repeated
#   values into one list, which never equals a single origin (review round 1, finding 3).
#
# USAGE
#
#   scripts/check-cors-preflight.sh                      live staging, defaults below
#   scripts/check-cors-preflight.sh --url U --origin O --require h1,h2
#   scripts/check-cors-preflight.sh --from-file F        judge a saved response-header dump
#                                                        (curl -D) instead of curling
#   scripts/check-cors-preflight.sh --selftest           the judge against synthetic dumps
#
#   Defaults: URL     https://api-staging.olajay.co.uk/api/v1/public/shops/staging-test-kitchen/orders
#             ORIGIN  https://app-staging.olajay.co.uk
#             REQUIRE content-type,authorization,idempotency-key,x-tenant-id
#   (the four non-safelisted headers the browser sends cross-origin; INV-24 derives the same
#   set from frontend/ on every CI run).
#
# EXIT
#
#   0  the preflight admits every required header from ORIGIN
#   1  it does not (a non-2xx status, a header missing, a wildcard, no or repeated allow-headers,
#      the wrong, no or repeated ACAO, no credentials true, POST not allowed)
#   2  VOID: curl missing or failed, no HTTP status line, an unreadable --from-file, bad usage
#
# WHERE IT RUNS
#
#   The live check runs in the `staging-edge-probe` job of .github/workflows/ci-cd.yaml after
#   every successful staging deploy (read-only, public host, no credential). --selftest runs
#   in k8s-validate on every change.

set -uo pipefail

URL="https://api-staging.olajay.co.uk/api/v1/public/shops/staging-test-kitchen/orders"
ORIGIN="https://app-staging.olajay.co.uk"
REQUIRE="content-type,authorization,idempotency-key,x-tenant-id"
FROM_FILE=""
CURL_TIMEOUT=20

# header_values NAME FILE — every value of a response header, one per line, case-insensitive,
# CR stripped. The caller decides what more than one means; nothing here picks one silently.
header_values() {
  awk -v n="$(tr '[:upper:]' '[:lower:]' <<< "$1")" '
    { sub(/\r$/, "") }
    { i = index($0, ":"); if (i == 0) next
      k = tolower(substr($0, 1, i - 1)); v = substr($0, i + 1); sub(/^[[:space:]]+/, "", v); sub(/[[:space:]]+$/, "", v)
      if (k == n) print v }' "$2"
}

# single NAME FILE — sets SINGLE to the header's only value and returns 0; returns 1 (with
# SINGLE empty) when it is absent and 3 when it is repeated.
single() {
  local vals n
  vals=$(header_values "$1" "$2")
  SINGLE=""
  [[ -n "$vals" ]] || return 1
  n=$(awk 'END { print NR }' <<< "$vals")
  (( n == 1 )) || return 3
  SINGLE="$vals"
}

# judge FILE ORIGIN REQUIRE — prints one line per finding; returns 0 / 1 / 2.
judge() {
  local file="$1" origin="$2" require="$3" status acao acac acam acah list h verdict=0 rc
  local -a req
  [[ -r "$file" ]] || { echo "VOID: cannot read $file"; return 2; }
  status=$(awk '{ sub(/\r$/, "") } /^HTTP\/[0-9.]+ [0-9]{3}/ { s = $2 } END { print s }' "$file")
  [[ -n "$status" ]] || { echo "VOID: no HTTP status line in the response"; return 2; }
  if [[ "$status" == 2[0-9][0-9] ]]; then
    echo "OK   HTTP $status"
  else
    echo "FAIL HTTP $status: a non-2xx preflight fails CORS in the browser whatever its headers say"
    verdict=1
  fi

  single Access-Control-Allow-Origin "$file"; rc=$?; acao="$SINGLE"
  if (( rc == 3 )); then
    echo "FAIL access-control-allow-origin is repeated ($(header_values Access-Control-Allow-Origin "$file" | paste -sd'|')): a browser rejects multiple values"
    verdict=1
  elif [[ "$acao" == "$origin" ]]; then
    echo "OK   access-control-allow-origin: $acao"
  else
    echo "FAIL access-control-allow-origin: '${acao:-<none>}' (want '$origin')"
    verdict=1
  fi

  single Access-Control-Allow-Credentials "$file"; rc=$?; acac="$SINGLE"
  if (( rc == 0 )) && [[ "$acac" == "true" ]]; then
    echo "OK   access-control-allow-credentials: true"
  else
    echo "FAIL access-control-allow-credentials: '${acac:-<none or repeated>}' (want exactly 'true'; a credentialed request is refused without it)"
    verdict=1
  fi

  single Access-Control-Allow-Methods "$file"; rc=$?; acam="$SINGLE"
  list=",$(tr -d ' ' <<< "${acam^^}"),"
  if (( rc == 0 )) && [[ "$list" == *",POST,"* ]]; then
    echo "OK   access-control-allow-methods: $acam (admits POST)"
  else
    echo "FAIL access-control-allow-methods: '${acam:-<none or repeated>}' does not name POST, the method the order request uses"
    verdict=1
  fi

  single Access-Control-Allow-Headers "$file"; rc=$?; acah="$SINGLE"
  if (( rc == 3 )); then
    echo "FAIL access-control-allow-headers is repeated: a browser does not merge it reliably; the edge must send one list"
    return 1
  fi
  if [[ -z "$acah" ]]; then
    echo "FAIL access-control-allow-headers: <none>"
    return 1
  fi
  echo "     access-control-allow-headers: $acah"
  list=",$(tr -d ' ' <<< "${acah,,}"),"
  if [[ "$list" == *",*,"* ]]; then
    echo "FAIL access-control-allow-headers is a wildcard; a credentialed request rejects it"
    return 1
  fi
  IFS=',' read -r -a req <<< "${require// /}"
  for h in "${req[@]}"; do
    [[ -n "$h" ]] || continue
    if [[ "$list" == *",${h,,},"* ]]; then
      echo "OK   admits ${h,,}"
    else
      echo "FAIL omits ${h,,}: a browser request carrying it fails the preflight (issue #1025)"
      verdict=1
    fi
  done
  return "$verdict"
}

selftest() {
  local t fails=0 arm want rc out
  t=$(mktemp -d) || { echo "VOID: mktemp -d failed" >&2; return 2; }
  # shellcheck disable=SC2064
  trap "rm -rf '$t'" RETURN
  local o="https://app-staging.olajay.co.uk"
  local old="DNT,Keep-Alive,User-Agent,X-Requested-With,If-Modified-Since,Cache-Control,Content-Type,Range,Authorization"
  local new="$old,Idempotency-Key,X-Tenant-Id"
  local m="GET,POST,PUT,DELETE,OPTIONS,PATCH"
  # d STATUS ACAH ACAO [extra lines...] — a response dump with credentials true and methods $m
  d() {
    local st="$1" ah="$2" ao="$3"; shift 3
    printf 'HTTP/2 %s \r\naccess-control-allow-credentials: true\r\naccess-control-allow-methods: %s\r\n' "$st" "$m"
    [[ -n "$ah" ]] && printf 'access-control-allow-headers: %s\r\n' "$ah"
    [[ -n "$ao" ]] && printf 'access-control-allow-origin: %s\r\n' "$ao"
    local x; for x in "$@"; do printf '%s\r\n' "$x"; done
    printf '\r\n'
  }
  d 200 "$old" "$o" > "$t/today"
  d 200 "$new" "$o" | sed 's/^access-control-allow-headers/Access-Control-Allow-Headers/; s/^access-control-allow-origin/Access-Control-Allow-Origin/' > "$t/fixed"
  d 204 "$new" "$o" > "$t/fixed-204"
  d 200 "content-type, idempotency-key , AUTHORIZATION,x-tenant-id" "$o" > "$t/spaced"
  d 200 "*" "$o" > "$t/wildcard"
  d 200 "" "$o" > "$t/no-acah"
  d 200 "$new" "" > "$t/no-acao"
  d 200 "$new" "https://evil.example" > "$t/wrong-acao"
  d 200 "Content-Type,Authorization,X-Idempotency-Key,X-Tenant-Id" "$o" > "$t/lookalike"
  # review round 1: finding 1 (status), 2 (methods, credentials), 3 (repeated ACAO / ACAH)
  d 403 "$new" "$o" > "$t/status-403"
  d 302 "$new" "$o" > "$t/status-302"
  d 200 "$new" "$o" | sed 's/^access-control-allow-methods: .*/access-control-allow-methods: GET,OPTIONS\r/' > "$t/no-post"
  d 200 "$new" "$o" | grep -v '^access-control-allow-credentials' > "$t/no-credentials"
  d 200 "$new" "$o" | sed 's/^access-control-allow-credentials: true/access-control-allow-credentials: false/' > "$t/credentials-false"
  d 200 "$new" "*" "access-control-allow-origin: $o" > "$t/dup-acao"
  d 200 "Content-Type" "$o" "access-control-allow-headers: $new" > "$t/dup-acah"
  : > "$t/empty"
  for arm in today:1 fixed:0 fixed-204:0 spaced:0 wildcard:1 no-acah:1 no-acao:1 wrong-acao:1 lookalike:1 \
             status-403:1 status-302:1 no-post:1 no-credentials:1 credentials-false:1 dup-acao:1 dup-acah:1 empty:2; do
    want="${arm##*:}"; arm="${arm%%:*}"
    out=$(judge "$t/$arm" "$o" "$REQUIRE"); rc=$?
    if [[ "$rc" == "$want" ]]; then
      echo "ok   $arm rc=$rc"
    else
      echo "FAIL $arm: expected rc $want, got rc $rc"; sed 's/^/       /' <<< "$out"
      fails=$((fails + 1))
    fi
  done
  # a lone requirement: the exact post-deploy question for issue #1025
  out=$(judge "$t/today" "$o" "idempotency-key"); rc=$?
  if [[ "$rc" == 1 ]]; then echo "ok   today-idempotency-only rc=1"; else echo "FAIL today-idempotency-only: rc $rc"; fails=$((fails + 1)); fi
  out=$(judge "$t/fixed" "$o" "idempotency-key"); rc=$?
  if [[ "$rc" == 0 ]]; then echo "ok   fixed-idempotency-only rc=0"; else echo "FAIL fixed-idempotency-only: rc $rc"; fails=$((fails + 1)); fi
  if (( fails > 0 )); then echo "SELFTEST FAIL: $fails arm(s)"; return 1; fi
  echo "SELFTEST PASS: every arm judged as expected"
}

while (( $# > 0 )); do
  case "$1" in
    --selftest) selftest; exit $? ;;
    --url) URL="${2:?--url needs a value}"; shift 2 ;;
    --origin) ORIGIN="${2:?--origin needs a value}"; shift 2 ;;
    --require) REQUIRE="${2:?--require needs a value}"; shift 2 ;;
    --from-file) FROM_FILE="${2:?--from-file needs a value}"; shift 2 ;;
    -h|--help) sed -n '2,45p' "$0"; exit 0 ;;
    *) echo "VOID: unknown argument $1" >&2; exit 2 ;;
  esac
done

if [[ -n "$FROM_FILE" ]]; then
  echo "judging $FROM_FILE (origin $ORIGIN, require $REQUIRE)"
  judge "$FROM_FILE" "$ORIGIN" "$REQUIRE"; rc=$?
  exit "$rc"
fi

command -v curl > /dev/null 2>&1 || { echo "VOID: curl is not installed" >&2; exit 2; }
tmp=$(mktemp) || { echo "VOID: mktemp failed" >&2; exit 2; }
trap 'rm -f "$tmp"' EXIT
echo "OPTIONS $URL"
echo "  Origin: $ORIGIN"
echo "  Access-Control-Request-Method: POST"
echo "  Access-Control-Request-Headers: ${REQUIRE,,}"
curl -s -o /dev/null -D "$tmp" --max-time "$CURL_TIMEOUT" -X OPTIONS "$URL" \
  -H "Origin: $ORIGIN" -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: ${REQUIRE,,}"; crc=$?
if (( crc != 0 )); then echo "VOID: curl rc=$crc"; exit 2; fi
judge "$tmp" "$ORIGIN" "$REQUIRE"; rc=$?
case "$rc" in
  0) echo "PASS: the edge admits every required header from $ORIGIN" ;;
  1) echo "FAIL: the edge refuses at least one required header from $ORIGIN" ;;
  *) echo "VOID: the preflight response could not be judged" ;;
esac
exit "$rc"
