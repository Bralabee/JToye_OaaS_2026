#!/usr/bin/env bash
# ---------------------------------------------------------------------------------
# check-go-coverage.sh — read the edge-go coverage profile CI has been producing, and
#                        fail when it drops below a measured floor.
#
# WHY THIS EXISTS
#
#   .github/workflows/ci-cd.yaml has run
#
#       go test -v -coverprofile=coverage.out ./...
#
#   in job `test` for months, and uploaded `edge-go/coverage.out` as an artifact. Nothing
#   anywhere read it. There was no `go tool cover` invocation, no threshold, no consumer
#   of any kind in the repository (issue #110, re-confirmed by measurement 2026-08-28).
#
#   A measurement nobody reads is not a gate. Every one of edge-go's packages could have
#   fallen to zero and the build would have stayed green, because producing the number and
#   checking the number are two different acts and only the first was happening.
#
# WHY AN EMPTY PROFILE MUST NOT READ AS 0%
#
#   MEASURED on this machine, 2026-08-28, Go 1.26:
#
#       go tool cover -func=<an EMPTY file>          -> rc=0, "total: (statements) 0.0%"
#       go tool cover -func=<a file with only 'mode: set'>
#                                                   -> rc=0, "total: (statements) 0.0%"
#       go tool cover -func=<a file of junk>         -> rc=2, "cover: bad mode line: ..."
#
#   So the obvious shape — trust rc, then trust the total — turns a BROKEN OR SKIPPED TEST
#   RUN into "0.0% coverage", which this gate would then report as a coverage regression.
#   That misdiagnoses an infrastructure failure as a code problem, and it is exactly the
#   fail-open shape CLAUDE.md's falsifiable-evidence contract forbids: "found nothing" is
#   never "clean". This gate therefore inspects the profile STRUCTURALLY before believing
#   any percentage from it — a mode line plus at least one coverage data line — and VOIDs
#   (exit 2) when either is absent.
#
# WHAT IT ENFORCES
#   G-1  the profile exists, is non-empty, and parses as a Go coverage profile
#        (mode line + >= 1 data line). Anything else is VOID, never 0%.
#   G-2  `go tool cover -func` succeeds and emits a numeric `total:` line.
#   G-3  that total is at or above MIN_TOTAL_PERCENT.
#
# WHY A FLOOR AND NOT A TARGET
#
#   MIN_TOTAL_PERCENT is a NO-REGRESSION GUARDRAIL. It says "coverage has not fallen",
#   not "coverage is good enough". Raising it is a deliberate act that must come with its
#   own fresh measurement recorded below. LOWERING IT TO MAKE A RED BUILD GREEN IS THE
#   FAILURE MODE, and this repo has already written that down once — see
#   frontend/e2e/perf-budgets.ts:64-70: "Raising a budget until the tree passes is how a
#   budget stops meaning anything." If this gate goes red, the answer is a test, not a
#   smaller number.
#
# MEASURED
#
#   2026-08-28, `go test -coverprofile=coverage.out ./...` on this tree, Go 1.26.
#   Identical to the figures 34-RESEARCH.md recorded the same day (independent re-run):
#
#       github.com/jtoye/edge/cmd/edge              49.8%   (128/257 stmts)
#       github.com/jtoye/edge/docs                   0.0%   (0/1 stmts)   generated swag docs
#       github.com/jtoye/edge/internal/auth         88.6%   (31/35 stmts)
#       github.com/jtoye/edge/internal/core         80.0%   (92/115 stmts)
#       github.com/jtoye/edge/internal/middleware   79.8%   (91/114 stmts)
#       github.com/jtoye/edge/internal/whatsapp     92.6%   (25/27 stmts)
#       ------------------------------------------------------------------
#       total                                       66.8%   (367/549 stmts)
#
#   MIN_TOTAL_PERCENT is 65.0 — 1.8 points below the measurement. The margin exists
#   because CI is not this machine: 34-RESEARCH assumption A2 rates the "a threshold just
#   below today's local number will not flake" risk MED, since a hosted runner can differ
#   in Go minor version and in which packages build. 1.8 points is ~10 statements of the
#   549 in the profile, which is wider than any version-driven drift seen here and far
#   narrower than a deleted test file. The first CI run of this gate is itself the
#   measurement A2 asks for; if it lands below 65.0 the correct response is to record the
#   CI number here, not to widen the margin silently.
#
#   The per-package table is printed on FAILURE because a total that moved is nearly
#   always one package, and a bare "66.8 < 65" tells the reader nothing about where to
#   look. The table is computed from the profile itself (statements covered / statements
#   total, per directory) and was validated against `go test`'s own per-package output on
#   2026-08-28: all six figures identical to the tenth of a point.
#
# INPUT
#   edge-go/coverage.out, produced by:
#     cd edge-go && go test -coverprofile=coverage.out ./...
#   Overrides: GO_COVERPROFILE=<file>   GO_MODULE_DIR=<dir>
#
#   `go tool cover -func` resolves the package paths recorded in the profile, so it MUST
#   run inside the module (measured: from the repo root it exits non-zero with
#   "go.mod file not found in current directory or any parent directory"). This gate cds
#   into GO_MODULE_DIR itself, so it can be invoked from anywhere — the CI step needs no
#   `working-directory:`.
#
# EXIT CODES
#   0 = total coverage is at or above the floor
#   1 = total coverage is below the floor
#   2 = VOID — missing/empty/unparseable profile, no Go toolchain, no module directory,
#       `go tool cover` failed, no total line, or a non-numeric total.
#       "Found nothing" is never "clean".
#
# USAGE
#   scripts/check-go-coverage.sh
#   GO_COVERPROFILE=/tmp/coverage.out scripts/check-go-coverage.sh
# ---------------------------------------------------------------------------------
set -uo pipefail

