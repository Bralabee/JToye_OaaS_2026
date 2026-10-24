#!/usr/bin/env bash
# check-staging-edge.sh — the LIVE staging edge does what its annotations say (plan 29-15,
# decision D-29(b)).
#
# ---------------------------------------------------------------------------------------
# WHY THIS EXISTS
#
#   Staging's ingress controller is Traefik v3 reading the `nginx.ingress.kubernetes.io/*`
#   annotations through its Ingress-NGINX provider (D-29). The render gates prove the
#   annotations are WRITTEN; they cannot prove Traefik HONOURS them. Plan 29-03 measured, in
#   the pinned source, two ways the translation silently drops behaviour (snippet annotations
#   off skips the whole Ingress; proxy-body-size is inert without request buffering). So the
#   only evidence that the security headers, CORS, the rate limit and the Keycloak admin-path
#   restriction are real is a request to the real host. This script makes those requests.
#
# THE ARMS (each PASS / FAIL / VOID)
#
#   headers  app-staging / and api-staging carry Strict-Transport-Security, X-Frame-Options,
#            X-Content-Type-Options, Referrer-Policy, Permissions-Policy and an X-Robots-Tag
#            containing noindex (D-08 applied to crawlers, T-29-29).
#   csp      app-staging's Content-Security-Policy admits the realm endpoints the browser
#            talks to (https://auth-staging.../realms/jtoye-staging/protocol/openid-connect/
#            auth and .../token) in BOTH connect-src and form-action (#293). The match follows
#            CSP3 source matching: a source with a path matches that path EXACTLY unless it
#            ends in "/", in which case it matches the subtree; a source with no path matches
#            every path on its host.
#   cors     a preflight from https://app-staging.olajay.co.uk to api-staging is answered with
#            Access-Control-Allow-Origin equal to it and Allow-Credentials true; the same
#            preflight from https://evil.example is NOT echoed (no ACAO, or one that is
#            neither the foreign origin nor "*").
#   burst    60 requests at 10/s on one connection get zero 429s; then 1500 requests at
#            concurrency 40 on one HTTP/2 connection get at least one 429 from the EDGE (a
#            non-problem+json body) and no 503. Every 429 is classified: edge (Traefik) or
#            app (core-java's own limiter, application/problem+json). The burst targets
#            api-staging /api/v1/edge-burst-probe, a path no controller serves: without a
#            token Spring Security answers 401 in its filter chain, before core-java's
#            RateLimitInterceptor, so the app limiter is not in the path and a 429 can only
#            be the edge's. It must also be FAST: the bucket starts full at
#            limit-rps x limit-burst-multiplier (500) and refills at 100/s, so 1500 requests
#            draw a 429 only above ~150 rps. app-staging /robots.txt was measured first
#            (2026-10-19): the 500m-CPU frontend held concurrency 40 to ~68 rps (1500 in
#            22 s), the bucket never emptied and the arm read 0 x 429 — a statement about
#            the backend, not the edge.
#            Concurrency is 40, not 50, so the run stays under limit-connections (50
#            in-flight per client): a 429 here is the limit-rps bucket, not the in-flight cap.
#   admin    https://auth-staging/admin/ and /admin/master/console/ are 404 from the edge
#            (plan 29-10 routes only /realms/jtoye-staging, /realms/jtoye-customers and
#            /resources/), while the realm's .well-known/openid-configuration is 200 with
#            issuer https://auth-staging.olajay.co.uk/realms/jtoye-staging.
#
# --fail-arms: SHOWING EVERY ARM CAN FAIL
#
#   An arm only ever observed passing may be incapable of failing. --fail-arms creates a
#   scratch route in namespace jtoye-staging — Pod, Service, NetworkPolicy and Ingress, all
#   named `fidelity-probe` — whose backend is agnhost netexec and whose Ingress carries NO
#   annotation at all (no snippet, no CORS, no limits). It reuses the three existing TLS
#   Secrets and carries no cert-manager annotation, so no Certificate is requested. Routes:
#
#       app-staging  /fidelity-probe  -> headers, csp arms
#       api-staging  /fidelity-probe  -> headers, cors, burst arms
#       auth-staging /admin           -> admin arm (an /admin route that answers 200)
#
#   Every arm is run against those routes and MUST report FAIL. An arm that PASSES there is
#   reported `not ok` (it cannot fail) and the run exits 1. The probe is then deleted BY NAME
#   and its absence is verified; a probe still present is also exit 1. Before any arm runs,
#   each probe route must answer with agnhost's own "NOW:" body, so an arm can never be
#   pointed at the frontend by a route that has not propagated yet.
#
# USAGE
#
#   scripts/check-staging-edge.sh [--skip-burst]                       read-only, no cluster access
#   scripts/check-staging-edge.sh --context jtoye-stg-aks --fail-arms  writes to the cluster
#   EDGE_FORMAT=tap scripts/check-staging-edge.sh ...   (TAP 13 output, one test per arm)
#
#   The read-only run (default, or --skip-burst) only curls the public hosts. It needs no
#   kubeconfig, no kubelogin and no --context (one given is ignored), so a CI job can run it
#   holding no cluster credential. --fail-arms is the only mode that writes to the cluster:
#   it REQUIRES --context, with no default, which passes scripts/lib/staging-kube-guard.sh
#   (the employer's sipbihs2aks is refused by name; the context must point at the staging
#   API server recorded in k8s/staging/azure-identity.yaml).
#
#   --skip-burst  run the four read-only arms (headers, csp, cors, admin) and NOT the burst arm.
#                 The burst arm fires 1500 requests at concurrency 40 and passes only above
#                 ~150 rps, so its verdict is a statement about the client's bandwidth and the
#                 backend's CPU as much as about the edge: timing-sensitive, and wrong for a
#                 shared hosted runner. A run with the flag says so on its PASS line, so it can
#                 never be read as the full five-arm result. Refused together with --fail-arms
#                 (that mode exists to show EVERY arm can fail, the burst arm included).
#
# EXIT
#
#   0  every arm PASS (normal) / every arm FAILED on the probe and the probe is gone
#   1  an arm FAILED (normal) / an arm PASSED on the probe, or the probe was not removed
#   2  VOID: a tool is missing, a host does not resolve, a request did not complete, or a
#      probe route never answered. "Could not evaluate" is never reported as a pass.
#
# WHERE IT RUNS
#
#   The `staging-edge-probe` job in .github/workflows/ci-cd.yaml runs the four read-only arms
#   with --skip-burst after every successful staging deploy. It is a SEPARATE job (needs:
#   deploy-staging), not a step of the deploy job, so a red here never triggers the deploy's
#   rollback of a correct deploy. The script needs only curl, jq and getent, all on a hosted
#   runner image, and the job holds `contents: read` and nothing else: no Azure login, no
#   kubeconfig, no OIDC token, no environment.
#   NOT in CI, by design: the burst arm (timing-sensitive) and --fail-arms (creates and deletes
#   a scratch Ingress in jtoye-staging). Both stay operator-run.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IDENTITY_FILE="$ROOT/k8s/staging/azure-identity.yaml"
NS="jtoye-staging"

