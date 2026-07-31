#!/usr/bin/env bash
#
# check-handoff-contract.sh — assert the LIVE claims in HANDOFF.md against reality.
#
# WHY THIS EXISTS. HANDOFF.md went stale TWICE on 2026-07-31, both times under the work it was
# describing: it listed PR #383 as open minutes after #383 merged (fixed in #391), and its §5.5
# called the changelog "the one thing left undone" after #392 backfilled it and #393 gated it
# (fixed in #394). Nothing read the file, so both survived until a human reread it — the same
# root cause as the changelog drift that #393 closed, and the same one recorded for
# docs/deferred-items.md.
#
# THE DESIGN PROBLEM, AND THE CHOICE MADE. HANDOFF.md is HALF LIVE STATE AND HALF HISTORY.
# "§1 What landed: #381 — environment-scoped mute" is a permanent record that must never be
# flagged; the summary table at the top is current state that must never be wrong. A gate that
# cannot tell them apart fires on correct sentences and gets `|| true` appended to it.
#
# So a claim OPTS IN by writing its state word in CAPITALS (H-2), and the two unambiguous
# machine-checkable facts — the gate count and the document's staleness — are checked always.
#
# WHAT IT ENFORCES.
#   H-1  Every "N of N rc=0" and "EXPECT N x rc=0" claim equals the ACTUAL number of gate
#        scripts. This rotted twice in one session (15 -> 16 -> 17) and is pure local counting.
#   H-2  Every CAPITALISED issue/PR state claim matches the forge. "#384 is now CLOSED" and
#        "#385 still OPEN" are checked; lower-case prose is deliberately not.
#   H-3  The document is not more than MAX_PRS_BEHIND merged commits behind the base branch,
#        measured from HEAD's copy of it — so a change that updates the handoff gets credit for
#        doing so, while a change that does not still sees a stale base. Measuring from the BASE
#        instead (the original form) made this check deadlock itself: once main was over budget,
#        every PR went red including the handoff update that was the only thing able to clear it,
#        and `docs-freshness` is a required check. See the block at the H-3 implementation.
#        The ONLY check here that catches semantic rot, and it does so indirectly: it does not
#        know what the prose says, only that the world moved and the document did not.
#   H-4  SELF-TEST of both extractors — each must find a positive sample AND decline a negative
#        one, so a pattern that silently stops matching cannot make H-1/H-2 pass over an empty
#        set. This is not hypothetical: the changelog gate shipped exactly that bug in #393,
#        and its self-test missed it by testing an input shape the scan never sees.
#
# WHAT IT CANNOT DO — read this before treating a green run as "the handoff is accurate".
# It cannot detect semantic rot. A paragraph that states something no longer true, in prose,
# with no capitalised state word and no stale count, passes. §5.5's case would have been caught
# here only by H-3, and only because main happened to move. Green means "the mechanically
# checkable claims hold", nothing more.
#
# FAIL-CLOSED. Missing git / grep -P / gh / an unauthenticated gh / a failed API call / an
# unreadable or empty config / a missing HANDOFF.md / no gate-count claim found at all / a
# claim naming an issue that does not exist / either self-test failing => exit 2 (VOID), never
# 0. A real FAIL (1) outranks a VOID (2), so a genuine drift is never masked by an unreadable
# config — same precedence as check-claims.sh and check-changelog-contract.sh.
#
# Usage:
#   scripts/check-handoff-contract.sh
#   HANDOFF_SKIP_FORGE=1 scripts/check-handoff-contract.sh   # H-1/H-3 only; H-2 reports VOID
#
# Falsification (run BOTH directions before trusting this gate):
#   sed -i 's/17 of 17 rc=0/16 of 16 rc=0/' HANDOFF.md && scripts/check-handoff-contract.sh; echo $?  # expect 1
#   git checkout HANDOFF.md                            && scripts/check-handoff-contract.sh; echo $?  # expect 0
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || { printf 'VOID: cannot cd to repo root\n' >&2; exit 2; }

DOC="${HANDOFF_FILE:-HANDOFF.md}"
CONF="${HANDOFF_CONF:-scripts/gates/handoff-contract.conf}"

FAIL=0
VOID=0
void() { printf 'VOID: %s\n' "$1" >&2; VOID=1; }
fail() { printf 'FAIL: %s\n' "$1" >&2; FAIL=1; }
die_void() { printf 'VOID: %s\n' "$1" >&2; exit 2; }

printf 'check-handoff-contract  (%s)\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"

command -v git >/dev/null 2>&1 || die_void "git is not installed"
printf 'x' | grep -qP 'x' 2>/dev/null || die_void "grep does not support -P (PCRE)"
git rev-parse --git-dir >/dev/null 2>&1 || die_void "not inside a git repository"
[ -r "$DOC" ] || die_void "$DOC is missing or unreadable"
[ -s "$DOC" ] || die_void "$DOC is empty"
[ -r "$CONF" ] || die_void "$CONF is missing or unreadable"

