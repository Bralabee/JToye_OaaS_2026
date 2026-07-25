#!/usr/bin/env bash
# k8s-local-secrets.sh — idempotent out-of-band bootstrap for the local
# Kubernetes rehearsal (Phase 26 / INFRA-01; decisions D-01, D-02, D-03, D-04).
#
# WHAT IT CREATES (all idempotent, safe to re-run)
#   1. the local namespace, if absent
#   2. the BYPASSRLS `jtoye_backup` dump role in the host dev Postgres, by
#      running infra/backups/create-backup-role.sql — this file NEVER writes its
#      own role SQL, it invokes that one, so there is exactly one definition of
#      the role's privileges
#   3. the backup bucket in host MinIO, with NO public-read policy
#   4. every Secret the base manifests consume, rendered client-side and applied
#
# WHY THIS IS A SCRIPT AND NOT A DOC STEP
#   The 2026-07-14 first-live-deploy rehearsal reached 11/11 pods READY through a
#   hand-typed imperative sequence that lived nowhere in the repository. This
#   file is that knowledge, in git, reviewable and re-runnable.
#
# WHY THE SECRETS ARE IMPERATIVE AND NOT KUSTOMIZE RESOURCES
#   k8s/scripts/check-no-plaintext-secrets.sh auto-discovers every kustomization
#   and fails the build on any `kind: Secret` in the render (#100), so
#   `secretGenerator` is not an option for k8s/local. Secrets therefore arrive
#   out-of-band from here, sourced from the gitignored .env, and nothing this
#   file creates is ever a kustomize resource.
#
# NO ENVIRONMENT-VARYING LITERALS
#   No host or port literal appears below (GLOBAL_RULE_6 / ARCHITECTURE_RULE_8).
#   Every one comes from the K8S_LOCAL_* keys in .env, so a compose published-port
#   shift is a one-file .env fix. The namespace comes from the local
#   kustomization, which is its single source of truth.
#
# SECURITY
#   Mirrors scripts/verify-env.sh: variable NAMES only, never a value. Secret
#   values reach only a literal argument or the injected SQL variable; nothing
#   echoes them. (Values are briefly visible in argv to a local `ps` — accepted,
#   threat T-26-28: this is the mandated D-01 pattern, k8s/QUICK_START.md already
#   documents it, and the host is a single-user development machine.)
#
# AUTHORED IN PLAN 26-05, FIRST EXECUTED IN PLAN 26-07
#   Steps 2-5 mutate SHARED state: an RLS-bypassing role on the dev Postgres, a
#   bucket in host MinIO, and cluster objects. That needs the human approval plan
#   26-07's checkpoint carries, so plan 26-05 authored and statically verified
#   this file WITHOUT ever invoking it (26-REVIEWS.md Adjudication J). Plan 26-05
#   proved the refusal two ways that mutate nothing by construction: the guard
#   calls all precede every mutating call in this file, and the identical guard
#   sequence was exercised function-level from the library.
#
# USAGE
#   scripts/k8s-local-secrets.sh
#   (normally invoked for you by scripts/k8s-local-up.sh, which is the single
#   bring-up entry point)
#
# EXIT CODES: 0 = done, 1 = a guard refused or a verification failed,
#             2 = parse / tooling failure.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=scripts/lib/k8s-local-guards.sh
. "$SCRIPT_DIR/lib/k8s-local-guards.sh"

echo "=== J'Toye local Kubernetes bootstrap: secrets + backup role + bucket ==="

# ---------------------------------------------------------------------------
# STEP 1 — GUARDS. Every one of these precedes every mutating call below, so a
# refusal is provably a no-op.
# ---------------------------------------------------------------------------
k8s_local_load_env
k8s_local_assert_context
k8s_local_assert_compose_xor

# ---------------------------------------------------------------------------
# STEP 1b — value preflight. Fail loud, by NAME, before anything is created:
# a half-bootstrapped cluster is worse than one that refused to start.
# ---------------------------------------------------------------------------
REQUIRED_VALUES=(
  POSTGRES_USER
  POSTGRES_DB
  DB_USER
  DB_PASSWORD
  DB_BACKUP_PASSWORD
  REDIS_PASSWORD
  RABBITMQ_USER
  RABBITMQ_PASSWORD
  KEYCLOAK_ADMIN
  KEYCLOAK_ADMIN_PASSWORD
  KEYCLOAK_CLIENT_SECRET
  NEXTAUTH_SECRET
  MINIO_ROOT_USER
  MINIO_ROOT_PASSWORD
)
missing=0
for var in "${REQUIRED_VALUES[@]}"; do
  if [ -z "${!var:-}" ]; then
    echo "MISSING: ${var} is unset or empty in .env" >&2
    missing=$((missing + 1))
  fi
