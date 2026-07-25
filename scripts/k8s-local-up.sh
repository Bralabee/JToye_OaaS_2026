#!/usr/bin/env bash
# k8s-local-up.sh — the SINGLE idempotent bring-up entry point for the local
# Kubernetes rehearsal (Phase 26 / INFRA-01, decision D-14).
#
# WHAT IT REPLACES
#   The 2026-07-14 first-live-deploy rehearsal reached 11/11 pods READY through a
#   hand-typed imperative sequence — profile start, addon enable, in-cluster
#   configmap patches, ad-hoc secret creation, image loads — none of which lived
#   in the repository. It could not be reviewed, re-run or regression-tested.
#   Everything below is that sequence, in git, in one command, and re-runnable.
#
# ORDER (and why the order is load-bearing)
#   0. flags        — parsed FIRST, so a typo can never fall through into a
#                     mutating step
#   1. preflight    — .env contract + the standard compose env preflight + tools
#   2. compose XOR  — refuse while any compose APP container runs; refuse if a
#                     backing service is down. THIS PRECEDES THE PROFILE START:
#                     that ordering is what lets the refuse path be proven with
#                     the profile still Stopped, and what stops a mis-invocation
#                     from starting a cluster against a live compose stack.
#   3. profile      — start the local minikube profile if it is not Running,
#                     then assert the kubectl context (it only exists AFTER the
#                     profile has started)
#   4. addon        — the ingress addon, idempotently
#   5. reachability — probe every backing-service port from INSIDE the cluster
#   6. hosts file   — check the ingress hostnames resolve to the node IP; PRINT
#                     the fix, never escalate privilege
#   7. images       — build + load all four with manifest-matching names and the
#                     tag the overlay pins, then print their identities
#   8. bootstrap    — secrets, dump role, backup bucket
#   9. apply        — namespace first, then a server dry-run printed VERBATIM,
#                     then the real apply
#  10. rollout      — wait for the three Deployments
#  11. smoke        — through the ingress hostnames, never a loopback address
#  12. evidence     — a copy-pasteable block for the k8s/LOCAL.md runbook
#
# NO ENVIRONMENT-VARYING LITERALS
#   Every host, port, hostname and tag comes from .env or from the committed
#   overlay render (GLOBAL_RULE_6 / ARCHITECTURE_RULE_8). In particular the two
#   ingress hostnames and the browser-facing API base are READ FROM THE RENDER,
#   so they cannot drift from what the cluster actually serves.
#
# AUTHORED IN PLAN 26-05, FIRST RUN TO COMPLETION IN PLAN 26-07
#   Steps 3-9 mutate SHARED state: they start a cluster, create an RLS-bypassing
#   role on the shared dev Postgres, create a bucket and create cluster objects.
#   Plan 26-07's checkpoint:human-action obtains approval for exactly that. The
#   run additionally requires the compose app containers to be DOWN — which is
#   the human's decision, not this script's: step 2 refuses, it never stops a
#   container, because a second concurrent session may own that stack.
#
# USAGE
#   scripts/k8s-local-up.sh [--dry-run-only] [--skip-build]
#     --dry-run-only  stop after the server-side dry-run (no real apply)
#     --skip-build    load the existing locally-tagged images without rebuilding;
#                     refuses if any required tag is absent
#
# EXIT CODES: 0 = up, 1 = a guard refused or a step failed, 2 = usage / tooling.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---------------------------------------------------------------------------
# STEP 0 — flags, before anything else
# ---------------------------------------------------------------------------
DRY_RUN_ONLY=0
SKIP_BUILD=0
usage() {
  sed -n '/^# USAGE/,/^#     --skip-build/p' "$0" | sed 's/^# \{0,1\}//'
}
for arg in "$@"; do
  case "$arg" in
    --dry-run-only) DRY_RUN_ONLY=1 ;;
    --skip-build)   SKIP_BUILD=1 ;;
    -h|--help)      usage; exit 0 ;;
    *)
      echo "USAGE ERROR: unknown flag '$arg'" >&2
      usage >&2
      exit 2
      ;;
  esac
done

# shellcheck source=scripts/lib/k8s-local-guards.sh
. "$SCRIPT_DIR/lib/k8s-local-guards.sh"

step() { echo; echo "=== $* ==="; }
die()  { echo "FAIL: $*" >&2; exit 1; }

