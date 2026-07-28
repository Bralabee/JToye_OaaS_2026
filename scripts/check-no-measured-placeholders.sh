#!/usr/bin/env bash
# check-no-measured-placeholders.sh — no unfilled placeholder may reach shipped config (27-04, D-05).
#
# 27-04 writes its numbers as `<<MEASURED>>` until the baseline run fills them. This gate is what
# makes that convention safe to use.
#
# WHY IT IS NEEDED — the failure it prevents is invisible to everything else:
#   `${JTOYE_RABBIT_MEDIA_PREFETCH:<<MEASURED>>}` left in application.yml
#     - compiles and builds clean
#     - PASSES k8s/scripts/check-env-contract.sh, because that gate fails only on a MISSING
#       default or a LOCAL-ONLY default (localhost, guest, minioadmin, ...). A `<<MEASURED>>`
#       default is neither, so it is classified `pass by rule` and the gate exits 0.
#     - fails only at CONTAINER START, with a NumberFormatException binding the property.
#   i.e. without this gate the defect SHIPS, and is discovered by a crash-looping pod.
#
# Scope: core-java/src/main/resources/application*.yml, .env.example, and everything under k8s/.
#
# Requires: bash, grep. Exit codes: 0 = clean, 1 = a placeholder was found, 2 = VOID.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

void() { echo "VOID: $*" >&2; exit 2; }

command -v grep >/dev/null 2>&1 || void "grep not found"

# The forbidden tokens are ASSEMBLED, never written literally. A gate that spells out the string
# it forbids fires on its own definition — a known vacuous shape in this repo. Assembling them
# means this file can scan itself and stay clean.
OPEN='<''<'
CLOSE='>''>'
PATTERN="${OPEN}(MEASURED|TODO|TBD)${CLOSE}"

TARGETS=()
while IFS= read -r f; do TARGETS+=("$f"); done < <(
    ls "$REPO_ROOT"/core-java/src/main/resources/application*.yml 2>/dev/null || true
)
[ -f "$REPO_ROOT/.env.example" ] && TARGETS+=("$REPO_ROOT/.env.example")

# An empty file list is a broken locator, not a clean tree.
[ "${#TARGETS[@]}" -gt 0 ] || void "no application*.yml or .env.example found under $REPO_ROOT — a scan with no inputs cannot prove anything"
[ -d "$REPO_ROOT/k8s" ] || void "k8s/ directory not found under $REPO_ROOT"

# `| wc -l`, never a bare `grep -c`: grep -c exits 1 when the count is 0, which under `set -e`
# kills this script on the CLEAN tree — the one case it must report success for.
HITS_FILES="$( { grep -rnE "$PATTERN" "${TARGETS[@]}" || true; } | wc -l)"
HITS_K8S="$(   { grep -rnE "$PATTERN" "$REPO_ROOT/k8s" || true; } | wc -l)"
TOTAL=$(( HITS_FILES + HITS_K8S ))

echo "Placeholder scan: ${#TARGETS[@]} config file(s) + k8s/"
echo "  matches: $TOTAL"

if [ "$TOTAL" -ne 0 ]; then
    echo ""
    { grep -rnE "$PATTERN" "${TARGETS[@]}" || true; }
    { grep -rnE "$PATTERN" "$REPO_ROOT/k8s" || true; }
    echo ""
    echo "FAIL: an unfilled placeholder reached shipped config. It would build clean, pass" >&2
    echo "      check-env-contract.sh, and fail only at container start on a number parse." >&2
    exit 1
fi

echo "PASS: no unfilled placeholder in application*.yml, .env.example or k8s/."
