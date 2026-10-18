#!/usr/bin/env bash
# azure-staging-datastores.sh — the managed PostgreSQL the staging slice needs
# (Phase 29 / DPLY-01, DPLY-04; decisions D-10, D-25; threats T-29-21, T-29-22).
#
# Adapted from STEP 6 of the August script on `phase-29-research` (read with git
# show, never checked out). Carried: the create shape, `--output none` on the
# create (its JSON result carries the admin password and a connection string
# with it embedded; measured in August, the password reached a run log twice),
# the azure.extensions allowlist BEFORE any Flyway run, the two databases, the
# single firewall rule. Changed: PostgreSQL 16 -> 18 (D-25), the password moves
# by FILE REFERENCE only, the decision file gates the run, every call goes
# through scripts/lib/azure-guard.sh. Dropped: Azure Managed Redis (its STEP 7;
# D-24 resolved to an in-cluster pod).
#
# ---------------------------------------------------------------------------
# ORDER (fixed; each step precedes everything after it)
#   1. argument parsing
#   2. STEP 0, decisions   refuse ("REFUSED [decisions]", rc 1, zero az calls)
#                          unless the decision record carries `APPROVED: option-a`
#                          or `APPROVED: option-b` (plan 29-09's checkpoint),
#                          PG_ADMIN_LOGIN=<login> and PG_PUBLIC_ACCESS=public
#   3. dry-run stub check  --dry-run refuses (rc 2) unless az on PATH is the
#                          az_guard_stub; a live run refuses the stub
#   4. az_guard_init       the subscription equality + read-back
#   5. the server:
#        two existence probes, BEFORE anything is written:
#          the Key Vault secret pg-admin--password (`--query id`: existence only)
#          the server jtoye-stg-pg
#        (a) neither  -> generate the password into a mode-600 file, write it to
#                        Key Vault from that file, create the server from the same file
#        (b) secret only -> RESUME: an earlier run wrote the secret and its create
#                        failed. Read the stored value into the mode-600 file and
#                        create from it. No second secret version is ever written
#        (c) both     -> skip the create; run the idempotent steps below
#        (d) server only -> "REFUSED [pg-admin-password]" rc 1: the vault does not
#                        know the server's admin password. Resetting it is an
#                        owner decision, done by hand (the refusal names the command)
#   6. firewall          exactly ONE rule, start = end = the cluster outbound IP
#                        (jtoye-stg-outbound-ip, plan 29-01), read back
#   7. azure.extensions  = UUID-OSSP, read back. V1__base_schema.sql runs
#                        CREATE EXTENSION "uuid-ossp"; on a managed server that
#                        fails unless the extension is allowlisted first
#   8. databases         jtoye and keycloak (D-02: one server, two databases)
#   9. read-backs        version 18, state Ready, the admin login, max_connections
#
# THE ADMIN PASSWORD NEVER CROSSES ARGV (T-29-22)
#   It lives only in a mode-600 file inside a mode-700 temp dir that an EXIT trap
#   removes. It reaches az only as a file reference:
#     keyvault secret set ... --file FILE        (never --value)
#     flexible-server create ... --admin-password @FILE
#   The `@FILE` form is expanded by the Azure CLI itself before argument parsing:
#   azure-cli 2.91.0, azure/cli/core/commands/__init__.py, _expand_file_prefixed_files
#   (line 77; under /opt/az/lib/python3.14/site-packages on this host). It strips
#   a trailing newline (content.rstrip(os.linesep), line 84) and, on OSError,
#   FALLS BACK TO THE LITERAL ARGUMENT (lines 97-98) — a missing file would create
#   the server with the password "@/tmp/…". So the file is asserted present and
#   non-empty immediately before the create. The live proof that the server holds
#   the vault's value is plan 29-09's first `staging-db-bootstrap.sh --stage pre`
#   login.
#
# DRY RUN
#   Every call reaches the az_guard_stub. The two existence probes STILL RUN
#   against it, so a stub answer file selects branch (a), (b), (c) or (d). Every
#   other existence probe is skipped and the resource assumed absent, and every
#   read-back assertion is skipped with a `DRY-RUN skip:` line on stderr (a stub's
#   answer is not state). The live run (plan 29-09) performs every assertion.
#
# USAGE
#   JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94 \
#     scripts/azure-staging-datastores.sh [--dry-run] [--decisions PATH]
#     --dry-run         every call goes to the az_guard_stub; mutate nothing
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
skip()   { echo "DRY-RUN skip: $*" >&2; }

