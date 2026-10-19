#!/usr/bin/env bash
# staging-db-bootstrap.sh — the role shape a FRESH managed PostgreSQL needs
# before core-java first boots, and after its first migration
# (Phase 29 / DPLY-01, DPLY-04; decisions D-10, D-25; Blocker E; threats
# T-29-22, T-29-23).
#
# WHY THE ORDER IS FIXED (Blocker E)
#   k8s never wired the Phase 28 runtime/migrator split. On a fresh database:
#   DatabaseConfigurationValidator refuses a DB user that owns any public table,
#   and Flyway as the DML-only runtime role cannot migrate. So the OWNER
#   (jtoye_app, Flyway's user) and the RUNTIME role (jtoye_runtime, the Hikari
#   pool's user, with default privileges granted FOR ROLE jtoye_app) must exist
#   BEFORE the first boot. infra/db/create-runtime-role.sql cannot simply run
#   first: its GRANT TRUNCATE ON postcode_centroid fails on an empty database
#   (V61 creates that table). Hence two stages:
#     --stage pre    infra/db/managed-pre-bootstrap.sql, before the first boot:
#                    jtoye_app, jtoye_runtime + default privileges, keycloak
#                    owning database keycloak and its public schema, uuid-ossp
#     --stage post   after the first migration: the two existing role files,
#                    infra/db/create-runtime-role.sql and
#                    infra/backups/create-backup-role.sql, through a \getenv
#                    preamble and \ir — the same files compose uses
#     --stage monitor  jtoye_monitor LOGIN IN ROLE pg_monitor (plan 29-23)
#     --stage readback roles (rolsuper, rolbypassrls), \ddp, database and
#                    public-schema owners, max_connections, printed raw
#
# THE MANAGED-SERVER ADMIN IS NOT A SUPERUSER
#   azure_pg_admin is a pseudo-superuser. Measured on postgres:18 with a
#   CREATEROLE non-superuser standing in for it (this script's --selftest):
#     - a CREATEROLE creator gets ADMIN on the roles it creates but NOT SET, so
#       handing it ownership ("ALTER SCHEMA public OWNER TO jtoye_app") or
#       setting default privileges FOR it fails "must be able to SET ROLE"
#       until the admin grants itself membership (GRANT jtoye_app TO
#       CURRENT_USER). The admin therefore stays a member of jtoye_app and
#       keycloak; a re-run's ALTER statements need it
#     - ANY ALTER ROLE that names the SUPERUSER attribute, NOSUPERUSER included,
#       is refused ("Only roles with the SUPERUSER attribute may change the
#       SUPERUSER attribute"), which is why create-runtime-role.sql applies
#       NOSUPERUSER only when the role actually is a superuser
#
# WHERE psql RUNS
#   Always inside a postgres:18 container (the host's client binaries are 16.x
#   and cannot read an 18 server's dumps). Passwords reach psql only through the
#   container ENVIRONMENT (`docker run -e NAME`, the value taken from this
#   process's environment) and `\getenv` — never through argv, so neither a
#   process listing nor `docker inspect`'s Args shows one (--selftest arm
#   no-password-in-argv). Real mode verifies the server certificate
#   (sslmode=verify-full sslrootcert=system); the postgres:18 image ships no CA
#   bundle (/usr/lib/ssl/cert.pem is a dangling link to
#   /etc/ssl/certs/ca-certificates.crt), so the host's bundle is mounted
#   read-only at that path.
#
# MODES
#   real mode (default)   the staging server. Sources scripts/lib/azure-guard.sh
#                         and runs az_guard_init in THIS shell
#                         (JTOYE_AZURE_SUBSCRIPTION_ID from the caller; refused
#                         rc 1 before any read without it), reads PG_ADMIN_LOGIN
#                         from the decision record and every password from Key
#                         Vault jtoye-stg-kv into mode-600 files:
#                           pg-admin--password                       admin (PGPASSWORD)
#                           postgres-credentials--migration-password jtoye_app
#                           postgres-credentials--password           jtoye_runtime
#                           postgres-credentials--backup-password    jtoye_backup
#                           keycloak-server-credentials--db-password keycloak
#                           postgres-exporter-credentials--password  jtoye_monitor
#   --test-container NAME stand-in mode: a local postgres container on a user
#                         network, host = NAME, sslmode=disable. Reads NO Key
#                         Vault value and never calls az_guard_init; takes
#                         PG_ADMIN_LOGIN, PGPASSWORD and the role passwords from
#                         the caller's environment
#   --selftest            starts and removes its own postgres:18 stand-in and
#                         runs the named arms below, one line each
#   --via-context CTX     reach the server THROUGH the cluster (both modes).
#                         The server's firewall admits exactly one address, the
#                         cluster's outbound IP (T-29-34), so psql from an
#                         operator host times out (measured 2026-10-19). With
#                         this flag the script, after staging_kube_guard CTX
#                         (scripts/lib/staging-kube-guard.sh: sipbihs2aks and any
#                         context not pointing at the staging API server are
#                         refused before any cluster call), starts ONE relay pod
#                         in namespace default (busybox nc, pinned by digest;
#                         default has no NetworkPolicy, jtoye-staging is
#                         default-deny), port-forwards a random loopback port to
#                         it (PID captured at start), and runs psql on the host
#                         network with hostaddr=127.0.0.1 and that port. The
#                         relay carries only the TLS stream: verify-full still
#                         checks the certificate against --host, and no password
#                         or SQL ever enters the cluster or a pod spec. The
#                         port-forward is killed by its PID and the pod deleted
#                         by name on every exit path.
#
# USAGE
#   JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94 \
#     scripts/staging-db-bootstrap.sh --stage pre|post|monitor|readback [--host H] [--decisions PATH] \
#       [--via-context jtoye-stg-aks]
#   PG_ADMIN_LOGIN=… PGPASSWORD=… JTOYE_APP_PASSWORD=… … \
#     scripts/staging-db-bootstrap.sh --test-container NAME --stage pre|post|monitor|readback
#   scripts/staging-db-bootstrap.sh --selftest
#     --host H          default jtoye-stg-pg.postgres.database.azure.com
#     --decisions PATH  default .planning/phases/29-deployable-staging-with-its-own-monitoring/29-ESTATE-DECISIONS.md
#   Environment by stage (stand-in mode; real mode fills them from Key Vault):
#     pre      PGPASSWORD JTOYE_APP_PASSWORD JTOYE_RUNTIME_PASSWORD KEYCLOAK_DB_PASSWORD
#     post     PGPASSWORD JTOYE_RUNTIME_PASSWORD JTOYE_BACKUP_PASSWORD
#     monitor  PGPASSWORD JTOYE_MONITOR_PASSWORD
#     readback PGPASSWORD
#   BOOTSTRAP_SELFTEST_FORMAT=tap renders the selftest as TAP 13.
#
# SELFTEST ARMS (PASS <arm> … or FAIL <arm> <reason>, one line each)
#   pre-idempotent              --stage pre twice: rc 0 both; role list, memberships,
#                               \ddp, owners and ACLs identical by content; a planted
#                               extra default GRANT makes the same diff non-empty
#                               (control), and revoking it restores the capture
#   owners-can-create           AS keycloak in database keycloak and AS jtoye_app in
#                               database jtoye, CREATE TABLE succeeds (the first-boot
#                               path of Keycloak and Flyway), over TCP with SCRAM
#   post-before-migration-fails --stage post before postcode_centroid exists exits
#                               non-zero naming that table (Blocker E's ordering)
#   post-readback               after a simulated first migration, --stage post rc 0;
#                               readback rolbypassrls jtoye_backup t, jtoye_runtime f,
#                               jtoye_app f; jtoye_runtime and jtoye_backup can read
#                               the migrated table; the readback names jtoye_app as
#                               the installer of the (simulated) flyway history
#   via-context-relay           --via-context st-ok (stub kubectl): readback reaches the
#                               stand-in through hostaddr=127.0.0.1 and the port the
#                               stub port-forward announces; exactly one relay pod
#                               run, one port-forward and one delete of the SAME pod;
#                               the port-forward process is gone afterwards; and
#                               --via-context sipbihs2aks is REFUSED rc 1 with 0 runs
#   no-password-in-argv         no password appears in docker inspect's Path/Args/Cmd
#                               of any psql container this script started
#
# EXIT CODES: 0 = done, 1 = refused or a SQL/verification failure,
#             2 = usage / tooling / VOID.

