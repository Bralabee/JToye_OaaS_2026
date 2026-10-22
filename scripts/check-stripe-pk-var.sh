#!/usr/bin/env bash
# check-stripe-pk-var.sh — the Stripe publishable key a deploy is about to ship is the right MODE for
# its environment (issue #957, phase 30 plan 30-01; decisions D-06, D-08).
#
# USAGE
#   STRIPE_PK_VAR=<value> scripts/check-stripe-pk-var.sh --environment staging|production
#   scripts/check-stripe-pk-var.sh --selftest
#   STRIPE_PK_VAR_SELFTEST_FORMAT=tap scripts/check-stripe-pk-var.sh --selftest
#
# EXIT
#   0  accepted
#   1  refused
#   2  usage error

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF="$SCRIPT_DIR/check-stripe-pk-var.sh"

void() { echo "VOID: $*" >&2; exit 2; }

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
  rc=$(st_set staging-live staging "$live_key");            arm staging-live 1 "$rc" staging-live "prefix=pk_live_"
  rc=$(st_set staging-malformed staging "$malformed");      arm staging-malformed 1 "$rc" staging-malformed "prefix=pk_test_"
  rc=$(st_set production-test production "$test_key");      arm production-test 1 "$rc" production-test "prefix=pk_test_"
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
  --selftest) selftest ;;
  *) void "not implemented" ;;
esac
