#!/usr/bin/env bash
# staging-seed.sh — the staging seed, through the product's own APIs (Phase 29,
# plan 29-06; DPLY-01; decisions D-26, D-27; threats T-29-17..T-29-20).
#
# WHAT IT IS FOR
#   DPLY-01 needs real seeded rows, and D-26 needs every one of them made the way a
#   first tenant's would be: the seed doubles as a smoke test of the onboarding
#   paths Phase 32 needs. D-27: the DPLY-01 login is a staging-only test vendor
#   whose password lives only in Key Vault. This script contains no SQL and no
#   database client; every row is written by an HTTP call a vendor or an admin
#   could make.
#
# USAGE
#   scripts/staging-seed.sh --context NAME --stage vendor [--identity-file PATH]
#                           [--api URL] [--dry-run]
#   scripts/staging-seed.sh --list-kv-objects
#   scripts/staging-seed.sh --selftest
#
#     --context NAME     the kube context (required for every stage; no default).
#     --identity-file P  default k8s/staging/azure-identity.yaml (plan 29-09).
#     --api URL          default https://api-staging.olajay.co.uk, and it must be
#                        exactly that: any other value is REFUSED [api-base].
#     --stage vendor     this plan. order and all are added by plan 29-18.
#     --dry-run          run every guard (they only read), then print the ordered
#                        request plan; no Azure, Keycloak or API call is made.
#     --list-kv-objects  the Key Vault object names the vendor stage reads, one
#                        per line, C-locale sorted (offline; plan 29-13 writes them).
#     --selftest         one line per arm, PASS <arm> or FAIL <arm> <reason>;
#                        STAGING_SEED_SELFTEST_FORMAT=tap renders TAP 13.
#
# EXIT CODES
#   0 done, 1 refused or failed, 2 usage error or VOID (could not evaluate).

set -euo pipefail

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(dirname "$SCRIPT_PATH")"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

STAGING_API="https://api-staging.olajay.co.uk"
NAMESPACE="jtoye-staging"
REALM="jtoye-staging"
KEY_VAULT_NAME="jtoye-stg-kv"
KV_MAP=(
  "VENDOR_PASSWORD=staging-seed--test-vendor-password"
  "KC_ADMIN_USERNAME=keycloak-credentials--admin-username"
  "KC_ADMIN_PASSWORD=keycloak-credentials--admin-password"
  "CORE_API_CLIENT_SECRET=keycloak-credentials--frontend-client-secret"
)

TENANT_NAME="J'Toye Staging Test Kitchen"
VENDOR_USERNAME="vendor.staging@olajay.co.uk"
SHOP_SLUG="staging-test-kitchen"

WORKDIR=""
cleanup() {
  local rc=$?
  if [ -n "${WORKDIR:-}" ] && [ -d "${WORKDIR:-}" ]; then rm -rf "$WORKDIR"; fi
  exit "$rc"
}
trap cleanup EXIT

void()        { echo "VOID: $*" >&2; exit 2; }
usage_error() { echo "USAGE ERROR: $*" >&2; exit 2; }
refuse()      { local tag="$1"; shift; echo "REFUSED [$tag]: $*" >&2; exit 1; }
need_tool()   { command -v "$1" >/dev/null 2>&1 || void "$1 not on PATH"; }

ensure_workdir() {
  if [ -z "$WORKDIR" ]; then
    WORKDIR=$(umask 077 && mktemp -d) || void "mktemp -d failed"
    chmod 700 "$WORKDIR"
  fi
}

list_kv_objects() {
  local row
  for row in "${KV_MAP[@]}"; do printf '%s\n' "${row#*=}"; done | LC_ALL=C sort
}

# ===========================================================================
# Guards. Every one runs before any network call.
# ===========================================================================
run_guards() {
  # RED skeleton: no guards yet.
  [ -n "$KUBE_CONTEXT" ] || usage_error "--context is required"
  return 0
}

