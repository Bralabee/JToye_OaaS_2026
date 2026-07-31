#!/usr/bin/env bash
#
# check-changelog-contract.sh — assert that every merged feat/fix PR appears in the changelog.
#
# WHY THIS EXISTS. docs/CHANGELOG.md is the only doc in this repo that NOTHING read.
# docs-freshness.sh, check-doc-metrics.sh, check-doc-citations.sh and check-claims.sh
# together open CLAUDE.md, AGENTS.md, README.md, the .planning/codebase docs, k8s/DEPLOYMENT.md
# and docs/ops/terminal-states.yaml — and not this file. Measured 2026-07-31: the last entry
# was ccb15e23 (#363) while main had merged NINE further feat/fix PRs (#368, #370, #376, #380,
# #381, #382, #383, #387, #389), all four doc gates green on every one of those commits.
# Backfilled in #392; this gate is what stops it recurring.
#
# It is deliberately a DIFFERENT SHAPE from the claim-gate engine. That engine asserts
# "a value quoted in a doc equals its source of truth" — a value-to-value comparison. There
# is no value here: the assertion is that a SET (merged feat/fix PRs) is covered by a
# document. So it gets its own script rather than a manifest row that could not express it.
#
# WHAT IT ENFORCES.
#   C-1  Every feat/fix commit merged after FLOOR is cited in docs/CHANGELOG.md by "(#NNN)".
#   C-2  Every EXEMPT row is still NEEDED. An exemption whose PR is now cited, or which names
#        a PR that is not an uncited feat/fix commit in range, is STALE and FAILS — so
#        exemptions are retired by the gate going red, not by somebody remembering to look.
#        Mirrors KNOWN_DATALESS in check-alert-metrics.sh, which has retired three that way.
#   C-3  SELF-TEST of the commit matcher: it must match a positive sample AND reject a
#        negative one. A subject regex that silently stops matching would make C-1 pass over
#        an empty set — the classic vacuous assertion. This proves the instrument can fire.
#   C-4  SELF-TEST of the citation lookup, in BOTH directions: a number known to be present
#        must be found, and a constructed-absent number must not be. Proves that "not cited"
#        is a real absence rather than a broken search.
#
# WHY IT READS MERGED HISTORY, NOT THE BRANCH. The range ends at the resolved base branch
# (origin/HEAD), never at HEAD. Branch-local commits do not carry a PR number yet, so ending
# at HEAD would VOID on essentially every feature PR and train people to ignore it. The
# consequence is deliberate: a PR that forgets its entry goes red on the PUSH-TO-MAIN run
# immediately after it merges, which is the right moment and the right person.
#
# FAIL-CLOSED. Missing git / missing grep -P / unreadable or empty config / no FLOOR / a FLOOR
# that is not a commit / an unresolvable base ref / a git log that errors / a feat/fix commit
# carrying NO PR number (unattributable, so uncheckable) / either self-test failing => exit 2
# (VOID), never 0. "Could not check it" is never rendered as "checked it and it was fine".
# A real FAIL (1) outranks a VOID (2), so a genuine omission is never masked by an unreadable
# config — same precedence as check-claims.sh.
#
# Usage:
#   scripts/check-changelog-contract.sh            # check mode (CI)
#   CHANGELOG_BASE_REF=origin/main scripts/check-changelog-contract.sh
#
# Falsification (run BOTH directions before trusting this gate):
#   sed -i 's/(#389)/(#000)/g' docs/CHANGELOG.md && scripts/check-changelog-contract.sh; echo $?   # expect 1
#   git checkout docs/CHANGELOG.md                && scripts/check-changelog-contract.sh; echo $?  # expect 0
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || { printf 'VOID: cannot cd to repo root\n' >&2; exit 2; }

CHANGELOG="${CHANGELOG_FILE:-docs/CHANGELOG.md}"
CONF="${CHANGELOG_CONF:-scripts/gates/changelog-contract.conf}"

# A conventional-commit subject for a user-visible change, optionally breaking (`feat!:`),
# optionally scoped (`fix(ui+docs):`). Anchored, so `docs(handoff):` and `chore:` do not match.
SUBJECT_RE='^(feat|fix)(\([^)]*\))?!?: '
PR_RE='\(#([0-9]+)\)$'

