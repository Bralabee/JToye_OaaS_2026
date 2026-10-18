#!/usr/bin/env bash
# azure-staging-storage.sh — the staging Blob estate, exactly as
# docs/runbooks/azure-blob-provisioning.md specifies it
# (Phase 29 / DPLY-01, DPLY-04; decision D-11 as amended by Phase 36; threats
# T-29-24, T-29-01).
#
# The runbook is the specification; this script is its executable form. Section
# numbers in the STEP banners are the runbook's, so a reader can hold the two
# side by side:
#   §2.1  Microsoft.Storage registered, then `check-name` for both accounts. A
#         taken name REFUSES ("runbook §9: owner decision"): the names are in
#         every persisted image URL, so a substitute is never picked on the spot
#   §2.2  jtoyestgmedia (uksouth, anonymous blob access ALLOWED at account level)
#         and jtoyestgbackup (ukwest, never public); shared-key access disabled
#         on both, so a leaked key authorises nothing
#   §3    containers through the control plane (container-rm): jtoye-images
#         (access level blob, never container: #626), jtoye-quarantine (private),
#         jtoye-db-backups (private)
#   §4    the backup account, in the runbook's order: blob soft delete 14 days,
#         container time-based retention 30 days created UNLOCKED, lifecycle rule
#         deleting backups/ after 35 days
#   §5    read only: the two workload identities plan 29-01 created
#   §6    container-scope RBAC: Storage Blob Data Contributor for
#         jtoye-staging-media-id on each media container; the write-only custom
#         role "J'Toye Backup Blob Writer" for jtoye-staging-backup-id on
#         jtoye-db-backups. Nothing at account, group or subscription scope
#   §8    --readback: the settings printed RAW (A10 allowBlobPublicAccess, A12
#         primaryLocation, soft delete, lifecycle, retention policy state, RBAC)
#
# THE ONE-WAY LOCK IS NOT HERE, BY CONSTRUCTION
#   Locking the retention policy (runbook §4.4) is irreversible until the
#   retention lapses, and it is a named human step after the plan 29-20 restore
#   drill. This script contains no command that can lock it; plan 29-07's verify
#   greps every non-comment line for the lock spellings, with the runbook (which
#   documents the human command) as the positive control.
#
# ORDER (fixed): argument parsing; STEP 0 decisions (refuse "REFUSED
# [decisions]" rc 1 with ZERO az calls unless the plan 29-09 record carries
# `APPROVED: option-a` or `APPROVED: option-b`: these accounts are billable);
# the dry-run stub check; az_guard_init; the runbook steps.
#
# DRY RUN
#   Every call reaches the az_guard_stub, which records argv and mutates nothing.
#   The provider poll, the check-name VERDICT, every existence probe and every
#   read-back assertion are skipped with a `DRY-RUN skip:` line on stderr (a
#   stub's answer is not state); every create still reaches the stub, so its argv
#   is recorded. The check-name call itself still runs (its argv is recorded; its
#   answer is not read).
#
# USAGE
#   JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94 \
#     scripts/azure-staging-storage.sh [--dry-run] [--readback] [--decisions PATH]
#     --dry-run         every call goes to the az_guard_stub; mutate nothing
#     --readback        runbook §8 read-backs only (no mutation), printed raw
#     --decisions PATH  default .planning/phases/29-deployable-staging-with-its-own-monitoring/29-ESTATE-DECISIONS.md
#
# EXIT CODES: 0 = done (or dry-run printed), 1 = a guard refused, a
#             verification failed, or a read-back read failed,
#             2 = usage / tooling / VOID.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ok()     { echo "OK: $*"; }
rstep()  { echo; echo "STEP runbook §$1: $2"; }
refuse() { local arm="$1"; shift; echo "REFUSED [$arm]: $*" >&2; exit 1; }
die()    { echo "FAIL: $*" >&2; exit 1; }
void()   { echo "VOID: $*" >&2; exit 2; }
skip()   { echo "DRY-RUN skip: $*" >&2; }

usage() {
  sed -n '/^# USAGE/,/^# EXIT CODES/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
}

