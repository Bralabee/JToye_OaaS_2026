#!/usr/bin/env bash
# staging-bootstrap.sh — install the in-cluster platform the staging app needs
# before it can be reached (Phase 29, plan 29-03; DPLY-01, DPLY-02 #99).
#
# WHAT IT INSTALLS, IN THIS ORDER (--stage all)
#
#   namespace          jtoye-staging, created if absent; its
#                      kubernetes.io/metadata.name label read back (every
#                      NetworkPolicy namespaceSelector depends on that label)
#   cert-manager       v1.21.2 release manifest, by pinned URL + sha256; waits for
#                      the cert-manager Deployments (the webhook included) to be
#                      Available, then proves the admission webhook ANSWERS
#   rabbitmq-operator  RabbitMQ cluster operator v2.23.1, by pinned URL + sha256.
#                      After cert-manager: the operator manifest carries
#                      cert-manager.io/v1 objects (3 in v2.23.1, measured)
#   traefik            k8s/platform/traefik (D-29); waits until the Service holds
#                      the static IP the identity file names
#   issuers            k8s/platform/cert-manager (ClusterIssuers), both Ready
#   ci-rbac            k8s/platform/rbac with the CI principal's object id
#                      substituted; REFUSED if the token survives
#   acme-probe         k8s/platform/acme-probe; waits for the staging certificate,
#                      then requires netexec's answer over HTTPS through the edge
#
#   acme-probe-delete  NOT part of `all`: run it explicitly once the probe's
#                      evidence is recorded (plan 29-11).
#
# THIRD-PARTY MANIFESTS STAY OUTSIDE k8s/ (RESEARCH Pattern 3). The render gates
# discover kustomizations at depth 2 and render-golden.sh compares staging and
# production byte for byte, so ~1.4 MB of upstream CRDs inside a kustomization
# would make every upstream bump an unreviewable golden diff. They are applied
# from here, by pinned URL plus recorded sha256, and EVERY artefact the selected
# stages need is downloaded and verified BEFORE any stage runs: verifying just in
# time would let a bad second artefact leave the first one installed.
#
# THE GUARDS COME FIRST (T-29-09). Before anything touches a cluster the script
# calls staging_kube_guard (scripts/lib/staging-kube-guard.sh), the ONE
# implementation of the kube-context checks for this phase: --context present,
# not the employer's sipbihs2aks, the identity file readable with exactly one
# aks_api_server_fqdn, the context's API server byte-equal to
# https://<that FQDN>:443 (this is what refuses the stale `jtoye-staging` context
# left on this host by the deleted August cluster), and kubelogin on PATH (Entra
# only, D-32). Plans 29-04 and 29-06 source the same function.
#
# NO AZURE CALL. The identity file (k8s/staging/azure-identity.yaml) is written by
# plan 29-09 from subscription-pinned read-backs; this script only reads its
# aks_api_server_fqdn, ingress_ip_address and ci_principal_object_id keys.
#
# Every kubectl call passes --context explicitly; `kubectl config use-context` is
# never called.
#
# USAGE
#   scripts/staging-bootstrap.sh --context NAME [--stage STAGE] [--dry-run]
#                                [--identity-file PATH]
#   scripts/staging-bootstrap.sh --selftest
#
#     --context NAME        REQUIRED, no default
#     --stage STAGE         namespace | cert-manager | rabbitmq-operator | traefik |
#                           issuers | ci-rbac | acme-probe | acme-probe-delete | all
#                           (default all)
#     --dry-run             run the guards, then print the ordered stage plan and
#                           every command a real run would issue; downloads
#                           nothing and calls kubectl for nothing but the guard's
#                           local `config view` read
#     --identity-file PATH  default k8s/staging/azure-identity.yaml (plan 29-09)
#     --selftest            run the named refusal arms against stub kubectl,
#                           kubelogin and curl, a temp KUBECONFIG and a temp
#                           identity file; one line per arm, PASS or FAIL.
#                           STAGING_BOOTSTRAP_SELFTEST_FORMAT=tap renders TAP 13
#
# EXIT CODES: 0 = done, 1 = a guard refused or a verification failed,
#             2 = usage / tooling / VOID (could not evaluate).

set -euo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(dirname "$SCRIPT_PATH")"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=lib/staging-kube-guard.sh
source "$SCRIPT_DIR/lib/staging-kube-guard.sh"

ok()     { echo "OK: $*"; }
step()   { echo; echo "=== $* ==="; }
refuse() { local arm="$1"; shift; echo "REFUSED [$arm]: $*" >&2; exit 1; }
fail()   { echo "FAIL: $*" >&2; exit 1; }
void()   { echo "VOID: $*" >&2; exit 2; }

