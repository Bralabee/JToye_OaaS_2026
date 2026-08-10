#!/usr/bin/env bash
# check-networkpolicy-enforcement.sh — proves a NetworkPolicy DENIAL actually happens.
#
# WHAT GOES WRONG
#
#   Phase 26 recorded NetworkPolicy enforcement as NOT PROVEN. That record is the
#   important part: the manifests under k8s/base/networkpolicies/ are correct, they
#   render correctly, `check-render-invariants.sh` asserts their port sets exactly, and
#   NONE of that is evidence that a single packet was ever dropped. A Kubernetes cluster
#   whose CNI does not implement NetworkPolicy ACCEPTS every one of those objects and
#   enforces nothing at all — `kubectl get networkpolicy` lists them, `kubectl describe`
#   prints the rules, and every pod can still reach every other pod. There is no error,
#   no warning, and no status field that says so.
#
#   So a "NOT PROVEN" that is never revisited silently becomes an inherited pass. This
#   gate exists to make that impossible: DPLY-05 is satisfied by a captured denial, or it
#   is not satisfied.
#
# THE MECHANISM
#
#   Two pods, one image, one target, one port. The ONLY difference is the pod's labels:
#
#     CONTROL  labelled to match an allow-rule  -> the connection MUST succeed
#     DENIED   unlabelled, matched by nothing   -> the connection MUST time out
#
#   00-default-deny.yaml selects every pod in the namespace with `podSelector: {}` for
#   both Ingress and Egress, so a pod carrying no matching label is fully isolated;
#   20-core-java.yaml then re-opens port 9090 from `app=frontend` and `app=edge-go`, and
#   30-edge-go.yaml grants `app=edge-go` the matching egress. The control arm therefore
#   travels a path the manifests explicitly permit and the denied arm travels one nothing
#   permits — which is precisely the difference an unenforcing CNI cannot reproduce.
#
# WHY agnhost AND NOT tcpdump
#
#   A packet capture answers "did bytes arrive", which needs a privileged pod, a node
#   session and an interpretation step. Kubernetes' own e2e test image answers the
#   question directly and unambiguously:
#
#     /agnhost connect <ip>:<port> --timeout=3s --protocol=tcp
#       -> prints the literal string TIMEOUT and exits non-zero when the connection is denied
#       -> prints NOTHING and exits 0 when it is allowed
#
#   That is Microsoft's documented verification recipe for AKS network policy, and it is a
#   two-arm proof out of the box. A refused connection (RST) is NOT a timeout and is NOT
#   reported as one — which matters, because "connection refused" means the packet reached
#   a host that answered, i.e. the policy did NOT drop it.
#
# WHY THE CONTROL ARM RUNS FIRST — the whole point
#
#   A broken probe reads as a perfect security posture. Wrong image, wrong port, wrong
#   pod IP, a target that is not running, a typo in the target selector: every one of
#   those produces a TIMEOUT from the denied arm, and a gate that ran only the denied arm
#   would report the strongest possible result for each. So the control arm runs FIRST,
#   and a control arm that fails makes the whole run VOID (exit 2) rather than allowing a
#   denial to be interpreted. Arm B alone is exactly the result a broken pipeline also
#   produces, by luck; arm A is what makes arm B mean something.
#
# WHAT NOT TO "FIX"
#
#   - NEVER relax the probe to make the gate green. Widening the timeout, retrying the
#     denied arm until it times out, or treating "no output" as a denial all convert this
#     into a gate that cannot fail. If the denied arm connects, the finding is real: the
#     cluster is not enforcing NetworkPolicy.
#   - NEVER drop the always-failing readinessProbe from the control pod. It is not
#     latency padding. The control pod carries `app=edge-go`, and Service/edge-go selects
#     `app=edge-go` — a READY pod with that label is added to the Service's endpoints and
#     starts receiving real traffic it cannot serve. A probe that never reports ready is
#     never an endpoint. It is deliberately an httpGet against a port nothing listens on
#     rather than an exec, because the agnhost image is minimal and an exec probe would
#     depend on a shell being present.
#   - NEVER pipe agnhost's output through a filter running INSIDE the pod. The same
#     minimal-image trap is recorded in scripts/k8s-local-secrets.sh: `mc ls | grep` died
#     with "grep: command not found" AFTER the mutation had already succeeded, reporting
#     failure for a step that worked. All parsing here happens on the host, against text
#     already retrieved with `kubectl logs`.
#   - NEVER let this run against the ambient kube context. See below.
#
# THE EMPLOYER-CLUSTER HAZARD
#
#   On the operator's host the ONLY kubectl context is `sipbihs2aks`, which is EMPLOYER
#   infrastructure. This script CREATES AND DELETES PODS. An ambient-context default
#   would therefore schedule workloads onto someone else's production cluster on a bare
#   invocation with no arguments. The context must be named explicitly, and naming a
#   forbidden one is refused even when it is named — intent is not a safety mechanism.
#   This mirrors k8s_local_assert_context() in scripts/lib/k8s-local-guards.sh.
#
# EXIT CODES — uniform across this phase's gates
#   0 = clean · 1 = a live detection defect · 2 = VOID (could not evaluate)
#
#   VOID on: missing kubectl, an unnamed/absent/forbidden context, an unreachable
#   namespace, no running target pod, a probe pod that never ran to completion,
#   unreadable logs, an unrecognised probe result, or a FAILED CONTROL ARM.
#   "Found nothing" is NEVER "clean".
#
# USAGE
#   scripts/check-networkpolicy-enforcement.sh --context <ctx> [--namespace jtoye-staging]
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || exit 2