echo "=== J'Toye local Kubernetes bring-up (dry-run-only=${DRY_RUN_ONLY}, skip-build=${SKIP_BUILD}) ==="

# ---------------------------------------------------------------------------
# STEP 1 — preflight
# ---------------------------------------------------------------------------
step "STEP 1: preflight"
k8s_local_load_env

for tool in docker kubectl minikube jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo "TOOLING ERROR: ${tool} not found on PATH" >&2; exit 2; }
done
echo "OK: docker, kubectl, minikube, jq present"

# The standard env preflight that already gates every compose dev run. Reused
# rather than duplicated, and deliberately NOT made stricter (a k8s-local-only
# key must not become mandatory for compose).
bash "$SCRIPT_DIR/verify-env.sh" >/dev/null || die "scripts/verify-env.sh refused — fix the named variable(s) in .env"
echo "OK: scripts/verify-env.sh passed"

PROFILE="$K8S_LOCAL_MINIKUBE_PROFILE"
NS="$(k8s_local_namespace)"
OVERLAY="$REPO_ROOT/k8s/local"

# ---------------------------------------------------------------------------
# STEP 2 — compose XOR k8s. MUST precede the profile start below.
# ---------------------------------------------------------------------------
step "STEP 2: compose XOR k8s guard"
k8s_local_assert_compose_xor

# ---------------------------------------------------------------------------
# STEP 3 — profile, then the context assertion
# ---------------------------------------------------------------------------
step "STEP 3: minikube profile ${PROFILE}"
PROFILE_STATUS="$(minikube profile list -o json 2>/dev/null \
  | jq -r --arg p "$PROFILE" '.valid[]? | select(.Name == $p) | .Status // empty')"
if [ "$PROFILE_STATUS" = "Running" ]; then
  echo "OK: profile ${PROFILE} already Running (idempotent no-op)"
else
  echo "profile ${PROFILE} status='${PROFILE_STATUS:-absent}' — starting with ${K8S_LOCAL_MINIKUBE_CPUS} CPUs / ${K8S_LOCAL_MINIKUBE_MEMORY}"
  minikube start -p "$PROFILE" \
    --cpus "$K8S_LOCAL_MINIKUBE_CPUS" \
    --memory "$K8S_LOCAL_MINIKUBE_MEMORY" \
    || die "minikube start failed for profile ${PROFILE}"
fi

# Only meaningful once the profile has been started — the context does not exist
# in kubeconfig while the profile is Stopped.
k8s_local_assert_context
NODE_IP="$(k8s_local_profile_ip)" || die "could not resolve the node IP of profile ${PROFILE}"

# ---------------------------------------------------------------------------
# STEP 4 — ingress addon (idempotent)
#
# metrics-server is deliberately NOT enabled: D-09 patches HPA minReplicas to 1,
# so nothing needs metrics locally.
#
# The ingress controller's own configuration is NOT touched. In particular no
# snippet-annotation allowance is enabled on the controller: the local overlay
# nulls the snippet annotation instead (PIT-1). Weakening a cluster's admission
# posture to make an apply succeed is forbidden.
# ---------------------------------------------------------------------------
step "STEP 4: ingress addon"
minikube addons enable ingress -p "$PROFILE" || die "could not enable the ingress addon"
echo "OK: ingress addon enabled for profile ${PROFILE}"

