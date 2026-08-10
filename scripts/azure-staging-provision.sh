#!/usr/bin/env bash
# azure-staging-provision.sh — the WHOLE staging estate, from one idempotent,
# guard-first script (Phase 29 / DPLY-01, DPLY-04; decisions D-01, D-04, D-07,
# D-09).
#
# ---------------------------------------------------------------------------
# THE MEASURED DEFECT THIS PREVENTS
#
#   Run `az account show` on this host, right now:
#
#       $ az account show --query "{id:id,name:name}" -o tsv
#       8d1c4578-****-****-****-************   Prod - HS2 Ltd
#
#   The AMBIENT DEFAULT SUBSCRIPTION ON THIS MACHINE IS THE EMPLOYER'S.
#   (Measured 2026-08-10. The id is deliberately not written out here — it is
#   third-party infrastructure, and the equality check below refuses every
#   non-owner subscription without needing to name it.)
#
#   So an unqualified `az aks create -g jtoye-rg …` on this host does not create
#   anything in the owner's subscription. It fails, or worse, it succeeds
#   somewhere it must never touch. Every mutation below is therefore pinned with
#   an explicit `--subscription`, and STEP 1 refuses to proceed unless the pinned
#   value equals the id recorded in 29-OPERATOR-DECISIONS.md.
#
#   A wrong-subscription mutation is not recoverable by re-running with the right
#   one. That asymmetry is why the guard is first and why it refuses rather than
#   corrects.
#
# ---------------------------------------------------------------------------
# ORDER (and why the order is load-bearing)
#
#   0. flags          — parsed FIRST, so a typo can never fall through into a
#                       mutating step (the k8s-local-up.sh house shape)
#   1. subscription   — assert the pinned subscription IS the owner's, and that
#      guard           it is reachable and reports the id we asked for. A typo'd
#                       id that silently resolves to the default is the failure
#                       mode this catches
#   2. decisions      — read every SKU, count, ceiling and disposition out of
#                       29-OPERATOR-DECISIONS.md. Refuse, BY NAME, if any key is
#                       missing. Nothing environment-varying is a literal here
#                       (GLOBAL_RULE_6 / ARCHITECTURE_RULE_8)
#   3. providers      — Microsoft.ContainerService / .Network / .Cache were all
#                       MEASURED NotRegistered on this subscription 2026-08-10.
#                       Registration is idempotent, free, and takes minutes, so
#                       it precedes everything that needs it
#   4. AKS            — BEFORE the database, because step 6's firewall rule must
#                       be scoped to the cluster's EGRESS IP, which does not
#                       exist until the cluster does. Provisioning the server
#                       first forces either a wide-open rule (the shape
#                       `snackpass-pg` has, explicitly rejected by 29-10
#                       T-29-10-01) or a second pass
#   5. static IP      — in the cluster's NODE resource group, because a
#                       LoadBalancer Service can only bind an IP that AKS's own
#                       identity can read (D-07)
#   6. PostgreSQL     — version comes from the record and is a REQUIREMENT, not a
#                       preference (Blocker C: BYPASSRLS cannot be granted to a
#                       non-admin role on Flexible Server at PG15 or earlier, and
#                       without `jtoye_backup` the logical dump captures ZERO rows
#                       from every FORCE-RLS table). The azure.extensions
#                       allowlist is set BEFORE anything can run Flyway, because
#                       V1__base_schema.sql:6 runs
#                       `CREATE EXTENSION IF NOT EXISTS "uuid-ossp"` and V1
#                       failing means nothing else runs at all
#   7. Azure Cache    — no ordering dependency; last of the datastores
#   8. identity       — user-assigned identity + GitHub federated credential.
#                       Deliberately AFTER the cluster so the evidence block can
#                       print the OIDC issuer alongside it (D-04, #99)
#   9. evidence       — the facts later plans need and CANNOT infer
#
# WHAT THIS SCRIPT DELIBERATELY DOES NOT DO
#   * No DNS records. Those are manual at Netlify (D-07) and belong to plan 29-10.
#   * No snackpass disposition. The record says `scale-to-zero`; plan 29-10 owns
#     EXECUTING it ("Execute the snackpass disposition recorded in
#     29-OPERATOR-DECISIONS.md FIRST"). `--delete-snackpass` exists only so the
#     delete path can never be reached by momentum — see STEP 2b.
#   * No Kubernetes objects. `scripts/staging-bootstrap.sh` installs the platform
#     components; `kubectl apply -k k8s/staging` deploys the app.
#   * No secrets. `scripts/staging-secrets.sh` owns those, out-of-band.
#
# DRY RUN IS OFFLINE BY CONSTRUCTION
#   `--dry-run` executes NO `az` command that names a resource — not even a read.
#   Every command is printed verbatim and nothing is called. The ONE exception is
#   STEP 1's `az account show`, which is read-only, names no resource, and is the
#   guard that makes the rest of the printed output trustworthy. Existence probes
#   are printed and then ASSUMED ABSENT, so a dry run shows the complete
#   first-time-through plan rather than a partial diff against live state.
#
# USAGE
#   scripts/azure-staging-provision.sh [--dry-run] [--decisions PATH]
#                                      [--subscription ID] [--delete-snackpass]
#     --dry-run           print every az command, execute none, mutate nothing
#     --decisions PATH    read the decision record from PATH (default: the
#                         in-repo 29-OPERATOR-DECISIONS.md). Exists so the
#                         missing-key refusal can be falsified against a copy
#                         without editing the real record
#     --subscription ID   pin to this subscription id (default: the record's
#                         AZURE_SUBSCRIPTION_ID). A non-owner id REFUSES
#     --delete-snackpass  opt in to the destructive disposition. Refuses unless
#                         the record ALSO says delete-snackpass
#
# REQUIRED IN THE ENVIRONMENT FOR A REAL RUN (never for --dry-run, never printed)
#   PG_ADMIN_USER, PG_ADMIN_PASSWORD — the Flexible Server administrator login.
#   GLOBAL_RULE_6: no credential value appears in this file, in a commit message
#   or in any tracked artifact. Generate with `openssl rand -hex 32` per
#   docs/runbooks/credential-rotation.md.
#
# EXIT CODES: 0 = provisioned (or dry-run printed), 1 = a guard refused or a step
#             failed, 2 = usage / tooling / VOID.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---------------------------------------------------------------------------
# Reporting helpers. NAMES only, never values (mirrors scripts/verify-env.sh).
# ---------------------------------------------------------------------------
ok()     { echo "OK: $*"; }
step()   { echo; echo "=== $* ==="; }
refuse() { local arm="$1"; shift; echo "REFUSED [$arm]: $*" >&2; exit 1; }
die()    { echo "FAIL: $*" >&2; exit 1; }
void()   { echo "VOID: $*" >&2; exit 2; }

