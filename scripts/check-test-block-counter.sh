#!/usr/bin/env bash
#
# check-test-block-counter.sh — prove scripts/count-test-blocks.mjs can still FAIL.
#
# WHY THIS EXISTS
#
#   count-test-blocks.mjs replaced a grep that was wrong in both directions and never
#   went red, because the only thing checking it was itself (issue #291). Replacing
#   one unfalsifiable measurement with another would be no improvement, so every
#   behaviour the counter claims is asserted here against a fixture whose answer is
#   known by construction — INCLUDING the refusals. A counter that silently guesses a
#   number it cannot derive is exactly the failure that produced #291.
#
#   The fixtures live in scripts/fixtures/test-block-counter/ and end in
#   `.fixture.ts`, deliberately outside every path family docs-freshness.sh counts
#   and outside every runner's testMatch, so proving the counter can never change the
#   number the counter is proving.
#
# WHAT IT ASSERTS
#   COUNT arms  — a fixture whose block count is known by construction must produce
#                 exactly that number, and rc 0.
#   VOID arms   — a construct the counter cannot resolve (a computed .each table, a
#                 tagged-template table, describe.each, an xit alias, an unmodelled
#                 modifier chain, a loop-declared head) must produce rc 2 and print
#                 nothing on stdout.
#
#   The loop arms (issue #582) come in matched pairs on purpose. A refusal is only
#   worth having if it can also NOT fire: loops-inside-blocks.fixture.ts is the same
#   loop keywords arranged inside the block instead of around it and must still
#   count, and loop-scope-by-family.fixture.ts is ONE file that must VOID as jest and
#   count as playwright. Without those two, a loop check that simply refused every
#   file containing the word `for` would pass every arm here.
#
# Exit codes: 0 = every arm behaved, 1 = an arm misbehaved, 2 = VOID (cannot run).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COUNTER="$ROOT/scripts/count-test-blocks.mjs"
FIXTURES="$ROOT/scripts/fixtures/test-block-counter"
cd "$ROOT"

FAILED=0
ARMS=0

command -v node >/dev/null 2>&1 || { echo "VOID: node is not installed" >&2; exit 2; }
[ -f "$COUNTER" ]  || { echo "VOID: $COUNTER is missing" >&2; exit 2; }
[ -d "$FIXTURES" ] || { echo "VOID: $FIXTURES is missing" >&2; exit 2; }

FIXTURE_COUNT=$(find "$FIXTURES" -maxdepth 1 -name '*.fixture.ts' | wc -l | tr -d ' ')
[ "$FIXTURE_COUNT" -ge 15 ] || {
	echo "VOID: only $FIXTURE_COUNT fixture(s) found — the arms below name 15. A shrinking" >&2
	echo "      fixture set is how a self-test quietly stops testing anything." >&2
	exit 2
}

# Assignment and $? are separate statements throughout: `out=$(cmd); echo …; rc=$?`
# reports the ECHO's status, which is 0 essentially always, so every arm would read
# green (trap_exit_code_read_after_echo).
expect_count() { # <family> <fixture-basename> <expected-blocks>
	local out rc got
	ARMS=$((ARMS + 1))
	out=$(node "$COUNTER" --family "$1" "$FIXTURES/$2" 2>&1)
	rc=$?
	if [ "$rc" -ne 0 ]; then
		printf 'ARM FAIL  count[%s] %s: rc=%s (expected 0)\n%s\n' "$1" "$2" "$rc" "$out" >&2
		FAILED=1
		return
	fi
	got=$(printf '%s' "$out" | sed -nE 's/.*"blocks":([0-9]+).*/\1/p')
	if [ "$got" != "$3" ]; then
		printf 'ARM FAIL  count[%s] %s: got %s block(s), expected %s\n' "$1" "$2" "${got:-<none>}" "$3" >&2
		FAILED=1
		return
	fi
	printf '  ok  count[%-10s] %-34s = %s\n' "$1" "$2" "$3"
}

expect_void() { # <family> <fixture-basename> <substring the message must contain>
	local out rc
	ARMS=$((ARMS + 1))
	out=$(node "$COUNTER" --family "$1" "$FIXTURES/$2" 2>&1)
	rc=$?
	if [ "$rc" -ne 2 ]; then
		printf 'ARM FAIL  void[%s] %s: rc=%s (expected 2 — it produced a number it cannot justify)\n%s\n' \
			"$1" "$2" "$rc" "$out" >&2
		FAILED=1
		return
	fi
	# grep on a here-string, never `printf | grep -q`: under pipefail grep exits at
	# the first match, the writer takes SIGPIPE, and 141 INVERTS the test.
	if ! grep -qF "$3" <<< "$out"; then
		printf 'ARM FAIL  void[%s] %s: exited 2 but for the wrong reason.\n  wanted: %s\n  got:    %s\n' \
			"$1" "$2" "$3" "$out" >&2
		FAILED=1
		return
	fi
	printf '  ok  void[%-11s] %-34s (%s)\n' "$1" "$2" "$3"
}

