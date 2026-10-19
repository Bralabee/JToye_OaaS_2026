#!/usr/bin/env bash
# azure-staging-provision.sh — the compute and identity half of the staging
# estate (Phase 29 / DPLY-01, DPLY-05; decisions D-01, D-07, D-23, D-32).
#
# Adapted from the August script on `phase-29-research` (read with git show,
# never checked out). Carried: the guard-first order, the subscription pin, the
# "no CLI call may bypass the wrappers" idea (its STEP 1c, now a CI gate:
# scripts/check-az-subscription-pin.sh). NOT carried: PG16 and the database
# (plan 29-07 owns PostgreSQL 18), Azure Managed Redis (D-24 resolved to an
# in-cluster pod), the August SKUs, the snackpass disposition lock.
#
# ---------------------------------------------------------------------------
# THE DEFECT THIS PREVENTS
#   On this host the az CLI's ambient default subscription has been the
#   EMPLOYER's, and an empty `--subscription ""` silently resolves to it
#   (measured 2026-09-28). A wrong-subscription mutation is not recoverable by
#   re-running with the right one. So every call below goes through az_mutate or
#   az_read (scripts/lib/azure-guard.sh), which refuse unless az_guard_init has
#   asserted the J'Toye id and read its name and state back, and which append
#   `--subscription c483d353-…` as the last two argv entries.
#
# ---------------------------------------------------------------------------
# ORDER (fixed; each step precedes everything after it)
#   1. argument parsing     a typo can never fall through into a stage
#   2. STEP 0, decisions    refuse ("REFUSED [decisions]", rc 1) unless the
#                           decision record exists, carries the line
#                           `APPROVED: option-a` or `APPROVED: option-b` (written
#                           by plan 29-09's checkpoint) and defines POD_CIDR,
#                           KV_PURGE_PROTECTION and KV_RETENTION_DAYS as KEY=value
#                           lines. STEP 0 makes NO az call, so an unapproved run
#                           touches nothing, not even a read
#   3. dry-run stub check   under --dry-run, refuse (rc 2) unless `az` on PATH is
#                           the az_guard_stub (its directory holds .az-guard-stub):
#                           a dry-run can never reach the real CLI. A LIVE run
#                           refuses the stub, so a stub's answers can never be
#                           reported as provisioned state
#   4. az_guard_init        the subscription equality + read-back
#   5. the stages, in this order:
#        providers   register Microsoft.KeyVault + Microsoft.Storage (both
#                    measured NotRegistered), poll to Registered (10 min, VOID)
#        network     regional quota (standardBSFamily and total regional cores
#                    >= 8 free vCPU: 3 x B2s + 1 surge node); static Standard
#                    IPv4 jtoye-stg-ingress-ip (Traefik LoadBalancer, D-07) and
#                    jtoye-stg-outbound-ip (cluster egress: the only Postgres
#                    firewall rule, stable across cluster re-creation)
#        aks         jtoye-stg-aks (flags below); Network Contributor for the
#                    control-plane identity on the ingress IP ONLY; Azure
#                    Kubernetes Service RBAC Cluster Admin for the signed-in
#                    OPERATOR (never the CI identity)
#        keyvault    jtoye-stg-kv in RBAC mode; Key Vault Secrets Officer for the
#                    operator (plan 29-13's write-once writer runs as the operator)
#        identities  ten user-assigned identities, one federated credential each,
#                    subject exactly system:serviceaccount:jtoye-staging:<sa>
#        ci          read back jtoye-ci and gh-deploy-staging (REFUSE on a wrong
#                    subject); Cluster User Role at cluster scope and RBAC Writer
#                    at <cluster>/namespaces/jtoye-staging only (D-32)
#   6. STEP 9 evidence      identifiers only (resource ids, client ids, principal
#                           ids, issuer URL), never a value
#
# DRY RUN
#   Every call reaches the az_guard_stub, which records and echoes its argv and
#   mutates nothing. Every poll and every read-back ASSERTION is skipped with a
#   `DRY-RUN skip: <what>` line (a stub's answer is not state); existence probes
#   are skipped and the resource assumed ABSENT, so the full first-time argv is
#   shown. The reads that compose later argv (outbound IP id, issuer URL,
#   operator UPN, latest 1.36.x, principal ids) still go through az_read and get
#   the stub's canned identifier, so the argv shape is exercised. The live run
#   (plan 29-09) performs every poll and assertion.
#
# WHAT THIS SCRIPT DELIBERATELY DOES NOT DO
#   PostgreSQL (plan 29-07), storage accounts (docs/runbooks/azure-blob-provisioning.md),
#   Key Vault role assignments per secret (they need the secrets; plan 29-13), DNS
#   (Namecheap, by the owner), Kubernetes objects, secrets.
#
# USAGE
#   JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94 \
#     scripts/azure-staging-provision.sh [--dry-run] [--stage STAGE] [--decisions PATH]
#     --dry-run         every call goes to the az_guard_stub; mutate nothing
#     --stage STAGE     providers|network|aks|keyvault|identities|ci|all (default all)
#     --decisions PATH  default .planning/phases/29-deployable-staging-with-its-own-monitoring/29-ESTATE-DECISIONS.md
#
# EXIT CODES: 0 = done (or dry-run printed), 1 = a guard refused or a
#             verification failed, 2 = usage / tooling / VOID.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ok()     { echo "OK: $*"; }
step()   { echo; echo "=== $* ==="; }
refuse() { local arm="$1"; shift; echo "REFUSED [$arm]: $*" >&2; exit 1; }
die()    { echo "FAIL: $*" >&2; exit 1; }
void()   { echo "VOID: $*" >&2; exit 2; }
# stderr, never stdout: a skip line inside a function whose stdout is captured
# (operator_upn) became part of the captured value and was passed to az as an
# --assignee — caught by the dry-run's every-line-pinned count (55 of 57 lines).
skip()   { echo "DRY-RUN skip: $*" >&2; }