# ---------------------------------------------------------------------------
# STEP 0 — flags, before anything else
# ---------------------------------------------------------------------------
DRY_RUN=0
DELETE_SNACKPASS=0
DECISIONS_FILE=""
SUBSCRIPTION_ARG=""

usage() {
  sed -n '/^# USAGE/,/^#                         the record ALSO says delete-snackpass/p' "$0" | sed 's/^# \{0,1\}//'
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run)          DRY_RUN=1 ;;
    --delete-snackpass) DELETE_SNACKPASS=1 ;;
    --decisions)        shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --decisions needs a path" >&2; exit 2; }; DECISIONS_FILE="$1" ;;
    --subscription)     shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --subscription needs an id" >&2; exit 2; }; SUBSCRIPTION_ARG="$1" ;;
    -h|--help)          usage; exit 0 ;;
    *)
      echo "USAGE ERROR: unknown flag '$1'" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

DECISIONS_FILE="${DECISIONS_FILE:-$REPO_ROOT/.planning/phases/29-deployable-staging-with-its-own-monitoring/29-OPERATOR-DECISIONS.md}"

echo "=== J'Toye staging estate provisioning (dry-run=${DRY_RUN}) ==="

command -v az >/dev/null 2>&1 || void "az not found on PATH"

# ---------------------------------------------------------------------------
# NAMES AND IN-REPO CONSTANTS.
#
# These are NOT environment-varying values — they are this estate's identifiers,
# and every one is overridable from the environment so a second estate needs no
# edit. Everything that VARIES BY DECISION (SKU, count, version, ceiling,
# disposition, region, resource group, subscription) is read from the record in
# STEP 2 and appears nowhere in this file as a literal.
# ---------------------------------------------------------------------------
PG_SERVER_NAME="${PG_SERVER_NAME:-jtoye-staging-pg}"
REDIS_NAME="${REDIS_NAME:-jtoye-staging-redis}"
STATIC_IP_NAME="${STATIC_IP_NAME:-jtoye-staging-ingress-ip}"
IDENTITY_NAME="${IDENTITY_NAME:-jtoye-ci}"
FEDCRED_NAME="${FEDCRED_NAME:-gh-deploy-staging}"
GITHUB_REPO="${GITHUB_REPO:-Bralabee/JToye_OaaS_2026}"
# deploy-staging runs on main AND declares `environment: staging`, so the
# environment subject is the correct and TIGHTER one. Subjects are EXACT MATCH —
# there are no wildcards, so `main` and `environment:staging` would need separate
# credentials if both were used.
GITHUB_ENVIRONMENT="${GITHUB_ENVIRONMENT:-staging}"
OIDC_ISSUER_URL="${OIDC_ISSUER_URL:-https://token.actions.githubusercontent.com}"
OIDC_AUDIENCE="${OIDC_AUDIENCE:-api://AzureADTokenExchange}"
# The kubectl context this script creates and then uses. NEVER the ambient one:
# the only pre-existing context on this host is employer infrastructure.
KUBE_CONTEXT="${KUBE_CONTEXT:-jtoye-staging}"
# Contexts that must never be targeted, by name. Intent is not a safety
# mechanism, so this is checked even when the name is supplied explicitly.
FORBIDDEN_KUBE_CONTEXTS="${FORBIDDEN_KUBE_CONTEXTS:-sipbihs2aks}"
# Subscription DISPLAY NAMES that are third-party infrastructure. The id
# equality check in STEP 1 already refuses every non-owner subscription; this is
# the belt-and-braces arm, and it names only the string the decision record
# already carries.
FORBIDDEN_SUBSCRIPTION_NAMES="${FORBIDDEN_SUBSCRIPTION_NAMES:-Prod - HS2 Ltd}"
# Keycloak gets its OWN database on the same server (D-02). The platform
# database name is parsed from the SQL below, not restated.
KEYCLOAK_DB_NAME="${KEYCLOAK_DB_NAME:-keycloak}"
# Storage + retention. Sized from RESEARCH §"Capacity and cost math" (32 GiB at
# £0.1008/GiB/mo = £3.23) and D-09's 7-day PITR window.
PG_STORAGE_GB="${PG_STORAGE_GB:-32}"
PG_BACKUP_RETENTION_DAYS="${PG_BACKUP_RETENTION_DAYS:-7}"
AZURE_PROVIDERS="${AZURE_PROVIDERS:-Microsoft.ContainerService Microsoft.Network Microsoft.Cache}"

# Files that are the SINGLE SOURCE OF TRUTH for values this script must not
# restate. Parsed, never copied.
ROLE_SQL="$REPO_ROOT/infra/backups/create-backup-role.sql"
BASE_MIGRATION="$REPO_ROOT/core-java/src/main/resources/db/migration/V1__base_schema.sql"

# ---------------------------------------------------------------------------
# az wrappers. EVERY az invocation in the body goes through one of these, and
# both INJECT `--subscription`. That is a structural guarantee, not a habit: a
# bare `az` in the body would be the only way to reach the ambient default, and
# STEP 1c asserts there are none.
#
# Sensitive argument values are redacted from the printed form. The redaction is
# by FLAG NAME, so a new secret-bearing flag must be added to the list — that
# direction fails loudly (an unredacted print) rather than silently.
# ---------------------------------------------------------------------------
REDACT_FLAGS="--admin-password --password --secret"

az_print() {
  local out="az" redact_next=0 a
  for a in "$@"; do
    if [ "$redact_next" -eq 1 ]; then
      out="${out} '<redacted>'"
      redact_next=0
      continue
    fi
    case " $REDACT_FLAGS " in
      *" $a "*) redact_next=1 ;;
    esac
    # Quote anything a shell would re-interpret, so the printed line is
    # copy-pasteable rather than merely readable. A placeholder like
    # <AKS-egress-IP> printed bare would become a redirection.
    case "$a" in
      *[\ \<\>\|\&\;\$\(\)\*\?\'\"]*) out="${out} '${a}'" ;;
      *)                              out="${out} ${a}" ;;
    esac
  done
  printf '%s\n' "$out"
}

az_mutate() {
  if [ "$DRY_RUN" -eq 1 ]; then
    printf 'DRY-RUN would run: '
    az_print "$@" --subscription "$AZURE_SUBSCRIPTION_ID"
    return 0
  fi
  az "$@" --subscription "$AZURE_SUBSCRIPTION_ID"
}

