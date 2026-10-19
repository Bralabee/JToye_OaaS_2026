#!/bin/bash
# Deployment script for J'Toye OaaS — STAGING and PRODUCTION only.
# Usage: ./scripts/deploy.sh [staging|production] [service]
# Examples:
#   ./scripts/deploy.sh production all
#   ./scripts/deploy.sh staging core-java
#
# For a LOCAL Kubernetes rehearsal use scripts/k8s-local-up.sh instead: local
# needs the compose-XOR guard, the out-of-band secret bootstrap and locally built
# images, none of which belong in a staging/production deploy path.

# --selftest (plan 29-14 Task 1, RED): the arms below run against THIS body.
SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ===========================================================================
# --selftest (plan 29-14 Task 1): every refusal and record this script makes,
# against stubs, touching no cluster.
#
# The arms run a COPY of this script inside a mktemp -d repo layout (the k8s/
# tree, scripts/lib/staging-kube-guard.sh, and a STUB scripts/staging-secrets-
# preflight.sh) with a scrubbed environment whose PATH holds only a stub kubectl,
# a stub kubelogin and the plain tools the script needs. The stub kubectl logs
# its argv and hands ONLY `config` (the guard's local kubeconfig read) and
# `kustomize` (an offline render) to the real binary, resolved before PATH is
# replaced; every other call is logged and answered 0, or 1 for the one rollout
# an arm marks as failing. The kubeconfig is the selftest's own, so even the
# delegated `config` read never sees the host's contexts. The stub preflight's
# exit status is set by the arm through the stub's own environment: deploy.sh
# itself reads no variable that could swap the preflight in a real run.
# ===========================================================================
_ST_RESULTS=()
_st_result() { _ST_RESULTS+=("$1"); }
_st_render() {
  local i=0 r verdict rest
  if [ "${DEPLOY_SELFTEST_FORMAT:-plain}" = tap ]; then
    echo "TAP version 13"
    echo "1..${#_ST_RESULTS[@]}"
    for r in "${_ST_RESULTS[@]}"; do
      i=$((i + 1)); verdict="${r%% *}"; rest="${r#* }"
      if [ "$verdict" = PASS ]; then
        echo "ok $i - ${rest%% *}"
      else
        echo "not ok $i - ${rest%% *}"
        echo "  ---"
        echo "  message: '$(printf '%s' "${rest#* }" | tr "'" '"' | tr '\n' ' ')'"
        echo "  ..."
      fi
    done
  else
    for r in "${_ST_RESULTS[@]}"; do printf '%s\n' "$r"; done
  fi
}

selftest() {
  local real_bash real_kubectl st fails=0 why t p fqdn arm
  local sha="0123456789abcdef0123456789abcdef01234567"
  local arms="no-context employer-context stale-server image-tag preflight-refusal render-only rollout-failure deployed-sha"
  real_bash=$(type -P bash) || { echo "VOID: bash not found" >&2; return 2; }
  if ! real_kubectl=$(type -P kubectl); then
    for arm in $arms; do _st_result "FAIL $arm VOID: the selftest needs a real kubectl for the offline render and the guard's local config read"; done
    _st_render; return 2
  fi
  st=$(umask 077 && mktemp -d "${TMPDIR:-/tmp}/jtoye-deploy-st.XXXXXXXX") || { echo "VOID: mktemp -d failed" >&2; return 2; }
  # The dispatch exits straight after selftest returns, so EXIT is the cleanup.
  # shellcheck disable=SC2064
  trap "rm -rf '$st'" EXIT
  mkdir -p "$st/repo/scripts/lib" "$st/stub" "$st/tools" "$st/logs" "$st/home" "$st/tmp"
  cp "$SCRIPT_PATH" "$st/repo/scripts/deploy.sh"
  cp -R "$PROJECT_ROOT/k8s" "$st/repo/k8s"
  if [ -f "$PROJECT_ROOT/scripts/lib/staging-kube-guard.sh" ]; then
    cp "$PROJECT_ROOT/scripts/lib/staging-kube-guard.sh" "$st/repo/scripts/lib/"
  fi
  # The stub preflight: logged, and its status is the arm's choice.
  printf 'printf "%%s\\n" "preflight $*" >> "$STUB_LOG_DIR/preflight.log"\nprintf "%%s\\n" "preflight" >> "$STUB_LOG_DIR/calls.log"\nexit "${STUB_PREFLIGHT_RC:-0}"\n' \
    > "$st/repo/scripts/staging-secrets-preflight.sh"
  for t in bash env sed awk grep cat mktemp rm mkdir date tr wc head tail cut sort dirname basename cp chmod realpath sha256sum; do
    p=$(type -P "$t") || { for arm in $arms; do _st_result "FAIL $arm VOID: selftest tool not found: $t"; done; _st_render; return 2; }
    ln -s "$p" "$st/tools/$t"
  done
  cat > "$st/stub/kubectl" <<STUB
#!${real_bash}
printf '%s\n' "\$*" >> "\$STUB_LOG_DIR/kubectl.log"
printf 'kubectl %s\n' "\$*" >> "\$STUB_LOG_DIR/calls.log"
case "\${1:-}" in config|kustomize) exec "\$REAL_KUBECTL" "\$@" ;; esac
case " \$* " in
  *" apply -k "*)
    # Keep the render of what was applied, for the arm to read back.
    "\$REAL_KUBECTL" kustomize "\${@: -1}" > "\$STUB_LOG_DIR/applied-render.yaml" 2>"\$STUB_LOG_DIR/applied-render.err" || exit 1 ;;