# ---------------------------------------------------------------------------
# argument parsing
# ---------------------------------------------------------------------------
DRY_RUN=0
MODE=provision
DECISIONS_FILE="$REPO_ROOT/.planning/phases/29-deployable-staging-with-its-own-monitoring/29-ESTATE-DECISIONS.md"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --readback) MODE=readback ;;
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

# Names and values: the runbook's (D-11, D-07). Constants, never overrides.
RG="jtoye-rg"
MEDIA_ACCT="jtoyestgmedia"
MEDIA_LOCATION="uksouth"
BACKUP_ACCT="jtoyestgbackup"
BACKUP_LOCATION="ukwest"
MEDIA_CONTAINERS="jtoye-images jtoye-quarantine"
BACKUP_CONTAINER="jtoye-db-backups"
SOFT_DELETE_DAYS=14
RETENTION_DAYS=30
LIFECYCLE_DELETE_DAYS=35
MEDIA_IDENTITY="jtoye-staging-media-id"
BACKUP_IDENTITY="jtoye-staging-backup-id"
ROLE_MEDIA="Storage Blob Data Contributor"
ROLE_BACKUP="J'Toye Backup Blob Writer"
PROVIDER="Microsoft.Storage"
PROVIDER_DEADLINE_SECONDS=600

echo "=== J'Toye staging Blob estate (mode=${MODE}, dry-run=${DRY_RUN}) ==="

# ---------------------------------------------------------------------------
# STEP 0 — the decision record. No az call of any kind before this passes.
# ---------------------------------------------------------------------------
echo
echo "=== STEP 0: decision record ==="
[ -f "$DECISIONS_FILE" ] || refuse decisions "no decision record at ${DECISIONS_FILE}. Plan 29-09's checkpoint writes it after the owner approves the estate; these accounts are billable and nothing runs before that."
if ! command grep -qxE 'APPROVED: option-(a|b)[[:space:]]*' "$DECISIONS_FILE"; then
  refuse decisions "${DECISIONS_FILE} carries no 'APPROVED: option-a' or 'APPROVED: option-b' line"
fi
APPROVED_OPTION="$(command grep -xE 'APPROVED: option-(a|b)[[:space:]]*' "$DECISIONS_FILE" | sed -E 's/^APPROVED: (option-[ab]).*$/\1/' | sort -u)"
[ "$(printf '%s\n' "$APPROVED_OPTION" | wc -l)" -eq 1 ] || refuse decisions "${DECISIONS_FILE} approves more than one option"
ok "decision record ${APPROVED_OPTION}"

# ---------------------------------------------------------------------------
# which az: the stub under --dry-run, never the stub otherwise
# ---------------------------------------------------------------------------
AZ_PATH="$(command -v az 2>/dev/null || true)"
AZ_IS_STUB=0
if [ -n "$AZ_PATH" ] && [ -f "$(dirname "$AZ_PATH")/.az-guard-stub" ]; then AZ_IS_STUB=1; fi
if [ "$DRY_RUN" -eq 1 ] && [ "$AZ_IS_STUB" -ne 1 ]; then
  echo "VOID [dry-run]: az on PATH is '${AZ_PATH:-<none>}', not an az_guard_stub (no .az-guard-stub beside it). A dry-run must never reach the real CLI." >&2
  exit 2
fi
if [ "$DRY_RUN" -eq 0 ] && [ "$AZ_IS_STUB" -eq 1 ]; then
  echo "VOID [live]: az on PATH is the az_guard_stub at '${AZ_PATH}'; a live run against the test double would report its canned answers as provisioned state" >&2
  exit 2
fi

# ---------------------------------------------------------------------------
# the subscription guard (sets AZ_GUARD_OK in THIS shell)
# ---------------------------------------------------------------------------
# shellcheck source=scripts/lib/azure-guard.sh
. "$SCRIPT_DIR/lib/azure-guard.sh"
echo
echo "=== STEP 1: subscription guard ==="
GUARD_RC=0
az_guard_init || GUARD_RC=$?
[ "$GUARD_RC" -eq 0 ] || exit "$GUARD_RC"
SUB="$JTOYE_AZURE_SUBSCRIPTION_ID"
ACCT_SCOPE="/subscriptions/${SUB}/resourceGroups/${RG}/providers/Microsoft.Storage/storageAccounts"
container_scope() { printf '%s/%s/blobServices/default/containers/%s\n' "$ACCT_SCOPE" "$1" "$2"; }