# The scan runs over `git log --format='%h%x09%s'` lines, which begin with the SHA and a TAB —
# so SUBJECT_RE's own `^` can never match there. Applying it directly returned 0 feat/fix over
# 26 commits and still reported PASS: the precise vacuous pass this gate exists to prevent,
# caught only because the printed count looked wrong. LINE_RE re-anchors it past the SHA field,
# and C-3 below now self-tests THIS regex against TAB-bearing samples built the same way the
# real lines are, so the failure mode cannot come back silently.
LINE_RE="^[^"$'\t'"]*"$'\t'"${SUBJECT_RE#^}"

FAIL=0
VOID=0
void() { printf 'VOID: %s\n' "$1" >&2; VOID=1; }
fail() { printf 'FAIL: %s\n' "$1" >&2; FAIL=1; }
die_void() { printf 'VOID: %s\n' "$1" >&2; exit 2; }

printf 'check-changelog-contract  (%s)\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# --- preconditions -----------------------------------------------------------------
command -v git >/dev/null 2>&1 || die_void "git is not installed"
printf 'x' | grep -qP 'x' 2>/dev/null || die_void "grep does not support -P (PCRE)"
git rev-parse --git-dir >/dev/null 2>&1 || die_void "not inside a git repository"
[ -r "$CHANGELOG" ] || die_void "$CHANGELOG is missing or unreadable"
[ -s "$CHANGELOG" ] || die_void "$CHANGELOG is empty — nothing could be cited in it"
[ -r "$CONF" ] || die_void "$CONF is missing or unreadable"

# --- config ------------------------------------------------------------------------
# Strip comments whose first non-space char is '#'; keep everything else for validation,
# so an unknown directive is caught rather than silently ignored.
FLOOR=""
EXEMPT_PRS=()
EXEMPT_REASONS=()
conf_lines=0
while IFS= read -r line; do
	case "$line" in ''|[[:space:]]*'#'*|'#'*) continue ;; esac
	[ -z "${line//[[:space:]]/}" ] && continue
	conf_lines=$((conf_lines + 1))
	directive=$(awk '{print $1}' <<< "$line")
	case "$directive" in
		FLOOR)
			[ -n "$FLOOR" ] && die_void "$CONF declares FLOOR more than once"
			FLOOR=$(awk '{print $2}' <<< "$line")
			[ -n "$FLOOR" ] || die_void "$CONF has a FLOOR directive with no commit-ish"
			;;
		EXEMPT)
			pr=$(awk '{print $2}' <<< "$line")
			reason=$(cut -d' ' -f3- <<< "$line")
			grep -qE '^[0-9]+$' <<< "$pr" || die_void "$CONF EXEMPT has a non-numeric PR: '$pr'"
			[ -n "${reason//[[:space:]]/}" ] || die_void "$CONF EXEMPT #$pr has no reason — a reason is mandatory"
			EXEMPT_PRS+=("$pr")
			EXEMPT_REASONS+=("$reason")
			;;
		*) die_void "$CONF has an unknown directive: '$directive'" ;;
	esac
done < "$CONF"

[ "$conf_lines" -gt 0 ] || die_void "$CONF declares nothing — the gate would check an undefined range"
[ -n "$FLOOR" ] || die_void "$CONF declares no FLOOR — the gate would have no starting point"
git rev-parse --verify --quiet "${FLOOR}^{commit}" >/dev/null || die_void "FLOOR '$FLOOR' is not a commit in this repository"

# --- base ref (resolved, never hardcoded — this repo's default is not assumed) -------
BASE_REF="${CHANGELOG_BASE_REF:-}"
if [ -z "$BASE_REF" ]; then
	BASE_REF=$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null) || BASE_REF=""
fi
if [ -z "$BASE_REF" ]; then
	d=$(git remote show origin 2>/dev/null | awk '/HEAD branch/ {print $NF}')
	[ -n "$d" ] && BASE_REF="origin/$d"
fi
[ -n "$BASE_REF" ] || die_void "cannot resolve the base branch (no origin/HEAD, no remote) — set CHANGELOG_BASE_REF"
git rev-parse --verify --quiet "${BASE_REF}^{commit}" >/dev/null || die_void "base ref '$BASE_REF' is not a commit (shallow clone?)"

printf '  changelog : %s\n  config    : %s\n  range     : %s..%s\n' \
	"$CHANGELOG" "$CONF" "$(git rev-parse --short "$FLOOR")" "$BASE_REF"

