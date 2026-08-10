#!/usr/bin/env bash
# staging-bootstrap.sh — install the three third-party platform components the
# staging cluster needs, from PINNED, DIGEST-VERIFIED release artefacts
# (Phase 29 / DPLY-01, DPLY-03; decisions D-02, D-07, D-09, D-16).
#
# ---------------------------------------------------------------------------
# WHY THESE MANIFESTS LIVE OUTSIDE k8s/ (RESEARCH Pattern 3)
#
#   k8s/scripts/* auto-discover targets with
#   `find k8s -maxdepth 2 -name kustomization.yaml`, and
#   k8s/scripts/render-golden.sh byte-compares the FULL render of each overlay.
#   Vendoring ~1.4 MB / ~5,900 lines of upstream CRDs into a kustomization would
#   turn every upstream bump into a golden diff nobody can review meaningfully —
#   the goldens are 1,578 lines each today. So the upstream artefacts are applied
#   from here, by pinned URL plus recorded sha256, and only OUR OWN
#   `RabbitmqCluster`, `ClusterIssuer` and `Certificate` CRs live in a
#   kustomization. k8s/goldens/ deliberately contains no kustomization.yaml for
#   the same reason; this is that reasoning applied one layer out.
#
# ---------------------------------------------------------------------------
# ORDER (and why the order is load-bearing)
#
#   0. flags       — parsed FIRST, so a typo cannot fall through into an apply
#   1. context     — the kubectl context must be NAMED. The only context on this
#                    host is `sipbihs2aks`, which is EMPLOYER infrastructure, and
#                    naming it explicitly does not make it allowed
#   2. inputs      — the node resource group and static IP name come from the
#                    evidence block scripts/azure-staging-provision.sh prints.
#                    They are never inferred
#   3. VERIFY ALL  — every artefact is fetched and digest-checked BEFORE ANY of
#      artefacts     them is applied. Verifying just-in-time would let a bad
#                    third artefact leave the first two already installed, i.e. a
#                    half-bootstrapped cluster produced by the very check meant
#                    to prevent one
#   4. cert-manager — FIRST, and this is MEASURED, not assumed: the RabbitMQ
#                    operator manifest contains 3 `cert-manager.io/v1` objects
#                    (Certificate at lines 5870 and 5887, Issuer at 5904 of
#                    cluster-operator.yml v2.22.3). Applying the operator without
#                    the cert-manager CRDs fails on those three documents
#   4b. webhook     — a rollout wait is NOT sufficient. The lesson is already in
#      probe         this repo (scripts/k8s-local-up.sh STEP 4b): a controller can
#                    report ready while its admission Service still resolves to
#                    the previous pod IP. Exercise the REAL webhook path with a
#                    --dry-run=server object, which creates nothing and where any
#                    ANSWER — accept or deny — proves reachability
#   5. RabbitMQ     — the operator, into its own `rabbitmq-system` namespace
#      operator
#   6. ingress      — LAST, because it is the only one that claims a public IP,
#                    and claiming the front door before TLS issuance exists would
#                    publish an endpoint with no certificate
#   7. evidence
#
# ---------------------------------------------------------------------------
# THE INGRESS CONTROLLER CHOICE — 2026-08-10, with its cost stated
#
#   CHOSEN: self-installed ingress-nginx `controller-v1.15.1`.
#   REJECTED: the AKS application-routing add-on.
#
#   REASON: k8s/base/ingress.yaml sets HSTS, X-Frame-Options, nosniff,
#   Referrer-Policy and Permissions-Policy through
#   `nginx.ingress.kubernetes.io/configuration-snippet`. The add-on constrains
#   which annotations may be set and owns its own controller ConfigMap, so those
#   headers would need re-homing into a mechanism the add-on permits — a change
#   to the security posture of every environment, made in order to adopt an
#   add-on, which is the wrong way round.
#
#   NEITHER OPTION IS "FINE", AND THE COST IS NOT HIDDEN. ingress-nginx is
#   RETIRED upstream: no releases, no bugfixes and NO SECURITY FIXES after March
#   2026 (announced 2025-11-11; controller-v1.15.1, 2026-03-19, is the last
#   release). Its planned successor InGate was also retired; Kubernetes now
#   recommends Gateway API. The add-on is on its own clock — Microsoft patches
#   its NGINX only through November 2026. So this decision buys time, not safety.
#
#   OBLIGATION (plan 29-09, which already edits infra/dependency-horizons.yaml):
#   a DATED horizon row for `ingress-nginx controller-v1.15.1` recording the
#   March-2026 end of security fixes, plus a recorded Gateway-API migration
#   deferral. A retirement accepted without a dated row is a retirement
#   discovered by a CVE.
#
# ---------------------------------------------------------------------------
# PITFALL 5 — THE SECURITY HEADERS, AND THE CVE THIS ACCEPTS
#
#   Since ingress-nginx v1.9, `allow-snippet-annotations` defaults to FALSE (the
#   CVE-2021-25742 mitigation). MEASURED in the v1.15.1 manifest this script
#   pins: the shipped `ingress-nginx-controller` ConfigMap contains ZERO
#   occurrences of that key, so a default install takes the false default. On
#   such an install every page is served WITHOUT those headers while the Ingress
#   object still shows them and every HTTP check still returns 200 — there is no
#   warning sign by construction.
#
#   AND SETTING THAT ONE KEY IS NOT ENOUGH. Upstream classifies
#   `configuration-snippet` as a **Critical**-risk annotation, while
#   `annotations-risk-level` defaults to **High**, and an annotation whose risk
#   exceeds the configured level is rejected regardless of the snippet flag. A
#   bootstrap that set only `allow-snippet-annotations` would therefore reproduce
#   the exact symptom it was written to prevent. Both keys are set in STEP 6, and
#   the script PRINTS which route it took.
#
#   ROUTE TAKEN: allow-snippet-annotations=true + annotations-risk-level=Critical
#   on the controller ConfigMap.
#   ACCEPTANCE, DATED 2026-08-10: this re-enables a Critical-risk annotation
#   class on a SINGLE-TENANT staging cluster whose only Ingress objects come from
#   this repository's own reviewed manifests. CVE-2021-25742's threat model is a
#   tenant who can create Ingress objects escalating through snippet injection;
#   there is no such second tenant here, and RBAC for Ingress creation is not
#   granted to any workload.
#
#   WHY NOT THE STRICTLY BETTER ROUTE. Re-expressing the headers as a
#   controller-level `add-headers` ConfigMap would serve them WITHOUT re-enabling
#   the snippet class, and it is the better end state. It cannot be done from
#   this script: the base Ingress would still carry the snippet annotation and
#   would still be REJECTED by the admission webhook, so it also needs a
#   k8s/staging overlay patch nulling the annotation plus a deliberate
#   k8s/goldens/staging.yaml regeneration — files this plan does not own and
#   which a parallel plan is editing. Recorded here as the follow-up, not
#   silently dropped. k8s/local/ingress-patch.yaml forbids enabling the snippet
#   class "to satisfy a LOCAL convenience"; this is not that — staging is the
#   environment those headers exist to protect, and the acceptance is written
#   down rather than assumed.
#
# ---------------------------------------------------------------------------
# USAGE
#   scripts/staging-bootstrap.sh --context <kube-context> \
#       --node-resource-group <rg> --static-ip-name <name> [MODE]
#     --context NAME              REQUIRED. No default, ever
#     --node-resource-group RG    REQUIRED for the ingress step. From the
#                                 provisioning evidence block
#     --static-ip-name NAME       REQUIRED for the ingress step. From the same
#     --artefact-dir PATH         read the artefacts from PATH instead of
#                                 downloading (air-gapped path). Still fully
#                                 digest-verified — this is not a bypass
#     --verify-artefacts-only     fetch + verify digests and STOP. Needs no
#                                 cluster, so the digest refusal is falsifiable
#                                 offline
#     --dry-run-only              stop after the server-side dry-runs; applies
#                                 nothing
#
# EXIT CODES: 0 = installed, 1 = a guard refused or a digest did not match,
#             2 = usage / tooling / VOID.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ok()     { echo "OK: $*"; }
step()   { echo; echo "=== $* ==="; }
refuse() { local arm="$1"; shift; echo "REFUSED [$arm]: $*" >&2; exit 1; }
die()    { echo "FAIL: $*" >&2; exit 1; }
void()   { echo "VOID: $*" >&2; exit 2; }

