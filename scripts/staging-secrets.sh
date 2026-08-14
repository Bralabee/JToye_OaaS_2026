#!/usr/bin/env bash
# staging-secrets.sh — idempotent out-of-band bootstrap for the AKS staging
# environment (Phase 29 / DPLY-01, DPLY-03, DPLY-04; decisions D-02, D-09, D-11,
# D-12, D-17, D-19).
#
# This is the staging sibling of scripts/k8s-local-secrets.sh. The apply idiom,
# the fail-loud-by-NAME preflight and the DB-side verification are that file's,
# deliberately unchanged in shape — what differs is named below, item by item, so
# a reader can tell a real divergence from a copy.
#
# WHAT IT CREATES (all idempotent, safe to re-run)
#   1. the staging namespace, if absent
#   2. the three database roles on the MANAGED Flexible Server, by invoking
#      infra/db/create-runtime-role.sql and infra/backups/create-backup-role.sql
#      — this file NEVER writes their role SQL, so there is exactly one
#      definition of each role's privileges
#   3. every Secret the k8s/staging render consumes, rendered client-side and
#      applied
#
# HOW IT DIFFERS FROM THE LOCAL SIBLING, AND WHY
#   * The compose-XOR guard is replaced by a KUBE-CONTEXT guard. Local's hazard
#     was two writers on one dev Postgres. Staging's hazard is different and
#     worse: the ONLY kubectl context on this host is `sipbihs2aks`, which is
#     EMPLOYER infrastructure. So the context must be NAMED, must exist, and must
#     not be on the refusal list — and naming a forbidden one explicitly does not
#     make it allowed, because intent is not a safety mechanism.
#   * There is no container to `docker exec` into. The managed server is reached
#     with the host `psql` client over TLS, so `psql` is a hard requirement and a
#     missing one is VOID, not a skip.
#   * `jtoye_app` has no operator-bootstrap SQL in this repo — only the
#     compose-only FRESH-VOLUME path in infra/db/init/00-create-db.sql, which a
#     managed server never runs. STEP 4a therefore creates it, and that is the
#     ONE piece of role SQL this file owns. It is deliberately minimal, and it
#     binds the OWNER/MIGRATOR password (DB_MIGRATION_PASSWORD), which is exactly
#     the binding the compose init file cannot express (it has only one password).
#   * Three credentials are new to this phase: the Alertmanager SMTP app password
#     (D-17 — "alerts a human" is the phase's entire point), the Grafana admin
#     credential (D-19) and REAL AWS keys for the media and backup buckets
#     (D-11/D-12), where local used MinIO root credentials for both.
#   * The `ghcr-pull` imagePullSecret is conditional on the DECISION RECORD, not
#     on a guess. Assumption A5 was resolved by measurement in plan 29-01: all
#     three jtoye packages are PUBLIC, so no pull secret is created.
#
# WHY THE SECRETS ARE IMPERATIVE AND NOT KUSTOMIZE RESOURCES
#   k8s/scripts/check-no-plaintext-secrets.sh auto-discovers every kustomization
#   and fails the build on any `kind: Secret` in the render (#100), so
#   `secretGenerator` is not an option. Secrets therefore arrive out-of-band from
#   here, and nothing this file creates is ever a kustomize resource.
#
#   THE MECHANISM CHOICE IS RECORDED, NOT IMPLIED: staging uses plain Kubernetes
#   Secrets via this script. #100 and #300 (sealed-secrets) are DEFERRED with the
#   reason written down in k8s/QUICK_START.md — a single-operator staging cluster
#   gains little from a sealing controller and pays for it with a keypair to back
#   up and a second failure mode on day one.
#
# NO ENVIRONMENT-VARYING LITERALS (GLOBAL_RULE_6 / ARCHITECTURE_RULE_8)
#   No host, port or endpoint literal appears below. The namespace comes from
#   k8s/staging/kustomization.yaml, the database name and all three role names
#   come from the SQL files that define them, and every endpoint comes from the
#   environment — populated from the evidence block that
#   scripts/azure-staging-provision.sh prints.
#
# SECURITY
#   Variable NAMES only, never a value. No credential value appears in this
#   script, in a commit message or in any tracked artifact. Values reach only a
#   literal argument or an injected SQL variable; nothing echoes them.
#   (Values are briefly visible in argv to a local `ps` — the same accepted
#   trade-off as the local sibling, threat T-26-28.)
#
# USAGE
#   scripts/staging-secrets.sh --context <kube-context> [MODE]
#     --context NAME       REQUIRED. The kubectl context. There is no default and
#                          there will never be one: the ambient context on this
#                          host is employer infrastructure
#     --roles-only         guards + preflight + database roles, then stop. Makes
#                          NO kubectl call, so the cluster need not exist yet
#     --secrets-only       guards + preflight + Secrets, then stop. For rotating
#                          a value on a server whose roles already exist
#     --verify-roles-only  run ONLY the DB-side role verification. Mutates
#                          NOTHING, on the server or in the cluster
#
# EXIT CODES: 0 = done, 1 = a guard refused or a verification failed,
#             2 = usage / tooling / VOID.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ok()     { echo "OK: $*"; }
step()   { echo; echo "=== $* ==="; }
refuse() { local arm="$1"; shift; echo "REFUSED [$arm]: $*" >&2; exit 1; }
void()   { echo "VOID: $*" >&2; exit 2; }