usage() {
  sed -n '/^# USAGE/,/^# EXIT CODES/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'
}

# ---------------------------------------------------------------------------
# 1. argument parsing
# ---------------------------------------------------------------------------
DRY_RUN=0
DECISIONS_FILE="$REPO_ROOT/.planning/phases/29-deployable-staging-with-its-own-monitoring/29-ESTATE-DECISIONS.md"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
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

# The naming contract (29-01 interfaces block) and the D-25 server shape.
RG="jtoye-rg"
LOCATION="uksouth"
PG_NAME="jtoye-stg-pg"
PG_VERSION="18"
PG_TIER="Burstable"
# Standard_B2s: max_connections 429, the k8s-staging row of
# k8s/scripts/connection-budget.conf (plan 29-05). A different SKU changes that
# number, so the two move together or the connection-math gate lies.
PG_SKU="Standard_B2s"
PG_STORAGE_GB="32"
PG_BACKUP_RETENTION_DAYS="7"
KV_NAME="jtoye-stg-kv"
KV_SECRET="pg-admin--password"
OUTBOUND_IP_NAME="jtoye-stg-outbound-ip"
FIREWALL_RULE_NAME="aks-jtoye-stg-outbound"
DATABASES="jtoye keycloak"
# The one extension the migration chain creates (scripts/check-no-create-extension.sh
# exempts exactly this one). Re-derived from the migrations below, and a mismatch
# refuses: an allowlist that silently lags a new migration fails the first boot.
PG_EXTENSIONS="UUID-OSSP"
MIGRATIONS_DIR="$REPO_ROOT/core-java/src/main/resources/db/migration"
BUDGET_CONF="$REPO_ROOT/k8s/scripts/connection-budget.conf"

echo "=== J'Toye staging estate: managed PostgreSQL ${PG_NAME} (dry-run=${DRY_RUN}) ==="

# ---------------------------------------------------------------------------
# 2. STEP 0 — the decision record. No az call of any kind before this passes.
# ---------------------------------------------------------------------------
step "STEP 0: decision record"
[ -f "$DECISIONS_FILE" ] || refuse decisions "no decision record at ${DECISIONS_FILE}. Plan 29-09's checkpoint writes it after the owner approves the one-way parameter set; nothing runs before that."
if ! command grep -qxE 'APPROVED: option-(a|b)[[:space:]]*' "$DECISIONS_FILE"; then
  refuse decisions "${DECISIONS_FILE} carries no 'APPROVED: option-a' or 'APPROVED: option-b' line; the server's one-way create parameters are not approved"
fi
APPROVED_OPTION="$(command grep -xE 'APPROVED: option-(a|b)[[:space:]]*' "$DECISIONS_FILE" | sed -E 's/^APPROVED: (option-[ab]).*$/\1/' | sort -u)"
[ "$(printf '%s\n' "$APPROVED_OPTION" | wc -l)" -eq 1 ] || refuse decisions "${DECISIONS_FILE} approves more than one option"

