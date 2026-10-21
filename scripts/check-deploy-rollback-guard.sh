#!/usr/bin/env bash
# check-deploy-rollback-guard.sh — the deploy-staging job may only ROLL BACK what it started to deploy.
#
# THE DEFECT THIS PINS (Phase 29 PR #984, review round 1, F2)
#   deploy-staging ends in a step `Rollback on failure` with `if: failure()`. failure() is true
#   when ANY earlier step in the job failed — the baked-origin guard, the logout pre-flight, the
#   subscription check, kubelogin, the can-i check, the Key Vault pre-flight — all of which run
#   BEFORE `kubectl apply`. A disabled Key Vault secret therefore made the job run
#   `kubectl rollout undo` on the three live Deployments although nothing had been deployed,
#   rolling healthy staging back one revision, and the red run read as "deploy failed, rolled back".
#
# WHAT IT ASSERTS (on .github/workflows/ci-cd.yaml, job deploy-staging)
#   R-1  the `Rollback on failure` step exists and its `if:` contains failure() AND
#        steps.<id>.outputs.<name> == 'true';
#   R-2  a step with `id: <id>` exists BEFORE the rollback step;
#   R-3  that step writes `<name>=true` to $GITHUB_OUTPUT on a line BEFORE its first
#        `kubectl apply` (the output means "the apply was reached", so it must be set before it
#        and by nothing earlier in the job);
#   R-4  that step is the FIRST step in the job that runs `kubectl apply`;
#   R-5  (issue #969) the rollback `if:` also contains steps.wait_other.outcome != 'failure', and a
#        step with `id: wait_other` exists, runs scripts/wait-other-workloads.sh, comes AFTER the
#        app checks (the `Digest parity`, `Health check` and `Run smoke tests` steps) and is the
#        step IMMEDIATELY before the rollback. Placed before them, a monitoring fault would fail
#        wait_other first, skip the app checks and disable the rollback: apps that fail their
#        health or smoke check would stay on staging. Placed last, an app-check failure skips
#        wait_other (outcome `skipped`, so the rollback still fires) and a wait_other-only
#        failure is the only thing the exclusion covers. That step waits on the workloads the job did NOT retag
#        (monitoring, Keycloak, ...); when it fails the retagged apps are Ready, and an undo of
#        them for a Prometheus fault would trade a monitoring fault for an application one.
#   Comment lines are ignored (a rule that must name the token it forbids would otherwise match
#   its own explanation).
#
# Exit codes: 0 = holds, 1 = a rule fails, 2 = VOID (file/job/steps not found, or a tool missing).
# --selftest plants one hole per rule in a copy of the workflow and requires each to be caught,
# and requires the real file to pass (the control).
#
# Usage: scripts/check-deploy-rollback-guard.sh [--file WORKFLOW] [--job JOB] | --selftest

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/ci-cd.yaml"
JOB="deploy-staging"
SELFTEST=0

void() { printf 'VOID: %s\n' "$*" >&2; exit 2; }

needval() { [ "$#" -ge 2 ] || void "$1 needs a value, and none was given"; }
while [ "$#" -gt 0 ]; do
  case "$1" in
    --file)     needval "$@"; WORKFLOW="$2"; shift 2 ;;
    --job)      needval "$@"; JOB="$2"; shift 2 ;;
    --selftest) SELFTEST=1; shift ;;
    -h|--help)  sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) void "unknown argument '$1'" ;;
  esac
done