done
[ "$missing" -eq 0 ] || {
  echo "REFUSED [value-preflight]: ${missing} required variable(s) missing — see the names above" >&2
  exit 1
}
case "${DB_BACKUP_PASSWORD}" in
  CHANGE_ME*)
    echo "REFUSED [weak-value]: DB_BACKUP_PASSWORD is still the .env.example placeholder. Generate one with: openssl rand -hex 32" >&2
    exit 1
    ;;
esac
echo "OK: all ${#REQUIRED_VALUES[@]} required values present and DB_BACKUP_PASSWORD is not the placeholder"

NS="$(k8s_local_namespace)"
echo "OK: target namespace ${NS} (parsed from the local kustomization)"

# Container names come from docker-compose.full-stack.yml's `container_name:`
# fields — in-repo constants, not environment-varying endpoints, and using them
# means no host psql/mc client and no published-port literal is needed.
PG_CONTAINER="jtoye-postgres"
MINIO_CONTAINER="jtoye-minio"
# In-network DNS name of the MinIO service on the compose network.
MINIO_SERVICE="minio"
ROLE_SQL="$REPO_ROOT/infra/backups/create-backup-role.sql"

# The dump role's NAME is defined by the SQL file, so parse it from there rather
# than restating it — one definition, no drift.
BACKUP_ROLE="$(grep -oE "rolname = '[a-z_]+'" "$ROLE_SQL" | head -1 | sed -E "s/.*'([a-z_]+)'/\1/")"
[ -n "$BACKUP_ROLE" ] || {
  echo "PARSE ERROR: could not read the dump role name from ${ROLE_SQL}" >&2
  exit 2
}

# ---------------------------------------------------------------------------
# Helpers. Defined AFTER the guards on purpose: a helper body containing a
# mutating call must not sit at a lower line number than the guards, or the
# "guards precede every mutation" source assertion becomes unfalsifiable.
# ---------------------------------------------------------------------------
CREATED=()
SKIPPED=()

apply_secret() {
  # apply_secret <name> <--from-literal=k=v>...
  # D-01's mandated idempotent pattern: render client-side, then apply. No delete
  # window, no error on re-run, and nothing becomes a kustomize resource.
  local name="$1"; shift
  kubectl create secret generic "$name" "$@" --dry-run=client -o yaml | k8s_local_kubectl apply -n "$NS" -f - >/dev/null
  CREATED+=("$name")
}

skip_secret() {
  # skip_secret <name> <reason>
  SKIPPED+=("$1 — $2")
  echo "SKIP: Secret ${1} not created — ${2}"
}

# ---------------------------------------------------------------------------
# STEP 2 — namespace (idempotent)
# ---------------------------------------------------------------------------
echo "--- namespace ---"
k8s_local_kubectl apply -f "$REPO_ROOT/k8s/local/namespace.yaml" >/dev/null
echo "OK: namespace ${NS} present"

# ---------------------------------------------------------------------------
# STEP 3 — BYPASSRLS dump role (D-02)
#
# WHY: the tenant tables use FORCE ROW LEVEL SECURITY, which applies RLS even to
# the table owner, so a pg_dump as the app role with no tenant GUC silently
# captures ZERO rows from every tenant-scoped table — the backup rehearsal would
# "pass" on an empty dump. Nothing (not compose, not Flyway) provisions this
# role: BYPASSRLS can only be granted by a superuser.
#
# The SQL is INVOKED, never reimplemented — see infra/backups/create-backup-role.sql.
# ---------------------------------------------------------------------------
echo "--- ${BACKUP_ROLE} dump role ---"
docker exec -i "$PG_CONTAINER" psql \
  -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v ON_ERROR_STOP=1 \
  -v backup_password="$DB_BACKUP_PASSWORD" \
  -f - < "$ROLE_SQL" >/dev/null