API_HOST="api-staging.olajay.co.uk"
APP_HOST="app-staging.olajay.co.uk"
AUTH_HOST="auth-staging.olajay.co.uk"
GRAFANA_HOST="grafana-staging.olajay.co.uk"
STAGING_HOSTS=("$API_HOST" "$APP_HOST" "$AUTH_HOST" "$GRAFANA_HOST")
REALM="jtoye-staging"
ALLOWED_ORIGIN="https://${APP_HOST}"
FOREIGN_ORIGIN="https://evil.example"

PROBE="fidelity-probe"
PROBE_PATH="/fidelity-probe"
AGNHOST_IMAGE="registry.k8s.io/e2e-test-images/agnhost:2.66.1@sha256:6e2eef87d0dc77e070a7549bc5fdfa290c0fefe3359eb33c2ad1eda692fe66a4"

FORMAT="${EDGE_FORMAT:-text}"
CURL_TIMEOUT=20
SUB_N=60
SUB_RATE="10/s"
BURST_N=1500
BURST_PAR=40
ROUTE_DEADLINE=120

usage() {
  sed -n '/^# USAGE/,/^# EXIT/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
}

CONTEXT=""
FAIL_ARMS=0
SKIP_BURST=0
while [ $# -gt 0 ]; do
  case "$1" in
    --context)
      if [ $# -lt 2 ]; then echo "USAGE ERROR: --context needs a value" >&2; exit 2; fi
      CONTEXT="$2"; shift 2 ;;
    --context=*) CONTEXT="${1#--context=}"; shift ;;
    --fail-arms) FAIL_ARMS=1; shift ;;
    --skip-burst) SKIP_BURST=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "USAGE ERROR: unknown argument \"$1\"" >&2; exit 2 ;;
  esac
done

if [ "$FAIL_ARMS" -eq 1 ] && [ "$SKIP_BURST" -eq 1 ]; then
  echo "USAGE ERROR: --skip-burst cannot be combined with --fail-arms (that mode must show every arm, burst included, can fail)" >&2
  exit 2
fi

