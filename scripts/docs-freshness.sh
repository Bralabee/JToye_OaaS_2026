#!/usr/bin/env bash
#
# docs-freshness.sh — keep documentation counts honest.
#
# Recomputes the project's test/controller/schema metrics from source and
# compares them against the committed manifest at docs/metrics.json. In the
# default (check) mode it exits non-zero on any mismatch so CI fails when the
# docs drift from reality. Run with --write to regenerate the manifest after a
# legitimate change (then commit the result and update README/PROJECT.md).
#
# Usage:
#   scripts/docs-freshness.sh            # check mode (CI)
#   scripts/docs-freshness.sh --write    # regenerate docs/metrics.json
#
# Determinism: counts are derived from git-tracked files only, so untracked
# scratch files and node_modules never affect the result.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT/docs/metrics.json"

cd "$ROOT"

# List working-tree files (tracked + untracked-but-not-ignored) matching a
# grep-style path regex. Using --exclude-standard keeps node_modules/.next and
# other gitignored paths out while still counting not-yet-committed tests.
tracked() { git ls-files --cached --others --exclude-standard | grep -E "$1" || true; }

# Count regex occurrences across a set of files (0 when the set is empty).
count_occurrences() { # <file-path-regex> <content-regex>
	local files
	files="$(tracked "$1")"
	[ -z "$files" ] && { echo 0; return; }
	# shellcheck disable=SC2086
	grep -hoE "$2" $files 2>/dev/null | wc -l | tr -d ' '
}

count_files_with() { # <file-path-regex> <content-regex>
	local files
	files="$(tracked "$1")"
	[ -z "$files" ] && { echo 0; return; }
	# shellcheck disable=SC2086
	grep -lE "$2" $files 2>/dev/null | wc -l | tr -d ' '
}

# ---------------------------------------------------------------------------
# JS/TS test blocks (Jest, Playwright, vitest) — issue #291.
#
# These three families are NOT counted with grep, because grep was wrong in both
# directions at once and the errors partly cancelled, which is exactly why nothing
# ever went red over it. `\b(it|test)\(` matched `RegExp.prototype.test(` (7 phantom
# Jest blocks, 5 phantom Playwright blocks on 2026-08-05) and could not see a
# table-driven `it.each([...])` at all (9 sites, 51 executed tests, contributing
# zero). Manifest said 745; `npx jest` executed 789.
#
# scripts/count-test-blocks.mjs masks comments/strings/regexes, rejects member
# access, and expands `.each` tables. It exits 2 on anything it cannot resolve, and
# that VOID is propagated here rather than degraded into a number.
# ---------------------------------------------------------------------------
JS_COUNTER="$ROOT/scripts/count-test-blocks.mjs"
command -v node >/dev/null 2>&1 || {
	echo "ERROR: node is not on PATH — scripts/count-test-blocks.mjs cannot run, so the" >&2
	echo "       Jest/Playwright/vitest block counts are UNKNOWN. Refusing to emit a number." >&2
	exit 2
}
[ -f "$JS_COUNTER" ] || { echo "ERROR: $JS_COUNTER is missing." >&2; exit 2; }

# Sets the globals CJ_BLOCKS and CJ_FILES. Deliberately NOT a command substitution:
# an `exit 2` inside `$( )` kills only the subshell, so the caller would read empty
# values and carry on — a VOID silently degraded into a number. Assignment and $? are
# separate statements so the status read is node's, not an echo's or a pipeline's.
CJ_BLOCKS=""
CJ_FILES=""
count_js() { # <family> <file-path-regex>
	local files out rc
	CJ_BLOCKS=""
	CJ_FILES=""
	files="$(tracked "$2")"
	if [ -z "$files" ]; then
		echo "ERROR: no files matched '$2' — an empty set is not a count of 0." >&2
		exit 2
	fi
	# `out=$(failing-cmd)` under `set -e` aborts the script THERE, before the
	# diagnostic below can print — measured: rc=2 with an empty log, a VOID with no
	# reason, which is barely better than a wrong number. The `&& rc=0 || rc=$?`
	# form keeps the status without arming errexit, and $? is read on its own
	# statement so it is node's and not an echo's.
	out=$(printf '%s\n' "$files" | node "$JS_COUNTER" --family "$1" --stdin 2>&1) && rc=0 || rc=$?
	if [ "$rc" -ne 0 ]; then
		echo "ERROR: count-test-blocks.mjs could not count family '$1' (rc=$rc):" >&2
		printf '%s\n' "$out" >&2
		echo "       Treat this as UNVERIFIED, not as a pass. Extend the counter's POLICY." >&2
		exit 2
	fi
	CJ_BLOCKS=$(printf '%s' "$out" | sed -nE 's/.*"blocks":([0-9]+).*/\1/p')
	CJ_FILES=$(printf '%s' "$out" | sed -nE 's/.*"files":([0-9]+).*/\1/p')
	case "$CJ_BLOCKS" in ''|*[!0-9]*) echo "ERROR: unparseable counter output for '$1': $out" >&2; exit 2 ;; esac
	case "$CJ_FILES" in ''|*[!0-9]*) echo "ERROR: unparseable counter output for '$1': $out" >&2; exit 2 ;; esac
}

