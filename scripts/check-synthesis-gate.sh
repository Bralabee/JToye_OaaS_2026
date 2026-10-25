#!/usr/bin/env bash
# ==============================================================================
# check-synthesis-gate.sh — Repo-committed Synthesis Governance Gate
# ==============================================================================
# Ensures both the repository owner and colleagues without ~/dotfiles are
# prompted to conduct or approve synthesis (/qa-council + /ux-persona-test) at
# milestone completion and hard-gated before closing a milestone or cutting a
# release tag.
#
# Modes:
#   scripts/check-synthesis-gate.sh [--dir <repo>] [--milestone <ver>] [--allow-snoozed]
#   scripts/check-synthesis-gate.sh --record [--dir <repo>] [--milestone <ver>] --status <approved|waived|snoozed> --by <name> --note <text> [--qa-run-id <id>] [--persona-run-id <id>]
#   scripts/check-synthesis-gate.sh --hook-session-start [--dir <repo>]
#   scripts/check-synthesis-gate.sh --hook-skill-gate
#   scripts/check-synthesis-gate.sh --hook-bash-gate
#   scripts/check-synthesis-gate.sh --selftest
# ==============================================================================
set -uo pipefail

usage() {
    cat >&2 <<'EOF'
Usage:
  scripts/check-synthesis-gate.sh [--dir <repo>] [--milestone <ver>] [--allow-snoozed]
  scripts/check-synthesis-gate.sh --record [--dir <repo>] [--milestone <ver>] --status <approved|waived|snoozed> --by <name> --note <text> [--qa-run-id <id>] [--persona-run-id <id>]
  scripts/check-synthesis-gate.sh --hook-session-start [--dir <repo>]
  scripts/check-synthesis-gate.sh --hook-skill-gate
  scripts/check-synthesis-gate.sh --hook-bash-gate
  scripts/check-synthesis-gate.sh --selftest
EOF
    exit 2
}

if ! command -v jq >/dev/null 2>&1; then
    echo "check-synthesis-gate: ERROR — jq is required" >&2
    exit 2
fi

resolve_repo_root() {
    local explicit_dir="${1:-}" root=""
    if [ -n "$explicit_dir" ]; then
        [ -d "$explicit_dir" ] || { echo "check-synthesis-gate: ERROR — '$explicit_dir' is not a directory" >&2; exit 2; }
        root=$(cd "$explicit_dir" && git rev-parse --show-toplevel 2>/dev/null) || root=$(cd "$explicit_dir" && pwd)
    else
        root=$(git rev-parse --show-toplevel 2>/dev/null) || {
            local script_dir
            script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
            root="$script_dir"
        }
    fi
    printf '%s\n' "$root"
}

resolve_active_milestone() {
    local root="$1" ms=""
    if [ -s "$root/qa/synthesis-ledger.json" ]; then
        ms=$(jq -r '.current_milestone // empty' "$root/qa/synthesis-ledger.json" 2>/dev/null || true)
    fi
    if [ -z "$ms" ] && [ -s "$root/.planning/STATE.md" ]; then
        ms=$(sed -n 's/^milestone:[[:space:]]*\([^[:space:]]*\).*$/\1/p' "$root/.planning/STATE.md" | head -n 1)
    fi
    if [ -z "$ms" ] && [ -s "$root/.planning/ROADMAP.md" ]; then
        ms=$(sed -n 's/.*\*\*v\([0-9][0-9.]*\).*$/v\1/p' "$root/.planning/ROADMAP.md" | tail -n 1)
    fi
    [ -n "$ms" ] || ms="default"
    printf '%s\n' "$ms"
}

validate_synthesis_ledger() {
    local lf="$1"
    [ -s "$lf" ] || return 1
    jq -e '
        type == "object"
        and (.schema == 1)
        and (.milestones | type == "object")
        and all(.milestones[];
            type == "object"
            and (.status | IN("approved","waived","snoozed"))
            and (.by | type == "string" and length > 0)
            and (.at | type == "string" and length > 0)
            and (.note | type == "string" and length > 0)
        )
    ' "$lf" >/dev/null 2>&1
}