case "$FORMAT" in text|tap) ;; *) echo "USAGE ERROR: EDGE_FORMAT must be text or tap, got '$FORMAT'" >&2; exit 2 ;; esac

# ---- the context guard (the same implementation every staging script uses) -------------
# Applied ONLY to --fail-arms, the one mode that writes to the cluster. The read-only run
# (default, --skip-burst) only curls the public hosts: it makes no cluster call, so it needs
# no kubeconfig, no kubelogin and no --context, and a CI job running it holds no cluster
# credential at all (review of #989: the probe job was minting a cluster-write token only to
# satisfy this guard).
if [ "$FAIL_ARMS" -eq 1 ]; then
  # shellcheck source=lib/staging-kube-guard.sh
  if ! source "$ROOT/scripts/lib/staging-kube-guard.sh"; then
    echo "VOID: could not source scripts/lib/staging-kube-guard.sh" >&2
    exit 2
  fi
  staging_kube_guard "$CONTEXT" "$IDENTITY_FILE"; guard_rc=$?
  if [ "$guard_rc" -ne 0 ]; then exit "$guard_rc"; fi
fi

for tool in curl jq getent; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "VOID: $tool not on PATH" >&2
    exit 2
  fi
done

for h in "${STAGING_HOSTS[@]}"; do
  resolved=$(getent hosts "$h" 2>/dev/null); grc=$?
  if [ "$grc" -ne 0 ] || [ -z "$resolved" ]; then
    echo "VOID: staging host $h does not resolve (getent rc=$grc); the edge cannot be probed" >&2
    exit 2
  fi
done

# ---- reporting ---------------------------------------------------------------------------
REPORT_N=0
N_FAIL=0
N_VOID=0
REPORT_LINES=()

# report NAME VERDICT(ok|not ok|void) DETAIL
report() {
  local name="$1" verdict="$2" detail="$3" line
  REPORT_N=$((REPORT_N + 1))
  case "$verdict" in
    ok) ;;
    void) N_VOID=$((N_VOID + 1)) ;;
    *) N_FAIL=$((N_FAIL + 1)) ;;
  esac
  if [ "$FORMAT" = "tap" ]; then
    if [ "$verdict" = "ok" ]; then line="ok ${REPORT_N} - ${name}"; else line="not ok ${REPORT_N} - ${name}"; fi
    if [ "$verdict" = "void" ]; then line+=" # VOID"; fi
    line+=$'\n  ---\n  message: '"'$(printf '%s' "$detail" | tr '\n' ' ' | sed "s/'/''/g")'"$'\n  ...'
  else
    case "$verdict" in
      ok) line="PASS  [${name}]" ;;
      void) line="VOID  [${name}]" ;;
      *) line="FAIL  [${name}]" ;;
    esac
    line+=$'\n'"$(printf '%s\n' "$detail" | sed '/^$/d; s/^/        /')"
  fi
  REPORT_LINES+=("$line")
}

# ---- HTTP helpers -----------------------------------------------------------------------
HDRS=""
BODY=""
CODE=""
FETCH_RC=0

# fetch_headers URL [curl args...] -> HDRS, CODE; returns curl's rc (0 = completed)
fetch_headers() {
  local url="$1" out
  shift
  out=$(curl -sS --max-time "$CURL_TIMEOUT" -o /dev/null -D - -w '\n__CODE__%{http_code}\n' "$@" "$url" 2>/dev/null); FETCH_RC=$?
  HDRS="${out%%__CODE__*}"
  CODE="${out##*__CODE__}"; CODE="${CODE//[!0-9]/}"
  return "$FETCH_RC"
}

# fetch_body URL [curl args...] -> BODY, CODE; returns curl's rc
fetch_body() {
  local url="$1" out
  shift
  out=$(curl -sS --max-time "$CURL_TIMEOUT" -w '\n__CODE__%{http_code}' "$@" "$url" 2>/dev/null); FETCH_RC=$?
  BODY="${out%$'\n'__CODE__*}"
  CODE="${out##*__CODE__}"; CODE="${CODE//[!0-9]/}"
  return "$FETCH_RC"
}

# header_value NAME HEADERS -> the first value of NAME (case-insensitive), CR stripped
header_value() {
  local name="${1,,}" line v
  while IFS= read -r line; do
    line="${line%$'\r'}"
    if [[ "${line,,}" == "${name}:"* ]]; then
      v="${line#*:}"
      v="${v#"${v%%[![:space:]]*}"}"
      printf '%s' "$v"
      return 0
    fi
  done <<< "$2"
  return 1
}

# ---- the arms. Each sets ARM_DETAIL and returns 0 PASS, 1 FAIL, 2 VOID ------------------
ARM_DETAIL=""