# ---------------------------------------------------------------------------
# helpers (every CLI call below is az_mutate or az_read)
# ---------------------------------------------------------------------------
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

# count_is_zero DESC <az list args with --query length(@)…> -> 0 when nothing
# matches (so the create must run). Dry-run: skipped, assumed zero.
count_is_zero() {
  local desc="$1" n
  shift
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "existence count for ${desc}; assumed 0 so the create argv is shown"
    return 0
  fi
  n="$(read_required "count of ${desc}" "$@")"
  [[ $n =~ ^[0-9]+$ ]] || void "count of ${desc} is not a number: '${n}'"
  if [ "$n" -eq 0 ]; then return 0; fi
  ok "${desc} already present (${n}): NOT recreated"
  return 1
}

rows() { printf '%s\n' "$1" | tr '\t' '\n'; }

TMP_DIR="$(umask 077; mktemp -d "${TMPDIR:-/tmp}/jtoye-storage.XXXXXXXX")" || void "mktemp -d failed"
trap 'rm -rf "$TMP_DIR"' EXIT

# ---------------------------------------------------------------------------
# --readback: runbook §8, raw
# ---------------------------------------------------------------------------
if [ "$MODE" = readback ]; then
  READ_FAILS=0
  raw() {
    local label="$1" rc=0 out
    shift
    echo "--- ${label}"
    out="$(az_read "$@" 2>&1)" || rc=$?
    printf '%s\n' "$out"
    if [ "$rc" -ne 0 ]; then
      echo "READ FAILED rc=${rc}: ${label}"
      READ_FAILS=$((READ_FAILS + 1))
    fi
  }
  rstep 8 "read-backs (raw, $(date -u +%Y-%m-%dT%H:%M:%SZ))"
  for a in "$MEDIA_ACCT" "$BACKUP_ACCT"; do
    # A10 (allowBlobPublicAccess: media true, backup false) and A12 (primaryLocation).
    raw "account ${a} (A10 allowBlobPublicAccess, A12 primaryLocation)" storage account show -g "$RG" -n "$a" \
      --query "{name:name,location:primaryLocation,sku:sku.name,allowBlobPublicAccess:allowBlobPublicAccess,allowSharedKeyAccess:allowSharedKeyAccess,minTls:minimumTlsVersion,httpsOnly:enableHttpsTrafficOnly}" -o json
  done
  for c in $MEDIA_CONTAINERS; do
    raw "container ${MEDIA_ACCT}/${c} access level" storage container-rm show -g "$RG" --storage-account "$MEDIA_ACCT" -n "$c" \
      --query "{name:name,publicAccess:publicAccess}" -o json
  done
  raw "container ${BACKUP_ACCT}/${BACKUP_CONTAINER} access level" storage container-rm show -g "$RG" --storage-account "$BACKUP_ACCT" -n "$BACKUP_CONTAINER" \
    --query "{name:name,publicAccess:publicAccess}" -o json
  raw "retention policy on ${BACKUP_CONTAINER} (state, immutabilityPeriodSinceCreationInDays)" storage container immutability-policy show -g "$RG" \
    --account-name "$BACKUP_ACCT" --container-name "$BACKUP_CONTAINER" -o json
  raw "blob soft delete on ${BACKUP_ACCT}" storage account blob-service-properties show -g "$RG" --account-name "$BACKUP_ACCT" \
    --query deleteRetentionPolicy -o json
  raw "lifecycle policy on ${BACKUP_ACCT}" storage account management-policy show -g "$RG" --account-name "$BACKUP_ACCT" -o json
  for c in $MEDIA_CONTAINERS; do
    raw "role assignments at ${MEDIA_ACCT}/${c}" role assignment list --scope "$(container_scope "$MEDIA_ACCT" "$c")" -o table
  done
  raw "role assignments at ${BACKUP_ACCT}/${BACKUP_CONTAINER}" role assignment list --scope "$(container_scope "$BACKUP_ACCT" "$BACKUP_CONTAINER")" -o table
  for id in "$MEDIA_IDENTITY" "$BACKUP_IDENTITY"; do
    pid=""
    pid="$(az_read identity show -g "$RG" -n "$id" --query principalId -o tsv 2>/dev/null)" || true
    if [ -n "$pid" ]; then
      raw "every role assignment held by ${id} (expect container scopes only)" role assignment list --assignee-object-id "$pid" --all -o table
    else
      echo "--- every role assignment held by ${id}"
      echo "READ FAILED: principalId of ${id}"
      READ_FAILS=$((READ_FAILS + 1))
    fi
  done
  echo "--- anonymous LIST of ${MEDIA_ACCT}/jtoye-images (#626: expect NOT 200, no EnumerationResults)"
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "anonymous LIST probe over the network"
  elif command -v curl >/dev/null 2>&1; then
    body="$(curl -s --max-time 20 -w '\nHTTP %{http_code}' "https://${MEDIA_ACCT}.blob.core.windows.net/jtoye-images?restype=container&comp=list" 2>&1)" || true
    tail -n 1 <<<"$body"
    echo "EnumerationResults count: $(command grep -c '<EnumerationResults' <<<"$body" || true)"
  else
    echo "READ FAILED: curl not on PATH"
    READ_FAILS=$((READ_FAILS + 1))
  fi
  echo "(the anonymous GET of a real derivative, runbook §8, needs an image core-java has written: plan 29-14)"
  echo
  echo "readback reads_failed=${READ_FAILS}"
  [ "$READ_FAILS" -eq 0 ] || exit 1
  exit 0
