#!/usr/bin/env bash
# ---------------------------------------------------------------------------------
# check-jacoco-coverage.sh — enforce a floor on the AGGREGATE Java coverage of
#                            `test` + `integrationTest`, and VOID when it cannot
#                            measure rather than publish a number it does not have.
#
# THIS IS NOT A UNIT-COVERAGE FIGURE. READ THIS BEFORE QUOTING ANY NUMBER IT PRINTS.
#
#   core-java runs ONE test suite through TWO Gradle tasks. `test` EXCLUDES the
#   `testcontainers` tag (build.gradle.kts:183-186); `integrationTest` runs ONLY that
#   tag (:202-284). Both drive sourceSets["test"]. So `test` alone executes roughly
#   two thirds of the suite, and the coverage it reports is TWENTY-FIVE POINTS BELOW
#   the real figure. Measured on this tree 2026-08-29:
#
#       counter        `test` only   `test` + `integrationTest`   delta
#       INSTRUCTION        62.55%                       88.06%   +25.51
#       BRANCH             51.03%                       71.88%   +20.85
#       LINE               62.12%                       87.55%   +25.43
#       METHOD             65.01%                       87.53%   +22.52
#
#   The unit-only figure must NEVER be published as this project's coverage. It is
#   wrong by a quarter of the codebase. A floor set from it — say "60% line" — would
#   sit about two points under a tree that is actually at 87.55%, so it could never
#   catch any regression a human would care about: three quarters of the covered
#   lines could stop being covered before it noticed. This script therefore reads the
#   AGGREGATE report, and J-4 below makes it impossible to satisfy with a unit-only
#   one. That check is the difference between a gate and a decoration.
#
# WHERE THIS GATE MAY RUN
#
#   ONLY where BOTH suites have executed. Anywhere else it VOIDs, by design.
#
#   ci-cd.yaml's `integration-tests` job is path-filtered: on a pull_request whose
#   diff cannot affect the Java suite it SKIPS the suite and STILL REPORTS SUCCESS,
#   deliberately, so it stays a satisfiable required check. That green says nothing
#   whatsoever about coverage, and the job's skip-notice step now says so in words.
#   This gate is guarded by the same `if:` expression as the suite it measures, so it
#   never runs on a skipped run — and if it is ever invoked without its inputs it
#   exits 2 instead of inventing a number. "Could not measure" is not "measured and
#   fine", and a job's SUCCESS must never be readable as a coverage result.
#
#   The same fail-open shape exists inside Gradle: JacocoReport carries a built-in
#   `onlyIf` that SKIPS the report task when no execution data file exists, so a
#   missing .exec produces a GREEN build and NO report rather than an error. A gate
#   that then read a stale CSV, or treated a missing one as 0%, would convert a
#   broken measurement into a code regression and send someone hunting for deleted
#   tests. That is the same trap scripts/check-go-coverage.sh's structural pre-parse
#   exists to prevent, reached through a different door.
#
# WHAT IT ENFORCES
#   J-1  BOTH execution data files exist and are non-empty:
#          core-java/build-local/jacoco/test.exec
#          core-java/build-local/jacoco/integrationTest.exec
#        A missing one is VOID, naming which. This is the direct expression of "the
#        report being read was produced from both suites", and it is checked first
#        because no CSV can tell you which .exec files produced it.
#   J-2  each CSV exists, is non-empty, carries JaCoCo's exact 13-column header, and
#        has at least one data row. Anything else is VOID, never 0%.
#   J-3  every counter column is numeric and every denominator is non-zero. A report
#        claiming the codebase has zero lines is a broken measurement, not 0%.
#   J-4  the aggregate and the unit-only report describe the SAME class set (equal
#        denominators — proven equal by measurement, see MEASURED), and the aggregate
#        STRICTLY EXCEEDS the unit-only report on all four covered counters. The unit
#        CSV is REQUIRED, not optional: without it there is nothing to compare
#        against. An "aggregate" that merely equals the unit figure is a unit report
#        wearing the wrong filename — which is exactly what a stale CSV, or one
#        generated before integrationTest.exec landed, looks like.
#   J-5  each of the four ratios is at or above its floor.
#
#   All four ratios print on EVERY run, pass or fail, so the number is visible in the
#   job log rather than only when something breaks.
#
# WHY FLOORS AND NOT TARGETS
#
#   These are NO-REGRESSION GUARDRAILS. They say "coverage has not fallen", not
#   "coverage is good enough". LOWERING ONE TO MAKE A RED BUILD GREEN IS THE FAILURE
#   MODE, and this repo has already written that down once — frontend/e2e/
#   perf-budgets.ts:64-70: "Raising a budget until the tree passes is how a budget
#   stops meaning anything." If this gate goes red, the answer is a test.
#
# MEASURED
#
#   2026-08-29, this tree, JaCoCo 0.8.12 / Gradle 8.10.2 / JDK 21, on a 16-core box.
#
#     ./gradlew :core-java:test :core-java:jacocoTestReport --no-daemon
#         rc=0, 1m18s. INSTRUCTION 62.55% (30437/48657)  BRANCH 51.03% (1539/3016)
#                      LINE        62.12% (6826/10989)   METHOD 65.01% (2023/3112)
#
#     ./gradlew :core-java:integrationTest :core-java:jacocoAggregateReport --no-daemon
#         rc=0, 22m25s, 607 tests / 0 failures / 1 skipped / 132 classes.
#                      INSTRUCTION 88.06% (42847/48657)  BRANCH 71.88% (2168/3016)
#                      LINE        87.55% (9621/10989)   METHOD 87.53% (2724/3112)
#
#   Both CSVs carry 405 class rows and IDENTICAL denominators — 48657 instructions,
#   3016 branches, 10989 lines, 3112 methods. That equality is MEASURED, not assumed,
#   and it is what licenses the same-class-set assertion in J-4: both reports analyse
#   the same `main` sourceSet, so a disagreement means one of them is stale.
#
#   34-RESEARCH.md measured 88.07 / 71.95 / 87.55 / 87.53 earlier the same week. This
#   is an independent re-run on a tree that has moved since, and it reproduces those
#   figures to within 0.07 of a point — so the research number is confirmed rather
#   than restated, and the two rows above are this tree's own.
#
#   FLOORS: floor(aggregate measurement) - 2, as ONE stated rule for all four
#   counters rather than four separately-negotiated numbers (the same rule plan
#   34-08 applied to the Go and Jest thresholds).
#
#       counter        measured   floor   margin
#       INSTRUCTION      88.06%      86    2.06 points
#       BRANCH           71.88%      69    2.88 points
#       LINE             87.55%      85    2.55 points
#       METHOD           87.53%      85    2.53 points
#
#   The margin exists because CI is not this machine: 34-RESEARCH assumption A2 rates
#   the "a threshold just below today's local number will not flake" risk MED, since
#   a hosted runner can differ in JDK minor, in fork count (maxParallelForks is
#   derived from availableProcessors(): 4 here, 1-2 on a hosted runner) and in which
#   Testcontainers classes complete. On this tree 2 points of line coverage is ~220
#   lines of 10989 — wider than any drift seen here, far narrower than a deleted test
#   class.
#
# CI CALIBRATION
#
#   RESEARCH A2 asks for the baseline to be measured WHERE THE GATE WILL RUN, not
#   only locally. The block below records that; if it says the calibration run did
#   not complete, these floors are LOCAL-ONLY and must be described that way rather
#   than as calibrated.
#
#   CI CALIBRATION RESULT: NOT YET RUN. The floors above are LOCAL-ONLY as of this
#   commit. Plan 34-09 Task 3 wires the gate into ci-cd.yaml and runs the pipeline on
#   a push branch; this block is rewritten with the run id, its conclusion and the
#   four CI-measured ratios at that point. Until it is, do not call these calibrated.
#
# INPUT
#   core-java/build-local/reports/jacoco/aggregate/jacocoAggregateReport.csv
#   core-java/build-local/reports/jacoco/test/jacocoTestReport.csv
#   core-java/build-local/jacoco/test.exec
#   core-java/build-local/jacoco/integrationTest.exec
#
#   Produced by:
#     ./gradlew :core-java:test :core-java:jacocoTestReport --no-daemon
#     ./gradlew :core-java:integrationTest :core-java:jacocoAggregateReport --no-daemon
#
#   In CI the two suites run in two different jobs, so job `test` uploads its
#   test.exec as an artifact and the integration job downloads it into
#   core-java/build-local/jacoco/ before generating BOTH reports. Generating the
#   unit-only report there costs nothing — jacocoTestReport only `mustRunAfter`s the
#   test task, it does not depend on it, so it re-reports the downloaded .exec
#   without executing a single test.
#
#   core-java/build-local is the LIVE build directory (build.gradle.kts:15 redirects
#   layout.buildDirectory). core-java/build/ is STALE, and reading it is a recorded
#   stale-artifact trap in this repo — every default path below is build-local.
#
#   Overrides: JACOCO_BUILD_DIR  JACOCO_AGGREGATE_CSV  JACOCO_UNIT_CSV  JACOCO_EXEC_DIR
#
# EXIT CODES
#   0 = every counter is at or above its floor
#   1 = at least one counter is below its floor
#   2 = VOID — a missing or empty execution data file, a missing/empty/header-only/
#       unparseable CSV, a non-numeric column, a zero denominator, a missing unit
#       report, two reports describing different class sets, or an aggregate that
#       does not exceed the unit-only figure.
#       "Found nothing" is never "clean".
#
# USAGE
#   scripts/check-jacoco-coverage.sh
#   JACOCO_AGGREGATE_CSV=/tmp/agg.csv scripts/check-jacoco-coverage.sh
# ---------------------------------------------------------------------------------
set -uo pipefail