# ---------------------------------------------------------------------------
# Pinned upstream artefacts. Measured 2026-10-18 (RESEARCH Standard Stack, then
# re-downloaded by the executor into an isolated scratch directory and hashed):
#   cert-manager v1.21.2   cert-manager.yaml     1034400 bytes
#     sha256 e03b668ec8675214af6b0a671699d088f2601fa3878e0dbe1b41d3feafd1879f
#   rabbitmq operator v2.23.1  cluster-operator.yml  360452 bytes
#     sha256 ae35a547751656bc64bb734026249a845d226d96aba5a73783e05c5ec3444687
# A version bump changes the URL AND the sha256 in the same commit, and the
# horizon rows cert-manager / rabbitmq-cluster-operator in
# infra/dependency-horizons.yaml move with them (H-5 checks the pin at the line).
# ---------------------------------------------------------------------------
CERT_MANAGER_VERSION="v1.21.2"
CERT_MANAGER_URL="https://github.com/cert-manager/cert-manager/releases/download/v1.21.2/cert-manager.yaml"
CERT_MANAGER_SHA256="e03b668ec8675214af6b0a671699d088f2601fa3878e0dbe1b41d3feafd1879f"
RABBITMQ_OPERATOR_VERSION="v2.23.1"
RABBITMQ_OPERATOR_URL="https://github.com/rabbitmq/cluster-operator/releases/download/v2.23.1/cluster-operator.yml"
RABBITMQ_OPERATOR_SHA256="ae35a547751656bc64bb734026249a845d226d96aba5a73783e05c5ec3444687"

NAMESPACE="jtoye-staging"
CERT_MANAGER_NS="cert-manager"
RABBITMQ_OPERATOR_NS="rabbitmq-system"
TRAEFIK_NS="traefik"
PROBE_HOST="app-staging.olajay.co.uk"
CI_TOKEN="__CI_PRINCIPAL_OBJECT_ID__"
ALL_STAGES="namespace cert-manager rabbitmq-operator traefik issuers ci-rbac acme-probe"
KNOWN_STAGES="$ALL_STAGES acme-probe-delete all"

WAIT_SECONDS_DEPLOY=600     # 10 minutes: cert-manager webhook, operator, Traefik
WAIT_SECONDS_IP=600         # 10 minutes: static IP on the Traefik Service
WAIT_SECONDS_CERT=900       # 15 minutes: the probe's staging certificate
POLL_SECONDS="${STAGING_BOOTSTRAP_POLL_SECONDS:-10}"

WORKDIR=""
# An EXIT trap's own last status would otherwise become the script's status and
# turn every VOID (2) into 1 (measured on the phase-29-research branch, bash
# 5.2): capture the incoming status first and re-assert it last.
cleanup() {
  local rc=$?
  if [ -n "${WORKDIR:-}" ] && [ -d "${WORKDIR:-}" ]; then
    rm -rf "$WORKDIR"
  fi
  exit "$rc"
}
trap cleanup EXIT