decision_key() {
  local key="$1" vals
  vals="$(command grep -E "^${key}=" "$DECISIONS_FILE" | sed -E "s/^${key}=//; s/[[:space:]]+\$//" | sort -u)" || true
  [ -n "$vals" ] || refuse decisions "${DECISIONS_FILE} does not define ${key} (a missing or empty key is not a value this script may guess)"
  [ "$(printf '%s\n' "$vals" | wc -l)" -eq 1 ] || refuse decisions "${DECISIONS_FILE} defines ${key} more than once with different values"
  printf '%s\n' "$vals"
}
PG_ADMIN_LOGIN="$(decision_key PG_ADMIN_LOGIN)"
PG_PUBLIC_ACCESS="$(decision_key PG_PUBLIC_ACCESS)"
[ "$PG_PUBLIC_ACCESS" = public ] || refuse decisions "PG_PUBLIC_ACCESS is '${PG_PUBLIC_ACCESS}'; this script implements only 'public' (one firewall rule = the cluster outbound IP). Private access is a different, one-way network design"
# Azure's admin-login rules: letters, digits and underscore; not pg_-prefixed;
# not a reserved name. Checked here so a typo refuses before anything is written.
[[ $PG_ADMIN_LOGIN =~ ^[a-z][a-z0-9_]{0,62}$ ]] || refuse decisions "PG_ADMIN_LOGIN '${PG_ADMIN_LOGIN}' must be 1-63 lower-case letters, digits or underscores, starting with a letter"
case "$PG_ADMIN_LOGIN" in
  pg_*|azure_superuser|azure_pg_admin|admin|administrator|root|guest|public)
    refuse decisions "PG_ADMIN_LOGIN '${PG_ADMIN_LOGIN}' is reserved by Azure Database for PostgreSQL" ;;
esac
ok "decision record ${APPROVED_OPTION}: PG_ADMIN_LOGIN=${PG_ADMIN_LOGIN} PG_PUBLIC_ACCESS=${PG_PUBLIC_ACCESS}"

# The allowlist against the migrations (a local read; still no az call).
[ -d "$MIGRATIONS_DIR" ] || void "no migration directory at ${MIGRATIONS_DIR}; the extension allowlist has no source"
derived="$(command grep -rhoE '^[[:space:]]*CREATE EXTENSION IF NOT EXISTS "[^"]+"' "$MIGRATIONS_DIR" | sed -E 's/.*"([^"]+)".*/\1/' | tr '[:lower:]' '[:upper:]' | sort -u | paste -sd, -)" || true
[ -n "$derived" ] || void "found no CREATE EXTENSION in ${MIGRATIONS_DIR}; the pattern broke (V1 creates uuid-ossp), refusing to allowlist nothing"
[ "$derived" = "$PG_EXTENSIONS" ] || refuse extensions "the migrations create extension(s) '${derived}' but this script allowlists '${PG_EXTENSIONS}'; update PG_EXTENSIONS (and confirm Azure supports each on PostgreSQL ${PG_VERSION})"
ok "azure.extensions source: ${derived} (from ${MIGRATIONS_DIR#"$REPO_ROOT"/})"

# ---------------------------------------------------------------------------
# 3. which az: the stub under --dry-run, never the stub otherwise
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

read_required() {
  local desc="$1" out rc=0
  shift
  out="$(az_read "$@")" || rc=$?
  [ "$rc" -eq 0 ] || die "could not read ${desc} (az rc=${rc})"
  [ -n "$out" ] || die "read of ${desc} returned nothing; an empty value is not one"
  printf '%s\n' "$out"
}

# probe DESC <az show args…> -> 0 present, 1 absent (az rc 3). Runs in dry-run
# too: the two existence probes that choose the branch read the stub's answers.
probe() {
  local desc="$1" rc=0
  shift
  az_read "$@" >/dev/null 2>&1 || rc=$?
  case "$rc" in
    0) return 0 ;;
    3) return 1 ;;
    *) void "existence probe for ${desc} failed with az rc=${rc} (only 3 means not found)" ;;
  esac
}

# want_create DESC <az show args…> -> 0 when the create must run. Dry-run:
# skipped, assumed absent so the create argv is shown.
want_create() {
  local desc="$1"
  shift
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "existence probe for ${desc}; assumed ABSENT so the create argv is shown"
    return 0
  fi
  if probe "$desc" "$@"; then ok "${desc} already exists: read back, NOT recreated"; return 1; fi
  return 0
}

rows() { printf '%s\n' "$1" | tr '\t' '\n'; }

# The password's only home: a mode-600 file in a mode-700 directory, gone on exit.
umask 077
PW_DIR="$(mktemp -d "${TMPDIR:-/tmp}/jtoye-pgadmin.XXXXXXXX")" || void "mktemp -d failed"
trap 'rm -rf "$PW_DIR"' EXIT
chmod 700 "$PW_DIR"
PW_FILE="$PW_DIR/pg-admin-password"