# ---------------------------------------------------------------------------
# STEP 0 — flags
# ---------------------------------------------------------------------------
KUBE_CONTEXT=""
NODE_RESOURCE_GROUP=""
STATIC_IP_NAME=""
ARTEFACT_DIR=""
MODE="all"

usage() {
  sed -n '/^# USAGE/,/^#                                 nothing/p' "$0" | sed 's/^# \{0,1\}//'
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --context)              shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --context needs a name" >&2; exit 2; }; KUBE_CONTEXT="$1" ;;
    --node-resource-group)  shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --node-resource-group needs a name" >&2; exit 2; }; NODE_RESOURCE_GROUP="$1" ;;
    --static-ip-name)       shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --static-ip-name needs a name" >&2; exit 2; }; STATIC_IP_NAME="$1" ;;
    --artefact-dir)         shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --artefact-dir needs a path" >&2; exit 2; }; ARTEFACT_DIR="$1" ;;
    --verify-artefacts-only) MODE="verify" ;;
    --dry-run-only)         MODE="dryrun" ;;
    -h|--help)              usage; exit 0 ;;
    *)
      echo "USAGE ERROR: unknown flag '$1'" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

echo "=== J'Toye staging platform bootstrap (mode=${MODE}) ==="

# ---------------------------------------------------------------------------
# THE PINNED ARTEFACTS.
#
# Each row: name | url | sha256 | bytes. The sha256 values were computed on
# 2026-08-10 from the artefacts downloaded by these exact URLs, and each was
# fetched TWICE and compared byte-for-byte to confirm the pin is immutable rather
# than a moving target. The byte size is a SECONDARY sanity check: it catches a
# truncated transfer with a message a human can act on, before the digest
# comparison reports an opaque mismatch.
#
# Provenance (29-RESEARCH.md "Package Legitimacy Audit"): all three come from the
# project's own release channel and were read, not merely referenced. This phase
# installs no npm or PyPI package, so `slopcheck` is out of domain — recorded
# rather than skipped.
#
# Every upstream bump here needs the sha256 recomputed IN THE SAME CHANGE. A
# version bumped without its digest is the supply-chain hole this file exists to
# close.
# ---------------------------------------------------------------------------
CERT_MANAGER_VERSION="v1.21.1"
CERT_MANAGER_URL="https://github.com/cert-manager/cert-manager/releases/download/${CERT_MANAGER_VERSION}/cert-manager.yaml"
CERT_MANAGER_SHA256="5f6a499b8c1857d57f560f536e0dcc830914b45c420899fe7ad0692c8624e408"
CERT_MANAGER_BYTES="1034400"