REQUIRED_HEADERS=(Strict-Transport-Security X-Frame-Options X-Content-Type-Options Referrer-Policy Permissions-Policy X-Robots-Tag)

# arm_headers URL... — every URL carries all six headers, X-Robots-Tag with noindex
arm_headers() {
  local url h v verdict=0 missing
  ARM_DETAIL=""
  for url in "$@"; do
    if ! fetch_headers "$url"; then
      ARM_DETAIL+="curl rc=${FETCH_RC} on ${url}: the request did not complete"
      return 2
    fi
    missing=()
    for h in "${REQUIRED_HEADERS[@]}"; do
      v=$(header_value "$h" "$HDRS") || v=""
      [ -n "$v" ] || missing+=("$h")
    done
    v=$(header_value X-Robots-Tag "$HDRS") || v=""
    if [ -n "$v" ] && [[ "${v,,}" != *noindex* ]]; then missing+=("X-Robots-Tag(noindex; got '${v}')"); fi
    if ((${#missing[@]})); then
      verdict=1
      ARM_DETAIL+="${url} (HTTP ${CODE}): MISSING ${missing[*]}"$'\n'
    else
      ARM_DETAIL+="${url} (HTTP ${CODE}): all six present; X-Robots-Tag: ${v}"$'\n'
    fi
  done
  return "$verdict"
}

# csp_directive NAME CSP -> the directive's source list (space separated); 1 if absent
csp_directive() {
  local name="$1" d
  local -a parts
  IFS=';' read -ra parts <<< "$2"
  for d in "${parts[@]}"; do
    d="${d#"${d%%[![:space:]]*}"}"
    if [ "$d" = "$name" ] || [[ "$d" == "$name "* ]]; then
      printf '%s' "${d#"$name"}"
      return 0
    fi
  done
  return 1
}

# src_matches SOURCE URL -> 0 when the CSP source expression admits URL. CSP3 source
# matching for the shapes a host or scheme source can take: a scheme-source ("https:")
# matches its scheme; a host-source matches scheme (or none), host (exact, or "*." suffix)
# and port (none or 443 for https); its PATH matches EXACTLY unless it ends in "/", where it
# matches the subtree, and a source with no path matches every path. Keywords ('self',
# 'nonce-…') never match: 'self' is app-staging, never auth-staging.
src_matches() {
  local src="$1" url="$2"
  local u_scheme="${url%%://*}" u_rest="${url#*://}"
  local u_host="${u_rest%%/*}" u_path="/"
  [[ "$u_rest" == */* ]] && u_path="/${u_rest#*/}"
  case "$src" in
    "'"*) return 1 ;;
    *://*) ;;
    *:) [ "${src%:}" = "$u_scheme" ]; return ;;
  esac
  local s_scheme="" s_rest="$src"
  if [[ "$src" == *://* ]]; then s_scheme="${src%%://*}"; s_rest="${src#*://}"; fi
  local s_host="${s_rest%%/*}" s_path=""
  [[ "$s_rest" == */* ]] && s_path="/${s_rest#*/}"
  if [ -n "$s_scheme" ] && [ "${s_scheme,,}" != "${u_scheme,,}" ]; then return 1; fi
  case "$s_host" in
    *:443) [ "${u_scheme,,}" = "https" ] || return 1; s_host="${s_host%:443}" ;;
    *:\*) s_host="${s_host%:\*}" ;;
    *:*) return 1 ;;
  esac
  if [[ "$s_host" == "*."* ]]; then
    [[ "${u_host,,}" == *".${s_host#\*.}" ]] || return 1
  else
    [ "${s_host,,}" = "${u_host,,}" ] || return 1
  fi
  [ -z "$s_path" ] && return 0
  if [[ "$s_path" == */ ]]; then [[ "$u_path" == "$s_path"* ]]; return; fi
  [ "$u_path" = "$s_path" ]
}

CSP_TARGETS=(
  "https://${AUTH_HOST}/realms/${REALM}/protocol/openid-connect/auth"
  "https://${AUTH_HOST}/realms/${REALM}/protocol/openid-connect/token"
)

# arm_csp URL — connect-src AND form-action each admit both realm endpoints
arm_csp() {
  local url="$1" csp dir toks t target matched verdict=0
  ARM_DETAIL=""
  if ! fetch_headers "$url"; then
    ARM_DETAIL="curl rc=${FETCH_RC} on ${url}: the request did not complete"
    return 2
  fi
  if ! csp=$(header_value Content-Security-Policy "$HDRS") || [ -z "$csp" ]; then
    ARM_DETAIL="${url} (HTTP ${CODE}): no Content-Security-Policy header"
    return 1
  fi
  ARM_DETAIL+="Content-Security-Policy: ${csp}"$'\n'
  for dir in connect-src form-action; do
    if ! toks=$(csp_directive "$dir" "$csp"); then
      ARM_DETAIL+="${dir}: ABSENT"$'\n'
      verdict=1
      continue
    fi
    for target in "${CSP_TARGETS[@]}"; do
      matched=""
      for t in $toks; do
        if src_matches "$t" "$target"; then matched+="${t} "; fi
      done
      if [ -z "$matched" ]; then
        ARM_DETAIL+="${dir}: NO source admits ${target}"$'\n'
        verdict=1
      else
        ARM_DETAIL+="${dir}: ${target} admitted by ${matched% }"$'\n'
      fi
    done
  done
  return "$verdict"
}

# arm_cors URL — the app origin's preflight is allowed with credentials; a foreign one is not echoed
arm_cors() {
  local url="$1" acao acac verdict=0
  local -a pre=(-X OPTIONS -H "Access-Control-Request-Method: GET" -H "Access-Control-Request-Headers: authorization,content-type")
  ARM_DETAIL=""
  if ! fetch_headers "$url" "${pre[@]}" -H "Origin: ${ALLOWED_ORIGIN}"; then
    ARM_DETAIL="curl rc=${FETCH_RC} on the allowed-origin preflight to ${url}"
    return 2
  fi
  acao=$(header_value Access-Control-Allow-Origin "$HDRS") || acao=""
  acac=$(header_value Access-Control-Allow-Credentials "$HDRS") || acac=""
  if [ "$acao" = "$ALLOWED_ORIGIN" ] && [ "${acac,,}" = "true" ]; then
    ARM_DETAIL+="Origin ${ALLOWED_ORIGIN}: HTTP ${CODE}, ACAO '${acao}', ACAC '${acac}' (allowed with credentials)"$'\n'
  else
    ARM_DETAIL+="Origin ${ALLOWED_ORIGIN}: HTTP ${CODE}, ACAO '${acao:-<none>}', ACAC '${acac:-<none>}' (NOT allowed with credentials)"$'\n'
    verdict=1
  fi
  if ! fetch_headers "$url" "${pre[@]}" -H "Origin: ${FOREIGN_ORIGIN}"; then
    ARM_DETAIL+="curl rc=${FETCH_RC} on the foreign-origin preflight to ${url}"
    return 2
  fi
  acao=$(header_value Access-Control-Allow-Origin "$HDRS") || acao=""
  if [ -z "$acao" ] || { [ "$acao" != "$FOREIGN_ORIGIN" ] && [ "$acao" != "*" ]; }; then
    ARM_DETAIL+="Origin ${FOREIGN_ORIGIN}: HTTP ${CODE}, ACAO '${acao:-<none>}' (refused: not echoed)"$'\n'
  else
    ARM_DETAIL+="Origin ${FOREIGN_ORIGIN}: HTTP ${CODE}, ACAO '${acao}' (ALLOWED: a foreign origin is echoed)"$'\n'
    verdict=1
  fi
  return "$verdict"
}

# arm_burst URL — sub-limit control first (zero 429), then the burst (an edge 429, no 503)
arm_burst() {
  local url="$1" tmp i out rc lines sub429 n429 edge app n503 n2xx other t0 t1
  ARM_DETAIL=""
  tmp=$(mktemp -d) || { ARM_DETAIL="mktemp failed"; return 2; }
  for ((i = 0; i < SUB_N; i++)); do printf 'url = "%s"\noutput = "/dev/null"\n' "$url"; done > "$tmp/sub.cfg"
  for ((i = 0; i < BURST_N; i++)); do printf 'url = "%s"\noutput = "/dev/null"\n' "$url"; done > "$tmp/burst.cfg"

  t0=$SECONDS
  out=$(curl -s --http2 --rate "$SUB_RATE" --max-time "$CURL_TIMEOUT" -w '%{http_code} %{content_type}\n' -K "$tmp/sub.cfg" 2>/dev/null); rc=$?
  t1=$SECONDS
  lines=$(command grep -c '' <<< "$out")
  if [ "$rc" -ne 0 ] || [ "$lines" -ne "$SUB_N" ]; then
    ARM_DETAIL="sub-limit run incomplete: curl rc=${rc}, ${lines}/${SUB_N} responses"
    rm -rf "$tmp"; return 2
  fi
  sub429=$(command grep -c '^429 ' <<< "$out")
  ARM_DETAIL+="sub-limit: ${SUB_N} requests at ${SUB_RATE} in $((t1 - t0))s -> ${sub429} x 429; codes: $(cut -d' ' -f1 <<< "$out" | sort | uniq -c | tr -s ' ' | tr '\n' ';')"$'\n'

  t0=$SECONDS
  out=$(curl -s --http2 --parallel --parallel-max "$BURST_PAR" --max-time 120 -w '%{http_code} %{content_type}\n' -K "$tmp/burst.cfg" 2>/dev/null); rc=$?
  t1=$SECONDS
  rm -rf "$tmp"
  lines=$(command grep -c '' <<< "$out")
  if [ "$lines" -ne "$BURST_N" ]; then
    ARM_DETAIL+="burst run incomplete: curl rc=${rc}, ${lines}/${BURST_N} responses"
    return 2
  fi
  n429=$(command grep -c '^429 ' <<< "$out")
  app=$(command grep -c '^429 application/problem+json' <<< "$out")
  edge=$((n429 - app))
  n503=$(command grep -c '^503 ' <<< "$out")
  n2xx=$(command grep -c '^2[0-9][0-9] ' <<< "$out")
  other=$((BURST_N - n429 - n503 - n2xx))
  ARM_DETAIL+="burst: ${BURST_N} requests, concurrency ${BURST_PAR}, one HTTP/2 connection, $((t1 - t0))s (curl rc=${rc}) -> ${n429} x 429 (edge ${edge}, app ${app}), ${n503} x 503, ${n2xx} x 2xx, ${other} other; 429 content-types: $(command grep '^429 ' <<< "$out" | cut -d' ' -f2- | sort | uniq -c | tr -s ' ' | tr '\n' ';')"$'\n'

  if [ "$sub429" -ne 0 ]; then ARM_DETAIL+="FALSE POSITIVE: the sub-limit rate drew ${sub429} x 429"$'\n'; return 1; fi
  if [ "$edge" -lt 1 ]; then ARM_DETAIL+="NO EDGE 429: the burst was never limited at the edge"$'\n'; return 1; fi
  if [ "$n503" -ne 0 ]; then ARM_DETAIL+="503 under burst: an NGINX-style refusal or an overloaded backend, not the edge's 429"$'\n'; return 1; fi
  ARM_DETAIL+="layer that answered: edge (Traefik) ${edge}, app (core-java) ${app}"$'\n'
  return 0
}

# arm_admin ADMIN_URL — the admin paths are 404 at the edge; the realm's discovery is 200
arm_admin() {
  local admin_url="$1" verdict=0 u issuer
  local want="https://${AUTH_HOST}/realms/${REALM}"
  ARM_DETAIL=""
  for u in "$admin_url" "https://${AUTH_HOST}/admin/master/console/"; do
    if ! fetch_body "$u"; then ARM_DETAIL+="curl rc=${FETCH_RC} on ${u}"; return 2; fi
    if [ "$CODE" = "404" ]; then
      ARM_DETAIL+="${u}: HTTP 404 (not routed)"$'\n'
    else
      ARM_DETAIL+="${u}: HTTP ${CODE} (ROUTED: body starts '$(head -c 40 <<< "$BODY" | tr -d '\n')')"$'\n'
      verdict=1
    fi
  done
  u="https://${AUTH_HOST}/realms/${REALM}/.well-known/openid-configuration"
  if ! fetch_body "$u"; then ARM_DETAIL+="curl rc=${FETCH_RC} on ${u}"; return 2; fi
  issuer=$(jq -r '.issuer // empty' <<< "$BODY" 2>/dev/null) || issuer=""
  if [ "$CODE" = "200" ] && [ "$issuer" = "$want" ]; then
    ARM_DETAIL+="${u}: HTTP 200, issuer ${issuer}"$'\n'
  else
    ARM_DETAIL+="${u}: HTTP ${CODE}, issuer '${issuer:-<none>}', expected 200 and ${want}"$'\n'
    verdict=1
  fi
  return "$verdict"
}

# ---- the scratch probe (only --fail-arms) -----------------------------------------------
PROBE_CREATED=0

kc() { kubectl --context "$CONTEXT" -n "$NS" "$@"; }

probe_apply() {
  kc apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: ${PROBE}
  labels:
    app: ${PROBE}
    app.kubernetes.io/name: ${PROBE}
spec:
  automountServiceAccountToken: false
  enableServiceLinks: false
  securityContext:
    runAsNonRoot: true
    runAsUser: 65532
    runAsGroup: 65532
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: netexec
      image: ${AGNHOST_IMAGE}
      imagePullPolicy: IfNotPresent
      args: ["netexec", "--http-port=8080"]
      ports:
        - name: http
          containerPort: 8080
          protocol: TCP
      readinessProbe:
        httpGet:
          path: /
          port: http
        periodSeconds: 2
      resources:
        requests:
          cpu: 10m
          memory: 16Mi
        limits:
          cpu: 200m
          memory: 64Mi
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        runAsNonRoot: true
        capabilities:
          drop: ["ALL"]
---
apiVersion: v1
kind: Service
metadata:
  name: ${PROBE}
  labels:
    app.kubernetes.io/name: ${PROBE}
spec:
  selector:
    app: ${PROBE}
  ports:
    - name: http
      port: 8080
      targetPort: http
      protocol: TCP
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: ${PROBE}-allow
  labels:
    app.kubernetes.io/name: ${PROBE}-allow
spec:
  podSelector:
    matchLabels:
      app: ${PROBE}
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: traefik
      ports:
        - protocol: TCP
          port: 8080
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ${PROBE}
  labels:
    app.kubernetes.io/name: ${PROBE}
spec:
  ingressClassName: nginx
  tls:
    - hosts: [${APP_HOST}]
      secretName: jtoye-staging-app-tls
    - hosts: [${API_HOST}]
      secretName: jtoye-staging-api-tls
    - hosts: [${AUTH_HOST}]
      secretName: jtoye-staging-auth-tls
  rules:
    - host: ${APP_HOST}
      http:
        paths:
          - path: ${PROBE_PATH}
            pathType: Prefix
            backend:
              service:
                name: ${PROBE}
                port:
                  number: 8080
    - host: ${API_HOST}
      http:
        paths:
          - path: ${PROBE_PATH}
            pathType: Prefix
            backend:
              service:
                name: ${PROBE}
                port:
                  number: 8080
    - host: ${AUTH_HOST}
      http:
        paths:
          - path: /admin
            pathType: Prefix
            backend:
              service:
                name: ${PROBE}
                port:
                  number: 8080
EOF
}

PROBE_KINDS=("ingress/${PROBE}" "service/${PROBE}" "networkpolicy/${PROBE}-allow" "pod/${PROBE}")

probe_delete() {
  local obj
  for obj in "${PROBE_KINDS[@]}"; do
    kc delete "$obj" --ignore-not-found --wait=true --timeout=90s >/dev/null 2>&1
  done
}

# probe_gone -> 0 when every probe object reads NotFound; prints the survivors otherwise
probe_gone() {
  local obj out rc left=()
  for obj in "${PROBE_KINDS[@]}"; do
    out=$(kc get "$obj" -o name 2>&1); rc=$?
    if [ "$rc" -eq 0 ]; then
      left+=("$obj")
    elif [[ "$out" != *NotFound* && "$out" != *"not found"* ]]; then
      left+=("$obj(unreadable: ${out//$'\n'/ })")
    fi
  done
  if ((${#left[@]})); then printf '%s\n' "${left[@]}"; return 1; fi
  return 0
}

on_exit() {
  if [ "$PROBE_CREATED" -eq 1 ]; then probe_delete; fi
}
trap on_exit EXIT
trap 'exit 130' INT TERM

# wait_route URL -> 0 once URL answers with agnhost's "NOW:" body, 1 at the deadline
wait_route() {
  local url="$1" deadline=$((SECONDS + ROUTE_DEADLINE))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if fetch_body "$url" && [[ "$BODY" == NOW:* ]]; then return 0; fi
    sleep 2
  done
  return 1
}

# ---- run ----------------------------------------------------------------------------------
STAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)
MODE="normal"; [ "$FAIL_ARMS" -eq 1 ] && MODE="fail-arms"
CTX_LABEL="$CONTEXT"; [ "$FAIL_ARMS" -eq 0 ] && CTX_LABEL="none (read-only: public hosts only, no cluster call)"
[ "$SKIP_BURST" -eq 1 ] && MODE="normal,skip-burst"
if [ "$FORMAT" = "tap" ]; then
  echo "TAP version 13"
  if [ "$FAIL_ARMS" -eq 1 ]; then echo "1..6"; elif [ "$SKIP_BURST" -eq 1 ]; then echo "1..4"; else echo "1..5"; fi
  echo "# check-staging-edge ${STAMP} mode=${MODE} context=${CTX_LABEL}"
else
  echo "check-staging-edge  (${STAMP})  mode=${MODE}  context=${CTX_LABEL}"
fi

# run_arm NAME FN ARGS... — normal mode: the arm's own verdict
run_arm() {
  local name="$1" fn="$2" rc
  shift 2
  "$fn" "$@"; rc=$?
  case "$rc" in
    0) report "$name" ok "$ARM_DETAIL" ;;
    1) report "$name" "not ok" "$ARM_DETAIL" ;;
    *) report "$name" void "$ARM_DETAIL" ;;
  esac
}

# run_fail_arm NAME FN ARGS... — fail-arms mode: the arm MUST fail on the probe
run_fail_arm() {
  local name="$1" fn="$2" rc
  shift 2
  "$fn" "$@"; rc=$?
  case "$rc" in
    1) report "fail-arm-${name}" ok "FAILED on the probe route, as it must: ${ARM_DETAIL}" ;;
    0) report "fail-arm-${name}" "not ok" "PASSED on the probe route, so this arm cannot fail: ${ARM_DETAIL}" ;;
    *) report "fail-arm-${name}" void "$ARM_DETAIL" ;;
  esac
}

