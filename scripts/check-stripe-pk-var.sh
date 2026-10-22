#!/usr/bin/env bash
# check-stripe-pk-var.sh — the Stripe publishable key a deploy is about to ship is the right MODE for
# its environment (issue #957, phase 30 plan 30-01; decisions D-06, D-08).
#
# WHY
#   build-and-push bakes ONE frontend image per commit, with the repository variable
#   FRONTEND_PUBLIC_STRIPE_PUBLISHABLE_KEY inlined into the browser bundle as
#   NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY. That image is what deploy-staging and deploy-production
#   ship, and an inlined key cannot be corrected by a runtime env afterwards. So the deploy jobs ask
#   this guard, BEFORE any credential is materialised, whether the baked key is allowed there:
#     staging     ^pk_test_[A-Za-z0-9]+$ only. Only Stripe TEST credentials enter staging (D-06,
#                 D-28). An empty key ships an inert card checkout (#957), a pk_live_ key would take
#                 real money from a test environment, and anything else (a SECRET sk_/rk_ key pasted
#                 into the wrong variable, T-30-02) must never reach a public bundle.
#     production  refuses pk_test_ (D-08: the one-image pipeline must never promote a test-keyed
#                 image — per-environment images are Phase 32) and refuses empty (an inert card
#                 checkout); accepts ^pk_live_[A-Za-z0-9]+$.
#   The value is read ONLY from the environment variable STRIPE_PK_VAR, never from argv (argv is
#   visible to every process on the runner), and only its PREFIX and LENGTH are ever printed.
#
# USAGE
#   STRIPE_PK_VAR=<value> scripts/check-stripe-pk-var.sh --environment staging|production
#   scripts/check-stripe-pk-var.sh --selftest
#   STRIPE_PK_VAR_SELFTEST_FORMAT=tap scripts/check-stripe-pk-var.sh --selftest
#
# EXIT
#   0  accepted for this environment
#   1  refused: unset, empty, wrong mode, or not a well-formed publishable key
#   2  usage error (unknown environment, missing or extra argument)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF="$SCRIPT_DIR/check-stripe-pk-var.sh"

void() { echo "VOID: $*" >&2; exit 2; }

refuse() { echo "FAIL: $*" >&2; exit 1; }

# well_formed VALUE PREFIX -> 0 when VALUE is PREFIX followed by one or more [A-Za-z0-9].
well_formed() {
  local rest
  case "$1" in "$2"*) rest="${1#"$2"}" ;; *) return 1 ;; esac
  case "$rest" in ''|*[!A-Za-z0-9]*) return 1 ;; esac
  return 0
}

check() {
  local env="$1" value prefix
  if [ -z "${STRIPE_PK_VAR+set}" ]; then
    echo "environment=$env prefix=empty length=0"
    refuse "STRIPE_PK_VAR is UNSET: the deploy step did not pass vars.FRONTEND_PUBLIC_STRIPE_PUBLISHABLE_KEY at all. Wire the step's env: block; do not relax this guard."
  fi
  value="$STRIPE_PK_VAR"
  case "$value" in
    '') prefix=empty ;;
    pk_test_*) prefix=pk_test_ ;;
    pk_live_*) prefix=pk_live_ ;;
    *) prefix=other ;;
  esac
  echo "environment=$env prefix=$prefix length=${#value}"

  if [ "$prefix" = empty ]; then
    refuse "the repository variable FRONTEND_PUBLIC_STRIPE_PUBLISHABLE_KEY is EMPTY, so the frontend image baked no publishable key and its card checkout is inert (#957). Set the variable (staging: a Stripe TEST key, pk_test_...), then re-run; do not relax this guard."
  fi
  case "$value" in
    sk_*|rk_*)
      refuse "the value is a SECRET-key shape (sk_/rk_), not a publishable key, and it is already INLINED in the public frontend bundle built for this commit. Rotate that key in the Stripe dashboard now, then set the variable to the publishable key." ;;
  esac

  case "$env" in
    staging)
      if [ "$prefix" != pk_test_ ]; then
        refuse "staging accepts only a Stripe TEST publishable key (pk_test_...; D-06: no live credential enters staging), got prefix=$prefix."
      fi
      well_formed "$value" pk_test_ || refuse "the value starts pk_test_ but is not pk_test_ followed by letters and digits only (length=${#value}); a pasted key with stray characters would fail inside Stripe.js, far from here."
      ;;
    production)
      if [ "$prefix" = pk_test_ ]; then
        refuse "production refuses a Stripe TEST publishable key (D-08): this image would take test-mode payments in production. The pipeline builds ONE frontend image per commit, so a pk_test_ variable can never be promoted; per-environment images are Phase 32."
      fi
      [ "$prefix" = pk_live_ ] || refuse "production accepts only a Stripe LIVE publishable key (pk_live_...), got prefix=$prefix."
      well_formed "$value" pk_live_ || refuse "the value starts pk_live_ but is not pk_live_ followed by letters and digits only (length=${#value})."
      ;;
  esac
  echo "OK: the baked publishable key is accepted for $env (prefix=$prefix length=${#value})"
  exit 0
}

# ---------------------------------------------------------------------------
# --selftest: every environment x key-shape case the deploy jobs depend on
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