RABBITMQ_OPERATOR_VERSION="v2.22.3"
RABBITMQ_OPERATOR_URL="https://github.com/rabbitmq/cluster-operator/releases/download/${RABBITMQ_OPERATOR_VERSION}/cluster-operator.yml"
RABBITMQ_OPERATOR_SHA256="8e2c20fe9fe8fb06a8e4a99574951d7933ba7cbc4d83c854bc5e7acc7dc0624e"
RABBITMQ_OPERATOR_BYTES="351140"

INGRESS_NGINX_VERSION="controller-v1.15.1"
INGRESS_NGINX_URL="https://raw.githubusercontent.com/kubernetes/ingress-nginx/${INGRESS_NGINX_VERSION}/deploy/static/provider/cloud/deploy.yaml"
INGRESS_NGINX_SHA256="502fddca66b09c20dd48b6d0a792a9671cd663a3a0d2a8bda5ae990d13b6c5b2"
INGRESS_NGINX_BYTES="16384"

# Namespaces and object names the upstream manifests define. Read out of those
# manifests when they were inspected on 2026-08-10, not guessed.
CERT_MANAGER_NS="cert-manager"
RABBITMQ_OPERATOR_NS="rabbitmq-system"
INGRESS_NS="ingress-nginx"
INGRESS_CONTROLLER_CM="ingress-nginx-controller"
INGRESS_CONTROLLER_SVC="ingress-nginx-controller"

