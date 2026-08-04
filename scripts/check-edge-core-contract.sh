#!/usr/bin/env bash
#
# check-edge-core-contract.sh — issue #337: the edge↔core contract gate.
#
# The edge gateway hand-writes its five calls to core-java. Go's encoding/json
# ignores fields it cannot place, so a renamed core field does not error — it
# decodes to a zero value and the edge reports it as fact. Nothing caught that
# class of drift before this gate: the `OpenAPI Breaking-Change Gate` diffs
# core's spec against its own reviewed snapshot (core vs core), and the edge's
# openapi_test.go checks the edge's published spec against its own routes
# (edge vs edge). Neither crosses the boundary. This one does.
#
# The check itself is edge-go/internal/core/contract_test.go, driven by the
# manifest in contract.go. This wrapper exists to give it the operational
# contract the other ops-contracts gates use, and to assert the thing a bare
# `go test` cannot: that the check ACTUALLY RAN.
#
# Exit codes (the repo's ops-contracts convention):
#   0  clean    — every declared edge→core call matches core's snapshot
#   1  violation— a divergence was found; the report names the field or path
#   2  VOID     — the check could not be run or did not execute. Fails the
#                 build on purpose: "found nothing" is never "clean".
#
# Local usage:
#   scripts/check-edge-core-contract.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EDGE_DIR="$ROOT/edge-go"
SNAPSHOT="$ROOT/docs/api/openapi-snapshot.json"
TEST_NAME="TestEdgeCoreContract"
TEST_PKG="./internal/core/"

void() {
	echo "VOID: $*" >&2
	echo "  (exit 2 — the gate could not vouch for anything; this fails the build by design)" >&2
	exit 2
}

# --- preconditions. Each of these would otherwise make the run silently vacuous.

command -v go >/dev/null 2>&1 || void "the Go toolchain is not on PATH; the contract check cannot run"
[ -d "$EDGE_DIR" ] || void "edge-go/ not found at $EDGE_DIR"
[ -f "$SNAPSHOT" ] || void "core's reviewed OpenAPI snapshot is missing at $SNAPSHOT
  Regenerate with: ./gradlew :core-java:updateOpenApiSnapshot"
[ -s "$SNAPSHOT" ] || void "core's OpenAPI snapshot at $SNAPSHOT is EMPTY; every comparison would pass vacuously"
[ -f "$EDGE_DIR/internal/core/contract_test.go" ] || void "the gate itself (edge-go/internal/core/contract_test.go) is missing"

# --- run it.
#
# -count=1 is load-bearing: without it Go serves a cached result and prints
# `ok ... (cached)` WITHOUT executing the test, which is the "reports success
# while executing nothing" failure mode this repo has been bitten by.
# -v is load-bearing too: it is what makes the per-test PASS/FAIL line appear,
# which is the only evidence the named test actually ran.
echo "Running $TEST_NAME (edge-go $TEST_PKG) against $SNAPSHOT"
OUT="$(cd "$EDGE_DIR" && go test -count=1 -v -run "^${TEST_NAME}\$" "$TEST_PKG" 2>&1)"
rc=$?

echo "$OUT"
echo

# --- did it actually execute? A `go test` that matched no test, or failed to
# build, exits 0 or 1 with no per-test verdict line at all. Decide on the
# verdict line, never on the exit code alone.
ran_pass=0
ran_fail=0
while IFS= read -r line; do
	case "$line" in
	*"--- PASS: ${TEST_NAME}"*) ran_pass=1 ;;
	*"--- FAIL: ${TEST_NAME}"*) ran_fail=1 ;;
	esac
done <<<"$OUT"

if [ "$ran_pass" -eq 0 ] && [ "$ran_fail" -eq 0 ]; then
	void "'$TEST_NAME' produced no verdict line — it did not run (build failure, renamed test, or no match).
  go test exited $rc. A pass here would be a pass over nothing."
fi

if [ "$ran_fail" -eq 1 ] || [ "$rc" -ne 0 ]; then
	echo "VIOLATION: the edge's calls to core-java no longer match core's reviewed OpenAPI snapshot." >&2
	echo "  The report above names the call and the field/path that diverged." >&2
	echo "  Fix the edge client (edge-go/internal/core/), or — if core changed on purpose —" >&2
	echo "  update edge-go/internal/core/contract.go in the SAME PR so the new shape is reviewed." >&2
	exit 1
fi

echo "OK: every declared edge→core call matches core's reviewed OpenAPI snapshot."
exit 0