# ---------------------------------------------------------------------------
# STEP 0 — flags, before anything else, so a typo cannot fall through into a
# mutating step.
# ---------------------------------------------------------------------------
KUBE_CONTEXT=""
MODE="all"

usage() {
  sed -n '/^# USAGE/,/^#                          on the server or in the cluster/p' "$0" | sed 's/^# \{0,1\}//'
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --context)           shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --context needs a name" >&2; exit 2; }; KUBE_CONTEXT="$1" ;;
    --roles-only)        MODE="roles" ;;
    --secrets-only)      MODE="secrets" ;;
    --verify-roles-only) MODE="verify" ;;
    -h|--help)           usage; exit 0 ;;
    *)
      echo "USAGE ERROR: unknown flag '$1'" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

echo "=== J'Toye staging bootstrap: database roles + Secrets (mode=${MODE}) ==="

# ---------------------------------------------------------------------------
# In-repo constants and single sources of truth. Parsed, never restated.
# ---------------------------------------------------------------------------
STAGING_KUSTOMIZATION="k8s/staging/kustomization.yaml"
STAGING_NAMESPACE_MANIFEST="$REPO_ROOT/k8s/staging/namespace.yaml"
BACKUP_ROLE_SQL="$REPO_ROOT/infra/backups/create-backup-role.sql"
RUNTIME_ROLE_SQL="$REPO_ROOT/infra/db/create-runtime-role.sql"
DECISIONS_FILE="${DECISIONS_FILE:-$REPO_ROOT/.planning/phases/29-deployable-staging-with-its-own-monitoring/29-OPERATOR-DECISIONS.md}"

# Contexts that must NEVER be targeted. Checked even when named explicitly.
FORBIDDEN_KUBE_CONTEXTS="${FORBIDDEN_KUBE_CONTEXTS:-sipbihs2aks}"
# Grafana's own product default for the admin login name. Not environment-varying;
# overridable so an environment that renames it needs no edit.
GRAFANA_ADMIN_USER="${GRAFANA_ADMIN_USER:-admin}"
# Azure Flexible Server refuses non-TLS connections. This is not tuning.
PGSSLMODE_STAGING="${PGSSLMODE_STAGING:-require}"

# ---------------------------------------------------------------------------
# STEP 1 — KUBE-CONTEXT GUARD.
#
# This replaces the local sibling's compose-XOR guard, and it is strictly more
# load-bearing: local's worst case was a broken dev stack, this one's worst case
# is somebody else's production. It precedes every mutating call below, so a
# refusal is provably a no-op.
#
# In --verify-roles-only and --roles-only NO kubectl call is made at all, so the
# guard is not evaluated — and that is stated out loud rather than left silent.
# A guard that protects nothing is not a bypass; a guard silently skipped is.
# ---------------------------------------------------------------------------
step "STEP 1: kube-context guard"

kube_guard_needed=1
case "$MODE" in
  roles|verify) kube_guard_needed=0 ;;
esac

if [ "$kube_guard_needed" -eq 0 ]; then
  echo "SKIP: mode '${MODE}' makes no kubectl call, so the context guard is not evaluated."
  echo "      This script structurally cannot reach the Secret steps in this mode."
else
  command -v kubectl >/dev/null 2>&1 || void "kubectl not found on PATH"

  [ -n "$KUBE_CONTEXT" ] || \
    void "--context is REQUIRED and has no default. The only kubectl context on this host is '${FORBIDDEN_KUBE_CONTEXTS}', which is EMPLOYER infrastructure — running against the ambient default would target it. Resolve the staging context first with 'az aks get-credentials … --context <name>'."

  case " $FORBIDDEN_KUBE_CONTEXTS " in
    *" $KUBE_CONTEXT "*)
      void "context '${KUBE_CONTEXT}' is on the refusal list — it is EMPLOYER infrastructure. Naming it explicitly does not make it safe; intent is not a safety mechanism."
      ;;
  esac

  KNOWN_CONTEXTS="$(kubectl config get-contexts -o name 2>/dev/null || true)"
  [ -n "$KNOWN_CONTEXTS" ] || void "kubectl reported no contexts at all — the check would pass by finding nothing, which is VOID, not clean"
  if ! grep -Fxq "$KUBE_CONTEXT" <<<"$KNOWN_CONTEXTS"; then
    echo "known contexts:" >&2
    printf '  %s\n' $KNOWN_CONTEXTS >&2
    void "context '${KUBE_CONTEXT}' does not exist in kubeconfig. Not proceeding on an unresolvable target."
  fi
  ok "kubectl context '${KUBE_CONTEXT}' exists and is not on the refusal list"