FORBIDDEN_KUBE_CONTEXTS="${FORBIDDEN_KUBE_CONTEXTS:-sipbihs2aks}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-5m}"
WEBHOOK_ATTEMPTS="${WEBHOOK_ATTEMPTS:-30}"
WEBHOOK_SLEEP="${WEBHOOK_SLEEP:-5}"

WORKDIR=""

# AN EXIT TRAP CAN REWRITE THE SCRIPT'S EXIT STATUS, AND THAT BREAKS THE 0/1/2
# CONTRACT. Measured here on bash 5.2.21, in isolation:
#
#     WORKDIR=""
#     cleanup() { [ -n "$WORKDIR" ] && [ -d "$WORKDIR" ] && rm -rf "$WORKDIR"; }
#     trap cleanup EXIT
#     exit 2        ->  the script actually exits 1
#
# The trap's final `[ -n "" ]` returns 1 and that status becomes the script's.
# Every VOID (2) coming out of a guard would silently be reported as a violation
# (1) — the two mean different things everywhere in this repo ("VOID is never
# clean" is a distinct verdict from "this failed"), and CI branches on them. The
# incoming status is therefore captured FIRST and re-asserted LAST.
cleanup() {
  local rc=$?
  if [ -n "${WORKDIR:-}" ] && [ -d "${WORKDIR:-}" ]; then
    rm -rf "$WORKDIR"
  fi
  exit "$rc"
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# STEP 1 — CONTEXT GUARD. Precedes every apply, so a refusal is a no-op.
#
# In --verify-artefacts-only NO kubectl call is made, so the guard is not
# evaluated — stated out loud rather than skipped in silence.
# ---------------------------------------------------------------------------
step "STEP 1: kube-context guard"

if [ "$MODE" = "verify" ]; then
  echo "SKIP: --verify-artefacts-only makes no kubectl call, so the context guard is not evaluated."
  echo "      This script structurally cannot reach any apply in this mode."
else
  command -v kubectl >/dev/null 2>&1 || void "kubectl not found on PATH"

  [ -n "$KUBE_CONTEXT" ] || \
    void "--context is REQUIRED and has no default. The only kubectl context on this host is '${FORBIDDEN_KUBE_CONTEXTS}', which is EMPLOYER infrastructure — an apply against the ambient default would land there. This script installs cluster-scoped CRDs and RBAC with cluster-admin, so that is not a recoverable mistake."

  case " $FORBIDDEN_KUBE_CONTEXTS " in
    *" $KUBE_CONTEXT "*)
      void "context '${KUBE_CONTEXT}' is on the refusal list — it is EMPLOYER infrastructure. Naming it explicitly does not make it safe; intent is not a safety mechanism."
      ;;
  esac

  KNOWN_CONTEXTS="$(kubectl config get-contexts -o name 2>/dev/null || true)"
  [ -n "$KNOWN_CONTEXTS" ] || void "kubectl reported no contexts at all — the check would pass by finding nothing, which is VOID, not clean"
  if ! grep -Fxq "$KUBE_CONTEXT" <<<"$KNOWN_CONTEXTS"; then
    echo "known contexts:" >&2
    printf '  %s\n' $KNOWN_CONTEXTS >&2
    void "context '${KUBE_CONTEXT}' does not exist in kubeconfig. Not proceeding on an unresolvable target."
  fi
  ok "kubectl context '${KUBE_CONTEXT}' exists and is not on the refusal list"
fi