esac
if [ -n "\${STUB_FAIL_ROLLOUT:-}" ]; then
  case " \$* " in *" rollout status deployment/\${STUB_FAIL_ROLLOUT} "*) echo "stub: rollout of \${STUB_FAIL_ROLLOUT} failed" >&2; exit 1 ;; esac
fi
exit 0
STUB
  printf '#!%s\nprintf "%%s\\n" "$*" >> "$STUB_LOG_DIR/kubelogin.log"\nexit 0\n' "$real_bash" > "$st/stub/kubelogin"
  chmod +x "$st/stub/kubectl" "$st/stub/kubelogin"

  fqdn=$(awk -F'"' '/^[[:space:]]*aks_api_server_fqdn[[:space:]]*:/ { print $2; exit }' "$st/repo/k8s/staging/azure-identity.yaml")
  if [ -z "$fqdn" ]; then
    for arm in $arms; do _st_result "FAIL $arm VOID: no aks_api_server_fqdn in the copied k8s/staging/azure-identity.yaml"; done
    _st_render; return 2
  fi
  cat > "$st/kubeconfig" <<KCFG
apiVersion: v1
kind: Config
clusters:
- name: good
  cluster:
    server: https://${fqdn}:443
- name: stale
  cluster:
    server: https://stale.example:443
contexts:
- name: jtoye-stg-aks
  context: {cluster: good, user: none}
- name: jtoye-staging
  context: {cluster: stale, user: none}
- name: sipbihs2aks
  context: {cluster: good, user: none}
users:
- name: none
  user: {}