set -euo pipefail

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(dirname "$SELF")"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

refuse() { local arm="$1"; shift; echo "REFUSED [$arm]: $*" >&2; exit 1; }
die()    { echo "FAIL: $*" >&2; exit 1; }
void()   { echo "VOID: $*" >&2; exit 2; }

usage() {
  sed -n '/^# USAGE/,/^# SELFTEST ARMS/p' "$SELF" | sed '$d' | sed 's/^# \{0,1\}//'
}

PG_IMAGE="postgres:18"
DEFAULT_HOST="jtoye-stg-pg.postgres.database.azure.com"
KV_NAME="jtoye-stg-kv"
HOST_CA_BUNDLE="/etc/ssl/certs/ca-certificates.crt"
# The relay (--via-context): busybox 1.37 by its multi-arch index digest, in a
# namespace with no NetworkPolicy.
RELAY_IMAGE="busybox@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e"
RELAY_NAMESPACE="default"
RELAY_READY_SECONDS="${BOOTSTRAP_RELAY_READY_SECONDS:-180}"
VIA_CONTEXT=""
RELAY_POD=""
RELAY_PORT=""
PF_PID=""
SECRET_DIR=""
ROLES_READBACK="'jtoye_app','jtoye_runtime','jtoye_backup','keycloak','jtoye_monitor'"

# ---------------------------------------------------------------------------
# argument parsing
# ---------------------------------------------------------------------------
STAGE=""
HOST="$DEFAULT_HOST"
TEST_CONTAINER=""
SELFTEST=0
DECISIONS_FILE="$REPO_ROOT/.planning/phases/29-deployable-staging-with-its-own-monitoring/29-ESTATE-DECISIONS.md"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --stage)
      shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --stage needs a name" >&2; exit 2; }
      STAGE="$1" ;;
    --host)
      shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --host needs a name" >&2; exit 2; }
      HOST="$1" ;;
    --test-container)
      shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --test-container needs a container name" >&2; exit 2; }
      TEST_CONTAINER="$1" ;;
    --decisions)
      shift; [ "$#" -gt 0 ] || { echo "USAGE ERROR: --decisions needs a path" >&2; exit 2; }
      DECISIONS_FILE="$1" ;;
    --via-context)
      shift; [ "$#" -gt 0 ] && [ -n "$1" ] || { echo "USAGE ERROR: --via-context needs a kube context name" >&2; exit 2; }
      VIA_CONTEXT="$1" ;;
    --selftest) SELFTEST=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "USAGE ERROR: unknown argument '$1'" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

# ---------------------------------------------------------------------------
# the stage SQL that is not a file of its own
# ---------------------------------------------------------------------------
# post: the two existing role files, UNCHANGED, behind a \getenv preamble (the
# files read :'runtime_password' and :'backup_password').
post_sql() {
  cat <<'SQL'
\set ON_ERROR_STOP on
\getenv runtime_password JTOYE_RUNTIME_PASSWORD
\if :{?runtime_password}
\else
DO $$ BEGIN RAISE EXCEPTION 'JTOYE_RUNTIME_PASSWORD is not set in the psql environment'; END $$;
\endif
\ir /sql/db/create-runtime-role.sql
\getenv backup_password JTOYE_BACKUP_PASSWORD
\if :{?backup_password}
\else
DO $$ BEGIN RAISE EXCEPTION 'JTOYE_BACKUP_PASSWORD is not set in the psql environment'; END $$;
\endif
\ir /sql/backups/create-backup-role.sql
SQL
}

monitor_sql() {
  cat <<'SQL'
\set ON_ERROR_STOP on
\getenv monitor_password JTOYE_MONITOR_PASSWORD
\if :{?monitor_password}
\else
DO $$ BEGIN RAISE EXCEPTION 'JTOYE_MONITOR_PASSWORD is not set in the psql environment'; END $$;
\endif
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jtoye_monitor') THEN
    CREATE ROLE jtoye_monitor LOGIN NOSUPERUSER NOBYPASSRLS IN ROLE pg_monitor;
  END IF;
END
$$;
GRANT pg_monitor TO jtoye_monitor;
ALTER ROLE jtoye_monitor WITH LOGIN PASSWORD :'monitor_password';
GRANT CONNECT ON DATABASE jtoye TO jtoye_monitor;
GRANT CONNECT ON DATABASE keycloak TO jtoye_monitor;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jtoye_monitor' AND (rolsuper OR rolbypassrls)) THEN
    RAISE EXCEPTION 'jtoye_monitor is SUPERUSER or BYPASSRLS; a metrics role must be neither';
  END IF;
END
$$;
SQL
}