# --- the floor -----------------------------------------------------------------------
# NO-REGRESSION GUARDRAIL, not a target. Measured total on 2026-08-28: 66.8%.
# Margin: 1.8 points. Read the MEASURED and "WHY A FLOOR" sections above before editing.
MIN_TOTAL_PERCENT=65.0

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="${GO_MODULE_DIR:-$REPO_ROOT/edge-go}"
PROFILE="${GO_COVERPROFILE:-$MODULE_DIR/coverage.out}"

echo "check-go-coverage  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"

void() { echo "VOID: $*" >&2; exit 2; }
fail() { echo "FAIL: $*" >&2; }

while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'; exit 0 ;;
        *) void "unknown argument: $1 (try --help)" ;;
    esac
done

command -v go >/dev/null 2>&1 || void "the Go toolchain is not installed — coverage cannot be read"
[ -d "$MODULE_DIR" ] || void "module directory not found: $MODULE_DIR"
[ -f "$MODULE_DIR/go.mod" ] || void "no go.mod in $MODULE_DIR — 'go tool cover' cannot resolve package paths there"

echo "  profile   : $PROFILE"
echo "  module    : $MODULE_DIR"
echo "  floor     : ${MIN_TOTAL_PERCENT}% (no-regression guardrail)"

# --- G-1: the profile must BE a coverage profile --------------------------------------
# Structural, and deliberately BEFORE any percentage is read. See "WHY AN EMPTY PROFILE
# MUST NOT READ AS 0%" above: `go tool cover` reports 0.0% at rc=0 for an empty file and
# for a mode-line-only file, so believing rc alone converts a broken run into a coverage
# failure. A profile that describes no statements describes nothing.
[ -f "$PROFILE" ] || void "coverage profile not found: $PROFILE — run 'cd edge-go && go test -coverprofile=coverage.out ./...' first. An absent profile is not 0% coverage."
[ -s "$PROFILE" ] || void "coverage profile is EMPTY (0 bytes): $PROFILE — an empty profile means the test run produced nothing, not that nothing is covered."