KCFG

  # _st_run [VAR=value ...] -- ARGS... — the copy, scrubbed, stubs first on PATH.
  _st_run() {
    local extra=()
    while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do extra+=("$1"); shift; done
    shift
    : > "$st/logs/kubectl.log"; : > "$st/logs/kubelogin.log"; : > "$st/logs/preflight.log"; : > "$st/logs/calls.log"
    rm -f "$st/logs/applied-render.yaml"
    command env -i PATH="$st/stub:$st/tools" HOME="$st/home" KUBECONFIG="$st/kubeconfig" TMPDIR="$st/tmp" \
      STUB_LOG_DIR="$st/logs" REAL_KUBECTL="$real_kubectl" ${extra[@]+"${extra[@]}"} \
      "$real_bash" "$st/repo/scripts/deploy.sh" "$@" > "$st/out" 2> "$st/err"; ST_RC=$?
    ST_OUT=$(cat "$st/out"); ST_ERR=$(cat "$st/err")
  }
  _st_lines() { local f="$st/logs/$1.log"; if [ -s "$f" ]; then command grep -c '' "$f"; else echo 0; fi; }
  _st_kubectl_matching() { if [ -s "$st/logs/kubectl.log" ]; then command grep -cE -- "$1" "$st/logs/kubectl.log" || true; else echo 0; fi; }
  _st_beyond_config() { if [ -s "$st/logs/kubectl.log" ]; then command grep -vc '^config view ' "$st/logs/kubectl.log" || true; else echo 0; fi; }
  _st_snip() { head -c 220 <<<"$1" | tr '\n' ' '; }

  # --- no-context: a deploy without --context is a usage error, before any call
  _st_run -- staging --image-tag "$sha"
  why=""
  if [ "$ST_RC" -ne 2 ]; then why="rc=$ST_RC (want 2): $(_st_snip "$ST_ERR")"
  elif ! command grep -qF 'USAGE ERROR' <<<"$ST_OUT$ST_ERR"; then why="no USAGE ERROR line: $(_st_snip "$ST_ERR")"
  elif [ "$(_st_lines kubectl)" -ne 0 ]; then why="kubectl calls=$(_st_lines kubectl) (want 0): $(head -1 "$st/logs/kubectl.log")"
  elif [ "$(_st_lines preflight)" -ne 0 ]; then why="the preflight ran"
  fi
  if [ -z "$why" ]; then _st_result "PASS no-context rc=2 kubectl_calls=0 preflight_calls=0"
  else _st_result "FAIL no-context $why"; fails=$((fails + 1)); fi

  # --- employer-context: sipbihs2aks refused by name, before any read --------
  _st_run -- staging --context sipbihs2aks --image-tag "$sha"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(_st_snip "$ST_ERR")"
  elif ! command grep -qF 'REFUSED [employer-context]' <<<"$ST_ERR"; then why="stderr lacks REFUSED [employer-context]: $(_st_snip "$ST_ERR")"
  elif [ "$(_st_beyond_config)" -ne 0 ]; then why="kubectl calls beyond the context read: $(_st_beyond_config)"
  elif [ "$(_st_lines preflight)" -ne 0 ]; then why="the preflight ran"
  fi
  if [ -z "$why" ]; then _st_result "PASS employer-context rc=1 kubectl_calls=$(_st_lines kubectl) preflight_calls=0"
  else _st_result "FAIL employer-context $why"; fails=$((fails + 1)); fi

  # --- stale-server: a context pointing anywhere but the staging API server --
  _st_run -- staging --context jtoye-staging --image-tag "$sha"
  why=""
  if [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1): $(_st_snip "$ST_ERR")"
  elif ! command grep -qF 'REFUSED [server-url]' <<<"$ST_ERR"; then why="stderr lacks REFUSED [server-url]: $(_st_snip "$ST_ERR")"
  elif [ "$(_st_beyond_config)" -ne 0 ]; then why="kubectl calls beyond the context read: $(_st_beyond_config)"
  elif [ "$(_st_kubectl_matching '^config view ')" -lt 1 ]; then why="the context's server was never read (no config view call)"
  elif [ "$(_st_lines preflight)" -ne 0 ]; then why="the preflight ran"
  fi
  if [ -z "$why" ]; then _st_result "PASS stale-server rc=1 kubectl_calls=$(_st_lines kubectl)(config view) apply_calls=0 preflight_calls=0"
  else _st_result "FAIL stale-server $why"; fails=$((fails + 1)); fi

  # --- image-tag: anything but 40 lowercase hex is a usage error ------------
  why=""
  local bad label
  for bad in "0123abc" "main" "latest" "0123456789ABCDEF0123456789ABCDEF01234567" "<absent>"; do
    if [ "$bad" = "<absent>" ]; then _st_run -- staging --context jtoye-stg-aks; label="absent"
    else _st_run -- staging --context jtoye-stg-aks --image-tag "$bad"; label="$bad"; fi
    if [ "$ST_RC" -ne 2 ]; then why="tag '$label': rc=$ST_RC (want 2): $(_st_snip "$ST_ERR")"; break; fi
    if [ "$(_st_beyond_config)" -ne 0 ]; then why="tag '$label': kubectl calls beyond the context read: $(_st_beyond_config)"; break; fi
    if [ "$(_st_lines preflight)" -ne 0 ]; then why="tag '$label': the preflight ran"; break; fi
  done
  if [ -z "$why" ]; then _st_result "PASS image-tag short,branch,latest,uppercase,absent all rc=2 apply_calls=0 preflight_calls=0"
  else _st_result "FAIL image-tag $why"; fails=$((fails + 1)); fi

  # --- preflight-refusal: a failed (1) or VOID (2) preflight refuses the deploy
  why=""
  local prc
  for prc in 1 2; do
    _st_run STUB_PREFLIGHT_RC="$prc" -- staging --context jtoye-stg-aks --image-tag "$sha"
    if [ "$ST_RC" -ne 1 ]; then why="preflight rc $prc: rc=$ST_RC (want 1): $(_st_snip "$ST_ERR")"; break; fi
    if ! command grep -qF 'REFUSED [preflight]' <<<"$ST_ERR"; then why="preflight rc $prc: stderr lacks REFUSED [preflight]: $(_st_snip "$ST_ERR")"; break; fi
    if [ "$(_st_lines preflight)" -ne 1 ]; then why="preflight rc $prc: preflight calls=$(_st_lines preflight) (want 1, the control that it was reached)"; break; fi
    if [ "$(_st_kubectl_matching ' apply ')" -ne 0 ] || [ "$(_st_kubectl_matching ' rollout ')" -ne 0 ]; then why="preflight rc $prc: apply/rollout called after a failed preflight"; break; fi
  done
  if [ -z "$why" ]; then _st_result "PASS preflight-refusal preflight_rc=1,2 -> rc=1 preflight_calls=1 apply_calls=0 rollout_calls=0"
  else _st_result "FAIL preflight-refusal $why"; fails=$((fails + 1)); fi

  # --- render-only: offline, no context, the committed overlay untouched -----
  local h_before h_after n_app n_pg
  h_before=$(sha256sum "$st/repo/k8s/staging/kustomization.yaml" | cut -d' ' -f1)
  _st_run -- staging --render-only --image-tag "$sha"
  h_after=$(sha256sum "$st/repo/k8s/staging/kustomization.yaml" | cut -d' ' -f1)
  n_app=$(command grep -cE "image: ghcr.io/bralabee/jtoye-(core-java|frontend|edge-go):${sha}\$" <<<"$ST_OUT" || true)
  n_pg=$(command grep -c 'image: ghcr.io/bralabee/jtoye-pg-backup:18-blob$' <<<"$ST_OUT" || true)
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(_st_snip "$ST_ERR")"
  elif [ -z "$ST_OUT" ]; then why="empty render"
  elif [ "$n_app" -ne 3 ]; then why="app image lines at the tag=$n_app (want 3)"
  elif [ "$n_pg" -ne 1 ]; then why="pg-backup 18-blob lines=$n_pg (want 1)"
  elif [ "$h_before" != "$h_after" ]; then why="k8s/staging/kustomization.yaml changed: $h_before -> $h_after"
  elif [ "$(_st_kubectl_matching ' ?(apply|rollout|create|delete|patch|label) ')" -ne 0 ]; then why="a mutating kubectl call: $(command grep -E '(apply|rollout|create|delete|patch|label) ' "$st/logs/kubectl.log" | head -1)"
  elif [ "$(_st_lines preflight)" -ne 0 ]; then why="the preflight ran"
  elif [ -n "$(ls -A "$st/tmp")" ]; then why="the wrapper dir was left behind: $(ls -A "$st/tmp" | head -1)"
  fi
  if [ -z "$why" ]; then _st_result "PASS render-only rc=0 app_images_at_tag=3 pg_backup_18_blob=1 kustomization_sha256=${h_after:0:16}(unchanged) mutating_calls=0"
  else _st_result "FAIL render-only $why"; fails=$((fails + 1)); fi

  # --- rollout-failure: undo the failed Deployment, no DEPLOYED_SHA record ----
  _st_run STUB_FAIL_ROLLOUT=frontend -- staging --context jtoye-stg-aks --image-tag "$sha"
  why=""
  if [ "$ST_RC" -eq 0 ]; then why="rc=0 after a failed frontend rollout"
  elif [ "$(_st_kubectl_matching 'rollout undo deployment/frontend( |$)')" -ne 1 ]; then why="rollout undo deployment/frontend calls=$(_st_kubectl_matching 'rollout undo deployment/frontend( |$)') (want 1)"
  elif command grep -q '^DEPLOYED_SHA=' <<<"$ST_OUT"; then why="a DEPLOYED_SHA line was printed after a failed rollout"
  elif [ "$(_st_kubectl_matching ' apply -k ')" -ne 1 ]; then why="apply -k calls=$(_st_kubectl_matching ' apply -k ') (want 1: the failure must come from the rollout)"
  fi
  if [ -z "$why" ]; then _st_result "PASS rollout-failure rc=$ST_RC undo_frontend=1 deployed_sha_lines=0"
  else _st_result "FAIL rollout-failure $why"; fails=$((fails + 1)); fi

  # --- deployed-sha: every rollout green -> the LAST stdout line is the record
  _st_run -- staging --context jtoye-stg-aks --image-tag "$sha"
  why=""
  local last n_ctx n_all n_applied order
  last=$(tail -n 1 "$st/out")
  n_all=$(_st_beyond_config)
  n_ctx=$(_st_kubectl_matching '^--context jtoye-stg-aks ')
  n_applied=0
  [ -f "$st/logs/applied-render.yaml" ] && n_applied=$(command grep -cE "image: ghcr.io/bralabee/jtoye-(core-java|frontend|edge-go):${sha}\$" "$st/logs/applied-render.yaml" || true)
  order=$(awk '/^preflight/ { printf "preflight," } /^kubectl .* apply -k / { printf "apply," }' "$st/logs/calls.log")
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(_st_snip "$ST_ERR")"
  elif [ "$last" != "DEPLOYED_SHA=$sha" ]; then why="last stdout line is '$(_st_snip "$last")', want DEPLOYED_SHA=$sha"
  elif [ "$(command grep -c '^DEPLOYED_SHA=' "$st/out" || true)" -ne 1 ]; then why="DEPLOYED_SHA lines=$(command grep -c '^DEPLOYED_SHA=' "$st/out" || true) (want 1)"
  elif [ "$(_st_kubectl_matching ' apply -k ')" -ne 1 ]; then why="apply -k calls=$(_st_kubectl_matching ' apply -k ') (want 1)"
  elif [ "$n_ctx" -ne "$n_all" ]; then why="$n_all cluster calls, only $n_ctx through --context jtoye-stg-aks (an ambient-context call)"
  elif [ "$n_applied" -ne 3 ]; then why="the applied render holds $n_applied app images at the tag (want 3)"
  elif [ "$order" != "preflight,apply," ]; then why="call order '$order' (want the preflight before the apply)"
  elif [ "$(_st_kubectl_matching 'rollout status deployment/(core-java|edge-go|frontend)( |$)')" -ne 3 ]; then why="rollout status calls=$(_st_kubectl_matching 'rollout status deployment/(core-java|edge-go|frontend)( |$)') (want 3)"
  fi
  if [ -z "$why" ]; then _st_result "PASS deployed-sha rc=0 last_line=DEPLOYED_SHA=$sha apply=1 applied_images_at_tag=3 rollouts=3 all_calls_via_context=$n_ctx/$n_all preflight_before_apply=yes"
  else _st_result "FAIL deployed-sha $why"; fails=$((fails + 1)); fi

  _st_render
  [ "$fails" -eq 0 ]
}