# az_read <placeholder> <az args…>
#   Reads a single value. THE NOTICE GOES TO STDERR, DELIBERATELY: every caller
#   captures this function with $(…), which captures stdout — so printing the
#   "would read" line on stdout put a whole sentence into the variable, and that
#   sentence then appeared as the --start-ip-address of a firewall rule in the
#   dry-run output. A dry run whose printed commands are not the commands that
#   would run is worse than no dry run. On stdout the dry-run emits ONLY the
#   named placeholder, so every downstream line stays a faithful rendering.
az_read() {
  local placeholder="$1"; shift
  if [ "$DRY_RUN" -eq 1 ]; then
    {
      printf 'DRY-RUN would read (-> %s): ' "$placeholder"
      az_print "$@" --subscription "$AZURE_SUBSCRIPTION_ID"
    } >&2
    printf '%s\n' "$placeholder"
    return 0
  fi
  # An absent resource is an EXPECTED read failure here (the caller checks for an
  # empty result and decides). Suppression lives inside the wrapper so the
  # dry-run notice above cannot be swallowed by a caller-side 2>/dev/null.
  az "$@" --subscription "$AZURE_SUBSCRIPTION_ID" 2>/dev/null || true
}

# resource_exists <human-name> <az args…>
#   Check-then-create, NEVER delete-then-create. In --dry-run the probe is
#   printed and the resource is ASSUMED ABSENT, so the create command is printed
#   too — a dry run shows the complete first-time plan rather than a partial diff.
resource_exists() {
  local what="$1"; shift
  if [ "$DRY_RUN" -eq 1 ]; then
    printf 'DRY-RUN would probe: '
    az_print "$@" --subscription "$AZURE_SUBSCRIPTION_ID"
    echo "DRY-RUN assumes ABSENT: ${what}"
    return 1
  fi
  if az "$@" --subscription "$AZURE_SUBSCRIPTION_ID" >/dev/null 2>&1; then
    ok "${what} already exists — idempotent no-op, NOT recreating"
    return 0
  fi
  return 1
}

# ---------------------------------------------------------------------------
# STEP 1 — SUBSCRIPTION GUARD. Precedes every mutation in this file, so a
# refusal is provably a no-op.
# ---------------------------------------------------------------------------
step "STEP 1: subscription guard"

[ -f "$DECISIONS_FILE" ] || void "decision record not found: ${DECISIONS_FILE}"

