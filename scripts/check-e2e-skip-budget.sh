#!/usr/bin/env bash
# ---------------------------------------------------------------------------------
# check-e2e-skip-budget.sh — assert the Playwright suite's SKIPPED tests are declared.
#
# WHY THIS EXISTS
#
#   The suite reported "114 passed, 14 skipped, 0 failed" and every summary, badge and
#   human reading of it said green. A skip means NOBODY CHECKED THIS. Among those 14 was
#   a real gating assertion on a money-touching path — "the Issue refund button is hidden
#   on a DRAFT order" — which had never executed, because the dev DB held 91 orders and
#   not one in DRAFT. Nothing was red, and nothing ever would have been.
#
#   This is the same shape as the anti-vacuity guard in media-review-320.spec.ts, which
#   is the only reason that spec's fixture decay was ever noticed.
#
# WHY A COUNT ALONE IS NOT ENOUGH
#
#   If one skip is fixed and another appears, the total is unchanged and the regression
#   is invisible. So every skipped test is matched BY TITLE against ALLOW entries in the
#   config. An undeclared skip fails even when the number does not move.
#
# AND WHY STALE ENTRIES ALSO FAIL
#
#   An ALLOW that matches nothing is a claim about coverage that is no longer true. It
#   fails, so exemptions are retired by the gate going red rather than by someone
#   remembering to look — the same contract as check-changelog-contract's C-2 and
#   check-alert-metrics' KNOWN_DATALESS.
#
# WHAT IT ENFORCES
#   S-1  total skipped <= MAX_SKIPS
#   S-2  every skipped test matches some ALLOW
#   S-3  every ALLOW matches at least one skipped test (no stale exemptions)
#   S-4  SELF-TEST of the matcher, in BOTH directions: a known-present title must match
#        and a constructed-absent one must not, so "all declared" cannot be reached by a
#        matcher that silently stopped working.
#
# INPUT
#   A Playwright JSON report. Produce one with:
#     cd frontend && npx playwright test --reporter=json > e2e-artifacts/report.json
#   Path override: E2E_REPORT=<file>
#
#   This gate deliberately does NOT run the suite. The suite needs the full compose
#   stack, which the PER-PR runner does not stand up (#420) — the nightly job
#   .github/workflows/e2e-nightly.yml does, and calls this gate after it. A gate that
#   silently runs nothing is worse than no gate. Absent report == VOID, never pass.
#
# EXIT CODES
#   0 = every skip is declared and within budget
#   1 = over budget, an undeclared skip, or a stale ALLOW
#   2 = VOID — no report, unparseable, zero tests, missing jq, or a bad config directive.
#       "Found nothing" is never "clean".
# ---------------------------------------------------------------------------------
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONF="${E2E_SKIP_CONF:-$REPO_ROOT/scripts/gates/e2e-skip-budget.conf}"
REPORT="${E2E_REPORT:-$REPO_ROOT/frontend/e2e-artifacts/report.json}"

echo "check-e2e-skip-budget  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"

void() { echo "VOID: $*" >&2; exit 2; }
fail_count=0
fail() { echo "FAIL: $*" >&2; fail_count=$((fail_count + 1)); }

command -v jq >/dev/null 2>&1 || void "jq is not installed — cannot parse the Playwright report"
[ -f "$CONF" ] || void "config not found: $CONF"
[ -s "$REPORT" ] || void "no Playwright JSON report at $REPORT — run the suite with --reporter=json first"

# A report older than the specs it claims to describe certifies a skip set that may no
# longer exist. Reading a stale artifact as if it were live is a documented trap in this
# repo (core-java/build vs build-local); refuse rather than repeat it.
NEWEST_SPEC=$(find "$REPO_ROOT/frontend/e2e" "$REPO_ROOT/frontend/playwright.config.ts" \
                   -newer "$REPORT" -print -quit 2>/dev/null)
[ -z "$NEWEST_SPEC" ] \
    || void "report is OLDER than $NEWEST_SPEC — re-run the suite; a stale report certifies a skip set that may no longer exist"

# --- parse config -------------------------------------------------------------------
MAX_SKIPS=""
ALLOWS=()
while IFS= read -r raw; do
    line="${raw%%$'\r'}"
    case "$(printf '%s' "$line" | sed 's/^[[:space:]]*//')" in
        ''|'#'*) continue ;;
    esac
    directive=$(printf '%s' "$line" | awk '{print $1}')
    value=$(printf '%s' "$line" | cut -d' ' -f2-)
    case "$directive" in
        MAX_SKIPS) MAX_SKIPS="$value" ;;
        ALLOW)     ALLOWS+=("$value") ;;
        *)         void "unknown directive '$directive' in $CONF — refusing to guess" ;;
    esac