void() { echo "VOID: $*" >&2; exit 2; }

# ---- configuration -----------------------------------------------------------------
# Defaults describe the staging shape this phase deploys. Every one is overridable so
# the gate can follow the manifests rather than needing an edit when they move.
KUBE_CONTEXT="${NETPOL_KUBE_CONTEXT:-}"
NAMESPACE="${NETPOL_NAMESPACE:-jtoye-staging}"
TARGET_SELECTOR="${NETPOL_TARGET_SELECTOR:-app=core-java}"
TARGET_PORT="${NETPOL_TARGET_PORT:-9090}"
ALLOWED_LABEL_KEY="${NETPOL_ALLOWED_LABEL_KEY:-app}"
ALLOWED_LABEL_VALUE="${NETPOL_ALLOWED_LABEL_VALUE:-edge-go}"
# Pinned by tag from Kubernetes' own e2e test-image registry. registry.k8s.io rather than
# the k8s.gcr.io spelling in the AKS documentation: k8s.gcr.io was FROZEN in 2023 and
# redirects, and pointing a gate at a frozen registry is a dependency that can only rot.
# Same artefact, same 2.33 pin. A dependency-horizons row for this pin is plan 29-09's
# job, alongside the phase's other new pins — it is NOT recorded yet.
AGNHOST_IMAGE="${NETPOL_AGNHOST_IMAGE:-registry.k8s.io/e2e-test-images/agnhost:2.33}"
# Contexts this script refuses to touch even when named explicitly.
FORBIDDEN_CONTEXTS="${NETPOL_FORBIDDEN_CONTEXTS:-sipbihs2aks}"
PROBE_TIMEOUT="${NETPOL_PROBE_TIMEOUT:-3s}"
POD_DEADLINE="${NETPOL_POD_DEADLINE:-120}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --context)   KUBE_CONTEXT="${2:-}"; shift 2 ;;
    --namespace) NAMESPACE="${2:-}"; shift 2 ;;
    --image)     AGNHOST_IMAGE="${2:-}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --context <kube-context> [--namespace <ns>] [--image <agnhost>]"
      exit 0 ;;
    *) void "unknown argument '$1' — refusing to guess what was meant while holding cluster credentials" ;;
  esac
done

# ---- 1. tooling and context guard, BEFORE any mutation -------------------------------
command -v kubectl >/dev/null 2>&1 \
  || void "kubectl not on PATH — a NetworkPolicy denial cannot be observed without a cluster client"

[ -n "$KUBE_CONTEXT" ] || void "no kube context named.
       This script CREATES AND DELETES PODS, and it will not inherit the ambient
       context to do it: on the operator's host the only kubectl context is
       'sipbihs2aks', which is EMPLOYER infrastructure. Name the target explicitly:
         $0 --context <kube-context> --namespace $NAMESPACE
       An unnamed context is VOID, never clean."

for forbidden in $FORBIDDEN_CONTEXTS; do
  [ "$KUBE_CONTEXT" = "$forbidden" ] \
    && void "context '$KUBE_CONTEXT' is on the refusal list (NETPOL_FORBIDDEN_CONTEXTS).
       That cluster is EMPLOYER infrastructure and this gate schedules pods. Naming it
       explicitly does not make it safe — intent is not a safety mechanism."
done

CTX_NAMES=$(kubectl config get-contexts -o name 2>/dev/null) \
  || void "cannot read the kubeconfig context list — refusing to proceed blind"
command grep -Fxq -- "$KUBE_CONTEXT" <<<"$CTX_NAMES" \
  || void "kube context '$KUBE_CONTEXT' does not exist in this kubeconfig. Known contexts:
$(sed 's/^/         /' <<<"$CTX_NAMES")
       A context that cannot be resolved is VOID — it must never fall back to the default."