# ===========================================================================
# --dry-run: the ordered request plan. Names only, never a value.
# ===========================================================================
print_plan() {
  cat <<PLAN
PLAN --stage vendor (dry-run: nothing below is called)
  target        api ${API_BASE}; context ${KUBE_CONTEXT}; namespace ${NAMESPACE}; realm ${REALM}
  01 az_read    keyvault secret show --vault-name ${KEY_VAULT_NAME}: $(list_kv_objects | tr '\n' ' ')(values never printed)
  02 kubectl    port-forward svc/keycloak 18080:8080 19000:9000 (PID captured; killed by PID on exit)
  03 POST       /realms/master/protocol/openid-connect/token (master admin, through the port-forward)
  04 POST       /admin/realms/${REALM}/users seed-operator-<UTC> + realm role admin (deleted on EVERY exit path)
  05 POST       ${API_BASE#https://} token: password grant on core-api at the public issuer, operator
  06 GET/POST   /api/v1/admin/tenants                       check: reuse by name "${TENANT_NAME}"
  07 PUT        /admin/realms/${REALM}/users/<operator>       tenant_id attribute; operator token re-issued
  08 GET/POST   /admin/realms/${REALM}/users                  check: reuse ${VENDOR_USERNAME}; tenant_id read back by content
  09 GET/POST   /api/v1/staff/me, /api/v1/staff/grant         check: vendor groupAdmin before granting
  10 GET/POST   /api/v1/shops                                 check: reuse slug ${SHOP_SLUG}
  11 GET/POST   /api/v1/products/culinary-packs, /api/v1/products/culinary-packs/seed   check: shop has zero products
  12 GET/PUT    /api/v1/trader-identity                       check: identical identity already on file
  13 GET/POST   /api/v1/onboarding/me, /api/v1/onboarding, /api/v1/onboarding/submit   check: status
  14 POST       /api/v1/onboarding/admin/{id}/gates/{gate}/resolve   check: only open gates; reason "staging seed, scripted — D-26"
  15 POST       /api/v1/onboarding/admin/{id}/approve          check: status PENDING_APPROVAL
  16 POST       /api/v1/onboarding/go-live                    check: status APPROVED (LIVE is skipped)
  17 GET        read-back: tenant, shop + published, product count, onboarding status, vendor tenant_id
PLAN
}

# ===========================================================================
# --stage vendor (live)
# ===========================================================================
vendor_stage() {
  void "the vendor stage is not implemented yet"
}

# ===========================================================================
# --selftest
# ===========================================================================
_ST_RESULTS=()
_st_result() { _ST_RESULTS+=("$1"); }
_st_render() {
  local i=0 r verdict rest
  if [ "${STAGING_SEED_SELFTEST_FORMAT:-plain}" = tap ]; then
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

# The stub core-java pod list, in the shape `kubectl get pods -o json` returns.
#   _st_pod NAME READY(True|False) PROFILE
_st_pod() {
  jq -n --arg n "$1" --arg r "$2" --arg p "$3" '{
    metadata: {name: $n, labels: {app: "core-java"}},
    spec: {containers: [{name: "core-java", env: [{name: "SPRING_PROFILES_ACTIVE", value: $p}, {name: "DB_HOST", value: "x"}]}]},
    status: {phase: "Running", conditions: [{type: "Ready", status: $r}]}
  }'
}
_st_pods() { jq -s '{apiVersion: "v1", kind: "List", items: .}'; }

selftest() {
  local tmp real_bash real_kubectl fails=0 why t p ast
  real_bash=$(type -P bash) || { echo "VOID: bash not found" >&2; return 2; }
  command -v jq >/dev/null 2>&1 || { echo "VOID: jq not on PATH" >&2; return 2; }
  if ! real_kubectl=$(type -P kubectl); then
    echo "VOID: the selftest needs a real kubectl for the guard's local config view read" >&2; return 2
  fi
  ensure_workdir
  tmp="$WORKDIR/selftest"
  ast="$tmp"
  mkdir -p "$ast/stub" "$ast/tools" "$ast/logs" "$ast/home" "$ast/resp"

  # A tools directory holds only what the script needs, so no real curl, az,
  # kubelogin or kubectl can stand in for a stub.
  for t in bash env sed awk grep cat mktemp rm mkdir date tr wc head tail cut sort comm dirname basename sleep cp chmod jq openssl base64; do
    p=$(type -P "$t") || { echo "VOID: selftest tool not found: $t" >&2; return 2; }
    ln -s "$p" "$ast/tools/$t"
  done
  # kubectl: argv logged; `config` (the guard's local kubeconfig read) goes to the
  # real binary; `get pods` answers from STUB_PODS_FILE; a port-forward stays up
  # until it is killed (by PID, as the script must); `get deployment` answers a
  # prod template, which a correct profile guard never asks for.
  cat > "$ast/stub/kubectl" <<STUB
#!$real_bash
printf '%s\n' "\$*" >> "\$STUB_LOG_DIR/kubectl.log"
case "\${1:-}" in config) exec "\$REAL_KUBECTL" "\$@" ;; esac
case " \$* " in
  *" get pods "*) cat "\${STUB_PODS_FILE:-/dev/null}"; exit 0 ;;
  *" get deployment"*|*" get deploy "*) printf '%s\n' '{"spec":{"template":{"spec":{"containers":[{"name":"core-java","env":[{"name":"SPRING_PROFILES_ACTIVE","value":"prod"}]}]}}}}'; exit 0 ;;
  *" port-forward "*) exec sleep 600 ;;