# The ONLY way this script talks to a cluster: --context is always explicit, and
# `kubectl config use-context` is never called anywhere.
k() {
  [ -n "$KUBE_CONTEXT" ] || void "internal: k() called with no context"
  kubectl --context "$KUBE_CONTEXT" "$@"
}

# ---------------------------------------------------------------------------
# STEP 2 — inputs that must not be inferred
# ---------------------------------------------------------------------------
if [ "$MODE" = "all" ]; then
  step "STEP 2: ingress inputs"
  missing=0
  [ -n "$NODE_RESOURCE_GROUP" ] || { echo "MISSING: --node-resource-group (the AKS node resource group; 'AKS node RG' in the provisioning evidence block)" >&2; missing=$((missing + 1)); }
  [ -n "$STATIC_IP_NAME" ]      || { echo "MISSING: --static-ip-name (the pre-created static public IP; 'static ingress IP' in the same evidence block)" >&2; missing=$((missing + 1)); }
  [ "$missing" -eq 0 ] || \
    refuse "ingress-inputs" "${missing} required input(s) missing — see the names above. Without them the controller Service would be given a NEW, dynamic public IP, and the four DNS A records would point at an address nothing serves."
  ok "node resource group '${NODE_RESOURCE_GROUP}', static IP '${STATIC_IP_NAME}'"
fi

# ---------------------------------------------------------------------------
# STEP 3 — FETCH AND VERIFY EVERY ARTEFACT BEFORE APPLYING ANY OF THEM
# ---------------------------------------------------------------------------
step "STEP 3: artefact provenance"

for tool in curl sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || void "${tool} not found on PATH — the digest check cannot run, and an unverified 1.4 MB of cluster-admin YAML is VOID, not clean"
done

WORKDIR="$(mktemp -d)"

fetch_and_verify() {
  # fetch_and_verify <label> <url> <expected-sha256> <expected-bytes> <basename>
  local label="$1" url="$2" want_sha="$3" want_bytes="$4" base="$5"
  local dest="$WORKDIR/$base" actual_sha actual_bytes

  if [ -n "$ARTEFACT_DIR" ]; then
    [ -f "$ARTEFACT_DIR/$base" ] || void "--artefact-dir was given but ${ARTEFACT_DIR}/${base} does not exist"
    cp "$ARTEFACT_DIR/$base" "$dest"
    echo "${label}: read from ${ARTEFACT_DIR}/${base} (still digest-verified below)"
  else
    echo "${label}: fetching ${url}"
    curl -fsSL --max-time 120 -o "$dest" "$url" || \
      void "could not download ${label} from ${url}. A failed download is VOID — this script does NOT fall back to an unpinned or differently-named artefact, because a substituted artefact is the exact supply-chain risk the digest exists to catch."
  fi

  actual_bytes="$(wc -c < "$dest" | tr -d '[:space:]')"
  if [ "$actual_bytes" != "$want_bytes" ]; then
    echo "${label}: expected ${want_bytes} bytes, got ${actual_bytes}" >&2
    refuse "artefact-size" "${label} is not the size recorded for ${url}. This is the SECONDARY check and it fires first on a truncated transfer, where a bare digest mismatch would be opaque. Refusing to apply."
  fi

  actual_sha="$(sha256sum "$dest" | awk '{print $1}')"
  if [ "$actual_sha" != "$want_sha" ]; then
    echo "${label}: expected sha256 ${want_sha}" >&2
    echo "${label}: actual   sha256 ${actual_sha}" >&2
    refuse "artefact-digest" "${label} does not match its recorded sha256. Either the upstream artefact changed under a pinned tag, or what arrived is not what was pinned. Refusing to apply ~1.4 MB of cluster-admin YAML on an unverified digest. NOTHING has been applied — all artefacts are verified before any of them is installed."
  fi

  ok "${label} verified: ${actual_bytes} bytes, sha256 ${actual_sha}"
}

