#!/usr/bin/env bash
# deploy.sh — deploy J'Toye OaaS to STAGING or PRODUCTION, at one exact image tag,
# through one named kube context.
#
# For a LOCAL Kubernetes rehearsal use scripts/k8s-local-up.sh instead: local
# needs the compose-XOR guard, the out-of-band secret bootstrap and locally built
# images, none of which belong in a staging/production deploy path.
#
# USAGE
#   scripts/deploy.sh staging|production --context NAME --image-tag SHA [all]
#   scripts/deploy.sh staging|production --render-only --image-tag SHA
#   scripts/deploy.sh --selftest
#
#   --context NAME    REQUIRED for a deploy, no default (plan 29-14). An ambient
#                     kubectl context is how a command lands on the wrong cluster:
#                     on this host the kubeconfig's current-context is a dead
#                     cluster and the employer's cluster sits beside the staging
#                     one. Checked by scripts/lib/staging-kube-guard.sh before any
#                     cluster call: sipbihs2aks is refused by name, and the
#                     context's server must equal https://<aks_api_server_fqdn>:443
#                     from k8s/<env>/azure-identity.yaml (production has no such
#                     file yet, so a production deploy is refused until Phase 32
#                     writes one: fail closed, never "unchecked").
#   --image-tag SHA   REQUIRED: the full 40-lowercase-hex commit sha, the tag CI
#                     pushes for ghcr.io/bralabee/jtoye-{core-java,frontend,edge-go}.
#                     It is applied through a temporary WRAPPER kustomization in a
#                     mktemp -d dir whose one resource is k8s/<env> given RELATIVE
#                     to the wrapper (kustomize refuses an absolute base) and whose
#                     images block retags those three; pg-backup keeps its own tag
#                     and the committed kustomization.yaml is never edited.
#   --render-only     print the wrapper render and apply nothing: offline, no
#                     --context, no preflight.
#   [all]             the only service selector left. One apply -k retags all
#                     three images, so waiting on one rollout would certify a
#                     deploy that was not checked; a single-service selector is a
#                     usage error.
#   --selftest        the behaviour arms, against stubs (see selftest below).
#
# ORDER, each refusal before anything after it: flags -> environment -> image tag
# -> (render-only: render, exit) -> context guard -> staging preflight
# (scripts/staging-secrets-preflight.sh, plan 29-13: any non-zero, a VOID 2
# included, refuses) -> render the wrapper (a render that fails is VOID, before any
# apply) -> apply -k <wrapper> -> rollout status per retagged Deployment; a failed
# rollout undoes EVERY Deployment that apply changed (not only the failed one, and
# not one it left alone) -> rollout status for every OTHER Deployment and StatefulSet
# in the render (monitoring, Keycloak, Redis, the mail trap...), read from the render,
# sharing one 600 s budget, every failure named, rc 1, and NO undo: those were not
# retagged and the retagged apps are Ready (issue #969) -> the final stdout line
# `DEPLOYED_SHA=<sha>`, printed only after every workload the apply touched is Ready.
# That line is the record of what was applied;
# plan 29-14's tracer and every digest-parity check read it back instead of
# re-deriving a sha from HEAD.
#
# A real staging deploy runs with the jtoye-ops conda env active (the preflight
# reaches keyvault-chain.py through python3) and with
# JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94 exported by
# the CALLER: the preflight initialises the plan 29-01 guard itself, this script
# never sets the id, and a missing id surfaces as the preflight's REFUSED.
#
# EXIT CODES
#   0  rendered (--render-only), or applied and every rollout green
#   1  refused (environment, context guard, preflight), a retagged rollout failed (undone),
#      or another workload in the render did not become Ready (not undone, not certified)
#   2  usage error, or VOID (missing tooling, a render that failed)

