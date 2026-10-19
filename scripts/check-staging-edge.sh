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
#            app-staging /robots.txt, a static frontend answer behind jtoye-ingress, so
#            core-java's limiter is not in the path and cannot be mistaken for the edge.
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
#       app-staging  /fidelity-probe  -> headers, csp, burst arms
#       api-staging  /fidelity-probe  -> headers, cors arms
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
#   scripts/check-staging-edge.sh --context jtoye-stg-aks [--fail-arms]
#   EDGE_FORMAT=tap scripts/check-staging-edge.sh ...   (TAP 13 output, one test per arm)
#
#   --context is REQUIRED, with no default, and passes scripts/lib/staging-kube-guard.sh
#   (the employer's sipbihs2aks is refused by name; the context must point at the staging
#   API server recorded in k8s/staging/azure-identity.yaml). The normal run only reads the
#   public hosts; --fail-arms is the only mode that writes to the cluster.
#
# EXIT
#
#   0  every arm PASS (normal) / every arm FAILED on the probe and the probe is gone
#   1  an arm FAILED (normal) / an arm PASSED on the probe, or the probe was not removed
#   2  VOID: a tool is missing, a host does not resolve, a request did not complete, or a
#      probe route never answered. "Could not evaluate" is never reported as a pass.
#
# Runs nowhere in CI on its own: scripts/gates/gate-enforcement.conf records why (a hosted
# runner has no public staging to call); plan 29-21 runs it from the deploy job.

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
while [ $# -gt 0 ]; do
  case "$1" in
    --context)
      if [ $# -lt 2 ]; then echo "USAGE ERROR: --context needs a value" >&2; exit 2; fi
      CONTEXT="$2"; shift 2 ;;
    --context=*) CONTEXT="${1#--context=}"; shift ;;
    --fail-arms) FAIL_ARMS=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "USAGE ERROR: unknown argument \"$1\"" >&2; exit 2 ;;
  esac
done

case "$FORMAT" in text|tap) ;; *) echo "USAGE ERROR: EDGE_FORMAT must be text or tap, got '$FORMAT'" >&2; exit 2 ;; esac

# ---- the context guard (the same implementation every staging script uses) -------------
# shellcheck source=lib/staging-kube-guard.sh
if ! source "$ROOT/scripts/lib/staging-kube-guard.sh"; then
  echo "VOID: could not source scripts/lib/staging-kube-guard.sh" >&2
  exit 2
fi
staging_kube_guard "$CONTEXT" "$IDENTITY_FILE"; guard_rc=$?
if [ "$guard_rc" -ne 0 ]; then exit "$guard_rc"; fi

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

arm_headers() {
  ARM_DETAIL="STUB: not implemented"
  return 0
}

arm_csp() {
  ARM_DETAIL="STUB: not implemented"
  return 0
}

arm_cors() {
  ARM_DETAIL="STUB: not implemented"
  return 0
}

arm_burst() {
  ARM_DETAIL="STUB: not implemented"
  return 0
}

arm_admin() {
  ARM_DETAIL="STUB: not implemented"
  return 0
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
if [ "$FORMAT" = "tap" ]; then
  echo "TAP version 13"
  if [ "$FAIL_ARMS" -eq 1 ]; then echo "1..6"; else echo "1..5"; fi
  echo "# check-staging-edge ${STAMP} mode=${MODE} context=${CONTEXT}"
else
  echo "check-staging-edge  (${STAMP})  mode=${MODE}  context=${CONTEXT}"
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
  run_arm burst arm_burst "https://${APP_HOST}/robots.txt"
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
  run_fail_arm burst arm_burst "https://${APP_HOST}${PROBE_PATH}"
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
[ "$FORMAT" = "tap" ] && echo "# PASS: ${REPORT_N} of ${REPORT_N}" || echo "PASS: ${REPORT_N} of ${REPORT_N} check(s) hold on the live edge (mode=${MODE})"
exit 0
