#!/usr/bin/env bash
#
# check-test-count-oracle.sh — assert docs/metrics.json against the TEST RUNNERS.
#
# WHY THIS EXISTS (issue #291)
#
#   scripts/docs-freshness.sh asserts docs/metrics.json against the source tree, and
#   scripts/check-doc-metrics.sh asserts the prose against docs/metrics.json. Both
#   were green for months over a Jest count that was simply wrong: the tree-side gate
#   counted `\b(it|test)\(`, which matches `RegExp.prototype.test(` and cannot see a
#   table-driven `it.each([...])` at all. Manifest 745 (357 at Phase 23), runner 789
#   (352 at Phase 23) — and NOTHING in CI ever compared the two, so the loop was
#   closed on itself. A self-consistent loop cannot detect that its own measurement
#   is the thing that is wrong.
#
#   scripts/count-test-blocks.mjs fixed the measurement. This is the check that can
#   prove it stayed fixed: it asks each runner how many tests it actually has and
#   compares that to the manifest. It is the only gate here whose answer does not
#   come from reading the source with a regex.
#
#   It also closes the hole the static counter cannot: a test declared inside a loop
#   is one declaration and N executions, and no static reader resolves that. The
#   runner does.
#
#   Since #582 the static counter no longer UNDER-COUNTS that shape for jest/vitest —
#   it VOIDs on it, because a silent under-count made this gate and docs-freshness
#   mutually unsatisfiable and both are required checks. That refusal is lexical
#   (`for`/`while`/`do` bodies and array-iteration callbacks), so a hand-rolled helper
#   that loops still slips past it and this remains the last word.
#
# WHAT IT ASSERTS, per family:
#   jest        docs/metrics.json .jest_blocks       == jest's numTotalTests
#               docs/metrics.json .jest_files        == jest's numTotalTestSuites
#   playwright  docs/metrics.json .playwright_blocks == unique (file,line,column)
#                                                       spec sites in `--list`
#               docs/metrics.json .playwright_specs  == unique spec files
#   vitest      docs/metrics.json .mcp_test_blocks   == vitest's numTotalTests
#               docs/metrics.json .mcp_test_files    == input files with >=1 test
#
#   Playwright is counted by DECLARATION SITE, not by executed test, because its
#   project matrix (desktop + mobile) runs every spec more than once — 80 sites
#   become 182 runs on this tree — and `playwright_blocks` has always meant
#   "`test()` blocks in the source", which is what README and CLAUDE.md quote.
#
# FAIL-CLOSED. Missing jq/node/npx, missing node_modules, an unparseable report, or a
# runner that reports ZERO tests all exit 2 (VOID). "Could not ask the runner" is
# never rendered as "asked it and it agreed" — a suite that fails to boot reports
# 0 tests, and 0 must never silently satisfy anything.
#
# Usage:
#   scripts/check-test-count-oracle.sh jest [--report <jest --json outputFile>]
#   scripts/check-test-count-oracle.sh playwright
#   scripts/check-test-count-oracle.sh vitest
#
# Exit codes: 0 = runner agrees with the manifest, 1 = disagreement, 2 = VOID.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT/docs/metrics.json"
cd "$ROOT"

FAMILY="${1:-}"
REPORT=""
shift || true
while [ $# -gt 0 ]; do
	case "$1" in
		--report) REPORT="${2:-}"; shift 2 ;;
		*) echo "VOID: unknown argument '$1'" >&2; exit 2 ;;
	esac
done

void() { printf 'VOID: %s\n' "$1" >&2; exit 2; }
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

command -v jq   >/dev/null 2>&1 || void "jq is not installed"
command -v node >/dev/null 2>&1 || void "node is not installed"
command -v npx  >/dev/null 2>&1 || void "npx is not installed"
[ -f "$MANIFEST" ] || void "$MANIFEST is missing"
jq -e . "$MANIFEST" >/dev/null 2>&1 || void "$MANIFEST is not parseable JSON"

