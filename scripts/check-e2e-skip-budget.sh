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
#   S-5  every ALLOW is immediately preceded by a contiguous block of comment lines
#        containing both a justification and a non-empty `REMOVE WHEN:` clause.
#
# INPUT
#   One or more Playwright JSON reports. Produce one with:
#     cd frontend && npx playwright test --reporter=json > e2e-artifacts/report.json
#   Path override: E2E_REPORT=<file>, or repeatable `--report <file>` (gates the union).
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
#   1 = over budget, an undeclared skip, a stale ALLOW, or an ALLOW lacking S-5 comments
#   2 = VOID — no report, unparseable, zero tests, missing jq, a bad config directive,
#       or a report whose spec digest is absent, sentinel, or mismatched.
#       "Found nothing" is never "clean".
#
# USAGE
#   scripts/check-e2e-skip-budget.sh                          # read frontend/e2e-artifacts/report.json
#   scripts/check-e2e-skip-budget.sh --report A --report B    # gate the union of several reports
#   scripts/check-e2e-skip-budget.sh --lint-conf [FILE]       # run S-5 alone with no report
#   scripts/check-e2e-skip-budget.sh --selftest               # run the 14-arm selftest
#   scripts/check-e2e-skip-budget.sh --from-nightly           # read the last successful nightly's artifact
#   E2E_REPORT=<file> scripts/check-e2e-skip-budget.sh
# ---------------------------------------------------------------------------------
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONF="${E2E_SKIP_CONF:-$REPO_ROOT/scripts/gates/e2e-skip-budget.conf}"
REPORT="${E2E_REPORT:-$REPO_ROOT/frontend/e2e-artifacts/report.json}"
DIGEST_SCRIPT="$REPO_ROOT/scripts/e2e-spec-digest.sh"
FROM_NIGHTLY=0
LINT_CONF=0
REPORTS=()
_SELFTEST_TMP=""

echo "check-e2e-skip-budget  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"

void() { echo "VOID: $*" >&2; exit 2; }
fail_count=0
fail() { echo "FAIL: $*" >&2; fail_count=$((fail_count + 1)); }