# ---------------------------------------------------------------------------
# STEP 4b — WAIT FOR THE ADMISSION WEBHOOK TO ACTUALLY ANSWER
#
# `addons enable ingress` is idempotent in effect but NOT inert: it re-applies
# the addon manifests and rolls the controller container. Observed across
# consecutive runs of this script: restartCount 5 -> 6 with the pod IP moving
# 10.244.0.51 -> 10.244.0.52. The admission Service keeps its ClusterIP, so for
# a few seconds the API server dials the ClusterIP and lands on the OLD pod IP:
#
#   Internal error occurred: failed calling webhook
#   "validate.nginx.ingress.kubernetes.io": ... dial tcp 10.108.175.67:443:
#   connect: no route to host
#
# Step 9 then fails on both Ingress objects, and the message reads
# `error when creating ".../k8s/local"` — which points an operator straight at
# the manifests, when the manifests are fine. That is the PIT-11 misdiagnosis
# shape (blame the manifest, miss the infrastructure) and it made this script
# not reliably re-runnable, breaking D-14.
#
# A pod-readiness wait does NOT fix it: the container reports ready=true for the
# whole window. The only sufficient check is to exercise the real webhook path,
# so this probes with a throwaway Ingress under --dry-run=server. A dry-run
# creates nothing, and any ANSWER (accept or deny) proves reachability — only a
# transport failure is retried.
# ---------------------------------------------------------------------------
step "STEP 4b: ingress admission webhook reachability"
webhook_answers() {
  local out
  out="$(k8s_local_kubectl apply -n default --dry-run=server -f - 2>&1 <<'PROBE'
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: k8s-local-webhook-readiness-probe
spec:
  ingressClassName: nginx
  rules:
    - host: webhook-readiness-probe.invalid
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: webhook-readiness-probe
                port:
                  number: 80
PROBE
  )" || true
  # A transport failure is the only retryable condition. An admission DENIAL is a
  # real answer, so it ends the wait and is left for step 9 to surface properly.
  ! grep -Eq 'failed calling webhook|no route to host|connection refused|context deadline exceeded|EOF' <<<"$out"
}
WEBHOOK_OK=0
for attempt in $(seq 1 30); do
  if webhook_answers; then
    echo "OK: admission webhook answered on attempt ${attempt}"
    WEBHOOK_OK=1
    break
  fi
  [ "$attempt" -eq 1 ] && echo "waiting for the ingress-nginx admission webhook to converge after the addon roll..."
  sleep 5
done
[ "$WEBHOOK_OK" -eq 1 ] || die "the ingress-nginx admission webhook did not become reachable within 150s. This is an INFRASTRUCTURE condition, not a manifest problem — check 'kubectl --context ${K8S_LOCAL_KUBE_CONTEXT} -n ingress-nginx get pods,endpointslices' and the controller's restart count. Do NOT relax allow-snippet-annotations to work around it (PIT-1)."

# ---------------------------------------------------------------------------
# STEP 5 — backing-service reachability FROM INSIDE the cluster
#
# A pod that cannot reach the host backing services fails in a way that looks
# like a manifest bug. Probe it directly instead, and name the likely cause.
# ---------------------------------------------------------------------------
step "STEP 5: host-service reachability from inside the cluster"
UNREACHABLE=""
for port in "$K8S_LOCAL_DB_PORT" "$K8S_LOCAL_KC_PORT" "$K8S_LOCAL_REDIS_PORT" \
            "$K8S_LOCAL_AMQP_PORT" "$K8S_LOCAL_STOMP_PORT" "$K8S_LOCAL_MINIO_PORT" \
            "$K8S_LOCAL_SMTP_PORT"; do
  if minikube ssh -p "$PROFILE" -- "nc -vz -w 3 ${K8S_LOCAL_POD_HOST} ${port}" >/dev/null 2>&1; then
    echo "OK: ${K8S_LOCAL_POD_HOST}:${port} reachable from inside the cluster"
  else
    echo "UNREACHABLE: ${K8S_LOCAL_POD_HOST}:${port}"
    UNREACHABLE="${UNREACHABLE} ${port}"
  fi
done
if [ -n "$UNREACHABLE" ]; then
  die "port(s)${UNREACHABLE} on ${K8S_LOCAL_POD_HOST} are not reachable from inside the cluster. Most likely a HOST FIREWALL rule on the minikube bridge interface, not a manifest problem — check that the compose port is published on all interfaces and that the bridge is allowed. Do NOT start editing manifests first."
fi

# ---------------------------------------------------------------------------
# STEP 6 — hosts-file check (D-12). PRINTS the fix; never escalates privilege.
# ---------------------------------------------------------------------------
step "STEP 6: ingress hostname resolution"
mapfile -t INGRESS_HOSTS < <(kubectl kustomize "$OVERLAY" | awk '/^[[:space:]]*-[[:space:]]+host:[[:space:]]/{print $3}' | sort -u)
[ "${#INGRESS_HOSTS[@]}" -gt 0 ] || { echo "PARSE ERROR: no ingress hostnames found in the ${OVERLAY} render" >&2; exit 2; }