K="kubectl --context $KUBE_CONTEXT -n $NAMESPACE --request-timeout=20s"
# shellcheck disable=SC2086
$K get namespace "$NAMESPACE" -o name >/dev/null 2>&1 \
  || void "namespace '$NAMESPACE' is not reachable on context '$KUBE_CONTEXT' — nothing to probe"

# ---- 2. resolve the target -----------------------------------------------------------
# shellcheck disable=SC2086
TARGET_IP=$($K get pod -l "$TARGET_SELECTOR" --field-selector=status.phase=Running \
              -o jsonpath='{.items[0].status.podIP}' 2>/dev/null)
[ -n "$TARGET_IP" ] \
  || void "no RUNNING pod matches '$TARGET_SELECTOR' in namespace '$NAMESPACE', so there is nothing to connect to.
       Every arm below would time out against a target that does not exist, and that reads
       as perfect enforcement. No target is VOID, never clean."

CONTROL_POD="netpol-probe-allowed-$$"
DENIED_POD="netpol-probe-denied-$$"
MANIFEST=""

cleanup() {
  # --wait=false so a failing run is not held open by pod teardown; --ignore-not-found so
  # cleanup is idempotent when an arm never created its pod.
  if [ -n "$KUBE_CONTEXT" ]; then
    kubectl --context "$KUBE_CONTEXT" -n "$NAMESPACE" delete pod \
      "$CONTROL_POD" "$DENIED_POD" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  fi
  [ -n "$MANIFEST" ] && rm -f "$MANIFEST"
  return 0
}
trap cleanup EXIT

# ---- probe runner --------------------------------------------------------------------
# Sets PROBE_PHASE / PROBE_RC / PROBE_OUT for the caller. Returns 1 when the pod could
# not be run to completion at all, which every caller treats as VOID.
PROBE_PHASE=""; PROBE_RC=""; PROBE_OUT=""
run_probe() { # run_probe <pod-name> <labels-yaml-block>
  local pod="$1" labels="$2" deadline phase

  MANIFEST=$(mktemp) || return 1
  cat > "$MANIFEST" <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $pod
  labels:
$labels
spec:
  restartPolicy: Never
  containers:
    - name: probe
      image: $AGNHOST_IMAGE
      command: ["/agnhost", "connect", "$TARGET_IP:$TARGET_PORT", "--timeout=$PROBE_TIMEOUT", "--protocol=tcp"]
      # ALWAYS-FAILING readiness probe. Not padding: a labelled pod that reports Ready is
      # added to Service/edge-go's endpoints and starts receiving real traffic. httpGet on
      # a port nothing listens on, not exec, because the image is minimal.
      readinessProbe:
        httpGet:
          path: /never
          port: 1
        periodSeconds: 5
EOF

  # shellcheck disable=SC2086
  $K apply -f "$MANIFEST" >/dev/null 2>&1 || { rm -f "$MANIFEST"; MANIFEST=""; return 1; }
  rm -f "$MANIFEST"; MANIFEST=""

  deadline=$((SECONDS + POD_DEADLINE))
  phase=""
  while [ "$SECONDS" -lt "$deadline" ]; do
    # shellcheck disable=SC2086
    phase=$($K get pod "$pod" -o jsonpath='{.status.phase}' 2>/dev/null)
    case "$phase" in
      Succeeded|Failed) break ;;
    esac
    sleep 3
  done
  PROBE_PHASE="$phase"
  case "$phase" in
    Succeeded|Failed) ;;
    *) return 1 ;;
  esac

  # shellcheck disable=SC2086
  PROBE_RC=$($K get pod "$pod" -o jsonpath='{.status.containerStatuses[0].state.terminated.exitCode}' 2>/dev/null)
  # shellcheck disable=SC2086
  PROBE_OUT=$($K logs "$pod" 2>/dev/null) || return 1
  [ -n "$PROBE_RC" ] || return 1
  return 0
}

# ---- 3. CONTROL ARM FIRST ------------------------------------------------------------
echo "check-networkpolicy-enforcement  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  context   : $KUBE_CONTEXT"
echo "  namespace : $NAMESPACE"
echo "  target    : $TARGET_SELECTOR at $TARGET_IP:$TARGET_PORT"
echo "  image     : $AGNHOST_IMAGE"
echo
echo "ARM A (control) — pod labelled $ALLOWED_LABEL_KEY=$ALLOWED_LABEL_VALUE, which the manifests PERMIT"

run_probe "$CONTROL_POD" "    $ALLOWED_LABEL_KEY: $ALLOWED_LABEL_VALUE" \
  || void "control arm could not be run to completion (phase='${PROBE_PHASE:-<none>}').
       A probe that never ran proves nothing about a policy, and every denial measured
       afterwards would be meaningless. Check the image pull, scheduling and quota."