# --- C-3  self-test: the commit matcher can fire, and can decline ------------------
# Samples are built in the SAME '%h<TAB>%s' shape the real scan consumes. Testing a bare
# subject here is what let the un-anchored-past-the-SHA bug through: the self-test passed on
# an input shape the gate never actually sees.
TAB=$'\t'
grep -qE "$LINE_RE" <<< "abc1234${TAB}feat(scope): a sample subject (#1)" \
	|| die_void "C-3 self-test: the matcher did NOT match a known feat line — C-1 would scan an empty set"
grep -qE "$LINE_RE" <<< "abc1234${TAB}fix: another sample (#2)" \
	|| die_void "C-3 self-test: the matcher did NOT match a known fix line"
grep -qE "$LINE_RE" <<< "abc1234${TAB}fix(ui+docs): a scoped sample (#3)" \
	|| die_void "C-3 self-test: the matcher did NOT match a scoped fix line"
grep -qE "$LINE_RE" <<< "abc1234${TAB}feat!: a breaking sample (#4)" \
	|| die_void "C-3 self-test: the matcher did NOT match a breaking feat line"
if grep -qE "$LINE_RE" <<< "abc1234${TAB}docs(handoff): a sample that must NOT match (#5)"; then
	die_void "C-3 self-test: the matcher matched a docs line — it would demand entries for docs PRs"
fi
if grep -qE "$LINE_RE" <<< "abc1234${TAB}chore: a sample that must NOT match (#6)"; then
	die_void "C-3 self-test: the matcher matched a chore line"
fi
# The subject must not be matched loosely: 'prefix feat(x): y' is not a feat commit.
if grep -qE "$LINE_RE" <<< "abc1234${TAB}revert: feat(x): a sample that must NOT match (#7)"; then
	die_void "C-3 self-test: the matcher matched mid-subject — it is not anchored to the subject start"
fi

# --- C-4  self-test: the citation lookup answers correctly in BOTH directions -------
# Positive probe is DERIVED from the file, so it cannot go stale the way a literal would.
# The lookup must understand GROUPED citations. This file's established style puts several
# PRs under one heading — "(#342, #346, #347)", "(#380, #383)" — so requiring the literal
# "(#NNN)" reports a genuinely-cited PR as missing. Measured: #380 is cited in a grouped
# heading and a "(#380)" search found nothing. Digit boundaries instead of parentheses, so
# #38 and #3800 still do not satisfy a search for #380.
# Only ENTRY HEADINGS count as a citation. Searching the whole file lets an incidental
# mention satisfy the gate: the break arm that deleted #380 from its own heading still
# passed, because an unrelated entry's prose happens to say "#380 merged, changing
# core-java sources". A PR referred to in passing is not a PR that has been written up.
HEADINGS=$(grep -E '^#{2,4} ' "$CHANGELOG")
[ -n "$HEADINGS" ] || die_void "$CHANGELOG contains no '###' entry headings — the lookup would have nothing to read"

cited_all=$(grep -oP '#\K[0-9]+' <<< "$HEADINGS" | sort -un)
[ -n "$cited_all" ] || die_void "C-4 self-test: no entry heading in $CHANGELOG cites a PR number — the lookup is unusable"
probe_present=$(head -1 <<< "$cited_all")
probe_absent=$(( $(tail -1 <<< "$cited_all") + 1000000 ))
is_cited() { grep -qP "(?<![0-9])#$1(?![0-9])" <<< "$HEADINGS"; }
is_cited "$probe_present" || die_void "C-4 self-test: lookup failed to find #$probe_present, which IS in $CHANGELOG"
if is_cited "$probe_absent"; then
	die_void "C-4 self-test: lookup claims #$probe_absent is cited, but it was constructed to be absent"
fi
# Boundary behaviour, tested on the pattern itself so it does not depend on what the file
# happens to contain: a grouped citation must satisfy the search, and a number that merely
# CONTAINS the digits must not.
cite_re() { printf '(?<![0-9])#%s(?![0-9])' "$1"; }
grep -qP "$(cite_re 380)" <<< '### A grouped heading (#380, #383) — 2026-07-31' \
	|| die_void "C-4 self-test: the lookup does not recognise a GROUPED citation"
