#!/usr/bin/env bash
# check-playwright-mobile-contract.sh — a project that claims mobile must be able to FEEL touch.
#
# WHY THIS EXISTS (#503)
#
#   frontend/playwright.config.ts declared:
#
#       { name: "mobile", use: { browserName: "chromium",
#                                viewport: { width: 390, height: 844 },
#                                isMobile: true } }
#
#   `hasTouch` is absent. Chromium then reports `pointer: fine` and
#   `maxTouchPoints: 0`, so `(pointer: coarse)` never matches. Every defect whose
#   symptom is "behaves like a mouse on a touch device" is invisible to the repo's
#   own mobile suite BY CONSTRUCTION — the suite goes green over surface it cannot
#   observe.
#
#   That is not hypothetical. It is why nobody found the ungated `hover:` problem:
#   65 Tailwind hover utilities, 1 gated, and a tap on a real phone latches the
#   hover state so the button stays highlighted after being pressed. The blind
#   instrument and the defect it hid were filed as ONE issue for that reason.
#
# WHAT IT CHECKS
#
#   Every Playwright project whose `use` block sets `isMobile` must also set
#   `hasTouch`, OR spread a real device descriptor (`...devices[...]`), which
#   carries `hasTouch` itself.
#
# WHAT IT DOES NOT CHECK — stated rather than implied:
#   This is a CONFIG-SHAPE gate. It reads text; it cannot prove the emulation took
#   effect. `frontend/e2e/mobile-instrument-contract.spec.ts` asserts the resulting
#   `matchMedia("(pointer: coarse)")` state in a real browser. This gate exists
#   because it runs in seconds on a CI runner with no stack, catching the omission
#   at PR time instead of after a 20-minute suite. Neither replaces the other:
#   a structural check can pass while the function is still broken.
#
# Requires: bash, awk. Exit codes: 0 = clean, 1 = a mobile project cannot feel touch, 2 = VOID.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG="$REPO_ROOT/frontend/playwright.config.ts"

void() { echo "VOID: $*" >&2; exit 2; }

command -v awk >/dev/null 2>&1 || void "awk not found"
[ -f "$CONFIG" ] || void "playwright.config.ts not found at $CONFIG"

# awk walks the `projects: [` array, slices it at each `name:` key, and reports one
# line per project: "<name> <has_isMobile> <has_hasTouch> <has_deviceSpread>".
REPORT="$(awk '
    /projects:[[:space:]]*\[/ { inproj = 1; next }
    inproj {
        # Leaving the array: a "]" at the same indent level that opened it.
        if ($0 ~ /^[[:space:]]*\][[:space:]]*,?[[:space:]]*$/ && depth <= 0) { inproj = 0; next }

        # Strip line comments BEFORE matching. Without this a comment merely
        # MENTIONING hasTouch satisfies the gate — and this config is heavily
        # commented, including a comment block explaining why hasTouch matters.
        # A gate that its own rationale can satisfy is vacuous.
        code = $0
        sub(/\/\/.*/, "", code)

        if (code ~ /name:[[:space:]]*"/) {
            if (cur != "") print cur, mob, touch, dev
            cur = code
            sub(/.*name:[[:space:]]*"/, "", cur)
            sub(/".*/, "", cur)
            mob = 0; touch = 0; dev = 0
        }
        if (cur != "") {
            if (code ~ /isMobile[[:space:]]*:/)            mob   = 1
            if (code ~ /hasTouch[[:space:]]*:/)            touch = 1
            if (code ~ /\.\.\.[[:space:]]*devices\[/)      dev   = 1
        }
    }
    END { if (cur != "") print cur, mob, touch, dev }
' "$CONFIG")"

[ -n "$REPORT" ] || void "no Playwright projects parsed out of $CONFIG — a scan with no inputs cannot prove anything"

PROJECTS=0
VIOLATIONS=0
echo "check-playwright-mobile-contract"
echo "  config : frontend/playwright.config.ts"

while read -r name mob touch dev; do
    [ -n "$name" ] || continue
    PROJECTS=$(( PROJECTS + 1 ))
    if [ "$mob" = "1" ] && [ "$touch" != "1" ] && [ "$dev" != "1" ]; then
        echo "  BLIND    project \"$name\" sets isMobile with no hasTouch and no device descriptor"
        VIOLATIONS=$(( VIOLATIONS + 1 ))
    else
        label="ok"
        [ "$mob" = "1" ] && label="mobile, touch-capable"
        [ "$mob" = "1" ] && [ "$dev" = "1" ] && label="mobile, via device descriptor"
        echo "  ok       project \"$name\" ($label)"
    fi
done <<< "$REPORT"

echo "  parsed : $PROJECTS project(s), $VIOLATIONS blind"

if [ "$VIOLATIONS" -ne 0 ]; then
    echo ""
    echo "FAIL: a Playwright project claims to be mobile but cannot report a coarse pointer." >&2
    echo "      Chromium with isMobile and no hasTouch reports pointer:fine and" >&2
    echo "      maxTouchPoints:0, so (pointer: coarse) never matches and the project is" >&2
    echo "      blind to every touch-specific defect it exists to catch (#503)." >&2
    echo "      Fix: add hasTouch: true, or spread a real descriptor from \`devices\`." >&2
    exit 1
fi

echo "PASS: every mobile Playwright project can report touch."
