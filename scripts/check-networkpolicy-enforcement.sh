#!/usr/bin/env bash
# check-networkpolicy-enforcement.sh — proves a NetworkPolicy DENIAL actually happens (DPLY-05,
# #297; carried from phase-29-research and extended by plan 29-17).
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
#   is not satisfied. Staging's engine is AKS-native Cilium (chosen at creation, plan
#   29-09); #297 closes on this proof, not on the product name.
#
# THE MECHANISM
#
#   Probe pods, one image, the SAME connection; the ONLY difference between a control and
#   its denial is the client pod's labels:
#
#     CONTROL  labelled to match an allow-rule  -> the connection MUST succeed
#     DENIED   unlabelled, matched by nothing   -> the connection MUST time out
#
#   00-default-deny.yaml selects every pod in the namespace with `podSelector: {}` for
#   both Ingress and Egress, so a pod carrying no matching label is fully isolated, and
#   20-core-java.yaml re-opens 9090 from app=frontend, app=edge-go and the controller
#   namespace, and 9091 from app=prometheus.
#
#   The arms, run in EXACTLY this order, every control before its denial. Each prints one
#   line `ARM <id> <verdict> <ISO-8601 UTC>`, so the order and the verdicts are checkable:
#
#     P0  policy-free  a client in a fresh namespace np-probe-<RUN_ID> (no NetworkPolicy at
#                      all) connects to an agnhost netexec target in the same namespace
#                      AND to the public address the E2 denial uses. CONNECTED required:
#                      it proves the image, the command and the path work where no policy
#                      exists, so a TIMEOUT elsewhere is the policy's. The strongest
#                      control. Not CONNECTED -> VOID.
#     A   control      app=frontend in jtoye-staging -> Service core-java:9090
#     B   denied       unlabelled -> Service core-java:9090
#     F1  claim        a pod in namespace traefik -> Service core-java:9090 (Blocker F: the
#                      controller namespace reaches core-java). Not CONNECTED -> FAIL.
#     M1  control      app=prometheus -> the core-java POD IP:9091 (management)
#     M2  denied       unlabelled -> the core-java pod IP:9091
#     E1  control      app=core-java -> 1.1.1.1:443 (in core-java's egress allow-list)
#     E2  denied       app=core-java -> 1.1.1.1:${NP_EGRESS_DENY_PORT:-80} (not in it)
#     K1  control      app=frontend -> Service keycloak:8080
#     K2  denied       unlabelled -> Service keycloak:8080
#
#   `--only ARM` runs P0, the named arm's control (when ARM is a denial) and ARM. The
#   Blocker-F live arm (plan 29-17 Task 2) uses `--only F1` while core-java-allow's traefik
#   rule is removed: F1 must then read DENIED and the run exits 1.
#
# THE SCRATCH EGRESS POLICY (np-probe-<RUN_ID>-egress)
#
#   default-deny also denies every probe pod's EGRESS. Without help, B's TIMEOUT would be
#   the probe's own egress denial — the core-java ingress rule under test would never be
#   exercised — and M1 could never connect at all, because no Prometheus egress policy
#   exists until plan 29-23. So the run creates one policy selecting ONLY this run's probe
#   pods (np-probe=<RUN_ID>), allowing egress ONLY to app=core-java on 9090 and 9091 and to
#   app=keycloak on 8080. With it, every denial below is the TARGET's ingress allow-list
#   (B, M2, K2) or core-java's own egress allow-list (E2). It opens nothing for any real
#   workload: no workload carries the run's label, and it is deleted with the run.
#
# WHY agnhost AND NOT tcpdump
#
#   A packet capture answers "did bytes arrive", which needs a privileged pod, a node
#   session and an interpretation step. Kubernetes' own e2e test image answers the
#   question directly and unambiguously (measured on the pinned 2.66.1, 2026-10-19):
#
#     /agnhost connect <ip>:<port> --timeout=3s --protocol=tcp
#       -> prints TIMEOUT and exits 1 when the connection is dropped
#       -> prints NOTHING and exits 0 when it connects
#       -> prints REFUSED and exits 1 on a reset; DNS: ... / OTHER: ... otherwise
#
#   A refused connection (RST) is NOT a timeout and is NEVER a denial: "refused" means the
#   packet reached a host that answered, i.e. the policy did NOT drop it. Cilium drops on a
#   policy denial; it does not reset.
#
#   The probes are long-lived `agnhost pause` pods and every connection is a
#   `kubectl exec`. kubectl appends "command terminated with exit code N" to stderr for a
#   non-zero remote exit; the classifier strips exactly that line, and requires its N to
#   equal the exit status, before it reads agnhost's own output.
#
# WHY THE CONTROL ARM RUNS FIRST — the whole point
#
#   A broken probe reads as a perfect security posture. Wrong image, wrong port, wrong
#   pod IP, a target that is not running, a typo in the target selector: every one of
#   those produces a TIMEOUT from the denied arm, and a gate that ran only the denied arm
#   would report the strongest possible result for each. So P0 and each control run
#   FIRST, and a control that is not CONNECTED makes the whole run VOID (exit 2) rather
#   than allowing a denial to be interpreted. A denial alone is exactly the result a broken
#   pipeline also produces; the controls are what make it mean something.
#
# RUN-ID HYGIENE
#
#   RUN_ID is a UTC timestamp plus a random suffix. Every object the run creates (the
#   probe namespace, every pod, the scratch policy) is NAMED with it and LABELLED
#   np-probe=<RUN_ID>. At start-up any np-probe-labelled object carrying ANOTHER run id is
#   reported and the run is REFUSED (exit 1) with nothing deleted: it may be a concurrent
#   run, and deleting it would void that run's proof. An EXIT trap deletes exactly this
#   run's names (--wait=true --timeout=180s), re-lists `-l np-probe=<RUN_ID>` and prints
#   `CLEANUP remaining=N`; anything but 0 is exit 1. The same listing is shown able to see
#   what it must later not find: right after creation it must count every object
#   (`ARTEFACTS seen=n expected=n`), or the run is VOID.
#
# WHAT NOT TO "FIX"
#
#   - NEVER relax the probe to make the gate green. Widening the timeout, retrying the
#     denied arm until it times out, or treating "no output" as a denial all convert this
#     into a gate that cannot fail. If a denied arm connects, the finding is real.
#   - NEVER widen any NetworkPolicy to make a proof pass (a 0.0.0.0/0 peer, a broader
#     selector, an extra port). The scratch egress policy above selects only this run's
#     pods and names only the targets under test; never give it an ipBlock, an ingress
#     rule or a wider selector.
#   - NEVER drop the always-failing readinessProbe from the probe pods. It is not latency
#     padding. A probe carries app=core-java / app=frontend / app=prometheus, and a READY
#     pod whose labels match a Service selector is added to its endpoints and receives
#     real traffic it cannot serve. A probe that never reports ready is never an
#     endpoint. It is an httpGet against a port nothing listens on rather than an exec,
#     because the agnhost image is minimal.
#   - NEVER pick the core-java pod IP with `-l app=core-java`: the E probe carries that
#     label. The serving pod is read from the Service's EndpointSlice (ready=true).
#   - NEVER pipe agnhost's output through a filter running INSIDE the pod. The same
#     minimal-image trap is recorded in scripts/k8s-local-secrets.sh: `mc ls | grep` died
#     with "grep: command not found" AFTER the mutation had already succeeded, reporting
#     failure for a step that worked. All parsing here happens on the host, against text
#     already retrieved.
#   - NEVER use port 25 for E2. Azure blocks outbound TCP 25 for most subscription types
#     at the platform, so a TIMEOUT there is Azure's, not the policy's; P0's external leg
#     is what would expose that (it must CONNECT on the same port with no policy present).
#   - NEVER let this run against the ambient kube context. See below.
#
# THE EMPLOYER-CLUSTER HAZARD
#
#   This script CREATES AND DELETES PODS, A NAMESPACE AND A NETWORKPOLICY. --context is
#   REQUIRED and passes scripts/lib/staging-kube-guard.sh: the employer's sipbihs2aks is
#   refused by name, and the context's server must equal the staging API server recorded
#   in k8s/staging/azure-identity.yaml (which also refuses the stale `jtoye-staging`
#   context of the deleted August cluster).
#
# THE IMAGE
#
#   Read from k8s/platform/acme-probe/probe.yaml (plan 29-03's tag+digest pin): one pin,
#   one horizon row (infra/dependency-horizons.yaml `agnhost`). Exactly one agnhost image
#   line must be found there, or VOID.
#
# EXIT CODES — uniform across this phase's gates
#   0 = clean · 1 = a live detection defect (or REFUSED, or CLEANUP remaining>0)
#   2 = VOID (could not evaluate) / USAGE ERROR
#
#   VOID on: missing kubectl/kubelogin, an unnamed context (USAGE ERROR), no Service
#   endpoint to target, a probe pod that never ran, an artefact listing that cannot see
#   what the run created, a policy-free arm or a control arm that is not CONNECTED, or an
#   unrecognised probe result. "Found nothing" is NEVER "clean".
#
# USAGE
#   scripts/check-networkpolicy-enforcement.sh --context jtoye-stg-aks [--only ARM]
#   scripts/check-networkpolicy-enforcement.sh --selftest      (TAP 13; no cluster)
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" || exit 2
IDENTITY_FILE="${NP_IDENTITY_FILE:-$ROOT/k8s/staging/azure-identity.yaml}"
IMAGE_FILE="$ROOT/k8s/platform/acme-probe/probe.yaml"
NAMESPACE="jtoye-staging"
CONTROLLER_NS="traefik"
PROBE_TIMEOUT="${NP_PROBE_TIMEOUT:-3s}"
POD_DEADLINE="${NP_POD_DEADLINE:-180}"
SETTLE_SECONDS="${NP_SETTLE_SECONDS:-5}"
EGRESS_ALLOW_ADDR="1.1.1.1"
EGRESS_ALLOW_PORT=443
EGRESS_DENY_PORT="${NP_EGRESS_DENY_PORT:-80}"

ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# ---- pure functions (covered by --selftest) -------------------------------------------