if [ "$FAIL_ARMS" -eq 0 ]; then
  run_arm headers arm_headers "https://${APP_HOST}/" "https://${API_HOST}/api/v1/public/shops"
  run_arm csp arm_csp "https://${APP_HOST}/"
  run_arm cors arm_cors "https://${API_HOST}/api/v1/products"
  run_arm admin arm_admin "https://${AUTH_HOST}/admin/"
  if [ "$SKIP_BURST" -eq 0 ]; then
    run_arm burst arm_burst "https://${API_HOST}/api/v1/edge-burst-probe"
  fi
else
  if ! command -v kubectl >/dev/null 2>&1; then echo "VOID: kubectl not on PATH" >&2; exit 2; fi
  PROBE_CREATED=1
  apply_out=$(probe_apply 2>&1); arc=$?
  if [ "$arc" -ne 0 ]; then
    echo "VOID: the scratch probe could not be applied (rc=$arc): ${apply_out}" >&2
    exit 2
  fi
  wait_out=$(kc wait --for=condition=Ready "pod/${PROBE}" --timeout=180s 2>&1); wrc=$?
  if [ "$wrc" -ne 0 ]; then
    echo "VOID: probe pod never became Ready (rc=$wrc): ${wait_out}" >&2
    exit 2
  fi
  for u in "https://${APP_HOST}${PROBE_PATH}" "https://${API_HOST}${PROBE_PATH}" "https://${AUTH_HOST}/admin/"; do
    if ! wait_route "$u"; then
      echo "VOID: probe route $u never answered with agnhost's NOW: body within ${ROUTE_DEADLINE}s (last HTTP ${CODE:-none}, curl rc ${FETCH_RC})" >&2
      exit 2
    fi
  done
  run_fail_arm headers arm_headers "https://${APP_HOST}${PROBE_PATH}" "https://${API_HOST}${PROBE_PATH}"
  run_fail_arm csp arm_csp "https://${APP_HOST}${PROBE_PATH}"
  run_fail_arm cors arm_cors "https://${API_HOST}${PROBE_PATH}"
  run_fail_arm admin arm_admin "https://${AUTH_HOST}/admin/"
  run_fail_arm burst arm_burst "https://${API_HOST}${PROBE_PATH}"
  probe_delete
  left=$(probe_gone); grc=$?
  if [ "$grc" -eq 0 ]; then
    PROBE_CREATED=0
    report probe-cleanup ok "deleted by name and verified NotFound: ${PROBE_KINDS[*]}"
  else
    report probe-cleanup "not ok" "still present after delete: ${left//$'\n'/ }"
  fi
fi

for l in "${REPORT_LINES[@]}"; do printf '%s\n' "$l"; done

if [ "$N_VOID" -gt 0 ]; then
  [ "$FORMAT" = "tap" ] && echo "# VOID: ${N_VOID} of ${REPORT_N} could not be evaluated" || echo "VOID: ${N_VOID} of ${REPORT_N} arm(s) could not be evaluated; nothing is proven"
  exit 2
fi
if [ "$N_FAIL" -gt 0 ]; then
  [ "$FORMAT" = "tap" ] && echo "# FAIL: ${N_FAIL} of ${REPORT_N}" || echo "FAIL: ${N_FAIL} of ${REPORT_N} check(s) failed"
  exit 1
fi
SKIP_NOTE=""; [ "$SKIP_BURST" -eq 1 ] && SKIP_NOTE="; the burst arm was NOT RUN (--skip-burst), so the rate limit is unproven by this run"
[ "$FORMAT" = "tap" ] && echo "# PASS: ${REPORT_N} of ${REPORT_N}${SKIP_NOTE}" || echo "PASS: ${REPORT_N} of ${REPORT_N} check(s) hold on the live edge (mode=${MODE})${SKIP_NOTE}"
exit 0
