#!/usr/bin/env bash
#
# Gate: every Spring Boot configuration key and every auto-configuration exclude in core-java is
# one that Boot 4 actually honours (Phase 38, plan 38-11, BOOT4-11).
#
# WHY THIS EXISTS
#
#   Boot does not fail on a key it no longer binds. The application starts, and the setting
#   silently reverts to Boot's default. The move from Boot 3.5 to Boot 4.1 left EIGHTEEN such keys
#   in core-java's application*.yml, and no test caught one of them:
#
#     management.zipkin.tracing.endpoint   -> the Zipkin endpoint env var was ignored everywhere
#     server.error.include-*               -> error detail fell back to the defaults (base, staging, prod)
#     logging.file.max-size|max-history|total-size-cap
#                                          -> prod's 30-day / 1GB log retention fell back to 7 days, uncapped
#     management.metrics.export.prometheus.enabled (staging; dead since Boot 3.0)
#
#   Every one of those names is still IN Boot 4.1's configuration metadata, at deprecation level
#   "error" with a replacement. An "is this key known?" check alone passes all eighteen, so this
#   gate fails on deprecated keys too, at any level.
#
#   The same silence applies to spring.autoconfigure.exclude. Boot only rejects an exclude whose
#   class IS on the classpath, so an exclude naming a Boot-3 class that no longer exists is a
#   no-op and the auto-configuration runs after all. Thirteen such names were found: one Rabbit
#   and two Redis excludes in the two application-test.yml files, and ten string literals in five
#   test classes.
#
# HOW
#
#   The engine is a JUnit class, core-java's uk.jtoye.core.boot4.ConfigKeyContractTest. It reads
#   Boot's configuration metadata from the PRODUCTION runtime classpath (never the test classpath,
#   which carries test auto-configure modules the shipped jar does not), judges every spring.*,
#   management.*, server.* and logging.* key in every application*.yml under core-java's main and
#   test resources, checks every exclude against the AutoConfiguration.imports files, and writes
#   core-java/build-local/boot4/config-key-report.tsv (file, key, verdict, detail).
#
#   This script runs that class FRESH (stale XML and report deleted first, then cleanTest and
#   --rerun, so an UP-TO-DATE Gradle run that executes nothing cannot pass) and judges its JUnit
#   XML and its report. It lives in the JDK job in CI because it needs Gradle and a JDK, nothing
#   else: no database, no broker, no network service.
#
# Exit codes:
#   0  clean: the test ran (tests > 0), passed, and the report holds only OK rows
#   1  at least one bad key or exclude, each NAMED with its file, verdict and replacement
#   2  VOID: no XML, tests="0", a Gradle failure with no XML, an unparseable XML, a report with a
#      VOID row (zero yml files, zero keys, zero metadata or zero exclude literals scanned), or a
#      red test whose report names no bad row (the instrument itself is broken)
#
# 2 is load-bearing. "The check found nothing to judge" must never read as "nothing is wrong".
#
# Overrides (for the VOID arms; defaults are the real paths):
#   RESULTS_DIR             JUnit XML directory. Default core-java/build-local/test-results/test
#                           (build-local is the LIVE build dir; core-java/build/ is stale).
#   CONFIG_KEY_REPORT       the report TSV. Default core-java/build-local/boot4/config-key-report.tsv
#   JTOYE_CONFIG_KEY_DIRS   read by the TEST, not here: replaces the yml directories it scans
#   CHECK_BOOT_CONFIG_KEYS_SKIP_GRADLE=1
#                           TESTING ONLY: do not run Gradle; judge whatever RESULTS_DIR and
#                           CONFIG_KEY_REPORT already hold. Used to prove a missing XML is VOID.
#
# Shell traps avoided here, both recorded in this repo: `cmd | grep -q X` under pipefail inverts on
# a match (SIGPIPE -> 141), so the XML and report are read with awk and here-strings; and an exit
# code is captured on the same line as its command.

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

TEST_CLASS="uk.jtoye.core.boot4.ConfigKeyContractTest"
RESULTS_DIR="${RESULTS_DIR:-$REPO_ROOT/core-java/build-local/test-results/test}"
REPORT="${CONFIG_KEY_REPORT:-$REPO_ROOT/core-java/build-local/boot4/config-key-report.tsv}"
XML="$RESULTS_DIR/TEST-$TEST_CLASS.xml"
SKIP_GRADLE="${CHECK_BOOT_CONFIG_KEYS_SKIP_GRADLE:-0}"

fail() { echo "FAIL: $*" >&2; exit 1; }
void() { echo "VOID: $*" >&2; exit 2; }

echo "Boot config-key and autoconfigure-exclude gate (38-11)"
echo "  results : ${RESULTS_DIR#"$REPO_ROOT"/}"
echo "  report  : ${REPORT#"$REPO_ROOT"/}"

GRADLE_RC="not run"
if [ "$SKIP_GRADLE" = "1" ]; then
    echo "  gradle  : SKIPPED (CHECK_BOOT_CONFIG_KEYS_SKIP_GRADLE=1, testing only)"