if [ "${1:-}" = "--selftest" ]; then
    selftest; exit $?
fi

set -e

ENVIRONMENT="${1:-staging}"
SERVICE="${2:-all}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}=== J'Toye OaaS Deployment ===${NC}"
echo "Environment: $ENVIRONMENT"
echo "Service: $SERVICE"
echo ""

# Validate environment.
#
# Phase 26: `dev` was a PHANTOM target. The old regex accepted it, but there is
# no k8s/dev overlay anywhere in the repo, so the run then applied k8s/base files
# directly into a jtoye-dev namespace with no overlay at all. Local Kubernetes has
# its own committed overlay and its own guarded entry point, so this script is now
# strictly staging/production and says so.
#
# This check deliberately stays ABOVE every cluster call, so a rejected target
# performs no cluster action whatsoever.
case "$ENVIRONMENT" in
    staging|production)
        ;;
    dev)
        echo -e "${RED}Error: 'dev' is not a target of this script.${NC}"
        echo "It never was: no k8s/dev overlay exists anywhere in the repo, so the"
        echo "old code applied k8s/base files with no overlay at all."
        echo "For a local Kubernetes rehearsal use the guarded entry point instead:"
        echo "    scripts/k8s-local-up.sh"
        exit 1
        ;;
    local)
        echo -e "${RED}Error: 'local' is not a target of this script.${NC}"
        echo "The k8s/local overlay does exist, but bringing it up needs the compose-XOR"
        echo "guard, the out-of-band secret bootstrap and locally built images. Use its"
        echo "own entry point, which does all three in order:"
        echo "    scripts/k8s-local-up.sh"
        exit 1
        ;;
    *)
        echo -e "${RED}Error: Invalid environment '${ENVIRONMENT}'. Use: staging or production${NC}"
        exit 1
        ;;