# st_set TAG ENV VALUE -> rc (STRIPE_PK_VAR set to VALUE, possibly empty); output in $ST/TAG.out
st_set() {
  local rc=0
  STRIPE_PK_VAR="$3" bash "$SELF" --environment "$2" > "$ST/$1.out" 2>&1 < /dev/null || rc=$?
  echo "$rc"
}
# st_unset TAG ENV -> rc (STRIPE_PK_VAR absent from the environment)
st_unset() {
  local rc=0
  ( unset STRIPE_PK_VAR; bash "$SELF" --environment "$2" ) > "$ST/$1.out" 2>&1 < /dev/null || rc=$?
  echo "$rc"
}
# arm NAME WANT_RC GOT_RC TAG NEEDLE
arm() {
  local name="$1" want="$2" got="$3" tag="$4" needle="$5" why=""
  [ "$got" = "$want" ] || why="rc=$got (want $want): $(tail -n 2 "$ST/$tag.out" | tr '\n' ' ')"
  if [ -z "$why" ] && ! command grep -qF -- "$needle" "$ST/$tag.out"; then
    why="output lacks '$needle': $(tail -n 2 "$ST/$tag.out" | tr '\n' ' ')"
  fi
  if [ -z "$why" ]; then result "PASS $name rc=$got"; else result "FAIL $name $why"; FAILS=$((FAILS + 1)); fi
}

selftest() {
  local fmt="${STRIPE_PK_VAR_SELFTEST_FORMAT:-plain}" rc test_key live_key malformed secret why f v
  FAILS=0
  ST="$(mktemp -d "${TMPDIR:-/tmp}/jtoye-pkvar-st.XXXXXXXX")" || void "mktemp -d failed"
  trap 'rm -rf "$ST"' EXIT
  # Fixture values are COMPOSED at run time so no secret scanner sees a key-shaped literal here.
  test_key="pk_""test_""PkVarSelfTestFixture012345"
  live_key="pk_""live_""PkVarLiveFixture0123456789"
  malformed="pk_""test_""has-a-dash+and.dot"
  secret="s""k_""test_""PkVarSecretFixture01234567"

  rc=$(st_set staging-test staging "$test_key");            arm staging-test 0 "$rc" staging-test "environment=staging prefix=pk_test_ length=${#test_key}"
  rc=$(st_set staging-empty staging "");                    arm staging-empty 1 "$rc" staging-empty "is EMPTY"
  rc=$(st_unset staging-unset staging);                     arm staging-unset 1 "$rc" staging-unset "is UNSET"
  rc=$(st_set staging-live staging "$live_key");            arm staging-live 1 "$rc" staging-live "D-06"
  rc=$(st_set staging-malformed staging "$malformed");      arm staging-malformed 1 "$rc" staging-malformed "letters and digits only"
  rc=$(st_set production-test production "$test_key");      arm production-test 1 "$rc" production-test "D-08"
  rc=$(st_set production-empty production "");              arm production-empty 1 "$rc" production-empty "is EMPTY"
  rc=$(st_set production-live production "$live_key");      arm production-live 0 "$rc" production-live "environment=production prefix=pk_live_"
  rc=$(st_set bad-environment preview "$test_key");         arm bad-environment 2 "$rc" bad-environment "USAGE ERROR"
  # A SECRET key pasted into the publishable-key variable is refused in staging (T-30-02); its
  # output is part of the no-echo scan below.
  rc=$(st_set staging-secret staging "$secret");            arm staging-secret 1 "$rc" staging-secret "SECRET"

  # no-echo: no case's output contains a fixture VALUE (prefix and length only)
  why=""
  for f in "$ST"/*.out; do
    for v in "$test_key" "$live_key" "$malformed" "$secret"; do
      if command grep -qF -- "$v" "$f"; then why="$(basename "$f") contains a fixture value"; break 2; fi
    done
  done
  # Non-vacuity: the accepted case does describe its key, so the absence above is about the value.
  [ -z "$why" ] && ! command grep -qF "length=${#test_key}" "$ST/staging-test.out" && why="staging-test.out does not describe the key, so the scan above proves nothing"
  if [ -z "$why" ]; then result "PASS no-echo"; else result "FAIL no-echo $why"; FAILS=$((FAILS + 1)); fi

  render_results "$fmt"
  [ "$fmt" = tap ] && printf "# "; echo "SELFTEST: $(( ${#RESULTS[@]} - FAILS ))/${#RESULTS[@]}"
  [ "$FAILS" -eq 0 ] || exit 1
  exit 0
}

case "${1-}" in
  --selftest)
    [ "$#" -eq 1 ] || { echo "USAGE ERROR: --selftest takes no other argument" >&2; exit 2; }
    selftest ;;
  --environment)
    [ "$#" -eq 2 ] || { echo "USAGE ERROR: --environment takes exactly one value (staging|production); the key is read from STRIPE_PK_VAR, never from argv" >&2; exit 2; }
    case "$2" in
      staging|production) check "$2" ;;
      *) echo "USAGE ERROR: unknown environment '$2' (staging|production)" >&2; exit 2 ;;
    esac ;;
  -h|--help) sed -n '/^# USAGE/,/^# EXIT/p' "$SELF" | sed '$d' | sed 's/^# \{0,1\}//' ;;
  *) echo "usage: STRIPE_PK_VAR=<value> $0 --environment staging|production | --selftest" >&2; exit 2 ;;
esac
