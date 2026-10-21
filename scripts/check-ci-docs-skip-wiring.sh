#!/usr/bin/env bash
# check-ci-docs-skip-wiring.sh — the doc-only skip in ci-cd.yaml skips ONLY what it is meant to.
#
# WHY THIS EXISTS
#   scripts/ci-docs-only.sh decides whether a change is documentation-only; ci-cd.yaml's
#   `changes` job runs it and four jobs consume the answer. The classifier has its own
#   --selftest. Nothing else asserts the WIRING, and the wiring is where this feature can
#   quietly go wrong in either direction:
#     - too much is skipped: someone gates `lint` or `security-scan` on docs_only, and a doc
#       change (or, if the classifier is ever wrong, a code change) loses a required gate;
#     - the skip fails OPEN: the Integration Tests condition loses its `!cancelled()`, so a
#       FAILED `changes` job skips the required suite and GitHub reports the skip as success
#       ("A job depends on a failed job -> the dependent job is skipped and may not block
#       merging"), merging a code change with no integration run;
#     - the skip stops propagating: a status function (always()/!cancelled()) is added to a
#       deploy job and it deploys an image that was never built.
#
# WHAT IT ASSERTS (on .github/workflows/ci-cd.yaml; comment lines are ignored)
#   W-1  a job `changes` exists, has no job-level `if:`, and outputs docs_only;
#   W-2  EXACTLY these jobs reference docs_only outside comments: integration-tests,
#        build-and-push, deploy-staging, deploy-production (plus `changes`, which defines it).
#        Any other job referencing it is a gate that can now be skipped; a missing one is a
#        skip that no longer happens;
#   W-3  each of the four lists `changes` in its needs and carries
#        needs.changes.outputs.docs_only != 'true' in its job-level `if:`;
#   W-4  integration-tests' `if:` also contains !cancelled() (so a failed or skipped `changes`
#        RUNS the suite), and is not a bare `success()`/`always()` form that would ignore the
#        output;
#   W-5  build-and-push, deploy-staging, deploy-production and staging-edge-probe contain no
#        always()/cancelled()/failure() in their job-level `if:` (it would defeat the skip's
#        propagation through needs), and staging-edge-probe still needs deploy-staging.
#
# Exit codes: 0 = holds, 1 = a rule fails, 2 = VOID (file/jobs not found, extractor blind).
# --selftest plants one break per rule in a copy of the workflow and requires each to be caught,
# and requires the real file to pass (the control).
#
# Usage: scripts/check-ci-docs-skip-wiring.sh [--file WORKFLOW] | --selftest

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/ci-cd.yaml"
SELFTEST=0

GATED="integration-tests build-and-push deploy-staging deploy-production"
PROPAGATING="build-and-push deploy-staging deploy-production staging-edge-probe"

void() { printf 'VOID: %s\n' "$*" >&2; exit 2; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --file)
      [ "$#" -ge 2 ] || void "--file needs a value"
      WORKFLOW="$2"; shift 2 ;;
    --selftest) SELFTEST=1; shift ;;
    -h|--help)  sed -n '2,36p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) void "unknown argument '$1'" ;;
  esac
done

# extract FILE -> one TAB record per job:  name <TAB> needs <TAB> if <TAB> docs_only-refs <TAB> has-outputs-docs_only
# "-" stands for an empty field (a tab IFS collapses empties and shifts the rest).
extract() {
  awk '
    function flush() {
      if (job != "") printf "%s\t%s\t%s\t%d\t%d\n", job, (needs == "" ? "-" : needs), (cond == "" ? "-" : cond), refs, outdef
    }
    /^jobs:[ \t]*$/ { injobs = 1; next }
    !injobs { next }
    /^  [A-Za-z0-9_-]+:[ \t]*$/ {
      flush(); job = $0; sub(/^  /, "", job); sub(/:[ \t]*$/, "", job)
      needs = ""; cond = ""; refs = 0; outdef = 0; next
    }
    job == "" { next }
    /^[ \t]*#/ { next }
    {
      line = $0
      if (match(line, /^    needs: */)) { needs = substr(line, RLENGTH + 1); sub(/[ \t]+$/, "", needs); if (needs == "") needs = "MULTILINE" }
      if (match(line, /^    if: */))    { cond = substr(line, RLENGTH + 1); sub(/[ \t]+$/, "", cond) }
      if (line ~ /docs_only/) refs++
      if (line ~ /^      docs_only:/) outdef = 1
    }
    END { flush() }
  ' "$1"
}