esac
exit 0
STUB
  printf '#!%s\nprintf "%%s\\n" "$*" >> "$STUB_LOG_DIR/kubelogin.log"\nexit 0\n' "$real_bash" > "$ast/stub/kubelogin"
  # curl: argv logged in full (so a secret in argv is detectable), one
  # "METHOD URL -> CODE" flow line per call, answered from STUB_RESP_DIR.
  cat > "$ast/stub/curl" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$STUB_LOG_DIR/curl.log"
out=""; wfmt=""; hdr=""; method=""; url=""; data=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    -w) wfmt="$2"; shift 2 ;;
    -D) hdr="$2"; shift 2 ;;
    -X) method="$2"; shift 2 ;;
    --data-urlencode|--data-binary|-d) data=1; shift 2 ;;
    -K|-H) shift 2 ;;
    http://*|https://*) url="$1"; shift ;;
    *) shift ;;
  esac
done
[ -n "$method" ] || { if [ "$data" -eq 1 ]; then method=POST; else method=GET; fi; }
rest="${url#*://}"; path="/${rest#*/}"; base="${path%%\?*}"; query=""
case "$path" in *\?*) query="${path#*\?}" ;; esac
key=""; code=404; location=""
case "$method $base" in
  "GET /health/ready") key=empty; code=200 ;;
  "POST /realms/master/protocol/openid-connect/token") key=admin-token; code=200 ;;
  "POST /realms/jtoye-staging/protocol/openid-connect/token") key=user-token; code=200 ;;
  "POST /admin/realms/jtoye-staging/users") key=empty; code=201; location="http://127.0.0.1:18080/admin/realms/jtoye-staging/users/op-0001" ;;
  "GET /admin/realms/jtoye-staging/users")
    case "$query" in *vendor.staging*) key=vendor-list ;; *) key=empty-list ;; esac; code=200 ;;
  "GET /admin/realms/jtoye-staging/roles/admin") key=role-admin; code=200 ;;
  "POST /admin/realms/jtoye-staging/users/"*"/role-mappings/realm") key=empty; code=204 ;;
  "GET /admin/realms/jtoye-staging/users/op-0001") key=operator; code=200 ;;
  "GET /admin/realms/jtoye-staging/users/vendor-0001") key=vendor; code=200 ;;
  "PUT /admin/realms/jtoye-staging/users/"*) key=empty; code=204 ;;
  "DELETE /admin/realms/jtoye-staging/users/"*) key=empty; code=204 ;;
  "GET /api/v1/admin/tenants") key=tenants; code=200 ;;
  "GET /api/v1/staff/me") key=staff-me; code=200 ;;
  "GET /api/v1/shops") key=shops; code=200 ;;
  "GET /api/v1/products") key=products; code=200 ;;
  "GET /api/v1/products/culinary-packs") key=packs; code=200 ;;
  "POST /api/v1/products/culinary-packs/seed") key=seed; code=200 ;;
esac
if [ -n "${STUB_FAIL_ON:-}" ] && [[ "$method $base" =~ $STUB_FAIL_ON ]]; then key=problem; code=500; fi
printf '%s %s -> %s\n' "$method" "$url" "$code" >> "$STUB_LOG_DIR/curl-flow.log"
if [ -n "$out" ]; then
  if [ -n "$key" ] && [ -f "$STUB_RESP_DIR/$key.json" ]; then cat "$STUB_RESP_DIR/$key.json" > "$out"; else printf '{}' > "$out"; fi
