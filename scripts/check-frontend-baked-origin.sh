#!/usr/bin/env bash
# check-frontend-baked-origin.sh — CR-02: the API origin baked into the frontend image must be
# the api.url of the overlay being deployed (Phase 29 plan 29-21; extracted from the inline
# step of ci-cd.yaml deploy-staging, behaviour unchanged except that an empty baked origin is
# now rc 1, see EXIT).
#
# WHY
#   build-and-push produces ONE frontend image per commit, and NEXT_PUBLIC_API_URL (the repo
#   variable FRONTEND_PUBLIC_API_URL) is INLINED into it at build time. A single image carries
#   a single API origin and is only correct for the environment whose app-config api.url
#   equals it. A mismatch gives every browser an enforcing CSP whose connect-src names the
#   wrong origin: refused fetches, behind READY pods and passing server-side smoke tests.
#   It cannot be corrected by a runtime env, so the deploy must refuse it.
#
# USAGE
#   BAKED_API_URL=<origin> scripts/check-frontend-baked-origin.sh --overlay k8s/staging
#   scripts/check-frontend-baked-origin.sh --selftest
#   BAKED_ORIGIN_SELFTEST_FORMAT=tap scripts/check-frontend-baked-origin.sh --selftest
#
#   The overlay is rendered with `kubectl kustomize` (BAKED_ORIGIN_KUBECTL overrides the
#   binary, selftests only). api.url is read from the render's `api.url: <value>` line.
#
# EXIT
#   0  the baked origin equals the overlay's api.url
#   1  they differ (both printed), or BAKED_API_URL is empty/unset (the image baked an
#      EMPTY origin; the inline step exited 2 here, but an unset variable is a known
#      wrong state, not one this guard could not evaluate)
#   2  VOID: usage error, kubectl missing, the render failed, or the render holds no
#      api.url (the guard is blind, so it fails closed)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SELF="$SCRIPT_DIR/check-frontend-baked-origin.sh"

void() { echo "VOID: $*" >&2; exit 2; }

usage() { sed -n '/^# USAGE/,/^# EXIT/p' "$SELF" | sed '$d' | sed 's/^# \{0,1\}//'; }

check() {
  local overlay="$1"
  # RED stub (29-21 Task 1): no comparison yet. The selftest must catch this.
  echo "OK: (stub) ${overlay}"
  exit 0
}