esac

# Validate kubectl access
if ! kubectl cluster-info &> /dev/null; then
    echo -e "${RED}Error: Cannot connect to Kubernetes cluster${NC}"
    echo "Please configure kubectl with: export KUBECONFIG=/path/to/kubeconfig"
    exit 1
fi

NAMESPACE="jtoye-${ENVIRONMENT}"

# Check if namespace exists
if ! kubectl get namespace "$NAMESPACE" &> /dev/null; then
    echo -e "${YELLOW}Warning: Namespace $NAMESPACE does not exist. Creating...${NC}"
    kubectl create namespace "$NAMESPACE"
    kubectl label namespace "$NAMESPACE" environment="$ENVIRONMENT"
fi

# Apply this environment's overlay through kustomize.
#
# Phase 26 fix: this used to be three raw `kubectl apply -f k8s/base/<svc>-deployment.yaml`
# calls plus two more for the base configmap and ingress. Applying base files
# directly BYPASSES KUSTOMIZE ENTIRELY, so every one of those applies silently
# skipped the overlay's namespace transformer, its ConfigMap patch and its
# image-tag pin — i.e. it could push base defaults and an unpinned image into a
# real environment while reporting success. One `apply -k` per environment is the
# only form that deploys what the overlay says.
echo -e "\n${BLUE}Applying k8s/${ENVIRONMENT} overlay...${NC}"
kubectl apply -k "$PROJECT_ROOT/k8s/${ENVIRONMENT}"

