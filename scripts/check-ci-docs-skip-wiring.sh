#!/usr/bin/env bash
# check-ci-docs-skip-wiring.sh — the doc-only skip in ci-cd.yaml skips ONLY what it is meant to.
#
# WHY THIS EXISTS
#   scripts/ci-docs-only.sh decides whether a change is documentation-only; ci-cd.yaml's
#   `changes` job runs it and other jobs consume the answer. The classifier has its own
#   --selftest. Nothing else asserts the WIRING, and the wiring is where this feature can
#   quietly go wrong in either direction:
#     - too much is skipped: someone gates `lint` or `security-scan` on docs_only, and a doc
#       change (or, if the classifier is ever wrong, a code change) loses a required gate;
#     - the skip fails OPEN: the required Integration Tests job depends on `changes`, so a
#       FAILED `changes` job makes GitHub skip it, and GitHub reports a skipped job as success
#       ("A job depends on a failed job -> the dependent job is skipped and may not block
#       merging"), merging a code change with no integration run;
#     - the skip stops propagating: a status function (always()/!cancelled()) is added to a
#       deploy job and it deploys an image that was never built.
#
# WHAT IT ASSERTS (on .github/workflows/ci-cd.yaml; comment lines are ignored)
#   Conditions are judged by STRUCTURE (top-level `&&` conjuncts, with a top-level `||` rejected
#   because it changes what the conjunct binds to), never by substring: `success() && !cancelled()
#   && ...` contains `!cancelled()` and is exactly the fail-open form.
#
#   W-1  a job `changes` exists, has no job-level `if:`, and outputs docs_only;
#   W-2  EXACTLY the jobs in SKIPPING plus integration-tests reference docs_only outside
#        comments (plus `changes`, which defines it). Any other job referencing it is a gate
#        that can now be skipped; a missing one is a skip that no longer happens;
#   W-3  each SKIPPING job lists `changes` in its needs and has needs.changes.outputs.docs_only
#        != 'true' as a top-level conjunct of its job-level `if:`;
#   W-4  integration-tests (a REQUIRED check) must never be skipped on docs_only and must RUN
#        when `changes` fails: it lists `changes` in needs, its job-level `if:` is exactly
#        `!cancelled()` (or `always()`) - not absent, not carrying `success()`, not carrying a
#        docs_only term - and every step gated on the path filter (steps.filter.outputs.
#        integration) carries needs.changes.outputs.docs_only != 'true' as its own top-level
#        conjunct, with no top-level `||` (at least MIN_GATED_STEPS such steps, or the check is
#        blind and VOID);
#   W-5  jobs in PROPAGATING contain no always()/cancelled()/failure() in their job-level `if:`
#        (it would defeat the skip's propagation through needs), and staging-edge-probe still
#        needs deploy-staging.
#
# Exit codes: 0 = holds, 1 = a rule fails, 2 = VOID (file/jobs not found, extractor blind).
# --selftest plants one break per rule in a copy of the workflow and requires each to be caught
# FOR ITS NAMED REASON, and requires the real file to pass (the control).
#
# Usage: scripts/check-ci-docs-skip-wiring.sh [--file WORKFLOW] | --selftest

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/ci-cd.yaml"
SELFTEST=0

SKIPPING="build-and-push deploy-staging deploy-production"
PROPAGATING="build-and-push deploy-staging deploy-production staging-edge-probe"
MIN_GATED_STEPS=7
TERM="needs.changes.outputs.docs_only != 'true'"

void() { printf 'VOID: %s\n' "$*" >&2; exit 2; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --file)
      [ "$#" -ge 2 ] || void "--file needs a value"
      WORKFLOW="$2"; shift 2 ;;
    --selftest) SELFTEST=1; shift ;;
    -h|--help)  sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
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