fi

# The ONLY way this script talks to a cluster: --context is always explicit.
# `kubectl config use-context` is never called anywhere, so the ambient
# current-context can never decide where an apply lands.
staging_kubectl() {
  [ -n "$KUBE_CONTEXT" ] || void "internal: staging_kubectl called with no context"
  kubectl --context "$KUBE_CONTEXT" "$@"
}

# ---------------------------------------------------------------------------
# STEP 2 — names parsed from their single sources of truth.
# ---------------------------------------------------------------------------
step "STEP 2: names"

for f in "$BACKUP_ROLE_SQL" "$RUNTIME_ROLE_SQL" "$STAGING_NAMESPACE_MANIFEST"; do
  [ -f "$f" ] || void "not found: ${f}"
done

NS="$(awk '/^namespace:[[:space:]]*[^[:space:]]/{print $2; exit}' "$REPO_ROOT/$STAGING_KUSTOMIZATION" 2>/dev/null || true)"
[ -n "$NS" ] || void "could not parse a 'namespace:' line from ${STAGING_KUSTOMIZATION} — the namespace has exactly one source of truth and this is it"

BACKUP_ROLE="$(grep -oE "rolname = '[a-z_]+'" "$BACKUP_ROLE_SQL"   | head -1 | sed -E "s/.*'([a-z_]+)'/\1/")"
RUNTIME_ROLE="$(grep -oE "rolname = '[a-z_]+'" "$RUNTIME_ROLE_SQL" | head -1 | sed -E "s/.*'([a-z_]+)'/\1/")"
OWNER_ROLE="$(sed -nE 's/^ALTER DEFAULT PRIVILEGES FOR ROLE ([a-z_]+).*/\1/p' "$RUNTIME_ROLE_SQL" | head -1)"
PLATFORM_DB="$(sed -nE 's/^GRANT CONNECT ON DATABASE ([a-z_]+) TO .*/\1/p' "$BACKUP_ROLE_SQL" | head -1)"

for pair in "BACKUP_ROLE:$BACKUP_ROLE" "RUNTIME_ROLE:$RUNTIME_ROLE" "OWNER_ROLE:$OWNER_ROLE" "PLATFORM_DB:$PLATFORM_DB"; do
  [ -n "${pair#*:}" ] || void "could not parse ${pair%%:*} from the SQL files — refusing to bootstrap roles whose names had to be guessed"
done

ok "namespace ${NS}; database ${PLATFORM_DB}; roles ${OWNER_ROLE} (owner/migrator), ${RUNTIME_ROLE} (DML), ${BACKUP_ROLE} (dump)"

# ---------------------------------------------------------------------------
# STEP 3 — VALUE PREFLIGHT. Fail loud, BY NAME, before anything is created: a
# half-bootstrapped cluster is worse than one that refused to start.
#
# The list is the local sibling's, extended with this phase's new credentials.
# Every entry is here because something breaks WITHOUT it, not for completeness.
# ---------------------------------------------------------------------------
step "STEP 3: value preflight"

REQUIRED_VALUES=(
  # --- managed Flexible Server endpoint + administrator (D-09) ---------------
  # Populated from the evidence block scripts/azure-staging-provision.sh prints.
  STAGING_DB_HOST
  STAGING_DB_PORT
  PG_ADMIN_USER
  PG_ADMIN_PASSWORD
  # --- the three-role split (SEC-04 / #552, Phase 28) -----------------------
  DB_MIGRATION_PASSWORD   # the OWNER/MIGRATOR role — Flyway needs CREATE
  DB_PASSWORD             # the DML runtime role the application connects as
  DB_BACKUP_PASSWORD      # the BYPASSRLS dump role the pg-backup CronJob uses
  # --- the rest of the platform --------------------------------------------
  REDIS_PASSWORD
  RABBITMQ_USER
  RABBITMQ_PASSWORD
  KEYCLOAK_ADMIN
  KEYCLOAK_ADMIN_PASSWORD
  KEYCLOAK_CLIENT_SECRET
  KC_DB_PASSWORD          # Keycloak's OWN database on the same server (D-02)
  NEXTAUTH_SECRET
  # --- real AWS, not MinIO (D-11 media, D-12 backups) -----------------------
  AWS_MEDIA_ACCESS_KEY_ID
  AWS_MEDIA_SECRET_ACCESS_KEY
  AWS_BACKUP_ACCESS_KEY_ID
  AWS_BACKUP_SECRET_ACCESS_KEY
  # --- new in this phase ----------------------------------------------------
  # D-17: a missing To address does not fail — it silently sends nowhere, which
  # is indistinguishable from a healthy system with no alerts. That is precisely
  # the failure this phase exists to eliminate, so all three are REQUIRED.
  ALERTMANAGER_SMTP_PASSWORD
  ALERTMANAGER_SMTP_FROM
  ALERTMANAGER_SMTP_TO
  GRAFANA_ADMIN_PASSWORD  # D-19; generate with `openssl rand -hex 32`
)

