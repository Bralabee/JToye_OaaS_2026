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
#   NARROWING THIS TO A SINGLE MEASURED DIGEST IS OWED TO A LATER PLAN. Plan 29-14 ran it on
#   the first live deploy and recorded which digest AKS/containerd reports in
#   evidence/29-14-tracer.txt; until a plan narrows it against that record, do not narrow it
#   speculatively: a gate that reds every correct deploy gets deleted, and then there is no
#   gate.
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
# USAGE (plan 29-14)
#   scripts/check-deploy-digest-parity.sh --context <ctx> --tag <40-hex sha> \
#       [--namespace jtoye-staging] [--services "core-java edge-go frontend"] [--plant-stale]
#   scripts/check-deploy-digest-parity.sh --selftest
#
#   --context   REQUIRED, no default and no environment fallback. Checked by
#               scripts/lib/staging-kube-guard.sh before any cluster read: sipbihs2aks is
#               refused by name and the context's server must equal
#               https://<aks_api_server_fqdn>:443 from k8s/staging/azure-identity.yaml (a
#               namespace other than jtoye-staging has no identity file yet and is refused:
#               fail closed). The guard's refusal (1) or VOID (2) is re-reported as VOID:
#               this gate compared nothing.
#   --tag       REQUIRED: the full 40-lowercase-hex commit sha the images were pushed under.
#               It must be the RECORDED deployed sha (deploy.sh's DEPLOYED_SHA line, or the
#               variable the CI job deployed with), never a fresh `git rev-parse HEAD`: an
#               evidence or docs commit moves HEAD to a commit whose images never existed.
#   --plant-stale  the fail arm: everything is read for real, then the expected set is
#               REPLACED by one deliberately wrong digest, so every running container must
#               come out NOT IN THE EXPECTED SET and the gate must exit 1. An exit 0 here
#               means the gate cannot see a wrong image.
#   --fixture DIR  comparison logic only: read DIR/<svc>.actual and DIR/<svc>.expected
#               instead of the cluster and the registry. Refused together with --context, so
#               a cluster run can never be fed files. --selftest drives it.
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || exit 2

void() { printf 'VOID: %s\n' "$*" >&2; exit 2; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
log()  { printf '  %s\n' "$*"; }

# No environment variable feeds any of these: what a cluster run compares is decided by its
# arguments alone (the branch version read DIGEST_PARITY_* overrides, including a fixture dir).
KUBE_CONTEXT=""
NAMESPACE="jtoye-staging"
GIT_SHA=""
SERVICES="core-java edge-go frontend"
REGISTRY_PREFIX="ghcr.io/bralabee/jtoye-"
FIXTURE=""
PLANT_STALE=0
SELFTEST=0
# One deliberately wrong digest for --plant-stale: sha256 of the string "plant-stale".
STALE_DIGEST="sha256:$(printf 'plant-stale' | sha256sum | awk '{print $1}')"
# Contexts refused by name before the guard even runs (the guard refuses it too).
FORBIDDEN_CONTEXTS="sipbihs2aks"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --context)     KUBE_CONTEXT="${2:-}"; shift 2 ;;
    --namespace)   NAMESPACE="${2:-}"; shift 2 ;;
    --tag)         GIT_SHA="${2:-}"; shift 2 ;;
    --services)    SERVICES="${2:-}"; shift 2 ;;
    --fixture)     FIXTURE="${2:-}"; shift 2 ;;
    --plant-stale) PLANT_STALE=1; shift ;;
    --selftest)    SELFTEST=1; shift ;;
    -h|--help)
      echo "usage: $0 --context <ctx> --tag <40-hex sha> [--namespace ns] [--services \"a b c\"] [--plant-stale] | --selftest"
      exit 0 ;;
    *) void "unknown argument '$1' — refusing to guess what was meant while holding cluster credentials" ;;
  esac
done