# Function to wait for one service's rollout, rolling it back on failure.
deploy_service() {
    local svc=$1
    echo -e "\n${BLUE}Waiting for ${svc} rollout...${NC}"

    if ! kubectl rollout status deployment/"${svc}" -n "$NAMESPACE" --timeout=10m; then
        echo -e "${RED}Error: Rollout failed for ${svc}${NC}"
        echo "Rolling back..."
        kubectl rollout undo deployment/"${svc}" -n "$NAMESPACE"
        return 1
    fi

    echo -e "${GREEN}✓ ${svc} deployed successfully${NC}"
}

# Wait for rollouts. SERVICE still selects which rollout(s) to wait on.
if [ "$SERVICE" = "all" ]; then
    # In order: core-java (backend), edge-go (gateway), frontend (UI)
    deploy_service "core-java" || exit 1
    deploy_service "edge-go" || exit 1
    deploy_service "frontend" || exit 1
else
    deploy_service "$SERVICE" || exit 1
fi

# Display pod status
echo -e "\n${BLUE}Current Pod Status:${NC}"
kubectl get pods -n "$NAMESPACE" -l app="$SERVICE" --field-selector=status.phase=Running

# Display service endpoints
echo -e "\n${BLUE}Service Endpoints:${NC}"
kubectl get svc -n "$NAMESPACE"

# Display ingress
echo -e "\n${BLUE}Ingress Configuration:${NC}"
kubectl get ingress -n "$NAMESPACE"

echo -e "\n${GREEN}✓ Deployment completed successfully!${NC}"
echo -e "\nTo view logs:"
echo -e "  kubectl logs -f deployment/${SERVICE} -n ${NAMESPACE}"
echo -e "\nTo check status:"
echo -e "  kubectl get all -n ${NAMESPACE}"