check_synthesis() {
    local explicit_dir="" milestone="" allow_snoozed=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --dir) [ $# -ge 2 ] || usage; explicit_dir="$2"; shift 2 ;;
            --milestone) [ $# -ge 2 ] || usage; milestone="$2"; shift 2 ;;
            --allow-snoozed) allow_snoozed=1; shift ;;
            *) usage ;;
        esac
    done

    local root
    root=$(resolve_repo_root "$explicit_dir")
    [ -n "$milestone" ] || milestone=$(resolve_active_milestone "$root")
    local ledger="$root/qa/synthesis-ledger.json"

    if ! validate_synthesis_ledger "$ledger"; then
        cat >&2 <<EOF
SYNTHESIS GATE FAIL: qa/synthesis-ledger.json is missing or invalid for milestone '$milestone'.
Before closing a milestone or cutting a release tag, conduct or approve the synthesis (/qa-council + /ux-persona-test):
  1. Run synthesis:   /qa-council   and/or   /ux-persona-test
  2. Approve ledger:  bash scripts/check-synthesis-gate.sh --record --milestone "$milestone" --status approved --by "<your-name>" --note "<summary>"
  3. Or waive:        bash scripts/check-synthesis-gate.sh --record --milestone "$milestone" --status waived --by "<your-name>" --note "<reason>"
EOF
        return 1
    fi

    if ! jq -e --arg ms "$milestone" '.milestones | has($ms)' "$ledger" >/dev/null 2>&1; then
        cat >&2 <<EOF
SYNTHESIS GATE FAIL: milestone '$milestone' has no synthesis sign-off in qa/synthesis-ledger.json.
Before closing milestone '$milestone' or cutting a release tag, conduct or approve the synthesis:
  1. Run synthesis:   /qa-council   and/or   /ux-persona-test
  2. Approve ledger:  bash scripts/check-synthesis-gate.sh --record --milestone "$milestone" --status approved --by "<your-name>" --note "<summary>"
  3. Or waive:        bash scripts/check-synthesis-gate.sh --record --milestone "$milestone" --status waived --by "<your-name>" --note "<reason>"
EOF
        return 1
    fi

    local status by at
    status=$(jq -r --arg ms "$milestone" '.milestones[$ms].status' "$ledger")
    by=$(jq -r --arg ms "$milestone" '.milestones[$ms].by' "$ledger")
    at=$(jq -r --arg ms "$milestone" '.milestones[$ms].at' "$ledger")

    case "$status" in
        approved|waived)
            echo "synthesis-gate: PASS (milestone=$milestone status=$status by=$by at=$at)"
            return 0
            ;;
        snoozed)
            if [ "$allow_snoozed" -eq 1 ]; then
                echo "synthesis-gate: SNOOZED (milestone=$milestone by=$by at=$at)"
                return 0
            fi
            cat >&2 <<EOF
SYNTHESIS GATE FAIL: milestone '$milestone' synthesis is currently 'snoozed' (by $by at $at).
Snoozing only suppresses SessionStart reminders; milestone closeout and release tags require 'approved' or 'waived':
  bash scripts/check-synthesis-gate.sh --record --milestone "$milestone" --status approved --by "<your-name>" --note "<summary>"
EOF
            return 1
            ;;
        *)
            echo "SYNTHESIS GATE FAIL: invalid status '$status' for milestone '$milestone'" >&2
            return 1
            ;;
    esac
}