fi

# ---------------------------------------------------------------------------
# §2.1 — the provider, then the name verdict
# ---------------------------------------------------------------------------
rstep 2.1 "Microsoft.Storage registered, then check-name for ${MEDIA_ACCT} and ${BACKUP_ACCT}"
az_mutate --no-echo provider register --namespace "$PROVIDER"
if [ "$DRY_RUN" -eq 1 ]; then
  skip "poll ${PROVIDER} until registrationState=Registered (deadline ${PROVIDER_DEADLINE_SECONDS}s)"
else
  deadline=$((SECONDS + PROVIDER_DEADLINE_SECONDS))
  while :; do
    state="$(read_required "registration state of ${PROVIDER}" provider show --namespace "$PROVIDER" --query registrationState -o tsv)"
    [ "$state" = Registered ] && break
    [ "$SECONDS" -lt "$deadline" ] || void "${PROVIDER} still '${state}' after ${PROVIDER_DEADLINE_SECONDS}s"
    sleep 10
  done
  ok "${PROVIDER} Registered"
fi
for a in "$MEDIA_ACCT" "$BACKUP_ACCT"; do
  # An account this estate already created answers AlreadyExists to check-name,
  # so a re-run reads the account first and asks for a verdict only when absent.
  if [ "$DRY_RUN" -eq 0 ] && exists "storage account ${a}" storage account show -g "$RG" -n "$a" --query id -o tsv; then
    continue
  fi
  verdict="$(read_required "check-name ${a}" storage account check-name --name "$a" --query "[nameAvailable, reason]" -o tsv)"
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "check-name verdict for ${a} (a stub's answer is not a name's availability)"
  else
    verdict="$(rows "$verdict" | tr '\n' ' ')"
    case "$verdict" in
      "true "*) ok "${a}: name available" ;;
      *) refuse name-taken "storage account name ${a} is not available ('${verdict}'): runbook §9: owner decision. Do not create a substitute name" ;;
    esac
  fi
done

