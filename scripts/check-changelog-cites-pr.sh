#!/usr/bin/env bash
#
# check-changelog-cites-pr.sh — assert THIS pull request is cited in docs/CHANGELOG.md.
#
# WHY THIS EXISTS. check-changelog-contract.sh C-1 asserts that every MERGED feat/fix PR is
# cited in the changelog. It is correct and it works, but it is structurally incapable of
# firing on the PR that breaks it: on a PR branch the current PR is not merged, so C-1
# correctly finds nothing wrong and goes red later, on main, in front of whoever opens the
# next unrelated PR. Measured 2026-08-05/06: SIX instances in two days — #568, #572, #575,
# #577, #573, #574 — each one an entry heading citing the ISSUE the PR closes rather than
# the PR itself. Filed as #579; this is the complementary PR-TIME half.
#
# It does not replace C-1. C-1 still catches a PR that merged with no entry at all (this
# check can be skipped by a fork, a re-run, or a missing number). The two ask different
# questions at different times and both are wanted.
#
# WHAT IT ENFORCES.
#   P-1  If the PR title is a feat/fix subject, an entry heading in docs/CHANGELOG.md cites
#        this PR's number. Only ENTRY HEADINGS count, matching C-1's rule exactly — an
#        incidental mention in prose is not a write-up.
#   P-2  SELF-TEST of the citation lookup in BOTH directions, on the pattern itself: a
#        grouped citation must satisfy it, and a number that merely CONTAINS the digits must
#        not. Proves "not cited" is a real absence and not a broken search.
#   P-3  SELF-TEST of the subject matcher: it must match feat/fix and decline docs/chore.
#        A matcher that silently stopped matching would make P-1 skip every PR.
#
# EXEMPTIONS reuse check-changelog-contract's config, so a PR exempted there is exempted
# here and there is exactly one list to keep.
#
# FAIL-CLOSED. Missing grep -P / unreadable or empty changelog / no PR number / a non-numeric
# PR number / a changelog with no entry headings / either self-test failing => exit 2 (VOID),
# never 0. A real FAIL (1) outranks a VOID (2), matching the sibling gates.
#
# Usage:
#   scripts/check-changelog-cites-pr.sh --pr 574 --title "fix(ci): count test blocks (#291)"
#   PR_NUMBER=574 PR_TITLE='fix: ...' scripts/check-changelog-cites-pr.sh
#
# Falsification (run BOTH directions before trusting this gate):
#   scripts/check-changelog-cites-pr.sh --pr 574 --title 'fix: x'   # cited   -> expect 0
#   scripts/check-changelog-cites-pr.sh --pr 999999 --title 'fix: x' # absent -> expect 1
#   scripts/check-changelog-cites-pr.sh --pr 999999 --title 'docs: x' # skip   -> expect 0
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || { printf 'VOID: cannot cd to repo root\n' >&2; exit 2; }

CHANGELOG="${CHANGELOG_FILE:-docs/CHANGELOG.md}"
CONF="${CHANGELOG_CONF:-scripts/gates/changelog-contract.conf}"
PR_NUMBER="${PR_NUMBER:-}"
PR_TITLE="${PR_TITLE:-}"

while [ $# -gt 0 ]; do
	case "$1" in
		--pr)    PR_NUMBER="${2:-}"; shift 2 ;;
		--title) PR_TITLE="${2:-}";  shift 2 ;;
		*) printf 'VOID: unknown argument %s\n' "$1" >&2; exit 2 ;;
	esac
done

die_void() { printf 'VOID: %s\n' "$1" >&2; exit 2; }

printf 'check-changelog-cites-pr  (%s)\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# --- preconditions -----------------------------------------------------------------
printf 'x' | grep -qP 'x' 2>/dev/null || die_void "grep does not support -P (PCRE)"
[ -r "$CHANGELOG" ] || die_void "$CHANGELOG is missing or unreadable"
[ -s "$CHANGELOG" ] || die_void "$CHANGELOG is empty — nothing could be cited in it"
[ -n "$PR_NUMBER" ] || die_void "no PR number given (--pr or PR_NUMBER) — cannot check anything"
grep -qE '^[0-9]+$' <<< "$PR_NUMBER" || die_void "PR number '$PR_NUMBER' is not numeric"
[ -n "$PR_TITLE" ] || die_void "no PR title given (--title or PR_TITLE) — cannot tell if this PR owes an entry"