# classify OUTPUT RC -> CONNECTED | DENIED | RESET | UNKNOWN
classify() {
  local out="$1" rc="$2" line body="" trailer=""
  while IFS= read -r line || [ -n "$line" ]; do
    if [[ "$line" =~ ^command\ terminated\ with\ exit\ code\ ([0-9]+)$ ]]; then
      trailer="${BASH_REMATCH[1]}"
      continue
    fi
    [ -z "${line//[[:space:]]/}" ] && continue
    body+="${body:+$'\n'}$line"
  done <<<"$out"
  if [ -n "$trailer" ] && [ "$trailer" != "$rc" ]; then
    echo UNKNOWN; return 0
  fi
  case "$body" in
    *TIMEOUT*|*[Rr]efused*|*REFUSED*)
      if [ "$rc" != "0" ]; then echo DENIED; else echo UNKNOWN; fi ;;
    "")
      if [ "$rc" = "0" ]; then echo CONNECTED; else echo UNKNOWN; fi ;;
    *) echo UNKNOWN ;;
  esac
}

# pair_verdict CONTROL_VERDICT DENIED_VERDICT -> PASS | FAIL | VOID
pair_verdict() {
  local c="$1" d="$2"
  [ "$c" = "CONNECTED" ] || { echo VOID; return 0; }
  case "$d" in
    DENIED) echo PASS ;;
    CONNECTED|RESET) echo FAIL ;;
    *) echo VOID ;;
  esac
}