fetch_and_verify "cert-manager ${CERT_MANAGER_VERSION}"        "$CERT_MANAGER_URL"      "$CERT_MANAGER_SHA256"      "$CERT_MANAGER_BYTES"      "cert-manager.yaml"
fetch_and_verify "rabbitmq cluster-operator ${RABBITMQ_OPERATOR_VERSION}" "$RABBITMQ_OPERATOR_URL" "$RABBITMQ_OPERATOR_SHA256" "$RABBITMQ_OPERATOR_BYTES" "cluster-operator.yml"
fetch_and_verify "ingress-nginx ${INGRESS_NGINX_VERSION}"      "$INGRESS_NGINX_URL"     "$INGRESS_NGINX_SHA256"     "$INGRESS_NGINX_BYTES"     "ingress-nginx.yaml"

# The ORDER claim, asserted against the artefact rather than trusted. If a future
# operator release stops carrying cert-manager CRs this prints 0 and the ordering
# comment above becomes stale — better to see that than to keep asserting it.
CM_DEPS="$(awk '/^apiVersion: cert-manager\.io\/v1/{c++} END{print c+0}' "$WORKDIR/cluster-operator.yml")"
echo "cluster-operator.yml contains ${CM_DEPS} cert-manager.io/v1 object(s) — this is WHY cert-manager is installed first"
[ "$CM_DEPS" -gt 0 ] || echo "NOTE: the operator manifest no longer carries cert-manager objects; the ordering rationale in this header needs revisiting." >&2

# Pitfall 5, measured on the artefact actually about to be applied rather than
# assumed from the release notes.
SNIPPET_KEYS="$(awk '/allow-snippet-annotations/{c++} END{print c+0}' "$WORKDIR/ingress-nginx.yaml")"
echo "ingress-nginx.yaml declares allow-snippet-annotations ${SNIPPET_KEYS} time(s) — 0 means it takes the false default, which is Pitfall 5"

ok "all three artefacts verified BEFORE any apply"

if [ "$MODE" = "verify" ]; then
  echo
  echo "PASS: --verify-artefacts-only — every artefact matched its recorded size and sha256. Nothing was applied."
  exit 0
fi

# ---------------------------------------------------------------------------
# Apply helper: the server-side dry-run is printed VERBATIM before the real
# apply, exactly as scripts/k8s-local-up.sh does.
#
# --server-side is not a preference. Client-side apply stores the whole object in
# a `last-applied-configuration` ANNOTATION, and annotations are capped at
# 262,144 bytes — cert-manager's CRDs exceed that. Server-side apply has no such
# limit; --force-conflicts is what keeps a re-run idempotent when field ownership
# has already been recorded.
# ---------------------------------------------------------------------------
apply_artefact() {
  local label="$1" file="$2"
  echo
  echo "--- ${label}: server-side dry-run (VERBATIM) ---"
  k apply --server-side --force-conflicts --dry-run=server -f "$file"
  echo "--- end ${label} dry-run ---"
  echo
  if [ "$MODE" = "dryrun" ]; then
    echo "SKIP: --dry-run-only — not applying ${label}."
    return 0
  fi
  k apply --server-side --force-conflicts -f "$file"
}

# ---------------------------------------------------------------------------
# STEP 4 — cert-manager, FIRST
# ---------------------------------------------------------------------------
step "STEP 4: cert-manager ${CERT_MANAGER_VERSION}"
apply_artefact "cert-manager" "$WORKDIR/cert-manager.yaml"

