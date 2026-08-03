#!/usr/bin/env bash
# ci-lane-cost.sh — which CI lane does this change fall in, and does batching pay?
#
# NOT a gate. Deliberately NOT named check-*.sh, so the `scripts/check-*.sh` gate
# loop does not pick it up. It answers a planning question, not a correctness one.
#
# WHY THIS EXISTS
#
#   The cost of a PR in this repo is bimodal, and the two modes are 15x apart.
#   Measured from three PRs merged 2026-08-03, per-job:
#
#     #509  batch/core-java-444-486-484     Integration Tests  45 min   -> ~45 min total
#     #508  batch/frontend-458-459-467-463  Integration Tests   0 min   -> ~3 min total
#     #510  batch/infra-438-439-441         Integration Tests   0 min   -> ~3 min total
#
#   The 45 minutes is the Testcontainers suite, and it is PATH-FILTERED. A PR that
#   touches none of the trigger paths skips it (the job still reports SUCCESS on
#   purpose, so it stays a satisfiable required check).
#
#   The consequence people get wrong in both directions:
#     - Batching several changes into one PR saves ~45 min EACH in the expensive
#       lane. Eight java-labelled issues shipped singly is ~6 hours of CI.
#     - Batching in a cheap lane saves ~3 min and costs a harder review. It is
#       not worth it. "Batch everything" is as wrong as "batch nothing".
#
# WHY IT PARSES THE WORKFLOW INSTEAD OF LISTING THE PATHS
#
#   The trigger paths live in .github/workflows/ci-cd.yaml. A copy of that list in
#   this script would be a second source of truth that goes stale silently the
#   first time someone edits the filter — and the failure would be this tool
#   confidently reporting "cheap" for a PR that is about to cost 45 minutes.
#   So the list is READ from the workflow every run, and a parse that finds
#   nothing is a VOID, never an empty (and therefore always-cheap) answer.
#
# USAGE
#   scripts/ci-lane-cost.sh                 # current branch vs its merge-base with the default branch
#   scripts/ci-lane-cost.sh <base-ref>      # vs an explicit base
#   scripts/ci-lane-cost.sh --files a b c   # classify an explicit file list (for planning work not yet written)
#
# OUTPUT
#   A human report, plus a machine-readable `LANE=expensive|cheap` line.
#
# Requires: bash, git, awk. Exit codes: 0 = report produced, 2 = VOID.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/ci-cd.yaml"

void() { echo "VOID: $*" >&2; exit 2; }

command -v git >/dev/null 2>&1 || void "git not found"
command -v awk >/dev/null 2>&1 || void "awk not found"
[ -f "$WORKFLOW" ] || void "workflow not found at $WORKFLOW — cannot derive the trigger paths"

# --- 1. Read the trigger paths out of the workflow's `integration:` filter -------
# The block looks like:
#     integration:
#       - 'core-java/**'
#       - 'build.gradle.kts'
PATTERNS=()
while IFS= read -r p; do PATTERNS+=("$p"); done < <(
    awk '
        function indent_of(s,   t) { t = s; sub(/[^[:space:]].*$/, "", t); return length(t) }

        /^[[:space:]]*integration:[[:space:]]*$/ { inblk = 1; key_indent = indent_of($0); next }

        inblk {
            # Blank lines inside the block are tolerated but must not end it silently.
            if ($0 !~ /[^[:space:]]/) next

            # A list item belongs to this block ONLY if it is indented DEEPER than the
            # `integration:` key. Without this the parser walks straight out of the
            # filter and into the next workflow step, whose `- name: ...` line is also
            # a dash item — measured: it captured
            # "name: Skip notice (integration suite not affected by this PR)" as a path.
            # An over-greedy parse here adds phantom patterns; an under-greedy one drops
            # real ones and reports EXPENSIVE work as cheap. Anchor on indentation.
            if (indent_of($0) <= key_indent) { inblk = 0; next }

            if ($0 ~ /^[[:space:]]*-[[:space:]]*.+$/) {
                line = $0
                sub(/^[[:space:]]*-[[:space:]]*/, "", line)
                gsub(/^['"'"'"]|['"'"'"][[:space:]]*$/, "", line)
                print line
                next
            }
        }
    ' "$WORKFLOW"
)