# policy_free_verdict VERDICT -> OK | VOID
policy_free_verdict() { if [ "$1" = "CONNECTED" ]; then echo OK; else echo VOID; fi; }

# claim_verdict VERDICT -> PASS | FAIL | VOID   (an unpaired CONNECTED claim, e.g. F1)
claim_verdict() {
  case "$1" in
    CONNECTED) echo PASS ;;
    DENIED|RESET) echo FAIL ;;
    *) echo VOID ;;
  esac
}

# foreign_artefacts RUN_ID LISTING -> prints the foreign lines; returns 1 when any exist.
# LISTING lines are "<kind>/<namespace>/<name> <np-probe label value>".
foreign_artefacts() {
  local run="$1" listing="$2" line val found=1
  while IFS= read -r line; do
    [ -z "${line//[[:space:]]/}" ] && continue
    val="${line##* }"
    if [ "$val" != "$run" ]; then echo "$line"; found=0; fi
  done <<<"$listing"
  [ "$found" -eq 0 ] && return 1
  return 0
}

# artefact_verdict SEEN EXPECTED RC -> OK | VOID
artefact_verdict() {
  if [ "$3" = "0" ] && [ "$1" = "$2" ] && [ "$2" -gt 0 ] 2>/dev/null; then echo OK; else echo VOID; fi
}