# VERIFY from the DB side, so a silently-failed bootstrap cannot masquerade as
# success and hand the CronJob a zero-row dump.
ROLE_BYPASSRLS="$(docker exec "$PG_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -tAc "SELECT rolbypassrls FROM pg_roles WHERE rolname = '${BACKUP_ROLE}'" | tr -d '[:space:]')"
if [ "$ROLE_BYPASSRLS" != "t" ]; then
  echo "FAIL: role ${BACKUP_ROLE} is missing or does not have rolbypassrls (got: '${ROLE_BYPASSRLS:-<absent>}'). A dump under this role would capture ZERO rows from every FORCE-RLS table." >&2
  exit 1
fi
echo "OK: role ${BACKUP_ROLE} exists with rolbypassrls = t"

# ---------------------------------------------------------------------------
# STEP 4 — backup bucket in host MinIO
#
# Mechanism mirrors the compose `minio-init` service: the minio/mc image on the
# compose network, credentials expanded INSIDE the container from the environment
# (the established in-repo pattern). The container port is resolved from the
# running container by looking up the published port .env declares, so no port
# literal is needed AND the lookup doubles as a check that .env's MinIO port is
# really the one that container publishes.
#
# NOTE: unlike the images bucket, this one gets NO public-read anonymous-download
# policy. Database dumps must not be world-readable (threat T-26-26).
# ---------------------------------------------------------------------------
echo "--- backup bucket ${K8S_LOCAL_BACKUP_BUCKET} ---"
MINIO_NETWORK="$(docker inspect "$MINIO_CONTAINER" \
  --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{"\n"}}{{end}}' | head -1)"
[ -n "$MINIO_NETWORK" ] || { echo "PARSE ERROR: could not resolve the compose network of ${MINIO_CONTAINER}" >&2; exit 2; }