[ "${#PATTERNS[@]}" -gt 0 ] || void \
  "parsed 0 trigger paths out of the integration filter in ci-cd.yaml — an empty pattern list would classify EVERY change as cheap, which is the dangerous direction"

# --- 2. Work out which files to classify ----------------------------------------
FILES=()
MODE=""
if [ "${1:-}" = "--files" ]; then
    shift
    [ "$#" -gt 0 ] || void "--files given with no paths"
    FILES=("$@")
    MODE="explicit file list"
else
    BASE="${1:-}"
    if [ -z "$BASE" ]; then
        DEFAULT_REF="$(git -C "$REPO_ROOT" symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null || true)"
        [ -n "$DEFAULT_REF" ] || void "could not resolve origin/HEAD — pass a base ref explicitly"
        BASE="$DEFAULT_REF"
    fi
    git -C "$REPO_ROOT" rev-parse --verify --quiet "$BASE" >/dev/null || void "base ref '$BASE' does not resolve"
    while IFS= read -r f; do [ -n "$f" ] && FILES+=("$f"); done < <(
        git -C "$REPO_ROOT" diff --name-only "$BASE"...HEAD
    )
    MODE="diff $BASE...HEAD"
fi

if [ "${#FILES[@]}" -eq 0 ]; then
    echo "ci-lane-cost"
    echo "  scope   : $MODE"
    echo "  changed : 0 files — nothing to classify"
    echo "LANE=cheap"
    exit 0
fi

# --- 3. Classify -----------------------------------------------------------------
matches_pattern() {
    local file="$1" pat
    for pat in "${PATTERNS[@]}"; do
        case "$pat" in
            */\*\*) [ "${file#"${pat%\*\*}"}" != "$file" ] && return 0 ;;
            *\*)    [ "${file#"${pat%\*}"}" != "$file" ] && return 0 ;;
            *)      [ "$file" = "$pat" ] && return 0 ;;
        esac
    done
    return 1
}

TRIGGERS=()
for f in "${FILES[@]}"; do
    matches_pattern "$f" && TRIGGERS+=("$f")
done

echo "ci-lane-cost"
echo "  scope        : $MODE"
echo "  trigger set  : ${#PATTERNS[@]} path pattern(s), read from ci-cd.yaml"
echo "  changed      : ${#FILES[@]} file(s)"
echo "  triggering   : ${#TRIGGERS[@]} file(s)"

if [ "${#TRIGGERS[@]}" -gt 0 ]; then
    echo ""
    for f in "${TRIGGERS[@]}"; do echo "    + $f"; done
    echo ""
    echo "  LANE: EXPENSIVE — the Testcontainers suite will RUN."
    echo "    Baseline 45 min (measured on PR #509, 2026-08-03; re-measure with"
    echo "    gh run view <id> --json jobs)."
    echo "    BATCHING PAYS HERE. Every additional PR in this lane costs another"
    echo "    full suite, so group confirmed work — but keep anything you are"
    echo "    unsure about in its own PR: a red run costs 45 min to retry and"
    echo "    would block its siblings."
    echo "LANE=expensive"
else
    echo ""
    echo "  LANE: CHEAP — the Testcontainers suite path-skips (reports SUCCESS by design)."
    echo "    Baseline ~3 min (measured on #508/#510), dominated by Run Tests."
    echo "    Varies: PR #513 measured 12 min because the Playwright BROWSER CACHE"
    echo "    missed — 'Install Playwright chromium' took 633s against 27s on #508,"
    echo "    while every other step matched within seconds. That is infrastructure"
    echo "    variance, not diff size, and it does not change the decision: even a"
    echo "    cache-miss cheap run is ~4x below the expensive lane."
    echo "    BATCHING BUYS ~NOTHING HERE. Ship these separately for an easier"
    echo "    review and a smaller blast radius; the CI saving is minutes."
    echo "LANE=cheap"
fi