else
    [ -x "$REPO_ROOT/gradlew" ] || void "gradlew not found or not executable at $REPO_ROOT/gradlew"
    # Delete what a previous run left, so only THIS run's output can be judged.
    rm -f "$XML" "$REPORT"
    GRADLE_LOG="$(mktemp)"
    trap 'rm -f "$GRADLE_LOG"' EXIT
    (cd "$REPO_ROOT" && ./gradlew :core-java:cleanTest :core-java:test --tests "$TEST_CLASS" --rerun --no-daemon) >"$GRADLE_LOG" 2>&1; GRADLE_RC=$?
    echo "  gradle  : rc=$GRADLE_RC"
fi

if [ ! -f "$XML" ]; then
    if [ "$SKIP_GRADLE" != "1" ]; then
        echo "--- last 30 lines of the Gradle output ---" >&2
        tail -n 30 "$GRADLE_LOG" >&2
    fi
    void "no JUnit XML for $TEST_CLASS at ${XML#"$REPO_ROOT"/} (gradle rc=$GRADLE_RC) — nothing ran, so nothing is proven"
fi

SUITE="$(awk '/<testsuite /{print; exit}' "$XML")"
[ -n "$SUITE" ] || void "no <testsuite> element in ${XML#"$REPO_ROOT"/}"
attr() { awk -v a="$1" '{ if (match($0, " " a "=\"[0-9]+\"")) { s = substr($0, RSTART, RLENGTH); gsub(/[^0-9]/, "", s); print s } }' <<< "$SUITE"; }
TESTS="$(attr tests)"
FAILURES="$(attr failures)"
ERRORS="$(attr errors)"
[ -n "$TESTS" ] && [ -n "$FAILURES" ] && [ -n "$ERRORS" ] \
    || void "unparseable <testsuite> counts in ${XML#"$REPO_ROOT"/}: $SUITE"
echo "  junit   : tests=$TESTS failures=$FAILURES errors=$ERRORS"
[ "$TESTS" -gt 0 ] || void "tests=\"0\" — the class ran nothing"

if [ "$FAILURES" -eq 0 ] && [ "$ERRORS" -eq 0 ]; then
    if [ "$SKIP_GRADLE" != "1" ] && [ "$GRADLE_RC" != "0" ]; then
        void "the XML is green but Gradle exited $GRADLE_RC — the build failed somewhere this gate cannot see"
    fi
    [ -f "$REPORT" ] || void "the test passed but wrote no report at ${REPORT#"$REPO_ROOT"/}"
    ROWS="$(awk -F'\t' 'NR>1' "$REPORT")"
    [ -n "$ROWS" ] || void "the report has no rows"
    NOT_OK="$(awk -F'\t' 'NR>1 && $3!="OK"' <<< "$(cat "$REPORT")")"
    [ -z "$NOT_OK" ] || void "the test passed but its report holds non-OK rows — the test and the report disagree:"$'\n'"$NOT_OK"
    KEYS="$(awk -F'\t' 'NR>1 && $1 ~ /\.ya?ml$/ && $2 ~ /^(spring|management|server|logging)\./' "$REPORT" | wc -l)"
    FILES="$(awk -F'\t' 'NR>1 && $1 ~ /\.ya?ml$/ {print $1}' "$REPORT" | sort -u | wc -l)"
    EXCLUDES="$(awk -F'\t' 'NR>1 && $2 ~ /^org\./' "$REPORT" | wc -l)"
    echo "PASS: $KEYS key(s) in $FILES yml file(s) and $EXCLUDES autoconfigure exclude(s)/literal(s) are all known to Boot 4."
    exit 0
fi

# Red. Decide between "a bad key was named" (1) and "the instrument is broken" (2).
[ -f "$REPORT" ] || void "$TEST_CLASS failed ($FAILURES failure(s), $ERRORS error(s)) and wrote no report — it broke before judging anything"
VOID_ROWS="$(awk -F'\t' 'NR>1 && $3=="VOID" {print "  " $4}' "$REPORT")"
[ -z "$VOID_ROWS" ] || void "the instrument saw nothing to judge:"$'\n'"$VOID_ROWS"
BAD="$(awk -F'\t' 'NR>1 && $3!="OK" {printf "  %s: %s -> %s (%s)\n", $1, $2, $3, $4}' "$REPORT")"
if [ -z "$BAD" ]; then
    void "$TEST_CLASS failed ($FAILURES failure(s), $ERRORS error(s)) but its report names no bad key or exclude — a control test failed; read ${XML#"$REPO_ROOT"/}"
fi
COUNT="$(awk 'END{print NR}' <<< "$BAD")"
echo "$BAD" >&2
fail "$COUNT configuration key(s) or autoconfigure exclude(s) that Boot 4 silently ignores. Rename each to its replacement (or the Boot-4 auto-configuration class); do not delete a key that still carries a value."