HOSTS_BAD=""
for h in "${INGRESS_HOSTS[@]}"; do
  # `getent` exits 2 for an unresolvable name. Under this script's `set -o pipefail`
  # that exit status propagates out of the pipeline, `set -e` then aborts the whole
  # script — SILENTLY, with exit 2, before any of the diagnostics below can run. The
  # unresolved branch is the ONLY branch this step exists to serve (it prints the
  # /etc/hosts line the operator must add), so the failure mode killed exactly the
  # path that matters, and did it with no message at all. `|| true` contains the
  # expected non-zero here; an empty `resolved` is the real signal. (Found in plan
  # 26-07, the first execution of this script — 26-05 authored it but was forbidden
  # to run it, so no static check could have caught this.)
  resolved="$( { getent ahostsv4 "$h" 2>/dev/null || true; } | awk 'NR==1{print $1}')"
  if [ "$resolved" = "$NODE_IP" ]; then
    echo "OK: ${h} -> ${NODE_IP}"
  else
    echo "WRONG: ${h} resolves to '${resolved:-nothing}', expected ${NODE_IP}"
    HOSTS_BAD="${HOSTS_BAD} ${h}"
  fi
done
if [ -n "$HOSTS_BAD" ]; then
  echo
  echo "Add (or correct) this single line in /etc/hosts:"
  echo
  echo "    ${NODE_IP} ${INGRESS_HOSTS[*]}"
  echo
  echo "One way to do that, run it yourself — this script never escalates privilege:"
  echo
  echo "    echo '${NODE_IP} ${INGRESS_HOSTS[*]}' | sudo tee -a /etc/hosts"
  echo
  die "ingress hostname(s)${HOSTS_BAD} do not resolve to the node IP"
fi

# ---------------------------------------------------------------------------
# STEP 7 — images
#
# PIT-4: the on-host 2.1.0 images predate several phases, and a stale-image
# rehearsal is a FALSE GREEN. All four are built here with the names the base
# manifests reference and the tag the local overlay pins, then loaded into the
# cluster's own image store (the base sets IfNotPresent, which is exactly right
# for loaded images). The identities are printed so "which code did we prove?"
# is answerable from the evidence block alone.
#
# The repo's older generic image-build helper is deliberately NOT reused here: it
# tags a third naming scheme matching neither the manifests nor compose, and it
# passes no browser build args at all.
# ---------------------------------------------------------------------------
step "STEP 7: images"
LOCAL_TAG="$(awk '/^[[:space:]]*newTag:/{gsub(/"/,"",$2); print $2; exit}' "$OVERLAY/kustomization.yaml")"
[ -n "$LOCAL_TAG" ] || { echo "PARSE ERROR: could not read the images newTag from ${OVERLAY}/kustomization.yaml" >&2; exit 2; }

IMG_CORE="ghcr.io/bralabee/jtoye-core-java:${LOCAL_TAG}"
IMG_EDGE="ghcr.io/bralabee/jtoye-edge-go:${LOCAL_TAG}"
IMG_FRONT="ghcr.io/bralabee/jtoye-frontend:${LOCAL_TAG}"
# The backup image's tag is already immutable and environment-independent, so it
# is loaded AS-IS at whatever the CronJob pins — never retagged.
IMG_BACKUP="$(awk '$1=="image:" && $2 ~ /jtoye-pg-backup/{print $2; exit}' "$REPO_ROOT/k8s/base/pg-backup-cronjob.yaml")"
[ -n "$IMG_BACKUP" ] || { echo "PARSE ERROR: could not read the pg-backup image from k8s/base/pg-backup-cronjob.yaml" >&2; exit 2; }

# Drift guard: the three service image NAMES must appear in the render, or we
# would build images the cluster will never pull.
RENDER="$(kubectl kustomize "$OVERLAY")"
# A HERESTRING, not a pipe, and the reason is not style. `grep -q` exits the
# instant it matches; the writer of a pipe then takes SIGPIPE and reports 141,
# and `set -o pipefail` promotes that 141 to the pipeline's status. So
# `printf "$RENDER" | grep -Fq …` reports FAILURE precisely when the pattern IS
# found — the assertion inverts, and it inverts as a RACE (it passed 1 run in 8,
# whenever printf happened to finish before grep exited). The render is ~38 KB,
# which is large enough to lose that race almost every time. `<<<` removes the
# pipe entirely, so there is no writer to signal. (Found in plan 26-07, the first
# execution of this script; the die message asserted a drift that did not exist.)
for ref in "$IMG_CORE" "$IMG_EDGE" "$IMG_FRONT" "$IMG_BACKUP"; do
  grep -Fq "image: ${ref}" <<<"$RENDER" \
    || die "image '${ref}' does not appear in the ${OVERLAY} render — the manifests and this script have drifted"