# check_workflow FILE JOB — prints findings to stderr, returns 0 / 1 / 2.
check_workflow() {
  local file="$1" job="$2" steps n
  [ -f "$file" ] || { echo "VOID: workflow file '$file' not found" >&2; return 2; }
  # One record per step of the job:
  #   idx <TAB> id <TAB> name <TAB> if <TAB> first-apply-line <TAB> output-name <TAB> output-line <TAB> waits-others
  steps="$(awk -v job="$job" '
    function flush() {
      # "-" for an empty field: `read` with a tab IFS collapses empty fields and shifts the rest.
      if (idx > 0) printf "%d\t%s\t%s\t%s\t%d\t%s\t%d\t%d\n", idx, (id == "" ? "-" : id), (name == "" ? "-" : name), (cond == "" ? "-" : cond), apply, (oname == "" ? "-" : oname), oline, waits
    }
    $0 == "  " job ":" { injob = 1; next }
    injob && /^  [A-Za-z0-9_-]+:/ { flush(); idx = 0; injob = 0 }
    !injob { next }
    /^      - / {
      flush(); idx++; id = ""; name = ""; cond = ""; apply = 0; oname = ""; oline = 0; waits = 0
    }
    idx == 0 { next }
    {
      line = $0
      if (match(line, /^      - name: */)) { name = substr(line, RLENGTH + 1) }
      else if (match(line, /^        name: */)) { name = substr(line, RLENGTH + 1) }
      if (match(line, /^        id: */)) { id = substr(line, RLENGTH + 1); sub(/[ \t]+$/, "", id) }
      if (match(line, /^        if: */)) { cond = substr(line, RLENGTH + 1) }
      if (line ~ /^[ \t]*#/) next
      if (apply == 0 && line ~ /kubectl apply/) apply = NR
      if (line ~ /wait-other-workloads\.sh/) waits = 1
      if (oline == 0 && match(line, /echo +"?[A-Za-z_][A-Za-z0-9_]*=true"? *>> *"?\$GITHUB_OUTPUT"?/)) {
        t = substr(line, RSTART, RLENGTH); sub(/^echo +"?/, "", t); sub(/=true.*$/, "", t); oname = t; oline = NR
      }
    }
    END { flush() }
  ' "$file")" || { echo "VOID: awk failed reading $file" >&2; return 2; }
  n="$(printf '%s' "$steps" | grep -c '' || true)"
  if [ -z "$steps" ] || [ "$n" -lt 3 ]; then
    echo "VOID: job '$job' yielded $n step record(s) in $file — the extractor is blind or the job is gone; nothing was evaluated" >&2
    return 2
  fi

  local bad=0 rb_idx rb_if rb_name ref_id ref_out id_idx id_apply id_oname id_oline first_apply_idx
  IFS=$'\t' read -r rb_idx _ rb_name rb_if _ _ _ _ < <(awk -F'\t' '$3 ~ /^Rollback on failure/ { print; exit }' <<<"$steps")
  if [ -z "${rb_idx:-}" ]; then
    echo "FAIL [$job] R-1: no 'Rollback on failure' step found" >&2; return 1
  fi
  if [[ "$rb_if" != *"failure()"* ]]; then
    echo "FAIL [$job] R-1: 'Rollback on failure' has if: '$rb_if' — no failure() (it would not run on a failed deploy)" >&2; bad=1
  fi
  if [[ "$rb_if" =~ steps\.([A-Za-z0-9_-]+)\.outputs\.([A-Za-z0-9_]+)[[:space:]]*==[[:space:]]*\'true\' ]]; then
    ref_id="${BASH_REMATCH[1]}"; ref_out="${BASH_REMATCH[2]}"
  else
    echo "FAIL [$job] R-1: 'Rollback on failure' has if: '$rb_if' — failure() alone is true when a step BEFORE the apply failed (pre-flight, kubeconfig, Key Vault), and the undo then rolls healthy live staging back a revision. Gate it on steps.<id>.outputs.<name> == 'true'." >&2
    return 1
  fi
  id_idx="$(awk -F'\t' -v i="$ref_id" '$2 == i { print $1; exit }' <<<"$steps")"
  if [ -z "$id_idx" ]; then
    echo "FAIL [$job] R-2: the rollback if: names steps.$ref_id but no step has 'id: $ref_id'" >&2; return 1
  fi
  if [ "$id_idx" -ge "$rb_idx" ]; then
    echo "FAIL [$job] R-2: step id '$ref_id' (#$id_idx) is not BEFORE the rollback step (#$rb_idx)" >&2; bad=1
  fi
  IFS=$'\t' read -r _ _ _ _ id_apply id_oname id_oline _ < <(awk -F'\t' -v i="$id_idx" '$1 == i { print; exit }' <<<"$steps")
  if [ "${id_apply:-0}" -eq 0 ]; then
    echo "FAIL [$job] R-3: step '$ref_id' runs no 'kubectl apply' — the output cannot mean 'the apply was reached'" >&2; bad=1
  fi
  if [ "${id_oname:-}" != "$ref_out" ] || [ "${id_oline:-0}" -eq 0 ]; then
    echo "FAIL [$job] R-3: step '$ref_id' never writes '$ref_out=true' to \$GITHUB_OUTPUT (found '${id_oname:--}' at line ${id_oline:-0})" >&2; bad=1
  elif [ "${id_apply:-0}" -ne 0 ] && [ "$id_oline" -ge "$id_apply" ]; then
    echo "FAIL [$job] R-3: step '$ref_id' sets '$ref_out' at line $id_oline, NOT before its first 'kubectl apply' (line $id_apply): a failure between them would skip the rollback of a half-applied deploy" >&2; bad=1
  fi
  first_apply_idx="$(awk -F'\t' '$5 > 0 { print $1; exit }' <<<"$steps")"
  if [ -n "$first_apply_idx" ] && [ "$first_apply_idx" != "$id_idx" ]; then
    echo "FAIL [$job] R-4: step #$first_apply_idx runs 'kubectl apply' before step '$ref_id' (#$id_idx): an apply the output does not cover" >&2; bad=1
  fi
  # R-5 (#969): a failure of the other-workloads wait must not roll the retagged apps back.
  local wo_idx wo_waits
  if [[ "$rb_if" != *"steps.wait_other.outcome != 'failure'"* ]]; then
    echo "FAIL [$job] R-5: 'Rollback on failure' has if: '$rb_if' — it does not exclude steps.wait_other.outcome != 'failure', so a monitoring-only failure would undo the three healthy retagged Deployments" >&2; bad=1
  fi
  IFS=$'\t' read -r wo_idx _ _ _ _ _ _ wo_waits < <(awk -F'\t' '$2 == "wait_other" { print; exit }' <<<"$steps")
  if [ -z "${wo_idx:-}" ]; then
    echo "FAIL [$job] R-5: no step has 'id: wait_other', so nothing waits on the workloads the job did not retag (#969)" >&2; bad=1
  else
    [ "$wo_idx" -lt "$rb_idx" ] || { echo "FAIL [$job] R-5: step wait_other (#$wo_idx) is not BEFORE the rollback step (#$rb_idx)" >&2; bad=1; }
    [ "$wo_idx" -eq $((rb_idx - 1)) ] || { echo "FAIL [$job] R-5: step wait_other (#$wo_idx) is not the step immediately before the rollback (#$rb_idx): a step between them could fail on its own and roll the apps back for it, or be skipped by it" >&2; bad=1; }
    local chk chk_idx
    for chk in 'Digest parity' 'Health check' 'Run smoke tests'; do
      chk_idx="$(awk -F'\t' -v n="$chk" 'index($3, n) == 1 { print $1; exit }' <<<"$steps")"
      if [ -z "$chk_idx" ]; then
        echo "FAIL [$job] R-5: no step named '$chk...', so the ordering of wait_other against the app checks cannot be shown" >&2; bad=1
      elif [ "$wo_idx" -le "$chk_idx" ]; then
        echo "FAIL [$job] R-5: step wait_other (#$wo_idx) runs BEFORE '$chk' (#$chk_idx): a monitoring failure would skip the app checks and disable the rollback of apps that fail them" >&2; bad=1
      fi
    done
    [ "${wo_waits:-0}" -eq 1 ] || { echo "FAIL [$job] R-5: step wait_other does not run scripts/wait-other-workloads.sh" >&2; bad=1; }
  fi
  [ "$bad" -eq 0 ] || return 1
  echo "OK   [$job] rollback is gated on steps.$ref_id.outputs.$ref_out (set at line $id_oline, before the apply at line $id_apply)"
  return 0
}

if [ "$SELFTEST" -eq 1 ]; then
  [ -f "$WORKFLOW" ] || void "workflow $WORKFLOW not found"
  st="$(mktemp -d)" || void "mktemp -d failed"
  trap 'rm -rf "$st"' EXIT
  fails=0
  arm() { # arm NAME WANT_RC FILE
    local name="$1" want="$2" f="$3" rc=0
    check_workflow "$f" "$JOB" >"$st/$name.out" 2>&1 || rc=$?
    if [ "$rc" -eq "$want" ]; then echo "PASS $name rc=$rc"
    else echo "FAIL $name rc=$rc (want $want): $(tail -2 "$st/$name.out" | tr '\n' ' ')"; fails=$((fails + 1)); fi
  }
  arm real 0 "$WORKFLOW"
  # Each plant edits ONE line of a copy, inside the job under test.
  # bare-failure: the reviewed defect — the rollback runs on any failure.
  sed -E "/^  ${JOB}:/,/^  [A-Za-z0-9_-]+:\$/ s/^(        if: failure\(\)) && steps\.[A-Za-z0-9_.-]+ == 'true'( \&\& steps\.wait_other\.outcome != 'failure')?\$/\1/; /^  ${JOB}:/,/^  [A-Za-z0-9_-]+:\$/ s/^(        if: failure\(\)) && steps\.[A-Za-z0-9_-]+\.outputs\.[A-Za-z0-9_]+ == 'true'( \&\& steps\.wait_other\.outcome != 'failure')?\$/\1/" "$WORKFLOW" > "$st/bare.yaml"
  arm bare-failure 1 "$st/bare.yaml"
  # wrong-output: the if: names an output the step never writes.
  sed -E "/^  ${JOB}:/,/^  [A-Za-z0-9_-]+:\$/ s/(steps\.[A-Za-z0-9_-]+\.outputs\.)[A-Za-z0-9_]+( == 'true')/\1nonexistent\2/" "$WORKFLOW" > "$st/wrongout.yaml"
  arm wrong-output 1 "$st/wrongout.yaml"
  # output-after-apply: the output is written after the apply, not before.
  awk -v job="$JOB" '
    $0 == "  " job ":" { injob = 1 }
    injob && /GITHUB_OUTPUT/ && /=true/ && !/^[ \t]*#/ { held = $0; next }
    { print }
    injob && /^ *kubectl apply/ && held != "" { print held; held = "" }
  ' "$WORKFLOW" > "$st/after.yaml"
  arm output-after-apply 1 "$st/after.yaml"
  # no-id: the step that sets the output loses its id.
  sed -E "/^  ${JOB}:/,/^  [A-Za-z0-9_-]+:\$/ { /^        id: /d }" "$WORKFLOW" > "$st/noid.yaml"
  arm no-id 1 "$st/noid.yaml"
  # no-rollback: the rollback step is gone (renamed).
  sed -E "/^  ${JOB}:/,/^  [A-Za-z0-9_-]+:\$/ s/^(      - name: )Rollback on failure/\1Undo/" "$WORKFLOW" > "$st/norb.yaml"
  arm no-rollback 1 "$st/norb.yaml"
  # R-5 plants. no-outcome-guard: the rollback `if:` loses the wait_other exclusion (the reviewed
  # defect: a monitoring-only failure undoes the apps). no-wait-step: the wait step is gone.
  sed -E "/^  ${JOB}:/,/^  [A-Za-z0-9_-]+:\$/ s/ && steps\.wait_other\.outcome != 'failure'//" "$WORKFLOW" > "$st/nooutcome.yaml"
  arm no-outcome-guard 1 "$st/nooutcome.yaml"
  sed -E "/^  ${JOB}:/,/^  [A-Za-z0-9_-]+:\$/ s/^        id: wait_other\$/        id: wait_other_renamed/" "$WORKFLOW" > "$st/nowait.yaml"
  arm no-wait-step 1 "$st/nowait.yaml"
  # wait-before-app-checks (review round 1 on #1019): wait_other moved back in front of the digest
  # parity, health and smoke steps. The block is cut out of its place and put before Digest parity.
  awk -v job="$JOB" '
    $0 == "  " job ":" { injob = 1; next }
    injob && /^  [A-Za-z0-9_-]+:/ { injob = 0 }
    injob && /^      - name: Wait for the other workloads/ { cap = 1 }
    cap && !/^      - name: Wait for the other workloads/ && /^      (- name:|#)/ { cap = 0 }
    cap { print > "'"$st"'/wo.block" }
  ' "$WORKFLOW"
  if [ -s "$st/wo.block" ]; then
    awk -v job="$JOB" -v blk="$st/wo.block" '
      $0 == "  " job ":" { injob = 1 }
      injob && /^  [A-Za-z0-9_-]+:/ && $0 != "  " job ":" { injob = 0 }
      injob && /^      - name: Wait for the other workloads/ { skip = 1 }
      skip && !/^      - name: Wait for the other workloads/ && /^      (- name:|#)/ { skip = 0 }
      skip { next }
      injob && /^      - name: Digest parity/ { while ((getline l < blk) > 0) print l; close(blk) }
      { print }
    ' "$WORKFLOW" > "$st/waitfirst.yaml"
  else
    cp "$WORKFLOW" "$st/waitfirst.yaml"   # the block was not found: the plant control below fails the run
  fi
  arm wait-before-app-checks 1 "$st/waitfirst.yaml"
  # void: a job that does not exist is VOID, never clean.
  rc=0; check_workflow "$WORKFLOW" "no-such-job" >"$st/void.out" 2>&1 || rc=$?
  if [ "$rc" -eq 2 ]; then echo "PASS missing-job rc=2"; else echo "FAIL missing-job rc=$rc (want 2)"; fails=$((fails + 1)); fi
  # Controls on the planting itself: every mutated copy must DIFFER from the real file,
  # otherwise a plant that silently changed nothing would "fail" for the wrong reason.
  for p in bare wrongout after noid norb nooutcome nowait waitfirst; do
    if cmp -s "$WORKFLOW" "$st/$p.yaml"; then echo "FAIL plant-$p changed nothing — the arm above proves nothing"; fails=$((fails + 1)); fi
  done
  [ "$fails" -eq 0 ] || exit 1
  exit 0
fi

rc=0; check_workflow "$WORKFLOW" "$JOB" || rc=$?
exit "$rc"
