#!/usr/bin/env bash
#
# check-doc-metrics.sh — assert the metric numbers QUOTED IN PROSE against docs/metrics.json.
#
# WHY THIS EXISTS. scripts/docs-freshness.sh closes exactly one half of the loop: it
# recomputes the counts from the source tree and asserts them against docs/metrics.json.
# Nothing asserted the numbers a human actually reads. Its own failure message ends
# "...then update README/PROJECT.md and commit" — a prose instruction, and prose
# instructions are what fail. They failed here: README.md advertised
#
#     Total: 921 logical test invocations
#     > Documentation counts are guarded by the `docs-freshness` CI gate ...
#     > which fails the build if these numbers drift from the source tree.
#
# while the tree stood at 1851 and docs-freshness.sh was green on every commit — because
# it never opened README.md. The doc claimed a guardian it did not have. Measured
# 2026-07-30: 921 vs 1851, a drift of 930 across five test tiers, plus a whole tier
# (mcp-server vitest, 48 blocks) that README did not mention at all.
#
# WHAT IT ENFORCES. A declared table of (doc, metric-key, extraction pattern). For each row:
#   M-1  the pattern must match at least once in that doc  — so deleting the sentence to
#        dodge the gate FAILS LOUDLY instead of silently passing (a zero-match rule is the
#        classic vacuous assertion: `== 0` was already true before the change).
#   M-2  every number the pattern captures must equal docs/metrics.json's value for that key.
#
# FAIL-CLOSED. Missing jq / missing manifest / missing doc / a manifest key that is absent
# or non-numeric / a rule that captures a non-numeric token => exit 2 (VOID), never 0.
# "Could not check it" is never rendered as "checked it and it was fine".
#
# Usage:
#   scripts/check-doc-metrics.sh          # check mode (CI)
#
# Falsification (run BOTH directions before trusting this gate):
#   sed -i 's/Total: 1851/Total: 1850/' README.md && scripts/check-doc-metrics.sh; echo $?   # expect 1
#   git checkout README.md                && scripts/check-doc-metrics.sh; echo $?           # expect 0
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT/docs/metrics.json"
cd "$ROOT"

VOID=0
FAIL=0
CHECKED=0

void() { printf 'VOID: %s\n' "$1" >&2; VOID=1; }
fail() { printf 'FAIL: %s\n' "$1" >&2; FAIL=1; }

command -v jq >/dev/null 2>&1 || { void "jq is not installed — cannot read $MANIFEST"; exit 2; }
[ -f "$MANIFEST" ] || { void "$MANIFEST is missing"; exit 2; }
jq -e . "$MANIFEST" >/dev/null 2>&1 || { void "$MANIFEST is not parseable JSON"; exit 2; }

# grep -P (PCRE, for \K and lookahead) is required. BSD grep lacks it.
printf 'x' | grep -qP 'x' 2>/dev/null || { void "grep does not support -P (PCRE) — cannot extract claims"; exit 2; }

# ---------------------------------------------------------------------------
# RULES: <doc>|<metrics.json key>|<PCRE whose MATCH is the claimed number>
#
# Patterns use \K (drop everything before it) and lookahead so the match is the
# bare integer. Backticks in the docs are matched with `.` to keep quoting sane.
# Every row is a promise that this claim EXISTS in that doc — see M-1 above.
# ---------------------------------------------------------------------------
RULES=$(cat <<'RULES_EOF'
README.md|total_logical_invocations|tests-\K[0-9]+(?=%20logical%20invocations)
README.md|total_logical_invocations|Total: \K[0-9]+(?= logical test invocations)
README.md|java_test_methods|Backend \(Java\): \K[0-9]+(?= .@Test. methods across)
README.md|java_test_files|Backend \(Java\): [0-9]+ .@Test. methods across \K[0-9]+
README.md|go_test_funcs|Edge \(Go\): \K[0-9]+(?= .Test\*. functions across)
README.md|go_test_files|Edge \(Go\): [0-9]+ .Test\*. functions across \K[0-9]+
README.md|jest_blocks|Frontend \(Jest\): \K[0-9]+(?= .it/test. blocks across)
README.md|jest_files|Frontend \(Jest\): [0-9]+ .it/test. blocks across \K[0-9]+
README.md|playwright_blocks|Frontend E2E \(Playwright\): \K[0-9]+(?= .test\(\). blocks across)
README.md|playwright_specs|Frontend E2E \(Playwright\): [0-9]+ .test\(\). blocks across \K[0-9]+
README.md|mcp_test_blocks|MCP server \(vitest\): \K[0-9]+(?= .it/test. blocks across)
README.md|mcp_test_files|MCP server \(vitest\): [0-9]+ .it/test. blocks across \K[0-9]+
README.md|schema_version|Database schema version: \*\*V\K[0-9]+
CLAUDE.md|schema_version|Current schema version: V\K[0-9]+
CLAUDE.md|total_logical_invocations|project standard is \K[0-9]+(?= logical invocations)
CLAUDE.md|java_test_methods|\K[0-9]+(?= Java .@Test. methods across)
CLAUDE.md|java_test_files|[0-9]+ Java .@Test. methods across \K[0-9]+
CLAUDE.md|jest_blocks|\K[0-9]+(?= Jest .it/test. blocks across)
CLAUDE.md|jest_files|[0-9]+ Jest .it/test. blocks across \K[0-9]+
CLAUDE.md|go_test_funcs|\K[0-9]+(?= top-level Go .Test\*. funcs across)
CLAUDE.md|go_test_files|top-level Go .Test\*. funcs across \K[0-9]+
CLAUDE.md|playwright_blocks|\K[0-9]+(?= Playwright .test\(\). blocks across)
CLAUDE.md|playwright_specs|Playwright .test\(\). blocks across \K[0-9]+
CLAUDE.md|mcp_test_blocks|\K[0-9]+(?= MCP-server vitest .it/test. blocks across)
CLAUDE.md|mcp_test_files|MCP-server vitest .it/test. blocks across \K[0-9]+
AGENTS.md|schema_version|Current schema version: V\K[0-9]+
AGENTS.md|total_logical_invocations|project standard is \K[0-9]+(?= logical invocations)
AGENTS.md|java_test_methods|\K[0-9]+(?= Java .@Test. methods across)
AGENTS.md|java_test_files|[0-9]+ Java .@Test. methods across \K[0-9]+
AGENTS.md|jest_blocks|\K[0-9]+(?= Jest .it/test. blocks across)
AGENTS.md|jest_files|[0-9]+ Jest .it/test. blocks across \K[0-9]+
AGENTS.md|go_test_funcs|\K[0-9]+(?= top-level Go .Test\*. funcs across)
AGENTS.md|go_test_files|top-level Go .Test\*. funcs across \K[0-9]+
AGENTS.md|playwright_blocks|\K[0-9]+(?= Playwright .test\(\). blocks across)
AGENTS.md|playwright_specs|Playwright .test\(\). blocks across \K[0-9]+
AGENTS.md|mcp_test_blocks|\K[0-9]+(?= MCP-server vitest .it/test. blocks across)
AGENTS.md|mcp_test_files|MCP-server vitest .it/test. blocks across \K[0-9]+
RULES_EOF
)