usage() {
  sed -n '/^# USAGE/,/^# EXIT CODES/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
}

# ---------------------------------------------------------------------------
# 1. argument parsing
# ---------------------------------------------------------------------------
DRY_RUN=0
STAGE=all
DECISIONS_FILE="$REPO_ROOT/.planning/phases/29-deployable-staging-with-its-own-monitoring/29-ESTATE-DECISIONS.md"
STAGES_ORDERED="providers network aks keyvault identities ci"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --stage)
      shift
      [ "$#" -gt 0 ] || { echo "USAGE ERROR: --stage needs a name" >&2; exit 2; }
      STAGE="$1"
      ;;
    --decisions)
      shift
      [ "$#" -gt 0 ] || { echo "USAGE ERROR: --decisions needs a path" >&2; exit 2; }
      DECISIONS_FILE="$1"
      ;;
    -h|--help) usage; exit 0 ;;
    *) echo "USAGE ERROR: unknown argument '$1'" >&2; usage >&2; exit 2 ;;
  esac
  shift
done
case " $STAGES_ORDERED all " in
  *" $STAGE "*) ;;
  *) echo "USAGE ERROR: --stage must be one of: $STAGES_ORDERED all (got '$STAGE')" >&2; exit 2 ;;
esac

# The naming contract (29-01 interfaces block). Constants, not overrides: every
# later plan's manifests and SecretProviderClasses name these exact strings.
RG="jtoye-rg"
LOCATION="uksouth"
AKS_NAME="jtoye-stg-aks"
INGRESS_IP_NAME="jtoye-stg-ingress-ip"
OUTBOUND_IP_NAME="jtoye-stg-outbound-ip"
KV_NAME="jtoye-stg-kv"
NAMESPACE="jtoye-staging"
K8S_MINOR="1.36"
NODE_VM_SIZE="Standard_B2s"
NODE_COUNT=3
QUOTA_FREE_VCPU_MIN=8          # 3 x B2s (2 vCPU) + 1 surge node during upgrades
QUOTA_SURGE_VCPU_MIN=2         # re-run with the cluster already created: its 6 vCPU are
                               # already counted as used, so only the surge node must fit
PROVIDERS="Microsoft.KeyVault Microsoft.Storage"
PROVIDER_DEADLINE_SECONDS=600
WI_AUDIENCE="api://AzureADTokenExchange"
# identity -> the ServiceAccount it federates (subject system:serviceaccount:jtoye-staging:<sa>)
IDENTITIES=(
  "jtoye-staging-media-id:core-java"
  "jtoye-staging-backup-id:pg-backup"
  "jtoye-staging-frontend-id:frontend"
  "jtoye-staging-keycloak-id:keycloak"
  "jtoye-staging-secret-sync-id:secret-sync"
  "jtoye-staging-redis-id:redis"
  "jtoye-staging-pg-exporter-id:postgres-exporter"
  "jtoye-staging-redis-exporter-id:redis-exporter"
  "jtoye-staging-grafana-id:grafana"
  "jtoye-staging-alertmanager-id:alertmanager"
)
CI_IDENTITY="jtoye-ci"
CI_FEDCRED="gh-deploy-staging"
CI_SUBJECT="repo:Bralabee/JToye_OaaS_2026:environment:staging"
CI_ISSUER="https://token.actions.githubusercontent.com"
ROLE_NETWORK_CONTRIBUTOR="Network Contributor"
ROLE_AKS_CLUSTER_ADMIN="Azure Kubernetes Service RBAC Cluster Admin"
ROLE_AKS_CLUSTER_USER="Azure Kubernetes Service Cluster User Role"
ROLE_AKS_RBAC_WRITER="Azure Kubernetes Service RBAC Writer"
ROLE_KV_SECRETS_OFFICER="Key Vault Secrets Officer"