missing=0
for var in "${REQUIRED_VALUES[@]}"; do
  if [ -z "${!var:-}" ]; then
    echo "MISSING: ${var} is unset or empty in the environment" >&2
    missing=$((missing + 1))
  fi
done
[ "$missing" -eq 0 ] || \
  refuse "value-preflight" "${missing} required variable(s) missing — see the names above. Nothing has been created."

# Placeholder values are worse than absent ones: they pass an is-set check and
# then authenticate nowhere. Same arm as the local sibling's DB_BACKUP_PASSWORD
# check, widened to every credential this phase adds.
for var in DB_BACKUP_PASSWORD DB_PASSWORD DB_MIGRATION_PASSWORD GRAFANA_ADMIN_PASSWORD ALERTMANAGER_SMTP_PASSWORD; do
  case "${!var}" in
    CHANGE_ME*|YOUR_*|changeme*|xxx*)
      refuse "weak-value" "${var} is still a placeholder. Generate one with: openssl rand -hex 32"
      ;;
  esac
done
ok "all ${#REQUIRED_VALUES[@]} required values present and none is a placeholder"

# ---------------------------------------------------------------------------
# STEP 4 — DATABASE ROLES on the managed server.
#
# WHY THIS IS NOT A FLYWAY MIGRATION: BYPASSRLS and CREATEROLE are not available
# to the migration role, and the migration chain must stay replayable on every
# existing database. It is an operator bootstrap, exactly as both SQL files' own
# headers say.
# ---------------------------------------------------------------------------
psql_admin() {
  # psql_admin <db> <psql args…> — always as the server administrator, always
  # over TLS. PGPASSWORD is passed in the environment of the single command and
  # is never echoed.
  local db="$1"; shift
  PGPASSWORD="$PG_ADMIN_PASSWORD" psql \
    "host=${STAGING_DB_HOST} port=${STAGING_DB_PORT} dbname=${db} user=${PG_ADMIN_USER} sslmode=${PGSSLMODE_STAGING}" \
    -v ON_ERROR_STOP=1 "$@"
}

# ---------------------------------------------------------------------------
# STEP 4v — THE DB-SIDE VERIFICATION.
#
# Defined before the mutating step so it can be run alone (--verify-roles-only),
# which is what makes it falsifiable: a scratch role created WITHOUT BYPASSRLS
# must make this exit non-zero, and that arm has been run.
#
# WHY IT EXISTS: the tenant tables use FORCE ROW LEVEL SECURITY, which applies
# RLS even to the table owner. A pg_dump as a role without BYPASSRLS and with no
# tenant GUC silently captures ZERO rows from every tenant-scoped table — a green
# backup over an empty database. Measured live 2026-07-10 on the dev DB:
#   as the app role (FORCE RLS, no tenant): SELECT count(*) FROM products -> 0
#   as the BYPASSRLS dump role            : SELECT count(*) FROM products -> 25
# That is DPLY-04 arm A's exact defect, so the bootstrap is not trusted to have
# worked — it is read back from the database.
#
# The runtime role is verified in the OPPOSITE direction, and that asymmetry is
# the point: it must NOT have BYPASSRLS and must NOT be a superuser, or the whole
# tenant wall is decorative for the role the application actually connects as.
# ---------------------------------------------------------------------------
verify_db_roles() {
  command -v psql >/dev/null 2>&1 || void "psql not found on PATH — the role verification cannot run, and an unverified role posture is VOID, not clean"

  local attrs
  attrs="$(psql_admin "$PLATFORM_DB" -tAF'|' -c \
      "SELECT rolname, rolbypassrls, rolsuper FROM pg_roles WHERE rolname IN ('${BACKUP_ROLE}','${RUNTIME_ROLE}','${OWNER_ROLE}') ORDER BY rolname" 2>/dev/null)" \
    || void "could not query pg_roles on ${STAGING_DB_HOST} — the verification is VOID, not passing"

  [ -n "$attrs" ] || \
    refuse "roles-absent" "pg_roles returned NO rows for any of ${OWNER_ROLE}, ${RUNTIME_ROLE}, ${BACKUP_ROLE} on ${STAGING_DB_HOST}. An empty result is not a pass — none of the roles exists."

  echo "pg_roles (rolname|rolbypassrls|rolsuper):"
  printf '%s\n' "$attrs" | sed 's/^/  /'

  local backup_bypass runtime_bypass runtime_super
  backup_bypass="$(printf  '%s\n' "$attrs" | awk -F'|' -v r="$BACKUP_ROLE"  '$1==r{print $2}')"
  runtime_bypass="$(printf '%s\n' "$attrs" | awk -F'|' -v r="$RUNTIME_ROLE" '$1==r{print $2}')"
  runtime_super="$(printf  '%s\n' "$attrs" | awk -F'|' -v r="$RUNTIME_ROLE" '$1==r{print $3}')"

  if [ "$backup_bypass" != "t" ]; then
    refuse "backup-role-bypassrls" \
      "role ${BACKUP_ROLE} is missing or does not have rolbypassrls (got: '${backup_bypass:-<absent>}'). A dump under this role would capture ZERO rows from every FORCE-RLS table — a green backup over an empty database, which is exactly DPLY-04 arm A's defect. On Azure Flexible Server this ALSO fails when the server is PostgreSQL 15 or earlier, where a non-admin role cannot be granted BYPASSRLS at all."
  fi
  ok "role ${BACKUP_ROLE} exists with rolbypassrls = t"

  if [ "$runtime_bypass" != "f" ] || [ "$runtime_super" != "f" ]; then
    refuse "runtime-role-overprivileged" \
      "role ${RUNTIME_ROLE} must have rolbypassrls = f AND rolsuper = f (got rolbypassrls='${runtime_bypass:-<absent>}', rolsuper='${runtime_super:-<absent>}'). This is the role the APPLICATION connects as: with either attribute it bypasses every Row-Level Security policy in the database and multi-tenant isolation becomes impossible. core-java's DatabaseConfigurationValidator refuses to start on the superuser case; the BYPASSRLS case has no such boot guard, which is why it is asserted here."
  fi
  ok "role ${RUNTIME_ROLE} exists with rolbypassrls = f and rolsuper = f"
}

