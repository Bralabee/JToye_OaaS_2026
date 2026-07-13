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

JAVA_TEST_METHODS=$(count_occurrences '^core-java/src/test/.*\.java$' '@Test\b')
JAVA_TEST_FILES=$(count_files_with '^core-java/src/test/.*\.java$' '@Test\b')
JAVA_CONTROLLERS=$(count_files_with '^core-java/src/main/.*\.java$' 'class [A-Za-z0-9_]*Controller\b')

SCHEMA_VERSION=$(git ls-files 'core-java/src/main/resources/db/migration/*.sql' \
	| sed -nE 's#.*/V([0-9]+)__.*#\1#p' | sort -n | tail -1)

GO_TEST_FUNCS=$(count_occurrences '.*_test\.go$' '^func Test[A-Za-z0-9_]+')
GO_TEST_FILES=$(count_files_with '.*_test\.go$' '^func Test')

JEST_BLOCKS=$(count_occurrences '^frontend/(app|components|lib|hooks|types|__tests__)/.*\.test\.tsx?$' '\b(it|test)\(')
JEST_FILES=$(count_files_with '^frontend/(app|components|lib|hooks|types|__tests__)/.*\.test\.tsx?$' '\b(it|test)\(')

PLAYWRIGHT_BLOCKS=$(count_occurrences '^frontend/e2e/.*\.spec\.ts$' '\btest\(')
PLAYWRIGHT_SPECS=$(count_files_with '^frontend/e2e/.*\.spec\.ts$' '\btest\(')

# MCP server vitest suite (mcp-server/) — the same \b(it|test)\( content-regex
# that matches Jest blocks matches vitest blocks; only the path family is new.
MCP_TEST_BLOCKS=$(count_occurrences '^mcp-server/(src|test)/.*\.(test|spec)\.ts$' '\b(it|test)\(')
MCP_TEST_FILES=$(count_files_with '^mcp-server/(src|test)/.*\.(test|spec)\.ts$' '\b(it|test)\(')

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