# --- the floors ------------------------------------------------------------------
# NO-REGRESSION GUARDRAILS, not targets. One rule: floor(measurement) - 2. Read the
# MEASURED and "WHY FLOORS" sections above before editing any of them.
MIN_INSTRUCTION_PERCENT=86
MIN_BRANCH_PERCENT=69
MIN_LINE_PERCENT=85
MIN_METHOD_PERCENT=85

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${JACOCO_BUILD_DIR:-$REPO_ROOT/core-java/build-local}"
AGG_CSV="${JACOCO_AGGREGATE_CSV:-$BUILD_DIR/reports/jacoco/aggregate/jacocoAggregateReport.csv}"
UNIT_CSV="${JACOCO_UNIT_CSV:-$BUILD_DIR/reports/jacoco/test/jacocoTestReport.csv}"
EXEC_DIR="${JACOCO_EXEC_DIR:-$BUILD_DIR/jacoco}"

EXPECTED_HEADER='GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED'

echo "check-jacoco-coverage  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"

void() { echo "VOID: $*" >&2; exit 2; }
fail_count=0
fail() { echo "FAIL: $*" >&2; fail_count=$((fail_count + 1)); }

while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'; exit 0 ;;
        *) void "unknown argument: $1 (try --help)" ;;
    esac