# --- P-3  self-test: the subject matcher fires and declines -------------------------
# Identical to check-changelog-contract's SUBJECT_RE. Kept in step deliberately: if the two
# disagree, a PR is either checked twice or by neither.
SUBJECT_RE='^(feat|fix)(\([^)]*\))?!?: '
grep -qE "$SUBJECT_RE" <<< 'feat(scope): sample' || die_void "P-3: matcher did not match a feat subject"
grep -qE "$SUBJECT_RE" <<< 'fix: sample'         || die_void "P-3: matcher did not match a fix subject"
grep -qE "$SUBJECT_RE" <<< 'feat!: sample'       || die_void "P-3: matcher did not match a breaking subject"
if grep -qE "$SUBJECT_RE" <<< 'docs(handoff): sample'; then
	die_void "P-3: matcher matched a docs subject — it would demand entries for docs PRs"
fi
if grep -qE "$SUBJECT_RE" <<< 'chore: sample'; then
	die_void "P-3: matcher matched a chore subject"
fi
if grep -qE "$SUBJECT_RE" <<< 'revert: fix(x): sample'; then
	die_void "P-3: matcher matched mid-subject — it is not anchored"
fi

# --- exemptions (shared with check-changelog-contract) ------------------------------
if [ -r "$CONF" ]; then
	while IFS= read -r line; do
		case "$line" in ''|[[:space:]]*'#'*|'#'*) continue ;; esac
		[ "$(awk '{print $1}' <<< "$line")" = "EXEMPT" ] || continue
		if [ "$(awk '{print $2}' <<< "$line")" = "$PR_NUMBER" ]; then
			printf '  PR #%s is EXEMPT per %s — nothing to check.\n' "$PR_NUMBER" "$CONF"
			exit 0
		fi
	done < "$CONF"
fi

# --- does this PR owe an entry at all? ----------------------------------------------
if ! grep -qE "$SUBJECT_RE" <<< "$PR_TITLE"; then
	printf '  PR #%s title is not a feat/fix subject — no entry required.\n    title: %s\n' \
		"$PR_NUMBER" "$PR_TITLE"
	exit 0
fi

# --- P-2  self-test: the citation lookup answers correctly in BOTH directions -------
# Tested on the PATTERN, not on whatever the file happens to contain, so it cannot go
# vacuous when the file changes.
cite_re() { printf '(?<![0-9])#%s(?![0-9])' "$1"; }
grep -qP "$(cite_re 380)" <<< '### A grouped heading (#380, #383) — 2026-07-31' \
	|| die_void "P-2: the lookup does not recognise a GROUPED citation"
if grep -qP "$(cite_re 380)" <<< 'a longer number #3800 appears here'; then
	die_void "P-2: the lookup matched #3800 when searching for #380 — no trailing boundary"
fi
if grep -qP "$(cite_re 380)" <<< 'a longer number #1380 appears here'; then
	die_void "P-2: the lookup matched #1380 when searching for #380 — no leading boundary"
fi

# --- P-1  is THIS PR cited in an entry heading? -------------------------------------
HEADINGS=$(grep -E '^#{2,4} ' "$CHANGELOG")
[ -n "$HEADINGS" ] || die_void "$CHANGELOG contains no '###' entry headings — the lookup would have nothing to read"

printf '  changelog : %s\n  pr        : #%s\n  title     : %s\n' "$CHANGELOG" "$PR_NUMBER" "$PR_TITLE"

if grep -qP "$(cite_re "$PR_NUMBER")" <<< "$HEADINGS"; then
	printf 'PASS: docs/CHANGELOG.md has an entry heading citing #%s.\n' "$PR_NUMBER"
	exit 0
fi

cat >&2 <<EOF
FAIL: PR #$PR_NUMBER is a feat/fix change, but no entry heading in $CHANGELOG cites (#$PR_NUMBER).

  This is the mistake that has redded main six times: the heading cites the ISSUE the PR
  closes instead of the PR itself. Both are welcome — add the PR number alongside:

      ### Some change (#<issue>) (#$PR_NUMBER) — YYYY-MM-DD

  Only ENTRY HEADINGS count, matching check-changelog-contract C-1. A mention in prose
  does not satisfy either gate.

  If this PR genuinely needs no entry, add an EXEMPT row with a reason to $CONF.
EOF
exit 1