# The record is read for exactly one key here — the owner's subscription id —
# because the guard must run before anything else, including the full parse.
decision_cell() {
  # decision_cell <KEY> -> the raw markdown value cell, or empty
  awk -F '|' -v k="$1" '
      /^\|/ {
        c = $2
        gsub(/`/, "", c)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", c)
        if (c == k) { print $3; exit }
      }' "$DECISIONS_FILE"
}

decision_value() {
  # decision_value <KEY> -> the FIRST backticked token of the value cell.
  # A key with no backticked token is treated as ABSENT, not as empty: a value
  # this parser cannot read is not a value it may guess at.
  local cell v
  cell="$(decision_cell "$1")"
  [ -n "$cell" ] || return 1
  v="$(printf '%s' "$cell" | sed -nE 's/^[^`]*`([^`]+)`.*$/\1/p')"
  [ -n "$v" ] || return 1
  printf '%s\n' "$v"
}

AZURE_SUBSCRIPTION_ID="$(decision_value AZURE_SUBSCRIPTION_ID)" || \
  void "the decision record ${DECISIONS_FILE} defines no readable AZURE_SUBSCRIPTION_ID — refusing to resolve a subscription from the ambient default, which on this host is EMPLOYER infrastructure"
OWNER_SUBSCRIPTION_ID="$AZURE_SUBSCRIPTION_ID"

if [ -n "$SUBSCRIPTION_ARG" ] && [ "$SUBSCRIPTION_ARG" != "$OWNER_SUBSCRIPTION_ID" ]; then
  refuse "wrong-subscription" \
    "--subscription '${SUBSCRIPTION_ARG}' is NOT the owner's subscription recorded in ${DECISIONS_FILE}.
  The other subscription visible from this host is '${FORBIDDEN_SUBSCRIPTION_NAMES}' — EMPLOYER infrastructure that must NEVER be touched.
  A wrong-subscription mutation is not recoverable by re-running with the right one, so this refuses rather than corrects."
fi

# What the CLI actually reports for the subscription we are about to pin. This
# is the ONE read a --dry-run performs: it is read-only, it names no resource,
# and it is what makes every printed command below trustworthy.
SUB_JSON="$(az account show --subscription "$AZURE_SUBSCRIPTION_ID" --query '{id:id,name:name,state:state}' -o tsv 2>/dev/null)" || \
  void "could not read subscription '${AZURE_SUBSCRIPTION_ID}' (az account show failed). Not proceeding on an unverified subscription — run 'az login' first."
RESOLVED_SUB_ID="$(printf '%s' "$SUB_JSON" | cut -f1)"
RESOLVED_SUB_NAME="$(printf '%s' "$SUB_JSON" | cut -f2)"
RESOLVED_SUB_STATE="$(printf '%s' "$SUB_JSON" | cut -f3)"

[ -n "$RESOLVED_SUB_ID" ] || void "az account show returned no subscription id — the assertion would pass by finding nothing, which is VOID, not clean"

# A typo'd id that silently resolves to the default is the failure mode this
# catches: ask for X, be given Y, mutate Y.
[ "$RESOLVED_SUB_ID" = "$OWNER_SUBSCRIPTION_ID" ] || \
  refuse "subscription-identity-mismatch" \
    "asked for subscription '${OWNER_SUBSCRIPTION_ID}' but the CLI resolved '${RESOLVED_SUB_ID}' (${RESOLVED_SUB_NAME}). Refusing to mutate a subscription that is not the one requested."

case "$RESOLVED_SUB_NAME" in
  "$FORBIDDEN_SUBSCRIPTION_NAMES")
    refuse "employer-subscription" \
      "subscription '${RESOLVED_SUB_NAME}' is EMPLOYER infrastructure and must NEVER be touched. Naming it explicitly does not make it safe — intent is not a safety mechanism."
    ;;
esac

[ "$RESOLVED_SUB_STATE" = "Enabled" ] || \
  void "subscription '${RESOLVED_SUB_NAME}' reports state '${RESOLVED_SUB_STATE}', not Enabled"

ok "subscription pinned: ${RESOLVED_SUB_NAME} (${RESOLVED_SUB_ID}), state ${RESOLVED_SUB_STATE}"

AMBIENT_SUB_NAME="$(az account show --query name -o tsv 2>/dev/null || true)"
if [ -n "$AMBIENT_SUB_NAME" ] && [ "$AMBIENT_SUB_NAME" != "$RESOLVED_SUB_NAME" ]; then
  echo "NOTE: the AMBIENT default subscription on this host is '${AMBIENT_SUB_NAME}', which is NOT the target."
  echo "      That is expected and is exactly why every az call below is pinned with --subscription."
  echo "      Do NOT 'fix' it with 'az account set' — that mutates ~/.azure globally for every session on this machine."
fi

# ---------------------------------------------------------------------------
# STEP 1c — no CLI invocation may bypass the wrappers.
#
# Both wrappers inject the subscription pin. An unpinned invocation in the body
# would be the one way to reach the ambient default (which, measured, is the
# employer's), so this asserts by source inspection that there are none — the
# guarantee is structural rather than a habit.
#
# WHY THE PATTERN IS ANCHORED TO A COMMAND POSITION, AND WHY COMMENTS ARE
# STRIPPED FIRST. The first draft matched the tool's name anywhere on a line. It
# fired on its own header prose and on its own refusal messages — the recorded
# "a rule that must name the token it forbids fires on its own definition" trap,
# which made the guard VOID on a correct tree. Fixing it by loosening the
# assertion would have been the wrong direction; instead the pattern now matches
# only a token in COMMAND POSITION (line start, or after a pipe / && / ; / $( ),
# with full-line comments removed. Two read-only `account show` calls in STEP 1
# are exempt by name: they name no resource, they precede every mutation, and one
# of them EXISTS to read the ambient value.
#
# This assertion is falsifiable and was falsified: adding one unpinned invocation
# to a scratch copy makes it exit 2.
# ---------------------------------------------------------------------------
BARE_CLI="$(sed 's/^[[:space:]]*#.*$//' "${BASH_SOURCE[0]}" \
             | grep -nE '(^[[:space:]]*|\$\(|\|[[:space:]]*|&&[[:space:]]*|;[[:space:]]*)az[[:space:]]+[a-z]' \
             | grep -vF -- '--subscription' \
             | grep -vF -- 'az account show' || true)"
if [ -n "$BARE_CLI" ]; then
  echo "$BARE_CLI" >&2
  void "the line(s) above invoke the CLI outside az_mutate/az_read, so they would NOT be pinned to the recorded subscription. Route them through a wrapper."
fi
ok "every CLI invocation in the mutating path is pinned to the recorded subscription"

# ---------------------------------------------------------------------------
# STEP 2 — the decision record. Every SKU, count, ceiling and disposition.
# Refuse BY NAME, before anything is created: a half-provisioned estate is worse
# than one that refused to start.
# ---------------------------------------------------------------------------
step "STEP 2: decision record"
echo "reading: ${DECISIONS_FILE}"

REQUIRED_KEYS=(
  AZURE_SUBSCRIPTION_ID
  AZURE_RESOURCE_GROUP
  AZURE_LOCATION
  AKS_CLUSTER_NAME
  NODE_VM_SIZE
  NODE_COUNT
  PG_SERVER_VERSION
  PG_SERVER_SKU
  PG_ACCESS_MODE
  REDIS_SKU
  SNACKPASS_DISPOSITION
  MONTHLY_CEILING_GBP
)

missing=0
for key in "${REQUIRED_KEYS[@]}"; do
  if value="$(decision_value "$key")"; then
    printf -v "$key" '%s' "$value"
  else
    echo "MISSING: decision key ${key} is absent or unreadable in ${DECISIONS_FILE}" >&2
    missing=$((missing + 1))
  fi
done
[ "$missing" -eq 0 ] || \
  refuse "decision-record" \
    "${missing} required decision key(s) missing — see the names above. Every SKU, count and ceiling this script uses is READ from that record; none is a literal here, so an absent key cannot be filled in by a default."
ok "all ${#REQUIRED_KEYS[@]} decision keys read from the record"

# The compute TIER is stated alongside the SKU in the record's PG_SERVER_SKU
# cell ("`Standard_B2s` Burstable"). Derive it from that cell rather than
# assuming it — a SKU without its tier is not a server shape.
PG_SKU_CELL="$(decision_cell PG_SERVER_SKU)"
PG_SERVER_TIER=""
for tier in Burstable GeneralPurpose MemoryOptimized; do
  case "$PG_SKU_CELL" in
    *"$tier"*) PG_SERVER_TIER="$tier" ;;
  esac
done
[ -n "$PG_SERVER_TIER" ] || \
  refuse "decision-record" "PG_SERVER_SKU's cell names no compute tier (expected one of Burstable / GeneralPurpose / MemoryOptimized): '${PG_SKU_CELL}'"

# REDIS_SKU is recorded as "<tier> <size>" (e.g. "Basic C0"). az wants them as
# two arguments, --sku and --vm-size, and --vm-size is lowercase.
REDIS_TIER="$(printf '%s' "$REDIS_SKU" | awk '{print $1}')"
REDIS_VM_SIZE="$(printf '%s' "$REDIS_SKU" | awk '{print tolower($2)}')"
[ -n "$REDIS_TIER" ] && [ -n "$REDIS_VM_SIZE" ] || \
  refuse "decision-record" "REDIS_SKU '${REDIS_SKU}' does not parse as '<tier> <size>'"

# Sanity, not taste: a non-numeric node count or ceiling means the parse read the
# wrong cell, and a wrong parse must not become a wrong estate.
case "$NODE_COUNT" in
  ''|*[!0-9]*) refuse "decision-record" "NODE_COUNT '${NODE_COUNT}' is not a number — the parse read the wrong cell" ;;
esac
case "$MONTHLY_CEILING_GBP" in
  ''|*[!0-9]*) refuse "decision-record" "MONTHLY_CEILING_GBP '${MONTHLY_CEILING_GBP}' is not a number" ;;
esac
case "$PG_SERVER_VERSION" in
  ''|*[!0-9]*) refuse "decision-record" "PG_SERVER_VERSION '${PG_SERVER_VERSION}' is not a number" ;;
esac
# Blocker C, stated as an executable constraint rather than a comment: on
# PostgreSQL 15 and earlier a non-admin role CANNOT be granted BYPASSRLS on
# Flexible Server (Microsoft holds the real superuser; azure_pg_admin is a
# pseudo-superuser). Without jtoye_backup the logical dump runs as a FORCE-RLS
# subject and captures ZERO rows from every tenant table — a green backup over an
# empty database, which is exactly DPLY-04 arm A's defect.
[ "$PG_SERVER_VERSION" -ge 16 ] || \
  refuse "pg-version" \
    "PG_SERVER_VERSION is ${PG_SERVER_VERSION}. BYPASSRLS cannot be granted to a non-admin role on Azure Flexible Server below 16, so infra/backups/create-backup-role.sql would fail and every logical dump would silently capture ZERO rows from every FORCE-RLS table. This is a requirement, not a preference (29-OPERATOR-DECISIONS.md §5)."

echo
echo "  subscription : ${AZURE_SUBSCRIPTION_ID}"
echo "  resource grp : ${AZURE_RESOURCE_GROUP}"
echo "  location     : ${AZURE_LOCATION}"
echo "  AKS          : ${AKS_CLUSTER_NAME}, ${NODE_COUNT} x ${NODE_VM_SIZE}"
echo "  PostgreSQL   : ${PG_SERVER_NAME}, version ${PG_SERVER_VERSION}, ${PG_SERVER_SKU} ${PG_SERVER_TIER}, access ${PG_ACCESS_MODE}"
echo "  Redis        : ${REDIS_NAME}, ${REDIS_TIER} ${REDIS_VM_SIZE}"
echo "  ceiling      : GBP ${MONTHLY_CEILING_GBP}/mo"
echo "  snackpass    : ${SNACKPASS_DISPOSITION} (executed by plan 29-10, NOT here)"

# ---------------------------------------------------------------------------
# STEP 2b — the snackpass disposition. Two locks, and today both are shut.
#
# The costed estate fits the ceiling ONLY once the pre-existing snackpass spend
# is removed (29-OPERATOR-DECISIONS.md §2.4: measured run-rate GBP 101.02/mo +
# GBP 147.00/mo new estate = GBP 248.02 against a GBP 150 ceiling). Executing the
# disposition is plan 29-10's job and its BEFORE-state table is already recorded
# there (§2.2, obligation O-4).
#
# Finding F3 is why the destructive path needs two locks rather than one: the
# snackpass estate is a DIFFERENT PROJECT (ghcr.io/bralabee/snackpass-*, with a
# python-vision service J'Toye has never had), not a stale copy of this platform.
# Deleting it by momentum would destroy someone else's deployment.
# ---------------------------------------------------------------------------
if [ "$DELETE_SNACKPASS" -eq 1 ] && [ "$SNACKPASS_DISPOSITION" != "delete-snackpass" ]; then
  refuse "snackpass-disposition" \
    "--delete-snackpass was passed but the record says SNACKPASS_DISPOSITION='${SNACKPASS_DISPOSITION}'. The recorded decision wins. The snackpass estate is a DIFFERENT PROJECT (29-OPERATOR-DECISIONS.md §4 F3), so a delete here would destroy work that is not this platform's."
fi
if [ "$DELETE_SNACKPASS" -eq 1 ]; then
  refuse "snackpass-disposition" \
    "the record says delete-snackpass and the opt-in flag was given, but EXECUTION of the disposition belongs to plan 29-10, which also records the after-state against the before-state table (obligation O-4). This script provisions; it does not dispose."
fi
echo
echo "NOTE: SNACKPASS_DISPOSITION='${SNACKPASS_DISPOSITION}' has NOT been applied by this script."
echo "      Until plan 29-10 applies it, the estate this script creates puts total spend over the"
echo "      GBP ${MONTHLY_CEILING_GBP}/mo ceiling. That is a known, recorded state — not a surprise."

# ---------------------------------------------------------------------------
# STEP 3 — resource providers (idempotent, free, minutes)
#
# MEASURED NotRegistered on this subscription 2026-08-10:
# Microsoft.ContainerService, Microsoft.Network, Microsoft.Cache.
# Registration is a subscription-level no-op when already registered.
# ---------------------------------------------------------------------------
step "STEP 3: resource providers"
for provider in $AZURE_PROVIDERS; do
  echo "registering ${provider} (idempotent)"
  az_mutate provider register -n "$provider"
done
ok "provider registration requested for: ${AZURE_PROVIDERS}"

# ---------------------------------------------------------------------------
# STEP 4 — AKS
#
# Cilium is the only dataplane Microsoft recommends for NEW clusters. Azure NPM
# on Linux retires 2028-09-30 and has a documented race with policies carrying
# many ipBlock `except` entries — and k8s/base/networkpolicies/20-core-java.yaml
# has exactly that shape, so NPM is not merely deprecated here, it is the wrong
# engine for THIS repo's policies.
#
# --tier free: the Standard Uptime SLA is GBP 0.0758/hr = GBP 55.33/mo, which
# would eat a third of the recorded ceiling.
# --enable-oidc-issuer: required for the workload-identity path and printed in
# the evidence block, because later plans cannot infer it.
# ---------------------------------------------------------------------------
step "STEP 4: AKS cluster ${AKS_CLUSTER_NAME}"
if ! resource_exists "AKS cluster ${AKS_CLUSTER_NAME}" aks show -g "$AZURE_RESOURCE_GROUP" -n "$AKS_CLUSTER_NAME"; then
  az_mutate aks create \
    -g "$AZURE_RESOURCE_GROUP" -n "$AKS_CLUSTER_NAME" \
    --location "$AZURE_LOCATION" \
    --tier free \
    --network-plugin azure --network-plugin-mode overlay \
    --network-dataplane cilium \
    --node-vm-size "$NODE_VM_SIZE" --node-count "$NODE_COUNT" \
    --enable-oidc-issuer \
    --generate-ssh-keys
fi

NODE_RG="$(az_read '<aks-node-resource-group>' aks show -g "$AZURE_RESOURCE_GROUP" -n "$AKS_CLUSTER_NAME" --query nodeResourceGroup -o tsv)"
OIDC_ISSUER="$(az_read '<aks-oidc-issuer-url>' aks show -g "$AZURE_RESOURCE_GROUP" -n "$AKS_CLUSTER_NAME" --query oidcIssuerProfile.issuerUrl -o tsv)"
EGRESS_IP_ID="$(az_read '<aks-egress-public-ip-id>' aks show -g "$AZURE_RESOURCE_GROUP" -n "$AKS_CLUSTER_NAME" \
                  --query 'networkProfile.loadBalancerProfile.effectiveOutboundIPs[0].id' -o tsv)"
EGRESS_IP=""
if [ -n "$EGRESS_IP_ID" ]; then
  EGRESS_IP="$(az_read '<aks-egress-ip>' network public-ip show --ids "$EGRESS_IP_ID" --query ipAddress -o tsv)"
fi

if [ "$DRY_RUN" -eq 0 ]; then
  [ -n "$NODE_RG" ]    || die "could not read the AKS node resource group — step 5 cannot place the static IP without it"
  [ -n "$EGRESS_IP" ]  || die "could not read the AKS egress IP — step 6's firewall rule would have to be wide open, which is the shape 29-10 T-29-10-01 explicitly rejects"
fi

# ---------------------------------------------------------------------------
# STEP 5 — static public IP for the ingress controller (D-07)
#
# It lives in the cluster's NODE resource group so the AKS-managed identity can
# read it when a LoadBalancer Service claims it. One IP, four A records added by
# hand at Netlify DNS — no external-dns, no zone migration.
# ---------------------------------------------------------------------------
step "STEP 5: static public IP ${STATIC_IP_NAME}"
IP_RG="${NODE_RG:-<AKS-node-resource-group>}"
if ! resource_exists "public IP ${STATIC_IP_NAME}" network public-ip show -g "$IP_RG" -n "$STATIC_IP_NAME"; then
  az_mutate network public-ip create \
    -g "$IP_RG" -n "$STATIC_IP_NAME" \
    --location "$AZURE_LOCATION" \
    --sku Standard --version IPv4 --allocation-method Static
fi
STATIC_IP="$(az_read '<static-ingress-ip>' network public-ip show -g "$IP_RG" -n "$STATIC_IP_NAME" --query ipAddress -o tsv)"

# ---------------------------------------------------------------------------
# STEP 6 — PostgreSQL Flexible Server
#
# The database NAME and the extension list are PARSED from their single sources
# of truth, never restated here: infra/backups/create-backup-role.sql owns the
# database name, and V1__base_schema.sql owns which extensions must be allowed
# before Flyway can run at all.
# ---------------------------------------------------------------------------
step "STEP 6: PostgreSQL Flexible Server ${PG_SERVER_NAME} (version ${PG_SERVER_VERSION})"

[ -f "$ROLE_SQL" ]       || void "not found: ${ROLE_SQL} — the platform database name has no source"
[ -f "$BASE_MIGRATION" ] || void "not found: ${BASE_MIGRATION} — the azure.extensions allowlist has no source"

PLATFORM_DB="$(sed -nE 's/^GRANT CONNECT ON DATABASE ([a-z_]+) TO .*/\1/p' "$ROLE_SQL" | head -1)"
[ -n "$PLATFORM_DB" ] || void "could not parse the platform database name from ${ROLE_SQL}"

# `uuid-ossp` is the one this repo needs today (V1__base_schema.sql:6, and the
# sole exemption in scripts/check-no-create-extension.sh). It is PARSED rather
# than hardcoded so a future migration that adds an extension is picked up here
# instead of failing at Flyway time. The live snackpass-pg allowlist reads
# `vector,pgcrypto` — do NOT copy that value; it does not contain uuid-ossp.
PG_EXTENSIONS="$(grep -oE 'CREATE EXTENSION IF NOT EXISTS "[^"]+"' "$BASE_MIGRATION" \
                  | sed -E 's/.*"([^"]+)"/\1/' | sort -u | paste -sd, -)"
[ -n "$PG_EXTENSIONS" ] || void "no CREATE EXTENSION statement found in ${BASE_MIGRATION} — the allowlist would be set to nothing, and V1 would fail on the first Flyway run"

if [ "$DRY_RUN" -eq 0 ]; then
  admin_missing=0
  for var in PG_ADMIN_USER PG_ADMIN_PASSWORD; do
    if [ -z "${!var:-}" ]; then
      echo "MISSING: ${var} is unset or empty in the environment" >&2
      admin_missing=$((admin_missing + 1))
    fi
  done
  [ "$admin_missing" -eq 0 ] || \
    refuse "admin-credentials" "${admin_missing} administrator credential variable(s) missing — see the names above. Generate with 'openssl rand -hex 32' (docs/runbooks/credential-rotation.md). No value is ever written into this script."
fi

# `--public-access Enabled`, NOT `None`. MEASURED 2026-08-10 on az-cli 2.89.0:
# the CLI's own help says `None` "sets the server in public access mode but does
# not create a firewall rule", which is EXACTLY the shape this estate wants — but
# the server it actually produced reported `network.publicNetworkAccess:
# Disabled`, and the very next step died with
#   "Firewall rule operations are not supported for a server without public
#    access enabled."
# So the documented meaning and the observed behaviour disagree, and the
# behaviour is what ships. `Enabled` creates the server in public-access mode
# with NO firewall rules of its own; the single scoped rule below is still the
# only way in. That keeps PG_ACCESS_MODE=public-with-firewall true, and it is
# NOT the wide-open shape `snackpass-pg` has (threat T-29-10-01).
#
# `--output none` is load-bearing, not tidiness. `az postgres flexible-server
# create` prints a JSON result containing BOTH a `password` field and a full
# `connectionString` with the administrator password embedded in it. The
# REDACT_FLAGS mechanism above only redacts the rendered COMMAND in --dry-run; on
# a real run it is the CLI's OUTPUT that discloses, and nothing was suppressing
# it. Measured: the password reached the run log in plaintext twice, and had to
# be rotated. Every other value this script needs is read back explicitly in the
# evidence step, so discarding this output costs nothing.
if ! resource_exists "Flexible Server ${PG_SERVER_NAME}" postgres flexible-server show -g "$AZURE_RESOURCE_GROUP" -n "$PG_SERVER_NAME"; then
  az_mutate postgres flexible-server create \
    -g "$AZURE_RESOURCE_GROUP" -n "$PG_SERVER_NAME" \
    --location "$AZURE_LOCATION" \
    --version "$PG_SERVER_VERSION" \
    --tier "$PG_SERVER_TIER" --sku-name "$PG_SERVER_SKU" \
    --storage-size "$PG_STORAGE_GB" \
    --backup-retention "$PG_BACKUP_RETENTION_DAYS" \
    --admin-user "${PG_ADMIN_USER:-<PG_ADMIN_USER>}" \
    --admin-password "${PG_ADMIN_PASSWORD:-<PG_ADMIN_PASSWORD>}" \
    --public-access Enabled \
    --yes \
    --output none