echo "=== J'Toye staging estate: compute + identity (dry-run=${DRY_RUN}, stage=${STAGE}) ==="

# ---------------------------------------------------------------------------
# 2. STEP 0 — the decision record. No az call of any kind before this passes.
# ---------------------------------------------------------------------------
step "STEP 0: decision record"
[ -f "$DECISIONS_FILE" ] || refuse decisions "no decision record at ${DECISIONS_FILE}. Plan 29-09's checkpoint writes it after the owner approves the one-way parameter set; nothing runs before that."
if ! command grep -qxE 'APPROVED: option-(a|b)[[:space:]]*' "$DECISIONS_FILE"; then
  refuse decisions "${DECISIONS_FILE} carries no 'APPROVED: option-a' or 'APPROVED: option-b' line; the one-way parameters (pod CIDR, Key Vault purge protection) are not approved"
fi
APPROVED_OPTION="$(command grep -xE 'APPROVED: option-(a|b)[[:space:]]*' "$DECISIONS_FILE" | sed -E 's/^APPROVED: (option-[ab]).*$/\1/' | sort -u)"
[ "$(printf '%s\n' "$APPROVED_OPTION" | wc -l)" -eq 1 ] || refuse decisions "${DECISIONS_FILE} approves more than one option"

# decision_key KEY -> the value of the single `KEY=value` line; refuses when the
# key is missing, empty, or given twice with different values.
decision_key() {
  local key="$1" vals
  vals="$(command grep -E "^${key}=" "$DECISIONS_FILE" | sed -E "s/^${key}=//; s/[[:space:]]+\$//" | sort -u)" || true
  [ -n "$vals" ] || refuse decisions "${DECISIONS_FILE} does not define ${key} (a missing or empty key is not a value this script may guess)"
  [ "$(printf '%s\n' "$vals" | wc -l)" -eq 1 ] || refuse decisions "${DECISIONS_FILE} defines ${key} more than once with different values"
  printf '%s\n' "$vals"
}
POD_CIDR="$(decision_key POD_CIDR)"
KV_PURGE_PROTECTION="$(decision_key KV_PURGE_PROTECTION)"
KV_RETENTION_DAYS="$(decision_key KV_RETENTION_DAYS)"
[[ $POD_CIDR =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}/[0-9]{1,2}$ ]] || refuse decisions "POD_CIDR '${POD_CIDR}' is not an IPv4 CIDR"
case "$KV_PURGE_PROTECTION" in true|false) ;; *) refuse decisions "KV_PURGE_PROTECTION must be true or false, got '${KV_PURGE_PROTECTION}'" ;; esac
[[ $KV_RETENTION_DAYS =~ ^[0-9]+$ ]] && [ "$KV_RETENTION_DAYS" -ge 7 ] && [ "$KV_RETENTION_DAYS" -le 90 ] \
  || refuse decisions "KV_RETENTION_DAYS must be an integer 7..90 (Key Vault soft-delete range), got '${KV_RETENTION_DAYS}'"
ok "decision record ${APPROVED_OPTION}: POD_CIDR=${POD_CIDR} KV_PURGE_PROTECTION=${KV_PURGE_PROTECTION} KV_RETENTION_DAYS=${KV_RETENTION_DAYS}"

# ---------------------------------------------------------------------------
# 3. which az: the stub under --dry-run, never the stub otherwise
# ---------------------------------------------------------------------------
AZ_PATH="$(command -v az 2>/dev/null || true)"
AZ_IS_STUB=0
if [ -n "$AZ_PATH" ] && [ -f "$(dirname "$AZ_PATH")/.az-guard-stub" ]; then AZ_IS_STUB=1; fi
if [ "$DRY_RUN" -eq 1 ] && [ "$AZ_IS_STUB" -ne 1 ]; then
  echo "VOID [dry-run]: az on PATH is '${AZ_PATH:-<none>}', not an az_guard_stub (no .az-guard-stub beside it). A dry-run must never reach the real CLI: create one with az_guard_stub DIR and put DIR first on PATH." >&2
  exit 2
fi
if [ "$DRY_RUN" -eq 0 ] && [ "$AZ_IS_STUB" -eq 1 ]; then
  echo "VOID [live]: az on PATH is the az_guard_stub at '${AZ_PATH}'; a live run against the test double would report its canned answers as provisioned state" >&2
  exit 2