record_synthesis() {
    local explicit_dir="" milestone="" status="" by="" note="" qa_run_id="" persona_run_id=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --dir) [ $# -ge 2 ] || usage; explicit_dir="$2"; shift 2 ;;
            --milestone) [ $# -ge 2 ] || usage; milestone="$2"; shift 2 ;;
            --status) [ $# -ge 2 ] || usage; status="$2"; shift 2 ;;
            --by) [ $# -ge 2 ] || usage; by="$2"; shift 2 ;;
            --note) [ $# -ge 2 ] || usage; note="$2"; shift 2 ;;
            --qa-run-id) [ $# -ge 2 ] || usage; qa_run_id="$2"; shift 2 ;;
            --persona-run-id) [ $# -ge 2 ] || usage; persona_run_id="$2"; shift 2 ;;
            *) usage ;;
        esac
    done

    case "$status" in
        approved|waived|snoozed) ;;
        *) echo "check-synthesis-gate: ERROR — --status must be approved, waived, or snoozed" >&2; exit 2 ;;
    esac
    if [ -z "$by" ] || [ -z "$note" ]; then
        echo "check-synthesis-gate: ERROR — --by and --note are required" >&2
        exit 2
    fi

    local root
    root=$(resolve_repo_root "$explicit_dir")
    [ -n "$milestone" ] || milestone=$(resolve_active_milestone "$root")

    local ledger="$root/qa/synthesis-ledger.json"
    mkdir -p "$root/qa"
    if [ ! -s "$ledger" ]; then
        printf '{"schema":1,"current_milestone":"%s","milestones":{}}\n' "$milestone" > "$ledger"
    fi

    local now_iso head_sha tmp
    now_iso=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    head_sha=$(git -C "$root" rev-parse HEAD 2>/dev/null || echo "uncommitted")
    tmp=$(mktemp)

    jq --arg ms "$milestone" \
       --arg st "$status" \
       --arg by "$by" \
       --arg at "$now_iso" \
       --arg sha "$head_sha" \
       --arg qid "$qa_run_id" \
       --arg pid "$persona_run_id" \
       --arg note "$note" '
        .schema = 1
        | .current_milestone = $ms
        | .milestones = (.milestones // {})
        | .milestones[$ms] = {
            status: $st,
            by: $by,
            at: $at,
            head_sha: $sha,
            qa_council_run_id: (if $qid == "" then null else $qid end),
            persona_sim_run_id: (if $pid == "" then null else $pid end),
            note: $note
        }
    ' "$ledger" > "$tmp" && mv "$tmp" "$ledger"

    validate_synthesis_ledger "$ledger" || {
        echo "GATE FAIL: updated qa/synthesis-ledger.json failed schema validation" >&2
        exit 1
    }
    echo "recorded synthesis sign-off: milestone=$milestone status=$status by=$by -> qa/synthesis-ledger.json"
}

global_hook_active() {
    local subcmd="$1"
    [ "${SYNTHESIS_GATE_IGNORE_GLOBAL:-0}" = "1" ] && return 1
    [ -x "$HOME/.claude/scripts/qa-council-ctl.sh" ] && \
        command grep -qF "qa-council-ctl.sh $subcmd" "$HOME/.claude/settings.json" 2>/dev/null
}

hook_session_start() {
    local explicit_dir=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --dir) [ $# -ge 2 ] || exit 0; explicit_dir="$2"; shift 2 ;;
            *) shift ;;
        esac
    done

    global_hook_active "hook-session-start" && exit 0

    local root
    root=$(resolve_repo_root "$explicit_dir" 2>/dev/null) || exit 0
    local milestone
    milestone=$(resolve_active_milestone "$root")

    if check_synthesis --dir "$root" --milestone "$milestone" --allow-snoozed >/dev/null 2>&1; then
        exit 0
    fi

    local reason=""
    if [ -s "$root/.planning/STATE.md" ]; then
        local total_p comp_p st
        total_p=$(sed -n 's/^[[:space:]]*total_phases:[[:space:]]*\([0-9][0-9]*\).*$/\1/p' "$root/.planning/STATE.md" | head -n 1)
        comp_p=$(sed -n 's/^[[:space:]]*completed_phases:[[:space:]]*\([0-9][0-9]*\).*$/\1/p' "$root/.planning/STATE.md" | head -n 1)
        st=$(sed -n 's/^status:[[:space:]]*\([^[:space:]]*\).*$/\1/p' "$root/.planning/STATE.md" | head -n 1)
        if [ -n "$total_p" ] && [ -n "$comp_p" ] && [ "$total_p" -gt 0 ] && [ "$comp_p" -ge "$total_p" ]; then
            reason="all $comp_p/$total_p phases in milestone $milestone are complete (status=${st:-unknown})"
        elif [ "$st" = "completed" ] || [ "$st" = "verifying" ]; then
            reason="milestone $milestone is in status '$st' without synthesis sign-off"
        fi
    elif [ -s "$root/qa/synthesis-ledger.json" ]; then
        reason="milestone $milestone has no sign-off entry in qa/synthesis-ledger.json"
    fi

    [ -n "$reason" ] || exit 0

    cat <<EOF
<synthesis-nudge milestone="$milestone">
SYNTHESIS GOVERNANCE NUDGE: $reason, and milestone '$milestone' is not yet signed off in qa/synthesis-ledger.json.
Before closing the milestone or cutting a release tag, prompt the user (via AskUserQuestion) to choose one:
  1. Conduct synthesis now (/qa-council and/or /ux-persona-test)
  2. Approve existing synthesis: bash scripts/check-synthesis-gate.sh --record --milestone "$milestone" --status approved --by "<name>" --note "<summary>"
  3. Snooze reminder until milestone close: bash scripts/check-synthesis-gate.sh --record --milestone "$milestone" --status snoozed --by "<name>" --note "snoozed during active dev"
  4. Waive synthesis for this milestone: bash scripts/check-synthesis-gate.sh --record --milestone "$milestone" --status waived --by "<name>" --note "<reason>"