if grep -qP "$(cite_re 380)" <<< 'a longer number #3800 appears here'; then
	die_void "C-4 self-test: the lookup matched #3800 when searching for #380 — no trailing boundary"
fi
if grep -qP "$(cite_re 380)" <<< 'a longer number #1380 appears here'; then
	die_void "C-4 self-test: the lookup matched #1380 when searching for #380 — no leading boundary"
fi

# --- discover the merged feat/fix commits in range ----------------------------------
log_out=$(git log --first-parent --format='%h%x09%s' "${FLOOR}..${BASE_REF}" 2>&1); log_rc=$?
[ "$log_rc" -eq 0 ] || die_void "git log failed for ${FLOOR}..${BASE_REF}: $log_out"

subjects=$(grep -E "$LINE_RE" <<< "$log_out")
n_total=$(grep -c . <<< "$log_out")
n_match=0
[ -n "$subjects" ] && n_match=$(grep -c . <<< "$subjects")

# An empty RANGE is legitimate right after the floor moves; an empty range where the floor
# is not the tip is not, and neither is a matcher that found nothing among many commits.
if [ "$n_total" -eq 0 ] && [ "$(git rev-parse "$FLOOR")" != "$(git rev-parse "$BASE_REF")" ]; then
	die_void "the range ${FLOOR}..${BASE_REF} is empty although FLOOR is not the base tip — discovery is broken"
fi

# --- C-1  every discovered feat/fix commit is cited ---------------------------------
declare -A IN_RANGE_UNCITED=()
n_cited=0
n_exempted=0
while IFS=$'\t' read -r sha subject; do
	[ -z "${sha:-}" ] && continue
	pr=$(grep -oP "$PR_RE" <<< "$subject" | grep -oP '[0-9]+')
	if [ -z "$pr" ]; then
		void "$sha carries no PR number and cannot be attributed: $subject"
		continue
	fi
	if is_cited "$pr"; then
		n_cited=$((n_cited + 1))
		continue
	fi
	IN_RANGE_UNCITED["$pr"]="$sha $subject"
done <<< "$subjects"

# --- C-2  exemptions must still be needed ------------------------------------------
declare -A HONOURED=()
i=0
while [ "$i" -lt "${#EXEMPT_PRS[@]}" ]; do
	pr="${EXEMPT_PRS[$i]}"; reason="${EXEMPT_REASONS[$i]}"; i=$((i + 1))
	if [ -n "${IN_RANGE_UNCITED[$pr]+x}" ]; then
		HONOURED["$pr"]=1
		n_exempted=$((n_exempted + 1))
		printf '  EXEMPT #%s — %s\n' "$pr" "$reason"
	elif is_cited "$pr"; then
		fail "C-2 EXEMPT #$pr is STALE: it is cited in $CHANGELOG anyway — remove the exemption"
	else
		fail "C-2 EXEMPT #$pr is STALE: it is not an uncited feat/fix commit in ${FLOOR}..${BASE_REF} — remove the exemption"
	fi
done

for pr in "${!IN_RANGE_UNCITED[@]}"; do
	[ -n "${HONOURED[$pr]+x}" ] && continue
	fail "C-1 PR #$pr is a merged feat/fix with no entry in $CHANGELOG — ${IN_RANGE_UNCITED[$pr]}"
done

# --- summary ------------------------------------------------------------------------
printf '  commits   : %s in range, %s feat/fix\n' "$n_total" "$n_match"
printf '  cited     : %s\n  exempt    : %s (%s declared)\n' "$n_cited" "$n_exempted" "${#EXEMPT_PRS[@]}"
printf '  self-test : C-3 matcher fires and declines · C-4 lookup found #%s, rejected #%s\n' \
	"$probe_present" "$probe_absent"

if [ "$FAIL" -ne 0 ]; then
	echo "FAILED: the changelog does not cover every merged feat/fix PR (see above)." >&2
	echo "        Add an entry to $CHANGELOG, or declare an EXEMPT row with a reason in $CONF." >&2
	exit 1
fi
if [ "$VOID" -ne 0 ]; then
	echo "VOID: the gate could not complete its checks (see above) — treat as unverified, not as a pass." >&2
	exit 2
fi
printf 'PASS: all %s merged feat/fix PR(s) since %s are cited in %s (%s exempt).\n' \
	"$n_match" "$(git rev-parse --short "$FLOOR")" "$CHANGELOG" "$n_exempted"