selftest() {
  local n=0 failed=0
  t() { # t NAME EXPECTED ACTUAL
    n=$((n + 1))
    if [ "$2" = "$3" ]; then
      echo "ok $n - $1"
    else
      echo "not ok $n - $1"
      echo "  ---"
      echo "  message: 'expected $2, got $3'"
      echo "  ..."
      failed=$((failed + 1))
    fi
  }
  echo "TAP version 13"
  echo "1..21"
  t "classify-timeout-is-denied"            DENIED    "$(classify 'TIMEOUT' 1)"
  t "classify-timeout-with-kubectl-trailer" DENIED    "$(classify $'TIMEOUT\ncommand terminated with exit code 1' 1)"
  t "classify-empty-rc0-is-connected"       CONNECTED "$(classify '' 0)"
  t "classify-REFUSED-is-reset"             RESET     "$(classify 'REFUSED' 1)"
  t "classify-connection-refused-is-reset"  RESET     "$(classify 'OTHER: dial tcp 10.0.0.1:9091: connect: connection refused' 1)"
  t "classify-refused-with-trailer-is-reset" RESET    "$(classify $'REFUSED\ncommand terminated with exit code 1' 1)"
  t "classify-timeout-rc0-is-unknown"       UNKNOWN   "$(classify 'TIMEOUT' 0)"
  t "classify-empty-rc1-is-unknown"         UNKNOWN   "$(classify '' 1)"
  t "classify-dns-error-is-unknown"         UNKNOWN   "$(classify 'DNS: lookup core-java on 10.0.0.10:53: i/o timeout' 1)"
  t "classify-exec-error-is-unknown"        UNKNOWN   "$(classify 'error: unable to upgrade connection: container not found ("probe")' 1)"
  t "classify-trailer-code-mismatch-unknown" UNKNOWN  "$(classify $'TIMEOUT\ncommand terminated with exit code 2' 1)"
  t "pair-control-connected-denied-pass"    PASS      "$(pair_verdict CONNECTED DENIED)"
  t "pair-control-denied-is-void"           VOID      "$(pair_verdict DENIED DENIED)"
  t "pair-control-unknown-is-void"          VOID      "$(pair_verdict UNKNOWN DENIED)"
  t "pair-denied-arm-connected-is-fail"     FAIL      "$(pair_verdict CONNECTED CONNECTED)"
  t "pair-denied-arm-reset-is-fail-never-pass" FAIL   "$(pair_verdict CONNECTED RESET)"
  t "policy-free-not-connected-is-void"     VOID      "$(policy_free_verdict DENIED)"
  t "policy-free-connected-ok"              OK        "$(policy_free_verdict CONNECTED)"
  local own="20261019t120000z-ab12"
  t "foreign-artefact-refused"              1         "$(foreign_artefacts "$own" $'pod/jtoye-staging/np-20261019t110000z-ff00-bare 20261019t110000z-ff00' >/dev/null; echo $?)"
  t "own-artefacts-not-refused"             0         "$(foreign_artefacts "$own" "pod/jtoye-staging/np-$own-bare $own" >/dev/null; echo $?)"
  t "artefact-count-mismatch-void"          VOID      "$(artefact_verdict 6 7 0)"
  if [ "$failed" -eq 0 ]; then
    echo "# PASS: $n of $n"
    return 0
  fi
  echo "# FAIL: $failed of $n"
  return 1
}

usage() {
  echo "usage: $0 --context <kube-context> [--only P0|A|B|F1|M1|M2|E1|E2|K1|K2]" >&2
  echo "       $0 --selftest" >&2
}

# ---- arguments ---------------------------------------------------------------------
CONTEXT=""; ONLY=""; MODE="run"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --context)  CONTEXT="${2:-}"; shift 2 || { usage; exit 2; } ;;
    --context=*) CONTEXT="${1#*=}"; shift ;;
    --only)     ONLY="${2:-}"; shift 2 || { usage; exit 2; } ;;
    --only=*)   ONLY="${1#*=}"; shift ;;
    --selftest) MODE="selftest"; shift ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "USAGE ERROR: unknown argument '$1' (refusing to guess while holding cluster credentials)" >&2; usage; exit 2 ;;
  esac
done

if [ "$MODE" = "selftest" ]; then
  selftest; exit $?
fi

# ---- 1. guard, before any read or write ------------------------------------------------
# shellcheck source=lib/staging-kube-guard.sh
if ! source "$ROOT/scripts/lib/staging-kube-guard.sh"; then
  echo "VOID: could not source scripts/lib/staging-kube-guard.sh" >&2; exit 2
fi
staging_kube_guard "$CONTEXT" "$IDENTITY_FILE"; guard_rc=$?
if [ "$guard_rc" -ne 0 ]; then usage; exit "$guard_rc"; fi

ARMS_ALL="P0 A B F1 M1 M2 E1 E2 K1 K2"
if [ -n "$ONLY" ]; then
  case " $ARMS_ALL " in
    *" $ONLY "*) ;;
    *) echo "USAGE ERROR: --only '$ONLY' is not one of: $ARMS_ALL" >&2; exit 2 ;;
  esac