first_line=$(head -n 1 "$PROFILE"); rc=$?
[ "$rc" -eq 0 ] || void "could not read $PROFILE (head rc=$rc)"
case "$first_line" in
    mode:*) : ;;
    *) void "unparseable coverage profile $PROFILE — first line is '${first_line}', expected a 'mode:' header. This is not a Go coverage profile." ;;
esac

# A data line is `name.go:startLine.col,endLine.col numStatements count`.
data_lines=$(awk '$1 ~ /:[0-9]+\.[0-9]+,[0-9]+\.[0-9]+$/ && $2 ~ /^[0-9]+$/ && $3 ~ /^[0-9]+$/ { n++ } END { print n+0 }' "$PROFILE"); rc=$?
[ "$rc" -eq 0 ] || void "could not scan $PROFILE for coverage data lines (awk rc=$rc)"
[ "$data_lines" -gt 0 ] 2>/dev/null \
    || void "unparseable coverage profile $PROFILE — it carries a mode line but ZERO coverage data lines. 'go tool cover' would report 0.0% at rc=0 for this file; that is a broken or skipped test run, not a coverage regression."

echo "  data lines: $data_lines"

# --- G-2: read the total, from inside the module --------------------------------------
func_out=$(cd "$MODULE_DIR" && go tool cover -func="$PROFILE" 2>&1); rc=$?
[ "$rc" -eq 0 ] || void "'go tool cover -func' failed (rc=$rc) in $MODULE_DIR: ${func_out}"

total=$(printf '%s\n' "$func_out" | awk '/^total:/ { gsub(/%/, "", $NF); print $NF }'); rc=$?
[ "$rc" -eq 0 ] || void "could not extract the total line from 'go tool cover' output (awk rc=$rc)"
[ -n "$total" ] || void "'go tool cover' emitted no 'total:' line for $PROFILE — unparseable output, not 0% coverage."
case "$total" in
    ''|*[!0-9.]*|*.*.*) void "non-numeric coverage total '${total}' from $PROFILE — refusing to compare a value that is not a number." ;;
esac

echo "  measured  : ${total}%"

# --- G-3: compare, numerically ---------------------------------------------------------
# awk, not `[ "$total" -ge ... ]` (integers only) and not string comparison: "9.0" > "65.0"
# lexically, which would pass a catastrophic regression.
awk -v t="$total" -v min="$MIN_TOTAL_PERCENT" 'BEGIN { exit !(t + 0 >= min + 0) }'; rc=$?
if [ "$rc" -ne 0 ]; then
    fail "edge-go total coverage ${total}% is BELOW the floor of ${MIN_TOTAL_PERCENT}%"
    echo "" >&2
    echo "  per-package breakdown (statements covered / total, from the profile):" >&2
    awk 'NR == 1 && /^mode:/ { next }
         $1 ~ /:[0-9]+\.[0-9]+,[0-9]+\.[0-9]+$/ {
             split($1, a, ":"); f = a[1];
             n = split(f, p, "/"); dir = "";
             for (i = 1; i < n; i++) dir = dir (i > 1 ? "/" : "") p[i];
             tot[dir] += $2;
             if ($3 + 0 > 0) cov[dir] += $2;
         }
         END {
             for (d in tot)
                 printf "    %-45s %6.1f%%  (%d/%d stmts)\n", d, (tot[d] ? 100 * cov[d] / tot[d] : 0), cov[d], tot[d];
         }' "$PROFILE" | sort >&2
    echo "" >&2
    echo "  A total that moved is usually one package. Add tests there." >&2
    echo "  Do NOT lower MIN_TOTAL_PERCENT to go green — see the header." >&2
    exit 1
fi

echo "PASS: edge-go total coverage ${total}% >= ${MIN_TOTAL_PERCENT}% floor (${data_lines} coverage blocks)"
exit 0