# steps_of FILE JOB -> one TAB record per step:  idx <TAB> name <TAB> if   ("-" for empty)
steps_of() {
  awk -v job="$2" '
    function flush() { if (idx > 0) printf "%d\t%s\t%s\n", idx, (name == "" ? "-" : name), (cond == "" ? "-" : cond) }
    $0 == "  " job ":" { injob = 1; next }
    injob && /^  [A-Za-z0-9_-]+:/ { flush(); idx = 0; injob = 0 }
    !injob { next }
    /^[ \t]*#/ { next }
    /^      - / { flush(); idx++; name = ""; cond = "" }
    idx == 0 { next }
    {
      line = $0
      if (match(line, /^      - name: */)) { name = substr(line, RLENGTH + 1) }
      else if (match(line, /^        name: */)) { name = substr(line, RLENGTH + 1) }
      if (match(line, /^        if: */)) { cond = substr(line, RLENGTH + 1); sub(/[ \t]+$/, "", cond) }
    }
    END { flush() }
  ' "$1"
}

# conjuncts EXPR -> prints each top-level `&&` operand on its own line, whitespace-trimmed, with
# an outer ${{ }} removed. A top-level `||` prints the marker line @@TOP-LEVEL-OR@@ first: then
# the "conjuncts" are not conjuncts of the whole expression, and a caller must refuse it.
conjuncts() {
  local e="$1"
  e="${e#\$\{\{}"; e="${e%\}\}}"
  awk -v e="$e" '
    function trim(x) { sub(/^[ \t]+/, "", x); sub(/[ \t]+$/, "", x); return x }
    BEGIN {
      depth = 0; inq = 0; cur = ""; n = length(e); toplevel_or = 0
      for (i = 1; i <= n; i++) {
        c = substr(e, i, 1)
        if (inq) { cur = cur c; if (c == "\047") inq = 0; continue }
        if (c == "\047") { inq = 1; cur = cur c; continue }
        if (c == "(") depth++
        if (c == ")") depth--
        if (depth == 0 && substr(e, i, 2) == "||") { toplevel_or = 1 }
        if (depth == 0 && substr(e, i, 2) == "&&") { out[++k] = trim(cur); cur = ""; i++; continue }
        cur = cur c
      }
      out[++k] = trim(cur)
      if (toplevel_or) print "@@TOP-LEVEL-OR@@"
      for (j = 1; j <= k; j++) print out[j]
    }'
}

# has_conjunct EXPR TERM -> 0 if TERM is a top-level conjunct and the expression has no top-level ||
has_conjunct() {
  local list
  list="$(conjuncts "$1")"
  grep -qxF -- "@@TOP-LEVEL-OR@@" <<< "$list" && return 1
  grep -qxF -- "$2" <<< "$list"
}

# normalise a job-level condition: drop ${{ }} and all whitespace
norm() { local e="$1"; e="${e#\$\{\{}"; e="${e%\}\}}"; printf '%s' "$e" | tr -d ' \t'; }