</synthesis-nudge>
EOF
    exit 0
}

hook_skill_gate() {
    local payload=""
    if [ ! -t 0 ]; then
        payload=$(cat)
    fi
    [ -n "$payload" ] || exit 0

    global_hook_active "hook-skill-gate" && exit 0

    local skill cwd
    skill=$(jq -r '.tool_input.skill // ""' <<<"$payload" 2>/dev/null || true)
    cwd=$(jq -r '.cwd // ""' <<<"$payload" 2>/dev/null || true)

    case "$skill" in
        gsd-audit-milestone|gsd:audit-milestone|audit-milestone|gsd-complete-milestone|gsd:complete-milestone|complete-milestone|gsd-milestone-summary|gsd:milestone-summary|milestone-summary)
            ;;
        *)
            exit 0
            ;;
    esac

    local root
    root=$(resolve_repo_root "$cwd" 2>/dev/null) || exit 0

    if ! check_synthesis --dir "$root" >/dev/null; then
        echo "BLOCKED Skill($skill): milestone synthesis governance check failed." >&2
        exit 2
    fi
    exit 0
}

is_release_or_tag_command() {
    local cmd="$1"
    if printf '%s\n' "$cmd" | command grep -qE '(^|[;&|[:space:]])gh[[:space:]]+release[[:space:]]+create([[:space:]]|$)'; then
        return 0
    fi
    if printf '%s\n' "$cmd" | command grep -qE '(^|[;&|[:space:]])git[[:space:]]+tag([[:space:]]|$)'; then
        if printf '%s\n' "$cmd" | command grep -qE '(^|[;&|[:space:]])git[[:space:]]+tag[[:space:]]+(-l|--list|-d|--delete|-v|--verify|--contains|--points-at)([[:space:]]|$)'; then
            return 1
        fi
        if printf '%s\n' "$cmd" | command grep -qE '(^|[;&|[:space:]])git[[:space:]]+tag([[:space:]]+(-[asfm]|--annotate|--sign|--force|--message(=[^[:space:]]*|[[:space:]]+("[^"]*"|'\''[^'\'']*'\''|[^[:space:]]+))))*[[:space:]]+v[0-9]'; then
            return 0
        fi
    fi
    return 1
}

hook_bash_gate() {
    local payload=""
    if [ ! -t 0 ]; then
        payload=$(cat)
    fi
    [ -n "$payload" ] || exit 0

    local command_str cwd
    command_str=$(jq -r '.tool_input.command // ""' <<<"$payload" 2>/dev/null || true)
    cwd=$(jq -r '.cwd // ""' <<<"$payload" 2>/dev/null || true)

    is_release_or_tag_command "$command_str" || exit 0

    local root
    root=$(resolve_repo_root "$cwd" 2>/dev/null) || exit 0

    if ! check_synthesis --dir "$root" >/dev/null; then
        echo "BLOCKED Bash($command_str): release/tag synthesis governance check failed." >&2
        exit 2
    fi
    exit 0
}