readback_sql() {
  cat <<SQL
\\set ON_ERROR_STOP on
\\pset footer off
SELECT current_database() AS db, to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS read_at;
SELECT rolname, rolsuper, rolbypassrls, rolcanlogin FROM pg_roles WHERE rolname IN (${ROLES_READBACK}) ORDER BY rolname;
SELECT datname, pg_get_userbyid(datdba) AS owner FROM pg_database WHERE datname IN ('jtoye', 'keycloak') ORDER BY datname;
SELECT current_database() AS db, nspname, pg_get_userbyid(nspowner) AS owner FROM pg_namespace WHERE nspname = 'public';
\\ddp
SHOW max_connections;
\\connect keycloak
SELECT current_database() AS db, nspname, pg_get_userbyid(nspowner) AS owner FROM pg_namespace WHERE nspname = 'public';
SQL
}

# ---------------------------------------------------------------------------
# the psql runner — the one place a psql process is started
# ---------------------------------------------------------------------------
# run_psql DB FILE|- [extra psql args…]. With FILE "-", the SQL is read from
# this function's stdin. Only environment-variable NAMES are passed to docker;
# their values come from this process's environment.
PASS_ENV="PGPASSWORD JTOYE_APP_PASSWORD JTOYE_RUNTIME_PASSWORD KEYCLOAK_DB_PASSWORD JTOYE_BACKUP_PASSWORD JTOYE_MONITOR_PASSWORD"
run_psql() {
  local db="$1" file="$2" conninfo name
  shift 2
  local -a dargs=(run -i)
  if [ "${BOOTSTRAP_KEEP_PSQL:-0}" = 1 ]; then
    # --selftest keeps the exited psql containers so it can docker-inspect them
    # (arm no-password-in-argv), then removes them by label.
    dargs+=(--label "jtoye.bootstrap.run=${BOOTSTRAP_LABEL:-unlabelled}")
  else
    dargs+=(--rm)
  fi
  for name in $PASS_ENV; do
    if [ -n "${!name:-}" ]; then dargs+=(-e "$name"); fi
  done
  dargs+=(-v "$REPO_ROOT/infra:/sql:ro")
  # Through the relay, libpq connects to hostaddr:port (the port-forward on the
  # host's loopback) while host= still names the server, so verify-full checks
  # the certificate against the real name.
  local addr="port=5432"
  if [ -n "$RELAY_PORT" ]; then
    dargs+=(--network host)
    addr="hostaddr=127.0.0.1 port=${RELAY_PORT}"
  elif [ -n "$TEST_CONTAINER" ]; then
    dargs+=(--network "$STANDIN_NETWORK")
  fi
  if [ -n "$TEST_CONTAINER" ]; then
    conninfo="host=${HOST} ${addr} dbname=${db} user=${PG_ADMIN_LOGIN} sslmode=disable connect_timeout=15"
  else
    dargs+=(-v "${HOST_CA_BUNDLE}:/etc/ssl/certs/ca-certificates.crt:ro")
    conninfo="host=${HOST} ${addr} dbname=${db} user=${PG_ADMIN_LOGIN} sslmode=verify-full sslrootcert=system connect_timeout=15"
  fi
  dargs+=("$PG_IMAGE" psql -X -v ON_ERROR_STOP=1 "$@" "$conninfo")
  if [ "$file" = - ]; then
    dargs+=(-f -)
    docker "${dargs[@]}"
  else
    dargs+=(-f "$file")
    docker "${dargs[@]}" </dev/null
  fi
}

# ---------------------------------------------------------------------------
# the relay (--via-context)
# ---------------------------------------------------------------------------
# cleanup runs on EXIT in both modes: the incoming status is captured first and
# re-asserted last, so a VOID (2) or a refusal (1) is never rewritten by it.
cleanup() {
  local rc=$?
  if [ -n "${PF_PID:-}" ]; then
    kill "$PF_PID" 2>/dev/null || true
    wait "$PF_PID" 2>/dev/null || true
  fi
  if [ -n "${RELAY_POD:-}" ]; then
    kubectl --context "$VIA_CONTEXT" -n "$RELAY_NAMESPACE" delete pod "$RELAY_POD" --ignore-not-found --wait=false >/dev/null 2>&1 \
      || echo "WARN: could not delete relay pod ${RELAY_NAMESPACE}/${RELAY_POD}; delete it by that name" >&2
  fi
  [ -z "${SECRET_DIR:-}" ] || rm -rf "$SECRET_DIR"
  exit "$rc"
}

relay_up() {
  local target="$1" pf_log t0 grc=0
  # shellcheck source=scripts/lib/staging-kube-guard.sh
  . "$SCRIPT_DIR/lib/staging-kube-guard.sh" || void "could not source scripts/lib/staging-kube-guard.sh"
  staging_kube_guard "$VIA_CONTEXT" "$REPO_ROOT/k8s/staging/azure-identity.yaml" || grc=$?
  [ "$grc" -eq 0 ] || exit "$grc"
  RELAY_POD="jtoye-dbboot-relay-$(date -u +%Y%m%d%H%M%S)-$$"
  kubectl --context "$VIA_CONTEXT" -n "$RELAY_NAMESPACE" run "$RELAY_POD" --image "$RELAY_IMAGE" --restart=Never \
    --labels app.kubernetes.io/name=jtoye-dbboot-relay --command -- nc -lk -p 5432 -e nc "$target" 5432 >/dev/null \
    || die "could not start relay pod ${RELAY_NAMESPACE}/${RELAY_POD}"
  kubectl --context "$VIA_CONTEXT" -n "$RELAY_NAMESPACE" wait --for=condition=Ready "pod/${RELAY_POD}" --timeout="${RELAY_READY_SECONDS}s" >/dev/null \
    || die "relay pod ${RELAY_NAMESPACE}/${RELAY_POD} not Ready within ${RELAY_READY_SECONDS}s"
  pf_log="$(mktemp "${TMPDIR:-/tmp}/jtoye-dbboot-pf.XXXXXXXX")" || void "mktemp failed"
  kubectl --context "$VIA_CONTEXT" -n "$RELAY_NAMESPACE" port-forward "pod/${RELAY_POD}" :5432 > "$pf_log" 2>&1 &
  PF_PID=$!
  t0=$(date +%s)
  while :; do
    RELAY_PORT="$(sed -nE 's/^Forwarding from 127\.0\.0\.1:([0-9]+) .*/\1/p' "$pf_log" | head -1)"
    [ -z "$RELAY_PORT" ] || break
    if ! kill -0 "$PF_PID" 2>/dev/null; then
      PF_PID=""
      die "the port-forward exited before it announced a port: $(head -c 300 "$pf_log" | tr '\n' ' ')"
    fi
    [ $(( $(date +%s) - t0 )) -lt 30 ] || die "the port-forward announced no port within 30s"
    sleep 1
  done
  rm -f "$pf_log"
  echo "relay: pod ${RELAY_NAMESPACE}/${RELAY_POD} -> ${target}:5432, port-forward pid ${PF_PID} on 127.0.0.1:${RELAY_PORT}"
}