fi

# ---------------------------------------------------------------------------
# 4. the subscription guard (sets AZ_GUARD_OK in THIS shell)
# ---------------------------------------------------------------------------
# shellcheck source=scripts/lib/azure-guard.sh
. "$SCRIPT_DIR/lib/azure-guard.sh"
step "STEP 1: subscription guard"
GUARD_RC=0
az_guard_init || GUARD_RC=$?
[ "$GUARD_RC" -eq 0 ] || exit "$GUARD_RC"

# ---------------------------------------------------------------------------
# helpers (every CLI call below is az_mutate or az_read)
# ---------------------------------------------------------------------------
EVIDENCE=()
evidence() { EVIDENCE+=("$1"); }

# read_required DESC <az args…> -> stdout of a read that must succeed and be non-empty
read_required() {
  local desc="$1" out rc=0
  shift
  out="$(az_read "$@")" || rc=$?
  [ "$rc" -eq 0 ] || die "could not read ${desc} (az rc=${rc})"
  [ -n "$out" ] || die "read of ${desc} returned nothing; an empty identifier is not one"
  printf '%s\n' "$out"
}

# exists DESC <az show args…> -> 0 present, 1 absent (az rc 3). Live only.
exists() {
  local desc="$1" rc=0
  shift
  az_read "$@" >/dev/null 2>&1 || rc=$?
  case "$rc" in
    0) ok "${desc} already exists: read back, NOT recreated"; return 0 ;;
    3) return 1 ;;
    *) void "existence probe for ${desc} failed with az rc=${rc} (only 3 means not found)" ;;
  esac
}

# want_absent DESC <az show args…> -> 0 when the create must run
want_create() {
  local desc="$1"
  shift
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "existence probe for ${desc}; assumed ABSENT so the create argv is shown"
    return 0
  fi
  if exists "$desc" "$@"; then return 1; fi
  return 0
}

# One row per field, in query order, whether az printed the list as one
# tab-separated row or one value per line.
rows() { printf '%s\n' "$1" | tr '\t' '\n'; }

# The signed-in operator, resolved in the pinned subscription's tenant. Never
# `ad signed-in-user show`: it takes no --subscription and reads whichever tenant
# the CLI happens to be in.
OPERATOR_UPN=""
operator_upn() {
  local type
  if [ -n "$OPERATOR_UPN" ]; then printf '%s\n' "$OPERATOR_UPN"; return 0; fi
  OPERATOR_UPN="$(read_required 'signed-in operator (user.name)' account show --query user.name -o tsv)"
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "assert the signed-in account is a user (user.type), not a service principal or managed identity"
  else
    type="$(read_required 'signed-in account type (user.type)' account show --query user.type -o tsv)"
    [ "$type" = user ] || refuse operator "the signed-in account is a '${type}', not a user; the operator roles go to a human, never to the CI identity or any other principal"
  fi
  printf '%s\n' "$OPERATOR_UPN"
}

aks_id() { read_required "AKS cluster id (${AKS_NAME})" aks show -g "$RG" -n "$AKS_NAME" --query id -o tsv; }

# ---------------------------------------------------------------------------
# stages
# ---------------------------------------------------------------------------
stage_providers() {
  local p state deadline
  step "STAGE providers: ${PROVIDERS}"
  for p in $PROVIDERS; do
    az_mutate provider register -n "$p"
  done
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "poll ${PROVIDERS} until registrationState=Registered (deadline ${PROVIDER_DEADLINE_SECONDS}s)"
    return 0
  fi
  for p in $PROVIDERS; do
    deadline=$((SECONDS + PROVIDER_DEADLINE_SECONDS))
    while :; do
      state="$(read_required "registration state of ${p}" provider show -n "$p" --query registrationState -o tsv)"
      [ "$state" = Registered ] && break
      [ "$SECONDS" -lt "$deadline" ] || void "${p} still '${state}' after ${PROVIDER_DEADLINE_SECONDS}s"
      sleep 10
    done
    ok "${p} Registered"
    evidence "provider ${p} : Registered"
  done
}