run_selftest() {
    local self="${BASH_SOURCE[0]}"
    local fmt="${E2E_SKIP_SELFTEST_FORMAT:-plain}"
    _SELFTEST_TMP=$(mktemp -d "${TMPDIR:-/tmp}/e2e-skip-selftest.XXXXXX") || exit 2
    local tmp="$_SELFTEST_TMP"
    trap '[ -n "${_SELFTEST_TMP:-}" ] && rm -rf "$_SELFTEST_TMP"' EXIT

    local tree_digest
    tree_digest=$(bash "$DIGEST_SCRIPT") || { echo "VOID: selftest could not compute tree digest" >&2; exit 2; }

    local pass=0 fail_arms=0 idx=0
    local -a tap_lines=()

    record_arm() {
        local ok="$1" name="$2" why="${3:-}"
        idx=$((idx + 1))
        if [ "$ok" -eq 1 ]; then
            pass=$((pass + 1))
            if [ "$fmt" = "tap" ]; then
                tap_lines+=("ok $idx - $name")
            else
                echo "PASS $name"
            fi
        else
            fail_arms=$((fail_arms + 1))
            if [ "$fmt" = "tap" ]; then
                tap_lines+=("not ok $idx - $name # $why")
            else
                echo "FAIL $name $why"
            fi
        fi
    }

    # Helper to write a minimal Playwright JSON report with custom specs JSON and digest.
    write_report() {
        local path="$1" digest="$2" specs_json="$3"
        jq -n --arg d "$digest" --argjson s "$specs_json" \
            '{config:{metadata:{specDigest:$d}},suites:[{specs:$s}],stats:{startTime:"2026-10-31T00:00:00.000Z"}}' > "$path"
    }

    local one_skip_specs='[{"file":"fixture.spec.ts","title":"skipped test","tests":[{"results":[{"status":"skipped"}]}]}]'
    local two_skip_same_specs='[{"file":"fixture.spec.ts","title":"skipped test","tests":[{"results":[{"status":"skipped"},{"status":"skipped"}]}]}]'
    local two_skip_diff_specs='[{"file":"fixture.spec.ts","title":"skipped test","tests":[{"results":[{"status":"skipped"}]}]},{"file":"other.spec.ts","title":"undeclared","tests":[{"results":[{"status":"skipped"}]}]}]'
    local zero_skip_specs='[{"file":"passed.spec.ts","title":"passed test","tests":[{"results":[{"status":"passed"}]}]}]'

    local rpt_one="$tmp/report-one.json"
    local rpt_two_same="$tmp/report-two-same.json"
    local rpt_two_diff="$tmp/report-two-diff.json"
    local rpt_zero="$tmp/report-zero.json"
    local rpt_bad_digest="$tmp/report-bad-digest.json"
    local bad_digest="0000000000000000000000000000000000000000000000000000000000000000"
    if [ "$tree_digest" = "$bad_digest" ]; then
        bad_digest="1111111111111111111111111111111111111111111111111111111111111111"
    fi

    write_report "$rpt_one" "$tree_digest" "$one_skip_specs"
    write_report "$rpt_two_same" "$tree_digest" "$two_skip_same_specs"
    write_report "$rpt_two_diff" "$tree_digest" "$two_skip_diff_specs"
    write_report "$rpt_zero" "$tree_digest" "$zero_skip_specs"
    write_report "$rpt_bad_digest" "$bad_digest" "$one_skip_specs"

    local out rc

    # 1. bare-allow: matches a skipping test, no comment above ALLOW -> fails ONLY S-5 (no S-2, no S-3)
    cat > "$tmp/bare.conf" <<'EOF'
MAX_SKIPS 2
ALLOW fixture.spec.ts
EOF
    out=$(E2E_SKIP_CONF="$tmp/bare.conf" E2E_REPORT="$rpt_one" bash "$self" 2>&1); rc=$?
    if [ "$rc" -eq 1 ] && command grep -qE "S-5.*ALLOW.*fixture\.spec\.ts" <<<"$out" \
        && ! command grep -q "S-2" <<<"$out" && ! command grep -q "S-3" <<<"$out"; then
        record_arm 1 "bare-allow"
    else
        record_arm 0 "bare-allow" "rc=$rc out=$(printf '%s' "$out" | tr '\n' ' ')"
    fi

    # 2. no-remove-when: justification comment only, no REMOVE WHEN:
    cat > "$tmp/no-rw.conf" <<'EOF'
MAX_SKIPS 2
# Needs a two-replica broker that local dev does not start.
ALLOW fixture.spec.ts
EOF
    out=$(E2E_SKIP_CONF="$tmp/no-rw.conf" E2E_REPORT="$rpt_one" bash "$self" 2>&1); rc=$?
    if [ "$rc" -eq 1 ] && command grep -qE "S-5.*ALLOW.*fixture\.spec\.ts" <<<"$out"; then
        record_arm 1 "no-remove-when"
    else
        record_arm 0 "no-remove-when" "rc=$rc"
    fi

    # 3. empty-remove-when: REMOVE WHEN: followed only by whitespace
    cat > "$tmp/empty-rw.conf" <<'EOF'
MAX_SKIPS 2
# Needs a two-replica broker that local dev does not start.
# REMOVE WHEN:   
ALLOW fixture.spec.ts
EOF
    out=$(E2E_SKIP_CONF="$tmp/empty-rw.conf" E2E_REPORT="$rpt_one" bash "$self" 2>&1); rc=$?
    if [ "$rc" -eq 1 ] && command grep -qE "S-5.*ALLOW.*fixture\.spec\.ts" <<<"$out"; then
        record_arm 1 "empty-remove-when"
    else
        record_arm 0 "empty-remove-when" "rc=$rc"
    fi

    # 4. remove-when-only: REMOVE WHEN: present, but no justification text outside it
    cat > "$tmp/rw-only.conf" <<'EOF'
MAX_SKIPS 2
# REMOVE WHEN: a scaled-stack job exists
ALLOW fixture.spec.ts
EOF
    out=$(E2E_SKIP_CONF="$tmp/rw-only.conf" E2E_REPORT="$rpt_one" bash "$self" 2>&1); rc=$?
    if [ "$rc" -eq 1 ] && command grep -qE "S-5.*ALLOW.*fixture\.spec\.ts" <<<"$out"; then
        record_arm 1 "remove-when-only"
    else
        record_arm 0 "remove-when-only" "rc=$rc"
    fi

    # 5. blank-line-gap: complete comment block separated from ALLOW by a blank line
    cat > "$tmp/gap.conf" <<'EOF'
MAX_SKIPS 2
# Needs a two-replica broker that local dev does not start.
# REMOVE WHEN: a scaled-stack job exists.

ALLOW fixture.spec.ts
EOF
    out=$(E2E_SKIP_CONF="$tmp/gap.conf" E2E_REPORT="$rpt_one" bash "$self" 2>&1); rc=$?
    if [ "$rc" -eq 1 ] && command grep -qE "S-5.*ALLOW.*fixture\.spec\.ts" <<<"$out"; then
        record_arm 1 "blank-line-gap"
    else
        record_arm 0 "blank-line-gap" "rc=$rc"
    fi

    # 6. full-block: valid contiguous comment block with justification + REMOVE WHEN:
    cat > "$tmp/full.conf" <<'EOF'
MAX_SKIPS 2
# Needs a two-replica broker that local dev does not start.
# REMOVE WHEN: a scaled-stack job exists.
ALLOW fixture.spec.ts
EOF
    out=$(E2E_SKIP_CONF="$tmp/full.conf" E2E_REPORT="$rpt_one" bash "$self" 2>&1); rc=$?
    if [ "$rc" -eq 0 ]; then
        record_arm 1 "full-block"
    else
        record_arm 0 "full-block" "rc=$rc out=$(printf '%s' "$out" | tr '\n' ' ')"
    fi

    # 7. undeclared-skip: S-2 fires on an un-ALLOWed skipped test
    out=$(E2E_SKIP_CONF="$tmp/full.conf" E2E_REPORT="$rpt_two_diff" bash "$self" 2>&1); rc=$?
    if [ "$rc" -eq 1 ] && command grep -q "S-2" <<<"$out"; then
        record_arm 1 "undeclared-skip"
    else
        record_arm 0 "undeclared-skip" "rc=$rc"
    fi

    # 8. stale-allow: S-3 fires when an ALLOW matches no skipped test
    cat > "$tmp/stale.conf" <<'EOF'
MAX_SKIPS 2
# Needs a two-replica broker that local dev does not start.
# REMOVE WHEN: a scaled-stack job exists.
ALLOW fixture.spec.ts
# Old skip that no longer occurs.
# REMOVE WHEN: retired.
ALLOW stale.spec.ts
EOF
    out=$(E2E_SKIP_CONF="$tmp/stale.conf" E2E_REPORT="$rpt_one" bash "$self" 2>&1); rc=$?
    if [ "$rc" -eq 1 ] && command grep -q "S-3" <<<"$out"; then
        record_arm 1 "stale-allow"
    else
        record_arm 0 "stale-allow" "rc=$rc"
    fi

    # 9. over-budget: S-1 fires when total skipped > MAX_SKIPS
    cat > "$tmp/over.conf" <<'EOF'
MAX_SKIPS 1
# Needs a two-replica broker that local dev does not start.
# REMOVE WHEN: a scaled-stack job exists.
ALLOW fixture.spec.ts
EOF
    out=$(E2E_SKIP_CONF="$tmp/over.conf" E2E_REPORT="$rpt_two_same" bash "$self" 2>&1); rc=$?
    if [ "$rc" -eq 1 ] && command grep -q "S-1" <<<"$out"; then
        record_arm 1 "over-budget"
    else
        record_arm 0 "over-budget" "rc=$rc"
    fi

    # 10. digest-mismatch: VOID (rc 2) when report specDigest != tree digest
    out=$(E2E_SKIP_CONF="$tmp/full.conf" E2E_REPORT="$rpt_bad_digest" bash "$self" 2>&1); rc=$?
    if [ "$rc" -eq 2 ] && command grep -q "DIFFERENT spec set" <<<"$out"; then
        record_arm 1 "digest-mismatch"
    else
        record_arm 0 "digest-mismatch" "rc=$rc"
    fi

    # 11. lint-real-conf: --lint-conf on the real conf exits 0 with no report
    out=$(bash "$self" --lint-conf 2>&1); rc=$?
    if [ "$rc" -eq 0 ] && command grep -q "PASS: lint" <<<"$out"; then
        record_arm 1 "lint-real-conf"
    else
        record_arm 0 "lint-real-conf" "rc=$rc"
    fi

    # 12. lint-bare: --lint-conf on bare.conf exits 1 naming S-5
    out=$(bash "$self" --lint-conf "$tmp/bare.conf" 2>&1); rc=$?
    if [ "$rc" -eq 1 ] && command grep -q "S-5" <<<"$out"; then
        record_arm 1 "lint-bare"
    else
        record_arm 0 "lint-bare" "rc=$rc"
    fi

    # 13. union-ok: --report A --report B unions skipped titles and totals
    out=$(E2E_SKIP_CONF="$tmp/full.conf" bash "$self" --report "$rpt_one" --report "$rpt_zero" 2>&1); rc=$?
    if [ "$rc" -eq 0 ]; then
        record_arm 1 "union-ok"
    else
        record_arm 0 "union-ok" "rc=$rc"
    fi

    # 14. union-digest-mismatch: second report carries a mismatched digest -> VOID (rc 2)
    out=$(E2E_SKIP_CONF="$tmp/full.conf" bash "$self" --report "$rpt_one" --report "$rpt_bad_digest" 2>&1); rc=$?
    if [ "$rc" -eq 2 ] && command grep -q "DIFFERENT spec set" <<<"$out"; then
        record_arm 1 "union-digest-mismatch"
    else
        record_arm 0 "union-digest-mismatch" "rc=$rc"
    fi

    if [ "$fmt" = "tap" ]; then
        echo "TAP version 13"
        echo "1..$idx"
        printf '%s\n' "${tap_lines[@]}"
    fi
    [ "$fail_arms" -eq 0 ]
}