fi

# The allowlist BEFORE anything can run Flyway. V1 failing means nothing else
# runs, so this is not a tuning step — it is a precondition.
echo "setting azure.extensions = ${PG_EXTENSIONS} (parsed from ${BASE_MIGRATION#"$REPO_ROOT"/})"
az_mutate postgres flexible-server parameter set \
  -g "$AZURE_RESOURCE_GROUP" -s "$PG_SERVER_NAME" \
  -n azure.extensions -v "$PG_EXTENSIONS"

# The firewall rule is scoped to the AKS EGRESS IP — a single address, not a
# range. The wide rule on the pre-existing snackpass-pg is explicitly NOT the
# shape to copy (29-OPERATOR-DECISIONS.md §1, threat T-29-10-01).
FW_IP="${EGRESS_IP:-<AKS-egress-IP>}"
echo "firewall rule scoped to the AKS egress IP only: ${FW_IP}"
az_mutate postgres flexible-server firewall-rule create \
  -g "$AZURE_RESOURCE_GROUP" -s "$PG_SERVER_NAME" \
  -n "aks-${AKS_CLUSTER_NAME}-egress" \
  --start-ip-address "$FW_IP" --end-ip-address "$FW_IP"

# Two databases on one server (D-02): the platform's, and Keycloak's own.
for dbname in "$PLATFORM_DB" "$KEYCLOAK_DB_NAME"; do
  if ! resource_exists "database ${dbname}" postgres flexible-server db show -g "$AZURE_RESOURCE_GROUP" -s "$PG_SERVER_NAME" -d "$dbname"; then
    az_mutate postgres flexible-server db create \
      -g "$AZURE_RESOURCE_GROUP" -s "$PG_SERVER_NAME" -n "$dbname"
  fi