pw_file_ready() {
  [ -f "$PW_FILE" ] && [ -s "$PW_FILE" ] || die "the admin password file is missing or empty; the CLI would pass '@${PW_FILE}' through as the literal password"
  [ "$(stat -c '%a' "$PW_FILE")" = 600 ] || die "the admin password file is not mode 600"
}

# 32 characters of [A-Za-z0-9], with at least one upper, one lower and one digit
# (Azure demands three of four character classes). Written straight into the file
# by the pipeline: the value is never in a variable, never in argv.
generate_password() {
  local i
  for i in 1 2 3 4 5; do
    openssl rand -base64 36 | tr -d '/+=\n' | cut -c1-32 | tr -d '\n' > "$PW_FILE"
    if [ "$(wc -c < "$PW_FILE")" -eq 32 ] \
      && command grep -q '[A-Z]' "$PW_FILE" \
      && command grep -q '[a-z]' "$PW_FILE" \
      && command grep -q '[0-9]' "$PW_FILE"; then
      return 0
    fi
  done
  die "could not generate a password meeting Azure's complexity rule in 5 attempts"
}

# ---------------------------------------------------------------------------
# 5. the server
# ---------------------------------------------------------------------------
step "STEP 2: existence probes (Key Vault secret ${KV_SECRET}, server ${PG_NAME})"
command -v openssl >/dev/null 2>&1 || void "openssl not found (it generates the admin password)"
SECRET_EXISTS=0; SERVER_EXISTS=0
if probe "Key Vault secret ${KV_SECRET}" keyvault secret show --vault-name "$KV_NAME" -n "$KV_SECRET" --query id -o tsv; then SECRET_EXISTS=1; fi
if probe "Flexible Server ${PG_NAME}" postgres flexible-server show -g "$RG" -n "$PG_NAME" --query id -o tsv; then SERVER_EXISTS=1; fi
echo "secret_exists=${SECRET_EXISTS} server_exists=${SERVER_EXISTS}"

if [ "$SERVER_EXISTS" -eq 1 ] && [ "$SECRET_EXISTS" -eq 0 ]; then
  refuse pg-admin-password "server ${PG_NAME} exists but Key Vault ${KV_NAME} holds no ${KV_SECRET}: the server's admin password is unknown to the vault. Resetting it is an owner decision. By hand, with a new value in a mode-600 file: az postgres flexible-server update -g ${RG} -n ${PG_NAME} --admin-password @FILE --subscription <id>, then keyvault secret set --file FILE. This script never does it."
fi

OUTBOUND_IP="$(read_required "outbound IP ${OUTBOUND_IP_NAME} address" network public-ip show -g "$RG" -n "$OUTBOUND_IP_NAME" --query ipAddress -o tsv)"
if [ "$DRY_RUN" -eq 1 ]; then
  skip "assert the outbound IP read parses as IPv4 (stub answered '${OUTBOUND_IP}')"