if [ "$MODE" = "verify" ]; then
  step "STEP 4v: database role verification only (mutates nothing)"
  verify_db_roles
  echo
  echo "PASS: role posture verified on ${STAGING_DB_HOST}. Nothing was modified."
  exit 0
fi

if [ "$MODE" != "secrets" ]; then
  step "STEP 4: database roles on ${STAGING_DB_HOST}"
  command -v psql >/dev/null 2>&1 || void "psql not found on PATH — there is no container to exec into on a managed server, so the host client is required"

  # --- STEP 4a — the owner/migrator role -----------------------------------
  # THE ONE PIECE OF ROLE SQL THIS FILE OWNS, and it exists because nothing else
  # in the repo can provide it on a managed server: infra/db/init/00-create-db.sql
  # creates this role, but that directory runs ONLY on an empty PostgreSQL data
  # directory, i.e. on compose. It also binds a single password to both the owner
  # and the runtime role, which is precisely the binding the Phase 28 split
  # forbids — so it could not be reused here even if it did run.
  echo "--- ${OWNER_ROLE} (owner/migrator) ---"
  psql_admin "$PLATFORM_DB" \
    -v owner_role="$OWNER_ROLE" \
    -v owner_password="$DB_MIGRATION_PASSWORD" \
    -v platform_db="$PLATFORM_DB" <<'SQL' >/dev/null
\set ON_ERROR_STOP on
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'owner_role', :'owner_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'owner_role')\gexec
-- Correct the password on an existing role too, so a re-run converges.
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD %L', :'owner_role', :'owner_password')\gexec
-- Flyway runs as this role and needs CREATE. Since PostgreSQL 15 the `public`
-- schema no longer grants CREATE to PUBLIC, so this grant is required, not
-- belt-and-braces.
SELECT format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I', :'platform_db', :'owner_role')\gexec
SELECT format('GRANT CREATE, USAGE ON SCHEMA public TO %I', :'owner_role')\gexec
-- ALTER DEFAULT PRIVILEGES FOR ROLE <owner> (run by both role files below)
-- requires the executing role to be a MEMBER of <owner>. Without this the two
-- files fail with "must be member of role", and the failure looks like a
-- privilege problem on the managed server rather than a missing grant.
SELECT format('GRANT %I TO CURRENT_USER', :'owner_role')
WHERE NOT EXISTS (
  SELECT FROM pg_auth_members m
  JOIN pg_roles r ON r.oid = m.roleid
  JOIN pg_roles g ON g.oid = m.member
  WHERE r.rolname = :'owner_role' AND g.rolname = CURRENT_USER
)\gexec
SQL
  ok "role ${OWNER_ROLE} present with CREATE on schema public"

  # --- STEP 4b — the DML runtime role and the BYPASSRLS dump role -----------
  # Both SQL files are INVOKED, never reimplemented, so each role has exactly one
  # definition of its privileges. Their ORDER is theirs, not ours: both grant
  # against objects that must already exist, and create-runtime-role.sql's own
  # header says it must run AFTER the first migration (it grants TRUNCATE on a
  # table V61 creates). A failure here that names a missing relation is that
  # ordering requirement, not a broken script — run Flyway, then re-run this.
  echo "--- ${RUNTIME_ROLE} (DML) ---"
  psql_admin "$PLATFORM_DB" -v runtime_password="$DB_PASSWORD" -f "$RUNTIME_ROLE_SQL" >/dev/null
  echo "--- ${BACKUP_ROLE} (BYPASSRLS dump) ---"
  psql_admin "$PLATFORM_DB" -v backup_password="$DB_BACKUP_PASSWORD" -f "$BACKUP_ROLE_SQL" >/dev/null

  # --- STEP 4c — read it back from the DB side -----------------------------
  step "STEP 4c: verify the role posture from the DB side"
  verify_db_roles
