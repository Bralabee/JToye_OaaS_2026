#!/usr/bin/env bash
# check-deploy-digest-parity.sh — proves the pods that are RUNNING after a rollout are the
# images that were BUILT for this commit.
#
# ---------------------------------------------------------------------------------------
# WHAT GOES WRONG
#
#   Phase 26 shipped a runtime missing its own `application.yml` change and three merged UI
#   PRs, past FOUR green gates, and the user caught it by eye. HTTP 200, a rendered page
#   title, "builds clean" and a green suite are all identical whether the running code is
#   current or months stale. The deploy job already:
#
#     - pins :<sha> with `kustomize edit set image` and asserts the RENDER contains it,
#     - waits for `kubectl rollout status` on all three deployments,
#     - curls actuator health in-cluster and runs public smoke tests.
#
#   None of that answers the question. A render assertion proves the YAML we are about to
#   apply names the right TAG. A rollout status proves pods became Ready. A health check
#   proves something is listening. A tag can be re-pointed, an imagePullPolicy can serve a
#   cached layer, a node can hold a stale image under the same tag, and a partially-failed
#   rollout can leave old replicas serving — every one of which produces exactly the same
#   green as a correct deploy.
#
#   The only thing that distinguishes them is the DIGEST the kubelet actually pulled.
#
# THE COMPOSE HALF AND THE KUBERNETES HALF
#
#   `scripts/check-runtime-freshness.sh` is the COMPOSE half of the runtime-parity
#   doctrine: it reads `.Metadata.LastTagTime` off local Docker images and compares the
#   running container's image id against the tag's. It has no cluster concept at all, so
#   pointed at Kubernetes it can only ever exit 2 (VOID) — and a VOID recorded as a pass is
#   how this whole failure mode survives. THIS script is the KUBERNETES half.
#
# WHY A SET AND NOT AN EQUALITY — a deliberate widening, with its reason
#
#   core-java and edge-go are built `linux/amd64,linux/arm64`. A multi-platform tag resolves
#   to a manifest-LIST (OCI index) digest, while a node may report the platform-specific
#   MANIFEST digest for the architecture it actually pulled. Which of the two AKS/containerd
#   reports in `.status.containerStatuses[].imageID` is NOT measurable without a live
#   cluster, and guessing would produce a gate that fails on every correct deploy.
#
#   So the expected set is built from `docker buildx imagetools inspect`: the index digest
#   PLUS each platform manifest digest, and membership is asserted. This is the WEAKER of
#   the two correct forms and it is chosen knowingly. It still fails on a genuinely
#   different image — which is the property being bought — because a stale or wrong build
#   produces a digest in NEITHER set.
#
#   NARROWING THIS TO A SINGLE MEASURED DIGEST IS OWED TO PLAN 29-15, on the first live
#   deploy, once it is known which digest AKS reports. Until that measurement exists, do not
#   narrow it speculatively: a gate that reds every correct deploy gets deleted, and then
#   there is no gate.
#
# WHY THE EXPECTED DIGESTS COME FROM THE REGISTRY AND NOT FROM JOB OUTPUTS
#
#   `build-and-push` is a MATRIX job (`strategy.matrix.service: [core-java, edge-go,
#   frontend]`) and GitHub Actions matrix job outputs are LAST-WRITER-WINS: three parallel
#   legs writing `outputs.digest` leave exactly one value, non-deterministically. Per-service
#   digests therefore cannot be plumbed through outputs at all. They are resolved from the
#   registry at deploy time, which is why the job needs `packages: read`.
#
# FAIL CLOSED — "could not check" is NEVER "clean"
#
#   An unreadable digest, an EMPTY actual set, an EMPTY expected set, or ZERO services
#   discovered are all exit 2. A step that exits 0 on an empty result measures nothing, and
#   this repo has already shipped one of those: check-runtime-freshness.sh once printed
#   `PASS: 3 … (1 unverified)` and exited 0 with a service it had not verified.
#
# SHELL DISCIPLINE
#
#   There is deliberately no pipe into a quiet grep anywhere in this file. That shape
#   inverts under `pipefail` when the pattern MATCHES — grep exits at the first hit, the
#   writer takes SIGPIPE, pipefail promotes it to 141 — and so fails OPEN. It has made a
#   real guard in this repo pass when it should have failed. Membership is tested with a
#   here-string, and every `rc=$?` is captured on the SAME statement as its command (an
#   intervening `echo` reports the echo's status, which is 0 essentially always).
#
# EXIT CODES — uniform with this phase's other gates
#   0 = the running images match this commit · 1 = a WRONG image is running · 2 = VOID
#
# USAGE
#   scripts/check-deploy-digest-parity.sh --context <ctx> --namespace jtoye-staging \
#       --sha <git-sha> [--services "core-java edge-go frontend"]
#
#   Offline falsification harness (documented knob, never used in CI):
#     DIGEST_PARITY_FIXTURE=<dir>   read <dir>/<svc>.actual and <dir>/<svc>.expected
#                                   instead of calling kubectl and the registry. This is
#                                   what makes the four arms (match / mismatch / empty
#                                   actual / zero services) runnable with no cluster.
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || exit 2