else
  [[ $OUTBOUND_IP =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || die "outbound IP ${OUTBOUND_IP_NAME} read back as '${OUTBOUND_IP}', not an IPv4 address"
fi

if [ "$SERVER_EXISTS" -eq 0 ]; then
  if [ "$SECRET_EXISTS" -eq 0 ]; then
    step "STEP 3a: fresh estate: generate the admin password, write it to Key Vault, create ${PG_NAME}"
    generate_password
    pw_file_ready
    az_mutate --no-echo keyvault secret set --vault-name "$KV_NAME" -n "$KV_SECRET" --file "$PW_FILE"
    ok "admin password written to ${KV_NAME}/${KV_SECRET} (write-once; value not printed)"
  else
    step "STEP 3b: RESUME: ${KV_SECRET} exists and ${PG_NAME} does not; create from the stored value"
    az_read keyvault secret show --vault-name "$KV_NAME" -n "$KV_SECRET" --query value -o tsv > "$PW_FILE.raw" \
      || die "could not read the stored ${KV_SECRET} back from ${KV_NAME}"
    tr -d '\r\n' < "$PW_FILE.raw" > "$PW_FILE"
    rm -f "$PW_FILE.raw"
    pw_file_ready
    ok "stored admin password reused (no second secret version written)"
  fi
  pw_file_ready
  # --public-access <one IPv4>: public access mode plus exactly one firewall rule
  # for that address (CLI help, 2.91.0). NOT `None`: measured in August, `None`
  # produced publicNetworkAccess Disabled, and every firewall call then failed.
  # --zonal-resiliency Disabled: the 2.91.0 spelling of "no high availability";
  # `--high-availability` is not an argument of this CLI version.
  az_mutate --no-echo postgres flexible-server create -g "$RG" -n "$PG_NAME" -l "$LOCATION" \
    --version "$PG_VERSION" --tier "$PG_TIER" --sku-name "$PG_SKU" \
    --storage-size "$PG_STORAGE_GB" --backup-retention "$PG_BACKUP_RETENTION_DAYS" \
    --zonal-resiliency Disabled \
    --admin-user "$PG_ADMIN_LOGIN" --admin-password "@$PW_FILE" \
    --public-access "$OUTBOUND_IP" --yes
  ok "server create issued (output suppressed: it carries the password)"
else
  step "STEP 3c: ${PG_NAME} and ${KV_SECRET} both exist: no create; idempotent steps only"
fi
rm -f "$PW_FILE"

step "STEP 4: read back ${PG_NAME}"
if [ "$DRY_RUN" -eq 1 ]; then
  skip "read back version ${PG_VERSION}, state Ready, administratorLogin ${PG_ADMIN_LOGIN}, publicNetworkAccess Enabled"
else
  props="$(read_required "${PG_NAME} properties" postgres flexible-server show -g "$RG" -n "$PG_NAME" \
    --query "[version, state, administratorLogin, network.publicNetworkAccess, sku.name]" -o tsv)"
  props="$(rows "$props" | tr '\n' ' ')"
  [ "$props" = "${PG_VERSION} Ready ${PG_ADMIN_LOGIN} Enabled ${PG_SKU} " ] \
    || die "${PG_NAME} reads back '${props}', want '${PG_VERSION} Ready ${PG_ADMIN_LOGIN} Enabled ${PG_SKU}'"
  ok "${PG_NAME}: ${props}"
  evidence "server ${PG_NAME} : ${props}"
fi

# ---------------------------------------------------------------------------
# 6. firewall: exactly one rule, the cluster outbound IP
# ---------------------------------------------------------------------------
step "STEP 5: firewall (exactly one rule = ${OUTBOUND_IP_NAME})"
if [ "$DRY_RUN" -eq 1 ]; then
  skip "read back the firewall rule list: exactly one rule, start = end = the outbound IP"
else
  rules="$(az_read postgres flexible-server firewall-rule list -g "$RG" -s "$PG_NAME" \
    --query "[].[name, startIpAddress, endIpAddress]" -o tsv)" || die "could not list firewall rules on ${PG_NAME}"
  if [ -z "$rules" ]; then
    # A server from an interrupted create can lack the rule the create makes.
    az_mutate --no-echo postgres flexible-server firewall-rule create -g "$RG" -s "$PG_NAME" \
      -n "$FIREWALL_RULE_NAME" --start-ip-address "$OUTBOUND_IP" --end-ip-address "$OUTBOUND_IP"
    rules="$(az_read postgres flexible-server firewall-rule list -g "$RG" -s "$PG_NAME" \
      --query "[].[name, startIpAddress, endIpAddress]" -o tsv)" || die "could not list firewall rules on ${PG_NAME}"
  fi
  n_rules="$(printf '%s\n' "$rules" | command grep -c .)" || true
  ip_pair="$(printf '%s\n' "$rules" | awk -F'\t' 'NF==3 {print $2" "$3}')"
  if [ "$n_rules" -ne 1 ] || [ "$ip_pair" != "${OUTBOUND_IP} ${OUTBOUND_IP}" ]; then
    die "${PG_NAME} has ${n_rules} firewall rule(s) ('$(printf '%s' "$rules" | tr '\n\t' '| ')'); want exactly one with start = end = ${OUTBOUND_IP}. Remove any extra rule by hand after checking who added it"
  fi
  ok "firewall: one rule, ${OUTBOUND_IP} - ${OUTBOUND_IP}"
  evidence "firewall ${PG_NAME} : ${rules//$'\t'/ }"
fi

# ---------------------------------------------------------------------------
# 7. azure.extensions BEFORE anything can run Flyway
# ---------------------------------------------------------------------------
step "STEP 6: azure.extensions = ${PG_EXTENSIONS}"
az_mutate --no-echo postgres flexible-server parameter set -g "$RG" -s "$PG_NAME" \
  --name azure.extensions --value "$PG_EXTENSIONS"
if [ "$DRY_RUN" -eq 1 ]; then
  skip "read back azure.extensions = ${PG_EXTENSIONS}"
else
  ext="$(read_required "azure.extensions on ${PG_NAME}" postgres flexible-server parameter show -g "$RG" -s "$PG_NAME" \
    --name azure.extensions --query value -o tsv)"
  [ "$(printf '%s' "$ext" | tr '[:lower:]' '[:upper:]')" = "$PG_EXTENSIONS" ] \
    || die "azure.extensions reads back '${ext}', want '${PG_EXTENSIONS}'"
  ok "azure.extensions = ${ext}"
  evidence "azure.extensions : ${ext}"
fi

# ---------------------------------------------------------------------------
# 8. databases
# ---------------------------------------------------------------------------
step "STEP 7: databases (${DATABASES})"
for db in $DATABASES; do
  if want_create "database ${db}" postgres flexible-server db show -g "$RG" -s "$PG_NAME" -n "$db" --query name -o tsv; then
    az_mutate --no-echo postgres flexible-server db create -g "$RG" -s "$PG_NAME" -n "$db"
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    skip "read back database ${db}"
  else
    got="$(read_required "database ${db}" postgres flexible-server db show -g "$RG" -s "$PG_NAME" -n "$db" --query name -o tsv)"
    [ "$got" = "$db" ] || die "database ${db} reads back as '${got}'"
    ok "database ${db} present"
    evidence "database ${db} : present"
  fi
done

# ---------------------------------------------------------------------------
# 9. max_connections: printed, not asserted (plan 29-14 measures SHOW
#    max_connections on the live server and updates the budget row)
# ---------------------------------------------------------------------------
step "STEP 8: max_connections"
budget="$(awk '$1=="k8s-staging"{print $2}' "$BUDGET_CONF" 2>/dev/null || true)"
if [ "$DRY_RUN" -eq 1 ]; then
  skip "read max_connections on ${PG_NAME} (budget row k8s-staging says ${budget:-<unreadable>})"
else
  maxc="$(read_required "max_connections on ${PG_NAME}" postgres flexible-server parameter show -g "$RG" -s "$PG_NAME" \
    --name max_connections --query value -o tsv)"
  echo "max_connections=${maxc} (connection-budget.conf k8s-staging=${budget:-<unreadable>})"
  if [ "$maxc" != "$budget" ]; then
    echo "WARN: max_connections ${maxc} differs from the budget row ${budget:-<unreadable>}: update k8s/scripts/connection-budget.conf in the same change (plan 29-14)" >&2
  fi
  evidence "max_connections : ${maxc}"
  fqdn="$(read_required "${PG_NAME} FQDN" postgres flexible-server show -g "$RG" -n "$PG_NAME" --query fullyQualifiedDomainName -o tsv)"
  evidence "fqdn : ${fqdn}"
fi

step "STEP 9: evidence (identifiers only, never a value)"
if [ "$DRY_RUN" -eq 1 ]; then
  echo "dry-run: no evidence recorded (a stub's answers are not state)"
else
  for e in "${EVIDENCE[@]}"; do echo "  ${e}"; done
fi
echo "DONE: ${PG_NAME} (dry-run=${DRY_RUN})"