stage_network() {
  local usage fields family_cur family_lim cores_cur cores_lim free_family free_cores name id need need_why
  step "STAGE network: quota + static IPs"
  usage="$(read_required 'uksouth vCPU usage' vm list-usage -l "$LOCATION" \
    --query "[?name.value=='standardBSFamily' || name.value=='cores'].[name.value, currentValue, limit]" -o tsv)"
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "assert >= ${QUOTA_FREE_VCPU_MIN} free vCPU in standardBSFamily and in regional cores"
  else
    fields="$(printf '%s\n' "$usage" | tr '\t' ' ')"
    family_cur="$(awk '$1=="standardBSFamily"{print $2}' <<<"$fields")"
    family_lim="$(awk '$1=="standardBSFamily"{print $3}' <<<"$fields")"
    cores_cur="$(awk '$1=="cores"{print $2}' <<<"$fields")"
    cores_lim="$(awk '$1=="cores"{print $3}' <<<"$fields")"
    for v in "$family_cur" "$family_lim" "$cores_cur" "$cores_lim"; do
      [[ $v =~ ^[0-9]+$ ]] || void "could not parse the uksouth quota read (got '$(printf '%s' "$usage" | tr '\n\t' '| ')')"
    done
    free_family=$((family_lim - family_cur)); free_cores=$((cores_lim - cores_cur))
    # A re-run after the cluster exists: its nodes already sit in currentValue, so
    # demanding 8 free would refuse every re-run (measured on the first live
    # re-run, 2026-10-19: "standardBSFamily has 4 free vCPU"). Only the upgrade
    # surge node still has to fit. The probe reads, it never adopts: stage aks
    # still asserts every one-way property of the existing cluster.
    need="$QUOTA_FREE_VCPU_MIN"; need_why="3 x ${NODE_VM_SIZE} + 1 surge"
    if exists "AKS cluster ${AKS_NAME} (quota already consumed by its nodes)" aks show -g "$RG" -n "$AKS_NAME" --query id -o tsv; then
      need="$QUOTA_SURGE_VCPU_MIN"; need_why="1 surge ${NODE_VM_SIZE}; the 3 nodes of the existing cluster are already counted"
    fi
    [ "$free_family" -ge "$need" ] || refuse quota "standardBSFamily has ${free_family} free vCPU in ${LOCATION}; ${need} needed (${need_why})"
    [ "$free_cores" -ge "$need" ] || refuse quota "regional cores have ${free_cores} free vCPU in ${LOCATION}; ${need} needed (${need_why})"
    ok "quota: standardBSFamily ${free_family} free, regional cores ${free_cores} free (${need} needed: ${need_why})"
  fi
  for name in "$INGRESS_IP_NAME" "$OUTBOUND_IP_NAME"; do
    if want_create "public IP ${name}" network public-ip show -g "$RG" -n "$name" --query id -o tsv; then
      az_mutate --no-echo network public-ip create -g "$RG" -n "$name" -l "$LOCATION" \
        --sku Standard --version IPv4 --allocation-method Static
    fi
    id="$(read_required "public IP ${name} id" network public-ip show -g "$RG" -n "$name" --query id -o tsv)"
    evidence "public IP ${name} : ${id}"
  done
}