# ---------------------------------------------------------------------------
# §2.2 — the two accounts
# ---------------------------------------------------------------------------
rstep 2.2 "create ${MEDIA_ACCT} (${MEDIA_LOCATION}) and ${BACKUP_ACCT} (${BACKUP_LOCATION}), shared-key access disabled"
create_account() {
  local name="$1" location="$2" public="$3" props want
  if want_create "storage account ${name}" storage account show -g "$RG" -n "$name" --query id -o tsv; then
    az_mutate --no-echo storage account create -g "$RG" -n "$name" \
      --location "$location" --kind StorageV2 --sku Standard_LRS \
      --min-tls-version TLS1_2 --https-only true \
      --allow-shared-key-access false \
      --allow-blob-public-access "$public"
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "read back ${name}: primaryLocation ${location}, allowBlobPublicAccess ${public}, allowSharedKeyAccess false"
    return 0
  fi
  props="$(read_required "${name} properties" storage account show -g "$RG" -n "$name" \
    --query "[primaryLocation, allowBlobPublicAccess, allowSharedKeyAccess, minimumTlsVersion, enableHttpsTrafficOnly]" -o tsv)"
  props="$(rows "$props" | tr '\n' ' ' | tr '[:upper:]' '[:lower:]')"
  want="${location} ${public} false tls1_2 true "
  [ "$props" = "$want" ] || die "${name} reads back '${props}', want '${want}'"
  ok "${name}: ${props}"
}
create_account "$MEDIA_ACCT" "$MEDIA_LOCATION" true
create_account "$BACKUP_ACCT" "$BACKUP_LOCATION" false

# ---------------------------------------------------------------------------
# §3 — containers, control plane
# ---------------------------------------------------------------------------
rstep 3 "containers: jtoye-images (blob), jtoye-quarantine (private), ${BACKUP_CONTAINER} (private)"
create_container() {
  local acct="$1" name="$2" access="$3" got want
  if want_create "container ${acct}/${name}" storage container-rm show -g "$RG" --storage-account "$acct" -n "$name" --query name -o tsv; then
    az_mutate --no-echo storage container-rm create -g "$RG" --storage-account "$acct" \
      --name "$name" --public-access "$access"
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "read back ${acct}/${name} publicAccess"
    return 0
  fi
  got="$(read_required "${acct}/${name} publicAccess" storage container-rm show -g "$RG" --storage-account "$acct" -n "$name" --query publicAccess -o tsv)"
  case "${access}:${got}" in
    blob:Blob|off:None) ok "${acct}/${name}: publicAccess ${got}" ;;
    *) die "${acct}/${name} reads back publicAccess '${got}', want '${access}' (jtoye-images at Container would be the #626 regression)" ;;
  esac
}
create_container "$MEDIA_ACCT" jtoye-images blob
create_container "$MEDIA_ACCT" jtoye-quarantine off
create_container "$BACKUP_ACCT" "$BACKUP_CONTAINER" off

# ---------------------------------------------------------------------------
# §4 — backup data protection, in the runbook's order
# ---------------------------------------------------------------------------
rstep 4 "backup data protection: soft delete ${SOFT_DELETE_DAYS} d, retention ${RETENTION_DAYS} d UNLOCKED, lifecycle delete after ${LIFECYCLE_DELETE_DAYS} d"
# 4.1 soft delete first (Microsoft's order: before immutability).
az_mutate --no-echo storage account blob-service-properties update -g "$RG" \
  --account-name "$BACKUP_ACCT" --enable-delete-retention true --delete-retention-days "$SOFT_DELETE_DAYS"
if [ "$DRY_RUN" -eq 1 ]; then
  skip "read back deleteRetentionPolicy enabled true, days ${SOFT_DELETE_DAYS}"
else
  sd="$(read_required "soft delete on ${BACKUP_ACCT}" storage account blob-service-properties show -g "$RG" --account-name "$BACKUP_ACCT" \
    --query "[deleteRetentionPolicy.enabled, deleteRetentionPolicy.days]" -o tsv)"
  sd="$(rows "$sd" | tr '\n' ' ' | tr '[:upper:]' '[:lower:]')"
  [ "$sd" = "true ${SOFT_DELETE_DAYS} " ] || die "soft delete on ${BACKUP_ACCT} reads back '${sd}', want 'true ${SOFT_DELETE_DAYS}'"
  ok "soft delete: ${sd}"
