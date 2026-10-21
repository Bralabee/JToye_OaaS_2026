#!/usr/bin/env bash
# wait-other-workloads.sh — after `kubectl apply -k <overlay>`, wait for every Deployment and
# StatefulSet in that overlay's render EXCEPT the ones the caller retagged and already waited on
# (issue #969). The CI deploy-staging job calls this; scripts/deploy.sh uses the same library
# (scripts/lib/wait-workloads.sh) inline, so the two cannot drift.
#
# USAGE
#   scripts/wait-other-workloads.sh --overlay DIR --namespace NS --retagged a,b,c [--budget SECONDS]
#                                   [--context CTX]
#   scripts/wait-other-workloads.sh --selftest
#
# SEMANTICS (the same as deploy.sh)
#   - the workload list is read from the render of DIR, not kept here;
#   - one shared budget (default 600 s), every failure named;
#   - rc 0 all Ready, rc 1 one or more not Ready, rc 2 VOID (render failed or empty, a retagged
#     name is missing from the render, tooling missing, usage);
#   - NO `rollout undo`, ever. The retagged apps were waited on by the caller and are Ready; a
#     monitoring fault is no reason to roll them back. The caller's rollback step must say so
#     (scripts/check-deploy-rollback-guard.sh R-5).

set -uo pipefail
SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/lib/wait-workloads.sh
. "$REPO_ROOT/scripts/lib/wait-workloads.sh" || { echo "VOID: cannot source scripts/lib/wait-workloads.sh" >&2; exit 2; }

void() { echo "VOID: $*" >&2; exit 2; }