# ---------------------------------------------------------------------------
# --selftest: the real k8s/staging overlay, three CR-02 cases and two VOID arms
# ---------------------------------------------------------------------------
RESULTS=()
result() { RESULTS+=("$1"); }
render_results() {
  local fmt="$1" i=0 r verdict rest
  if [ "$fmt" = tap ]; then
    echo "TAP version 13"
    echo "1..${#RESULTS[@]}"
    for r in "${RESULTS[@]}"; do
      i=$((i + 1)); verdict="${r%% *}"; rest="${r#* }"
      if [ "$verdict" = PASS ]; then
        echo "ok $i - ${rest%% *}"
      else
        echo "not ok $i - ${rest%% *}"
        echo "  ---"
        echo "  message: '$(printf '%s' "${rest#* }" | tr "'\n" '" ')'"
        echo "  ..."
      fi
    done
  else
    for r in "${RESULTS[@]}"; do printf '%s\n' "$r"; done
  fi
}

# st_run TAG BAKED [OVERLAY] -> rc; output in $ST/TAG.out
st_run() {
  local tag="$1" baked="$2" overlay="${3:-$REPO_ROOT/k8s/staging}" rc=0
  ( cd "$REPO_ROOT" && BAKED_API_URL="$baked" bash "$SELF" --overlay "$overlay" ) > "$ST/$tag.out" 2>&1 < /dev/null || rc=$?
  echo "$rc"
}

selftest() {
  local fmt="${BAKED_ORIGIN_SELFTEST_FORMAT:-plain}" fails=0 rc why staging_url
  ST="$(mktemp -d "${TMPDIR:-/tmp}/jtoye-cr02-st.XXXXXXXX")" || void "mktemp -d failed"
  trap 'rm -rf "$ST"' EXIT
  command -v "${BAKED_ORIGIN_KUBECTL:-kubectl}" >/dev/null 2>&1 || void "kubectl not on PATH; the selftest renders the real k8s/staging overlay"
  # The expected staging origin is read from the overlay source, independently of the
  # guard's own render parsing, so a parser that reads the wrong key cannot agree with it.
  staging_url="$(awk '$1 == "api.url:" { v = $2; gsub(/"/, "", v); print v; exit }' "$REPO_ROOT/k8s/staging/configmap-patch.yaml")"
  [ -n "$staging_url" ] || void "could not read api.url from k8s/staging/configmap-patch.yaml (the selftest's own control)"
  [ "$staging_url" != https://api.olajay.co.uk ] || void "k8s/staging api.url is the production origin; the production-origin case would prove nothing"

  # 1. production origin against the staging overlay: rc 1, both origins named
  rc=$(st_run prod https://api.olajay.co.uk); why=""
  [ "$rc" = 1 ] || why="rc=$rc (want 1): $(tail -n 2 "$ST/prod.out" | tr '\n' ' ')"
  [ -z "$why" ] && ! command grep -qF 'https://api.olajay.co.uk' "$ST/prod.out" && why="the baked production origin is not named"
  [ -z "$why" ] && ! command grep -qF "$staging_url" "$ST/prod.out" && why="the overlay origin $staging_url is not named"
  if [ -z "$why" ]; then result "PASS production-origin rc=1 both-named"; else result "FAIL production-origin $why"; fails=$((fails + 1)); fi

  # 2. the staging origin: rc 0
  rc=$(st_run match "$staging_url"); why=""
  [ "$rc" = 0 ] || why="rc=$rc (want 0): $(tail -n 2 "$ST/match.out" | tr '\n' ' ')"
  if [ -z "$why" ]; then result "PASS staging-origin rc=0"; else result "FAIL staging-origin $why"; fails=$((fails + 1)); fi

  # 3. an empty baked origin: rc 1, named as empty
  rc=$(st_run empty ""); why=""
  [ "$rc" = 1 ] || why="rc=$rc (want 1): $(tail -n 2 "$ST/empty.out" | tr '\n' ' ')"
  [ -z "$why" ] && ! command grep -qi 'empty' "$ST/empty.out" && why="the output does not say the origin is empty"
  if [ -z "$why" ]; then result "PASS empty-origin rc=1"; else result "FAIL empty-origin $why"; fails=$((fails + 1)); fi

  # 4. VOID: an overlay whose render has no api.url
  mkdir -p "$ST/noapi"
  printf '%s\n' 'apiVersion: v1' 'kind: ConfigMap' 'metadata:' '  name: app-config' 'data:' '  frontend.url: "https://x.invalid"' > "$ST/noapi/cm.yaml"
  printf '%s\n' 'resources:' '  - cm.yaml' > "$ST/noapi/kustomization.yaml"
  rc=$(st_run noapi "$staging_url" "$ST/noapi"); why=""
  [ "$rc" = 2 ] || why="rc=$rc (want 2): $(tail -n 2 "$ST/noapi.out" | tr '\n' ' ')"
  if [ -z "$why" ]; then result "PASS void-no-api-url rc=2"; else result "FAIL void-no-api-url $why"; fails=$((fails + 1)); fi

  # 5. VOID: kubectl missing
  rc=0; ( cd "$REPO_ROOT" && BAKED_ORIGIN_KUBECTL="$ST/no-kubectl" BAKED_API_URL="$staging_url" bash "$SELF" --overlay k8s/staging ) > "$ST/nokubectl.out" 2>&1 < /dev/null || rc=$?
  if [ "$rc" = 2 ]; then result "PASS void-no-kubectl rc=2"; else result "FAIL void-no-kubectl rc=$rc (want 2)"; fails=$((fails + 1)); fi

  render_results "$fmt"
  [ "$fmt" = tap ] && printf "# "; echo "SELFTEST: $(( ${#RESULTS[@]} - fails ))/${#RESULTS[@]} (staging api.url=$staging_url)"
  [ "$fails" -eq 0 ] || exit 1
  exit 0
}

case "${1-}" in
  --overlay)
    [ -n "${2-}" ] || { echo "USAGE ERROR: --overlay needs a directory" >&2; exit 2; }
    [ "$#" -eq 2 ] || { echo "USAGE ERROR: unexpected argument '${3}'" >&2; exit 2; }
    check "$2" ;;
  --selftest) selftest ;;
  -h|--help) usage ;;
  *) echo "usage: $0 --overlay DIR | --selftest" >&2; exit 2 ;;
esac