fi

# 4.2 container-level time-based retention, created and LEFT UNLOCKED. A new
# policy starts unlocked; this script never changes that state.
policy_state=""
if [ "$DRY_RUN" -eq 1 ]; then
  skip "existing retention policy state on ${BACKUP_CONTAINER}; assumed none so the create argv is shown"
else
  policy_state="$(az_read storage container immutability-policy show -g "$RG" --account-name "$BACKUP_ACCT" \
    --container-name "$BACKUP_CONTAINER" --query state -o tsv 2>/dev/null)" || policy_state=""
fi
case "$policy_state" in
  "")
    az_mutate --no-echo storage container immutability-policy create -g "$RG" \
      --account-name "$BACKUP_ACCT" --container-name "$BACKUP_CONTAINER" \
      --period "$RETENTION_DAYS" --allow-protected-append-writes false
    ;;
  Unlocked) ok "retention policy on ${BACKUP_CONTAINER} already exists, state Unlocked: NOT recreated" ;;
  Locked)   ok "retention policy on ${BACKUP_CONTAINER} is already Locked (the runbook §4.4 human step has run): left as is" ;;
  *) die "retention policy on ${BACKUP_CONTAINER} reads back state '${policy_state}'" ;;
esac
if [ "$DRY_RUN" -eq 1 ]; then
  skip "read back the retention policy: immutabilityPeriodSinceCreationInDays ${RETENTION_DAYS}, state Unlocked"
else
  pol="$(read_required "retention policy on ${BACKUP_CONTAINER}" storage container immutability-policy show -g "$RG" --account-name "$BACKUP_ACCT" \
    --container-name "$BACKUP_CONTAINER" --query "[immutabilityPeriodSinceCreationInDays, state]" -o tsv)"
  pol="$(rows "$pol" | tr '\n' ' ')"
  case "$pol" in
    "${RETENTION_DAYS} Unlocked "|"${RETENTION_DAYS} Locked ") ok "retention policy: ${pol}" ;;
    *) die "retention policy on ${BACKUP_CONTAINER} reads back '${pol}', want '${RETENTION_DAYS} Unlocked'" ;;
  esac
fi
echo "WORM retention left UNLOCKED: the lock is a human step after the plan 29-20 drill (runbook §4.4)"

# 4.3 lifecycle: delete dumps after 35 days (past the 30-day retention).
LIFECYCLE_JSON="$TMP_DIR/jtoye-backup-lifecycle.json"
cat > "$LIFECYCLE_JSON" <<EOF
{
  "rules": [
    {
      "enabled": true,
      "name": "delete-db-backups-after-${LIFECYCLE_DELETE_DAYS}d",
      "type": "Lifecycle",
      "definition": {
        "filters": { "blobTypes": ["blockBlob"], "prefixMatch": ["${BACKUP_CONTAINER}/backups/"] },
        "actions": { "baseBlob": { "delete": { "daysAfterModificationGreaterThan": ${LIFECYCLE_DELETE_DAYS} } } }
      }
    }
  ]
}
EOF
az_mutate --no-echo storage account management-policy create -g "$RG" \
  --account-name "$BACKUP_ACCT" --policy "@${LIFECYCLE_JSON}"
if [ "$DRY_RUN" -eq 1 ]; then
  skip "read back the lifecycle rule: prefix ${BACKUP_CONTAINER}/backups/, delete after ${LIFECYCLE_DELETE_DAYS} days"
else
  lc="$(read_required "lifecycle policy on ${BACKUP_ACCT}" storage account management-policy show -g "$RG" --account-name "$BACKUP_ACCT" \
    --query "[length(policy.rules), policy.rules[0].definition.filters.prefixMatch[0], policy.rules[0].definition.actions.baseBlob.delete.daysAfterModificationGreaterThan]" -o tsv)"
  lc="$(rows "$lc" | tr '\n' ' ')"
  case "$lc" in
    "1 ${BACKUP_CONTAINER}/backups/ ${LIFECYCLE_DELETE_DAYS} "|"1 ${BACKUP_CONTAINER}/backups/ ${LIFECYCLE_DELETE_DAYS}.0 ") ok "lifecycle: ${lc}" ;;
    *) die "lifecycle policy on ${BACKUP_ACCT} reads back '${lc}', want one rule on ${BACKUP_CONTAINER}/backups/ deleting after ${LIFECYCLE_DELETE_DAYS} days" ;;
  esac
