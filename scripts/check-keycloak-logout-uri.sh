#!/usr/bin/env bash
# check-keycloak-logout-uri.sh — does the target Keycloak realm ACCEPT the
# post_logout_redirect_uri this deployment's vendor sign-out will send?
#
# WHY THIS EXISTS (QA council 20260902-134741, FE-1 / owner design E-5 point 2)
#
#   FE-1 moves the vendor sign-out's Keycloak return leg to
#   /api/vendor-auth/logout-complete, the route that ends the Auth.js session
#   SERVER-SIDE in the last response the browser processes. That URI has to be
#   REGISTERED on the vendor client of the realm being signed out of, and the
#   consequence of getting it wrong is not cosmetic. Measured on this stack (#504):
#   an end-session request carrying an UNREGISTERED post_logout_redirect_uri makes
#   Keycloak answer 400 WITHOUT terminating the SSO session — the app cookie is
#   gone, the IdP session is alive, and the next person to press "Sign in with
#   Keycloak" on that device is signed in as the departed vendor. A working
#   sign-out turned back into the P0 it replaced, by a redirect URI.
#
#   The compose realm (infra/keycloak/realm-export.template.json, client core-api)
#   registers http://localhost:3000/* with post.logout.redirect.uris "+", so the
#   URI is valid there and this script is green against compose today. The
#   deployed staging and production realms are EXTERNAL and not verifiable from
#   this repository — which is why FE-1 ships behind VENDOR_LOGOUT_COMPLETE_ENABLED
#   ("true" in compose, "false" in every k8s overlay) and why this script runs in
#   the deploy jobs against the deployed realm before the flag may be flipped.
#
#   Open #299 is the same deployed-realm blind spot from the other side: the
#   CUSTOMER realm (jtoye-customers / storefront-client) is unconfigured in every
#   k8s environment, so nothing here can be said about its redirect URIs at all.
#   This script checks ONE client of ONE realm per run; point it at the customer
#   realm with KC_ISSUER/KC_CLIENT_ID/POST_LOGOUT_REDIRECT_URI once #299 is closed.
#
# WHAT IT ASSERTS (three arms, all three required — one passing arm proves nothing)
#
#   PASS arm     GET <end_session_endpoint>?client_id=<C>&post_logout_redirect_uri=<URI>
#                must answer 302 with Location == <URI>. Keycloak validates the URI
#                against the client's registered redirect set BEFORE it looks for a
#                session, so with no session cookie at all a registered URI still
#                302s straight back to it — measured 2026-09-02 on Keycloak 24.0.5.
#   CONTROL 1    the same request with a GARBAGE HOST substituted into the URI must
#                answer 400.
#   CONTROL 2    the same request with a WRONG PORT substituted into the URI must
#                answer 400.
#                The controls are derived from the CONFIGURED URI, not typed
#                separately, so they stay controls when the URI changes. If either
#                control does NOT 400, the realm is accepting arbitrary redirect
#                targets and the PASS arm was vacuous — that is a FAIL, not a pass.
#
#   The `client_id` (no `id_token_hint`) shape is used deliberately: it needs no
#   live session and no minted token, so it can run from a CI runner. The URI check
#   Keycloak performs is per-client in both shapes. The id_token_hint shape is
#   exercised by the browser probe on a live session (fe-signout-repeat.js), not
#   by this script.
#
# CONFIGURATION (env; defaults are the compose stack)
#
#   KC_ISSUER                   full realm URL, e.g. https://auth.example/realms/jtoye-prod
#                               (wins over KC_BASE_URL + KC_REALM when set)
#   KC_BASE_URL                 default http://localhost:8085
#   KC_REALM                    default jtoye-dev
#   KC_CLIENT_ID                default core-api
#   POST_LOGOUT_REDIRECT_URI    default http://localhost:3000/api/vendor-auth/logout-complete
#   CURL_TIMEOUT_S              default 15
#
# SEARCH DISCIPLINE: no `rg` in here — inside a `bash script.sh` it is not a binary
# (it is a shell function the harness injects) and dies rc=127, indistinguishable
# from "no match". Only curl, jq, grep and bash builtins are used.
#
# Requires: bash >= 4, curl, jq.
# Exit codes: 0 = accepted (PASS 302 + both controls 400)
#             1 = the realm refused the URI, or a control did not 400
#             2 = VOID — curl/jq missing, realm unreachable, discovery not 200,
#                 end_session_endpoint absent, unparseable URI, or an arm that
#                 returned no HTTP status at all. A 2 is never a pass.

set -uo pipefail

KC_ISSUER="${KC_ISSUER:-}"
KC_BASE_URL="${KC_BASE_URL:-http://localhost:8085}"
KC_REALM="${KC_REALM:-jtoye-dev}"
KC_CLIENT_ID="${KC_CLIENT_ID:-core-api}"
POST_LOGOUT_REDIRECT_URI="${POST_LOGOUT_REDIRECT_URI:-http://localhost:3000/api/vendor-auth/logout-complete}"
CURL_TIMEOUT_S="${CURL_TIMEOUT_S:-15}"