selftest() {
    local st real_bash real_kubectl fails=0 why rc out n_wl
    real_bash=$(type -P bash) || void "bash not found"
    real_kubectl=$(type -P kubectl) || void "the selftest needs a real kubectl for the offline render"
    st=$(umask 077 && mktemp -d "${TMPDIR:-/tmp}/jtoye-wait-others-st.XXXXXXXX") || void "mktemp -d failed"
    # shellcheck disable=SC2064
    trap "rm -rf '$st'" EXIT
    mkdir -p "$st/repo/scripts/lib" "$st/stub" "$st/logs"
    cp "$SCRIPT_PATH" "$st/repo/scripts/wait-other-workloads.sh"
    cp "$REPO_ROOT/scripts/lib/wait-workloads.sh" "$st/repo/scripts/lib/"
    cp -R "$REPO_ROOT/k8s" "$st/k8s.pristine"
    cp -R "$REPO_ROOT/k8s" "$st/repo/k8s"
    # Stub kubectl: `kustomize` goes to the real binary; `rollout status` is judged from the render
    # of the overlay (a workload on a registry.invalid image never becomes Ready, as
    # ImagePullBackOff on a cluster); every call is logged.
    cat > "$st/stub/bad-image.awk" <<'AWK'
/^---[ \t]*$/ { k = ""; n = "" }
/^kind: / { k = $2 }
/^  name: / { if (n == "" && (k == "Deployment" || k == "StatefulSet")) n = tolower(k) "/" $2 }
/^[ \t-]*image: / { if (n == want && $0 ~ /registry\.invalid\//) bad = 1 }
END { exit(bad ? 0 : 1) }
AWK
    cat > "$st/stub/kubectl" <<STUB
#!${real_bash}
printf '%s\n' "\$*" >> "\$STUB_LOG_DIR/kubectl.log"
case "\${1:-}" in kustomize) exec "$real_kubectl" "\$@" ;; esac
case " \$* " in
  *" rollout status "*)
    tgt=""; for a in "\$@"; do case "\$a" in deployment/*|statefulset/*) tgt="\$a" ;; esac; done
    if "$real_kubectl" kustomize "\$STUB_OVERLAY" | awk -v want="\$tgt" -f "\$STUB_LOG_DIR/../stub/bad-image.awk"; then
      echo "stub: \$tgt never became Ready (ImagePullBackOff)" >&2; exit 1
    fi ;;
esac
exit 0
STUB
    chmod +x "$st/stub/kubectl"
    _run() { # _run ARGS... — the copy, scrubbed, stub first on PATH
        : > "$st/logs/kubectl.log"
        out=$(command env -i PATH="$st/stub:$(dirname "$(type -P awk)"):$(dirname "$real_bash")" HOME="$st" STUB_LOG_DIR="$st/logs" \
              STUB_OVERLAY="$st/repo/k8s/staging" "$real_bash" "$st/repo/scripts/wait-other-workloads.sh" "$@" 2>&1); rc=$?
    }
    _n() { if [ -s "$st/logs/kubectl.log" ]; then command grep -cE -- "$1" "$st/logs/kubectl.log" || true; else echo 0; fi; }
    local common=(--overlay "$st/repo/k8s/staging" --namespace jtoye-staging --retagged core-java,edge-go,frontend)

    # --- clean: every other workload waited on once, each bounded, rc 0, no undo
    _run "${common[@]}"
    n_wl=$(kubectl kustomize "$st/repo/k8s/staging" | workloads_in_render | wc -l)
    why=""
    if [ "$rc" -ne 0 ]; then why="rc=$rc (want 0): $(head -c 200 <<<"$out" | tr '\n' ' ')"
    elif [ "$(_n ' rollout status ')" -ne $((n_wl - 3)) ]; then why="rollout status calls=$(_n ' rollout status ') (want $((n_wl - 3)): every workload but the three retagged)"
    elif [ "$(_n ' rollout status (deployment|statefulset)/(core-java|edge-go|frontend) ')" -ne 0 ]; then why="a retagged workload was waited on again"
    elif [ "$(_n ' rollout status deployment/(prometheus|alertmanager|grafana) ')" -ne 3 ]; then why="the monitoring Deployments were not all waited on"
    elif [ "$(_n ' rollout status .* --timeout=[0-9]+s$')" -ne $((n_wl - 3)) ]; then why="a rollout status call carries no bounded --timeout"
    elif [ "$(_n 'rollout undo')" -ne 0 ]; then why="rollout undo was called"
    fi
    if [ -z "$why" ]; then echo "PASS clean rc=0 waited=$((n_wl - 3)) retagged_rewaited=0 undo_calls=0"; else echo "FAIL clean $why"; fails=$((fails + 1)); fi

    # --- bad-image: prometheus on an unpullable image
    sed 's|image: prom/prometheus:[^[:space:]]*|image: registry.invalid/prometheus:does-not-exist|' "$st/k8s.pristine/components/monitoring/prometheus.yaml" > "$st/repo/k8s/components/monitoring/prometheus.yaml"
    # The CI logic this replaces, run against the SAME fixture: three rollout status calls, which
    # all succeed. This is the control that the fixture is one the old step could not catch.
    : > "$st/logs/kubectl.log"; rc=0
    for w in core-java edge-go frontend; do
        STUB_LOG_DIR="$st/logs" STUB_OVERLAY="$st/repo/k8s/staging" "$st/stub/kubectl" -n jtoye-staging rollout status "deployment/$w" --timeout=5m >/dev/null 2>&1 || rc=$?
    done
    if [ "$rc" -eq 0 ]; then echo "PASS old-ci-logic rc=0 (the three-app wait certifies the bad-image fixture)"; else echo "FAIL old-ci-logic rc=$rc (want 0: the fixture must be one the old step missed)"; fails=$((fails + 1)); fi
    _run "${common[@]}"
    why=""
    if [ "$rc" -ne 1 ]; then why="rc=$rc (want 1) with prometheus on a bad image: $(head -c 200 <<<"$out" | tr '\n' ' ')"
    elif ! command grep -qF 'deployment/prometheus' <<<"$out"; then why="the output does not name deployment/prometheus"
    elif [ "$(_n ' rollout status deployment/(grafana|alertmanager) ')" -ne 2 ]; then why="the other monitoring Deployments were not still waited on"
    elif [ "$(_n 'rollout undo')" -ne 0 ]; then why="rollout undo was called"
    fi
    if [ -z "$why" ]; then echo "PASS bad-image rc=1 names=deployment/prometheus others_still_waited=2 undo_calls=0"; else echo "FAIL bad-image $why"; fails=$((fails + 1)); fi
    rm -rf "$st/repo/k8s"; cp -R "$st/k8s.pristine" "$st/repo/k8s"

    # --- void: a retagged name the render does not carry, and a render that fails
    _run --overlay "$st/repo/k8s/staging" --namespace jtoye-staging --retagged core-java,edge-go,no-such-app
    if [ "$rc" -eq 2 ] && [ "$(_n ' rollout ')" -eq 0 ]; then echo "PASS void-missing-retagged rc=2 rollout_calls=0"; else echo "FAIL void-missing-retagged rc=$rc (want 2) rollout_calls=$(_n ' rollout ')"; fails=$((fails + 1)); fi
    _run --overlay "$st/repo/k8s/no-such-overlay" --namespace jtoye-staging --retagged core-java
    if [ "$rc" -eq 2 ] && [ "$(_n ' rollout ')" -eq 0 ]; then echo "PASS void-render-fails rc=2 rollout_calls=0"; else echo "FAIL void-render-fails rc=$rc (want 2) rollout_calls=$(_n ' rollout ')"; fails=$((fails + 1)); fi
    [ "$fails" -eq 0 ]
}

OVERLAY=""; NAMESPACE=""; RETAGGED=""; BUDGET=600; CONTEXT=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --selftest) [ "$#" -eq 1 ] || void "--selftest takes no other argument"; selftest; exit $? ;;
        --overlay)   [ "$#" -ge 2 ] || void "--overlay needs a directory"; OVERLAY="$2"; shift 2 ;;
        --namespace) [ "$#" -ge 2 ] || void "--namespace needs a name"; NAMESPACE="$2"; shift 2 ;;
        --retagged)  [ "$#" -ge 2 ] || void "--retagged needs a comma-separated list"; RETAGGED="$2"; shift 2 ;;
        --budget)    [ "$#" -ge 2 ] || void "--budget needs seconds"; BUDGET="$2"; shift 2 ;;
        --context)   [ "$#" -ge 2 ] || void "--context needs a name"; CONTEXT="$2"; shift 2 ;;
        *) void "unknown argument '$1'" ;;
    esac
done
[ -n "$OVERLAY" ] && [ -n "$NAMESPACE" ] && [ -n "$RETAGGED" ] || void "--overlay, --namespace and --retagged are required"
[[ "$BUDGET" =~ ^[0-9]+$ ]] && [ "$BUDGET" -ge 1 ] || void "--budget '$BUDGET' is not a positive integer"
command -v kubectl >/dev/null 2>&1 || void "kubectl not on PATH"

RENDER=$(kubectl kustomize "$OVERLAY" 2>/dev/null); rc=$?
{ [ "$rc" -eq 0 ] && [ -n "$RENDER" ]; } || void "kubectl kustomize $OVERLAY failed (rc=$rc) or rendered nothing; waited on nothing"
mapfile -t ALL < <(workloads_in_render <<< "$RENDER")
[ "${#ALL[@]}" -gt 0 ] || void "the render of $OVERLAY holds no Deployment or StatefulSet"
IFS=',' read -ra RETAG <<< "$RETAGGED"
for r in "${RETAG[@]}"; do
    case " ${ALL[*]} " in *" deployment/${r} "*) ;; *) void "the render of $OVERLAY does not carry deployment/${r}, named in --retagged" ;; esac
done
OTHERS=()
for w in "${ALL[@]}"; do
    skip=0; for r in "${RETAG[@]}"; do [ "$w" = "deployment/$r" ] && skip=1; done
    [ "$skip" -eq 1 ] || OTHERS+=("$w")
done
if [ "${#OTHERS[@]}" -eq 0 ]; then echo "no workload besides the retagged ones in the render of $OVERLAY"; exit 0; fi

KCMD=(kubectl); [ -z "$CONTEXT" ] || KCMD=(kubectl --context "$CONTEXT")
echo "Waiting for the other ${#OTHERS[@]} workload(s) in the render of $OVERLAY (shared budget ${BUDGET}s)..."
if wait_workloads "$NAMESPACE" "$BUDGET" "${KCMD[@]}" -- "${OTHERS[@]}"; then
    echo "All ${#OTHERS[@]} other workload(s) Ready."
    exit 0
fi
echo "NOT CERTIFIED: ${WAIT_NOT_READY[*]} did not become Ready. The retagged workloads (${RETAGGED}) were waited on by the caller and are left as they are: nothing is undone for a workload that was not retagged." >&2
exit 1