# check_workflow FILE -> 0 / 1 / 2
check_workflow() {
  local file="$1" recs n bad=0 j rec needs cond refs outdef have_refs=""
  [ -f "$file" ] || { echo "VOID: workflow file '$file' not found" >&2; return 2; }
  recs="$(extract "$file")" || { echo "VOID: awk failed reading $file" >&2; return 2; }
  n="$(printf '%s\n' "$recs" | awk 'NF { c++ } END { print c + 0 }')"
  if [ "$n" -lt 10 ]; then
    echo "VOID: only $n job record(s) extracted from $file - the extractor is blind or the file is not the workflow; nothing was evaluated" >&2
    return 2
  fi

  rec_of() { awk -F'\t' -v j="$1" '$1 == j { print; exit }' <<< "$recs"; }

  # W-1
  rec="$(rec_of changes)"
  if [ -z "$rec" ]; then
    echo "FAIL W-1: no job named 'changes'" >&2; return 1
  fi
  IFS=$'\t' read -r _ needs cond refs outdef <<< "$rec"
  if [ "$cond" != "-" ]; then
    echo "FAIL W-1: job 'changes' has a job-level if: '$cond' - the classifier itself must always run" >&2; bad=1
  fi
  if [ "$outdef" != 1 ]; then
    echo "FAIL W-1: job 'changes' does not define the output docs_only" >&2; bad=1
  fi

  # W-2: which jobs reference docs_only
  have_refs="$(awk -F'\t' '$1 != "changes" && $4 > 0 { print $1 }' <<< "$recs" | sort | tr '\n' ' ')"
  for j in $have_refs; do
    case " $GATED " in
      *" $j "*) ;;
      *) echo "FAIL W-2: job '$j' references docs_only but is not one of the expensive jobs ($GATED) - a cheap gate would be skipped on a docs-only change" >&2; bad=1 ;;
    esac
  done
  for j in $GATED; do
    case " $have_refs " in
      *" $j "*) ;;
      *) echo "FAIL W-2: expensive job '$j' no longer references docs_only - the doc-only skip does not reach it" >&2; bad=1 ;;
    esac
  done

  # W-3 / W-4
  for j in $GATED; do
    rec="$(rec_of "$j")"
    [ -n "$rec" ] || { echo "FAIL W-3: job '$j' not found" >&2; bad=1; continue; }
    IFS=$'\t' read -r _ needs cond refs outdef <<< "$rec"
    if [ "$needs" = MULTILINE ]; then
      echo "FAIL W-3: job '$j' has a multi-line needs: this gate cannot read it; use the inline form" >&2; bad=1
    elif ! [[ "$needs" =~ (^|[^A-Za-z0-9_-])changes([^A-Za-z0-9_-]|$) ]]; then
      echo "FAIL W-3: job '$j' does not list 'changes' in needs (needs: $needs) - needs.changes.outputs would be empty" >&2; bad=1
    fi
    if [[ "$cond" != *"needs.changes.outputs.docs_only != 'true'"* ]]; then
      echo "FAIL W-3: job '$j' job-level if: '$cond' lacks needs.changes.outputs.docs_only != 'true'" >&2; bad=1
    fi
    if [ "$j" = integration-tests ]; then
      if [[ "$cond" != *"!cancelled()"* ]]; then
        echo "FAIL W-4: integration-tests if: '$cond' lacks !cancelled() - if 'changes' FAILS, the implicit success() skips this REQUIRED check, GitHub reports the skip as success, and a code change merges with no integration run" >&2; bad=1
      fi
    fi
  done

  # W-5
  for j in $PROPAGATING; do
    rec="$(rec_of "$j")"
    [ -n "$rec" ] || { echo "FAIL W-5: job '$j' not found" >&2; bad=1; continue; }
    IFS=$'\t' read -r _ needs cond refs outdef <<< "$rec"
    if [[ "$cond" == *"always()"* || "$cond" == *"cancelled()"* || "$cond" == *"failure()"* ]]; then
      echo "FAIL W-5: job '$j' if: '$cond' carries a status function - a skipped need would no longer skip it" >&2; bad=1
    fi
  done
  rec="$(rec_of staging-edge-probe)"
  if [ -n "$rec" ]; then
    IFS=$'\t' read -r _ needs cond refs outdef <<< "$rec"
    if ! [[ "$needs" =~ (^|[^A-Za-z0-9_-])deploy-staging([^A-Za-z0-9_-]|$) ]]; then
      echo "FAIL W-5: staging-edge-probe no longer needs deploy-staging (needs: $needs) - it would probe a rollout that did not happen" >&2; bad=1
    fi
  fi

  [ "$bad" -eq 0 ] || return 1
  echo "OK   doc-only skip reaches exactly: $GATED; integration-tests fails closed; skip propagates to staging-edge-probe"
  return 0
}