done

command -v awk >/dev/null 2>&1 || void "awk is not installed — the CSV cannot be summed"

echo "  aggregate : $AGG_CSV"
echo "  unit-only : $UNIT_CSV"
echo "  exec dir  : $EXEC_DIR"
echo "  floors    : INSTRUCTION ${MIN_INSTRUCTION_PERCENT}%  BRANCH ${MIN_BRANCH_PERCENT}%  LINE ${MIN_LINE_PERCENT}%  METHOD ${MIN_METHOD_PERCENT}%  (no-regression guardrails)"

# --- J-1: both suites must have produced execution data ---------------------------
# First, and deliberately so. Everything downstream reads a CSV, and a CSV cannot say
# which .exec files the report task was handed — only that it produced rows. This is
# the assertion a unit-only run cannot satisfy by any amount of re-reporting.
for suite in test integrationTest; do
    ex="$EXEC_DIR/${suite}.exec"
    [ -f "$ex" ] || void "execution data missing for the '${suite}' suite: $ex — the aggregate cannot have been produced from both suites. Run './gradlew :core-java:${suite}' first, or download the artifact in CI. A missing suite is not 0% coverage."
    [ -s "$ex" ] || void "execution data for the '${suite}' suite is EMPTY (0 bytes): $ex — that is a broken or skipped run, not an uncovered codebase."
done
echo "  exec data : test.exec + integrationTest.exec both present and non-empty"

# --- J-2: each CSV must BE a JaCoCo CSV --------------------------------------------
csv_structure_or_void() {
    local label="$1" csv="$2"
    [ -f "$csv" ] || void "${label} CSV not found: $csv — an absent report is not 0% coverage. See INPUT in the header for the commands that produce it."
    [ -s "$csv" ] || void "${label} CSV is EMPTY (0 bytes): $csv — an empty report means the report task produced nothing, not that nothing is covered."

    local head_line rc rows
    head_line=$(head -n 1 "$csv"); rc=$?
    [ "$rc" -eq 0 ] || void "could not read ${label} CSV $csv (head rc=$rc)"
    [ "$head_line" = "$EXPECTED_HEADER" ] \
        || void "unparseable ${label} CSV $csv — its header is '${head_line}', which is not JaCoCo's 13-column CSV header. Refusing to sum columns whose meaning is unknown."

    rows=$(awk 'END { print NR }' "$csv"); rc=$?
    [ "$rc" -eq 0 ] || void "could not count rows in ${label} CSV $csv (awk rc=$rc)"
    [ "$rows" -gt 1 ] 2>/dev/null \
        || void "${label} CSV $csv is HEADER-ONLY (${rows} line) — it describes zero classes. That is a broken report, not a codebase with no coverage."
}