fi

# ---------------------------------------------------------------------------
# §5 — read only: the workload identities (created by azure-staging-provision.sh)
# ---------------------------------------------------------------------------
rstep 5 "workload identities ${MEDIA_IDENTITY} and ${BACKUP_IDENTITY} (read only; plan 29-01 creates them)"
MEDIA_PID="$(read_required "${MEDIA_IDENTITY} principalId" identity show -g "$RG" -n "$MEDIA_IDENTITY" --query principalId -o tsv)"
BACKUP_PID="$(read_required "${BACKUP_IDENTITY} principalId" identity show -g "$RG" -n "$BACKUP_IDENTITY" --query principalId -o tsv)"
ok "principal ids read (identifiers, not secrets): ${MEDIA_IDENTITY}=${MEDIA_PID} ${BACKUP_IDENTITY}=${BACKUP_PID}"

# ---------------------------------------------------------------------------
# §6 — RBAC, container scope only
# ---------------------------------------------------------------------------
rstep 6 "container-scope RBAC: ${ROLE_MEDIA} for ${MEDIA_IDENTITY}; ${ROLE_BACKUP} for ${BACKUP_IDENTITY}"
# assign PID ROLE SCOPE — idempotent; a fresh custom role can take a while to
# become assignable, so the live create retries on a deadline.
assign() {
  local pid="$1" role="$2" scope="$3" tries=0 rc
  if count_is_zero "${role} for ${pid} at ${scope##*/storageAccounts/}" \
      role assignment list --assignee-object-id "$pid" --role "$role" --scope "$scope" --query "length(@)" -o tsv; then
    while :; do
      rc=0
      az_mutate --no-echo role assignment create --assignee-object-id "$pid" --assignee-principal-type ServicePrincipal \
        --role "$role" --scope "$scope" || rc=$?
      [ "$rc" -eq 0 ] && break
      tries=$((tries + 1))
      [ "$DRY_RUN" -eq 0 ] && [ "$tries" -lt 10 ] || die "role assignment '${role}' at ${scope} failed (az rc=${rc})"
      echo "role assignment not accepted yet (attempt ${tries}/10, rc=${rc}); retrying in 15 s" >&2
      sleep 15
    done
  fi
  ok "${role} -> ${pid} at ${scope##*/storageAccounts/}"
}
for c in $MEDIA_CONTAINERS; do
  assign "$MEDIA_PID" "$ROLE_MEDIA" "$(container_scope "$MEDIA_ACCT" "$c")"
done

ROLE_JSON="$TMP_DIR/jtoye-backup-writer-role.json"
cat > "$ROLE_JSON" <<EOF
{
  "Name": "${ROLE_BACKUP}",
  "IsCustom": true,
  "Description": "Create new blobs in ${BACKUP_CONTAINER} only. No read, no list, no delete.",
  "Actions": [],
  "NotActions": [],
  "DataActions": [
    "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/write",
    "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/add/action"
  ],
  "NotDataActions": [],
  "AssignableScopes": ["/subscriptions/${SUB}/resourceGroups/${RG}"]
}
EOF
if count_is_zero "custom role ${ROLE_BACKUP}" role definition list --custom-role-only true --name "$ROLE_BACKUP" --query "length(@)" -o tsv; then
  az_mutate --no-echo role definition create --role-definition "@${ROLE_JSON}"
fi
assign "$BACKUP_PID" "$ROLE_BACKUP" "$(container_scope "$BACKUP_ACCT" "$BACKUP_CONTAINER")"

echo
echo "DONE: Blob estate (dry-run=${DRY_RUN}). Record the runbook §8 read-backs next: $0 --readback"