fi

if [ "$MODE" = "roles" ]; then
  echo
  echo "PASS: --roles-only — database roles bootstrapped and verified. No kubectl call was made."
  exit 0
fi

# ---------------------------------------------------------------------------
# STEP 5 — namespace (idempotent)
# ---------------------------------------------------------------------------
step "STEP 5: namespace ${NS}"
staging_kubectl apply -f "$STAGING_NAMESPACE_MANIFEST" >/dev/null
ok "namespace ${NS} present"

# ---------------------------------------------------------------------------
# STEP 6 — Secrets
#
# The apply idiom is the local sibling's, verbatim in shape and for its reasons:
# render client-side, then apply. No delete window, no error on re-run, and
# nothing becomes a kustomize resource.
# ---------------------------------------------------------------------------
step "STEP 6: secrets"

CREATED=()
SKIPPED=()

apply_secret() {
  # apply_secret <name> <--from-literal=k=v>...
  local name="$1"; shift
  kubectl create secret generic "$name" "$@" --dry-run=client -o yaml | staging_kubectl apply -n "$NS" -f - >/dev/null
  CREATED+=("$name")
}

skip_secret() {
  # skip_secret <name> <reason> — a skipped Secret is REPORTED, never silent.
  SKIPPED+=("$1 — $2")
  echo "SKIP: Secret ${1} not created — ${2}"
}

# DEF-2 / SEC-04: `username` is the OWNER/MIGRATOR role and `runtime-username` is
# the DML role the application connects as. Neither is ever the server
# administrator: injecting an admin login would bypass every RLS policy in the
# cluster, and core-java's DatabaseConfigurationValidator refuses to start rather
# than run that way.
apply_secret postgres-credentials \
  "--from-literal=host=$STAGING_DB_HOST" \
  "--from-literal=port=$STAGING_DB_PORT" \
  "--from-literal=database=$PLATFORM_DB" \
  "--from-literal=username=$OWNER_ROLE" \
  "--from-literal=password=$DB_MIGRATION_PASSWORD" \
  "--from-literal=runtime-username=$RUNTIME_ROLE" \
  "--from-literal=runtime-password=$DB_PASSWORD" \
  "--from-literal=backup-username=$BACKUP_ROLE" \
  "--from-literal=backup-password=$DB_BACKUP_PASSWORD"

apply_secret redis-credentials \
  "--from-literal=password=$REDIS_PASSWORD"

# STOMP has its own credential keys so the relay login can be rotated
# independently of the AMQP pool user; they default to the AMQP pair, which is
# what the operator-managed broker actually accepts.
#
# DEF-29-4 — THE THREE-WAY SPLIT, AND WHY THE STANZA IS BUILT ONCE.
#
#   This one Secret is read by TWO consumers that share NO key:
#     - the RabbitMQ cluster-operator reads ONLY `default_user.conf`. Under
#       `secretBackend.externalSecret` (k8s/base/rabbitmq-cluster.yaml) it
#       projects EXACTLY that one key — read at the pinned tag v2.22.3,
#       internal/resource/statefulset.go:958-983 — and mounts it at
#       /etc/rabbitmq/conf.d/11-default_user.conf. It is what defines the
#       broker's default user.
#     - core-java reads ONLY `username` / `password`
#       (k8s/base/core-java-deployment.yaml, RABBITMQ_USER / RABBITMQ_PASSWORD).
#
#   If those two disagree, the broker's default user is not the identity the
#   application is injected with, and EVERY AMQP and STOMP connection is refused
#   with ACCESS_REFUSED — on a cluster where the CR reports Ready, the pod passes
#   its probes, the NetworkPolicy permits the traffic and every static gate is
#   green. The platform reports a MESSAGING failure caused by a SECRET-SHAPE
#   omission, which is the most expensive kind of wrong place to look.
#
#   So the stanza is generated ONCE, here, from the SAME shell variables the flat
#   keys below use. Generating the pair twice — or hand-typing it into the ini
#   text — is precisely the divergence this exists to prevent, so the agreement
#   has to be STRUCTURAL rather than clerical. printf, not echo: the value is
#   newline-bearing and must survive argv intact.
#
#   THE TRAILING SENTINEL IS LOAD-BEARING, NOT A TYPO. `$(...)` strips ALL
#   trailing newlines from its output, so the obvious spelling of this — a plain
#   `$(printf '...\n')` — silently yields a conf file with NO final newline (55
#   bytes where the operator's own format is 56; measured, not assumed, via
#   `kubectl create secret --dry-run=client -o json` and a byte count of the
#   decoded value). The two lines are still separated, so the mismatch is
#   invisible to an eyeball and to a line-by-line comparison. Appending a
#   sentinel character and stripping it with ${var%x} is what preserves the byte
#   the shell would otherwise eat, so the value matches the format RabbitMQ's
#   line-oriented conf parser is handed by the operator itself.
RABBITMQ_DEFAULT_USER_CONF="$(printf 'default_user = %s\ndefault_pass = %s\n.' \
  "$RABBITMQ_USER" "$RABBITMQ_PASSWORD")"