csv_structure_or_void "aggregate" "$AGG_CSV"
csv_structure_or_void "unit-only" "$UNIT_CSV"

# --- J-3: sum the counters ---------------------------------------------------------
# Columns are addressed from the END of each row (NF-9 .. NF) rather than by fixed
# position, so a GROUP or PACKAGE field that ever contained a comma cannot silently
# shift every counter by one — the IFS-tab field-shift trap in this repo's memory,
# reached through a comma. The header check above has already fixed their meaning.
#
# The program prints ONE line of eight integers; anything else is unparseable input
# and exits 3, which the caller turns into a VOID.
sum_counters() {
    awk -F, '
        NR == 1 { next }
        {
            for (i = NF - 9; i <= NF; i++)
                if ($i !~ /^[0-9]+$/) { print "non-numeric value [" $i "] at row " NR " column " i; exit 3 }
            im += $(NF-9); ic += $(NF-8);
            bm += $(NF-7); bc += $(NF-6);
            lm += $(NF-5); lc += $(NF-4);
            mm += $(NF-1); mc += $(NF);
        }
        END { printf "%d %d %d %d %d %d %d %d\n", ic, im, bc, bm, lc, lm, mc, mm }
    ' "$1"
}

agg_sums=$(sum_counters "$AGG_CSV"); rc=$?
[ "$rc" -eq 0 ] || void "could not sum the aggregate CSV $AGG_CSV (awk rc=$rc): ${agg_sums}"
unit_sums=$(sum_counters "$UNIT_CSV"); rc=$?
[ "$rc" -eq 0 ] || void "could not sum the unit-only CSV $UNIT_CSV (awk rc=$rc): ${unit_sums}"

read -r A_IC A_IM A_BC A_BM A_LC A_LM A_MC A_MM <<< "$agg_sums"
read -r U_IC U_IM U_BC U_BM U_LC U_LM U_MC U_MM <<< "$unit_sums"

for v in "$A_IC" "$A_IM" "$A_BC" "$A_BM" "$A_LC" "$A_LM" "$A_MC" "$A_MM"; do
    case "$v" in ''|*[!0-9]*) void "non-numeric counter total '${v}' from $AGG_CSV — refusing to compare a value that is not a number." ;; esac
done
for v in "$U_IC" "$U_IM" "$U_BC" "$U_BM" "$U_LC" "$U_LM" "$U_MC" "$U_MM"; do
    case "$v" in ''|*[!0-9]*) void "non-numeric counter total '${v}' from $UNIT_CSV — refusing to compare a value that is not a number." ;; esac
done

A_IT=$((A_IC + A_IM)); A_BT=$((A_BC + A_BM)); A_LT=$((A_LC + A_LM)); A_MT=$((A_MC + A_MM))
U_IT=$((U_IC + U_IM)); U_BT=$((U_BC + U_BM)); U_LT=$((U_LC + U_LM)); U_MT=$((U_MC + U_MM))

# A zero denominator is a broken measurement. 0/0 is not 0%.
[ "$A_IT" -gt 0 ] || void "the aggregate report counts ZERO instructions — a codebase with no instructions is a broken report, not 0% coverage."
[ "$A_BT" -gt 0 ] || void "the aggregate report counts ZERO branches — broken report, not 0% coverage."
[ "$A_LT" -gt 0 ] || void "the aggregate report counts ZERO lines — broken report, not 0% coverage."
[ "$A_MT" -gt 0 ] || void "the aggregate report counts ZERO methods — broken report, not 0% coverage."
[ "$U_LT" -gt 0 ] || void "the unit-only report counts ZERO lines — it cannot serve as the comparison baseline for J-4."

pct() { awk -v c="$1" -v t="$2" 'BEGIN { printf "%.2f", (t > 0 ? 100 * c / t : 0) }'; }

A_INSTR=$(pct "$A_IC" "$A_IT"); A_BRANCH=$(pct "$A_BC" "$A_BT")
A_LINE=$(pct "$A_LC" "$A_LT");  A_METHOD=$(pct "$A_MC" "$A_MT")
U_INSTR=$(pct "$U_IC" "$U_IT"); U_BRANCH=$(pct "$U_BC" "$U_BT")
U_LINE=$(pct "$U_LC" "$U_LT");  U_METHOD=$(pct "$U_MC" "$U_MT")