void() { printf 'VOID: %s\n' "$*" >&2; exit 2; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
log()  { printf '  %s\n' "$*"; }

KUBE_CONTEXT="${DIGEST_PARITY_CONTEXT:-}"
NAMESPACE="${DIGEST_PARITY_NAMESPACE:-jtoye-staging}"
GIT_SHA="${DIGEST_PARITY_SHA:-}"
SERVICES="${DIGEST_PARITY_SERVICES-core-java edge-go frontend}"
REGISTRY_PREFIX="${DIGEST_PARITY_REGISTRY_PREFIX:-ghcr.io/bralabee/jtoye-}"
FIXTURE="${DIGEST_PARITY_FIXTURE:-}"
# Contexts this gate refuses even when named. It only READS, but it reads with cluster
# credentials, and the only context on the maintainer's own host is employer infrastructure.
FORBIDDEN_CONTEXTS="${DIGEST_PARITY_FORBIDDEN_CONTEXTS:-sipbihs2aks}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --context)   KUBE_CONTEXT="${2:-}"; shift 2 ;;
    --namespace) NAMESPACE="${2:-}"; shift 2 ;;
    --sha)       GIT_SHA="${2:-}"; shift 2 ;;
    --services)  SERVICES="${2:-}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --context <ctx> --namespace <ns> --sha <git-sha> [--services \"a b c\"]"
      exit 0 ;;
    *) void "unknown argument '$1' — refusing to guess what was meant while holding cluster credentials" ;;
  esac
done

echo "check-deploy-digest-parity  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"

# ---- 0. zero services is VOID, and it is checked FIRST ---------------------------------
# A loop over an empty list completes without a single comparison and reports success. That
# is the strongest-looking and least trustworthy result this gate could produce.
SERVICE_COUNT=0
for _s in $SERVICES; do SERVICE_COUNT=$((SERVICE_COUNT + 1)); done
[ "$SERVICE_COUNT" -gt 0 ] || void "ZERO services to check. A loop over an empty service list makes no
       comparison at all and would exit 0 having measured nothing. An empty discovery
       result is VOID, never clean."

if [ -n "$FIXTURE" ]; then
  echo "  ***********************************************************************"
  echo "  *  FIXTURE MODE — digests are read from files, not from a cluster or  *"
  echo "  *  a registry. This exercises the COMPARISON LOGIC only. It is not    *"
  echo "  *  evidence about any deployed image.                                 *"
  echo "  ***********************************************************************"
  [ -d "$FIXTURE" ] || void "fixture directory '$FIXTURE' does not exist"
else
  # ---- 1. tooling and context guard ----------------------------------------------------
  command -v kubectl >/dev/null 2>&1 \
    || void "kubectl not on PATH — the running image digest cannot be read without a cluster client"
  command -v docker >/dev/null 2>&1 \
    || void "docker not on PATH — the expected digest set is resolved with 'docker buildx imagetools inspect'"

  [ -n "$KUBE_CONTEXT" ] || void "no kube context named.
       This gate reads with cluster credentials and will not inherit the ambient context to
       do it: on the maintainer's host the only context is employer infrastructure. Name it:
         $0 --context <kube-context> --namespace $NAMESPACE --sha <git-sha>
       An unnamed context is VOID, never clean."

  for forbidden in $FORBIDDEN_CONTEXTS; do
    [ "$KUBE_CONTEXT" = "$forbidden" ] \
      && void "context '$KUBE_CONTEXT' is on the refusal list (DIGEST_PARITY_FORBIDDEN_CONTEXTS).
       That cluster is EMPLOYER infrastructure. Naming it explicitly does not make it safe —
       intent is not a safety mechanism."
  done

  [ -n "$GIT_SHA" ] || void "no --sha given. Without the commit under deploy there is no expected
       image to resolve, and a comparison against an unknown expectation is VOID, never clean."
fi

log "namespace : ${NAMESPACE}"
log "commit    : ${GIT_SHA:-<fixture mode>}"
log "services  : ${SERVICE_COUNT} (${SERVICES})"
echo