# Sets MANIFEST_INT rather than printing, and is called as a STATEMENT, never as
# `x=$(manifest_int k)`.
#
# WHY. void() ends in `exit 2`, and inside a command substitution that exits only the
# SUBSHELL. The caller then read an empty string and carried on, so every VOID here
# surfaced as a rc=1 FAIL reading "the runner says 789, docs/metrics.json says ." — the
# wrong severity (on this repo 2 means "could not check", 1 means "checked, and it is
# wrong") attached to the wrong remedy: it advised `docs-freshness.sh --write` for a
# manifest that was unreadable rather than stale.
#
# docs-freshness.sh's count_js already documents and avoids exactly this shape. This is
# the sibling script that reintroduced it.
MANIFEST_INT=""
manifest_int() { # <key> -> sets MANIFEST_INT
	local v
	# The type test happens in jq, BEFORE tostring: `"789"` is a JSON string and must be
	# refused, but tostring would normalise it to 789 and slip it past a shell glob test.
	v=$(jq -r --arg k "$1" '
		if (has($k) | not) then "__ABSENT__"
		elif (.[$k] | type) != "number" then "__NOTNUM__"
		else (.[$k] | tostring) end' "$MANIFEST")
	case "$v" in
		__ABSENT__) void "docs/metrics.json has no key '$1'" ;;
		__NOTNUM__) void "docs/metrics.json .$1 is not a JSON number" ;;
		''|*[!0-9]*) void "docs/metrics.json .$1 = '$v' is not a plain integer" ;;
	esac
	MANIFEST_INT="$v"
}

# A count of 0 from a runner means the suite did not boot, not that there are no
# tests. Refuse it in every family rather than letting an empty run look agreeable.
require_positive() { # <label> <value>
	case "$2" in
		''|*[!0-9]*) void "$1: runner produced a non-numeric count '$2'" ;;
		0) void "$1: runner reported ZERO tests — the suite did not run. Not a pass." ;;
	esac
}

compare() { # <label> <manifest-key> <observed>
	local expected
	manifest_int "$2"          # statement, not $( ) — see manifest_int
	expected="$MANIFEST_INT"
	printf '  %-34s runner=%-6s manifest=%s\n' "$1" "$3" "$expected"
	[ "$3" = "$expected" ] || fail "$1: the runner says $3, docs/metrics.json says $expected. Run scripts/docs-freshness.sh --write and update the prose counts."
}

# --- a BARE invocation runs every family ----------------------------------------------------
#
# WHY. This gate used to exit 2 (VOID) on a bare call, because "no family" fell through to the
# usage arm. That is the same exit code the script uses for "I could not perform the check", and
# on this repo VOID is a real, expected state that a reader is trained to investigate — so a
# usage error was indistinguishable from a genuine inability to verify.
#
# It cost real time on 2026-08-07. HANDOFF.md §6 instructs the next session to sweep every gate
# with a bare invocation; that sweep reported this script as `VOID(2)` alongside a genuine
# post-recreate failure in check-alert-metrics, and the two had to be told apart by hand. The
# sweep instruction was not wrong — the script was the only one of 29 that could not answer it.
#
# So the bare form now means what a reader sweeping gates obviously intends: CHECK EVERYTHING.
# It runs jest, playwright and vitest in turn and aggregates.
#
# AN UNKNOWN FAMILY STILL VOIDS. `check-test-count-oracle.sh jset` is a typo, not a request to
# check everything, and silently running all three would hide it. Absent-argument and
# wrong-argument are different mistakes and keep different answers.
#
# SEVERITY PRECEDENCE MATCHES THE SIBLING GATES: a real FAIL (1) outranks a VOID (2), so a
# genuine count mismatch is never masked by an unrelated missing runner. Same rule as
# check-handoff-contract.sh and check-changelog-contract.sh.
#
# `--report` is rejected here rather than silently ignored: it names a single runner's report and
# cannot mean anything across three families.
if [ -z "$FAMILY" ]; then
	[ -z "$REPORT" ] || void "--report names one runner's report and cannot apply to all families — call '$0 jest --report <file>'"
	printf 'check-test-count-oracle [all]  (%s)\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
	all_fail=0
	all_void=0
	for fam in jest playwright vitest; do
		bash "$0" "$fam"; rc=$?          # captured on the same line — never after an echo
		case "$rc" in
			0) ;;
			1) all_fail=1 ;;
			*) all_void=1 ;;
		esac
		printf '  --- %s -> rc=%s\n' "$fam" "$rc"
	done
	if [ "$all_fail" -ne 0 ]; then
		echo "FAILED: at least one runner disagrees with docs/metrics.json (see above)." >&2
		exit 1
	fi
	if [ "$all_void" -ne 0 ]; then
		echo "VOID: at least one family could not be checked (see above) — treat as unverified, not as a pass." >&2
		exit 2
	fi
	printf 'PASS: all three runners (jest, playwright, vitest) agree with docs/metrics.json.\n'
	exit 0
fi

printf 'check-test-count-oracle [%s]  (%s)\n' "${FAMILY:-<none>}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"