RABBITMQ_DEFAULT_USER_CONF="${RABBITMQ_DEFAULT_USER_CONF%.}"

apply_secret rabbitmq-credentials \
  "--from-literal=username=$RABBITMQ_USER" \
  "--from-literal=password=$RABBITMQ_PASSWORD" \
  "--from-literal=default_user.conf=$RABBITMQ_DEFAULT_USER_CONF" \
  "--from-literal=stomp-login=${STOMP_CLIENT_LOGIN:-$RABBITMQ_USER}" \
  "--from-literal=stomp-passcode=${STOMP_CLIENT_PASSCODE:-$RABBITMQ_PASSWORD}"

# db-username/db-password: Keycloak runs IN-CLUSTER in staging (D-02) against its
# OWN database on the same managed server. It reuses the owner role because it
# creates and migrates its own schema in that database.
apply_secret keycloak-credentials \
  "--from-literal=admin-username=$KEYCLOAK_ADMIN" \
  "--from-literal=admin-password=$KEYCLOAK_ADMIN_PASSWORD" \
  "--from-literal=frontend-client-secret=$KEYCLOAK_CLIENT_SECRET" \
  "--from-literal=db-username=$OWNER_ROLE" \
  "--from-literal=db-password=$KC_DB_PASSWORD"

apply_secret nextauth-secret \
  "--from-literal=secret=$NEXTAUTH_SECRET"

# D-11 / D-12: REAL AWS credentials, and two SEPARATE grants — the media pair is
# GetObject/PutObject/DeleteObject on the media bucket, the backup pair is
# PutObject/ListBucket/DeleteObject on the backup bucket. Locally both carried the
# same MinIO root credentials; here that would hand the media path the ability to
# delete every database dump.
apply_secret s3-media-credentials \
  "--from-literal=access-key=$AWS_MEDIA_ACCESS_KEY_ID" \
  "--from-literal=secret-key=$AWS_MEDIA_SECRET_ACCESS_KEY"

apply_secret s3-backup-credentials \
  "--from-literal=access-key=$AWS_BACKUP_ACCESS_KEY_ID" \
  "--from-literal=secret-key=$AWS_BACKUP_SECRET_ACCESS_KEY"

# D-17 — the phase's entire point. `from`/`to` are not credentials; they live
# here so the Alertmanager config plan 29-07 writes has exactly ONE source for
# the whole destination, and so a missing To refuses at bootstrap instead of
# sending nowhere in silence.
apply_secret alertmanager-smtp \
  "--from-literal=username=$ALERTMANAGER_SMTP_FROM" \
  "--from-literal=password=$ALERTMANAGER_SMTP_PASSWORD" \
  "--from-literal=from=$ALERTMANAGER_SMTP_FROM" \
  "--from-literal=to=$ALERTMANAGER_SMTP_TO"

apply_secret grafana-admin \
  "--from-literal=username=$GRAFANA_ADMIN_USER" \
  "--from-literal=password=$GRAFANA_ADMIN_PASSWORD"

if [ -n "${NOTIFICATION_UNSUBSCRIBE_SECRET:-}" ]; then
  apply_secret notification-credentials \
    "--from-literal=unsubscribe-signing-secret=$NOTIFICATION_UNSUBSCRIBE_SECRET"
else
  skip_secret notification-credentials "NOTIFICATION_UNSUBSCRIBE_SECRET is empty; the manifest ref is optional, so one-click unsubscribe stays inert. NOTE: its application.yml default is the EMPTY STRING, so an HMAC over an empty key is forgeable — set it in any environment that sends notification email"