# --- config -------------------------------------------------------------------------
MAX_BEHIND=""
conf_lines=0
while IFS= read -r line; do
	case "$line" in ''|[[:space:]]*'#'*|'#'*) continue ;; esac
	[ -z "${line//[[:space:]]/}" ] && continue
	conf_lines=$((conf_lines + 1))
	directive=$(awk '{print $1}' <<< "$line")
	case "$directive" in
		MAX_PRS_BEHIND)
			[ -n "$MAX_BEHIND" ] && die_void "$CONF declares MAX_PRS_BEHIND more than once"
			MAX_BEHIND=$(awk '{print $2}' <<< "$line")
			grep -qE '^[0-9]+$' <<< "$MAX_BEHIND" || die_void "$CONF MAX_PRS_BEHIND is not a number: '$MAX_BEHIND'"
			;;
		*) die_void "$CONF has an unknown directive: '$directive'" ;;
	esac
done < "$CONF"
[ "$conf_lines" -gt 0 ] || die_void "$CONF declares nothing"
[ -n "$MAX_BEHIND" ] || die_void "$CONF declares no MAX_PRS_BEHIND"

# --- patterns ------------------------------------------------------------------------
# Two sites state the gate count: the summary table, and the resume block's expectation.
GATE_TABLE_RE='\*\*([0-9]+) of ([0-9]+) rc=0'
GATE_EXPECT_RE='EXPECT ([0-9]+) x rc=0'
# A claim opts in by CAPITALISING the state word. Lower-case prose is narrative.
STATE_RE='#([0-9]+)[^#\n]{0,40}?\b(CLOSED|OPEN)\b'

# --- H-4  self-tests: both extractors fire, and decline -------------------------------
grep -qP "$GATE_TABLE_RE" <<< '| Gates | **17 of 17 rc=0** (measured) |' \
	|| die_void "H-4 self-test: the gate-count matcher did not match a known table claim"
grep -qP "$GATE_EXPECT_RE" <<< '# EXPECT 17 x rc=0 — ALL of them.' \
	|| die_void "H-4 self-test: the gate-count matcher did not match a known EXPECT claim"
if grep -qP "$GATE_TABLE_RE" <<< 'the run was 17 of 17 green'; then
	die_void "H-4 self-test: the gate-count matcher fired without the ** marker — it would read prose as a claim"
fi
grep -qP "$STATE_RE" <<< '**#384 is now CLOSED** by #389' \
	|| die_void "H-4 self-test: the state matcher did not match a known CLOSED claim"
grep -qP "$STATE_RE" <<< '- **Open: #385 still OPEN** (H-5 label)' \
	|| die_void "H-4 self-test: the state matcher did not match a known OPEN claim"
if grep -qP "$STATE_RE" <<< '#384 is closed, and that is history now'; then
	die_void "H-4 self-test: the state matcher fired on LOWER-CASE prose — narrative would be gated"
fi

# --- H-1  gate-count claims ------------------------------------------------------------
ACTUAL_GATES=$(ls scripts/check-*.sh scripts/docs-freshness.sh 2>/dev/null | wc -l)
[ "$ACTUAL_GATES" -gt 0 ] || die_void "found 0 gate scripts — discovery is broken, not a clean repo"

n_gate_claims=0
while IFS= read -r m; do
	[ -z "$m" ] && continue
	a=$(grep -oP '\*\*\K[0-9]+' <<< "$m" | head -1)
	b=$(grep -oP 'of \K[0-9]+' <<< "$m" | head -1)
	n_gate_claims=$((n_gate_claims + 1))
	if [ "$a" != "$ACTUAL_GATES" ] || [ "$b" != "$ACTUAL_GATES" ]; then
		fail "H-1 $DOC claims '$a of $b rc=0' but the repo has $ACTUAL_GATES gate script(s)"
	fi
done < <(grep -oP "$GATE_TABLE_RE" "$DOC")

while IFS= read -r m; do
	[ -z "$m" ] && continue
	a=$(grep -oP 'EXPECT \K[0-9]+' <<< "$m")
	n_gate_claims=$((n_gate_claims + 1))
	[ "$a" = "$ACTUAL_GATES" ] || fail "H-1 $DOC's resume block says 'EXPECT $a x rc=0' but the repo has $ACTUAL_GATES gate script(s)"
done < <(grep -oP "$GATE_EXPECT_RE" "$DOC")

[ "$n_gate_claims" -gt 0 ] || die_void "$DOC states no gate count at all — the anchor claim is missing, so this gate would check almost nothing"

# --- H-2  capitalised issue/PR state claims -------------------------------------------
n_state=0
n_state_ok=0
if [ "${HANDOFF_SKIP_FORGE:-0}" = "1" ]; then
	void "H-2 skipped by HANDOFF_SKIP_FORGE=1 — state claims are UNVERIFIED, not clean"