set -uo pipefail

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
  local arms="no-context employer-context stale-server image-tag preflight-refusal render-only rollout-failure rollout-failure-first rollout-failure-unchanged deployed-sha monitoring-bad-image statefulset-bad-image render-void"
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
  cp -R "$PROJECT_ROOT/k8s" "$st/k8s.pristine"
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
    "\$REAL_KUBECTL" kustomize "\${@: -1}" > "\$STUB_LOG_DIR/applied-render.yaml" 2>"\$STUB_LOG_DIR/applied-render.err" || exit 1
    : > "\$STUB_LOG_DIR/applied" ;;
  *" get deployment/"*"metadata.generation"*)
    # The generation read behind "which Deployments did THIS apply change". 1 before the apply;
    # 2 after it, except for the Deployments an arm lists in STUB_UNCHANGED (an apply that
    # changed nothing leaves metadata.generation alone, e.g. the same sha redeployed).
    svc=""
    for a in "\$@"; do case "\$a" in deployment/*) svc="\${a#deployment/}" ;; esac; done
    if [ -f "\$STUB_LOG_DIR/applied" ] && case " \${STUB_UNCHANGED:-} " in *" \$svc "*) false ;; *) true ;; esac; then echo 2; else echo 1; fi
    exit 0 ;;
esac
case " \$* " in
  *" rollout status "*)
    # A workload whose image can never be pulled never becomes Ready: judge the rollout from the
    # RENDER THAT WAS APPLIED, so a Deployment pinned to a bad image fails here exactly as it does
    # on a cluster (ImagePullBackOff), with no per-arm switch naming the victim.
    tgt=""
    for a in "\$@"; do case "\$a" in deployment/*|statefulset/*) tgt="\$a" ;; esac; done
    if [ -f "\$STUB_LOG_DIR/applied-render.yaml" ] && awk -v want="\$tgt" -f "\$STUB_BAD_IMAGE_AWK" "\$STUB_LOG_DIR/applied-render.yaml"; then
      echo "stub: \$tgt never became Ready (ImagePullBackOff)" >&2; exit 1
    fi ;;
esac
if [ -n "\${STUB_FAIL_ROLLOUT:-}" ]; then
  case " \$* " in *" rollout status deployment/\${STUB_FAIL_ROLLOUT} "*) echo "stub: rollout of \${STUB_FAIL_ROLLOUT} failed" >&2; exit 1 ;; esac
fi
exit 0
STUB
  printf '#!%s\nprintf "%%s\\n" "$*" >> "$STUB_LOG_DIR/kubelogin.log"\nexit 0\n' "$real_bash" > "$st/stub/kubelogin"
  chmod +x "$st/stub/kubectl" "$st/stub/kubelogin"
  # Exit 0 when the workload named by -v want (kind/name, lower case) carries an image on the
  # reserved .invalid TLD in the render it is handed; 1 when it does not.
  cat > "$st/stub/bad-image.awk" <<'AWK'
/^---[ \t]*$/ { k = ""; n = "" }
/^kind: / { k = $2 }
/^  name: / { if (n == "" && (k == "Deployment" || k == "StatefulSet")) n = tolower(k) "/" $2 }
/^[ \t-]*image: / { if (n == want && $0 ~ /registry\.invalid\//) bad = 1 }
END { exit(bad ? 0 : 1) }
AWK

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
    rm -f "$st/logs/applied-render.yaml" "$st/logs/applied"
    command env -i PATH="$st/stub:$st/tools" HOME="$st/home" KUBECONFIG="$st/kubeconfig" TMPDIR="$st/tmp" \
      STUB_LOG_DIR="$st/logs" STUB_BAD_IMAGE_AWK="$st/stub/bad-image.awk" REAL_KUBECTL="$real_kubectl" ${extra[@]+"${extra[@]}"} \
      "$real_bash" "$st/repo/scripts/deploy.sh" "$@" > "$st/out" 2> "$st/err"; ST_RC=$?
    ST_OUT=$(cat "$st/out"); ST_ERR=$(cat "$st/err")
  }
  _st_lines() { local f="$st/logs/$1.log"; if [ -s "$f" ]; then command grep -c '' "$f"; else echo 0; fi; }
  _st_kubectl_matching() { if [ -s "$st/logs/kubectl.log" ]; then command grep -cE -- "$1" "$st/logs/kubectl.log" || true; else echo 0; fi; }
  _st_beyond_config() { if [ -s "$st/logs/kubectl.log" ]; then command grep -vc '^config view ' "$st/logs/kubectl.log" || true; else echo 0; fi; }
  _st_snip() { head -c 220 <<<"$1" | tr '\n' ' '; }
  # The three app images, judged by NAME rather than by line count: a render may
  # run one image in several containers (core-java's truststore-builder init
  # container runs the core-java image, plan 29-22), so the claim is "each of the
  # three names is at the tag, and no app-image line is at any other tag".
  _st_app_at_tag() { command grep -oE "image: ghcr.io/bralabee/jtoye-(core-java|frontend|edge-go):${sha}\$" | sort -u | wc -l; }
  _st_app_off_tag() { command grep -E "image: ghcr.io/bralabee/jtoye-(core-java|frontend|edge-go)[:@]" | command grep -vcE ":${sha}\$" || true; }

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
  local h_before h_after n_app n_off n_pg
  h_before=$(sha256sum "$st/repo/k8s/staging/kustomization.yaml" | cut -d' ' -f1)
  _st_run -- staging --render-only --image-tag "$sha"
  h_after=$(sha256sum "$st/repo/k8s/staging/kustomization.yaml" | cut -d' ' -f1)
  n_app=$(_st_app_at_tag <<<"$ST_OUT"); n_off=$(_st_app_off_tag <<<"$ST_OUT")
  n_pg=$(command grep -c 'image: ghcr.io/bralabee/jtoye-pg-backup:18-blob$' <<<"$ST_OUT" || true)
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(_st_snip "$ST_ERR")"
  elif [ -z "$ST_OUT" ]; then why="empty render"
  elif [ "$n_app" -ne 3 ]; then why="distinct app images at the tag=$n_app (want 3)"
  elif [ "$n_off" -ne 0 ]; then why="app image lines at another tag=$n_off (want 0)"
  elif [ "$n_pg" -ne 1 ]; then why="pg-backup 18-blob lines=$n_pg (want 1)"
  elif [ "$h_before" != "$h_after" ]; then why="k8s/staging/kustomization.yaml changed: $h_before -> $h_after"
  elif [ "$(_st_kubectl_matching ' ?(apply|rollout|create|delete|patch|label) ')" -ne 0 ]; then why="a mutating kubectl call: $(command grep -E '(apply|rollout|create|delete|patch|label) ' "$st/logs/kubectl.log" | head -1)"
  elif [ "$(_st_lines preflight)" -ne 0 ]; then why="the preflight ran"
  elif [ -n "$(ls -A "$st/tmp")" ]; then why="the wrapper dir was left behind: $(ls -A "$st/tmp" | head -1)"
  fi
  if [ -z "$why" ]; then _st_result "PASS render-only rc=0 app_images_at_tag=3 app_lines_off_tag=0 pg_backup_18_blob=1 kustomization_sha256=${h_after:0:16}(unchanged) mutating_calls=0"
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

  # --- rollout-failure-first: the FIRST Deployment failing must not strand the others
  # at the new sha. core-java fails; edge-go and frontend were applied in the same
  # `apply -k` and were never waited on. All three are undone, so the environment is
  # one version, not core-java old + gateway and UI new (round 1, F3).
  _st_run STUB_FAIL_ROLLOUT=core-java -- staging --context jtoye-stg-aks --image-tag "$sha"
  why=""
  local svc u
  if [ "$ST_RC" -eq 0 ]; then why="rc=0 after a failed core-java rollout"
  elif command grep -q '^DEPLOYED_SHA=' <<<"$ST_OUT"; then why="a DEPLOYED_SHA line was printed after a failed rollout"
  fi
  if [ -z "$why" ]; then
    for svc in core-java edge-go frontend; do
      u=$(_st_kubectl_matching "rollout undo deployment/${svc}( |\$)")
      if [ "$u" -ne 1 ]; then why="rollout undo deployment/${svc} calls=$u (want 1: core-java failed, so the whole apply is undone, not just core-java)"; break; fi
    done
  fi
  if [ -z "$why" ]; then _st_result "PASS rollout-failure-first rc=$ST_RC undo_core-java=1 undo_edge-go=1 undo_frontend=1 deployed_sha_lines=0"
  else _st_result "FAIL rollout-failure-first $why"; fails=$((fails + 1)); fi

  # --- rollout-failure-unchanged: undo only what THIS apply changed. The same sha
  # redeployed leaves edge-go and frontend's metadata.generation alone; undoing them
  # would roll two healthy, current Deployments back to an OLDER revision.
  _st_run STUB_FAIL_ROLLOUT=core-java STUB_UNCHANGED="edge-go frontend" -- staging --context jtoye-stg-aks --image-tag "$sha"
  why=""
  if [ "$ST_RC" -eq 0 ]; then why="rc=0 after a failed core-java rollout"
  elif [ "$(_st_kubectl_matching 'rollout undo deployment/core-java( |$)')" -ne 1 ]; then why="rollout undo deployment/core-java calls=$(_st_kubectl_matching 'rollout undo deployment/core-java( |$)') (want 1)"
  elif [ "$(_st_kubectl_matching 'rollout undo deployment/(edge-go|frontend)( |$)')" -ne 0 ]; then why="an unchanged Deployment was rolled back to an older revision: $(command grep -E 'rollout undo' "$st/logs/kubectl.log" | tr '\n' ' ')"
  fi
  if [ -z "$why" ]; then _st_result "PASS rollout-failure-unchanged rc=$ST_RC undo_core-java=1 undo_unchanged=0"
  else _st_result "FAIL rollout-failure-unchanged $why"; fails=$((fails + 1)); fi

  # --- deployed-sha: every rollout green -> the LAST stdout line is the record
  _st_run -- staging --context jtoye-stg-aks --image-tag "$sha"
  why=""
  local last n_ctx n_all n_applied n_applied_off order
  last=$(tail -n 1 "$st/out")
  # The offline render the wait list is read from is the one call that needs no cluster context.
  n_all=$(( $(_st_beyond_config) - $(_st_kubectl_matching '^kustomize ') ))
  n_ctx=$(_st_kubectl_matching '^--context jtoye-stg-aks ')
  n_applied=0; n_applied_off=-1; n_wl=0
  if [ -f "$st/logs/applied-render.yaml" ]; then
    n_wl=$(command grep -cE '^kind: (Deployment|StatefulSet)$' "$st/logs/applied-render.yaml" || true)
    n_applied=$(_st_app_at_tag < "$st/logs/applied-render.yaml"); n_applied_off=$(_st_app_off_tag < "$st/logs/applied-render.yaml")
  fi
  order=$(awk '/^preflight/ { printf "preflight," } /^kubectl .* apply -k / { printf "apply," }' "$st/logs/calls.log")
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(_st_snip "$ST_ERR")"
  elif [ "$last" != "DEPLOYED_SHA=$sha" ]; then why="last stdout line is '$(_st_snip "$last")', want DEPLOYED_SHA=$sha"
  elif [ "$(command grep -c '^DEPLOYED_SHA=' "$st/out" || true)" -ne 1 ]; then why="DEPLOYED_SHA lines=$(command grep -c '^DEPLOYED_SHA=' "$st/out" || true) (want 1)"
  elif [ "$(_st_kubectl_matching ' apply -k ')" -ne 1 ]; then why="apply -k calls=$(_st_kubectl_matching ' apply -k ') (want 1)"
  elif [ "$n_ctx" -ne "$n_all" ]; then why="$n_all cluster calls, only $n_ctx through --context jtoye-stg-aks (an ambient-context call)"
  elif [ "$n_applied" -ne 3 ]; then why="the applied render holds $n_applied distinct app images at the tag (want 3)"
  elif [ "$n_applied_off" -ne 0 ]; then why="the applied render holds $n_applied_off app image line(s) at another tag (want 0)"
  elif [ "$order" != "preflight,apply," ]; then why="call order '$order' (want the preflight before the apply)"
  elif [ "$(_st_kubectl_matching 'rollout status deployment/(core-java|edge-go|frontend)( |$)')" -ne 3 ]; then why="rollout status calls=$(_st_kubectl_matching 'rollout status deployment/(core-java|edge-go|frontend)( |$)') (want 3)"
  elif [ "$n_wl" -lt 12 ]; then why="the applied render holds only $n_wl Deployments/StatefulSets (want the staging 12: the control that the monitoring set is in the render)"
  elif [ "$(_st_kubectl_matching ' rollout status ')" -ne "$n_wl" ]; then why="rollout status calls=$(_st_kubectl_matching ' rollout status ') (want $n_wl: every Deployment and StatefulSet in the render, not only the three retagged)"
  elif [ "$(_st_kubectl_matching ' rollout status deployment/(prometheus|alertmanager|grafana)( |$)')" -ne 3 ]; then why="the monitoring Deployments were not all waited on"
  elif [ "$(_st_kubectl_matching ' rollout status .* --timeout=[0-9]+[sm]$')" -ne "$n_wl" ]; then why="a rollout status call carries no bounded --timeout"
  elif [ "$(_st_kubectl_matching 'rollout undo')" -ne 0 ]; then why="a rollout undo on a green deploy"
  fi
  if [ -z "$why" ]; then _st_result "PASS deployed-sha rc=0 last_line=DEPLOYED_SHA=$sha apply=1 applied_images_at_tag=3 rollouts=$n_wl(3 retagged + $((n_wl - 3)) others, all --timeout bounded) all_calls_via_context=$n_ctx/$n_all preflight_before_apply=yes"
  else _st_result "FAIL deployed-sha $why"; fails=$((fails + 1)); fi

  # --- monitoring-bad-image: the three retagged rollouts are green, but a MONITORING Deployment
  # (prometheus) is pinned to an image that can never be pulled. Issue #969: deploy.sh used to
  # wait on core-java, edge-go and frontend only, print DEPLOYED_SHA and exit 0 over it. Now it
  # waits on every Deployment and StatefulSet in the render, refuses to certify (no
  # DEPLOYED_SHA, rc 1) and names the workload. It does NOT undo anything: the retagged apps
  # are healthy and a broken Prometheus image is not a reason to roll them back.
  local pf="$st/repo/k8s/components/monitoring/prometheus.yaml" h_pf_before h_pf_after n_wl_bad
  h_pf_before=$(sha256sum "$st/k8s.pristine/components/monitoring/prometheus.yaml" | cut -d' ' -f1)
  sed -i 's|image: prom/prometheus:[^[:space:]]*|image: registry.invalid/prometheus:does-not-exist|' "$pf"
  _st_run -- staging --context jtoye-stg-aks --image-tag "$sha"
  n_wl_bad=$(command grep -c 'registry.invalid/prometheus:does-not-exist' "$st/logs/applied-render.yaml" || true)
  rm -rf "$st/repo/k8s" && cp -R "$st/k8s.pristine" "$st/repo/k8s"
  h_pf_after=$(sha256sum "$pf" | cut -d' ' -f1)
  why=""
  if [ "$h_pf_before" != "$h_pf_after" ]; then why="the fixture was not restored by content: $h_pf_before -> $h_pf_after"
  elif [ "$n_wl_bad" -ne 1 ]; then why="the bad image is in the applied render $n_wl_bad times (want 1: the fixture did not take)"
  elif [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1) with prometheus on a bad image: $(_st_snip "$ST_ERR")"
  elif command grep -q '^DEPLOYED_SHA=' <<<"$ST_OUT"; then why="a DEPLOYED_SHA line was certified over a Deployment that never became Ready"
  elif [ "$(_st_kubectl_matching 'rollout status deployment/prometheus( |$)')" -ne 1 ]; then why="rollout status deployment/prometheus calls=$(_st_kubectl_matching 'rollout status deployment/prometheus( |$)') (want 1)"
  elif ! command grep -qF 'deployment/prometheus' <<<"$ST_ERR"; then why="stderr does not name deployment/prometheus: $(_st_snip "$ST_ERR")"
  elif [ "$(_st_kubectl_matching 'rollout status deployment/(core-java|edge-go|frontend)( |$)')" -ne 3 ]; then why="the retagged rollouts were not all waited on first"
  elif [ "$(_st_kubectl_matching 'rollout status deployment/(grafana|alertmanager)( |$)')" -ne 2 ]; then why="the other monitoring Deployments were not still waited on after the first failure"
  elif [ "$(_st_kubectl_matching 'rollout undo')" -ne 0 ]; then why="rollout undo called $(_st_kubectl_matching 'rollout undo') time(s); undo is scoped to the retagged apps and they were healthy"
  fi
  if [ -z "$why" ]; then _st_result "PASS monitoring-bad-image rc=$ST_RC deployed_sha_lines=0 prometheus_waited=1 other_monitoring_still_waited=2 undo_calls=0"
  else _st_result "FAIL monitoring-bad-image $why"; fails=$((fails + 1)); fi

  # --- statefulset-bad-image: a StatefulSet in the render is waited on by kind, not skipped.
  # The staging render carries none today; the fixture adds one so the kind cannot be forgotten.
  printf 'apiVersion: apps/v1\nkind: StatefulSet\nmetadata:\n  name: zz-fixture\nspec:\n  serviceName: zz-fixture\n  replicas: 1\n  selector:\n    matchLabels:\n      app: zz-fixture\n  template:\n    metadata:\n      labels:\n        app: zz-fixture\n    spec:\n      containers:\n        - name: c\n          image: registry.invalid/zz-fixture:none\n' \
    > "$st/repo/k8s/staging/zz-fixture-sts.yaml"
  sed -i 's|^resources:$|resources:\n  - zz-fixture-sts.yaml|' "$st/repo/k8s/staging/kustomization.yaml"
  _st_run -- staging --context jtoye-stg-aks --image-tag "$sha"
  rm -rf "$st/repo/k8s" && cp -R "$st/k8s.pristine" "$st/repo/k8s"
  why=""
  if [ -e "$st/repo/k8s/staging/zz-fixture-sts.yaml" ]; then why="the fixture file survived the restore"
  elif [ "$ST_RC" -ne 1 ]; then why="rc=$ST_RC (want 1) with a StatefulSet on a bad image: $(_st_snip "$ST_ERR")"
  elif command grep -q '^DEPLOYED_SHA=' <<<"$ST_OUT"; then why="a DEPLOYED_SHA line was certified over a StatefulSet that never became Ready"
  elif [ "$(_st_kubectl_matching 'rollout status statefulset/zz-fixture( |$)')" -ne 1 ]; then why="rollout status statefulset/zz-fixture calls=$(_st_kubectl_matching 'rollout status statefulset/zz-fixture( |$)') (want 1)"
  elif [ "$(_st_kubectl_matching 'rollout undo')" -ne 0 ]; then why="rollout undo called on a StatefulSet failure"
  fi
  if [ -z "$why" ]; then _st_result "PASS statefulset-bad-image rc=$ST_RC statefulset_waited=1 deployed_sha_lines=0 undo_calls=0"
  else _st_result "FAIL statefulset-bad-image $why"; fails=$((fails + 1)); fi

  # --- render-void: a render that kustomize cannot build is VOID (rc 2) BEFORE the apply.
  # The wait list is read from the render, so a render that fails must not fall back to the three
  # hard-coded names and apply anyway.
  sed -i 's|^resources:$|resources:\n  - zz-missing.yaml|' "$st/repo/k8s/staging/kustomization.yaml"
  _st_run -- staging --context jtoye-stg-aks --image-tag "$sha"
  rm -rf "$st/repo/k8s" && cp -R "$st/k8s.pristine" "$st/repo/k8s"
  why=""
  if [ "$ST_RC" -ne 2 ]; then why="rc=$ST_RC (want 2, VOID): $(_st_snip "$ST_ERR")"
  elif ! command grep -qF 'VOID' <<<"$ST_ERR"; then why="stderr lacks VOID: $(_st_snip "$ST_ERR")"
  elif [ "$(_st_kubectl_matching ' apply ')" -ne 0 ] || [ "$(_st_kubectl_matching ' rollout ')" -ne 0 ]; then why="apply/rollout called after a render that failed"
  elif command grep -q '^DEPLOYED_SHA=' <<<"$ST_OUT"; then why="a DEPLOYED_SHA line after a failed render"
  fi
  if [ -z "$why" ]; then _st_result "PASS render-void rc=$ST_RC apply_calls=0 rollout_calls=0 deployed_sha_lines=0"
  else _st_result "FAIL render-void $why"; fails=$((fails + 1)); fi

  _st_render
  [ "$fails" -eq 0 ]
}

# ===========================================================================
# The deploy.
# ===========================================================================
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

APP_IMAGES=(ghcr.io/bralabee/jtoye-core-java ghcr.io/bralabee/jtoye-frontend ghcr.io/bralabee/jtoye-edge-go)
# In order: core-java (backend), edge-go (gateway), frontend (UI).
ROLLOUTS=(core-java edge-go frontend)

usage() { command sed -n '/^# USAGE/,/^# ORDER/p' "$SCRIPT_PATH" | command sed '$d' | command sed 's/^# \{0,1\}//'; }
usage_error() { echo "USAGE ERROR: $*" >&2; usage >&2; exit 2; }
void() { echo "VOID: $*" >&2; exit 2; }

ENVIRONMENT=""
SERVICE=""
KUBE_CONTEXT=""
CONTEXT_GIVEN=0
IMAGE_TAG=""
TAG_GIVEN=0
RENDER_ONLY=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        --selftest)
            [ "$#" -eq 1 ] || usage_error "--selftest takes no other argument"
            selftest; exit $? ;;
        --context)
            shift; [ "$#" -gt 0 ] || usage_error "--context needs a name"
            KUBE_CONTEXT="$1"; CONTEXT_GIVEN=1 ;;
        --image-tag)
            shift; [ "$#" -gt 0 ] || usage_error "--image-tag needs a 40-hex commit sha"
            IMAGE_TAG="$1"; TAG_GIVEN=1 ;;
        --render-only) RENDER_ONLY=1 ;;
        -h|--help) usage; exit 0 ;;
        -*) usage_error "unknown flag '$1'" ;;
        *)
            if [ -z "$ENVIRONMENT" ]; then ENVIRONMENT="$1"
            elif [ -z "$SERVICE" ]; then SERVICE="$1"
            else usage_error "unexpected argument '$1'"; fi ;;
    esac
    shift
done

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
    "")
        usage_error "an environment is required: staging or production"
        ;;
    dev)
        echo -e "${RED}Error: 'dev' is not a target of this script.${NC}" >&2
        echo "It never was: no k8s/dev overlay exists anywhere in the repo, so the" >&2
        echo "old code applied k8s/base files with no overlay at all." >&2
        echo "For a local Kubernetes rehearsal use the guarded entry point instead:" >&2
        echo "    scripts/k8s-local-up.sh" >&2
        exit 1
        ;;
    local)
        echo -e "${RED}Error: 'local' is not a target of this script.${NC}" >&2
        echo "The k8s/local overlay does exist, but bringing it up needs the compose-XOR" >&2
        echo "guard, the out-of-band secret bootstrap and locally built images. Use its" >&2
        echo "own entry point, which does all three in order:" >&2
        echo "    scripts/k8s-local-up.sh" >&2
        exit 1
        ;;
    *)
        echo -e "${RED}Error: Invalid environment '${ENVIRONMENT}'. Use: staging or production${NC}" >&2
        exit 1
        ;;
esac

case "${SERVICE:-all}" in
    all) ;;
    *) usage_error "service '${SERVICE}' cannot be deployed alone: one apply -k retags core-java, frontend and edge-go together, so every rollout is waited on (omit it, or pass 'all')" ;;
esac

[ "$TAG_GIVEN" -eq 1 ] || usage_error "--image-tag is required: the full 40-hex commit sha CI built (no default: the committed 2.1.0 is not a deploy target)"
[[ "$IMAGE_TAG" =~ ^[0-9a-f]{40}$ ]] \
    || usage_error "--image-tag '${IMAGE_TAG}' is not a full 40-lowercase-hex commit sha (no short sha, branch name or latest: CI tags every image with the full commit sha)"

NAMESPACE="jtoye-${ENVIRONMENT}"
OVERLAY="$PROJECT_ROOT/k8s/${ENVIRONMENT}"
IDENTITY_FILE="$OVERLAY/azure-identity.yaml"

command -v kubectl >/dev/null 2>&1 || void "kubectl not on PATH"
command -v realpath >/dev/null 2>&1 || void "realpath not on PATH"
[ -f "$OVERLAY/kustomization.yaml" ] || void "no overlay at $OVERLAY"

# The wrapper: one relative resource (the overlay) and the three retags. Its dir
# is removed on every exit path.
WRAPPER=$(umask 077 && mktemp -d "${TMPDIR:-/tmp}/jtoye-deploy-wrap.XXXXXXXX") || void "mktemp -d failed"
# shellcheck disable=SC2064
trap "rm -rf '$WRAPPER'" EXIT
REL_OVERLAY=$(realpath --relative-to="$WRAPPER" "$OVERLAY") || void "realpath --relative-to failed for $OVERLAY"
{
    echo "# Generated by scripts/deploy.sh for one deploy; never committed."
    echo "apiVersion: kustomize.config.k8s.io/v1beta1"
    echo "kind: Kustomization"
    echo "resources:"
    echo "  - ${REL_OVERLAY}"
    echo "images:"
    for img in "${APP_IMAGES[@]}"; do
        echo "  - name: ${img}"
        echo "    newTag: \"${IMAGE_TAG}\""
    done
} > "$WRAPPER/kustomization.yaml"

if [ "$RENDER_ONLY" -eq 1 ]; then
    RENDER=$(kubectl kustomize "$WRAPPER" 2>"$WRAPPER/render.err"); rc=$?
    if [ "$rc" -ne 0 ] || [ -z "$RENDER" ]; then
        cat "$WRAPPER/render.err" >&2
        void "kubectl kustomize of the wrapper failed (rc=$rc) or rendered nothing"
    fi
    printf '%s\n' "$RENDER"
    exit 0
fi

[ "$CONTEXT_GIVEN" -eq 1 ] && [ -n "$KUBE_CONTEXT" ] \
    || usage_error "--context is required (no default: an ambient context is how a command lands on the wrong cluster)"

# shellcheck source=scripts/lib/staging-kube-guard.sh
. "$PROJECT_ROOT/scripts/lib/staging-kube-guard.sh" || void "could not source scripts/lib/staging-kube-guard.sh"
rc=0; staging_kube_guard "$KUBE_CONTEXT" "$IDENTITY_FILE" || rc=$?
[ "$rc" -eq 0 ] || exit "$rc"

echo -e "${BLUE}=== J'Toye OaaS Deployment ===${NC}"
echo "Environment: $ENVIRONMENT"
echo "Context:     $KUBE_CONTEXT (guard passed)"
echo "Image tag:   $IMAGE_TAG"
echo ""

if [ "$ENVIRONMENT" = staging ]; then
    echo -e "${BLUE}Staging secrets preflight (scripts/staging-secrets-preflight.sh)...${NC}"
    rc=0; bash "$PROJECT_ROOT/scripts/staging-secrets-preflight.sh" || rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "REFUSED [preflight]: scripts/staging-secrets-preflight.sh exited $rc; a half-configured staging is not deployed (rc 2 is VOID, and VOID refuses too)" >&2
        exit 1
    fi
fi

kc() { kubectl --context "$KUBE_CONTEXT" "$@"; }

# Apply this environment's overlay, through the wrapper, through kustomize.
#
# Phase 26 fix: this used to be three raw `kubectl apply -f k8s/base/<svc>-deployment.yaml`
# calls plus two more for the base configmap and ingress. Applying base files
# directly BYPASSES KUSTOMIZE ENTIRELY, so every one of those applies silently
# skipped the overlay's namespace transformer, its ConfigMap patch and its
# image-tag pin — i.e. it could push base defaults and an unpinned image into a
# real environment while reporting success. One `apply -k` per environment is the
# only form that deploys what the overlay says. The overlay carries its own
# Namespace, so the old create-namespace step is gone with the ambient context.
# metadata.generation moves when, and only when, the API server accepts a CHANGED spec, so
# before-versus-after is "did THIS apply change this Deployment". It is what a rollback is
# scoped to: re-deploying the same sha changes nothing, and `rollout undo` on a Deployment this
# run did not change would roll a healthy, current service back to an OLDER revision.
# --ignore-not-found: a Deployment that does not exist yet reads as generation 0 (a first deploy);
# any other read failure refuses BEFORE the apply, while nothing has changed.
read_generation() { # read_generation <svc> -> generation on stdout; rc 1 when it cannot be read
    local out rc=0
    out=$(kc -n "$NAMESPACE" get deployment/"$1" --ignore-not-found -o jsonpath='{.metadata.generation}') || rc=$?
    [ "$rc" -eq 0 ] || return 1
    printf '%s\n' "${out:-0}"
}
# Every workload the render carries, read BEFORE the apply (issue #969). `apply -k` applies the whole
# overlay, so a deploy is only certified once everything it applied is Ready, not just the three
# images it retagged: on 2026-10-19 DEPLOYED_SHA was printed while the new alertmanager and grafana
# pods were still ContainerCreating. Read from the render, not from a list kept here, so a
# Deployment or StatefulSet the overlay gains is waited on without anyone remembering to add it.
# kustomize emits keys in sorted order, so `kind` precedes `metadata` and the first two-space
# `name:` of a Deployment/StatefulSet document is its metadata.name.
RENDER=$(kubectl kustomize "$WRAPPER" 2>"$WRAPPER/render.err"); rc=$?
if [ "$rc" -ne 0 ] || [ -z "$RENDER" ]; then
    cat "$WRAPPER/render.err" >&2
    void "kubectl kustomize of the wrapper failed (rc=$rc) or rendered nothing; nothing was applied"
fi
mapfile -t WORKLOADS < <(printf '%s\n' "$RENDER" | awk '
    /^---[ \t]*$/ { k = ""; n = "" }
    /^kind: / { k = $2 }
    /^  name: / { if (n == "" && (k == "Deployment" || k == "StatefulSet")) { n = $2; gsub(/"/, "", n); print tolower(k) "/" n } }')
[ "${#WORKLOADS[@]}" -gt 0 ] || void "the render holds no Deployment or StatefulSet; nothing was applied"
for svc in "${ROLLOUTS[@]}"; do
    case " ${WORKLOADS[*]} " in
        *" deployment/${svc} "*) ;;
        *) void "the render does not carry deployment/${svc}, which this script retags; nothing was applied" ;;
    esac
done
# Everything in the render that this apply did NOT retag. No rollback is scoped to these.
OTHER_WORKLOADS=()
for w in "${WORKLOADS[@]}"; do
    case "$w" in
        deployment/core-java|deployment/edge-go|deployment/frontend) ;;
        *) OTHER_WORKLOADS+=("$w") ;;
    esac
done

declare -A GEN_BEFORE=()
for svc in "${ROLLOUTS[@]}"; do
    GEN_BEFORE[$svc]=$(read_generation "$svc") \
        || { echo -e "${RED}REFUSED [generation]: could not read deployment/${svc} generation before the apply; nothing was applied${NC}" >&2; exit 1; }
done

echo -e "\n${BLUE}Applying k8s/${ENVIRONMENT} at ${IMAGE_TAG}...${NC}"
rc=0; kc apply -k "$WRAPPER" || rc=$?
if [ "$rc" -ne 0 ]; then
    echo -e "${RED}Error: kubectl apply -k failed (rc=$rc)${NC}" >&2
    exit 1
fi

# The Deployments this apply changed. A generation that cannot be read AFTER the apply counts
# as changed: undoing one too many is recoverable, leaving one stranded is not noticed.
CHANGED=()
for svc in "${ROLLOUTS[@]}"; do
    gen_after=$(read_generation "$svc") || gen_after="unreadable"
    if [ "$gen_after" != "${GEN_BEFORE[$svc]}" ]; then CHANGED+=("$svc"); fi
done
echo "Changed by this apply: ${CHANGED[*]:-(none)}"

# One apply updates all three Deployments, so a failed rollout is a failed DEPLOY, not a failed
# service: undoing only the one that failed leaves the others at the new sha (mixed versions),
# and exiting before waiting on them leaves their state unrecorded. Undo every Deployment this
# apply changed (Round 1, F3), then say so.
rollback_changed() {
    local u
    echo "Rolling back every Deployment this apply changed: ${CHANGED[*]:-(none)}" >&2
    for u in ${CHANGED[@]+"${CHANGED[@]}"}; do
        kc -n "$NAMESPACE" rollout undo deployment/"${u}" || echo "rollout undo of ${u} failed too" >&2
    done
}

# Wait for one Deployment's rollout.
deploy_service() {
    local svc=$1
    echo -e "\n${BLUE}Waiting for ${svc} rollout...${NC}"
    if ! kc -n "$NAMESPACE" rollout status deployment/"${svc}" --timeout=10m; then
        echo -e "${RED}Error: Rollout failed for ${svc}${NC}" >&2
        return 1
    fi
    echo -e "${GREEN}✓ ${svc} deployed successfully${NC}"
}

for svc in "${ROLLOUTS[@]}"; do
    deploy_service "$svc" || { rollback_changed; exit 1; }
done

# The rest of the render: monitoring, Keycloak, Redis, the mail trap, the secret sync and anything
# else the overlay carries (issue #969). They share ONE bounded budget rather than a timeout each,
# so twelve broken workloads cost ten minutes, not two hours; once it is spent the remaining
# workloads get a one-second look, which a Ready one passes at once. Every workload is checked and
# every failure is named, instead of stopping at the first.
#
# NO `rollout undo` here, on purpose: the undo above is scoped to the Deployments this apply
# RETAGGED (core-java, edge-go, frontend), and they are Ready at this point. Rolling healthy
# application pods back to an older sha because Prometheus will not pull its image would trade a
# monitoring fault for an application one. The deploy is simply not certified: rc 1, no
# DEPLOYED_SHA, and the operator is told which workload to look at.
EXTRA_WAIT_BUDGET_SECONDS=600
if [ "${#OTHER_WORKLOADS[@]}" -gt 0 ]; then
    echo -e "\n${BLUE}Waiting for the other ${#OTHER_WORKLOADS[@]} workload(s) in the render (shared budget ${EXTRA_WAIT_BUDGET_SECONDS}s)...${NC}"
    extra_deadline=$((SECONDS + EXTRA_WAIT_BUDGET_SECONDS))
    NOT_READY=()
    for w in "${OTHER_WORKLOADS[@]}"; do
        remaining=$((extra_deadline - SECONDS)); [ "$remaining" -ge 1 ] || remaining=1
        if kc -n "$NAMESPACE" rollout status "$w" --timeout="${remaining}s"; then
            echo -e "${GREEN}✓ ${w} ready${NC}"
        else
            echo -e "${RED}Error: ${w} did not become Ready${NC}" >&2
            NOT_READY+=("$w")
        fi
    done
    if [ "${#NOT_READY[@]}" -gt 0 ]; then
        echo -e "${RED}NOT CERTIFIED: ${NOT_READY[*]} did not become Ready. ${ROLLOUTS[*]} are Ready at ${IMAGE_TAG} and were left there (nothing retagged failed, so nothing is undone); no DEPLOYED_SHA is recorded.${NC}" >&2
        exit 1
    fi
fi

# Informational read-outs; a failure here does not unmake a green rollout.
echo -e "\n${BLUE}Current Pod Status:${NC}"
kc get pods -n "$NAMESPACE" --field-selector=status.phase=Running || true
echo -e "\n${BLUE}Service Endpoints:${NC}"
kc get svc -n "$NAMESPACE" || true
echo -e "\n${BLUE}Ingress Configuration:${NC}"
kc get ingress -n "$NAMESPACE" || true
echo -e "\n${GREEN}✓ Deployment completed successfully!${NC}"

# THE RECORD. Always the last stdout line, and only after every rollout is green.
echo "DEPLOYED_SHA=${IMAGE_TAG}"