[ -n "$RULES" ] || { void "the rule table is empty — nothing would be checked"; exit 2; }

printf 'check-doc-metrics  (%s)\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '  manifest : %s\n' "docs/metrics.json"

DOCS_SEEN=""
while IFS='|' read -r doc key pat; do
	[ -z "${doc:-}" ] && continue
	case " $DOCS_SEEN " in *" $doc "*) ;; *) DOCS_SEEN="$DOCS_SEEN $doc" ;; esac

	if [ ! -f "$doc" ]; then
		void "$doc: declared in the rule table but the file does not exist"
		continue
	fi

	# Expected value from the manifest. A missing or non-numeric key is a VOID, not a pass.
	expected=$(jq -r --arg k "$key" 'if has($k) then (.[$k]|tostring) else "__ABSENT__" end' "$MANIFEST")
	if [ "$expected" = "__ABSENT__" ]; then
		void "$doc [$key]: key absent from docs/metrics.json"
		continue
	fi
	case "$expected" in ''|*[!0-9]*) void "$doc [$key]: manifest value '$expected' is not a plain integer"; continue ;; esac

	# Capture every claim this rule matches. Assign on its own line so $? is grep's,
	# not an echo's (trap_exit_code_read_after_echo).
	found=$(grep -ohP "$pat" "$doc" 2>/dev/null)
	grc=$?
	if [ "$grc" -gt 1 ]; then
		void "$doc [$key]: grep -P errored (rc=$grc) on pattern: $pat"
		continue
	fi

	# M-1: the claim must exist. A rule that matches nothing is a vacuous assertion.
	if [ -z "$found" ]; then
		fail "$doc [$key]: rule matched NOTHING — the claim was removed or reworded. Pattern: $pat"
		continue
	fi

	# M-2: every captured number must equal the manifest value.
	while read -r got; do
		[ -z "$got" ] && continue
		CHECKED=$((CHECKED + 1))
		case "$got" in ''|*[!0-9]*) void "$doc [$key]: captured non-numeric token '$got'"; continue ;; esac
		if [ "$got" != "$expected" ]; then
			fail "$doc [$key]: doc says $got, docs/metrics.json says $expected"
		fi
	done <<< "$found"
done <<< "$RULES"

RULE_COUNT=$(printf '%s\n' "$RULES" | grep -c '|')
DOC_COUNT=$(printf '%s' "$DOCS_SEEN" | wc -w | tr -d ' ')
printf '  rules    : %s across %s doc(s)\n' "$RULE_COUNT" "$DOC_COUNT"
printf '  claims   : %s extracted and compared\n' "$CHECKED"

# A run that compared nothing is not a pass, however green it looks.
if [ "$CHECKED" -eq 0 ]; then
	void "0 claims compared — the gate cannot have verified anything"
fi

if [ "$FAIL" -ne 0 ]; then
	echo "FAIL: prose metric claim(s) disagree with docs/metrics.json (see above). Fix the doc, or run scripts/docs-freshness.sh --write if the tree legitimately changed." >&2
	exit 1
fi
if [ "$VOID" -ne 0 ]; then
	echo "VOID: the gate could not complete its checks (see above) — treat as unverified, not as a pass." >&2
	exit 2
fi

printf 'PASS: all %s prose metric claim(s) across %s doc(s) match docs/metrics.json.\n' "$CHECKED" "$DOC_COUNT"