require_env() {
  local name missing=0
  for name in "$@"; do
    if [ -z "${!name:-}" ]; then
      echo "MISSING: ${name} is unset or empty" >&2
      missing=$((missing + 1))
    fi
  done
  [ "$missing" -eq 0 ] || refuse env "${missing} required variable(s) missing for --stage ${STAGE} (names above; values are never printed)"
}

stage_env() {
  case "$1" in
    pre)      echo "PGPASSWORD JTOYE_APP_PASSWORD JTOYE_RUNTIME_PASSWORD KEYCLOAK_DB_PASSWORD" ;;
    post)     echo "PGPASSWORD JTOYE_RUNTIME_PASSWORD JTOYE_BACKUP_PASSWORD" ;;
    monitor)  echo "PGPASSWORD JTOYE_MONITOR_PASSWORD" ;;
    readback) echo "PGPASSWORD" ;;
  esac
}

# The env name -> Key Vault object map (real mode).
kv_object_for() {
  case "$1" in
    PGPASSWORD)             echo "pg-admin--password" ;;
    JTOYE_APP_PASSWORD)     echo "postgres-credentials--migration-password" ;;
    JTOYE_RUNTIME_PASSWORD) echo "postgres-credentials--password" ;;
    JTOYE_BACKUP_PASSWORD)  echo "postgres-credentials--backup-password" ;;
    KEYCLOAK_DB_PASSWORD)   echo "keycloak-server-credentials--db-password" ;;
    JTOYE_MONITOR_PASSWORD) echo "postgres-exporter-credentials--password" ;;
  esac
}

run_stage() {
  case "$STAGE" in
    pre)      run_psql jtoye /sql/db/managed-pre-bootstrap.sql ;;
    post)     post_sql | run_psql jtoye - ;;
    monitor)  monitor_sql | run_psql jtoye - ;;
    readback) readback_sql | run_psql jtoye - -A -F '|' ;;
  esac
}