fi
control_of() { case "$1" in B) echo A ;; M2) echo M1 ;; E2) echo E1 ;; K2) echo K1 ;; *) echo "" ;; esac; }
if [ -n "$ONLY" ]; then
  ARMS="P0"
  c=$(control_of "$ONLY"); [ -n "$c" ] && ARMS="$ARMS $c"
  [ "$ONLY" != "P0" ] && ARMS="$ARMS $ONLY"
else
  ARMS="$ARMS_ALL"
fi

void() { echo "VOID: $*" >&2; exit 2; }

mapfile -t IMG_LINES < <(command grep -E '^[[:space:]]*image:[[:space:]]*registry\.k8s\.io/e2e-test-images/agnhost:' "$IMAGE_FILE" 2>/dev/null)
[ "${#IMG_LINES[@]}" -eq 1 ] || void "expected exactly 1 agnhost image line in $IMAGE_FILE, found ${#IMG_LINES[@]}"
IMAGE="${IMG_LINES[0]#*image:}"; IMAGE="${IMAGE//[[:space:]]/}"
[[ "$IMAGE" == *@sha256:* ]] || void "agnhost pin in $IMAGE_FILE is not digest-pinned: $IMAGE"

KC=(kubectl --context "$CONTEXT" --request-timeout=30s)
KCD=(kubectl --context "$CONTEXT")   # deletes wait up to 180s; no 30s request cap

RUN_ID="$(date -u +%Y%m%dt%H%M%Sz)-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
PROBE_NS="np-probe-$RUN_ID"
SCRATCH_NP="np-probe-$RUN_ID-egress"

echo "check-networkpolicy-enforcement  ($(ts))"
echo "  context   : $CONTEXT"
echo "  run id    : $RUN_ID"
echo "  image     : $IMAGE"
echo "  arms      : $ARMS"

# ---- 2. foreign artefacts: refuse, delete nothing ----------------------------------------
LIST_ERR=$(mktemp) || void "mktemp failed"
listing=$("${KC[@]}" get namespaces,pods,networkpolicies -A -l np-probe \
  -o 'custom-columns=K:.kind,NS:.metadata.namespace,N:.metadata.name,R:.metadata.labels.np-probe' \
  --no-headers 2>"$LIST_ERR"); lrc=$?
if [ "$lrc" -ne 0 ]; then
  cat "$LIST_ERR" >&2; rm -f "$LIST_ERR"; void "cannot list np-probe artefacts (rc $lrc)"
fi
rm -f "$LIST_ERR"
listing=$(awk 'NF==4{print $1"/"$2"/"$3" "$4}' <<<"$listing")
if ! foreign=$(foreign_artefacts "$RUN_ID" "$listing"); then
  echo "REFUSED: np-probe artefacts from another run exist; nothing was deleted:" >&2
  sed 's/^/  /' <<<"$foreign" >&2
  echo "  (a concurrent run, or an interrupted one whose trap never ran; inspect, then delete by name)" >&2
  exit 1
fi

# ---- 3. what this run creates, and the trap that deletes exactly that ------------------
uses() { case " $ARMS " in *" $1 "*) return 0 ;; esac; return 1; }