MINIO_CTR_PORT="$(docker inspect "$MINIO_CONTAINER" \
  --format "{{range \$p, \$b := .NetworkSettings.Ports}}{{range \$b}}{{if eq .HostPort \"${K8S_LOCAL_MINIO_PORT}\"}}{{\$p}}{{\"\n\"}}{{end}}{{end}}{{end}}" | head -1)"
MINIO_CTR_PORT="${MINIO_CTR_PORT%%/*}"
[ -n "$MINIO_CTR_PORT" ] || {
  echo "PARSE ERROR: ${MINIO_CONTAINER} publishes no port matching K8S_LOCAL_MINIO_PORT — check that key against docker-compose.full-stack.yml" >&2
  exit 2
}

docker run --rm --network "$MINIO_NETWORK" \
  -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD \
  -e MC_BUCKET="$K8S_LOCAL_BACKUP_BUCKET" \
  -e MC_URL="http://${MINIO_SERVICE}:${MINIO_CTR_PORT}" \
  --entrypoint /bin/sh "minio/mc:${MINIO_MC_IMAGE_TAG:-latest}" -c '
    set -e
    mc alias set bootstrap "$MC_URL" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" > /dev/null
    mc mb --ignore-existing "bootstrap/$MC_BUCKET"
    # Verify with mc itself, NOT `mc ls | grep`: the minio/mc image is minimal and
    # ships no grep (nor sed nor awk), so the piped form died with
    # "grep: command not found" — AFTER mc had already created the bucket, which
    # left the bootstrap half-applied (role + bucket created, no Secrets) and
    # reported a failure for a step that had actually succeeded. `mc ls <bucket>`
    # exits 0 when the bucket exists and 1 when it does not, which is the whole
    # assertion with no external binary. (Found in plan 26-07, the first execution
    # of this script.)
    mc ls "bootstrap/$MC_BUCKET" > /dev/null
  '
echo "OK: bucket ${K8S_LOCAL_BACKUP_BUCKET} exists in host MinIO (no public-read policy applied)"

# ---------------------------------------------------------------------------
# STEP 5 — Secrets
#
# The list is exactly the secret names + keys the RENDERED k8s/local overlay
# consumes (every `secretKeyRef` in the client-side kustomize render of
# k8s/local). Two are conditional on a non-empty source value; smtp-credentials
# is deliberately absent (Mailhog takes no auth, and plan 26-02 made that
# manifest ref `optional: true`).
#
# DEF-2: postgres-credentials `username` is bound to DB_USER (the NOSUPERUSER
# application role), NEVER to POSTGRES_USER (the superuser). Injecting the
# superuser would silently bypass every RLS policy in the cluster.
# ---------------------------------------------------------------------------
echo "--- secrets ---"

apply_secret postgres-credentials \
  "--from-literal=host=$K8S_LOCAL_POD_HOST" \
  "--from-literal=port=$K8S_LOCAL_DB_PORT" \
  "--from-literal=database=$POSTGRES_DB" \
  "--from-literal=username=$DB_USER" \
  "--from-literal=password=$DB_PASSWORD" \
  "--from-literal=backup-username=$BACKUP_ROLE" \
  "--from-literal=backup-password=$DB_BACKUP_PASSWORD"

apply_secret redis-credentials \
  "--from-literal=password=$REDIS_PASSWORD"

# STOMP has its own credential keys so the relay login can be rotated
# independently of the AMQP pool user; they default to the AMQP pair, which is
# what the compose broker actually accepts.
apply_secret rabbitmq-credentials \
  "--from-literal=username=$RABBITMQ_USER" \
  "--from-literal=password=$RABBITMQ_PASSWORD" \
  "--from-literal=stomp-login=${STOMP_CLIENT_LOGIN:-$RABBITMQ_USER}" \
  "--from-literal=stomp-passcode=${STOMP_CLIENT_PASSCODE:-$RABBITMQ_PASSWORD}"

apply_secret keycloak-credentials \
  "--from-literal=admin-username=$KEYCLOAK_ADMIN" \
  "--from-literal=admin-password=$KEYCLOAK_ADMIN_PASSWORD" \
  "--from-literal=frontend-client-secret=$KEYCLOAK_CLIENT_SECRET"

apply_secret nextauth-secret \
  "--from-literal=secret=$NEXTAUTH_SECRET"

# Both S3 secrets carry the MinIO root credentials locally; they stay two objects
# because the manifests reference them separately (media vs backup).
apply_secret s3-backup-credentials \
  "--from-literal=access-key=$MINIO_ROOT_USER" \
  "--from-literal=secret-key=$MINIO_ROOT_PASSWORD"

apply_secret s3-media-credentials \
  "--from-literal=access-key=$MINIO_ROOT_USER" \
  "--from-literal=secret-key=$MINIO_ROOT_PASSWORD"

if [ -n "${NOTIFICATION_UNSUBSCRIBE_SECRET:-}" ]; then
  apply_secret notification-credentials \
    "--from-literal=unsubscribe-signing-secret=$NOTIFICATION_UNSUBSCRIBE_SECRET"
else
  skip_secret notification-credentials "NOTIFICATION_UNSUBSCRIBE_SECRET is empty; the manifest ref is optional, so one-click unsubscribe stays inert"
fi

if [ -n "${STRIPE_API_KEY:-}" ]; then
  apply_secret stripe-credentials \
    "--from-literal=api-key=$STRIPE_API_KEY" \
    "--from-literal=webhook-secret=${STRIPE_WEBHOOK_SECRET:-}"
else
  skip_secret stripe-credentials "STRIPE_API_KEY is empty; the manifest ref is optional, so payments stay inert locally"
fi

skip_secret smtp-credentials "Mailhog accepts any sender with no auth, and the manifest ref was made optional in plan 26-02 — an empty username/password Secret would add nothing"

# ---------------------------------------------------------------------------
# STEP 6 — summary. Key NAMES only.
# ---------------------------------------------------------------------------
echo
echo "=== bootstrap summary (namespace ${NS}) ==="
echo "role   : ${BACKUP_ROLE} (rolbypassrls = t)"
echo "bucket : ${K8S_LOCAL_BACKUP_BUCKET} (private)"
echo "secrets created (${#CREATED[@]}):"
for s in "${CREATED[@]}"; do
  case "$s" in
    postgres-credentials)   echo "  - $s: host, port, database, username, password, backup-username, backup-password" ;;
    redis-credentials)      echo "  - $s: password" ;;
    rabbitmq-credentials)   echo "  - $s: username, password, stomp-login, stomp-passcode" ;;
    keycloak-credentials)   echo "  - $s: admin-username, admin-password, frontend-client-secret" ;;
    nextauth-secret)        echo "  - $s: secret" ;;
    s3-backup-credentials)  echo "  - $s: access-key, secret-key" ;;
    s3-media-credentials)   echo "  - $s: access-key, secret-key" ;;
    notification-credentials) echo "  - $s: unsubscribe-signing-secret" ;;
    stripe-credentials)     echo "  - $s: api-key, webhook-secret" ;;
    *)                      echo "  - $s" ;;
  esac
done
echo "secrets skipped (${#SKIPPED[@]}):"
for s in "${SKIPPED[@]}"; do echo "  - $s"; done
echo
echo "PASS: local bootstrap complete and safe to re-run."