stage_aks() {
  local outbound_id versions k8s_version ingress_id cp_pid cluster_id upn props
  step "STAGE aks: ${AKS_NAME}"
  outbound_id="$(read_required "outbound IP ${OUTBOUND_IP_NAME} id" network public-ip show -g "$RG" -n "$OUTBOUND_IP_NAME" --query id -o tsv)"
  versions="$(read_required "AKS ${K8S_MINOR}.x patch versions in ${LOCATION}" aks get-versions -l "$LOCATION" \
    --query "values[?version=='${K8S_MINOR}' && !isPreview].patchVersions | [0] | keys(@)" -o tsv)"
  k8s_version="$(rows "$versions" | command grep -E "^${K8S_MINOR//./\\.}\\.[0-9]+$" | sort -V | tail -n 1 || true)"
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "assert the latest ${K8S_MINOR}.x parses as a version; using the stub's answer to show the argv"
    k8s_version="$(rows "$versions" | head -n 1)"
  else
    [ -n "$k8s_version" ] || void "no non-preview ${K8S_MINOR}.x patch version offered in ${LOCATION} (got '$(printf '%s' "$versions" | tr '\n\t' '| ')')"
  fi

  # The network-policy ENGINE is chosen here, at creation, and cannot be swapped
  # in place (assumption delta, 2026-10-18): Cilium (AKS-native dataplane and
  # policy), because Microsoft recommends it, Azure NPM retires on Linux
  # 2028-09-30, and Calico is supported for standard NetworkPolicy only. The
  # manifests stay standard networking.k8s.io/v1 NetworkPolicy, so the engine
  # adds no CRD dependency; plan 29-02's invariant pins the render to that
  # dialect, and this argv to `--network-policy cilium`, so the two cannot drift.
  # Overlay mode and the pod CIDR are one-way too, hence the approved decision.
  # --tier free: Standard's uptime SLA alone would eat a third of the D-03 ceiling.
  # Ephemeral 30 GiB OS disks: the default 128 GiB P10 bills ~GBP 18/disk/month.
  # --enable-aad --enable-azure-rbac --disable-local-accounts (D-32): no static
  # admin kubeconfig exists; access is Entra ID role assignments only.
  # --no-echo: create output is not printed (the August admin-password leak came
  # from create output).
  if want_create "AKS cluster ${AKS_NAME}" aks show -g "$RG" -n "$AKS_NAME" --query id -o tsv; then
    az_mutate --no-echo aks create -g "$RG" -n "$AKS_NAME" -l "$LOCATION" \
      --tier free \
      --kubernetes-version "$k8s_version" \
      --network-plugin azure --network-plugin-mode overlay --pod-cidr "$POD_CIDR" \
      --network-dataplane cilium --network-policy cilium \
      --node-vm-size "$NODE_VM_SIZE" --node-count "$NODE_COUNT" \
      --node-osdisk-type Ephemeral --node-osdisk-size 30 \
      --enable-oidc-issuer --enable-workload-identity \
      --enable-addons azure-keyvault-secrets-provider \
      --enable-aad --enable-azure-rbac --disable-local-accounts \
      --load-balancer-outbound-ips "$outbound_id" \
      --auto-upgrade-channel patch --node-os-upgrade-channel NodeImage \
      --generate-ssh-keys
  fi

  if [ "$DRY_RUN" -eq 1 ]; then
    skip "read back ${AKS_NAME}: overlay, cilium dataplane + policy, pod CIDR ${POD_CIDR}, OIDC issuer, workload identity, local accounts disabled, Azure RBAC, Key Vault CSI add-on"
  else
    props="$(read_required "${AKS_NAME} one-way properties" aks show -g "$RG" -n "$AKS_NAME" \
      --query "[networkProfile.networkPluginMode, networkProfile.networkDataplane, networkProfile.networkPolicy, networkProfile.podCidr, oidcIssuerProfile.enabled, securityProfile.workloadIdentity.enabled, disableLocalAccounts, aadProfile.enableAzureRbac, addonProfiles.azureKeyvaultSecretsProvider.enabled]" -o tsv)"
    # Lower-cased before the comparison: azure-cli 2.91.0 prints a JMESPath boolean
    # in tsv as `true`, not `True` (measured on the first live run, 2026-10-19,
    # where the capitalised comparison refused a correctly created cluster). The
    # storage script already lower-cases its read-backs the same way.
    props="$(rows "$props" | tr '\n' ' ' | tr '[:upper:]' '[:lower:]')"
    [ "$props" = "overlay cilium cilium ${POD_CIDR} true true true true true " ] \
      || refuse aks "${AKS_NAME} read back as '${props}', want 'overlay cilium cilium ${POD_CIDR} true true true true true'. An existing cluster with other one-way properties is never adopted"
    ok "${AKS_NAME} one-way properties read back as created"
  fi

  cluster_id="$(aks_id)"
  evidence "AKS cluster ${AKS_NAME} : ${cluster_id}"
  evidence "AKS kubernetes version : ${k8s_version}"

  # Traefik's LoadBalancer Service binds jtoye-stg-ingress-ip from jtoye-rg, so the
  # control-plane identity needs Network Contributor on that ONE resource.
  ingress_id="$(read_required "ingress IP ${INGRESS_IP_NAME} id" network public-ip show -g "$RG" -n "$INGRESS_IP_NAME" --query id -o tsv)"
  cp_pid="$(read_required "${AKS_NAME} control-plane identity principal id" aks show -g "$RG" -n "$AKS_NAME" --query identity.principalId -o tsv)"
  az_mutate --no-echo role assignment create --assignee-object-id "$cp_pid" --assignee-principal-type ServicePrincipal \
    --role "$ROLE_NETWORK_CONTRIBUTOR" --scope "$ingress_id"
  evidence "AKS control-plane principal : ${cp_pid} (${ROLE_NETWORK_CONTRIBUTOR} on ${INGRESS_IP_NAME} only)"

  # With local accounts disabled, the owner applies the cluster-scoped platform
  # objects (CRDs, ClusterRoles, ClusterIssuers; plan 29-11) through this role.
  upn="$(operator_upn)"
  az_mutate --no-echo role assignment create --assignee "$upn" \
    --role "$ROLE_AKS_CLUSTER_ADMIN" --scope "$cluster_id"
  evidence "operator ${upn} : ${ROLE_AKS_CLUSTER_ADMIN} on ${AKS_NAME}"
}

stage_keyvault() {
  local kv_id upn props
  local -a purge=()
  step "STAGE keyvault: ${KV_NAME}"
  if [ "$KV_PURGE_PROTECTION" = true ]; then purge=(--enable-purge-protection true); fi
  if want_create "Key Vault ${KV_NAME}" keyvault show -n "$KV_NAME" -g "$RG" --query id -o tsv; then
    az_mutate --no-echo keyvault create -n "$KV_NAME" -g "$RG" -l "$LOCATION" \
      --enable-rbac-authorization true --retention-days "$KV_RETENTION_DAYS" "${purge[@]}"
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "read back ${KV_NAME}: RBAC authorization, retention ${KV_RETENTION_DAYS} days, purge protection ${KV_PURGE_PROTECTION}"
  else
    props="$(read_required "${KV_NAME} properties" keyvault show -n "$KV_NAME" -g "$RG" \
      --query "[properties.enableRbacAuthorization, properties.softDeleteRetentionInDays, properties.enablePurgeProtection]" -o tsv)"
    # Lower-cased for the same reason as the AKS read-back (tsv booleans are `true`).
    props="$(rows "$props" | tr '\n' ' ' | tr '[:upper:]' '[:lower:]')"
    case "$props" in
      "true ${KV_RETENTION_DAYS} true "|"true ${KV_RETENTION_DAYS} false "|"true ${KV_RETENTION_DAYS}  ") ;;
      *) refuse keyvault "${KV_NAME} read back as '${props}' (rbac, retention, purge); RBAC mode and retention ${KV_RETENTION_DAYS} are required" ;;
    esac
    if [ "$KV_PURGE_PROTECTION" = true ]; then
      [ "$props" = "true ${KV_RETENTION_DAYS} true " ] || refuse keyvault "${KV_NAME} purge protection is not on, but the decision record says it must be"
    else
      [ "$props" != "true ${KV_RETENTION_DAYS} true " ] || refuse keyvault "${KV_NAME} purge protection is ON (one-way), but the decision record says false"
    fi
    ok "${KV_NAME} read back: ${props}"
  fi
  kv_id="$(read_required "Key Vault ${KV_NAME} id" keyvault show -n "$KV_NAME" -g "$RG" --query id -o tsv)"
  evidence "Key Vault ${KV_NAME} : ${kv_id}"
  upn="$(operator_upn)"
  az_mutate --no-echo role assignment create --assignee "$upn" \
    --role "$ROLE_KV_SECRETS_OFFICER" --scope "$kv_id"
  evidence "operator ${upn} : ${ROLE_KV_SECRETS_OFFICER} on ${KV_NAME}"
}