done

PG_FQDN="$(az_read '<postgres-fqdn>' postgres flexible-server show -g "$AZURE_RESOURCE_GROUP" -n "$PG_SERVER_NAME" --query fullyQualifiedDomainName -o tsv)"

# ---------------------------------------------------------------------------
# STEP 7 — Azure MANAGED Redis (D-09, as superseded 2026-08-10)
#
# THIS WAS `az redis create` AND COULD NOT STAY THAT WAY. Measured live on
# 2026-08-10 while provisioning this estate:
#
#   ERROR: (BadRequest) Azure Cache for Redis is retiring, create Azure Managed
#          Redis instance instead.
#
# The old service is REFUSED at create time — not deprecated, not warned about.
# 29-OPERATOR-DECISIONS.md §6 had recorded that retirement as obligation O-5
# dated 2028-09-30, "no action this phase"; §9 records the falsification and the
# owner-approved move to Azure Managed Redis Balanced B0 (GBP 9.93/mo, which is
# GBP 5.55/mo CHEAPER than the blocked Basic C0).
#
# TWO RESOURCES, NOT ONE. `redisenterprise` (the ARM type behind Managed Redis)
# separates the CLUSTER from the DATABASE. A cluster with no database serves
# nothing and has no port, so both steps are required and the database create is
# NOT optional tidy-up.
#
# PORT 10000, NOT 6380. Managed Redis serves TLS on 10000. That value is
# mirrored in k8s/staging/configmap-patch.yaml (`redis.port`) and flows from
# there into the core-java-allow NetworkPolicy egress rule by kustomize
# `replacements:`. Under the enforcing Cilium dataplane this cluster runs, a
# stale port silently drops every cache call rather than warning.
#
# `--client-protocol Encrypted` is the TLS-only posture, and is the direct
# equivalent of NOT passing --enable-non-ssl-port to the old service: plaintext
# access is never enabled here, deliberately (RESEARCH Pitfall 7, T-29-02-02).
#
# The SKU is assembled from the decision record's "<tier> <size>" cell rather
# than hardcoded, exactly as before — Managed Redis spells it `Balanced_B0`.
# ---------------------------------------------------------------------------
REDIS_AMR_SKU="${REDIS_TIER}_$(printf '%s' "$REDIS_VM_SIZE" | tr '[:lower:]' '[:upper:]')"
step "STEP 7: Azure Managed Redis ${REDIS_NAME} (${REDIS_AMR_SKU})"
# THE CLUSTER AND THE DATABASE ARE CREATED SEPARATELY, ON PURPOSE.
# `az redisenterprise create` can make both in one call, but its own help
# describes re-running it against an existing cluster as
# "(overwrite/recreate, with potential downtime)". This script is idempotent and
# is expected to be re-run after a partial failure — which is exactly how the
# two defects above were found — so a combined create would turn a safe re-run
# into an outage. `--no-database` plus a separately-guarded database create
# keeps each half independently check-then-create.
#
# `--public-network-access Enabled` IS REQUIRED TODAY, not "soon". The CLI only
# warns that the argument "will become required in next breaking change
# release (2.92.0) scheduled for Nov 2026", but the API already refuses without
# it:
#     ERROR: (BadRequest) 'properties.publicNetworkAccess' is required in API
#            version 2025-07-01
# measured 2026-08-10. That is the same shape as the two other divergences this
# script has already hit — a documented future date describing a constraint that
# binds now — so the value is passed explicitly rather than left to a default.
if ! resource_exists "Managed Redis cluster ${REDIS_NAME}" redisenterprise show -g "$AZURE_RESOURCE_GROUP" -n "$REDIS_NAME"; then
  az_mutate redisenterprise create \
    -g "$AZURE_RESOURCE_GROUP" -n "$REDIS_NAME" \
    --location "$AZURE_LOCATION" \
    --sku "$REDIS_AMR_SKU" \
    --minimum-tls-version 1.2 \
    --public-network-access Enabled \
    --no-database