fi
if [ -n "$hdr" ]; then
  { printf 'HTTP/1.1 %s\r\n' "$code"; [ -z "$location" ] || printf 'Location: %s\r\n' "$location"; printf '\r\n'; } > "$hdr"
fi
[ -z "$wfmt" ] || printf '%s' "$code"
exit 0
STUB
  chmod +x "$ast/stub/kubectl" "$ast/stub/kubelogin" "$ast/stub/curl"
  ( source "$SCRIPT_DIR/lib/azure-guard.sh" && az_guard_stub "$ast/stub" ) \
    || { echo "VOID: az_guard_stub failed" >&2; return 2; }

  local fqdn="jtoye-stg-aks-selftest.hcp.uksouth.azmk8s.io"
  cat > "$ast/kubeconfig" <<KCFG
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
users:
- name: none
  user: {}
KCFG
  cat > "$ast/identity.yaml" <<IDF
apiVersion: v1
kind: ConfigMap
metadata:
  name: azure-identity
data:
  aks_api_server_fqdn: "${fqdn}"
IDF

  # Pod lists, one per profile arm.
  _st_pod core-java-a True prod | _st_pods > "$ast/pods-prod.json"
  _st_pod core-java-a True dev | _st_pods > "$ast/pods-dev.json"
  { _st_pod core-java-new True prod; _st_pod core-java-old True dev; } | _st_pods > "$ast/pods-mixed.json"
  { _st_pod core-java-a False prod; _st_pod core-java-b False prod; } | _st_pods > "$ast/pods-none-ready.json"

  # Every Key Vault read answers a value beginning STUB-SECRET-, so an echo of a
  # secret anywhere in this selftest's output or in any curl argv is detectable.
  local row obj
  : > "$ast/answers.tsv"
  for row in "${KV_MAP[@]}"; do
    obj="${row#*=}"
    printf '%s\t0\t%s\n' "-n ${obj} --query" "STUB-SECRET-${obj}-7f3a91" >> "$ast/answers.tsv"
  done

  # _st_run [VAR=value ...] -- ARGS... — the script as a scrubbed child.
  _st_run() {
    local extra=()
    while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do extra+=("$1"); shift; done
    shift
    : > "$ast/logs/kubectl.log"; : > "$ast/logs/kubelogin.log"; : > "$ast/logs/curl.log"
    : > "$ast/logs/curl-flow.log"; : > "$ast/logs/az.log"
    ST_OUT=$(command env -i PATH="$ast/stub:$ast/tools" HOME="$ast/home" KUBECONFIG="$ast/kubeconfig" \
      STUB_LOG_DIR="$ast/logs" STUB_RESP_DIR="$ast/resp" REAL_KUBECTL="$real_kubectl" AZ_STUB_LOG="$ast/logs/az.log" \
      JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94 AZ_STUB_ANSWERS="$ast/answers.tsv" \
      ${extra[@]+"${extra[@]}"} "$real_bash" "$SCRIPT_PATH" "$@" 2>"$ast/err"); ST_RC=$?
    ST_ERR=$(cat "$ast/err")
  }
  _st_lines() { local f="$ast/logs/$1.log"; if [ -s "$f" ]; then command grep -c '' "$f"; else echo 0; fi; }
  _st_kubectl_other() {
    # kubectl calls that are neither the guard's local `config view` nor the pod read
    if [ -s "$ast/logs/kubectl.log" ]; then
      command grep -vE '^config view |^--context [^ ]+ -n jtoye-staging get pods -l app=core-java -o json$' "$ast/logs/kubectl.log" | command grep -c '' || true
    else echo 0; fi
  }
  _st_pod_reads() { if [ -s "$ast/logs/kubectl.log" ]; then command grep -c ' get pods ' "$ast/logs/kubectl.log" || true; else echo 0; fi; }
  _st_config_reads() { if [ -s "$ast/logs/kubectl.log" ]; then command grep -c '^config view ' "$ast/logs/kubectl.log" || true; else echo 0; fi; }
  _st_leaks() { command grep -cF 'STUB-SECRET-' <<<"$ST_OUT$ST_ERR$(cat "$ast/logs/curl.log")" || true; }

  # _st_refusal WANT_RC WANT_TEXT MAX_CONFIG MAX_PODS — rc, the stderr tag, then
  # the logs: no az, no curl, no kubectl beyond the config read and the pod read.
  _st_refusal() {
    local want_rc="$1" want_text="$2" max_config="$3" max_pods="$4"
    if [ "$ST_RC" -ne "$want_rc" ]; then echo "rc=$ST_RC (want $want_rc): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"; return 1; fi
    if ! command grep -qF -- "$want_text" <<<"$ST_ERR"; then echo "stderr lacks '$want_text': $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"; return 1; fi
    if [ "$(_st_lines az)" -ne 0 ]; then echo "az calls: $(_st_lines az)"; return 1; fi
    if [ "$(_st_lines curl)" -ne 0 ]; then echo "curl calls: $(_st_lines curl)"; return 1; fi
    if [ "$(_st_kubectl_other)" -ne 0 ]; then echo "kubectl calls beyond config view and the pod read: $(_st_kubectl_other)"; return 1; fi
    if [ "$(_st_config_reads)" -gt "$max_config" ]; then echo "config reads=$(_st_config_reads) (max $max_config)"; return 1; fi
    if [ "$(_st_pod_reads)" -gt "$max_pods" ]; then echo "pod reads=$(_st_pod_reads) (max $max_pods)"; return 1; fi
    if [ "$(_st_leaks)" -ne 0 ]; then echo "secret marker printed"; return 1; fi
    return 0
  }
  local good=(--context jtoye-stg-aks --identity-file "$ast/identity.yaml" --stage vendor)

  # --- prod-api / local-api: any API base but the staging one ----------------
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${good[@]}" --api https://api.olajay.co.uk
  if why=$(_st_refusal 1 "REFUSED [api-base]" 1 0); then _st_result "PASS prod-api rc=$ST_RC az_calls=0 curl_calls=0 pod_reads=0"
  else _st_result "FAIL prod-api $why"; fails=$((fails + 1)); fi
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${good[@]}" --api http://localhost:9090
  if why=$(_st_refusal 1 "REFUSED [api-base]" 1 0); then _st_result "PASS local-api rc=$ST_RC az_calls=0 curl_calls=0 pod_reads=0"
  else _st_result "FAIL local-api $why"; fails=$((fails + 1)); fi

  # --- no-context ------------------------------------------------------------
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- --identity-file "$ast/identity.yaml" --stage vendor
  if why=$(_st_refusal 2 "USAGE ERROR" 0 0); then _st_result "PASS no-context rc=$ST_RC kubectl_calls=$(_st_lines kubectl)"
  else _st_result "FAIL no-context $why"; fails=$((fails + 1)); fi

  # --- stale-server: the shared staging_kube_guard is wired, not re-implemented
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- --context jtoye-staging --identity-file "$ast/identity.yaml" --stage vendor
  if why=$(_st_refusal 1 "REFUSED [server-url]" 1 0); then _st_result "PASS stale-server rc=$ST_RC kubectl_calls=$(_st_lines kubectl)(config view)"
  else _st_result "FAIL stale-server $why"; fails=$((fails + 1)); fi

  # --- the profile arms: read from the Ready pods, never the template --------
  local arm file
  for arm in dev-profile rollout-mixed no-ready-pod; do
    case "$arm" in
      dev-profile) file="$ast/pods-dev.json" ;;
      rollout-mixed) file="$ast/pods-mixed.json" ;;
      no-ready-pod) file="$ast/pods-none-ready.json" ;;
    esac
    _st_run STUB_PODS_FILE="$file" -- "${good[@]}"
    if why=$(_st_refusal 1 "REFUSED [profile]" 1 1); then
      if [ "$(_st_pod_reads)" -ne 1 ]; then why="pod reads=$(_st_pod_reads) (want 1: the refusal must come from the pods)"; fi
    fi
    if [ -z "$why" ]; then _st_result "PASS $arm rc=$ST_RC pod_reads=1 deployment_reads=0 az_calls=0 curl_calls=0"
    else _st_result "FAIL $arm $why"; fails=$((fails + 1)); fi
    why=""
  done

  # --- dry-run-plan: guards pass, the plan names every step, nothing is called
  _st_run STUB_PODS_FILE="$ast/pods-prod.json" -- "${good[@]}" --dry-run
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0): $(head -c 200 <<<"$ST_ERR" | tr '\n' ' ')"
  elif [ "$(_st_lines az)" -ne 0 ] || [ "$(_st_lines curl)" -ne 0 ]; then why="az=$(_st_lines az) curl=$(_st_lines curl) (want 0, 0)"
  elif [ "$(_st_kubectl_other)" -ne 0 ]; then why="kubectl calls beyond config view and the pod read: $(_st_kubectl_other)"
  elif [ "$(_st_config_reads)" -lt 1 ] || [ "$(_st_pod_reads)" -ne 1 ]; then why="guards did not run: config reads=$(_st_config_reads) pod reads=$(_st_pod_reads) (want >=1, 1)"
  elif [ "$(_st_leaks)" -ne 0 ]; then why="secret marker printed"
  else
    local step
    for step in /api/v1/admin/tenants /api/v1/onboarding /api/v1/trader-identity /api/v1/products/culinary-packs/seed /api/v1/onboarding/go-live; do
      command grep -qF -- "$step" <<<"$ST_OUT" || { why="the plan does not name $step"; break; }
    done
  fi
  if [ -z "$why" ]; then _st_result "PASS dry-run-plan rc=0 steps=$(command grep -cE '^  [0-9]{2} ' <<<"$ST_OUT") az_calls=0 curl_calls=0"
  else _st_result "FAIL dry-run-plan $why"; fails=$((fails + 1)); fi

  # --- list-kv-objects: offline, exactly the four names ----------------------
  _st_run -- --list-kv-objects
  local want_kv
  want_kv=$(printf '%s\n' keycloak-credentials--admin-password keycloak-credentials--admin-username \
    keycloak-credentials--frontend-client-secret staging-seed--test-vendor-password)
  why=""
  if [ "$ST_RC" -ne 0 ]; then why="rc=$ST_RC (want 0)"
  elif [ "$ST_OUT" != "$want_kv" ]; then why="listed '$(tr '\n' ' ' <<<"$ST_OUT")'"
  elif [ "$(_st_lines kubectl)" -ne 0 ] || [ "$(_st_lines az)" -ne 0 ] || [ "$(_st_lines curl)" -ne 0 ]; then why="a tool was called"
  fi
  if [ -z "$why" ]; then _st_result "PASS list-kv-objects rc=0 objects=4"
  else _st_result "FAIL list-kv-objects $why"; fails=$((fails + 1)); fi

  _st_render
  [ "$fails" -eq 0 ]
}