if [ "$SELFTEST" -eq 1 ]; then
  [ -f "$WORKFLOW" ] || void "workflow $WORKFLOW not found"
  st="$(mktemp -d)" || void "mktemp -d failed"
  trap 'rm -rf "$st"' EXIT
  fails=0
  arm() { # arm NAME WANT_RC FILE [TOKEN]: TOKEN must appear in the output, so an arm fails for the RIGHT reason
    local name="$1" want="$2" f="$3" token="${4:-}" rc=0 why=""
    check_workflow "$f" >"$st/$name.out" 2>&1 || rc=$?
    if [ -n "$token" ]; then
      grep -qF -- "$token" "$st/$name.out" || why=" but its output never says '$token'"
    fi
    if [ "$rc" -eq "$want" ] && [ -z "$why" ]; then echo "PASS $name rc=$rc${token:+ ($token)}"
    else echo "FAIL $name rc=$rc (want $want)$why: $(tail -2 "$st/$name.out" | tr '\n' ' ')"; fails=$((fails + 1)); fi
  }
  # plant NAME SED-SCRIPT: one edit to a copy; the copy must DIFFER from the original, or the
  # arm would pass because nothing was planted.
  plant() {
    local name="$1" script="$2" token="$3"
    sed -E "$script" "$WORKFLOW" > "$st/$name.yaml"
    if cmp -s "$WORKFLOW" "$st/$name.yaml"; then
      echo "FAIL $name: the planting edit changed nothing (the pattern no longer matches the workflow)"; fails=$((fails + 1)); return
    fi
    arm "$name" 1 "$st/$name.yaml" "$token"
  }
  arm real 0 "$WORKFLOW"
  plant drop-cancelled   "/^  integration-tests:/,/^  [a-z0-9-]+:\$/ s/^(    if: \\$\\{\\{ )!cancelled\\(\\) && /\\1/" "FAIL W-4"
  plant gate-lint        "/^  lint:/,/^  [a-z0-9-]+:\$/ s/^(    runs-on: ubuntu-latest)\$/\\1\n    if: needs.changes.outputs.docs_only != 'true'/" "FAIL W-2: job 'lint'"
  plant drop-deploy-term "/^  deploy-staging:/,/^  [a-z0-9-]+:\$/ s/ && needs\\.changes\\.outputs\\.docs_only != 'true'\$//" "FAIL W-3: job 'deploy-staging' job-level if"
  plant drop-bp-need     "/^  build-and-push:/,/^  [a-z0-9-]+:\$/ s/^(    needs: \\[)changes, /\\1/" "FAIL W-3: job 'build-and-push' does not list 'changes'"
  plant probe-always     "/^  staging-edge-probe:/,/^  [a-z0-9-]+:\$/ s/^(    if: )github\\.ref/\\1always() \\&\\& github.ref/" "FAIL W-5: job 'staging-edge-probe' if"
  plant changes-gated    "/^  changes:/,/^  [a-z0-9-]+:\$/ s/^(    runs-on: ubuntu-latest)\$/\\1\n    if: github.event_name == 'push'/" "FAIL W-1: job 'changes' has a job-level if"
  # Blind extractor: a file that is not the workflow is VOID, never a pass.
  printf 'name: x\njobs:\n  a:\n    runs-on: x\n' > "$st/blind.yaml"
  arm blind-extractor 2 "$st/blind.yaml"
  if [ "$fails" -eq 0 ]; then echo "ci-docs-skip-wiring selftest: all arms behaved"; exit 0; fi
  echo "ci-docs-skip-wiring selftest: $fails arm(s) FAILED" >&2; exit 1
fi

check_workflow "$WORKFLOW"
exit $?