# check_workflow FILE -> 0 / 1 / 2
check_workflow() {
  local file="$1" recs n bad=0 j rec needs cond refs outdef have_refs="" steps nsteps_gated=0
  [ -f "$file" ] || { echo "VOID: workflow file '$file' not found" >&2; return 2; }
  recs="$(extract "$file")" || { echo "VOID: awk failed reading $file" >&2; return 2; }
  n="$(printf '%s\n' "$recs" | awk 'NF { c++ } END { print c + 0 }')"
  if [ "$n" -lt 10 ]; then
    echo "VOID: only $n job record(s) extracted from $file - the extractor is blind or the file is not the workflow; nothing was evaluated" >&2
    return 2
  fi

  rec_of() { awk -F'\t' -v j="$1" '$1 == j { print; exit }' <<< "$recs"; }
  needs_has() { [[ "$1" =~ (^|[^A-Za-z0-9_-])$2([^A-Za-z0-9_-]|$) ]]; }

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
    case " $SKIPPING integration-tests " in
      *" $j "*) ;;
      *) echo "FAIL W-2: job '$j' references docs_only but is not one of the expected jobs ($SKIPPING integration-tests) - a cheap gate would be skipped on a docs-only change" >&2; bad=1 ;;
    esac
  done
  for j in $SKIPPING integration-tests; do
    case " $have_refs " in
      *" $j "*) ;;
      *) echo "FAIL W-2: job '$j' no longer references docs_only - the doc-only skip does not reach it" >&2; bad=1 ;;
    esac
  done

  # W-3
  for j in $SKIPPING; do
    rec="$(rec_of "$j")"
    [ -n "$rec" ] || { echo "FAIL W-3: job '$j' not found" >&2; bad=1; continue; }
    IFS=$'\t' read -r _ needs cond refs outdef <<< "$rec"
    if [ "$needs" = MULTILINE ]; then
      echo "FAIL W-3: job '$j' has a multi-line needs: this gate cannot read it; use the inline form" >&2; bad=1
    elif ! needs_has "$needs" changes; then
      echo "FAIL W-3: job '$j' does not list 'changes' in needs (needs: $needs) - needs.changes.outputs would be empty" >&2; bad=1
    fi
    if ! has_conjunct "$cond" "$TERM"; then
      echo "FAIL W-3: job '$j' job-level if: '$cond' lacks $TERM as a top-level && conjunct (or has a top-level ||)" >&2; bad=1
    fi
  done

  # W-4: integration-tests, a required check
  rec="$(rec_of integration-tests)"
  if [ -z "$rec" ]; then
    echo "FAIL W-4: job 'integration-tests' not found" >&2; bad=1
  else
    IFS=$'\t' read -r _ needs cond refs outdef <<< "$rec"
    if [ "$needs" = MULTILINE ] || ! needs_has "$needs" changes; then
      echo "FAIL W-4: integration-tests does not list 'changes' in needs (needs: $needs) - its steps could not read docs_only" >&2; bad=1
    fi
    case "$(norm "$cond")" in
      '!cancelled()'|'always()') ;;
      *) echo "FAIL W-4: integration-tests job-level if: '$cond' must be exactly !cancelled() (or always()). Anything else either SKIPS the required job on docs_only (a skipped job reports success), or, with success() / a missing if:, skips it when 'changes' FAILS and a code change merges with no integration run" >&2; bad=1 ;;
    esac
    steps="$(steps_of "$file" integration-tests)"
    local idx sname scond
    while IFS=$'\t' read -r idx sname scond; do
      [ -n "$idx" ] || continue
      [[ "$scond" == *"steps.filter.outputs.integration"* ]] || continue
      nsteps_gated=$((nsteps_gated + 1))
      # the PR-only skip notice is deliberately not path-filter-and-docs gated: it only prints
      [[ "$scond" == *"!= 'true'"* && "$scond" == "github.event_name == 'pull_request' &&"* ]] && continue
      if ! has_conjunct "$scond" "$TERM"; then
        echo "FAIL W-4: integration-tests step '$sname' if: '$scond' lacks $TERM as its own top-level && conjunct (or has a top-level || that makes '&&' bind to only part of it) - on a docs-only push it would still run" >&2; bad=1
      fi
    done <<< "$steps"
    if [ "$nsteps_gated" -lt "$MIN_GATED_STEPS" ]; then
      echo "VOID: only $nsteps_gated integration-tests step(s) gated on the path filter found (expected >= $MIN_GATED_STEPS); the step extractor is blind or the job was restructured" >&2
      return 2
    fi
  fi

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
    if ! needs_has "$needs" deploy-staging; then
      echo "FAIL W-5: staging-edge-probe no longer needs deploy-staging (needs: $needs) - it would probe a rollout that did not happen" >&2; bad=1
    fi
  fi

  [ "$bad" -eq 0 ] || return 1
  echo "OK   doc-only skip reaches exactly: $SKIPPING (job level) and integration-tests' $nsteps_gated steps (step level); integration-tests is never skipped and runs when 'changes' fails"
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
  # plant NAME SED-SCRIPT TOKEN [WANT_RC]: one edit to a copy; the copy must DIFFER from the
  # original, or the arm would pass because nothing was planted.
  plant() {
    local name="$1" script="$2" token="$3" want="${4:-1}"
    sed -E "$script" "$WORKFLOW" > "$st/$name.yaml"
    if cmp -s "$WORKFLOW" "$st/$name.yaml"; then
      echo "FAIL $name: the planting edit changed nothing (the pattern no longer matches the workflow)"; fails=$((fails + 1)); return
    fi
    arm "$name" "$want" "$st/$name.yaml" "$token"
  }
  IT='/^  integration-tests:/,/^  [a-z0-9-]+:$/'
  arm real 0 "$WORKFLOW"
  # --- W-4: integration-tests --------------------------------------------------------------
  plant it-success-and-cancelled "$IT s/^(    if: \\$\\{\\{ )!cancelled\\(\\) \\}\\}\$/\\1success() \\&\\& !cancelled() }}/" "FAIL W-4: integration-tests job-level if"
  plant it-skips-on-docs         "$IT s/^(    if: \\$\\{\\{ )!cancelled\\(\\) \\}\\}\$/\\1!cancelled() \\&\\& needs.changes.outputs.docs_only != 'true' }}/" "FAIL W-4: integration-tests job-level if"
  plant it-no-if                 "$IT{/^    if: \\$\\{\\{ !cancelled\\(\\) \\}\\}\$/d}" "FAIL W-4: integration-tests job-level if"
  plant it-bare-success          "$IT s/^(    if: \\$\\{\\{ )!cancelled\\(\\) \\}\\}\$/\\1success() }}/" "FAIL W-4: integration-tests job-level if"
  plant it-always-is-fine        "$IT s/^(    if: \\$\\{\\{ )!cancelled\\(\\) \\}\\}\$/\\1always() }}/" "OK" 0
  plant it-unparenthesised-step  "0,/^        if: \\(github\\.event_name != 'pull_request' \\|\\| steps\\.filter\\.outputs\\.integration == 'true'\\) && /s//        if: github.event_name != 'pull_request' || steps.filter.outputs.integration == 'true' \\&\\& /" "FAIL W-4: integration-tests step"
  plant it-step-no-term          "0,/^(        if: \\(github\\.event_name != 'pull_request' \\|\\| steps\\.filter\\.outputs\\.integration == 'true'\\)) && needs\\.changes\\.outputs\\.docs_only != 'true'\$/s//\\1/" "FAIL W-4: integration-tests step"
  plant it-drops-needs           "$IT s/^    needs: changes\$/    needs: test/" "FAIL W-4: integration-tests does not list"
  # --- W-3 / W-5 / W-1 / W-2 ---------------------------------------------------------------
  plant gate-lint        "/^  lint:/,/^  [a-z0-9-]+:\$/ s/^(    runs-on: ubuntu-latest)\$/\\1\n    if: needs.changes.outputs.docs_only != 'true'/" "FAIL W-2: job 'lint'"
  plant drop-deploy-term "/^  deploy-staging:/,/^  [a-z0-9-]+:\$/ s/ && needs\\.changes\\.outputs\\.docs_only != 'true'\$//" "FAIL W-3: job 'deploy-staging' job-level if"
  plant drop-bp-need     "/^  build-and-push:/,/^  [a-z0-9-]+:\$/ s/^(    needs: \\[)changes, /\\1/" "FAIL W-3: job 'build-and-push' does not list 'changes'"
  plant bp-or-term       "/^  build-and-push:/,/^  [a-z0-9-]+:\$/ s/^    if: \\((github\\.event_name == 'push' \\|\\| github\\.event_name == 'release')\\) && /    if: \\1 || /" "FAIL W-3: job 'build-and-push' job-level if"
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