JAVA_TEST_METHODS=$(count_occurrences '^core-java/src/test/.*\.java$' '@Test\b')
JAVA_TEST_FILES=$(count_files_with '^core-java/src/test/.*\.java$' '@Test\b')
JAVA_CONTROLLERS=$(count_files_with '^core-java/src/main/.*\.java$' 'class [A-Za-z0-9_]*Controller\b')

SCHEMA_VERSION=$(git ls-files 'core-java/src/main/resources/db/migration/*.sql' \
	| sed -nE 's#.*/V([0-9]+)__.*#\1#p' | sort -n | tail -1)

GO_TEST_FUNCS=$(count_occurrences '.*_test\.go$' '^func Test[A-Za-z0-9_]+')
GO_TEST_FILES=$(count_files_with '.*_test\.go$' '^func Test')

count_js jest '^frontend/(app|components|lib|hooks|types|__tests__)/.*\.test\.tsx?$'
JEST_BLOCKS="$CJ_BLOCKS"; JEST_FILES="$CJ_FILES"

count_js playwright '^frontend/e2e/.*\.spec\.ts$'
PLAYWRIGHT_BLOCKS="$CJ_BLOCKS"; PLAYWRIGHT_SPECS="$CJ_FILES"

# MCP server vitest suite (mcp-server/) — same counter, vitest's modifier policy.
count_js vitest '^mcp-server/(src|test)/.*\.(test|spec)\.ts$'
MCP_TEST_BLOCKS="$CJ_BLOCKS"; MCP_TEST_FILES="$CJ_FILES"

TOTAL=$((JAVA_TEST_METHODS + JEST_BLOCKS + GO_TEST_FUNCS + PLAYWRIGHT_BLOCKS + MCP_TEST_BLOCKS))

read -r -d '' COMPUTED <<JSON || true
{
  "java_test_methods": ${JAVA_TEST_METHODS},
  "java_test_files": ${JAVA_TEST_FILES},
  "java_controllers": ${JAVA_CONTROLLERS},
  "schema_version": ${SCHEMA_VERSION},
  "go_test_funcs": ${GO_TEST_FUNCS},
  "go_test_files": ${GO_TEST_FILES},
  "jest_blocks": ${JEST_BLOCKS},
  "jest_files": ${JEST_FILES},
  "playwright_blocks": ${PLAYWRIGHT_BLOCKS},
  "playwright_specs": ${PLAYWRIGHT_SPECS},
  "mcp_test_blocks": ${MCP_TEST_BLOCKS},
  "mcp_test_files": ${MCP_TEST_FILES},
  "total_logical_invocations": ${TOTAL}
}
JSON

if [ "${1:-}" = "--write" ]; then
	printf '%s\n' "$COMPUTED" > "$MANIFEST"
	echo "Wrote $MANIFEST:"
	cat "$MANIFEST"
	exit 0
fi

if [ ! -f "$MANIFEST" ]; then
	echo "ERROR: $MANIFEST is missing. Run: scripts/docs-freshness.sh --write" >&2
	exit 1
fi

# Normalise both sides (strip whitespace) and diff.
normalise() { tr -d ' \t\n' ; }
COMMITTED_NORM="$(normalise < "$MANIFEST")"
COMPUTED_NORM="$(printf '%s' "$COMPUTED" | normalise)"

if [ "$COMMITTED_NORM" != "$COMPUTED_NORM" ]; then
	echo "ERROR: documentation metrics are stale (docs/metrics.json != source reality)." >&2
	echo "--- committed (docs/metrics.json) ---" >&2
	cat "$MANIFEST" >&2
	echo "--- computed from source ---" >&2
	printf '%s\n' "$COMPUTED" >&2
	echo >&2
	echo "Fix by running: scripts/docs-freshness.sh --write   (then update README/PROJECT.md and commit)" >&2
	exit 1
fi

echo "docs-freshness OK: metrics match source (total logical invocations: ${TOTAL})."