if [ "$MODE" != "dryrun" ]; then
  for d in cert-manager cert-manager-webhook cert-manager-cainjector; do
    k -n "$CERT_MANAGER_NS" rollout status "deploy/${d}" --timeout="$ROLLOUT_TIMEOUT" \
      || die "cert-manager Deployment '${d}' did not become available"
  done

  # ---------------------------------------------------------------------------
  # STEP 4b — EXERCISE THE REAL WEBHOOK PATH.
  #
  # A rollout wait is not sufficient and this repo has already paid for learning
  # that (scripts/k8s-local-up.sh STEP 4b): a controller reports ready for the
  # whole window in which its admission Service still resolves to the previous
  # pod IP, and the resulting failure names the MANIFEST rather than the
  # infrastructure. A --dry-run=server object creates nothing, and any ANSWER —
  # accept or deny — proves reachability. Only a TRANSPORT failure is retried.
  # ---------------------------------------------------------------------------
  step "STEP 4b: cert-manager admission webhook reachability"
  webhook_answers() {
    local out
    out="$(k apply -n default --dry-run=server -f - 2>&1 <<'PROBE'
apiVersion: cert-manager.io/v1
kind: Issuer
metadata:
  name: staging-bootstrap-webhook-readiness-probe
spec:
  selfSigned: {}
PROBE
    )" || true
    ! grep -Eq 'failed calling webhook|no route to host|connection refused|context deadline exceeded|EOF|no matches for kind' <<<"$out"
  }
  WEBHOOK_OK=0
  for attempt in $(seq 1 "$WEBHOOK_ATTEMPTS"); do
    if webhook_answers; then
      ok "cert-manager admission webhook answered on attempt ${attempt}"
      WEBHOOK_OK=1
      break
    fi
    [ "$attempt" -eq 1 ] && echo "waiting for the cert-manager webhook and CRDs to converge..."
    sleep "$WEBHOOK_SLEEP"
  done
  [ "$WEBHOOK_OK" -eq 1 ] || \
    die "the cert-manager admission webhook did not become reachable. This is an INFRASTRUCTURE condition, not a manifest problem — check 'kubectl --context ${KUBE_CONTEXT} -n ${CERT_MANAGER_NS} get pods,endpointslices'. Applying the RabbitMQ operator now would fail on its ${CM_DEPS} cert-manager objects and read like a broken operator release."
fi

# ---------------------------------------------------------------------------
# STEP 5 — RabbitMQ cluster operator
# ---------------------------------------------------------------------------
step "STEP 5: rabbitmq cluster-operator ${RABBITMQ_OPERATOR_VERSION}"
apply_artefact "rabbitmq cluster-operator" "$WORKDIR/cluster-operator.yml"
if [ "$MODE" != "dryrun" ]; then
  k -n "$RABBITMQ_OPERATOR_NS" rollout status deploy/rabbitmq-cluster-operator --timeout="$ROLLOUT_TIMEOUT" \
    || die "the RabbitMQ cluster operator did not become available"
fi

# ---------------------------------------------------------------------------
# STEP 6 — ingress-nginx, its ConfigMap and the static IP binding
# ---------------------------------------------------------------------------
step "STEP 6: ingress-nginx ${INGRESS_NGINX_VERSION}"
apply_artefact "ingress-nginx" "$WORKDIR/ingress-nginx.yaml"

if [ "$MODE" != "dryrun" ]; then
  k -n "$INGRESS_NS" rollout status deploy/ingress-nginx-controller --timeout="$ROLLOUT_TIMEOUT" \
    || die "the ingress-nginx controller did not become available"

  # --- Pitfall 5, applied and announced --------------------------------------
  echo
  echo "SECURITY-HEADER ROUTE TAKEN: controller ConfigMap"
  echo "  allow-snippet-annotations = true      (default false since v1.9, the CVE-2021-25742 mitigation)"
  echo "  annotations-risk-level    = Critical  (default High; configuration-snippet is classified Critical,"
  echo "                                         so the snippet flag ALONE would not be enough)"
  echo "  Acceptance recorded 2026-08-10 in this script's header: single-tenant cluster, Ingress"
  echo "  objects only from this repository's reviewed manifests, no second tenant with Ingress RBAC."
  echo "  Strictly better follow-up, NOT done here: a controller-level add-headers ConfigMap plus a"
  echo "  k8s/staging patch nulling the snippet annotation — files this plan does not own."
  k -n "$INGRESS_NS" patch configmap "$INGRESS_CONTROLLER_CM" --type merge -p \
    '{"data":{"allow-snippet-annotations":"true","annotations-risk-level":"Critical"}}' >/dev/null
  k -n "$INGRESS_NS" rollout restart deploy/ingress-nginx-controller >/dev/null
  k -n "$INGRESS_NS" rollout status  deploy/ingress-nginx-controller --timeout="$ROLLOUT_TIMEOUT" \
    || die "the ingress-nginx controller did not come back after the ConfigMap change"
  ok "controller ConfigMap patched and the controller rolled so it re-reads it"

  # --- the static IP binding -------------------------------------------------
  # ANNOTATIONS, NOT spec.loadBalancerIP. Confirmed against Microsoft's current
  # static-IP how-to on 2026-08-10 rather than copied from the research, which
  # flagged the spelling MEDIUM confidence: `loadBalancerIP` "is still functional
  # but is being deprecated", and the annotation form is preferred because it
  # makes LoadBalancer creation efficient and avoids throttling.
  # `azure-load-balancer-resource-group` is required because the IP lives in the
  # AKS NODE resource group, not the cluster's own.
  echo
  echo "binding ${INGRESS_CONTROLLER_SVC} to the pre-created static IP '${STATIC_IP_NAME}' in '${NODE_RESOURCE_GROUP}'"
  k -n "$INGRESS_NS" annotate service "$INGRESS_CONTROLLER_SVC" --overwrite \
    "service.beta.kubernetes.io/azure-load-balancer-resource-group=${NODE_RESOURCE_GROUP}" \
    "service.beta.kubernetes.io/azure-pip-name=${STATIC_IP_NAME}" >/dev/null
  ok "static IP annotations set on service/${INGRESS_CONTROLLER_SVC}"
