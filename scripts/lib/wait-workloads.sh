#!/usr/bin/env bash
# wait-workloads.sh — the ONE implementation of "wait for every Deployment and StatefulSet in a
# render" (issue #969), sourced by scripts/deploy.sh and by scripts/wait-other-workloads.sh (the
# CI deploy-staging job's step). A fix here reaches both; neither carries its own copy.
#
# SOURCED, NEVER EXECUTED. Defines two functions and runs nothing at source time; they RETURN a
# status and never call `exit`.
#
#   workloads_in_render            reads a kustomize render on stdin, prints one `kind/name` (kind
#                                  lower-cased) per Deployment and StatefulSet. kustomize emits keys
#                                  in sorted order, so `kind` precedes `metadata` and the first
#                                  two-space `name:` of such a document is its metadata.name.
#   wait_workloads NS BUDGET_S KUBECTL_WORDS... -- KIND/NAME...
#                                  `rollout status` on each workload under ONE shared budget (not
#                                  one timeout each: twelve broken workloads cost BUDGET_S, not
#                                  twelve times it). Once the budget is spent each remaining
#                                  workload gets a 1 s look, which a Ready one passes at once.
#                                  EVERY workload is checked and every failure is named. Returns 0
#                                  when all are Ready, 1 otherwise, with the failures in the global
#                                  array WAIT_NOT_READY. It never undoes anything: a rollback is the
#                                  caller's, scoped to what it retagged.
#
# KUBECTL_WORDS is the command prefix, e.g. `kubectl` or `kubectl --context jtoye-stg-aks`.

workloads_in_render() {
    awk '
        /^---[ \t]*$/ { k = ""; n = "" }
        /^kind: / { k = $2 }
        /^  name: / { if (n == "" && (k == "Deployment" || k == "StatefulSet")) { n = $2; gsub(/"/, "", n); print tolower(k) "/" n } }'
}

wait_workloads() {
    local ns="$1" budget="$2" w remaining deadline
    local -a kcmd=()
    shift 2
    while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do kcmd+=("$1"); shift; done
    [ "$#" -gt 0 ] || { echo "wait_workloads: missing the -- before the workload list" >&2; return 2; }
    shift
    WAIT_NOT_READY=()
    deadline=$((SECONDS + budget))
    for w in "$@"; do
        remaining=$((deadline - SECONDS)); [ "$remaining" -ge 1 ] || remaining=1
        if "${kcmd[@]}" -n "$ns" rollout status "$w" --timeout="${remaining}s"; then
            echo "ready: ${w}"
        else
            echo "Error: ${w} did not become Ready" >&2
            WAIT_NOT_READY+=("$w")
        fi
    done
    [ "${#WAIT_NOT_READY[@]}" -eq 0 ]
}