while [ $# -gt 0 ]; do
    case "$1" in
        --selftest) run_selftest; exit $? ;;
        --lint-conf)
            LINT_CONF=1
            if [ $# -ge 2 ] && [[ "$2" != -* ]]; then
                CONF="$2"
                shift 2
            else
                shift
            fi
            ;;
        --report)
            [ $# -ge 2 ] && [ -n "${2:-}" ] || void "--report requires a file argument"
            REPORTS+=("$2")
            shift 2
            ;;
        --from-nightly) FROM_NIGHTLY=1; shift ;;
        -h|--help) sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'; exit 0 ;;
        *) void "unknown argument: $1 (try --help)" ;;
    esac
done

[ -f "$CONF" ] || void "config not found: $CONF"

# --- parse config (and enforce S-5 on every ALLOW block) ----------------------------
MAX_SKIPS=""
ALLOWS=()
COMMENT_BLOCK=()

check_s5_block() {
    local allow_val="$1"
    local has_remove_when=0
    local has_justification=0
    local c stripped
    for c in "${COMMENT_BLOCK[@]}"; do
        if [[ "$c" =~ REMOVE\ WHEN:[[:space:]]*[^[:space:]] ]]; then
            has_remove_when=1
        fi
        stripped=$(printf '%s' "$c" | sed 's/REMOVE WHEN:.*//')
        if [[ "$stripped" =~ [^[:space:]] ]]; then
            has_justification=1
        fi
    done
    if [ "$has_remove_when" -ne 1 ] || [ "$has_justification" -ne 1 ]; then
        fail "S-5 ALLOW '$allow_val' lacks a justification and/or a REMOVE WHEN line directly above it"
    fi
}