fi
# `--access-keys-auth Enabled` is stated rather than defaulted, because the CLI
# announces that this default FLIPS to Disabled in 2.92.0 (Nov 2026). The
# application authenticates to the cache with an access key (staging-secrets.sh
# renders REDIS_PASSWORD), so inheriting that flip would silently break every
# cache connection on a CLI upgrade, with nothing in this repo having changed.
# An announced default change is a time-bomb; pinning it is the fix.
# NO `-n` / `--name` ON THE DATABASE COMMANDS. Managed Redis allows exactly ONE
# database per cluster and names it `default` itself, so `az redisenterprise
# database show|create` accept only --cluster-name and -g. Passing a name is not
# merely redundant, it is rejected:
#     ERROR: unrecognized arguments: -n default
# measured 2026-08-10, AFTER the cluster had already been created — i.e. the
# failure lands halfway through, which is exactly why the two halves are
# separately guarded and the script is safe to re-run.
if ! resource_exists "Managed Redis database ${REDIS_NAME}/default" \
       redisenterprise database show --cluster-name "$REDIS_NAME" -g "$AZURE_RESOURCE_GROUP"; then
  az_mutate redisenterprise database create \
    --cluster-name "$REDIS_NAME" -g "$AZURE_RESOURCE_GROUP" \
    --client-protocol Encrypted \
    --access-keys-auth Enabled \
    --port 10000
fi
REDIS_HOSTNAME="$(az_read '<redis-hostname>' redisenterprise show -g "$AZURE_RESOURCE_GROUP" -n "$REDIS_NAME" --query hostName -o tsv)"
REDIS_PORT_LIVE="$(az_read '<redis-port>' redisenterprise database show --cluster-name "$REDIS_NAME" -g "$AZURE_RESOURCE_GROUP" --query port -o tsv)"