# ---------------------------------------------------------------------------
# --selftest
# ---------------------------------------------------------------------------
_ST_RESULTS=()
st_result() { _ST_RESULTS+=("$1"); }
st_render() {
  local i=0 r verdict rest
  if [ "${BOOTSTRAP_SELFTEST_FORMAT:-plain}" = tap ]; then
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
  # tmp, run_id, net and cid are GLOBAL on purpose: the EXIT trap runs after this
  # function has returned, when its locals no longer exist.
  local cname i fails=0 rc pwd_var
  command -v docker >/dev/null 2>&1 || void "docker not on PATH: the stand-in cannot run, which is not a pass"
  command -v openssl >/dev/null 2>&1 || void "openssl not on PATH (it generates the stand-in passwords)"
  docker image inspect "$PG_IMAGE" >/dev/null 2>&1 || docker pull -q "$PG_IMAGE" >/dev/null || void "cannot obtain ${PG_IMAGE}"
  tmp="$(umask 077; mktemp -d "${TMPDIR:-/tmp}/jtoye-dbboot-st.XXXXXXXX")" || void "mktemp -d failed"
  run_id="st$$x$RANDOM"
  net="jtoye-dbboot-${run_id}"
  cname="jtoye-dbboot-pg-${run_id}"
  cid=""
  # shellcheck disable=SC2329  # invoked by the trap
  st_cleanup() {
    local ids
    ids="$(docker ps -aq --filter "label=jtoye.bootstrap.run=${run_id:-none}" 2>/dev/null || true)"
    # shellcheck disable=SC2086
    [ -z "$ids" ] || docker rm -f $ids >/dev/null 2>&1 || true
    [ -z "${cid:-}" ] || docker rm -f "$cid" >/dev/null 2>&1 || true
    [ -z "${net:-}" ] || docker network rm "$net" >/dev/null 2>&1 || true
    [ -z "${tmp:-}" ] || rm -rf "$tmp"
  }
  trap st_cleanup EXIT

  # Every password is random per run; all of them are what the no-password-in-argv
  # arm searches docker inspect for.
  for pwd_var in ST_SUPER_PASSWORD PGPASSWORD JTOYE_APP_PASSWORD JTOYE_RUNTIME_PASSWORD KEYCLOAK_DB_PASSWORD JTOYE_BACKUP_PASSWORD JTOYE_MONITOR_PASSWORD; do
    printf -v "$pwd_var" '%s' "stpw$(openssl rand -hex 16)"
    export "${pwd_var?}"
  done
  export PG_ADMIN_LOGIN=fake_admin BOOTSTRAP_KEEP_PSQL=1 BOOTSTRAP_LABEL="$run_id"
  POSTGRES_PASSWORD="$ST_SUPER_PASSWORD"; export POSTGRES_PASSWORD

  docker network create "$net" >/dev/null || void "docker network create failed"
  # Published on a random loopback port as well: arm via-context-relay reaches
  # it there, the way a real run reaches the relay's kubectl port-forward.
  cid="$(docker run -d --name "$cname" --network "$net" -p 127.0.0.1::5432 -e POSTGRES_PASSWORD "$PG_IMAGE")" || void "could not start the ${PG_IMAGE} stand-in"
  # The image's init phase runs a socket-only server; TCP on 127.0.0.1 answers
  # only once the real server is up.
  for i in $(seq 1 90); do
    docker exec "$cid" pg_isready -h 127.0.0.1 -U postgres -q >/dev/null 2>&1 && break
    sleep 1
  done
  docker exec "$cid" pg_isready -h 127.0.0.1 -U postgres -q >/dev/null 2>&1 || void "stand-in not ready after 90 s"

  # su: the stand-in's superuser over its unix socket (trust), env + \getenv only.
  su_psql() { docker exec -i -e PGPASSWORD "$cid" psql -X -q -v ON_ERROR_STOP=1 -U postgres "$@"; }
  # as ROLE PASSWORD_VAR DB: a TCP (SCRAM) session as a real role, from a
  # throwaway client container on the stand-in's network.
  as_role() {
    local role="$1" pw_var="$2" db="$3"
    shift 3
    ( PGPASSWORD="${!pw_var}"; export PGPASSWORD
      docker run --rm -i --network "$net" -e PGPASSWORD "$PG_IMAGE" \
        psql -X -q -v ON_ERROR_STOP=1 "$@" "host=${cname} port=5432 dbname=${db} user=${role} sslmode=disable connect_timeout=15" -f - )
  }

  # The stand-in for the Azure admin: a NON-superuser with CREATEROLE CREATEDB
  # (and BYPASSRLS, which the managed admin needs to create jtoye_backup, D-10).
  # It creates both databases (as az postgres flexible-server db create leaves
  # them owned by the admin), and owns schema public in both (on Azure that
  # schema belongs to azure_pg_admin, of which the admin is a member, not to the
  # database owner).
  if ! su_psql -d postgres >"$tmp/setup.out" 2>&1 <<'SQL'
\getenv admin_pw PGPASSWORD
CREATE ROLE fake_admin LOGIN NOSUPERUSER CREATEROLE CREATEDB BYPASSRLS PASSWORD :'admin_pw';
SET ROLE fake_admin;
CREATE DATABASE jtoye;
CREATE DATABASE keycloak;
RESET ROLE;
\connect jtoye
ALTER SCHEMA public OWNER TO fake_admin;
\connect keycloak
ALTER SCHEMA public OWNER TO fake_admin;
SQL
  then
    void "stand-in setup failed: $(tr '\n' ' ' < "$tmp/setup.out")"
  fi

  standin() { bash "$SELF" --test-container "$cname" --stage "$1"; }
  # The idempotency capture: every place the pre stage writes, read as the superuser.
  capture() {
    { su_psql -d jtoye -A -F '|' <<SQL
SELECT 'role', rolname, rolsuper, rolinherit, rolcreaterole, rolcreatedb, rolcanlogin, rolbypassrls FROM pg_roles WHERE rolname IN (${ROLES_READBACK}, 'fake_admin') ORDER BY rolname;
SELECT 'member', r.rolname, m.rolname, g.rolname, a.admin_option, a.inherit_option, a.set_option FROM pg_auth_members a JOIN pg_roles r ON r.oid = a.roleid JOIN pg_roles m ON m.oid = a.member JOIN pg_roles g ON g.oid = a.grantor WHERE r.rolname IN (${ROLES_READBACK}) ORDER BY 2, 3, 4;
SELECT 'database', datname, pg_get_userbyid(datdba), datacl::text FROM pg_database WHERE datname IN ('jtoye', 'keycloak') ORDER BY datname;
SELECT 'schema', current_database(), pg_get_userbyid(nspowner), nspacl::text FROM pg_namespace WHERE nspname = 'public';
SELECT 'extension', extname FROM pg_extension ORDER BY extname;
\ddp
\connect keycloak
SELECT 'schema', current_database(), pg_get_userbyid(nspowner), nspacl::text FROM pg_namespace WHERE nspname = 'public';
SQL
    } 2>&1
  }
  first_err() { command grep -m1 -E 'ERROR|FATAL|REFUSED|VOID' "$1" | tr -d '\r' | cut -c1-200; }

  # --- pre-idempotent ---------------------------------------------------------
  local pre_ok=0 r1=0 r2=0 c1 c2 c3 c4
  standin pre >"$tmp/pre1.out" 2>&1 || r1=$?
  c1="$(capture)"
  standin pre >"$tmp/pre2.out" 2>&1 || r2=$?
  c2="$(capture)"
  # control: a planted extra default GRANT must make the same diff non-empty
  su_psql -d jtoye >/dev/null 2>&1 <<'SQL' || true
ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app IN SCHEMA public GRANT SELECT ON TABLES TO keycloak;
SQL
  c3="$(capture)"
  su_psql -d jtoye >/dev/null 2>&1 <<'SQL' || true
ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app IN SCHEMA public REVOKE SELECT ON TABLES FROM keycloak;
SQL
  c4="$(capture)"
  printf '%s\n' "$c1" > "$tmp/cap1"; printf '%s\n' "$c2" > "$tmp/cap2"; printf '%s\n' "$c3" > "$tmp/cap3"; printf '%s\n' "$c4" > "$tmp/cap4"
  d12="$(diff "$tmp/cap1" "$tmp/cap2" | command grep -c '^[<>]' || true)"
  d23="$(diff "$tmp/cap2" "$tmp/cap3" | command grep -c '^[<>]' || true)"
  d24="$(diff "$tmp/cap2" "$tmp/cap4" | command grep -c '^[<>]' || true)"
  # \ddp rows in unaligned form: owner|schema|type|privileges. Exactly the two
  # FOR ROLE jtoye_app defaults name jtoye_runtime (tables arwd, sequences rwU).
  ddp_rows="$(command grep -cE '^jtoye_app[|]public[|](table|sequence)[|].*jtoye_runtime=' "$tmp/cap2" || true)"
  if [ "$r1" -ne 0 ] || [ "$r2" -ne 0 ]; then
    st_result "FAIL pre-idempotent pre rc=${r1}/${r2}: $(first_err "$tmp/pre1.out")"; fails=$((fails + 1))
  elif [ "$d12" -ne 0 ]; then
    st_result "FAIL pre-idempotent second run changed ${d12} capture line(s): $(diff "$tmp/cap1" "$tmp/cap2" | command grep -m1 '^[<>]')"; fails=$((fails + 1))
  elif [ "$d23" -eq 0 ]; then
    st_result "FAIL pre-idempotent control: a planted default GRANT left the capture unchanged, so the diff cannot fail"; fails=$((fails + 1))
  elif [ "$d24" -ne 0 ]; then
    st_result "FAIL pre-idempotent control restore: revoking the planted GRANT left ${d24} line(s) different"; fails=$((fails + 1))
  elif [ "$ddp_rows" -ne 2 ]; then
    st_result "FAIL pre-idempotent \\ddp shows ${ddp_rows} FOR ROLE jtoye_app default(s) for jtoye_runtime, want 2 (tables, sequences)"; fails=$((fails + 1))
  else
    pre_ok=1
    st_result "PASS pre-idempotent rc=0/0 diff_lines=0 control_diff_lines=${d23} restored_diff_lines=0 runtime_defaults=${ddp_rows}"
  fi

  # --- owners-can-create ------------------------------------------------------
  local kc_rc=0 app_rc=0
  if [ "$pre_ok" -ne 1 ]; then
    st_result "FAIL owners-can-create pre stage did not complete, so neither owner exists to create anything"; fails=$((fails + 1))
  else
    as_role keycloak KEYCLOAK_DB_PASSWORD keycloak >"$tmp/kc.out" 2>&1 <<'SQL' || kc_rc=$?
CREATE TABLE st_probe (i int);
DROP TABLE st_probe;
SQL
    as_role jtoye_app JTOYE_APP_PASSWORD jtoye >"$tmp/app.out" 2>&1 <<'SQL' || app_rc=$?
CREATE TABLE st_probe (i int);
DROP TABLE st_probe;
SQL
    if [ "$kc_rc" -ne 0 ] || [ "$app_rc" -ne 0 ]; then
      st_result "FAIL owners-can-create keycloak rc=${kc_rc} ($(first_err "$tmp/kc.out")) jtoye_app rc=${app_rc} ($(first_err "$tmp/app.out"))"; fails=$((fails + 1))
    else
      st_result "PASS owners-can-create keycloak@keycloak rc=0 jtoye_app@jtoye rc=0"
    fi
  fi

  # --- post-before-migration-fails ------------------------------------------
  rc=0
  standin post >"$tmp/post0.out" 2>&1 || rc=$?
  if [ "$rc" -eq 0 ]; then
    st_result "FAIL post-before-migration-fails post exited 0 with no postcode_centroid table"; fails=$((fails + 1))
  elif ! command grep -q 'relation "postcode_centroid" does not exist' "$tmp/post0.out"; then
    st_result "FAIL post-before-migration-fails rc=${rc} for the wrong reason: $(first_err "$tmp/post0.out")"; fails=$((fails + 1))
  else
    st_result "PASS post-before-migration-fails rc=${rc} reason=relation-postcode_centroid-missing"
  fi

  # --- post-readback (after a simulated first migration) ----------------------
  local mig_rc=0 post_rc=0 rb_rc=0 rb want got read_rc=0
  if [ "$pre_ok" -ne 1 ]; then
    st_result "FAIL post-readback pre stage did not complete, so jtoye_app cannot run the simulated migration"; fails=$((fails + 1))
  else
    as_role jtoye_app JTOYE_APP_PASSWORD jtoye >"$tmp/mig.out" 2>&1 <<'SQL' || mig_rc=$?
CREATE TABLE postcode_centroid (postcode TEXT PRIMARY KEY, latitude DOUBLE PRECISION NOT NULL, longitude DOUBLE PRECISION NOT NULL);
INSERT INTO postcode_centroid VALUES ('B1 1AA', 52.48, -1.90);
CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(50), installed_by VARCHAR(100) NOT NULL DEFAULT current_user, installed_on TIMESTAMP NOT NULL DEFAULT now(), success BOOLEAN NOT NULL);
INSERT INTO flyway_schema_history (installed_rank, version, success) VALUES (1, '1', true), (2, '2', true);
SQL
    standin post >"$tmp/post1.out" 2>&1 || post_rc=$?
    rb="$(standin readback 2>"$tmp/rb.err")" || rb_rc=$?
    printf '%s\n' "$rb" > "$tmp/rb.out"
    # The pg_roles rows only: rolname|rolsuper|rolbypassrls|rolcanlogin (the
    # \ddp rows also start with jtoye_app, but their later fields are not t/f).
    got="$(awk -F'|' 'NF==4 && $2~/^[tf]$/ && $3~/^[tf]$/ && ($1=="jtoye_app"||$1=="jtoye_backup"||$1=="jtoye_runtime"){print $1"="$3}' "$tmp/rb.out" | sort | tr '\n' ' ')"
    want="jtoye_app=f jtoye_backup=t jtoye_runtime=f "
    { as_role jtoye_runtime JTOYE_RUNTIME_PASSWORD jtoye <<<'SELECT count(*) FROM postcode_centroid;' \
      && as_role jtoye_backup JTOYE_BACKUP_PASSWORD jtoye <<<'SELECT count(*) FROM postcode_centroid;'; } >"$tmp/read.out" 2>&1 || read_rc=$?
    if [ "$mig_rc" -ne 0 ]; then
      st_result "FAIL post-readback simulated migration rc=${mig_rc}: $(first_err "$tmp/mig.out")"; fails=$((fails + 1))
    elif [ "$post_rc" -ne 0 ]; then
      st_result "FAIL post-readback post rc=${post_rc}: $(first_err "$tmp/post1.out")"; fails=$((fails + 1))
    elif [ "$rb_rc" -ne 0 ]; then
      st_result "FAIL post-readback readback rc=${rb_rc}: $(first_err "$tmp/rb.err")"; fails=$((fails + 1))
    elif [ "$got" != "$want" ]; then
      st_result "FAIL post-readback rolbypassrls read back '${got}', want '${want}'"; fails=$((fails + 1))
    elif ! command grep -qE '^flyway[|]jtoye_app[|]2[|]t[|]' "$tmp/rb.out"; then
      st_result "FAIL post-readback readback shows no 'flyway|jtoye_app|2|t|' row: the first migration's installer is not read back"; fails=$((fails + 1))
    elif [ "$read_rc" -ne 0 ]; then
      st_result "FAIL post-readback the granted roles cannot read the migrated table: $(first_err "$tmp/read.out")"; fails=$((fails + 1))
    else
      st_result "PASS post-readback rc=0 ${got% } runtime_and_backup_read=ok flyway_installed_by=jtoye_app(2,t)"
    fi
  fi

  # --- via-context-relay ------------------------------------------------------
  # The managed server's firewall admits only the cluster's outbound IP (T-29-34),
  # so a real run reaches it through a one-off relay pod and a kubectl
  # port-forward (--via-context). Stub kubectl/kubelogin log their argv; the
  # stub's port-forward announces the stand-in's published loopback port, so
  # psql really connects through hostaddr=127.0.0.1 on the host network.
  local real_kubectl stub fwd_port vrc=0 vout runs pfs dels pod_run pod_del ref_rc=0 ref_runs pf_alive=no
  stub="$tmp/stub"; mkdir -p "$stub/bin" "$stub/log"
  fwd_port="$(docker port "$cid" 5432/tcp 2>/dev/null | awk -F: 'NR==1{print $NF}')" || fwd_port=""
  if ! real_kubectl="$(type -P kubectl)"; then
    st_result "FAIL via-context-relay VOID: the arm needs a real kubectl path to stand a stub in for"; fails=$((fails + 1))
  elif [ -z "$fwd_port" ]; then
    st_result "FAIL via-context-relay VOID: the stand-in has no published loopback port"; fails=$((fails + 1))
  else
    local fqdn
    fqdn="$(command grep -E '^[[:space:]]*aks_api_server_fqdn[[:space:]]*:' "$REPO_ROOT/k8s/staging/azure-identity.yaml" | head -1 | sed -E 's/^[^:]*:[[:space:]]*//; s/["'\'']//g; s/[[:space:]]+$//')"
    cat > "$stub/bin/kubectl" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$stub/log/kubectl.log"
