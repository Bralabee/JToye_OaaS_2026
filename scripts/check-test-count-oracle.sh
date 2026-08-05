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
#   It also closes the one hole the static counter cannot: a test declared inside a
#   `for` loop is one declaration and N executions, and no static reader resolves
#   that. The runner does.
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

manifest_int() { # <key>
	local v
	v=$(jq -r --arg k "$1" 'if has($k) then (.[$k]|tostring) else "__ABSENT__" end' "$MANIFEST")
	case "$v" in
		__ABSENT__) void "docs/metrics.json has no key '$1'" ;;
		''|*[!0-9]*) void "docs/metrics.json .$1 = '$v' is not a plain integer" ;;
	esac
	printf '%s' "$v"
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
	expected=$(manifest_int "$2")
	printf '  %-34s runner=%-6s manifest=%s\n' "$1" "$3" "$expected"
	[ "$3" = "$expected" ] || fail "$1: the runner says $3, docs/metrics.json says $expected. Run scripts/docs-freshness.sh --write and update the prose counts."
}

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