# Printed on EVERY run, pass or fail. A number nobody can see in the log is a number
# nobody checks — which is how the edge-go profile went unread for months.
echo ""
echo "  counter       unit-only     AGGREGATE       floor"
printf "  %-12s %8s%%   %9s%%  %8s%%\n" "INSTRUCTION" "$U_INSTR" "$A_INSTR" "$MIN_INSTRUCTION_PERCENT"
printf "  %-12s %8s%%   %9s%%  %8s%%\n" "BRANCH"      "$U_BRANCH" "$A_BRANCH" "$MIN_BRANCH_PERCENT"
printf "  %-12s %8s%%   %9s%%  %8s%%\n" "LINE"        "$U_LINE"   "$A_LINE"   "$MIN_LINE_PERCENT"
printf "  %-12s %8s%%   %9s%%  %8s%%\n" "METHOD"      "$U_METHOD" "$A_METHOD" "$MIN_METHOD_PERCENT"
echo "  lines covered: aggregate ${A_LC}/${A_LT}, unit-only ${U_LC}/${U_LT}"
echo ""

# --- J-4: the aggregate must not be a unit report in disguise ----------------------
# Both reports analyse the same `main` sourceSet, so their DENOMINATORS must agree
# exactly — measured identical on 2026-08-29 (48657/3016/10989/3112 in both). A
# disagreement means the two CSVs came from different builds, so one is stale and
# comparing them would be comparing two different codebases.
if [ "$A_IT" -ne "$U_IT" ] || [ "$A_BT" -ne "$U_BT" ] || [ "$A_LT" -ne "$U_LT" ] || [ "$A_MT" -ne "$U_MT" ]; then
    void "the aggregate and unit-only reports describe DIFFERENT class sets (aggregate totals ${A_IT}/${A_BT}/${A_LT}/${A_MT} vs unit ${U_IT}/${U_BT}/${U_LT}/${U_MT}) — one of the two CSVs is stale. Regenerate both from the same build before trusting either."
fi

# The load-bearing assertion. `integrationTest` loads hundreds of classes the unit
# suite never touches, so a genuine aggregate covers STRICTLY MORE on every counter.
# An aggregate equal to the unit figure is a unit report under an aggregate filename
# — stale, or generated before integrationTest.exec landed — and reporting it as this
# project's coverage is the precise misstatement this gate exists to prevent.
if [ "$A_IC" -le "$U_IC" ] || [ "$A_BC" -le "$U_BC" ] || [ "$A_LC" -le "$U_LC" ] || [ "$A_MC" -le "$U_MC" ]; then
    void "the 'aggregate' report does NOT exceed the unit-only report (aggregate covered ${A_IC}/${A_BC}/${A_LC}/${A_MC} vs unit ${U_IC}/${U_BC}/${U_LC}/${U_MC}) — it was produced from the unit execution data alone, or it is stale. Refusing to publish a unit-only figure as this project's coverage: the two differ by roughly 25 points. Re-run './gradlew :core-java:integrationTest :core-java:jacocoAggregateReport'."
fi
echo "  J-4 ok    : the aggregate exceeds the unit-only report on all four counters"

# --- J-5: compare against the floors -----------------------------------------------
# awk numerics, never shell `[ -ge ]` (integers only) and never string comparison:
# "9.00" sorts above "85.00" lexically, which would pass a catastrophic regression.
below() { awk -v v="$1" -v min="$2" 'BEGIN { exit !(v + 0 < min + 0) }'; }

below "$A_INSTR" "$MIN_INSTRUCTION_PERCENT" && fail "aggregate INSTRUCTION coverage ${A_INSTR}% is BELOW the floor of ${MIN_INSTRUCTION_PERCENT}%"
below "$A_BRANCH" "$MIN_BRANCH_PERCENT"     && fail "aggregate BRANCH coverage ${A_BRANCH}% is BELOW the floor of ${MIN_BRANCH_PERCENT}%"
below "$A_LINE" "$MIN_LINE_PERCENT"         && fail "aggregate LINE coverage ${A_LINE}% is BELOW the floor of ${MIN_LINE_PERCENT}%"
below "$A_METHOD" "$MIN_METHOD_PERCENT"     && fail "aggregate METHOD coverage ${A_METHOD}% is BELOW the floor of ${MIN_METHOD_PERCENT}%"

if [ "$fail_count" -gt 0 ]; then
    echo "" >&2
    echo "  ${fail_count} counter(s) below floor. Coverage is measured over BOTH suites, so a drop" >&2
    echo "  can have come from either — open the aggregate HTML report at" >&2
    echo "    core-java/build-local/reports/jacoco/aggregate/html/index.html" >&2
    echo "  Do NOT lower a floor to go green. See WHY FLOORS AND NOT TARGETS in the header." >&2
    exit 1
fi

echo "PASS: aggregate Java coverage (test + integrationTest) is at or above every floor"
exit 0