case " \$* " in
  *" config view "*)
    case " \$* " in *" --context st-ok "*) echo "https://${fqdn}:443" ;; esac
    exit 0 ;;
  *" port-forward "*)
    echo \$\$ > "$stub/log/pf.pid"
    echo "Forwarding from 127.0.0.1:${fwd_port} -> 5432"
    exec sleep 600 ;;
esac
exit 0
STUB
    printf '#!/usr/bin/env bash\nexit 0\n' > "$stub/bin/kubelogin"
    chmod +x "$stub/bin/kubectl" "$stub/bin/kubelogin"
    vout="$(PATH="$stub/bin:$PATH" bash "$SELF" --test-container "$cname" --via-context st-ok --stage readback 2>"$tmp/via.err")" || vrc=$?
    runs="$(command grep -c ' run jtoye-dbboot-relay-' "$stub/log/kubectl.log" 2>/dev/null || true)"
    pfs="$(command grep -c ' port-forward pod/jtoye-dbboot-relay-' "$stub/log/kubectl.log" 2>/dev/null || true)"
    dels="$(command grep -c ' delete pod jtoye-dbboot-relay-' "$stub/log/kubectl.log" 2>/dev/null || true)"
    pod_run="$(command grep -oE ' run jtoye-dbboot-relay-[a-z0-9-]+' "$stub/log/kubectl.log" 2>/dev/null | head -1 | sed 's/^ run //')"
    pod_del="$(command grep -oE ' delete pod jtoye-dbboot-relay-[a-z0-9-]+' "$stub/log/kubectl.log" 2>/dev/null | head -1 | sed 's/^ delete pod //')"
    if [ -s "$stub/log/pf.pid" ] && kill -0 "$(cat "$stub/log/pf.pid")" 2>/dev/null; then pf_alive=yes; fi
    : > "$stub/log/kubectl.log"
    PATH="$stub/bin:$PATH" bash "$SELF" --test-container "$cname" --via-context sipbihs2aks --stage readback >/dev/null 2>"$tmp/via-ref.err" || ref_rc=$?
    ref_runs="$(command grep -c ' run ' "$stub/log/kubectl.log" 2>/dev/null || true)"
    if [ "$vrc" -ne 0 ]; then
      st_result "FAIL via-context-relay rc=${vrc}: $(first_err "$tmp/via.err")"; fails=$((fails + 1))
    elif ! command grep -q '^jtoye_backup|' <<<"$vout"; then
      st_result "FAIL via-context-relay readback through the relay port returned no jtoye_backup row"; fails=$((fails + 1))
    elif [ "$runs" -ne 1 ] || [ "$pfs" -ne 1 ] || [ "$dels" -ne 1 ] || [ "$pod_run" != "$pod_del" ]; then
      st_result "FAIL via-context-relay relay lifecycle run=${runs} port-forward=${pfs} delete=${dels} (want 1/1/1, same pod: '${pod_run}' vs '${pod_del}')"; fails=$((fails + 1))
    elif [ "$pf_alive" != no ]; then
      st_result "FAIL via-context-relay the port-forward process outlived the run"; fails=$((fails + 1))
    elif [ "$ref_rc" -ne 1 ] || [ "$ref_runs" -ne 0 ] || ! command grep -q 'employer-context' "$tmp/via-ref.err"; then
      st_result "FAIL via-context-relay --via-context sipbihs2aks rc=${ref_rc} relay runs=${ref_runs} (want rc 1, 0 runs, REFUSED [employer-context])"; fails=$((fails + 1))
    else
      st_result "PASS via-context-relay rc=0 readback_through_port=${fwd_port} relay run/port-forward/delete=1/1/1 same_pod=yes pf_alive=no employer_context rc=1 runs=0"
    fi
  fi

  # --- no-password-in-argv ----------------------------------------------------
  local ids n_ct=0 hits=0 insp v
  ids="$(docker ps -aq --filter "label=jtoye.bootstrap.run=${run_id}")" || ids=""
  : > "$tmp/inspect.txt"
  for i in $ids; do
    n_ct=$((n_ct + 1))
    docker inspect --format '{{json .Path}} {{json .Args}} {{json .Config.Cmd}} {{json .Config.Entrypoint}}' "$i" >> "$tmp/inspect.txt" 2>&1 || true
  done
  insp="$(cat "$tmp/inspect.txt")"
  for v in "$PGPASSWORD" "$JTOYE_APP_PASSWORD" "$JTOYE_RUNTIME_PASSWORD" "$KEYCLOAK_DB_PASSWORD" "$JTOYE_BACKUP_PASSWORD" "$ST_SUPER_PASSWORD"; do
    if command grep -qF -- "$v" <<<"$insp"; then hits=$((hits + 1)); fi
  done
  if [ "$n_ct" -eq 0 ]; then
    st_result "FAIL no-password-in-argv inspected 0 psql containers: nothing was observed, which is not a pass"; fails=$((fails + 1))
  elif [ "$hits" -ne 0 ]; then
    st_result "FAIL no-password-in-argv ${hits} distinct password(s) visible in docker inspect Path/Args/Cmd of ${n_ct} psql container(s)"; fails=$((fails + 1))
  else
    st_result "PASS no-password-in-argv psql_containers=${n_ct} password_hits=0"
  fi

  if [ -n "${BOOTSTRAP_SELFTEST_KEEP:-}" ]; then cp -r "$tmp" "$BOOTSTRAP_SELFTEST_KEEP"; fi
  st_render
  [ "$fails" -eq 0 ] || return 1
  return 0
}