while IFS= read -r raw; do
    line="${raw%%$'\r'}"
    trimmed=$(printf '%s' "$line" | sed 's/^[[:space:]]*//')
    case "$trimmed" in
        '')
            COMMENT_BLOCK=()
            continue
            ;;
        '#'*)
            COMMENT_BLOCK+=("${trimmed#\#}")
            continue
            ;;
    esac
    directive=$(printf '%s' "$trimmed" | awk '{print $1}')
    value=$(printf '%s' "$trimmed" | cut -d' ' -f2-)
    case "$directive" in
        MAX_SKIPS)
            MAX_SKIPS="$value"
            COMMENT_BLOCK=()
            ;;
        ALLOW)
            ALLOWS+=("$value")
            check_s5_block "$value"
            COMMENT_BLOCK=()
            ;;
        *)
            void "unknown directive '$directive' in $CONF — refusing to guess"
            ;;
    esac
done < "$CONF"

[ -n "$MAX_SKIPS" ] || void "$CONF declares no MAX_SKIPS"
case "$MAX_SKIPS" in ''|*[!0-9]*) void "MAX_SKIPS '$MAX_SKIPS' is not a number" ;; esac
[ "${#ALLOWS[@]}" -gt 0 ] || void "$CONF declares no ALLOW entries — a budget with no declarations cannot enforce S-2"