run_selftest() {
    local tmp
    tmp=$(mktemp -d) || exit 2
    trap 'rm -rf "$tmp"' EXIT

    git -C "$tmp" init -q
    local self="${BASH_SOURCE[0]}"
    local passed=0 failed=0

    assert_rc() {
        local desc="$1" exp="$2" act="$3" out="$4" sub="${5:-}"
        if [ "$act" -ne "$exp" ]; then
            echo "  FAIL  $desc (expected rc=$exp, got rc=$act: $out)" >&2
            failed=$((failed + 1))
            return 1
        fi
        if [ -n "$sub" ] && ! command grep -qF -- "$sub" <<<"$out"; then
            echo "  FAIL  $desc (rc=$act matched, but output lacked '$sub': $out)" >&2
            failed=$((failed + 1))
            return 1
        fi
        passed=$((passed + 1))
        return 0
    }

    local out rc
    mkdir -p "$tmp/.planning"
    cat > "$tmp/.planning/STATE.md" <<'EOF'
---
milestone: v2.3
status: completed
progress:
  total_phases: 4
  completed_phases: 4
---
EOF

    # 1. Fails without qa/synthesis-ledger.json
    out=$(bash "$self" --dir "$tmp" 2>&1); rc=$?
    assert_rc "1 check fails without ledger" 1 "$rc" "$out" "SYNTHESIS GATE FAIL"

    # 2. SessionStart emits nudge when colleague runs without global hook
    out=$(SYNTHESIS_GATE_IGNORE_GLOBAL=1 bash "$self" --hook-session-start --dir "$tmp" 2>&1); rc=$?
    assert_rc "2 hook-session-start emits nudge" 0 "$rc" "$out" "<synthesis-nudge milestone=\"v2.3\">"

    # 3. Snooze suppresses SessionStart nudge but still blocks milestone close & git tag v*
    out=$(bash "$self" --record --dir "$tmp" --milestone v2.3 --status snoozed --by colleague --note "snoozed" 2>&1); rc=$?
    assert_rc "3 record snoozed" 0 "$rc" "$out" "status=snoozed"

    out=$(SYNTHESIS_GATE_IGNORE_GLOBAL=1 printf '{"cwd":"%s","tool_input":{"skill":"gsd-audit-milestone"}}' "$tmp" | SYNTHESIS_GATE_IGNORE_GLOBAL=1 bash "$self" --hook-skill-gate 2>&1); rc=$?
    assert_rc "4 hook-skill-gate blocks gsd-audit-milestone when snoozed" 2 "$rc" "$out" "BLOCKED Skill(gsd-audit-milestone)"

    out=$(printf '{"cwd":"%s","tool_input":{"command":"git tag v2.3.0"}}' "$tmp" | bash "$self" --hook-bash-gate 2>&1); rc=$?
    assert_rc "5 hook-bash-gate blocks git tag v2.3.0 when snoozed" 2 "$rc" "$out" "BLOCKED Bash(git tag v2.3.0)"

    out=$(printf '{"cwd":"%s","tool_input":{"command":"gh release create v2.3.0"}}' "$tmp" | bash "$self" --hook-bash-gate 2>&1); rc=$?
    assert_rc "6 hook-bash-gate blocks gh release create when snoozed" 2 "$rc" "$out" "BLOCKED Bash(gh release create v2.3.0)"

    out=$(printf '{"cwd":"%s","tool_input":{"command":"git tag -l v*"}}' "$tmp" | bash "$self" --hook-bash-gate 2>&1); rc=$?
    assert_rc "7 hook-bash-gate allows git tag -l" 0 "$rc" "$out" ""

    # 4. Recording approved unlocks check, skill gate, and bash gate
    out=$(bash "$self" --record --dir "$tmp" --milestone v2.3 --status approved --by colleague --note "QA Council + Persona Sim complete" 2>&1); rc=$?
    assert_rc "8 record approved" 0 "$rc" "$out" "status=approved"

    out=$(bash "$self" --dir "$tmp" 2>&1); rc=$?
    assert_rc "9 check passes when approved" 0 "$rc" "$out" "synthesis-gate: PASS"

    out=$(printf '{"cwd":"%s","tool_input":{"skill":"gsd-audit-milestone"}}' "$tmp" | SYNTHESIS_GATE_IGNORE_GLOBAL=1 bash "$self" --hook-skill-gate 2>&1); rc=$?
    assert_rc "10 hook-skill-gate allows gsd-audit-milestone when approved" 0 "$rc" "$out" ""

    out=$(printf '{"cwd":"%s","tool_input":{"command":"git tag v2.3.0"}}' "$tmp" | bash "$self" --hook-bash-gate 2>&1); rc=$?
    assert_rc "11 hook-bash-gate allows git tag v2.3.0 when approved" 0 "$rc" "$out" ""

    rm -rf "$tmp"
    trap - EXIT
    echo "check-synthesis-gate --selftest: passed=$passed failed=$failed"
    [ "$failed" -eq 0 ]
}

case "${1:-}" in
    --selftest) shift; run_selftest "$@" ;;
    --record) shift; record_synthesis "$@" ;;
    --hook-session-start) shift; hook_session_start "$@" ;;
    --hook-skill-gate) shift; hook_skill_gate "$@" ;;
    --hook-bash-gate) shift; hook_bash_gate "$@" ;;
    -h|--help) usage ;;
    *) check_synthesis "$@" ;;
esac