stage_identities() {
  local issuer pair id_name sa subject fc_name ids got rc
  step "STAGE identities: ${#IDENTITIES[@]} user-assigned identities + federated credentials"
  issuer="$(read_required "${AKS_NAME} OIDC issuer URL" aks show -g "$RG" -n "$AKS_NAME" --query oidcIssuerProfile.issuerUrl -o tsv)"
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "assert the issuer URL is https://"
  else
    [[ $issuer == https://* ]] || refuse identities "the OIDC issuer read back as '${issuer}', not an https URL"
  fi
  evidence "AKS OIDC issuer : ${issuer}"
  for pair in "${IDENTITIES[@]}"; do
    id_name="${pair%%:*}"
    sa="${pair#*:}"
    subject="system:serviceaccount:${NAMESPACE}:${sa}"
    fc_name="${sa}-${NAMESPACE}"
    if want_create "identity ${id_name}" identity show -g "$RG" -n "$id_name" --query id -o tsv; then
      az_mutate --no-echo identity create -g "$RG" -n "$id_name" -l "$LOCATION"
    fi
    if [ "$DRY_RUN" -eq 1 ]; then
      skip "existence probe for federated credential ${id_name}/${fc_name}; assumed ABSENT so the create argv is shown"
      az_mutate --no-echo identity federated-credential create --identity-name "$id_name" -g "$RG" -n "$fc_name" \
        --issuer "$issuer" --subject "$subject" --audiences "$WI_AUDIENCE"
    else
      rc=0
      got="$(az_read identity federated-credential show --identity-name "$id_name" -g "$RG" -n "$fc_name" \
        --query "[issuer, subject]" -o tsv 2>/dev/null)" || rc=$?
      case "$rc" in
        0)
          got="$(rows "$got" | tr '\n' ' ')"
          [ "$got" = "${issuer} ${subject} " ] || refuse identities "federated credential ${id_name}/${fc_name} reads back '${got}', want issuer ${issuer} and subject ${subject}; it is never recreated silently"
          ok "federated credential ${id_name}/${fc_name} read back: subject ${subject}"
          ;;
        3)
          az_mutate --no-echo identity federated-credential create --identity-name "$id_name" -g "$RG" -n "$fc_name" \
            --issuer "$issuer" --subject "$subject" --audiences "$WI_AUDIENCE"
          ;;
        *) void "reading federated credential ${id_name}/${fc_name} failed with az rc=${rc}" ;;
      esac
    fi
    ids="$(read_required "identity ${id_name} client and principal ids" identity show -g "$RG" -n "$id_name" --query "[clientId, principalId]" -o tsv)"
    ids="$(rows "$ids" | tr '\n' ' ')"
    evidence "identity ${id_name} -> ${subject} : clientId/principalId ${ids% }"
  done
}