if [ "$LINT_CONF" -eq 1 ]; then
    if [ "$fail_count" -eq 0 ]; then
        echo "PASS: lint ${#ALLOWS[@]} ALLOW block(s)"
        exit 0
    fi
    echo "FAILED: $fail_count skip-budget violation(s) (see above)." >&2
    exit 1
fi

command -v jq >/dev/null 2>&1 || void "jq is not installed — cannot parse the Playwright report"
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

if [ "${#REPORTS[@]}" -eq 0 ]; then
    REPORTS=("$REPORT")
fi

TREE_DIGEST=$(bash "$DIGEST_SCRIPT"); digest_rc=$?
[ "$digest_rc" -eq 0 ] || void "scripts/e2e-spec-digest.sh exited $digest_rc — cannot establish what the tree currently contains"
[[ "$TREE_DIGEST" =~ ^[0-9a-f]{64}$ ]] || void "spec digest helper produced '$TREE_DIGEST', not a digest"

# --- FRESHNESS & UNION: do all reports describe the specs on disk RIGHT NOW? ---------
TOTAL=0
SKIP_COUNT=0
RAW_SKIPPED=()

for rpt in "${REPORTS[@]}"; do
    [ -s "$rpt" ] || void "no Playwright JSON report at $rpt — run the suite with --reporter=json first"

    REPORT_DIGEST=$(jq -r '.config.metadata.specDigest // empty' "$rpt" 2>/dev/null)
    [ -n "$REPORT_DIGEST" ] || void \
        "report ($rpt) carries no config.metadata.specDigest — it predates the freshness contract (or was not produced by frontend/playwright.config.ts). Re-run the suite; a report that cannot be dated cannot certify a skip set."
    # Written as an explicit `if` rather than `A && B || void`: in that form a false A
    # ALSO runs the void, which happens to be right here and is wrong the moment anyone
    # adds a third clause. This repo has already been bitten by a short-circuit chain
    # that fired on the success path.
    if [ "$REPORT_DIGEST" = "UNAVAILABLE" ] || ! [[ "$REPORT_DIGEST" =~ ^[0-9a-f]{64}$ ]]; then
        void "report's specDigest ($rpt) is '$REPORT_DIGEST', not a digest — the run could not compute one, so its skip set cannot be tied to any tree."
    fi

    [ "$REPORT_DIGEST" = "$TREE_DIGEST" ] || void \
        "report ($rpt) describes a DIFFERENT spec set than the tree — re-run the suite.
        report : $REPORT_DIGEST
        tree   : $TREE_DIGEST
      This compares CONTENT, so a checkout/pull/merge that rewrote mtimes without
      changing bytes will NOT trip it; a real edit, rename, add or delete will."

    rpt_total=$(jq '[.. | objects | select(has("results")) | .results] | flatten | length' "$rpt" 2>/dev/null)
    case "${rpt_total:-}" in ''|*[!0-9]*) void "could not count tests in $rpt — is it a Playwright JSON report?" ;; esac
    [ "$rpt_total" -gt 0 ] || void "report ($rpt) contains ZERO test results — a run that executed nothing is not a pass"
    TOTAL=$((TOTAL + rpt_total))

    rpt_skips=$(jq '[.. | objects | select(has("results")) | .results[]? | select(.status == "skipped")] | length' "$rpt" 2>/dev/null)
    case "${rpt_skips:-}" in ''|*[!0-9]*) void "could not count skipped results in $rpt" ;; esac
    SKIP_COUNT=$((SKIP_COUNT + rpt_skips))

    mapfile -t rpt_skipped_titles < <(jq -r '
      [.. | objects | select(has("specs")) | .specs[]?
         | select(.tests[]?.results[]?.status == "skipped")
         | ((.file // "?") + " › " + .title)]
      | unique | .[]' "$rpt" 2>/dev/null)
    for t in "${rpt_skipped_titles[@]}"; do
        [ -n "$t" ] && RAW_SKIPPED+=("$t")
    done
done

if [ "${#RAW_SKIPPED[@]}" -gt 0 ]; then
    mapfile -t SKIPPED < <(printf '%s\n' "${RAW_SKIPPED[@]}" | LC_ALL=C sort -u)
else
    SKIPPED=()
fi

REPORT="${REPORTS[0]}"
echo "  report    : ${REPORTS[*]}"
echo "  config    : $CONF"
echo "  freshness : specDigest ${TREE_DIGEST:0:16}… matches the tree (content, not mtime)"
echo "  tests     : $TOTAL total, $SKIP_COUNT skipped (budget $MAX_SKIPS)"

# --- ADVISORY: the skip SET is current; the suite RESULT may not be ------------------
#
# Accounting for a good this change displaced. The old mtime check VOIDed after every
# merge, which was wrong about staleness — but the re-run it provoked is how the #593
# regression was caught, and HANDOFF §0.-13 says so explicitly: "a gate that VOIDs
# 'once per merge' is not noise — it is the only thing asking for the run that finds
# this class."
#
# Removing the false VOID must not silently remove that prompt, so it is replaced by
# an honest one. The two questions were always different and the mtime check conflated
# them:
#
#   is the SKIP SET valid?     <- this gate's job. Answered by the digest, above.
#   is the SUITE RESULT current? <- the nightly's job. Answered here, as an ADVISORY.
#
# Deliberately non-failing. A stale result is a reason to run the suite, not a reason
# to call an undeclared-skip check wrong, and a gate that fails for a second reason is
# a gate people learn to ignore.
REPORT_START=$(jq -r '.stats.startTime // empty' "$REPORT" 2>/dev/null)
if [ -n "$REPORT_START" ] && command -v date >/dev/null 2>&1; then
    start_epoch=$(date -d "$REPORT_START" +%s 2>/dev/null || echo "")
    if [ -n "$start_epoch" ]; then
        age_h=$(( ( $(date +%s) - start_epoch ) / 3600 ))
        echo "  ran       : $REPORT_START (${age_h}h ago)"
        # App code, NOT specs: a spec change would have moved the digest and VOIDed
        # above. This asks whether the code under test moved since the suite ran.
        newer=$(git -C "$REPO_ROOT" log --since="$REPORT_START" --oneline \
                    -- frontend ':(exclude)frontend/e2e' ':(exclude)frontend/playwright.config.ts' \
                    2>/dev/null | wc -l)
        if [ "${newer:-0}" -gt 0 ]; then
            echo "  ADVISORY  : $newer commit(s) touched frontend/ outside the spec set since this run."
            echo "              The SKIP SET below is still valid (specs are byte-identical), but the"
            echo "              suite RESULT is older than the code. Re-run the suite, or read the"
            echo "              nightly — 'gh workflow run e2e-nightly.yml --ref main'."
        fi
    fi
fi

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