# ---------------------------------------------------------------------------
# STEP 8 — user-assigned identity + GitHub federated credential (D-04, #99)
#
# No client secret is ever created. The subject is EXACT MATCH with no wildcards,
# which is why the environment form is used rather than the branch form: the
# deploy-staging job declares `environment: staging`, so this is both correct and
# tighter than `ref:refs/heads/main`.
# ---------------------------------------------------------------------------
step "STEP 8: managed identity ${IDENTITY_NAME} + federated credential ${FEDCRED_NAME}"
if ! resource_exists "user-assigned identity ${IDENTITY_NAME}" identity show -g "$AZURE_RESOURCE_GROUP" -n "$IDENTITY_NAME"; then
  az_mutate identity create -g "$AZURE_RESOURCE_GROUP" -n "$IDENTITY_NAME" --location "$AZURE_LOCATION"
fi

FEDCRED_SUBJECT="repo:${GITHUB_REPO}:environment:${GITHUB_ENVIRONMENT}"
if ! resource_exists "federated credential ${FEDCRED_NAME}" identity federated-credential show \
       --name "$FEDCRED_NAME" --identity-name "$IDENTITY_NAME" -g "$AZURE_RESOURCE_GROUP"; then
  az_mutate identity federated-credential create \
    --name "$FEDCRED_NAME" --identity-name "$IDENTITY_NAME" -g "$AZURE_RESOURCE_GROUP" \
    --issuer "$OIDC_ISSUER_URL" \
    --subject "$FEDCRED_SUBJECT" \
    --audience "$OIDC_AUDIENCE"
fi
IDENTITY_CLIENT_ID="$(az_read '<identity-client-id>' identity show -g "$AZURE_RESOURCE_GROUP" -n "$IDENTITY_NAME" --query clientId -o tsv)"
IDENTITY_TENANT_ID="$(az_read '<identity-tenant-id>' identity show -g "$AZURE_RESOURCE_GROUP" -n "$IDENTITY_NAME" --query tenantId -o tsv)"

# ---------------------------------------------------------------------------
# STEP 9 — EVIDENCE. The facts later plans need and CANNOT infer.
#
# Node allocatable is assumption A2 in the research: the pool sizing is REASONED
# there, not measured. It is measured HERE, once a node exists, because Pitfall 6
# turns on whether the estate actually fits. kubectl is invoked with an explicit
# --context, never the ambient one — the only pre-existing context on this host
# is employer infrastructure.
# ---------------------------------------------------------------------------
step "STEP 9: evidence"

NODE_ALLOCATABLE="(not measured — --dry-run)"
if [ "$DRY_RUN" -eq 0 ] && command -v kubectl >/dev/null 2>&1; then
  case " $FORBIDDEN_KUBE_CONTEXTS " in
    *" $KUBE_CONTEXT "*)
      die "KUBE_CONTEXT '${KUBE_CONTEXT}' is on the refusal list — that context is EMPLOYER infrastructure. Naming it explicitly does not make it safe."
      ;;
  esac
  az_mutate aks get-credentials -g "$AZURE_RESOURCE_GROUP" -n "$AKS_CLUSTER_NAME" \
    --context "$KUBE_CONTEXT" --overwrite-existing
  NODE_ALLOCATABLE="$(kubectl --context "$KUBE_CONTEXT" get nodes \
      -o go-template='{{range .items}}{{.metadata.name}} cpu={{.status.allocatable.cpu}} memory={{.status.allocatable.memory}}{{"\n"}}{{end}}' 2>/dev/null || echo '(kubectl get nodes failed)')"
fi

cat <<EVIDENCE

--- BEGIN STAGING ESTATE EVIDENCE ---
captured           : $(date -u +%Y-%m-%dT%H:%M:%SZ)
git commit         : $(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)
dry-run            : ${DRY_RUN}
decision record    : ${DECISIONS_FILE}
subscription       : ${RESOLVED_SUB_NAME} (${RESOLVED_SUB_ID})
resource group     : ${AZURE_RESOURCE_GROUP} (${AZURE_LOCATION})
AKS cluster        : ${AKS_CLUSTER_NAME} (${NODE_COUNT} x ${NODE_VM_SIZE})
AKS node RG        : ${NODE_RG:-<unresolved>}
AKS egress IP      : ${EGRESS_IP:-<unresolved>}      <- the ONLY address the Postgres firewall admits
AKS OIDC issuer    : ${OIDC_ISSUER:-<unresolved>}
static ingress IP  : ${STATIC_IP:-<unresolved>}      <- the A-record target for all four staging hostnames
Postgres FQDN      : ${PG_FQDN:-<unresolved>}
Postgres version   : ${PG_SERVER_VERSION} (${PG_SERVER_SKU} ${PG_SERVER_TIER})
Postgres databases : ${PLATFORM_DB}, ${KEYCLOAK_DB_NAME}
azure.extensions   : ${PG_EXTENSIONS}
Redis hostname     : ${REDIS_HOSTNAME:-<unresolved>} (Azure MANAGED Redis, TLS only)
Redis port (live)  : ${REDIS_PORT_LIVE:-<unresolved>}      <- must equal k8s/staging redis.port, or the NetworkPolicy drops every cache call
identity clientId  : ${IDENTITY_CLIENT_ID:-<unresolved>}
identity tenantId  : ${IDENTITY_TENANT_ID:-<unresolved>}
fedcred subject    : ${FEDCRED_SUBJECT}
node allocatable   :
$(printf '%s\n' "$NODE_ALLOCATABLE" | sed 's/^/  /')
snackpass          : ${SNACKPASS_DISPOSITION} — NOT applied here (plan 29-10, obligation O-4)
ceiling            : GBP ${MONTHLY_CEILING_GBP}/mo
--- END STAGING ESTATE EVIDENCE ---

NEXT, IN THIS ORDER:
  1. plan 29-10 applies the snackpass disposition and adds the four A records at
     Netlify DNS, all pointing at the static ingress IP above
  2. scripts/staging-secrets.sh   — Secrets + the DB role bootstrap
  3. scripts/staging-bootstrap.sh — cert-manager, RabbitMQ operator, ingress
  4. kubectl apply -k k8s/staging
EVIDENCE

if [ "$DRY_RUN" -eq 1 ]; then
  echo
  echo "PASS: --dry-run printed every az command and executed NONE of them. Nothing was created."
else
  echo
  echo "PASS: staging estate provisioned. Paste the evidence block above into the phase record."
fi
