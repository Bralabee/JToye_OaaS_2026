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
#   `--from-nightly` fetches the last SUCCESSFUL nightly's report artifact instead of
#   reading a local one. That is not a shortcut around the freshness question: the
#   downloaded report is subjected to exactly the same digest check as a local one, so
#   a nightly that ran on a different tree VOIDs rather than certifying yours.
#
# HOW STALENESS IS DECIDED — CONTENT, NOT mtime
#
#   This gate used to refuse a report older by MTIME than any spec:
#
#       find frontend/e2e frontend/playwright.config.ts -newer "$REPORT"
#
#   mtime answers "was this file WRITTEN after the report", which is not the question.
#   git rewrites mtime on checkout, pull, merge and stash pop even when the bytes are
#   identical, so the gate went VOID after EVERY merge touching a spec — including the
#   merge of the very change the report was produced from. The documented cost was a
#   standing ~6.5 minute suite re-run to re-earn a gate over byte-identical specs, and
#   a permanent warning in HANDOFF.md telling readers to expect it.
#
#   The honest question is content, and it is answered by comparing
#   `config.metadata.specDigest` — stamped into the report by frontend/playwright.config.ts
#   at run time — against a digest recomputed now by scripts/e2e-spec-digest.sh.
#   Identical content passes no matter how the files got there; any real edit, any
#   rename, and any added or deleted spec VOIDs.
#
#   A report with no digest (produced before this contract existed), a sentinel
#   digest, or a digest that does not match is VOID — never a pass. The gate must not
#   be satisfiable by omitting the field it checks.
#
# EXIT CODES
#   0 = every skip is declared and within budget
#   1 = over budget, an undeclared skip, or a stale ALLOW
#   2 = VOID — no report, unparseable, zero tests, missing jq, a bad config directive,
#       or a report whose spec digest is absent, sentinel, or mismatched.
#       "Found nothing" is never "clean".
#
# USAGE
#   scripts/check-e2e-skip-budget.sh                 # read frontend/e2e-artifacts/report.json
#   scripts/check-e2e-skip-budget.sh --from-nightly  # read the last successful nightly's artifact
#   E2E_REPORT=<file> scripts/check-e2e-skip-budget.sh
# ---------------------------------------------------------------------------------
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONF="${E2E_SKIP_CONF:-$REPO_ROOT/scripts/gates/e2e-skip-budget.conf}"
REPORT="${E2E_REPORT:-$REPO_ROOT/frontend/e2e-artifacts/report.json}"
DIGEST_SCRIPT="$REPO_ROOT/scripts/e2e-spec-digest.sh"
FROM_NIGHTLY=0

echo "check-e2e-skip-budget  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"

void() { echo "VOID: $*" >&2; exit 2; }
fail_count=0
fail() { echo "FAIL: $*" >&2; fail_count=$((fail_count + 1)); }

while [ $# -gt 0 ]; do
    case "$1" in
        --from-nightly) FROM_NIGHTLY=1; shift ;;
        -h|--help) sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'; exit 0 ;;
        *) void "unknown argument: $1 (try --help)" ;;
    esac
done

command -v jq >/dev/null 2>&1 || void "jq is not installed — cannot parse the Playwright report"
[ -f "$CONF" ] || void "config not found: $CONF"
[ -x "$DIGEST_SCRIPT" ] || [ -f "$DIGEST_SCRIPT" ] \
    || void "spec-digest helper not found at $DIGEST_SCRIPT — freshness cannot be established"

# --- optionally pull the authority's own report --------------------------------------
# The nightly is the only job that runs the whole suite against a real stack, so its
# report is the authoritative skip set. Fetching it locally beats re-running 20 minutes
# of suite to answer a question the nightly already answered — but ONLY because the
# digest check below then decides whether that answer applies to THIS tree.
if [ "$FROM_NIGHTLY" -eq 1 ]; then
    command -v gh >/dev/null 2>&1 || void "--from-nightly needs the gh CLI"
    dest="${TMPDIR:-/tmp}/e2e-nightly-report.$$"
    run_id=$(gh run list --workflow e2e-nightly.yml --status success \
                 --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null)
    case "${run_id:-}" in ''|*[!0-9]*) void "could not resolve a successful e2e-nightly run id (got '${run_id:-}')" ;; esac
    mkdir -p "$dest" || void "could not create $dest"
    gh run download "$run_id" --name e2e-nightly-report --dir "$dest" >/dev/null 2>&1 \
        || void "could not download artifact 'e2e-nightly-report' from run $run_id"
    REPORT="$dest/e2e-artifacts/report.json"
    echo "  source    : nightly run $run_id (downloaded to $dest)"
fi

[ -s "$REPORT" ] || void "no Playwright JSON report at $REPORT — run the suite with --reporter=json first"

# --- FRESHNESS: does this report describe the specs on disk RIGHT NOW? ---------------
REPORT_DIGEST=$(jq -r '.config.metadata.specDigest // empty' "$REPORT" 2>/dev/null)
[ -n "$REPORT_DIGEST" ] || void \
    "report carries no config.metadata.specDigest — it predates the freshness contract (or was not produced by frontend/playwright.config.ts). Re-run the suite; a report that cannot be dated cannot certify a skip set."
# Written as an explicit `if` rather than `A && B || void`: in that form a false A
# ALSO runs the void, which happens to be right here and is wrong the moment anyone
# adds a third clause. This repo has already been bitten by a short-circuit chain
# that fired on the success path.
if [ "$REPORT_DIGEST" = "UNAVAILABLE" ] || ! [[ "$REPORT_DIGEST" =~ ^[0-9a-f]{64}$ ]]; then
    void "report's specDigest is '$REPORT_DIGEST', not a digest — the run could not compute one, so its skip set cannot be tied to any tree."
fi

TREE_DIGEST=$(bash "$DIGEST_SCRIPT"); digest_rc=$?
[ "$digest_rc" -eq 0 ] || void "scripts/e2e-spec-digest.sh exited $digest_rc — cannot establish what the tree currently contains"
[[ "$TREE_DIGEST" =~ ^[0-9a-f]{64}$ ]] || void "spec digest helper produced '$TREE_DIGEST', not a digest"

[ "$REPORT_DIGEST" = "$TREE_DIGEST" ] || void \
    "report describes a DIFFERENT spec set than the tree — re-run the suite.
        report : $REPORT_DIGEST
        tree   : $TREE_DIGEST
      This compares CONTENT, so a checkout/pull/merge that rewrote mtimes without
      changing bytes will NOT trip it; a real edit, rename, add or delete will."

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
echo "  freshness : specDigest ${TREE_DIGEST:0:16}… matches the tree (content, not mtime)"
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
