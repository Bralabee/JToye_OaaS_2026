#!/usr/bin/env bash
# ---------------------------------------------------------------------------------
# check-e2e-typecheck.sh — type-check frontend/e2e/**, which NOTHING else does.
#
# WHY THIS EXISTS
#
#   `frontend/tsconfig.json` includes `**/*.ts` and excludes only `node_modules`, so
#   it is natural to assume `next build` type-checks the Playwright specs. It does
#   not. Measured 2026-08-07 by planting a deliberate error in a spec:
#
#     const broken: number = "this is not a number"
#
#     npm run build (next build)   -> rc=0, the file is not even mentioned
#     npx tsc --noEmit             -> rc=2, TS2322 on that line
#
#   `next build` type-checks the pages/app graph, not every file in the tsconfig
#   program. Playwright itself transpiles specs without full checking. So a type
#   error in a spec reached `main` with every gate green, and only surfaced when
#   the spec ran — or never, if the broken path was one of the declared skips.
#
#   That gap was found while REMOVING e2e/ from the frontend Docker build context
#   (#597). The removal was correct and lost nothing, because the coverage it
#   looked like it was giving up never existed. This gate creates it.
#
# WHY A SEPARATE tsconfig RATHER THAN `tsc --noEmit`
#
#   A bare `npx tsc --noEmit` over the frontend is RED at ~366 pre-existing errors —
#   jest-dom matcher typings in unit-test files that `next build` never checks. A
#   gate that is permanently red is a gate everyone learns to ignore, so it cannot
#   be the enforcement point. `frontend/tsconfig.e2e.json` extends the base config
#   and narrows `include` to `e2e/**/*.ts`, which is GREEN today (measured: 0
#   errors) and can therefore fail loudly tomorrow.
#
#   Widening this to the rest of the tree is a separate decision needing its own
#   evidence. Do not "tidy" it by pointing this gate at the base tsconfig.
#
# THE VACUITY THIS GUARDS AGAINST — measured, because the obvious version is wrong
#
#   The tempting claim is "tsc exits 0 on an empty program". It does NOT, and this
#   guard was nearly shipped with that false rationale. Measured 2026-08-07:
#
#     include matches NOTHING at all      -> tsc rc=2 (TS18003, no inputs found)
#     include matches real files, but
#     none of them under e2e/             -> tsc rc=0   <-- SILENT PASS
#
#   So tsc already fails the loud case and is blind to the quiet one. The quiet one
#   is the realistic one: a directory rename, a moved spec, or an `include` edited
#   to a path that still resolves to *something* leaves this gate reporting a
#   confident PASS over files nobody meant to check — and the e2e specs, which have
#   no other cover, silently stop being checked at all.
#
#   Hence the count is read out of the RESOLVED program (`--listFiles`, tsc's own
#   answer rather than a glob re-implemented here) and filtered to `/frontend/e2e/`.
#   Zero is VOID, never a pass. Verified in both directions: a config pointing at
#   `lib/utils.ts` gives raw tsc rc=0 and this gate rc=2.
#
# EXIT CODES
#   0 = every file under frontend/e2e type-checks
#   1 = at least one type error
#   2 = VOID — missing tooling, missing config, or a program containing ZERO
#       e2e files. "Checked nothing" is never "clean".
# ---------------------------------------------------------------------------------
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND="$REPO_ROOT/frontend"
TSCONFIG="${E2E_TSCONFIG:-tsconfig.e2e.json}"

echo "check-e2e-typecheck  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"

void() { echo "VOID: $*" >&2; exit 2; }

command -v npx >/dev/null 2>&1 || void "npx is not on PATH — cannot run tsc"
[ -d "$FRONTEND" ]                 || void "frontend directory not found at $FRONTEND"
[ -f "$FRONTEND/$TSCONFIG" ]       || void "$TSCONFIG not found in $FRONTEND — the scoped program is missing"
[ -d "$FRONTEND/e2e" ]             || void "frontend/e2e does not exist — nothing to type-check, which is not the same as clean"
[ -d "$FRONTEND/node_modules" ]    || void "frontend/node_modules is absent — run 'npm ci' in frontend/ first; tsc cannot resolve types without it"

echo "  config    : frontend/$TSCONFIG"

# --- ANTI-VACUITY: how many e2e files are actually in the resolved program? ---------
# Read from tsc's own resolved file list, not from a glob we re-implement here — the
# question is what TSC will check, and only tsc can answer that.
LIST_OUT="$(cd "$FRONTEND" && npx tsc --noEmit -p "$TSCONFIG" --listFiles 2>/dev/null)"
E2E_FILES=$(printf '%s\n' "$LIST_OUT" | grep -c "/frontend/e2e/" 2>/dev/null || true)
case "${E2E_FILES:-}" in ''|*[!0-9]*) E2E_FILES=0 ;; esac

[ "$E2E_FILES" -gt 0 ] || void \
    "the resolved program contains ZERO files under frontend/e2e. A program that resolves to OTHER files still exits 0 (measured), so without this check the gate would report a confident PASS while the e2e specs — which have no other type-check cover — went unchecked. Fix the 'include' glob in $TSCONFIG."

echo "  program   : $E2E_FILES file(s) under frontend/e2e"

# --- the check ----------------------------------------------------------------------
OUT="$(cd "$FRONTEND" && npx tsc --noEmit -p "$TSCONFIG" 2>&1)"; rc=$?

if [ "$rc" -eq 0 ]; then
    echo "PASS: $E2E_FILES e2e file(s) type-check clean."
    echo "      NOTE: 'next build' does NOT check these files — this gate is their only cover."
    exit 0
fi

echo "$OUT" | grep -E 'error TS' | head -25
n=$(printf '%s\n' "$OUT" | grep -c 'error TS' 2>/dev/null || echo "?")
echo "FAILED: $n TypeScript error(s) under frontend/e2e." >&2
echo "        Reproduce: cd frontend && npx tsc --noEmit -p $TSCONFIG" >&2
exit 1