# identity_value FILE KEY — prints the value of KEY when the file holds exactly
# one non-empty KEY: line (quotes stripped, trailing comment dropped); returns 1
# otherwise. The same reading rule staging_kube_guard applies to the FQDN.
identity_value() {
  local f="$1" key="$2" lines n v
  lines=$(command grep -E "^[[:space:]]*${key}[[:space:]]*:" "$f" 2>/dev/null || true)
  if [ -z "$lines" ]; then n=0; else n=$(command grep -c '' <<<"$lines"); fi
  [ "$n" -eq 1 ] || return 1
  v="${lines#*:}"
  v="${v#"${v%%[![:space:]]*}"}"
  case "$v" in
    \"*) v="${v#\"}"; v="${v%%\"*}" ;;
    \'*) v="${v#\'}"; v="${v%%\'*}" ;;
    *)   v="${v%%[[:space:]]#*}"; v="${v%"${v##*[![:space:]]}"}" ;;
  esac
  [ -n "$v" ] || return 1
  printf '%s' "$v"
}

# ===========================================================================
# --selftest: the named refusal arms, run against stubs. Defined before the
# flag parse so `--selftest` needs no other flag.
# ===========================================================================
_ST_RESULTS=()
_st_result() { _ST_RESULTS+=("$1"); }
_st_render() {
  local i=0 r verdict rest
  if [ "${STAGING_BOOTSTRAP_SELFTEST_FORMAT:-plain}" = tap ]; then
    echo "TAP version 13"
    echo "1..${#_ST_RESULTS[@]}"
    for r in "${_ST_RESULTS[@]}"; do
      i=$((i + 1)); verdict="${r%% *}"; rest="${r#* }"
      if [ "$verdict" = PASS ]; then
        echo "ok $i - ${rest%% *}"
      else
        echo "not ok $i - ${rest%% *}"
        echo "  ---"
        echo "  message: '$(printf '%s' "${rest#* }" | tr "'" '"')'"
        echo "  ..."
      fi
    done
  else
    for r in "${_ST_RESULTS[@]}"; do printf '%s\n' "$r"; done
  fi
}

selftest() {
  local real_kubectl real_bash tmp t p fails=0
  real_kubectl=$(type -P kubectl) || { echo "VOID: selftest needs a real kubectl for the local 'config view' read the guard makes" >&2; return 2; }
  real_bash=$(type -P bash) || { echo "VOID: bash not found" >&2; return 2; }
  tmp=$(mktemp -d) || { echo "VOID: mktemp -d failed" >&2; return 2; }
  WORKDIR="$tmp"
  mkdir -p "$tmp/stub" "$tmp/stub-nokubelogin" "$tmp/tools" "$tmp/logs" "$tmp/home"

  # A tools directory holding ONLY what the script needs, so no real curl,
  # kubelogin or kubectl on the system PATH can stand in for a stub.
  for t in bash env sed awk grep cat mktemp rm mkdir date sha256sum tr wc head cut sort dirname basename sleep cp; do
    p=$(type -P "$t") || { echo "VOID: selftest tool not found: $t" >&2; return 2; }
    ln -s "$p" "$tmp/tools/$t"
  done

  # Stubs log their argv, one line per call. The kubectl stub hands ONLY the two
  # local, cluster-free subcommands to the real binary — the guard's `config view`
  # read (against the temp KUBECONFIG) and the ci-rbac stage's `kustomize` render
  # — and answers everything else with success and no output, so a call that
  # should not have happened is visible in the log rather than in a cluster.
  printf '#!%s\nprintf "%%s\\n" "$*" >> "$STUB_LOG_DIR/kubectl.log"\ncase "${1:-}" in config|kustomize) exec "$REAL_KUBECTL" "$@" ;; esac\nexit 0\n' "$real_bash" > "$tmp/stub/kubectl"
  printf '#!%s\nprintf "%%s\\n" "$*" >> "$STUB_LOG_DIR/kubelogin.log"\nexit 0\n' "$real_bash" > "$tmp/stub/kubelogin"
  printf '#!%s\nprintf "%%s\\n" "$*" >> "$STUB_LOG_DIR/curl.log"\nout=""\nwhile [ "$#" -gt 0 ]; do case "$1" in -o) out="$2"; shift 2 ;; *) shift ;; esac; done\n[ -n "$out" ] && printf "tampered-by-selftest\\n" > "$out"\nexit 0\n' "$real_bash" > "$tmp/stub/curl"
  chmod +x "$tmp/stub/kubectl" "$tmp/stub/kubelogin" "$tmp/stub/curl"
  cp "$tmp/stub/kubectl" "$tmp/stub/curl" "$tmp/stub-nokubelogin/"

  local fqdn="jtoye-stg-aks-selftest.hcp.uksouth.azmk8s.io"
  cat > "$tmp/kubeconfig" <<KCFG
apiVersion: v1
kind: Config
clusters:
- name: good
  cluster:
    server: https://${fqdn}:443
- name: stale
  cluster:
    server: https://jtoye-staging-deleted-august.hcp.uksouth.azmk8s.io:443
- name: noport
  cluster:
    server: https://${fqdn}
contexts:
- name: jtoye-stg-aks
  context: {cluster: good, user: none}
- name: jtoye-staging
  context: {cluster: stale, user: none}
- name: noport
  context: {cluster: noport, user: none}
- name: sipbihs2aks
  context: {cluster: good, user: none}
users:
- name: none
  user: {}
KCFG
  cat > "$tmp/identity.yaml" <<IDF
apiVersion: v1
kind: ConfigMap
metadata:
  name: azure-identity
  annotations:
    config.kubernetes.io/local-config: "true"
data:
  aks_api_server_fqdn: "${fqdn}"
  ingress_ip_address: "203.0.113.10"
  ci_principal_object_id: "00000000-0000-4000-8000-000000000000"
IDF
  command sed 's/^  aks_api_server_fqdn:.*$/  aks_api_server_fqdn: ""/' "$tmp/identity.yaml" > "$tmp/identity-empty.yaml"
  { cat "$tmp/identity.yaml"; echo "  aks_api_server_fqdn: \"other.hcp.uksouth.azmk8s.io\""; } > "$tmp/identity-two.yaml"

  # _st_run STUBDIR ARGS... — run this script as a child with a scrubbed
  # environment; sets ST_RC, ST_OUT, ST_ERR and leaves the stub logs in place.
  _st_run() {
    local stubdir="$1"; shift
    : > "$tmp/logs/kubectl.log"; : > "$tmp/logs/curl.log"; : > "$tmp/logs/kubelogin.log"
    # selftest runs under set +e (see the --selftest flag handler)
    ST_OUT=$(command env -i PATH="$stubdir:$tmp/tools" HOME="$tmp/home" KUBECONFIG="$tmp/kubeconfig" \
      STUB_LOG_DIR="$tmp/logs" REAL_KUBECTL="$real_kubectl" STAGING_BOOTSTRAP_POLL_SECONDS=0 \
      "$real_bash" "$SCRIPT_PATH" "$@" 2>"$tmp/err"); ST_RC=$?
    ST_ERR=$(cat "$tmp/err")
  }
  _st_lines() { local f="$tmp/logs/$1.log"; if [ -s "$f" ]; then command grep -c '' "$f"; else echo 0; fi; }
  # Calls in the kubectl log that are NOT the guard's local `config view` read.
  _st_kubectl_other() { if [ -s "$tmp/logs/kubectl.log" ]; then command grep -vc '^config view ' "$tmp/logs/kubectl.log" || true; else echo 0; fi; }
  # Calls that are neither the config read nor the local kustomize render: what a
  # dry run must never make.
  _st_kubectl_cluster() { if [ -s "$tmp/logs/kubectl.log" ]; then command grep -vcE "^(config view |kustomize )" "$tmp/logs/kubectl.log" || true; else echo 0; fi; }

  # _st_check ARM WANT_RC WANT_STDERR_TEXT WANT_CURL_CALLS — checks the last run:
  # its rc, its stderr tag, no kubectl call beyond the config read, and the curl count.
  _st_check() {
    local arm="$1" want_rc="$2" want_text="$3" want_curl="$4" other curl
    other=$(_st_kubectl_other); curl=$(_st_lines curl)
    if [ "$ST_RC" -ne "$want_rc" ]; then echo "rc=$ST_RC (want $want_rc): $(head -c 160 <<<"$ST_ERR" | tr "\n" " ")"; return 1; fi
    if ! command grep -qF -- "$want_text" <<<"$ST_ERR"; then echo "stderr lacks '$want_text': $(head -c 160 <<<"$ST_ERR" | tr "\n" " ")"; return 1; fi
    if [ "$other" -ne 0 ]; then echo "kubectl calls beyond config view: $other ($(command grep -v '^config view ' "$tmp/logs/kubectl.log" | head -1))"; return 1; fi
    if [ "$curl" -ne "$want_curl" ]; then echo "curl calls=$curl (want $want_curl)"; return 1; fi
    return 0
  }

  local why
  # --- no-context: before ANY kubectl call ---------------------------------
  _st_run "$tmp/stub" --identity-file "$tmp/identity.yaml"
  if why=$(_st_check no-context 2 "USAGE ERROR" 0) && [ "$(_st_lines kubectl)" -eq 0 ]; then
    _st_result "PASS no-context rc=$ST_RC kubectl_calls=0 curl_calls=0"
  else _st_result "FAIL no-context ${why:-a kubectl call was made before the usage check}"; fails=$((fails + 1)); fi

  # --- employer-context: refused by name, before the config read ------------
  _st_run "$tmp/stub" --context sipbihs2aks --identity-file "$tmp/identity.yaml" --stage all
  if why=$(_st_check employer-context 1 "REFUSED [employer-context]" 0) && [ "$(_st_lines kubectl)" -eq 0 ]; then
    _st_result "PASS employer-context rc=$ST_RC kubectl_calls=0 curl_calls=0"
  else _st_result "FAIL employer-context ${why:-a kubectl call preceded the refusal}"; fails=$((fails + 1)); fi

  # --- identity-file: absent, empty value, two values -----------------------
  why=""
  local f sub
  for sub in absent empty two; do
    case "$sub" in absent) f="$tmp/identity-missing.yaml" ;; empty) f="$tmp/identity-empty.yaml" ;; two) f="$tmp/identity-two.yaml" ;; esac
    _st_run "$tmp/stub" --context jtoye-stg-aks --identity-file "$f" --stage all
    if ! why=$(_st_check identity-file 1 "REFUSED [identity-file]" 0); then why="($sub) $why"; break; fi
    if [ "$(_st_lines kubectl)" -ne 0 ]; then why="($sub) a kubectl call preceded the refusal"; break; fi
    why=""
  done
  if [ -z "$why" ]; then _st_result "PASS identity-file absent+empty+two rc=1 kubectl_calls=0 curl_calls=0"
  else _st_result "FAIL identity-file $why"; fails=$((fails + 1)); fi

  # --- server-url: the stale context, a missing context, a port-less URL ----
  why=""
  local c
  for c in jtoye-staging no-such-context noport; do
    _st_run "$tmp/stub" --context "$c" --identity-file "$tmp/identity.yaml" --stage all
    if ! why=$(_st_check server-url 1 "REFUSED [server-url]" 0); then why="($c) $why"; break; fi
    why=""
  done
  if [ -z "$why" ]; then _st_result "PASS server-url stale+missing+noport rc=1 kubectl_calls_beyond_config_view=0 curl_calls=0"
  else _st_result "FAIL server-url $why"; fails=$((fails + 1)); fi

  # --- digest: a tampered download refuses before ANY stage (namespace too) --
  _st_run "$tmp/stub" --context jtoye-stg-aks --identity-file "$tmp/identity.yaml" --stage all
  if why=$(_st_check digest 1 "REFUSED [digest]" 1); then
    _st_result "PASS digest rc=$ST_RC curl_calls=1 kubectl_calls_beyond_config_view=0 (nothing applied)"
  else _st_result "FAIL digest $why"; fails=$((fails + 1)); fi

  # --- dry-run: ordered plan + would-be commands, nothing mutated -----------
  _st_run "$tmp/stub" --context jtoye-stg-aks --identity-file "$tmp/identity.yaml" --stage all --dry-run
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC: $(head -c 160 <<<"$ST_ERR" | tr "\n" " ")"
  elif ! command grep -qF "stage plan: $ALL_STAGES" <<<"$ST_OUT"; then why="the ordered stage plan line is missing"
  elif [ "$(command grep -c '^WOULD RUN: kubectl --context jtoye-stg-aks .*apply' <<<"$ST_OUT" || true)" -lt 6 ]; then why="fewer than 6 would-be apply commands printed"
  elif [ "$(_st_kubectl_cluster)" -ne 0 ]; then why="kubectl calls that reach a cluster: $(_st_kubectl_cluster) ($(command grep -vE "^(config view |kustomize )" "$tmp/logs/kubectl.log" | head -1))"
  elif [ "$(_st_lines curl)" -ne 0 ]; then why="curl was called in a dry run"
  fi
  if [ -z "$why" ]; then
    _st_result "PASS dry-run rc=0 would_apply=$(command grep -c '^WOULD RUN: kubectl --context jtoye-stg-aks .*apply' <<<"$ST_OUT") cluster_calls=0 curl_calls=0"
  else _st_result "FAIL dry-run $why"; fails=$((fails + 1)); fi

  # --- kubelogin: an Entra-only cluster without kubelogin is VOID -----------
  _st_run "$tmp/stub-nokubelogin" --context jtoye-stg-aks --identity-file "$tmp/identity.yaml" --stage all --dry-run
  if why=$(_st_check kubelogin 2 "VOID: kubelogin not on PATH" 0); then
    _st_result "PASS kubelogin rc=$ST_RC curl_calls=0 kubectl_calls_beyond_config_view=0"
  else _st_result "FAIL kubelogin $why"; fails=$((fails + 1)); fi

  _st_render
  [ "$fails" -eq 0 ]
}