else
	command -v gh >/dev/null 2>&1 || die_void "gh is not installed — cannot verify issue/PR state claims"
	SLUG=$(gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null); rc=$?
	[ "$rc" -eq 0 ] && [ -n "$SLUG" ] || die_void "gh could not resolve the repository (unauthenticated?) — cannot verify state claims"
	while IFS= read -r m; do
		[ -z "$m" ] && continue
		num=$(grep -oP '#\K[0-9]+' <<< "$m" | head -1)
		want=$(grep -oP '\b(CLOSED|OPEN)\b' <<< "$m" | head -1)
		[ -z "$num" ] && continue
		n_state=$((n_state + 1))
		got=$(gh api "repos/$SLUG/issues/$num" --jq .state 2>/dev/null); rc=$?
		if [ "$rc" -ne 0 ] || [ -z "$got" ]; then
			void "H-2 could not resolve #$num via the forge — UNVERIFIED, not clean"
			continue
		fi
		# API states are lower-case; the doc's capitals are the opt-in marker, not the value.
		if [ "$(tr '[:upper:]' '[:lower:]' <<< "$want")" = "$got" ]; then
			n_state_ok=$((n_state_ok + 1))
		else
			fail "H-2 $DOC claims #$num is $want but the forge says $got"
		fi
	done < <(grep -oP "$STATE_RE" "$DOC")
fi

# --- H-3  staleness budget --------------------------------------------------------------
BASE_REF="${HANDOFF_BASE_REF:-}"
if [ -z "$BASE_REF" ]; then
	BASE_REF=$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null) || BASE_REF=""
fi
if [ -z "$BASE_REF" ]; then
	d=$(git remote show origin 2>/dev/null | awk '/HEAD branch/ {print $NF}')
	[ -n "$d" ] && BASE_REF="origin/$d"
fi
[ -n "$BASE_REF" ] || die_void "cannot resolve the base branch — set HANDOFF_BASE_REF"
git rev-parse --verify --quiet "${BASE_REF}^{commit}" >/dev/null || die_void "base ref '$BASE_REF' is not a commit (shallow clone?)"

# Measure the last touch from HEAD, not from BASE_REF.
#
# WHY. Reading BASE_REF only made this check DEADLOCK ITSELF, and it did so exactly when the
# document was most overdue. H-3 asked "how far has the base moved since the base last touched
# HANDOFF.md" — a question whose answer a PR cannot change, because the PR's commit is not on the
# base until it merges. So once main accumulated more than MAX_PRS_BEHIND commits, EVERY PR went
# red, INCLUDING the handoff update that was the only thing that could clear it, and
# `docs-freshness` is a required check. Measured 2026-07-31: main was red at 2b5339f8 and #403 —
# the fix — was BLOCKED by this line.
#
# Taking LAST_TOUCH from HEAD asks the question that was actually meant: "is the version of the
# document I am looking at stale relative to the base?" A change that updates the doc gets credit
# for it; one that does not, does not.
#
# The on-main semantics are UNCHANGED and deliberately so — on a push to main HEAD == BASE_REF, so
# this resolves to the identical commit and a genuinely stale main still fails. Verified in both
# directions rather than assumed.
#
# A branch that updates the doc but is BEHIND the base still fails here, because the base's newer
# commits are not reachable from LAST_TOUCH — which is the correct signal, and the same one
# `check-branch-behind-base.sh` gives.
LAST_TOUCH=$(git log -1 --format=%H HEAD -- "$DOC" 2>/dev/null)
[ -n "$LAST_TOUCH" ] || die_void "no commit reachable from HEAD has ever touched $DOC — cannot measure staleness"
BEHIND=$(git log --first-parent --oneline "${LAST_TOUCH}..${BASE_REF}" 2>/dev/null | wc -l)
if [ "$BEHIND" -gt "$MAX_BEHIND" ]; then
	fail "H-3 $DOC is $BEHIND merged commit(s) behind $BASE_REF (budget $MAX_BEHIND) — last touched by $(git log -1 --format=%h "$LAST_TOUCH"). Re-read it before trusting it."
fi

# --- summary ------------------------------------------------------------------------------
printf '  doc       : %s\n  config    : %s\n' "$DOC" "$CONF"
printf '  H-1 gates : %s claim(s), repo has %s gate script(s)\n' "$n_gate_claims" "$ACTUAL_GATES"
printf '  H-2 state : %s claim(s), %s matched the forge\n' "$n_state" "$n_state_ok"
printf '  H-3 stale : %s commit(s) behind %s (budget %s)\n' "$BEHIND" "$BASE_REF" "$MAX_BEHIND"
printf '  H-4 self  : both extractors fire and decline\n'

if [ "$FAIL" -ne 0 ]; then
	echo "FAILED: $DOC states something that is no longer true (see above)." >&2
	exit 1
fi
if [ "$VOID" -ne 0 ]; then
	echo "VOID: the gate could not complete its checks (see above) — treat as unverified, not as a pass." >&2
	exit 2
fi
printf 'PASS: %s — %s gate-count claim(s) and %s state claim(s) hold; %s commit(s) behind (budget %s).\n' \
	"$DOC" "$n_gate_claims" "$n_state" "$BEHIND" "$MAX_BEHIND"
printf '      NOTE: this gate cannot detect semantic rot — a green run is not "the handoff is accurate".\n'