# ---- --selftest: the comparison and refusal arms, no cluster, no registry -------------
if [ "$SELFTEST" -eq 1 ]; then
  st="$(mktemp -d)" || void "mktemp -d failed"
  trap 'rm -rf "$st"' EXIT
  d1="sha256:$(printf a | sha256sum | awk '{print $1}')"; d2="sha256:$(printf b | sha256sum | awk '{print $1}')"
  mkdir -p "$st/match" "$st/mismatch" "$st/empty"
  for s in core-java edge-go frontend; do
    printf '%s\n' "$d1" > "$st/match/$s.actual"; printf '%s\n%s\n' "$d2" "$d1" > "$st/match/$s.expected"
    printf '%s\n' "$d1" > "$st/mismatch/$s.actual"; printf '%s\n' "$d1" > "$st/mismatch/$s.expected"
    : > "$st/empty/$s.actual"; printf '%s\n' "$d1" > "$st/empty/$s.expected"
  done
  printf '%s\n' "$d2" > "$st/mismatch/frontend.actual"
  fails=0
  arm() { # arm NAME WANT_RC ARGS...
    local name="$1" want="$2" rc=0; shift 2
    bash "$0" "$@" >"$st/$name.out" 2>&1 || rc=$?
    if [ "$rc" -eq "$want" ]; then echo "PASS $name rc=$rc"; else echo "FAIL $name rc=$rc (want $want): $(tail -2 "$st/$name.out" | tr '\n' ' ')"; fails=$((fails + 1)); fi
  }
  arm match           0 --fixture "$st/match"
  arm mismatch        1 --fixture "$st/mismatch"
  arm plant-stale     1 --fixture "$st/match" --plant-stale
  arm empty-actual    2 --fixture "$st/empty"
  arm zero-services   2 --fixture "$st/match" --services ""
  arm no-context      2 --tag 0123456789abcdef0123456789abcdef01234567
  arm employer        2 --context sipbihs2aks --tag 0123456789abcdef0123456789abcdef01234567
  arm short-tag       2 --context jtoye-stg-aks --tag 0123abc
  arm fixture-context 2 --fixture "$st/match" --context jtoye-stg-aks
  [ "$fails" -eq 0 ] || exit 1
  exit 0
fi

echo "check-deploy-digest-parity  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"

# ---- 0. zero services is VOID, and it is checked FIRST ---------------------------------
# A loop over an empty list completes without a single comparison and reports success. That
# is the strongest-looking and least trustworthy result this gate could produce.
SERVICE_COUNT=0
for _s in $SERVICES; do SERVICE_COUNT=$((SERVICE_COUNT + 1)); done
[ "$SERVICE_COUNT" -gt 0 ] || void "ZERO services to check. A loop over an empty service list makes no
       comparison at all and would exit 0 having measured nothing. An empty discovery
       result is VOID, never clean."

if [ -n "$FIXTURE" ] && [ -n "$KUBE_CONTEXT" ]; then
  void "--fixture and --context together: a cluster run is never fed files"
fi

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

  [[ "$GIT_SHA" =~ ^[0-9a-f]{40}$ ]] || void "--tag must be the full 40-lowercase-hex commit sha the images were
       pushed under (got '${GIT_SHA}'). Without it there is no expected image to resolve, and a
       comparison against an unknown expectation is VOID, never clean."

  # The shared staging context guard (plan 29-03): employer context by name, then the
  # context's server against the identity file, read locally. Any refusal is VOID here.
  [ "$NAMESPACE" = jtoye-staging ] || void "namespace '$NAMESPACE' has no identity file to check the context's server
       against (only k8s/staging/azure-identity.yaml exists): fail closed."
  # shellcheck source=scripts/lib/staging-kube-guard.sh
  . "$REPO_ROOT/scripts/lib/staging-kube-guard.sh" || void "could not source scripts/lib/staging-kube-guard.sh"
  grc=0
  staging_kube_guard "$KUBE_CONTEXT" "$REPO_ROOT/k8s/staging/azure-identity.yaml" || grc=$?
  [ "$grc" -eq 0 ] || void "the staging context guard refused '$KUBE_CONTEXT' (rc $grc, line above); nothing was compared"
fi

log "namespace : ${NAMESPACE}"
log "commit    : ${GIT_SHA:-<fixture mode>}"
[ "$PLANT_STALE" -eq 0 ] || log "PLANT-STALE: the expected set is replaced by ${STALE_DIGEST} (fail arm)"
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
  if [ "$PLANT_STALE" -eq 1 ] && [ -n "$expected" ]; then
    expected="$STALE_DIGEST"
  fi

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
echo "      platform manifest digest). Narrowing it to a single measured digest is owed to a later plan."
exit 0