# ===========================================================================
# Flags. Parsed FIRST, so a typo cannot fall through into an apply.
# ===========================================================================
KUBE_CONTEXT=""
STAGE="all"
DRY_RUN=0
IDENTITY_FILE="$REPO_ROOT/k8s/staging/azure-identity.yaml"

usage() { command sed -n '/^# USAGE/,/^# EXIT CODES/p' "$SCRIPT_PATH" | command sed 's/^# \{0,1\}//'; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --context)       shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --context needs a name" >&2; exit 2; }; KUBE_CONTEXT="$1" ;;
    --stage)         shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --stage needs a value" >&2; exit 2; }; STAGE="$1" ;;
    --identity-file) shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --identity-file needs a path" >&2; exit 2; }; IDENTITY_FILE="$1" ;;
    --dry-run)       DRY_RUN=1 ;;
    --selftest)      set +e; selftest; rc=$?; exit "$rc" ;;
    -h|--help)       usage; exit 0 ;;
    *)               echo "USAGE ERROR: unknown flag '$1'" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

case " $KNOWN_STAGES " in
  *" $STAGE "*) ;;
  *) echo "USAGE ERROR: unknown --stage '$STAGE' (one of: $KNOWN_STAGES)" >&2; exit 2 ;;
esac

# ===========================================================================
# THE GUARD. Nothing above touched a cluster; nothing below runs unless it
# returns 0. 2 = usage/VOID, 1 = refused.
# ===========================================================================
staging_kube_guard "$KUBE_CONTEXT" "$IDENTITY_FILE" || exit $?
ok "context '${KUBE_CONTEXT}' passed staging_kube_guard"