void() { echo "VOID: $*" >&2; exit 2; }
fail() { echo "FAIL: $*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || void "curl not found"
command -v jq   >/dev/null 2>&1 || void "jq not found"

[ -n "$KC_ISSUER" ] || KC_ISSUER="${KC_BASE_URL%/}/realms/${KC_REALM}"
KC_ISSUER="${KC_ISSUER%/}"

urlenc() { jq -rn --arg v "$1" '$v | @uri'; }

# --- 0. discovery: the end-session endpoint comes from the realm, never composed -------
disc_out="$(curl -sS -m "$CURL_TIMEOUT_S" -w $'\n%{http_code}' "$KC_ISSUER/.well-known/openid-configuration" 2>&1)"; disc_rc=$?
[ "$disc_rc" -eq 0 ] || void "realm unreachable: curl rc=$disc_rc for $KC_ISSUER/.well-known/openid-configuration ($(printf '%s' "$disc_out" | head -c 200))"
disc_code="$(printf '%s\n' "$disc_out" | tail -n 1)"
disc_body="$(printf '%s\n' "$disc_out" | head -n -1)"
[ "$disc_code" = "200" ] || void "discovery document answered HTTP $disc_code (expected 200) at $KC_ISSUER"
END_SESSION="$(printf '%s' "$disc_body" | jq -r '.end_session_endpoint // empty' 2>/dev/null)"; jq_rc=$?
[ "$jq_rc" -eq 0 ] || void "discovery document is not JSON"
[ -n "$END_SESSION" ] || void "discovery document carries no end_session_endpoint"

# --- 1. derive the two controls from the CONFIGURED uri ---------------------------------
# One JSON object, then one field per jq read. NOT a tab-separated line: `read`
# under IFS=$'\t' collapses consecutive tabs, so an EMPTY port field shifts the
# path into the port variable — measured on the first run of this script with a
# port-less URI, and a documented trap on this machine.
parts="$(jq -cn --arg u "$POST_LOGOUT_REDIRECT_URI" '
  $u | capture("^(?<scheme>[a-zA-Z][a-zA-Z0-9+.-]*)://(?<host>[^/:?#]+)(:(?<port>[0-9]+))?(?<rest>[/?#].*)?$")
     | {scheme, host, port: (.port // ""), rest: (.rest // "/")}' 2>/dev/null)"; parts_rc=$?
[ "$parts_rc" -eq 0 ] && [ -n "$parts" ] || void "POST_LOGOUT_REDIRECT_URI is not an absolute http(s) URL: '$POST_LOGOUT_REDIRECT_URI'"
u_scheme="$(jq -r '.scheme' <<< "$parts")"
u_host="$(jq -r '.host' <<< "$parts")"
u_port="$(jq -r '.port' <<< "$parts")"
u_rest="$(jq -r '.rest' <<< "$parts")"
[ -n "$u_scheme" ] && [ -n "$u_host" ] || void "could not split '$POST_LOGOUT_REDIRECT_URI' into scheme/host"

if [ -n "$u_port" ]; then wrong_port=$((u_port + 1)); else wrong_port=3999; fi
GARBAGE_HOST_URI="${u_scheme}://logout-uri-control.invalid${u_port:+:$u_port}${u_rest}"
WRONG_PORT_URI="${u_scheme}://${u_host}:${wrong_port}${u_rest}"

# --- 2. the three arms -------------------------------------------------------------------
probe() { # $1 = uri  -> prints "<http_code> <redirect_url>"; rc = curl rc
  curl -sS -m "$CURL_TIMEOUT_S" -o /dev/null -w '%{http_code} %{redirect_url}' \
    "${END_SESSION}?client_id=$(urlenc "$KC_CLIENT_ID")&post_logout_redirect_uri=$(urlenc "$1")"
}

arm() { # $1 = label, $2 = uri, $3 = expected code, $4 = expected redirect (or "")
  local out rc code loc
  out="$(probe "$2")"; rc=$?
  [ "$rc" -eq 0 ] || void "$1: curl rc=$rc against $END_SESSION"
  code="${out%% *}"; loc="${out#* }"
  [ -n "$code" ] && [ "$code" != "000" ] || void "$1: no HTTP status returned"
  if [ "$code" = "$3" ] && { [ -z "$4" ] || [ "$loc" = "$4" ]; }; then
    echo "  ok    $1: HTTP $code${loc:+ -> $loc}"
    return 0
  fi
  echo "  BAD   $1: HTTP $code${loc:+ -> $loc} (expected $3${4:+ -> $4})" >&2
  return 1
}

echo "check-keycloak-logout-uri  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  realm    : $KC_ISSUER"
echo "  client   : $KC_CLIENT_ID"
echo "  endpoint : $END_SESSION"
echo "  uri      : $POST_LOGOUT_REDIRECT_URI"

bad=0
arm "PASS  (configured uri)" "$POST_LOGOUT_REDIRECT_URI" 302 "$POST_LOGOUT_REDIRECT_URI" || bad=1
arm "CTRL1 (garbage host)"   "$GARBAGE_HOST_URI"          400 ""                          || bad=1
arm "CTRL2 (wrong port)"     "$WRONG_PORT_URI"            400 ""                          || bad=1

if [ "$bad" -ne 0 ]; then
  fail "realm $KC_ISSUER / client $KC_CLIENT_ID does not cleanly accept $POST_LOGOUT_REDIRECT_URI — do NOT enable VENDOR_LOGOUT_COMPLETE_ENABLED for this environment (an unregistered URI makes Keycloak 400 without ending the SSO session)."
fi
echo "PASS: $KC_CLIENT_ID on $KC_ISSUER accepts $POST_LOGOUT_REDIRECT_URI (302) and refuses the two controls (400)."
exit 0
