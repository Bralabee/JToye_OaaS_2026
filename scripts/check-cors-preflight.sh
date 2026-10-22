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
#     a. Access-Control-Allow-Origin equal to ORIGIN;
#     b. Access-Control-Allow-Headers present, not "*" (invalid with credentials), and naming
#        every header in REQUIRE (case-insensitive, comma-separated, trimmed).
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
#   1  it does not (a header missing, a wildcard, no allow-headers, the wrong or no ACAO)
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

# header_value NAME FILE — the last value of a response header, case-insensitive, CR stripped.
header_value() {
  awk -v n="$(tr '[:upper:]' '[:lower:]' <<< "$1")" '
    { sub(/\r$/, "") }
    { i = index($0, ":"); if (i == 0) next
      k = tolower(substr($0, 1, i - 1)); v = substr($0, i + 1); sub(/^[[:space:]]+/, "", v); sub(/[[:space:]]+$/, "", v)
      if (k == n) { val = v; found = 1 } }
    END { if (found) print val; exit !found }' "$2"
}

# judge FILE ORIGIN REQUIRE — prints one line per finding; returns 0 / 1 / 2.
judge() {
  local file="$1" origin="$2" require="$3" status acao acah list h verdict=0
  local -a req
  [[ -r "$file" ]] || { echo "VOID: cannot read $file"; return 2; }
  status=$(awk '{ sub(/\r$/, "") } /^HTTP\/[0-9.]+ [0-9]{3}/ { s = $2 } END { print s }' "$file")
  [[ -n "$status" ]] || { echo "VOID: no HTTP status line in the response"; return 2; }
  echo "HTTP $status"
  acao=$(header_value Access-Control-Allow-Origin "$file") || acao=""
  if [[ "$acao" == "$origin" ]]; then
    echo "OK   access-control-allow-origin: $acao"
  else
    echo "FAIL access-control-allow-origin: '${acao:-<none>}' (want '$origin')"
    verdict=1
  fi
  acah=$(header_value Access-Control-Allow-Headers "$file") || acah=""
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
  # arm name | expected rc | response header dump
  printf 'HTTP/2 200\r\naccess-control-allow-headers: %s\r\naccess-control-allow-origin: %s\r\n\r\n' "$old" "$o" > "$t/today"
  printf 'HTTP/2 200\r\nAccess-Control-Allow-Headers: %s\r\nAccess-Control-Allow-Origin: %s\r\n\r\n' "$new" "$o" > "$t/fixed"
  printf 'HTTP/2 200\r\naccess-control-allow-headers: %s\r\naccess-control-allow-origin: %s\r\n\r\n' "content-type, idempotency-key , AUTHORIZATION,x-tenant-id" "$o" > "$t/spaced"
  printf 'HTTP/2 200\r\naccess-control-allow-headers: *\r\naccess-control-allow-origin: %s\r\n\r\n' "$o" > "$t/wildcard"
  printf 'HTTP/2 200\r\naccess-control-allow-origin: %s\r\n\r\n' "$o" > "$t/no-acah"
  printf 'HTTP/2 200\r\naccess-control-allow-headers: %s\r\n\r\n' "$new" > "$t/no-acao"
  printf 'HTTP/2 200\r\naccess-control-allow-headers: %s\r\naccess-control-allow-origin: https://evil.example\r\n\r\n' "$new" > "$t/wrong-acao"
  printf 'HTTP/2 200\r\naccess-control-allow-headers: %s\r\naccess-control-allow-origin: %s\r\n\r\n' "Content-Type,Authorization,X-Idempotency-Key,X-Tenant-Id" "$o" > "$t/lookalike"
  : > "$t/empty"
  for arm in today:1 fixed:0 spaced:0 wildcard:1 no-acah:1 no-acao:1 wrong-acao:1 lookalike:1 empty:2; do
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