printf 'check-test-block-counter  (%s)\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ── COUNT arms: the number is known by construction ─────────────────────────
# Over-count direction: 3 x `/re/.test(x)`, a commented-out it(), a commented-out
# test(), and both tokens inside a string. Old regex: 8. Truth: 2.
expect_count jest       phantom-regexp.fixture.ts        2
# Under-count direction: five .each tables (3 + 4 + 2 + 3 rows) + 1 plain + 1 skip.
# Old regex: 2 (it( and it.skip( are invisible to it; only the two plain heads hit).
expect_count jest       each-tables.fixture.ts           14
expect_count vitest     each-tables.fixture.ts           14
# Playwright's dual-mode modifiers: 2 bare + 1 declared-skip; 2 skip directives,
# a fail() directive, describe/use/beforeEach/setTimeout and a /re/.test( all zero.
expect_count playwright playwright-directives.fixture.ts 3
# Loop keywords INSIDE the blocks, not around them: 2 it() + 1 test(), each
# iterating assertions in its own body. The commonest arrangement on this tree, and
# the arm that fails if the #582 refusal stops asking whether the loop ENCLOSES the
# head. Measured 2026-08-06: 25 counted Jest files carry a for/while/.forEach and
# every one of them still counts, so a refusal that could not tell the two
# arrangements apart would VOID a quarter of the suite.
expect_count jest       loops-inside-blocks.fixture.ts   3
# Same file, opposite verdicts by family — see the jest arm further down. Playwright
# counts a loop-declared test() because its oracle counts declaration sites.
expect_count playwright loop-scope-by-family.fixture.ts  3

# ── VOID arms: refusing is the required behaviour ───────────────────────────
expect_void jest void-unresolvable-each.fixture.ts "not a resolvable array literal"
expect_void jest void-imported-each.fixture.ts     "not an array literal declared in this file"
expect_void jest void-alias.fixture.ts             "unsupported test alias 'xit'"
expect_void jest void-describe-each.fixture.ts     "describe.each multiplies"
expect_void jest void-tagged-each.fixture.ts       "tagged-template"
expect_void jest void-unknown-chain.fixture.ts     "unknown jest modifier chain"
# Loop-declared heads (issue #582). Before the fix these four counted the DECLARATION
# SITE at rc=0 — the only number this counter ever guessed — and that under-count is
# what deadlocks docs-freshness against check-test-count-oracle.
#   Single quotes below, never double: the messages contain no backticks today, but a
#   double-quoted assertion string is one edit away from EXECUTING one.
expect_void jest void-loop-for.fixture.ts          'is declared inside a for-loop body'
expect_void jest void-loop-foreach.fixture.ts      'is declared inside a .forEach(...) callback'
# Braceless body — no `{` between the loop header and the head, so the bracket walk
# alone finds nothing and the direct check is the only thing that fires.
expect_void jest void-loop-while.fixture.ts        'is declared inside a while-loop body'
# The head must be refused BEFORE its chain is classified: a resolvable 2-row table
# inside a 2-iteration loop is 4 executed tests, and "2" is the confident wrong answer.
expect_void jest void-loop-each-table.fixture.ts   "'it.each(' is declared inside a for-loop body"
# The other half of the playwright COUNT arm above, on the SAME file: jest's oracle
# counts executions so this must refuse, playwright's counts declaration sites so it
# must not. One of the two arms goes red whichever way loopMultiplies is mis-set.
expect_void jest loop-scope-by-family.fixture.ts   "'test(' is declared inside a for-loop body"
# An empty input set is not a count of zero.
ARMS=$((ARMS + 1))
EMPTY_OUT=$(printf '' | node "$COUNTER" --family jest --stdin 2>&1)
EMPTY_RC=$?
if [ "$EMPTY_RC" -eq 2 ]; then
	printf '  ok  void[%-11s] %-34s (%s)\n' "jest" "<empty file list>" "0 over an empty set is not a measurement"
else
	printf 'ARM FAIL  void[jest] <empty file list>: rc=%s (expected 2)\n%s\n' "$EMPTY_RC" "$EMPTY_OUT" >&2
	FAILED=1
fi

printf '  arms     : %s\n' "$ARMS"
if [ "$ARMS" -lt 18 ]; then
	echo "VOID: only $ARMS arm(s) ran — a self-test that shrank is not a self-test." >&2
	exit 2
fi
if [ "$FAILED" -ne 0 ]; then
	echo "FAIL: scripts/count-test-blocks.mjs did not behave as documented (see above)." >&2
	exit 1
fi
printf 'PASS: count-test-blocks.mjs counted %s arms correctly, refusals included.\n' "$ARMS"