CONTROL_RC="$PROBE_RC"
CONTROL_OUT="$PROBE_OUT"
echo "  exit code : $CONTROL_RC"
echo "  raw output: ${CONTROL_OUT:-<empty, which is what agnhost prints on SUCCESS>}"

case "$CONTROL_OUT" in
  *TIMEOUT*)
    void "control arm TIMED OUT. The probe cannot reach a target the manifests explicitly
       permit, so it is broken — wrong target, wrong port, or the allow-rule for
       $ALLOWED_LABEL_KEY=$ALLOWED_LABEL_VALUE is not what this gate believes.
       A denial measured with a broken probe is the strongest-looking and least
       trustworthy result this gate could produce, so the run is VOID." ;;
esac
[ "$CONTROL_RC" = "0" ] \
  || void "control arm exited $CONTROL_RC with output '${CONTROL_OUT:-<empty>}' — a permitted
       connection must exit 0. An unrecognised probe result is VOID, never clean."

echo "  result    : CONNECTED (the probe works)"
echo

# ---- 4. DENIED ARM -------------------------------------------------------------------
echo "ARM B (denied) — same image, same target, UNLABELLED pod, which nothing permits"

run_probe "$DENIED_POD" "    netpol-probe: denied" \
  || void "denied arm could not be run to completion (phase='${PROBE_PHASE:-<none>}').
       A pod that never ran is not a denial — VOID, never clean."

DENIED_RC="$PROBE_RC"
DENIED_OUT="$PROBE_OUT"
echo "  exit code : $DENIED_RC"
echo "  raw output: ${DENIED_OUT:-<empty>}"

# Asserted with `case` against agnhost's own literal, on the host, over text already in a
# variable. There is deliberately NO pipe into a quiet grep anywhere in this file: that
# shape inverts under pipefail when the pattern MATCHES (grep exits at the first hit, the
# writer takes SIGPIPE, pipefail promotes it to 141) and so fails OPEN. It has made a real
# guard in this repo pass when it should have failed. There is also no in-pod filter to be
# missing from a minimal image.
#
# The written-out form of that shape is avoided ON PURPOSE, because a rule that must name
# the token it forbids fires on its own definition — the count of it in this file is meant
# to measure CODE, and a comment that spelled it would make that count permanently 1 and
# the check permanently meaningless.
VERDICT=""
case "$DENIED_OUT" in
  *TIMEOUT*) VERDICT="denied" ;;
  "")
    if [ "$DENIED_RC" = "0" ]; then VERDICT="connected"; else VERDICT="unknown"; fi ;;
  *) VERDICT="unknown" ;;
esac

echo
echo "  arm                                   | result"
echo "  --------------------------------------+------------------------------------------"
printf '  %-37s | %s\n' "A control ($ALLOWED_LABEL_KEY=$ALLOWED_LABEL_VALUE, permitted)" "CONNECTED (exit $CONTROL_RC)"
printf '  %-37s | %s\n' "B denied (unlabelled, not permitted)" "$(
  case "$VERDICT" in
    denied)    echo "TIMEOUT (exit $DENIED_RC) — enforcement is REAL" ;;
    connected) echo "CONNECTED (exit 0) — enforcement is ABSENT" ;;
    *)         echo "unrecognised (exit ${DENIED_RC:-?}) — cannot evaluate" ;;
  esac)"
echo

case "$VERDICT" in
  denied)
    echo "PASS: a connection the manifests permit succeeded, and the identical connection from a"
    echo "      pod nothing permits was DROPPED (agnhost reported TIMEOUT). NetworkPolicy is"
    echo "      enforced on context '$KUBE_CONTEXT'."
    exit 0 ;;
  connected)
    echo "FAIL: the denied arm CONNECTED. Both arms reached $TARGET_IP:$TARGET_PORT, so the" >&2
    echo "      NetworkPolicy objects in this namespace are being stored and not enforced —" >&2
    echo "      the CNI in use does not implement NetworkPolicy, or the plugin is disabled." >&2
    echo "      This is the Phase 26 'NOT PROVEN' finding turning out to be a real gap. Do not" >&2
    echo "      relax this gate; enable a policy-capable network plugin on the cluster." >&2
    exit 1 ;;
  *)
    void "denied arm produced an unrecognised result: exit='${DENIED_RC:-<none>}' output='${DENIED_OUT:-<empty>}'.
       Expected either the literal TIMEOUT (denied) or empty output with exit 0 (connected).
       Anything else means the probe did not do what this gate assumes, and an assumption
       that cannot be checked is VOID, never clean." ;;
esac