done
echo "OK: all four image references match the overlay render"

# Browser-facing values are inlined by Next.js at BUILD time, so they must be
# baked correctly here: no runtime env can fix a wrong bake. The API base is read
# from the RENDERED ConfigMap, which is the same value the cluster serves — that
# is what stops a dashboard page from calling the compose backend the XOR rule
# just required to be down.
API_URL="$(printf '%s' "$RENDER" | awk '/^[[:space:]]*api\.url:[[:space:]]/{print $2; exit}')"
[ -n "$API_URL" ] || { echo "PARSE ERROR: could not read api.url from the rendered ConfigMap" >&2; exit 2; }
FRONTEND_URL_RENDERED="$(printf '%s' "$RENDER" | awk '/^[[:space:]]*frontend\.url:[[:space:]]/{print $2; exit}')"
[ -n "$FRONTEND_URL_RENDERED" ] || { echo "PARSE ERROR: could not read frontend.url from the rendered ConfigMap" >&2; exit 2; }

# The remaining browser build args come from .env; when a key is absent the
# fallback is PARSED OUT OF THE COMPOSE FILE, so this script carries no default
# of its own and cannot drift from what compose bakes.
compose_default() {
  sed -nE "s/.*\\\$\\{$1:-([^}]*)\\}.*/\\1/p" "$REPO_ROOT/docker-compose.full-stack.yml" | head -1
}
BA_CUSTOMER_KC="${NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL:-}"
[ -n "$BA_CUSTOMER_KC" ] || die "NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL is unset; compose marks it required because an empty value would bake an empty string into the browser bundle"
BA_SUPPORT_EMAIL="${NEXT_PUBLIC_SUPPORT_EMAIL:-$(compose_default NEXT_PUBLIC_SUPPORT_EMAIL)}"
BA_SUPPORT_URL="${NEXT_PUBLIC_SUPPORT_URL:-$(compose_default NEXT_PUBLIC_SUPPORT_URL)}"
BA_SLA_DAYS="${NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS:-$(compose_default NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS)}"

if [ "$SKIP_BUILD" -eq 1 ]; then
  for ref in "$IMG_CORE" "$IMG_EDGE" "$IMG_FRONT" "$IMG_BACKUP"; do
    docker image inspect "$ref" >/dev/null 2>&1 \
      || die "--skip-build was passed but image '${ref}' does not exist on this host. Re-run without --skip-build; loading a missing tag would leave the cluster on a stale or absent image (a false green)."
  done
  echo "OK: --skip-build — all four required tags already exist locally"
else
  echo "building ${IMG_CORE}"
  docker build -t "$IMG_CORE" -f "$REPO_ROOT/core-java/Dockerfile" "$REPO_ROOT"
  echo "building ${IMG_EDGE}"
  docker build -t "$IMG_EDGE" -f "$REPO_ROOT/edge-go/Dockerfile" "$REPO_ROOT/edge-go"
  echo "building ${IMG_FRONT} with NEXT_PUBLIC_API_URL=${API_URL}"
  docker build -t "$IMG_FRONT" -f "$REPO_ROOT/frontend/Dockerfile" \
    --build-arg "NEXT_PUBLIC_API_URL=${API_URL}" \
    --build-arg "NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL=${BA_CUSTOMER_KC}" \
    --build-arg "NEXT_PUBLIC_SUPPORT_EMAIL=${BA_SUPPORT_EMAIL}" \
    --build-arg "NEXT_PUBLIC_SUPPORT_URL=${BA_SUPPORT_URL}" \
    --build-arg "NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS=${BA_SLA_DAYS}" \
    "$REPO_ROOT/frontend"
  echo "building ${IMG_BACKUP}"
  docker build -t "$IMG_BACKUP" "$REPO_ROOT/infra/backups"
fi

for ref in "$IMG_CORE" "$IMG_EDGE" "$IMG_FRONT" "$IMG_BACKUP"; do
  echo "loading ${ref}"
  minikube -p "$PROFILE" image load "$ref" || die "could not load ${ref} into profile ${PROFILE}"
done