fi

if [ -n "${STRIPE_API_KEY:-}" ]; then
  apply_secret stripe-credentials \
    "--from-literal=api-key=$STRIPE_API_KEY" \
    "--from-literal=webhook-secret=${STRIPE_WEBHOOK_SECRET:-}"
else
  skip_secret stripe-credentials "STRIPE_API_KEY is empty; the manifest ref is optional, so payments stay inert in staging"
fi

# --- the conditional imagePullSecret, decided by the RECORD not by a guess ---
# Assumption A5 was resolved by MEASUREMENT in plan 29-01 (§7.1): an anonymous
# registry probe against all three jtoye packages returned HTTP 200, with a valid
# negative control (the snackpass-* packages provably exist and return NO-TOKEN).
# An unreadable value here is VOID, never "assume public": guessing public when a
# package is private produces ImagePullBackOff on every pod, and guessing private
# when it is public demands a PAT nobody needs.
GHCR_CELL=""
if [ -f "$DECISIONS_FILE" ]; then
  GHCR_CELL="$(awk -F '|' '/^\|/ { c = $2; gsub(/`/, "", c); gsub(/^[[:space:]]+|[[:space:]]+$/, "", c); if (c == "GHCR_VISIBILITY") { print $3; exit } }' "$DECISIONS_FILE")"
fi
case "$GHCR_CELL" in
  *PUBLIC*)
    skip_secret ghcr-pull "the decision record records GHCR_VISIBILITY as PUBLIC (assumption A5, resolved by an anonymous registry probe with a valid negative control) — no imagePullSecret is required"
    ;;
  *PRIVATE*)
    for var in GHCR_USERNAME GHCR_TOKEN; do
      [ -n "${!var:-}" ] || refuse "value-preflight" "the decision record says the GHCR packages are PRIVATE, so ${var} is required to create the ghcr-pull imagePullSecret"
    done
    kubectl create secret docker-registry ghcr-pull \
      --docker-server=ghcr.io \
      --docker-username="$GHCR_USERNAME" \
      --docker-password="$GHCR_TOKEN" \
      --dry-run=client -o yaml | staging_kubectl apply -n "$NS" -f - >/dev/null
    CREATED+=("ghcr-pull")
    ;;
  *)
    void "could not read GHCR_VISIBILITY from ${DECISIONS_FILE}. Whether an imagePullSecret is needed is not a thing to guess: guessing public on a private package is ImagePullBackOff on every pod, and guessing private on a public one demands a token nobody has."
    ;;
esac

skip_secret smtp-credentials "D-13 — app outbound email in staging is captured by the in-cluster Mailhog, which takes no auth. The ALERT path is separate and real: see alertmanager-smtp above"

# ---------------------------------------------------------------------------
# STEP 7 — summary. Key NAMES only.
# ---------------------------------------------------------------------------
echo
echo "=== staging bootstrap summary (context ${KUBE_CONTEXT}, namespace ${NS}) ==="
echo "database : ${PLATFORM_DB} on ${STAGING_DB_HOST}:${STAGING_DB_PORT} (sslmode=${PGSSLMODE_STAGING})"
echo "roles    : ${OWNER_ROLE} (owner/migrator), ${RUNTIME_ROLE} (rolbypassrls=f, rolsuper=f), ${BACKUP_ROLE} (rolbypassrls=t)"
echo "secrets created (${#CREATED[@]}):"
for s in "${CREATED[@]}"; do
  case "$s" in
    postgres-credentials)     echo "  - $s: host, port, database, username, password, runtime-username, runtime-password, backup-username, backup-password" ;;
    redis-credentials)        echo "  - $s: password" ;;
    rabbitmq-credentials)     echo "  - $s: username, password, default_user.conf (operator-only), stomp-login, stomp-passcode" ;;
    keycloak-credentials)     echo "  - $s: admin-username, admin-password, frontend-client-secret, db-username, db-password" ;;
    nextauth-secret)          echo "  - $s: secret" ;;
    s3-media-credentials)     echo "  - $s: access-key, secret-key" ;;
    s3-backup-credentials)    echo "  - $s: access-key, secret-key" ;;
    alertmanager-smtp)        echo "  - $s: username, password, from, to" ;;
    grafana-admin)            echo "  - $s: username, password" ;;
    notification-credentials) echo "  - $s: unsubscribe-signing-secret" ;;
    stripe-credentials)       echo "  - $s: api-key, webhook-secret" ;;
    ghcr-pull)                echo "  - $s: .dockerconfigjson" ;;
    *)                        echo "  - $s" ;;
  esac
done
echo "secrets skipped (${#SKIPPED[@]}):"
for s in "${SKIPPED[@]}"; do echo "  - $s"; done
echo
echo "PASS: staging bootstrap complete and safe to re-run."