if [ "$STAGE" = all ]; then STAGES="$ALL_STAGES"; else STAGES="$STAGE"; fi
has_stage() { case " $STAGES " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

# Identity values the selected stages need, read and shape-checked up front.
INGRESS_IP=""
CI_OID=""
if has_stage traefik || has_stage acme-probe; then
  INGRESS_IP=$(identity_value "$IDENTITY_FILE" ingress_ip_address) \
    || refuse identity-file "$IDENTITY_FILE holds no single non-empty ingress_ip_address (needed by: traefik, acme-probe)"
  [[ "$INGRESS_IP" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] \
    || refuse identity-file "ingress_ip_address '$INGRESS_IP' is not an IPv4 address"
fi
if has_stage ci-rbac; then
  CI_OID=$(identity_value "$IDENTITY_FILE" ci_principal_object_id) \
    || refuse identity-file "$IDENTITY_FILE holds no single non-empty ci_principal_object_id (needed by: ci-rbac)"
  [[ "$CI_OID" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
    || refuse identity-file "ci_principal_object_id '$CI_OID' is not an Entra object id (UUID)"
fi

# k ARGS... — the ONLY way this script reaches a cluster. In --dry-run it prints
# the command instead of running it.
k() {
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "WOULD RUN: kubectl --context $KUBE_CONTEXT $*"
    return 0
  fi
  kubectl --context "$KUBE_CONTEXT" "$@"
}

# ===========================================================================
# Artefacts: fetch and verify EVERY one the selected stages need before ANY
# stage runs.
# ===========================================================================
CERT_MANAGER_FILE=""
RABBITMQ_OPERATOR_FILE=""

fetch_and_verify() {
  # fetch_and_verify LABEL URL SHA256 BASENAME — prints the verified path.
  local label="$1" url="$2" want="$3" base="$4" dest
  dest="$WORKDIR/$base"
  echo "${label}: fetching ${url}" >&2
  curl -fsSL --proto '=https' --max-time 120 -o "$dest" "$url" \
    || void "could not download ${label} from ${url}; a failed download is VOID, and no other artefact is substituted"
  [ -s "$dest" ] || void "${label}: the download from ${url} is empty"
  local got bytes
  got=$(sha256sum "$dest" | awk '{print $1}')
  bytes=$(wc -c < "$dest" | tr -d '[:space:]')
  if [ "$got" != "$want" ]; then
    echo "${label}: expected sha256 ${want}" >&2
    echo "${label}: actual   sha256 ${got} (${bytes} bytes)" >&2
    refuse digest "${label} from ${url} does not match its pinned sha256. Either the upstream artefact changed under a pinned tag or what arrived is not what was pinned. NOTHING has been applied: every artefact is verified before any stage runs."
  fi
  echo "OK: ${label} verified: ${bytes} bytes, sha256 ${got}" >&2
  printf '%s' "$dest"
}

if has_stage cert-manager || has_stage rabbitmq-operator; then
  step "artefacts (verified before any stage runs)"
  if [ "$DRY_RUN" -eq 1 ]; then
    if has_stage cert-manager; then echo "WOULD DOWNLOAD: ${CERT_MANAGER_URL} and require sha256 ${CERT_MANAGER_SHA256}"; fi
    if has_stage rabbitmq-operator; then echo "WOULD DOWNLOAD: ${RABBITMQ_OPERATOR_URL} and require sha256 ${RABBITMQ_OPERATOR_SHA256}"; fi
  else
    for t in curl sha256sum; do
      command -v "$t" >/dev/null 2>&1 || void "$t not on PATH; the digest check cannot run, and an unverified upstream manifest is VOID, not clean"
    done
    WORKDIR=$(mktemp -d) || void "mktemp -d failed"
    if has_stage cert-manager; then
      CERT_MANAGER_FILE=$(fetch_and_verify "cert-manager ${CERT_MANAGER_VERSION}" "$CERT_MANAGER_URL" "$CERT_MANAGER_SHA256" cert-manager.yaml)
    fi
    if has_stage rabbitmq-operator; then
      RABBITMQ_OPERATOR_FILE=$(fetch_and_verify "rabbitmq cluster-operator ${RABBITMQ_OPERATOR_VERSION}" "$RABBITMQ_OPERATOR_URL" "$RABBITMQ_OPERATOR_SHA256" cluster-operator.yml)
    fi
    ok "every artefact the selected stages need is verified; nothing has been applied yet"
  fi
fi

# wait_until DEADLINE_SECONDS DESCRIPTION COMMAND... — re-runs COMMAND until it
# succeeds; VOID at the deadline. Never reached in --dry-run.
wait_until() {
  local secs="$1" what="$2"; shift 2
  local deadline=$(( $(date +%s) + secs ))
  until "$@"; do
    [ "$(date +%s)" -lt "$deadline" ] || void "timed out after ${secs}s waiting for ${what}"
    sleep "$POLL_SECONDS"
  done
}

# ===========================================================================
# Stages
# ===========================================================================
stage_namespace() {
  step "stage namespace: ${NAMESPACE}"
  if [ "$DRY_RUN" -eq 1 ]; then
    k get namespace "$NAMESPACE" -o name
    k create namespace "$NAMESPACE"
    echo "  (create only if the get above answers NotFound; then read back label kubernetes.io/metadata.name)"
    return 0
  fi
  local err out
  err=$(mktemp)
  if out=$(k get namespace "$NAMESPACE" -o name 2>"$err"); then
    ok "namespace ${NAMESPACE} exists (${out})"
  elif command grep -q 'NotFound' "$err"; then
    k create namespace "$NAMESPACE"
  else
    cat "$err" >&2; rm -f "$err"
    void "could not read namespace ${NAMESPACE} (not a NotFound, so not creating it)"
  fi
  rm -f "$err"
  local label
  label=$(k get namespace "$NAMESPACE" -o 'jsonpath={.metadata.labels.kubernetes\.io/metadata\.name}')
  [ "$label" = "$NAMESPACE" ] || fail "namespace label kubernetes.io/metadata.name is '${label}', expected '${NAMESPACE}'; every NetworkPolicy namespaceSelector depends on it"
  ok "kubernetes.io/metadata.name=${label}"
}

webhook_answers() {
  local out
  out=$(k apply -n default --dry-run=server -f - 2>&1 <<'PROBE'
apiVersion: cert-manager.io/v1
kind: Issuer
metadata:
  name: staging-bootstrap-webhook-readiness-probe
spec:
  selfSigned: {}
PROBE
  ) || true
  ! command grep -Eq 'failed calling webhook|no route to host|connection refused|context deadline exceeded|EOF|no matches for kind' <<<"$out"
}

stage_cert_manager() {
  step "stage cert-manager ${CERT_MANAGER_VERSION}"
  # Server-side apply: client-side apply stores the object in a
  # last-applied-configuration annotation capped at 262144 bytes, and
  # cert-manager's CRDs exceed it.
  k apply --server-side --force-conflicts -f "${CERT_MANAGER_FILE:-<verified cert-manager.yaml>}"
  k -n "$CERT_MANAGER_NS" wait --for=condition=Available deployment --all --timeout="${WAIT_SECONDS_DEPLOY}s"
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "  then: server-side dry-run of a throwaway Issuer until the admission webhook ANSWERS (accept or deny), ${WAIT_SECONDS_DEPLOY}s deadline"
    return 0
  fi
  # A rollout can report Available while the webhook Service still resolves to
  # the previous pod (scripts/k8s-local-up.sh STEP 4b); any ANSWER proves it.
  wait_until "$WAIT_SECONDS_DEPLOY" "the cert-manager admission webhook to answer" webhook_answers
  ok "cert-manager webhook answers"
}

stage_rabbitmq_operator() {
  step "stage rabbitmq-operator ${RABBITMQ_OPERATOR_VERSION}"
  k apply --server-side --force-conflicts -f "${RABBITMQ_OPERATOR_FILE:-<verified cluster-operator.yml>}"
  k -n "$RABBITMQ_OPERATOR_NS" wait --for=condition=Available deployment/rabbitmq-cluster-operator --timeout="${WAIT_SECONDS_DEPLOY}s"
  echo "NOTE: upgrading this operator rolls EVERY RabbitmqCluster it manages. Before a bump, pause"
  echo "      reconciliation on each cluster (label rabbitmq.com/pauseReconciliation=true), upgrade,"
  echo "      then unpause one cluster at a time (docs/runbooks/rabbitmq-broker-upgrade.md)."
}

svc_ip_is_expected() {
  local ip
  ip=$(k -n "$TRAEFIK_NS" get svc traefik -o 'jsonpath={.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
  if [ -n "$ip" ] && [ "$ip" != "$INGRESS_IP" ]; then
    fail "the traefik Service holds ${ip}, not the static IP ${INGRESS_IP} the identity file names; DNS would point at an address nothing serves"
  fi
  [ "$ip" = "$INGRESS_IP" ]
}

stage_traefik() {
  step "stage traefik (k8s/platform/traefik)"
  k apply -k "$REPO_ROOT/k8s/platform/traefik"
  k -n "$TRAEFIK_NS" wait --for=condition=Available deployment/traefik --timeout="${WAIT_SECONDS_DEPLOY}s"
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "  then: poll service traefik .status.loadBalancer.ingress[0].ip until it equals ${INGRESS_IP}, ${WAIT_SECONDS_IP}s deadline (VOID on timeout)"
    return 0
  fi
  wait_until "$WAIT_SECONDS_IP" "service traefik to hold ${INGRESS_IP}" svc_ip_is_expected
  ok "service traefik holds ${INGRESS_IP}"
}

stage_issuers() {
  step "stage issuers (k8s/platform/cert-manager)"
  k apply -k "$REPO_ROOT/k8s/platform/cert-manager"
  k wait --for=condition=Ready clusterissuer/letsencrypt-staging clusterissuer/letsencrypt-prod --timeout=300s
}

stage_ci_rbac() {
  step "stage ci-rbac (k8s/platform/rbac)"
  local r n
  r=$(kubectl kustomize "$REPO_ROOT/k8s/platform/rbac") || fail "k8s/platform/rbac does not render"
  r="${r//${CI_TOKEN}/${CI_OID}}"
  n=$(command grep -c -- "$CI_TOKEN" <<<"$r" || true)
  [ "$n" -eq 0 ] || refuse ci-rbac-token "${n} occurrence(s) of ${CI_TOKEN} survived the substitution; a binding to that literal name would grant nothing to CI and look applied"
  n=$(command grep -c -- "name: ${CI_OID}\$" <<<"$r" || true)
  [ "$n" -eq 2 ] || refuse ci-rbac-token "expected the object id in exactly 2 binding subjects, found ${n}"
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "WOULD RUN: kubectl --context $KUBE_CONTEXT apply -f - (k8s/platform/rbac rendered, ${CI_TOKEN} -> ${CI_OID})"
    return 0
  fi
  kubectl --context "$KUBE_CONTEXT" apply -f - <<<"$r"
}

cert_is_ready() {
  local s
  s=$(k -n "$NAMESPACE" get certificate acme-probe-tls -o 'jsonpath={.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)
  [ "$s" = "True" ]
}

stage_acme_probe() {
  step "stage acme-probe (k8s/platform/acme-probe)"
  k apply -k "$REPO_ROOT/k8s/platform/acme-probe"
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "  then: wait for certificate acme-probe-tls Ready (${WAIT_SECONDS_CERT}s deadline), then"
    echo "  curl -s --resolve ${PROBE_HOST}:443:${INGRESS_IP} -k https://${PROBE_HOST}/acme-probe and require netexec's 'NOW: ' answer"
    return 0
  fi
  wait_until "$WAIT_SECONDS_CERT" "certificate acme-probe-tls to be Ready (letsencrypt-staging, HTTP-01)" cert_is_ready
  ok "certificate acme-probe-tls is Ready"
  local body
  body=$(curl -s --max-time 20 --resolve "${PROBE_HOST}:443:${INGRESS_IP}" -k "https://${PROBE_HOST}/acme-probe" || true)
  case "$body" in
    "NOW: "*) ok "edge answered through Traefik: ${body}" ;;
    *) fail "https://${PROBE_HOST}/acme-probe via ${INGRESS_IP} did not return netexec's 'NOW: ' answer (got: $(head -c 120 <<<"$body"))" ;;
  esac
  echo "NEXT: record the evidence, then run --stage acme-probe-delete"
}

stage_acme_probe_delete() {
  step "stage acme-probe-delete"
  k delete -k "$REPO_ROOT/k8s/platform/acme-probe" --ignore-not-found
  k -n "$NAMESPACE" delete certificate acme-probe-tls --ignore-not-found
  k -n "$NAMESPACE" delete secret acme-probe-tls --ignore-not-found
}

if [ "$DRY_RUN" -eq 1 ]; then echo "DRY-RUN: stage plan: $STAGES"; fi
for s in $STAGES; do
  case "$s" in
    namespace)          stage_namespace ;;
    cert-manager)       stage_cert_manager ;;
    rabbitmq-operator)  stage_rabbitmq_operator ;;
    traefik)            stage_traefik ;;
    issuers)            stage_issuers ;;
    ci-rbac)            stage_ci_rbac ;;
    acme-probe)         stage_acme_probe ;;
    acme-probe-delete)  stage_acme_probe_delete ;;
  esac
done

if [ "$DRY_RUN" -eq 1 ]; then
  echo "DRY-RUN complete: nothing was downloaded or applied."
else
  ok "stages complete: $STAGES"
fi