image_identity() { docker image inspect "$1" --format '{{.Id}}' 2>/dev/null || echo "UNKNOWN"; }
ID_CORE="$(image_identity "$IMG_CORE")"
ID_EDGE="$(image_identity "$IMG_EDGE")"
ID_FRONT="$(image_identity "$IMG_FRONT")"
ID_BACKUP="$(image_identity "$IMG_BACKUP")"
echo "OK: four images built and loaded"

# ---------------------------------------------------------------------------
# STEP 8 — secrets, dump role, backup bucket
# ---------------------------------------------------------------------------
step "STEP 8: bootstrap secrets, dump role and backup bucket"
bash "$SCRIPT_DIR/k8s-local-secrets.sh" || die "the bootstrap refused or failed — nothing was applied"

# ---------------------------------------------------------------------------
# STEP 9 — apply
#
# The Namespace goes first because a server-side dry-run does NOT create the
# Namespace it is validating (PIT-8), so every namespaced object would fail with
# "namespace not found" until it exists.
# ---------------------------------------------------------------------------
step "STEP 9: apply"
k8s_local_kubectl apply -f "$OVERLAY/namespace.yaml"

echo
echo "--- server-side dry-run (VERBATIM) ---"
k8s_local_kubectl apply -k "$OVERLAY" --dry-run=server
echo "--- end server-side dry-run ---"
echo

if [ "$DRY_RUN_ONLY" -eq 1 ]; then
  echo "PASS: --dry-run-only — stopping before the real apply."
  exit 0
fi

k8s_local_kubectl apply -k "$OVERLAY"

# ---------------------------------------------------------------------------
# STEP 10 — rollouts
# ---------------------------------------------------------------------------
step "STEP 10: rollouts"
k8s_local_kubectl -n "$NS" rollout status deploy/core-java --timeout=5m || die "core-java rollout did not complete"
k8s_local_kubectl -n "$NS" rollout status deploy/frontend  --timeout=3m || die "frontend rollout did not complete"
k8s_local_kubectl -n "$NS" rollout status deploy/edge-go   --timeout=3m || die "edge-go rollout did not complete"

# ---------------------------------------------------------------------------
# STEP 11 — smoke, THROUGH THE INGRESS HOSTNAMES
#
# Both bases come from the rendered ConfigMap. A loopback address appearing here
# would mean the compose apps were up and the XOR guard had been bypassed.
# ---------------------------------------------------------------------------
step "STEP 11: smoke through the ingress"
curl -fsS --max-time 20 "${API_URL}/health"        >/dev/null || die "core /health did not answer through ${API_URL}"
echo "OK: ${API_URL}/health"
curl -fsS --max-time 20 "${API_URL}/public/shops"  >/dev/null || die "core /public/shops did not answer through ${API_URL}"
echo "OK: ${API_URL}/public/shops"
curl -fsS --max-time 20 "${FRONTEND_URL_RENDERED}/api/health" >/dev/null || die "frontend /api/health did not answer through ${FRONTEND_URL_RENDERED}"
echo "OK: ${FRONTEND_URL_RENDERED}/api/health"

# ---------------------------------------------------------------------------
# STEP 12 — evidence block for k8s/LOCAL.md
# ---------------------------------------------------------------------------
step "STEP 12: rehearsal evidence"
cat <<EVIDENCE
--- BEGIN LOCAL REHEARSAL EVIDENCE ---
captured    : $(date -u +%Y-%m-%dT%H:%M:%SZ)
git commit  : $(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)
profile     : ${PROFILE} (node ${NODE_IP})
namespace   : ${NS}
context     : ${K8S_LOCAL_KUBE_CONTEXT}
ingress     : ${INGRESS_HOSTS[*]}
api base    : ${API_URL}
app base    : ${FRONTEND_URL_RENDERED}
images      :
  ${IMG_CORE}   ${ID_CORE}
  ${IMG_EDGE}   ${ID_EDGE}
  ${IMG_FRONT}  ${ID_FRONT}
  ${IMG_BACKUP} ${ID_BACKUP}
pods        :
$(k8s_local_kubectl -n "$NS" get pods -o wide 2>&1 | sed 's/^/  /')
--- END LOCAL REHEARSAL EVIDENCE ---
EVIDENCE

echo
echo "PASS: local cluster is up and smoking clean. Paste the evidence block above into k8s/LOCAL.md."