# ===========================================================================
# Flags. Parsed FIRST.
# ===========================================================================
ACTION="stage"
KUBE_CONTEXT=""
IDENTITY_FILE="$REPO_ROOT/k8s/staging/azure-identity.yaml"
API_BASE="$STAGING_API"
STAGE=""
DRY_RUN=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --context)         shift; [ "$#" -gt 0 ] || usage_error "--context needs a name"; KUBE_CONTEXT="$1" ;;
    --identity-file)   shift; [ "$#" -gt 0 ] || usage_error "--identity-file needs a path"; IDENTITY_FILE="$1" ;;
    --api)             shift; [ "$#" -gt 0 ] || usage_error "--api needs a URL"; API_BASE="$1" ;;
    --stage)           shift; [ "$#" -gt 0 ] || usage_error "--stage needs vendor|order|all"; STAGE="$1" ;;
    --dry-run)         DRY_RUN=1 ;;
    --list-kv-objects) ACTION="list-kv-objects" ;;
    --selftest)        ACTION="selftest" ;;
    -h|--help)         command sed -n '/^# USAGE/,/^# EXIT CODES/p' "$SCRIPT_PATH" | command sed 's/^# \{0,1\}//'; exit 0 ;;
    *)                 usage_error "unknown flag '$1'" ;;
  esac
  shift
done

case "$ACTION" in
  selftest)
    set +e; selftest; rc=$?; exit "$rc" ;;
  list-kv-objects)
    list_kv_objects; exit 0 ;;
esac

case "$STAGE" in
  vendor) ;;
  order|all) echo "VOID: --stage $STAGE is added by plan 29-18 (the order stage needs Stripe TEST settlement, D-31)" >&2; exit 2 ;;
  "") usage_error "--stage vendor is required" ;;
  *) usage_error "--stage must be vendor, order or all" ;;
esac

run_guards
if [ "$DRY_RUN" -eq 1 ]; then
  print_plan
  exit 0
fi
vendor_stage