stage_ci() {
  local ci_pid fc cluster_id admins
  step "STAGE ci: ${CI_IDENTITY} (reused, never created here)"
  ci_pid="$(read_required "${CI_IDENTITY} principal id (the identity exists; this phase reuses it)" identity show -g "$RG" -n "$CI_IDENTITY" --query principalId -o tsv)"
  fc="$(read_required "${CI_IDENTITY}/${CI_FEDCRED} issuer and subject" identity federated-credential show \
    --identity-name "$CI_IDENTITY" -g "$RG" -n "$CI_FEDCRED" --query "[issuer, subject]" -o tsv)"
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "assert ${CI_FEDCRED} issuer ${CI_ISSUER} and subject ${CI_SUBJECT}"
  else
    fc="$(rows "$fc" | tr '\n' ' ')"
    [ "$fc" = "${CI_ISSUER} ${CI_SUBJECT} " ] || refuse ci "${CI_IDENTITY}/${CI_FEDCRED} reads back '${fc}', want issuer ${CI_ISSUER} and subject ${CI_SUBJECT}"
    ok "${CI_IDENTITY}/${CI_FEDCRED} subject ${CI_SUBJECT}"
  fi
  cluster_id="$(aks_id)"
  # D-32 least privilege: get-credentials at cluster scope, write only inside the
  # staging namespace. The one cluster-scoped object CI applies, the Namespace, is
  # granted by plan 29-03's narrowly-scoped ClusterRole, never by Cluster Admin.
  az_mutate --no-echo role assignment create --assignee-object-id "$ci_pid" --assignee-principal-type ServicePrincipal \
    --role "$ROLE_AKS_CLUSTER_USER" --scope "$cluster_id"
  az_mutate --no-echo role assignment create --assignee-object-id "$ci_pid" --assignee-principal-type ServicePrincipal \
    --role "$ROLE_AKS_RBAC_WRITER" --scope "${cluster_id}/namespaces/${NAMESPACE}"
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "assert ${CI_IDENTITY} holds no AKS cluster-admin role anywhere in the subscription"
  else
    admins="$(read_required "${CI_IDENTITY} AKS admin role count" role assignment list --assignee-object-id "$ci_pid" --all \
      --query "length([?roleDefinitionName=='${ROLE_AKS_CLUSTER_ADMIN}' || roleDefinitionName=='Azure Kubernetes Service Cluster Admin Role'])" -o tsv)"
    [ "$admins" = 0 ] || refuse ci "${CI_IDENTITY} holds ${admins} AKS cluster-admin assignment(s); D-32 gives CI Cluster User + namespace RBAC Writer only"
    ok "${CI_IDENTITY} holds no AKS cluster-admin role"
  fi
  evidence "CI ${CI_IDENTITY} principal : ${ci_pid} (${ROLE_AKS_CLUSTER_USER} on ${AKS_NAME}; ${ROLE_AKS_RBAC_WRITER} on namespaces/${NAMESPACE})"
}

# ---------------------------------------------------------------------------
# 5. run the stages
# ---------------------------------------------------------------------------
for s in $STAGES_ORDERED; do
  if [ "$STAGE" = all ] || [ "$STAGE" = "$s" ]; then
    "stage_${s}"
  fi
done

# ---------------------------------------------------------------------------
# 6. STEP 9 — evidence: identifiers only
# ---------------------------------------------------------------------------
step "STEP 9: evidence (identifiers only)"
echo "--- BEGIN STAGING ESTATE EVIDENCE ---"
echo "captured        : $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "git commit      : $(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
echo "dry-run         : ${DRY_RUN}"
echo "stage           : ${STAGE}"
echo "decision record : ${DECISIONS_FILE} (${APPROVED_OPTION})"
echo "subscription    : ${JTOYE_SUBSCRIPTION_NAME_EXPECTED} (${JTOYE_AZURE_SUBSCRIPTION_ID})"
echo "resource group  : ${RG} (${LOCATION})"
for e in "${EVIDENCE[@]+"${EVIDENCE[@]}"}"; do echo "$e"; done
echo "--- END STAGING ESTATE EVIDENCE ---"

if [ "$DRY_RUN" -eq 1 ]; then
  echo
  echo "PASS: --dry-run sent every call to the az_guard_stub; nothing was created."
else
  echo
  echo "PASS: stage(s) '${STAGE}' provisioned and read back."
fi