case "$FAMILY" in
# ---------------------------------------------------------------------------
jest)
	[ -d "$ROOT/frontend/node_modules" ] || void "frontend/node_modules is absent — run npm ci in frontend/ first"
	if [ -n "$REPORT" ]; then
		[ -f "$REPORT" ] || void "--report '$REPORT' does not exist"
	else
		REPORT="$(mktemp -t jtoye-jest-oracle-XXXXXX.json)"
		# shellcheck disable=SC2064
		trap "rm -f '$REPORT'" EXIT
		out=$(cd "$ROOT/frontend" && npx jest --ci --silent --json --outputFile="$REPORT" 2>&1)
		rc=$?
		if [ "$rc" -ne 0 ]; then
			printf '%s\n' "$out" >&2
			void "npx jest exited $rc — a red or non-booting suite cannot be used as a count oracle"
		fi
	fi
	jq -e . "$REPORT" >/dev/null 2>&1 || void "the jest report at $REPORT is not parseable JSON"
	blocks=$(jq -r '.numTotalTests // "x"' "$REPORT")
	files=$(jq -r '.numTotalTestSuites // "x"' "$REPORT")
	require_positive "jest blocks" "$blocks"
	require_positive "jest files"  "$files"
	compare "jest it/test blocks" jest_blocks "$blocks"
	compare "jest test files"     jest_files  "$files"
	;;
# ---------------------------------------------------------------------------
playwright)
	[ -d "$ROOT/frontend/node_modules" ] || void "frontend/node_modules is absent — run npm ci in frontend/ first"
	LIST="$(mktemp -t jtoye-pw-oracle-XXXXXX.json)"
	# shellcheck disable=SC2064
	trap "rm -f '$LIST'" EXIT
	out=$(cd "$ROOT/frontend" && npx playwright test --list --reporter=json 2>&1 >"$LIST")
	rc=$?
	if [ "$rc" -ne 0 ]; then
		printf '%s\n' "$out" >&2
		void "npx playwright test --list exited $rc — cannot enumerate declarations"
	fi
	jq -e . "$LIST" >/dev/null 2>&1 || void "the playwright --list report is not parseable JSON"
	# A spec is one `test()` declaration site. The project matrix repeats each spec
	# per project, so de-duplicate on (file, line, column) — otherwise the desktop
	# and mobile projects would each be counted as a separate block.
	blocks=$(jq '[.suites | .. | objects | select(has("specs")) | .specs[]? | {f:.file,l:.line,c:.column}] | unique | length' "$LIST")
	files=$(jq  '[.suites | .. | objects | select(has("specs")) | .specs[]? | .file]                        | unique | length' "$LIST")
	require_positive "playwright blocks" "$blocks"
	require_positive "playwright specs"  "$files"
	compare "playwright test() blocks" playwright_blocks "$blocks"
	compare "playwright spec files"    playwright_specs  "$files"
	;;
# ---------------------------------------------------------------------------
vitest)
	[ -d "$ROOT/mcp-server/node_modules" ] || void "mcp-server/node_modules is absent — run npm ci in mcp-server/ first"
	REPORT="$(mktemp -t jtoye-vitest-oracle-XXXXXX.json)"
	# shellcheck disable=SC2064
	trap "rm -f '$REPORT'" EXIT
	out=$(cd "$ROOT/mcp-server" && npx vitest run --reporter=json --outputFile="$REPORT" 2>&1)
	rc=$?
	if [ "$rc" -ne 0 ]; then
		printf '%s\n' "$out" >&2
		void "npx vitest run exited $rc — a red or non-booting suite cannot be used as a count oracle"
	fi
	jq -e . "$REPORT" >/dev/null 2>&1 || void "the vitest report is not parseable JSON"
	blocks=$(jq -r '.numTotalTests // "x"' "$REPORT")
	# vitest's numTotalTestSuites counts describe blocks, not files, so the file
	# count comes from the distinct source files in its own results.
	files=$(jq '[.testResults[]?.name] | unique | length' "$REPORT")
	require_positive "vitest blocks" "$blocks"
	require_positive "vitest files"  "$files"
	compare "mcp vitest it/test blocks" mcp_test_blocks "$blocks"
	compare "mcp vitest test files"     mcp_test_files  "$files"
	;;
# ---------------------------------------------------------------------------
*)
	void "usage: $0 <jest|playwright|vitest> [--report <file>]"
	;;
esac

printf 'PASS: the %s runner agrees with docs/metrics.json.\n' "$FAMILY"