declare -a CREATED=()   # "kind namespace name" (namespace "-" for cluster-scoped)
CREATED_NS=0
on_exit() {
  local rc=$? remaining rrc entry kind ns name
  trap - EXIT
  if [ "${#CREATED[@]}" -gt 0 ]; then
    for entry in "${CREATED[@]}"; do
      read -r kind ns name <<<"$entry"
      [ "$kind" = "namespace" ] && continue
      "${KCD[@]}" -n "$ns" delete "$kind" "$name" --ignore-not-found --wait=true --timeout=180s >/dev/null 2>&1
    done
    if [ "$CREATED_NS" -eq 1 ]; then
      "${KCD[@]}" delete namespace "$PROBE_NS" --ignore-not-found --wait=true --timeout=180s >/dev/null 2>&1
    fi
    remaining=$("${KC[@]}" get namespaces,pods,networkpolicies -A -l "np-probe=$RUN_ID" -o name 2>/dev/null); rrc=$?
    if [ "$rrc" -ne 0 ]; then
      echo "CLEANUP remaining=unknown (listing rc $rrc)"
      [ "$rc" -eq 0 ] && rc=1
    else
      if [ -z "$remaining" ]; then n=0; else n=$(command grep -c '' <<<"$remaining"); fi
      echo "CLEANUP remaining=$n"
      if [ "$n" -ne 0 ]; then
        sed 's/^/  left: /' <<<"$remaining"
        [ "$rc" -eq 0 ] && rc=1
      fi
    fi
  fi
  exit "$rc"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

pod_yaml() { # pod_yaml NAME NAMESPACE "label: value" (extra labels, may be empty) MODE(pause|target)
  local name="$1" ns="$2" extra="$3" mode="$4" args probe
  if [ "$mode" = "target" ]; then
    args='["netexec", "--http-port=8080"]'
    probe='        httpGet:
          path: /
          port: 8080'
  else
    args='["pause"]'
    # ALWAYS-FAILING readiness probe: never an endpoint of any Service (see header).
    probe='        httpGet:
          path: /never
          port: 1'
  fi
  cat <<EOF
---
apiVersion: v1
kind: Pod
metadata:
  name: $name
  namespace: $ns
  labels:
    np-probe: "$RUN_ID"
$( [ -n "$extra" ] && echo "    $extra" )
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  terminationGracePeriodSeconds: 1
  securityContext:
    runAsNonRoot: true
    runAsUser: 65532
    runAsGroup: 65532
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: probe
      image: $IMAGE
      imagePullPolicy: IfNotPresent
      args: $args
      readinessProbe:
$probe
        periodSeconds: 5
      resources:
        requests:
          cpu: 5m
          memory: 16Mi
        limits:
          cpu: 50m
          memory: 32Mi
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        runAsNonRoot: true
        capabilities:
          drop: ["ALL"]
EOF
}

P_TARGET="np-$RUN_ID-target"; P_CLIENT="np-$RUN_ID-client"
P_FRONT="np-$RUN_ID-frontend"; P_BARE="np-$RUN_ID-bare"; P_PROM="np-$RUN_ID-prom"
P_CJ="np-$RUN_ID-corejava"; P_TRAEFIK="np-$RUN_ID-traefik"

MANIFEST=$(mktemp) || void "mktemp failed"
{
  cat <<EOF
---
apiVersion: v1
kind: Namespace
metadata:
  name: $PROBE_NS
  labels:
    np-probe: "$RUN_ID"
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: $SCRATCH_NP
  namespace: $NAMESPACE
  labels:
    np-probe: "$RUN_ID"
spec:
  podSelector:
    matchLabels:
      np-probe: "$RUN_ID"
  policyTypes:
    - Egress
  egress:
    - to:
        - podSelector:
            matchLabels:
              app: core-java
      ports:
        - protocol: TCP
          port: 9090
        - protocol: TCP
          port: 9091
    - to:
        - podSelector:
            matchLabels:
              app: keycloak
      ports:
        - protocol: TCP
          port: 8080
EOF
} >"$MANIFEST"
CREATED+=("networkpolicy $NAMESPACE $SCRATCH_NP")
CREATED_NS=1; CREATED+=("namespace - $PROBE_NS")
WAIT_PODS=()  # "namespace name"
add_pod() { pod_yaml "$1" "$2" "$3" "$4" >>"$MANIFEST"; CREATED+=("pod $2 $1"); WAIT_PODS+=("$2 $1 $4"); }
add_pod "$P_TARGET" "$PROBE_NS" "" target
add_pod "$P_CLIENT" "$PROBE_NS" "" pause
{ uses A || uses K1; } && add_pod "$P_FRONT" "$NAMESPACE" "app: frontend" pause
{ uses B || uses M2 || uses K2; } && add_pod "$P_BARE" "$NAMESPACE" "" pause
uses M1 && add_pod "$P_PROM" "$NAMESPACE" "app: prometheus" pause
{ uses E1 || uses E2; } && add_pod "$P_CJ" "$NAMESPACE" "app: core-java" pause
uses F1 && add_pod "$P_TRAEFIK" "$CONTROLLER_NS" "" pause

APPLY_OUT=$("${KC[@]}" apply -f "$MANIFEST" 2>&1); arc=$?
rm -f "$MANIFEST"
[ "$arc" -eq 0 ] || void "apply of the probe objects failed (rc $arc): $APPLY_OUT"

# ---- 4. the listing control: the selector that later proves "nothing left" sees all ----
LIST_ERR=$(mktemp) || void "mktemp failed"
seen_out=$("${KC[@]}" get namespaces,pods,networkpolicies -A -l "np-probe=$RUN_ID" -o name 2>"$LIST_ERR"); src=$?
serr=$(<"$LIST_ERR"); rm -f "$LIST_ERR"
if [ -z "$seen_out" ]; then seen=0; else seen=$(command grep -c '' <<<"$seen_out"); fi
expected=${#CREATED[@]}
echo "ARTEFACTS seen=$seen expected=$expected"
[ "$(artefact_verdict "$seen" "$expected" "$src")" = "OK" ] \
  || void "the np-probe=$RUN_ID listing (rc $src, stderr '${serr}') does not see every object the run created; a cleanup check through it would be blind"

# ---- 5. wait for every probe to run (targets Ready, probes never Ready by design) ------
deadline=$((SECONDS + POD_DEADLINE))
for entry in "${WAIT_PODS[@]}"; do
  read -r ns name mode <<<"$entry"
  while :; do
    if [ "$mode" = "target" ]; then
      st=$("${KC[@]}" -n "$ns" get pod "$name" -o 'jsonpath={.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)
      [ "$st" = "True" ] && break
    else
      st=$("${KC[@]}" -n "$ns" get pod "$name" -o 'jsonpath={.status.containerStatuses[0].state.running.startedAt}' 2>/dev/null)
      [ -n "$st" ] && break
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      why=$("${KC[@]}" -n "$ns" get pod "$name" -o 'jsonpath={.status.phase} {.status.containerStatuses[0].state}' 2>&1)
      void "probe pod $ns/$name did not start within ${POD_DEADLINE}s ($why)"
    fi
    sleep 3
  done
done
sleep "$SETTLE_SECONDS"

# ---- 6. targets: Service ClusterIPs and the SERVING core-java pod --------------------
svc_ip() { "${KC[@]}" -n "$NAMESPACE" get svc "$1" -o 'jsonpath={.spec.clusterIP}' 2>/dev/null; }
ready_ep() { # first ready endpoint address of Service $1
  "${KC[@]}" -n "$NAMESPACE" get endpointslices -l "kubernetes.io/service-name=$1" \
    -o 'jsonpath={range .items[*].endpoints[?(@.conditions.ready==true)]}{.addresses[0]}{"\n"}{end}' 2>/dev/null \
    | awk 'NF{print; exit}'
}
CJ_SVC=$(svc_ip core-java); KC_SVC=$(svc_ip keycloak)
CJ_POD=$(ready_ep core-java); KC_EP=$(ready_ep keycloak)
TARGET_IP=$("${KC[@]}" -n "$PROBE_NS" get pod "$P_TARGET" -o 'jsonpath={.status.podIP}' 2>/dev/null)
[ -n "$CJ_SVC" ] || void "Service core-java has no ClusterIP"
[ -n "$KC_SVC" ] || void "Service keycloak has no ClusterIP"
[ -n "$CJ_POD" ] || void "Service core-java has no READY endpoint: every arm would time out against nothing"
[ -n "$KC_EP" ]  || void "Service keycloak has no READY endpoint: every arm would time out against nothing"
[ -n "$TARGET_IP" ] || void "the policy-free target has no pod IP"
np_in_probe_ns=$("${KC[@]}" -n "$PROBE_NS" get networkpolicies -o name 2>/dev/null); nprc=$?
[ "$nprc" -eq 0 ] && [ -z "$np_in_probe_ns" ] || void "namespace $PROBE_NS is not policy-free (rc $nprc: $np_in_probe_ns)"
echo "  targets   : core-java svc $CJ_SVC:9090, core-java pod $CJ_POD:9091, keycloak svc $KC_SVC:8080 (ready ep $KC_EP), policy-free target $TARGET_IP:8080"
echo "  egress    : allowed $EGRESS_ALLOW_ADDR:$EGRESS_ALLOW_PORT, denied-arm $EGRESS_ALLOW_ADDR:$EGRESS_DENY_PORT"
echo "  $PROBE_NS NetworkPolicies: 0"
echo

# ---- 7. the arms ---------------------------------------------------------------------
connect() { # connect NS POD DEST -> sets V (verdict) and prints the raw result
  local ns="$1" pod="$2" dest="$3" out rc
  out=$("${KC[@]}" -n "$ns" exec "$pod" -c probe -- /agnhost connect "$dest" --timeout="$PROBE_TIMEOUT" --protocol=tcp 2>&1); rc=$?
  V=$(classify "$out" "$rc")
  printf '    %s/%s -> %s : rc=%s output=[%s] => %s\n' "$ns" "$pod" "$dest" "$rc" "${out//$'\n'/ | }" "$V"
}
declare -A VERDICT
RESULT=0   # 0 pass, 1 fail
arm_line() { echo "ARM $1 $2 $(ts)"; }

run_arm() {
  local id="$1" v1 c
  case "$id" in
    P0)
      echo "  P0 policy-free: namespace $PROBE_NS (no NetworkPolicy), unlabelled client"
      connect "$PROBE_NS" "$P_CLIENT" "$TARGET_IP:8080"; v1="$V"
      connect "$PROBE_NS" "$P_CLIENT" "$EGRESS_ALLOW_ADDR:$EGRESS_DENY_PORT"
      if [ "$v1" = "CONNECTED" ] && [ "$V" = "CONNECTED" ]; then V=CONNECTED
      elif [ "$v1" != "CONNECTED" ]; then V="$v1"; fi
      VERDICT[P0]="$V"; arm_line P0 "$V"
      [ "$(policy_free_verdict "$V")" = "OK" ] \
        || void "policy-free arm P0 is $V: the probe cannot connect where NO policy exists, so no TIMEOUT below could be the policy's" ;;
    A)  echo "  A control: app=frontend -> Service core-java"
        connect "$NAMESPACE" "$P_FRONT" "$CJ_SVC:9090" ;;
    B)  echo "  B denied: unlabelled -> Service core-java"
        connect "$NAMESPACE" "$P_BARE" "$CJ_SVC:9090" ;;
    F1) echo "  F1 claim: namespace $CONTROLLER_NS -> Service core-java (Blocker F)"
        connect "$CONTROLLER_NS" "$P_TRAEFIK" "$CJ_SVC:9090" ;;
    M1) echo "  M1 control: app=prometheus -> core-java pod management port"
        connect "$NAMESPACE" "$P_PROM" "$CJ_POD:9091" ;;
    M2) echo "  M2 denied: unlabelled -> core-java pod management port"
        connect "$NAMESPACE" "$P_BARE" "$CJ_POD:9091" ;;
    E1) echo "  E1 control: app=core-java egress -> public 443"
        connect "$NAMESPACE" "$P_CJ" "$EGRESS_ALLOW_ADDR:$EGRESS_ALLOW_PORT" ;;
    E2) echo "  E2 denied: app=core-java egress -> public $EGRESS_DENY_PORT (outside its allow-list)"
        connect "$NAMESPACE" "$P_CJ" "$EGRESS_ALLOW_ADDR:$EGRESS_DENY_PORT" ;;
    K1) echo "  K1 control: app=frontend -> Service keycloak"
        connect "$NAMESPACE" "$P_FRONT" "$KC_SVC:8080" ;;
    K2) echo "  K2 denied: unlabelled -> Service keycloak"
        connect "$NAMESPACE" "$P_BARE" "$KC_SVC:8080" ;;
  esac
  [ "$id" = "P0" ] && return 0
  VERDICT[$id]="$V"; arm_line "$id" "$V"
  case "$id" in
    A|M1|E1|K1)
      [ "$V" = "CONNECTED" ] || void "control arm $id is $V: a connection the manifests PERMIT did not connect, so the probe or the target is broken and no denial can be interpreted" ;;
    F1)
      case "$(claim_verdict "$V")" in
        PASS) ;;
        FAIL) echo "  FAIL: F1 is $V — the controller namespace cannot reach core-java:9090 (Blocker F)"; RESULT=1 ;;
        *) void "F1 produced an unrecognised result" ;;
      esac ;;
    B|M2|E2|K2)
      c=$(control_of "$id")
      case "$(pair_verdict "${VERDICT[$c]:-}" "$V")" in
        PASS) ;;
        FAIL) echo "  FAIL: $id is $V while its control $c CONNECTED — this connection is NOT being dropped"; RESULT=1 ;;
        *) void "$id produced an unrecognised result ($V) or its control ${c} did not connect" ;;
      esac ;;
  esac
}

for arm in $ARMS; do run_arm "$arm"; done

echo
if [ "$RESULT" -eq 0 ]; then
  if [ -n "$ONLY" ]; then
    echo "PASS: --only $ONLY on context '$CONTEXT' gave its expected verdict, after P0 and its control connected."
  else
    echo "PASS: every control connected, the policy-free arm connected, and every denied arm TIMED OUT."
    echo "      NetworkPolicy is ENFORCED on context '$CONTEXT' (Cilium)."
  fi
  exit 0
fi
echo "FAIL: at least one arm contradicts the committed NetworkPolicies on context '$CONTEXT' (see above)." >&2
exit 1