done < "$CONF"

[ -n "$MAX_SKIPS" ] || void "$CONF declares no MAX_SKIPS"
case "$MAX_SKIPS" in ''|*[!0-9]*) void "MAX_SKIPS '$MAX_SKIPS' is not a number" ;; esac
[ "${#ALLOWS[@]}" -gt 0 ] || void "$CONF declares no ALLOW entries — a budget with no declarations cannot enforce S-2"

# --- read the report ----------------------------------------------------------------
# Playwright's JSON nests suites arbitrarily deep; recurse rather than assume a depth.
TOTAL=$(jq '[.. | objects | select(has("results")) | .results] | flatten | length' "$REPORT" 2>/dev/null)
case "${TOTAL:-}" in ''|*[!0-9]*) void "could not count tests in $REPORT — is it a Playwright JSON report?" ;; esac
[ "$TOTAL" -gt 0 ] || void "report contains ZERO test results — a run that executed nothing is not a pass"

# A test is skipped when its status is "skipped". Each entry is "<spec file> › <title>"
# so an ALLOW can target a whole spec file (stable across renames of a single test) or
# one specific test — whichever is the honest unit for that exemption.
mapfile -t SKIPPED < <(jq -r '
  [.. | objects | select(has("specs")) | .specs[]?
     | select(.tests[]?.results[]?.status == "skipped")
     | ((.file // "?") + " › " + .title)]
  | unique | .[]' "$REPORT" 2>/dev/null)

SKIP_COUNT=$(jq '[.. | objects | select(has("results")) | .results[]? | select(.status == "skipped")] | length' "$REPORT" 2>/dev/null)
case "${SKIP_COUNT:-}" in ''|*[!0-9]*) void "could not count skipped results in $REPORT" ;; esac

echo "  report    : $REPORT"
echo "  config    : $CONF"
echo "  tests     : $TOTAL total, $SKIP_COUNT skipped (budget $MAX_SKIPS)"

matches_any_allow() {
    local title="$1" a
    for a in "${ALLOWS[@]}"; do
        case "$title" in *"$a"*) return 0 ;; esac
    done
    return 1
}

# --- S-4  self-test of the matcher, BOTH directions ---------------------------------
# Run before the real checks: a matcher that silently stopped working would make S-2
# pass over everything, which is the classic vacuous assertion.
if [ "${#SKIPPED[@]}" -gt 0 ]; then
    probe="${ALLOWS[0]}"
    matches_any_allow "prefix ${probe} suffix" \
        || void "S-4 self-test: the matcher failed to match a title containing its own ALLOW"
fi
matches_any_allow "zzz-no-such-test-title-should-ever-match-this-zzz" \
    && void "S-4 self-test: the matcher fired on a constructed-absent title — it would declare anything"

# --- S-1  budget ---------------------------------------------------------------------
[ "$SKIP_COUNT" -le "$MAX_SKIPS" ] \
    || fail "S-1 $SKIP_COUNT skipped test(s) exceeds the declared budget of $MAX_SKIPS"

# --- S-2  every skip is declared -----------------------------------------------------
for title in "${SKIPPED[@]}"; do
    matches_any_allow "$title" \
        || fail "S-2 undeclared skip: \"$title\" — fix it, or add an ALLOW to $CONF with a justification"
done

# --- S-3  no stale ALLOW -------------------------------------------------------------
for a in "${ALLOWS[@]}"; do
    hit=0
    for title in "${SKIPPED[@]}"; do
        case "$title" in *"$a"*) hit=1; break ;; esac
    done
    [ "$hit" -eq 1 ] || fail "S-3 stale ALLOW '$a' matches no skipped test — the exemption outlived its cause; delete it and lower MAX_SKIPS"
done

echo "  declared  : ${#ALLOWS[@]} ALLOW entr(ies), ${#SKIPPED[@]} distinct skipped title(s)"
echo "  S-4 self  : matcher fires on a known title and declines a constructed-absent one"

if [ "$fail_count" -eq 0 ]; then
    echo "PASS: all $SKIP_COUNT skip(s) are declared and within the budget of $MAX_SKIPS."
    echo "      NOTE: a declared skip is still UNVERIFIED SURFACE, not a pass."
    exit 0
fi
echo "FAILED: $fail_count skip-budget violation(s) (see above)." >&2
exit 1