if [ "$SELFTEST" -eq 1 ]; then
  st_rc=0
  selftest || st_rc=$?
  exit "$st_rc"
fi

# ---------------------------------------------------------------------------
# a stage run
# ---------------------------------------------------------------------------
case "$STAGE" in
  pre|post|monitor|readback) ;;
  "") echo "USAGE ERROR: --stage is required (pre|post|monitor|readback), or --selftest" >&2; usage >&2; exit 2 ;;
  *) echo "USAGE ERROR: --stage must be pre, post, monitor or readback (got '${STAGE}')" >&2; exit 2 ;;
esac
command -v docker >/dev/null 2>&1 || void "docker not on PATH (psql runs inside ${PG_IMAGE})"
[ -f "$REPO_ROOT/infra/db/managed-pre-bootstrap.sql" ] || void "infra/db/managed-pre-bootstrap.sql not found"

if [ -n "$TEST_CONTAINER" ]; then
  # Stand-in mode: no Key Vault, no az_guard_init. The host is the container.
  HOST="$TEST_CONTAINER"
  STANDIN_NETWORK="$(docker inspect --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$TEST_CONTAINER" 2>/dev/null | awk '{print $1}')" || true
  [ -n "$STANDIN_NETWORK" ] || void "cannot read the network of test container '${TEST_CONTAINER}'"
  [ "$STANDIN_NETWORK" != bridge ] || void "test container '${TEST_CONTAINER}' is on the default bridge, where names do not resolve; put it on a user network"
  [ -n "${PG_ADMIN_LOGIN:-}" ] || refuse env "PG_ADMIN_LOGIN is unset (stand-in mode takes the admin login from the environment)"
  # shellcheck disable=SC2046
  require_env $(stage_env "$STAGE")
  echo "=== staging-db-bootstrap --stage ${STAGE} (stand-in ${TEST_CONTAINER}, network ${STANDIN_NETWORK}, admin ${PG_ADMIN_LOGIN}) ==="
  trap cleanup EXIT
  [ -z "$VIA_CONTEXT" ] || relay_up "$HOST"
  run_stage
  exit 0
