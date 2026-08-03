#!/usr/bin/env bash
# check-e2e-baseurl-contract.sh — playwright.config.ts is the ONLY base-URL authority.
#
# WHY THIS EXISTS (#505)
#
#   `frontend/e2e/vendor-refund-flow.spec.ts` defaulted to `http://localhost:3100`
#   while the config and eleven sibling specs defaulted to `:3000`. Nothing on the
#   Compose stack publishes 3100 (measured 2026-08-03: frontend 3000, core-java
#   9090, edge-go 8089, mcp-server 9100).
#
#   The damage was not "four red tests". It was that the four blocks were recorded
#   as DELIBERATE SKIPS — "refund E2E stays skipped, needs Stripe keys". That was
#   true of the skip condition INSIDE the test, but the test never reached it:
#   `page.goto` failed against an unpublished port first, so the blocks FAILED.
#   `check-e2e-skip-budget` was then reasoning about a skip-set membership that did
#   not hold, and the whole suite's skip accounting was wrong.
#
#   Root cause was a STALE COMMENT, not a typo. `playwright.config.ts` carried
#   "Dev env uses port 3100 (MCP server holds 3000)" — both halves false — and that
#   folklore propagated into nine files' prose before one file turned it into code.
#
# WHAT IT CHECKS
#
#   Every `PLAYWRIGHT_BASE_URL` fallback under frontend/e2e/ must equal the fallback
#   declared in playwright.config.ts. The expected value is DERIVED from the config,
#   never written here — a gate that hardcodes the port it defends goes stale the
#   same way the comment did, and would fire on its own definition.
#
# WHAT IT DELIBERATELY DOES NOT CHECK — stated so this is not read as more coverage
# than it is:
#   - Non-frontend hosts (Keycloak :8085, core-java :9090). Those are separate
#     services with their own env vars; they are not base-URL divergence.
#   - Whether a spec navigates relatively or absolutely. Both are legal as long as
#     the resolved base agrees. Relative is preferred (see vendor-refund-flow).
#   - Runtime reachability. That is check-e2e-skip-budget's job, after a real run.
#
# WHY IT SLURPS INSTEAD OF SCANNING LINES
#
#   The fallback is written across TWO lines in at least two specs:
#
#       const BASE_URL =
#         process.env.PLAYWRIGHT_BASE_URL ?? "http://localhost:3100"
#
#       process.env.PLAYWRIGHT_BASE_URL ||
#         "http://localhost:3000"
#
#   A line-oriented grep sees `PLAYWRIGHT_BASE_URL` with no URL on that line and
#   matches nothing — i.e. it would report a CLEAN tree for precisely the shape
#   that broke. Each file is therefore newline-flattened before matching.
#
# Requires: bash, grep, tr. Exit codes: 0 = clean, 1 = divergence found, 2 = VOID.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG="$REPO_ROOT/frontend/playwright.config.ts"
E2E_DIR="$REPO_ROOT/frontend/e2e"

void() { echo "VOID: $*" >&2; exit 2; }

command -v grep >/dev/null 2>&1 || void "grep not found"
command -v tr   >/dev/null 2>&1 || void "tr not found"
[ -f "$CONFIG" ]  || void "playwright.config.ts not found at $CONFIG — cannot derive the authority"
[ -d "$E2E_DIR" ] || void "e2e directory not found at $E2E_DIR"

# The env var name is assembled so this gate never matches itself if it is ever
# moved under a scanned directory.
VAR="PLAYWRIGHT""_BASE_URL"
FALLBACK_RE="${VAR}[[:space:]]*(\?\?|\|\|)[[:space:]]*\"[^\"]+\""

flatten() { tr '\n' ' ' < "$1"; }

# --- 1. Derive the authority from the config -------------------------------------
CONFIG_MATCHES="$( { flatten "$CONFIG" | grep -oE "$FALLBACK_RE" || true; } )"
CONFIG_COUNT="$( [ -n "$CONFIG_MATCHES" ] && printf '%s\n' "$CONFIG_MATCHES" | wc -l || echo 0 )"

[ "$CONFIG_COUNT" -eq 1 ] || void \
  "expected exactly 1 ${VAR} fallback in playwright.config.ts, found $CONFIG_COUNT — the authority is ambiguous, so no comparison below would mean anything"

# The quoted literal is the only double-quoted run in the match.
EXPECTED="$(printf '%s' "$CONFIG_MATCHES" | grep -oE '"[^"]+"' | tr -d '"')"
[ -n "$EXPECTED" ] || void "could not extract the fallback URL from playwright.config.ts"

# --- 2. Enumerate the specs ------------------------------------------------------
SPECS=()
while IFS= read -r f; do SPECS+=("$f"); done < <(find "$E2E_DIR" -name '*.spec.ts' -type f | sort)
[ "${#SPECS[@]}" -gt 0 ] || void "no *.spec.ts found under $E2E_DIR — a scan with no inputs cannot prove anything"

echo "check-e2e-baseurl-contract"
echo "  authority : playwright.config.ts -> $EXPECTED"
echo "  scanned   : ${#SPECS[@]} spec file(s)"

# --- 3. Compare ------------------------------------------------------------------
VIOLATIONS=0
DECLARED=0
for spec in "${SPECS[@]}"; do
    matches="$( { flatten "$spec" | grep -oE "$FALLBACK_RE" || true; } )"
    [ -n "$matches" ] || continue
    while IFS= read -r m; do
        [ -n "$m" ] || continue
        DECLARED=$(( DECLARED + 1 ))
        url="$(printf '%s' "$m" | grep -oE '"[^"]+"' | tr -d '"')"
        if [ "$url" != "$EXPECTED" ]; then
            echo ""
            echo "  DIVERGENT  ${spec#"$REPO_ROOT"/}"
            echo "             declares $url, config says $EXPECTED"
            VIOLATIONS=$(( VIOLATIONS + 1 ))
        fi
    done <<< "$matches"
done

echo "  declared  : $DECLARED local fallback(s), $VIOLATIONS divergent"

if [ "$VIOLATIONS" -ne 0 ]; then
    echo ""
    echo "FAIL: a spec declares a base URL the config does not." >&2
    echo "      A spec that points at an unpublished port does not skip — it FAILS at" >&2
    echo "      navigation, before its own skip condition is ever evaluated, and the" >&2
    echo "      suite's skip accounting silently becomes wrong (#505)." >&2
    echo "      Fix: delete the local constant and navigate with RELATIVE paths so" >&2
    echo "      Playwright resolves against the config's baseURL." >&2
    exit 1
fi

echo "PASS: every ${VAR} fallback under frontend/e2e/ agrees with playwright.config.ts."
