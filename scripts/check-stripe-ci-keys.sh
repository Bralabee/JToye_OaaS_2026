#!/usr/bin/env bash
# check-stripe-ci-keys.sh — guard that STRIPE_TEST_SECRET_KEY and STRIPE_TEST_PUBLISHABLE_KEY
# passed into CI / nightly jobs are genuine Stripe TEST-mode credentials (Phase 30 plan 30-22,
# PAY-01; threat mitigations T-30-09, T-30-10).
#
# USAGE
#   STRIPE_TEST_SECRET_KEY=<value> [STRIPE_TEST_PUBLISHABLE_KEY=<value>] scripts/check-stripe-ci-keys.sh
#   scripts/check-stripe-ci-keys.sh --selftest
#   STRIPE_CI_KEYS_SELFTEST_FORMAT=tap scripts/check-stripe-ci-keys.sh --selftest
#
# EXIT
#   0  accepted (test-mode secret key present; publishable key empty or test-mode)
#   1  refused (unset/empty secret key, live key, swapped keys, or malformed characters)
#   2  usage error

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF="$SCRIPT_DIR/check-stripe-ci-keys.sh"

void() { echo "VOID: $*" >&2; exit 2; }
refuse() { echo "FAIL: $*" >&2; exit 1; }

well_formed() {
  local rest
  case "$1" in "$2"*) rest="${1#"$2"}" ;; *) return 1 ;; esac
  case "$rest" in ''|*[!A-Za-z0-9]*) return 1 ;; esac
  return 0
}

classify_prefix() {
  case "$1" in
    '') echo empty ;;
    sk_test_*) echo sk_test_ ;;
    sk_live_*) echo sk_live_ ;;
    pk_test_*) echo pk_test_ ;;
    pk_live_*) echo pk_live_ ;;
    rk_test_*) echo rk_test_ ;;
    rk_live_*) echo rk_live_ ;;
    *) echo other ;;
  esac
}

check() {
  local sk="${STRIPE_TEST_SECRET_KEY-}" pk="${STRIPE_TEST_PUBLISHABLE_KEY-}"
  local sk_prefix pk_prefix
  sk_prefix="$(classify_prefix "$sk")"
  pk_prefix="$(classify_prefix "$pk")"
  echo "secret_prefix=$sk_prefix secret_length=${#sk} pk_prefix=$pk_prefix pk_length=${#pk}"

  if [ "$sk_prefix" = empty ]; then
    refuse "STRIPE_TEST_SECRET_KEY is unset or empty — provision the repository secret with a Stripe TEST secret key (sk_test_...); do not skip the Stripe E2E phase."
  fi
  if [ "$sk_prefix" != sk_test_ ]; then
    refuse "STRIPE_TEST_SECRET_KEY must be a Stripe TEST secret key (sk_test_...), got prefix=$sk_prefix."
  fi
  well_formed "$sk" sk_test_ || refuse "STRIPE_TEST_SECRET_KEY starts sk_test_ but contains non-alphanumeric characters (length=${#sk})."

  if [ "$pk_prefix" != empty ]; then
    if [ "$pk_prefix" != pk_test_ ]; then
      refuse "STRIPE_TEST_PUBLISHABLE_KEY must be a Stripe TEST publishable key (pk_test_...) when set, got prefix=$pk_prefix."
    fi
    well_formed "$pk" pk_test_ || refuse "STRIPE_TEST_PUBLISHABLE_KEY starts pk_test_ but contains non-alphanumeric characters (length=${#pk})."
  fi

  echo "OK: Stripe CI test keys accepted (secret_prefix=$sk_prefix secret_length=${#sk} pk_prefix=$pk_prefix pk_length=${#pk})"
  exit 0
}

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

st_run() {
  local rc=0
  STRIPE_TEST_SECRET_KEY="$2" STRIPE_TEST_PUBLISHABLE_KEY="$3" bash "$SELF" > "$ST/$1.out" 2>&1 < /dev/null || rc=$?
  echo "$rc"
}

arm() {
  local name="$1" want="$2" got="$3" tag="$4" needle="$5" why=""
  [ "$got" = "$want" ] || why="rc=$got (want $want): $(tail -n 2 "$ST/$tag.out" | tr '\n' ' ')"
  if [ -z "$why" ] && ! command grep -qF -- "$needle" "$ST/$tag.out"; then
    why="output lacks '$needle': $(tail -n 2 "$ST/$tag.out" | tr '\n' ' ')"
  fi
  if [ -z "$why" ]; then result "PASS $name rc=$got"; else result "FAIL $name $why"; FAILS=$((FAILS + 1)); fi
}

selftest() {
  local fmt="${STRIPE_CI_KEYS_SELFTEST_FORMAT:-plain}" rc sk_test sk_live pk_test pk_live why f v
  FAILS=0
  ST="$(mktemp -d "${TMPDIR:-/tmp}/jtoye-cikeys-st.XXXXXXXX")" || void "mktemp -d failed"
  trap 'rm -rf "$ST"' EXIT
  sk_test="s""k_""test_""CiKeysSelfTestSecret012345"
  sk_live="s""k_""live_""CiKeysLiveSecret0123456789"
  pk_test="p""k_""test_""CiKeysSelfTestPub012345678"
  pk_live="p""k_""live_""CiKeysLivePub0123456789012"

  rc=$(st_run ok "$sk_test" "$pk_test");             arm ok 0 "$rc" ok "secret_prefix=sk_test_ secret_length=${#sk_test}"
  rc=$(st_run secret-empty "" "$pk_test");           arm secret-empty 1 "$rc" secret-empty "STRIPE_TEST_SECRET_KEY"
  rc=$(st_run secret-live "$sk_live" "$pk_test");    arm secret-live 1 "$rc" secret-live "TEST secret key"
  rc=$(st_run pk-live "$sk_test" "$pk_live");        arm pk-live 1 "$rc" pk-live "TEST publishable key"
  rc=$(st_run swapped "$pk_test" "$sk_test");        arm swapped 1 "$rc" swapped "TEST secret key"

  why=""
  for f in "$ST"/*.out; do
    for v in "$sk_test" "$sk_live" "$pk_test" "$pk_live"; do
      if command grep -qF -- "$v" "$f"; then why="$(basename "$f") contains a fixture value"; break 2; fi
    done
  done
  [ -z "$why" ] && ! command grep -qF "secret_length=${#sk_test}" "$ST/ok.out" && why="ok.out does not describe the key, so the scan above proves nothing"
  if [ -z "$why" ]; then result "PASS no-echo"; else result "FAIL no-echo $why"; FAILS=$((FAILS + 1)); fi

  render_results "$fmt"
  [ "$fmt" = tap ] && printf "# "; echo "SELFTEST: $(( ${#RESULTS[@]} - FAILS ))/${#RESULTS[@]}"
  [ "$FAILS" -eq 0 ] || exit 1
  exit 0
}

case "${1-}" in
  "")
    check ;;
  --selftest)
    [ "$#" -eq 1 ] || { echo "USAGE ERROR: --selftest takes no other argument" >&2; exit 2; }
    selftest ;;
  -h|--help)
    sed -n '/^# USAGE/,/^# EXIT/p' "$SELF" | sed '$d' | sed 's/^# \{0,1\}//' ;;
  *)
    echo "USAGE ERROR: check-stripe-ci-keys.sh takes no positional arguments (keys are read from the environment) or --selftest" >&2
    exit 2 ;;
esac