fi

# ---------------------------------------------------------------------------
# STEP 7 — evidence
# ---------------------------------------------------------------------------
step "STEP 7: evidence"

ASSIGNED_IP="(not resolved)"
SNIPPET_EFFECTIVE="(not resolved)"
if [ "$MODE" != "dryrun" ]; then
  ASSIGNED_IP="$(k -n "$INGRESS_NS" get svc "$INGRESS_CONTROLLER_SVC" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"
  SNIPPET_EFFECTIVE="$(k -n "$INGRESS_NS" get configmap "$INGRESS_CONTROLLER_CM" -o jsonpath='{.data.allow-snippet-annotations}' 2>/dev/null || true)"
fi

cat <<EVIDENCE

--- BEGIN STAGING PLATFORM BOOTSTRAP EVIDENCE ---
captured        : $(date -u +%Y-%m-%dT%H:%M:%SZ)
git commit      : $(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)
mode            : ${MODE}
context         : ${KUBE_CONTEXT:-<none>}
cert-manager    : ${CERT_MANAGER_VERSION}  sha256 ${CERT_MANAGER_SHA256}
rabbitmq-op     : ${RABBITMQ_OPERATOR_VERSION}  sha256 ${RABBITMQ_OPERATOR_SHA256}
ingress-nginx   : ${INGRESS_NGINX_VERSION}  sha256 ${INGRESS_NGINX_SHA256}
snippet route   : ConfigMap allow-snippet-annotations + annotations-risk-level (effective: ${SNIPPET_EFFECTIVE:-<unset>})
ingress LB IP   : ${ASSIGNED_IP:-<pending>}
--- END STAGING PLATFORM BOOTSTRAP EVIDENCE ---

NEXT:
  1. point the four *-staging A records at the ingress LB IP above (manual, at
     Netlify DNS — D-07). Records FIRST, TLS SAN second: all SANs share one
     certificate order and a failed challenge fails the WHOLE order
  2. apply the ClusterIssuer and the RabbitmqCluster CR from the staging overlay
  3. kubectl apply -k k8s/staging
  4. VERIFY THE HEADERS ARE ACTUALLY SERVED, on the response and not the object:
       curl -sI https://app-staging.olajay.co.uk | grep -i strict-transport-security
     Pitfall 5 has no warning sign by construction — the Ingress shows the
     headers and every HTTP check returns 200 whether or not they are sent.
EVIDENCE

if [ "$MODE" = "dryrun" ]; then
  echo
  echo "PASS: --dry-run-only — server-side dry-runs printed, nothing applied."
else
  echo
  echo "PASS: staging platform components installed from digest-verified artefacts."
fi