fi

# Real mode: never the test double (its canned answers would be fed to psql as
# passwords), then the subscription guard in THIS shell, before any read.
AZ_PATH="$(command -v az 2>/dev/null || true)"
if [ -n "$AZ_PATH" ] && [ -f "$(dirname "$AZ_PATH")/.az-guard-stub" ]; then
  void "[live] az on PATH is the az_guard_stub at '${AZ_PATH}'; real mode reads real Key Vault values (use --test-container or --selftest for a stand-in)"
fi
# shellcheck source=scripts/lib/azure-guard.sh
. "$SCRIPT_DIR/lib/azure-guard.sh"
GUARD_RC=0
az_guard_init >/dev/null || GUARD_RC=$?
[ "$GUARD_RC" -eq 0 ] || exit "$GUARD_RC"

[ -f "$DECISIONS_FILE" ] || refuse decisions "no decision record at ${DECISIONS_FILE}; PG_ADMIN_LOGIN comes from it (plan 29-09)"
PG_ADMIN_LOGIN="$(command grep -E '^PG_ADMIN_LOGIN=' "$DECISIONS_FILE" | sed -E 's/^PG_ADMIN_LOGIN=//; s/[[:space:]]+$//' | sort -u)" || true
[ -n "$PG_ADMIN_LOGIN" ] && [ "$(printf '%s\n' "$PG_ADMIN_LOGIN" | wc -l)" -eq 1 ] \
  || refuse decisions "${DECISIONS_FILE} must define PG_ADMIN_LOGIN exactly once"
[ -f "$HOST_CA_BUNDLE" ] || void "no CA bundle at ${HOST_CA_BUNDLE}; verify-full has nothing to verify the server certificate against"

trap cleanup EXIT
SECRET_DIR="$(umask 077; mktemp -d "${TMPDIR:-/tmp}/jtoye-dbboot.XXXXXXXX")" || void "mktemp -d failed"
for name in $(stage_env "$STAGE"); do
  obj="$(kv_object_for "$name")"
  f="$SECRET_DIR/$name"
  rc=0
  az_read keyvault secret show --vault-name "$KV_NAME" -n "$obj" --query value -o tsv > "$f.raw" 2>/dev/null || rc=$?
  [ "$rc" -eq 0 ] || refuse secret "could not read Key Vault object ${KV_NAME}/${obj} (az rc=${rc}); plan 29-13 writes the role passwords, plan 29-07's datastores script the admin's"
  tr -d '\r\n' < "$f.raw" > "$f"
  rm -f "$f.raw"
  [ -s "$f" ] || refuse secret "Key Vault object ${KV_NAME}/${obj} is empty"
  printf -v "$name" '%s' "$(<"$f")"
  export "${name?}"
done
echo "=== staging-db-bootstrap --stage ${STAGE} (host ${HOST}, admin ${PG_ADMIN_LOGIN}, sslmode=verify-full${VIA_CONTEXT:+, via context ${VIA_CONTEXT}}) ==="
[ -z "$VIA_CONTEXT" ] || relay_up "$HOST"
run_stage