# ---- 2. digest readers -----------------------------------------------------------------
# Both go through a single function each, so fixture mode is total rather than best-effort.

read_actual() { # read_actual <svc> -> newline-separated digests on stdout
  local svc="$1"
  if [ -n "$FIXTURE" ]; then
    [ -f "$FIXTURE/$svc.actual" ] && cat "$FIXTURE/$svc.actual"
    return 0
  fi
  # imageID is reported per container as e.g.
  #   ghcr.io/bralabee/jtoye-core-java@sha256:abcd...
  # Take the digest portion only; a bare image id with no @sha256 is unusable and will
  # surface below as an empty set, which is VOID.
  kubectl --context "$KUBE_CONTEXT" -n "$NAMESPACE" --request-timeout=30s \
    get pods -l "app=$svc" -o jsonpath='{range .items[*]}{range .status.containerStatuses[*]}{.imageID}{"\n"}{end}{end}' 2>/dev/null \
    | sed -n 's/.*@\(sha256:[0-9a-f]\{64\}\).*/\1/p' | sort -u
}

read_expected() { # read_expected <svc> -> newline-separated digests on stdout
  local svc="$1"
  if [ -n "$FIXTURE" ]; then
    [ -f "$FIXTURE/$svc.expected" ] && cat "$FIXTURE/$svc.expected"
    return 0
  fi
  # The index digest PLUS each platform manifest digest — see the header for why this is a
  # set and not a single value.
  docker buildx imagetools inspect "${REGISTRY_PREFIX}${svc}:${GIT_SHA}" --raw >/dev/null 2>&1
  docker buildx imagetools inspect "${REGISTRY_PREFIX}${svc}:${GIT_SHA}" 2>/dev/null \
    | sed -n 's/.*\(sha256:[0-9a-f]\{64\}\).*/\1/p' | sort -u
}

# ---- 3. compare ------------------------------------------------------------------------
MISMATCHES=0
CHECKED=0

for svc in $SERVICES; do
  actual="$(read_actual "$svc")"
  expected="$(read_expected "$svc")"

  [ -n "$actual" ] || void "could not read a single image digest for '$svc' in namespace '$NAMESPACE'.
       An empty ACTUAL set means the gate is blind, and a blind gate that exits 0 is exactly
       the shape that let a stale runtime past four green gates. VOID, never clean."
  [ -n "$expected" ] || void "could not resolve any expected digest for '${REGISTRY_PREFIX}${svc}:${GIT_SHA}'.
       An empty EXPECTED set makes every comparison below vacuous — membership in the empty
       set is false for everything, so this would red a correct deploy for the wrong reason
       and green a wrong one if the test were inverted. VOID, never clean."

  svc_bad=0
  while IFS= read -r a; do
    [ -n "$a" ] || continue
    # HERE-STRING, never `printf ... | grep -q`: that shape inverts under pipefail when the
    # pattern matches (SIGPIPE -> 141) and fails OPEN.
    if command grep -Fxq -- "$a" <<<"$expected"; then
      printf '  %-12s %s  MATCH\n' "$svc" "$a"
    else
      printf '  %-12s %s  NOT IN THE EXPECTED SET\n' "$svc" "$a"
      svc_bad=$((svc_bad + 1))
    fi
  done <<<"$actual"

  if [ "$svc_bad" -gt 0 ]; then
    MISMATCHES=$((MISMATCHES + svc_bad))
    printf '  %-12s expected set was:\n' "$svc"
    while IFS= read -r e; do [ -n "$e" ] && printf '  %-12s   %s\n' "" "$e"; done <<<"$expected"
  fi
  CHECKED=$((CHECKED + 1))
done

echo
[ "$CHECKED" -gt 0 ] || void "no service was actually compared despite a non-empty service list — VOID, never clean."

if [ "$MISMATCHES" -gt 0 ]; then
  fail "$MISMATCHES running container image digest(s) across $CHECKED service(s) are NOT the images
      built for ${GIT_SHA:-this commit}. The rollout reported success and the pods are Ready, so
      every other gate in this job is green — this is the Phase 26 stale-runtime shape, caught.
      Do NOT relax this gate: re-run the build for this commit and redeploy."
fi

echo "PASS: every running container image digest across $CHECKED service(s) is in the set of digests"
echo "      published for ${GIT_SHA:-<fixture>}. NOTE: this is the SET form (index digest plus each"
echo "      platform manifest digest). Narrowing it to a single measured digest is owed to 29-15."
exit 0
