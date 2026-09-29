#!/usr/bin/env bash
# check-backup-restore-drill.sh — a backup restored FROM BLOB carries tenant rows, and a
# zero-row dump is caught. The two-arm restore drill of docs/runbooks/backups.md, executed.
#
# WHY THIS EXISTS
#
#   Every automated check inside the backup pipeline passes on a dump that contains no tenant
#   data at all. infra/backups/k8s-backup.sh verifies its artifact two ways — a size floor
#   (MIN_BACKUP_BYTES) and a `pg_restore --list` table-of-contents read — and a schema-only,
#   zero-row dump clears both: sixty-odd migrations of DDL plus the non-tenant reference data
#   are far above the floor, and a TOC lists perfectly with no rows behind it. The tenant tables
#   are FORCE ROW LEVEL SECURITY, so a dump taken as the application role with no tenant GUC
#   silently reads ZERO tenant rows. Only a RESTORE-AND-COUNT separates that artifact from a
#   real backup, and until this script the recipe existed only as prose (26-07 ran it once, by
#   hand). The restore path also had no standing coverage: the pg-backup image was built nowhere
#   in CI. This gate builds nothing itself, but runs the image the nightly builds, end to end,
#   against a real Blob API (Azurite) on every scheduled run.
#
# WHAT IS ASSERTED
#
#   Setup  the BYPASSRLS dump role jtoye_backup is ensured by piping
#          infra/backups/create-backup-role.sql into psql as the superuser (idempotent; the
#          password is the same .env DB_BACKUP_PASSWORD k8s-local already uses). The LIVE
#          `products` count is read as jtoye_backup. Live = 0 is a VOID: zero-vs-zero would
#          make arm B pass on an empty database and prove nothing.
#
#   Arm B  THE REAL BACKUP. The pg-backup image runs exactly as the CronJob does (its own
#          ENTRYPOINT, its own checks, `blobctl upload`) as jtoye_backup, into
#          jtoye-db-backups/drill/<run>/B/. It must exit 0. A second container of the same image
#          lists that prefix (exactly ONE .dump), downloads it with blobctl, restores it into a
#          fresh scratch database as the superuser, and counts. PASS needs
#          restored products == live products, and live > 0.
#
#   Arm A  THE COUNTEREXAMPLE, in two halves (see "A MEASURED CORRECTION" below):
#     A-job  the same backup image run as the app role (jtoye_app: owner, FORCE RLS applies, no
#            tenant GUC). It must exit NON-ZERO and must upload NOTHING under drill/<run>/A-job/.
#            That is the job's explicit pg_dump rc check plus its `rm -f "$TMP"` doing their work.
#     A-dump the zero-row dump itself: pg_dump as jtoye_app WITH --enable-row-security (so it
#            completes, reading RLS-filtered — i.e. zero — tenant rows). It must PASS the
#            pipeline's own two content checks, with the size floor read OUT OF THE IMAGE's
#            backup script, then be uploaded with blobctl and restored and counted exactly as
#            arm B is. PASS needs restored products == 0. A non-zero count means RLS is not
#            enforcing, which is a finding in its own right.
#
#   Every count is taken as `current_database(), count(*)` in ONE statement, and the database
#   name must equal the scratch database this run created. A count read from the wrong database
#   (the live one, say) therefore fails instead of passing arm B by reading the source.
#   A failed download, createdb, pg_restore or count is a FAIL — never a zero.
#
#   Three counts are printed: arm A restored, arm B restored, live. orders / customers / shops
#   are REPORTED beside them, not asserted: shops carries a public read policy for published
#   shops (shops_public_read), so an app-role read legitimately sees some shops, and the other
#   tables can move while core-java is running. products is the asserted anchor, as in 26-07.
#
# A MEASURED CORRECTION TO THE PLAN'S ARM A
#
#   36-08's plan asked for arm A to be the backup JOB run as jtoye_app, exiting 0. That cannot
#   happen on a correct tree: pg_dump requests row_security=off, which Postgres REFUSES for a
#   non-BYPASSRLS role on a FORCE-RLS table, so the job exits 1 with "query would be affected by
#   row-level security policy for table customers" (measured again 2026-09-29, as 26-07 did).
#   Making it exit 0 would mean deleting the job's rc check — the safety net. So the counterexample
#   is split: A-job proves the safety net, A-dump proves the content checks cannot see the
#   difference. Both are required.
#
# WHAT IT CANNOT PROVE
#
#   Blob immutability (WORM), soft delete and lifecycle deletion are NOT supported by Azurite
#   (its v3.37.0 support matrix), so the retention half of D-01 is a Phase 29 read-back against
#   the real backup account (docs/runbooks/azure-blob-provisioning.md). Nor does Azurite enforce
#   RBAC: the write-only backup identity cannot be probed here. The connection-string auth path
#   is exercised; the workload-identity path is not. Row counts, not row contents.
#
# EXIT CODES — uniform with the other ops gates
#   0 = both arms hold (A restores 0 products, B restores exactly the live count, live > 0)
#   1 = an arm does not hold — the failing assertion is named
#   2 = VOID (cannot evaluate): docker missing; the Postgres or Azurite container absent or not
#       running; the backup image absent; .env unreadable; a credential empty or CHANGE_ME; the
#       compose network unresolvable; the role bootstrap failing; the live count failing or 0;
#       the zero-row counterexample not constructible. "Found nothing" is never "clean".
#
# SHAPE RULES OBSERVED (each is a recorded failure in this repository)
#   - NO CREDENTIAL ON ANY COMMAND LINE. Values reach containers through a 0600 --env-file in a
#     0700 temp dir, and the role bootstrap reads its password with psql's \getenv from a
#     name-only `docker exec -e`. The EXIT trap deletes the files. Every captured output is
#     scanned for every credential value, and a hit is a FAIL.
#   - rc captured on the SAME statement as its command; no `cmd | grep -q` (pipefail inverts it);
#     here-strings on values already in hand.
#   - `docker exec` WITH -i whenever psql reads stdin (without it the heredoc never arrives and
#     psql runs nothing, successfully).
#   - Scratch databases are dropped, and drill containers removed, in an EXIT trap.
#
# WHY IT IS WIRED INTO THE NIGHTLY, NOT PER-PR CI
#   It needs the compose Postgres and Azurite running. e2e-nightly.yml is the only workflow that
#   brings that stack up, so it runs there (after the stack is healthy), and exits 2 anywhere
#   the stack is absent.
#
# USAGE
#   docker build -t ghcr.io/bralabee/jtoye-pg-backup:15-blob infra/backups
#   docker compose -f docker-compose.full-stack.yml up -d postgres azurite
#   bash scripts/check-backup-restore-drill.sh
#
#   Overrides: PG_BACKUP_IMAGE, PG_CONTAINER, AZURITE_CONTAINER, ENV_FILE,
#   DRILL_ARM_A_USER / DRILL_ARM_B_USER (role names; their passwords are looked up, never passed),
#   DRILL_STEP_TIMEOUT (seconds per container step, default 600).
#
#   Drill dumps stay in Azurite under drill/<run>/: blobctl has no delete, by design, and there is
#   no lifecycle rule locally. They are small and dev-only (the nightly's Azurite is ephemeral).
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

IMAGE="${PG_BACKUP_IMAGE:-ghcr.io/bralabee/jtoye-pg-backup:15-blob}"
PG_CONTAINER="${PG_CONTAINER:-jtoye-postgres}"
AZ_CONTAINER="${AZURITE_CONTAINER:-jtoye-azurite}"
ENV_FILE="${ENV_FILE:-$REPO_ROOT/.env}"
ROLE_SQL="$REPO_ROOT/infra/backups/create-backup-role.sql"
BLOB_CONTAINER="jtoye-db-backups"
DB_HOST_IN_NET="postgres"
AZURITE_CONN="UseDevelopmentStorage=true;DevelopmentStorageProxyUri=http://azurite"
STEP_TIMEOUT="${DRILL_STEP_TIMEOUT:-600}"

VOID=2
FAILED=0
SECRETS=()

void() {
    echo "VOID: $*" >&2
    echo "  (a drill that cannot be evaluated is never a clean bill)" >&2
    exit "$VOID"
}
fail() { echo "FAIL: $*" >&2; FAILED=1; }
note() { printf '  %s\n' "$*"; }

# ---- Preconditions: tooling, containers, image ----------------------------------------------

command -v docker >/dev/null 2>&1 || void "docker is not on PATH — there is no stack to drill against"
command -v timeout >/dev/null 2>&1 || void "timeout (coreutils) is not on PATH — a hung step would hang the gate"
[ -r "$ROLE_SQL" ] || void "cannot read $ROLE_SQL — the BYPASSRLS role bootstrap is the drill's only source for it"

for c in "$PG_CONTAINER" "$AZ_CONTAINER"; do
    state=$(docker inspect -f '{{.State.Status}}' "$c" 2>/dev/null); rc=$?
    [ "$rc" -eq 0 ] || void "container '$c' does not exist (docker inspect rc=$rc) — start it: docker compose -f docker-compose.full-stack.yml up -d postgres azurite"
    [ "$state" = "running" ] || void "container '$c' is '$state', not running"
done

docker image inspect "$IMAGE" >/dev/null 2>&1; rc=$?
[ "$rc" -eq 0 ] || void "backup image '$IMAGE' is not present locally (rc=$rc) — build it: docker build -t $IMAGE infra/backups"

# ---- Credentials: read by KEY from .env (last occurrence wins, as compose does) --------------

[ -r "$ENV_FILE" ] || void ".env is not readable at $ENV_FILE — credentials are required and never defaulted here"

env_value() {
    local v
    v=$(sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1)
    # Strip one pair of surrounding quotes, as compose's dotenv parser does.
    case "$v" in
        \"*\") v="${v#\"}"; v="${v%\"}" ;;
        \'*\') v="${v#\'}"; v="${v%\'}" ;;
    esac
    printf '%s' "$v"
}

# require_cred <VAR> <KEY>: assigns into VAR in THIS shell (never via a $(...) subshell, where a
# void would exit only the subshell and leave the variable silently empty).
require_cred() {
    local val
    val=$(env_value "$2")
    case "$val" in
        "") void "$2 is empty or absent in $ENV_FILE" ;;
        CHANGE_ME) void "$2 is still the .env.example placeholder CHANGE_ME" ;;
    esac
    printf -v "$1" '%s' "$val"
}

require_cred SUPER_USER POSTGRES_USER
require_cred SUPER_PASS POSTGRES_PASSWORD
require_cred MIG_PASS DB_MIGRATION_PASSWORD
require_cred BACKUP_PASS DB_BACKUP_PASSWORD
DB_NAME=$(env_value POSTGRES_DB); DB_NAME="${DB_NAME:-jtoye}"
MIG_USER=$(env_value DB_MIGRATION_USER); MIG_USER="${MIG_USER:-jtoye_app}"
SECRETS=("$SUPER_PASS" "$MIG_PASS" "$BACKUP_PASS")

ARM_A_USER="${DRILL_ARM_A_USER:-$MIG_USER}"
ARM_B_USER="${DRILL_ARM_B_USER:-jtoye_backup}"

# The password for a role NAME. Only the two roles this drill knows are resolvable; anything
# else is a VOID rather than a guess.
role_password() {
    case "$1" in
        jtoye_backup) printf '%s' "$BACKUP_PASS" ;;
        "$MIG_USER")  printf '%s' "$MIG_PASS" ;;
        *) return 1 ;;
    esac
}
ARM_A_PASS=$(role_password "$ARM_A_USER") || void "no credential is known for arm A's role '$ARM_A_USER'"
ARM_B_PASS=$(role_password "$ARM_B_USER") || void "no credential is known for arm B's role '$ARM_B_USER'"

# ---- The compose network, resolved from the running Postgres (never named here) -------------

NETS=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{"\n"}}{{end}}' "$PG_CONTAINER" 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] || void "could not read $PG_CONTAINER's networks (rc=$rc)"
NETS=$(sed '/^$/d' <<< "$NETS")
n_nets=$(wc -l <<< "$NETS")
[ -n "$NETS" ] && [ "$n_nets" -eq 1 ] || void "$PG_CONTAINER is on $n_nets network(s) ('${NETS//$'\n'/ }'); expected exactly one compose network"
NET="$NETS"
AZ_NETS=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{"\n"}}{{end}}' "$AZ_CONTAINER" 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] || void "could not read $AZ_CONTAINER's networks (rc=$rc)"
on_net=$(grep -cxF -- "$NET" <<< "$AZ_NETS" || true)
[ "$on_net" -eq 1 ] || void "$AZ_CONTAINER is not on $PG_CONTAINER's network '$NET' — the drill containers could not reach both"

# ---- Run identity, scratch space, cleanup ---------------------------------------------------

RUN_ID="$(date -u +%Y%m%d%H%M%S)_$$"
SCRATCH_A="jtoye_restore_drill_${RUN_ID}_a"
SCRATCH_B="jtoye_restore_drill_${RUN_ID}_b"
CNAME_PREFIX="jtoye-backup-drill-${RUN_ID//_/-}"
umask 077
WORK=$(mktemp -d "${TMPDIR:-/tmp}/backup-drill.XXXXXX") || void "mktemp failed"

psql_super() {
    # -c only: no stdin is read, so no -i (the one call that streams SQL uses -i explicitly).
    docker exec "$PG_CONTAINER" psql -U "$SUPER_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -X -q "$@"
}

cleanup() {
    local rc=$?
    for db in "$SCRATCH_A" "$SCRATCH_B"; do
        docker exec "$PG_CONTAINER" psql -U "$SUPER_USER" -d postgres -v ON_ERROR_STOP=1 -X -q \
            -c "DROP DATABASE IF EXISTS \"$db\" WITH (FORCE);" >/dev/null 2>&1 \
            || echo "WARN: could not drop scratch database $db — drop it by hand" >&2
    done
    for c in $(docker ps -aq --filter "name=^${CNAME_PREFIX}" 2>/dev/null); do
        docker rm -f "$c" >/dev/null 2>&1 || true
    done
    rm -rf "$WORK"
    exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# One env file per container step, 0600 inside a 0700 dir: the ONLY way a credential reaches a
# container. Arguments are KEY=VALUE pairs; a value holding a newline cannot be expressed in an
# env file and is refused rather than truncated.
write_env() {
    local f="$1"; shift
    : > "$f"
    local kv
    for kv in "$@"; do
        [[ "$kv" == *$'\n'* ]] && void "an env value contains a newline; refusing to write a truncated env file"
        printf '%s\n' "$kv" >> "$f"
    done
    chmod 600 "$f"
}

assert_no_secret() {
    local label="$1" text="$2" s hits
    for s in "${SECRETS[@]}"; do
        [ -n "$s" ] || continue
        hits=$(grep -cF -- "$s" <<< "$text" || true)
        if [ "$hits" -ne 0 ]; then
            fail "$label printed a credential value ($hits line(s)) — output suppressed"
            return 1
        fi
    done
    return 0
}

run_step() {
    # run_step <label> <envfile> [docker run args...] — captures combined output and rc.
    local label="$1" envf="$2"; shift 2
    STEP_OUT=$(timeout "$STEP_TIMEOUT" docker run --rm --name "${CNAME_PREFIX}-${label}" \
        --network "$NET" --env-file "$envf" "$@" 2>&1); STEP_RC=$?
    assert_no_secret "step $label" "$STEP_OUT" || STEP_OUT="(suppressed)"
}

echo "=============================================================================="
echo " check-backup-restore-drill — a backup restored from Blob carries tenant rows"
echo " image=$IMAGE"
echo " postgres=$PG_CONTAINER azurite=$AZ_CONTAINER network=$NET"
echo " run=$RUN_ID  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "=============================================================================="

# ---- Setup: the BYPASSRLS role, then the live count as that role ----------------------------

# The password travels as an ENV VALUE (name-only -e) and is read by psql's \getenv, so it is on
# no command line; the SQL file itself is streamed unchanged.
role_out=$( { printf '\\getenv backup_password DRILL_BACKUP_PW\n'; cat "$ROLE_SQL"; } \
    | DRILL_BACKUP_PW="$BACKUP_PASS" docker exec -i -e DRILL_BACKUP_PW "$PG_CONTAINER" \
        psql -U "$SUPER_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -X -q 2>&1 ); rc=$?
assert_no_secret "role bootstrap" "$role_out" || role_out="(suppressed)"
[ "$rc" -eq 0 ] || void "create-backup-role.sql failed (rc=$rc): $role_out"
bypass=$(psql_super -tA -c "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'jtoye_backup';" 2>&1); rc=$?
[ "$rc" -eq 0 ] && [ "$bypass" = "t" ] || void "jtoye_backup is not a BYPASSRLS role after the bootstrap (rc=$rc, rolbypassrls='$bypass')"
note "setup   jtoye_backup ensured (BYPASSRLS=t) from infra/backups/create-backup-role.sql"

LIVE=$(docker exec "$PG_CONTAINER" psql -U jtoye_backup -d "$DB_NAME" -v ON_ERROR_STOP=1 -X -tA -F'|' \
    -c "SELECT count(*) FROM products; " -c "SELECT (SELECT count(*) FROM orders), (SELECT count(*) FROM customers), (SELECT count(*) FROM shops);" 2>&1); rc=$?
[ "$rc" -eq 0 ] || void "live count as jtoye_backup failed (rc=$rc): $LIVE"
LIVE_PRODUCTS=$(sed -n 1p <<< "$LIVE")
LIVE_OTHERS=$(sed -n 2p <<< "$LIVE")
[[ "$LIVE_PRODUCTS" =~ ^[0-9]+$ ]] || void "live products count is not a number: '$LIVE_PRODUCTS'"
[ "$LIVE_PRODUCTS" -gt 0 ] || void "the live database holds 0 products — zero-vs-zero proves nothing; seed it first"
note "live    products=$LIVE_PRODUCTS  (orders|customers|shops=$LIVE_OTHERS, reported only)"

# ---- The restore half, shared by both arms ---------------------------------------------------
#
# Runs in a second container of the SAME image: blobctl list (exactly one .dump), blobctl
# download, createdb (fails if the name exists), pg_restore, then ONE statement returning the
# database it counted in beside the counts. Output lines are KEY=VALUE; nothing else is parsed.

RESTORE_SH='
set -u
names=$(blobctl list "$BACKUP_CONTAINER" "$DRILL_PREFIX/"); rc=$?
[ "$rc" -eq 0 ] || { echo "ERR=blobctl list rc=$rc"; exit 1; }
dumps=$(printf "%s\n" "$names" | grep -E "\.dump$" || true)
n=$(printf "%s" "$dumps" | grep -c . || true)
echo "LISTED=$n"
[ "$n" -eq 1 ] || { echo "ERR=expected exactly 1 dump under $DRILL_PREFIX/, found $n"; exit 1; }
echo "BLOB=$dumps"
blobctl download "$BACKUP_CONTAINER" "$dumps" /tmp/restore.dump; rc=$?
[ "$rc" -eq 0 ] || { echo "ERR=blobctl download rc=$rc"; exit 1; }
echo "BYTES=$(stat -c%s /tmp/restore.dump)"
createdb -h "$DB_HOST" -U "$SUPER_USER" "$SCRATCH_DB"; rc=$?
[ "$rc" -eq 0 ] || { echo "ERR=createdb $SCRATCH_DB rc=$rc"; exit 1; }
pg_restore -h "$DB_HOST" -U "$SUPER_USER" -d "$SCRATCH_DB" --no-owner --no-acl --exit-on-error /tmp/restore.dump; rc=$?
[ "$rc" -eq 0 ] || { echo "ERR=pg_restore rc=$rc"; exit 1; }
c=$(psql -h "$DB_HOST" -U "$SUPER_USER" -d "$SCRATCH_DB" -v ON_ERROR_STOP=1 -X -tA -F"|" -c "SELECT current_database(), (SELECT count(*) FROM products), (SELECT count(*) FROM orders), (SELECT count(*) FROM customers), (SELECT count(*) FROM shops)"); rc=$?
[ "$rc" -eq 0 ] || { echo "ERR=count query rc=$rc"; exit 1; }
echo "COUNTED=$c"
'

# restore_and_count <label> <prefix> <scratch db> -> sets R_DB R_PRODUCTS R_OTHERS, or fails.
restore_and_count() {
    local label="$1" prefix="$2" scratch="$3" envf="$WORK/restore-$1.env" kv
    R_DB="" R_PRODUCTS="" R_OTHERS=""
    write_env "$envf" \
        "STORAGE_AUTH_MODE=connection-string" "STORAGE_CONNECTION_STRING=$AZURITE_CONN" \
        "BACKUP_CONTAINER=$BLOB_CONTAINER" "DRILL_PREFIX=$prefix" \
        "DB_HOST=$DB_HOST_IN_NET" "SUPER_USER=$SUPER_USER" "PGPASSWORD=$SUPER_PASS" \
        "SCRATCH_DB=$scratch"
    run_step "restore-$label" "$envf" --entrypoint sh "$IMAGE" -c "$RESTORE_SH"
    note "        restore $label: rc=$STEP_RC $(grep -E '^(LISTED|BLOB|BYTES|ERR)=' <<< "$STEP_OUT" | tr '\n' ' ')"
    if [ "$STEP_RC" -ne 0 ]; then
        fail "arm $label: the restore from Blob did not complete (rc=$STEP_RC) — a failed restore is never a zero count"
        printf '%s\n' "$STEP_OUT" | tail -n 15 | sed 's/^/          | /' >&2
        return 1
    fi
    kv=$(sed -n 's/^COUNTED=//p' <<< "$STEP_OUT")
    R_DB=$(cut -d'|' -f1 <<< "$kv")
    R_PRODUCTS=$(cut -d'|' -f2 <<< "$kv")
    R_OTHERS=$(cut -d'|' -f3- <<< "$kv")
    if [ "$R_DB" != "$scratch" ]; then
        fail "arm $label: the count came from database '$R_DB', not this run's scratch database '$scratch'"
        return 1
    fi
    if ! [[ "$R_PRODUCTS" =~ ^[0-9]+$ ]]; then
        fail "arm $label: restored products count is not a number: '$R_PRODUCTS'"
        return 1
    fi
    return 0
}

# ---- Arm B: the real backup, exactly as the CronJob runs it ---------------------------------

echo
echo "Arm B — the backup job as $ARM_B_USER, uploaded to $BLOB_CONTAINER/drill/$RUN_ID/B/"
write_env "$WORK/job-b.env" \
    "DB_HOST=$DB_HOST_IN_NET" "DB_PORT=5432" "DB_NAME=$DB_NAME" "DB_USER=$ARM_B_USER" "PGPASSWORD=$ARM_B_PASS" \
    "STORAGE_AUTH_MODE=connection-string" "STORAGE_CONNECTION_STRING=$AZURITE_CONN" \
    "BACKUP_CONTAINER=$BLOB_CONTAINER" "BACKUP_PREFIX=drill/$RUN_ID/B"
run_step job-b "$WORK/job-b.env" "$IMAGE"
note "        job: rc=$STEP_RC"
printf '%s\n' "$STEP_OUT" | sed 's/^/          | /'
B_RESTORED=""
if [ "$STEP_RC" -ne 0 ]; then
    fail "arm B: the backup job exited $STEP_RC as $ARM_B_USER — the real backup did not reach Blob"
elif restore_and_count B "drill/$RUN_ID/B" "$SCRATCH_B"; then
    B_RESTORED="$R_PRODUCTS"
    note "        restored products=$R_PRODUCTS in $R_DB  (orders|customers|shops=$R_OTHERS, reported only)"
    [ "$R_PRODUCTS" -eq "$LIVE_PRODUCTS" ] \
        || fail "arm B: restored products=$R_PRODUCTS but live=$LIVE_PRODUCTS — the backup does not carry the tenant data"
fi

# ---- Arm A-job: the same job as the app role must FAIL and upload nothing --------------------

echo
echo "Arm A-job — the backup job as $ARM_A_USER must exit non-zero and upload nothing"
write_env "$WORK/job-a.env" \
    "DB_HOST=$DB_HOST_IN_NET" "DB_PORT=5432" "DB_NAME=$DB_NAME" "DB_USER=$ARM_A_USER" "PGPASSWORD=$ARM_A_PASS" \
    "STORAGE_AUTH_MODE=connection-string" "STORAGE_CONNECTION_STRING=$AZURITE_CONN" \
    "BACKUP_CONTAINER=$BLOB_CONTAINER" "BACKUP_PREFIX=drill/$RUN_ID/A-job"
run_step job-a "$WORK/job-a.env" "$IMAGE"
note "        job: rc=$STEP_RC"
printf '%s\n' "$STEP_OUT" | sed 's/^/          | /'
if [ "$STEP_RC" -eq 0 ]; then
    fail "arm A-job: the backup job SUCCEEDED as $ARM_A_USER — pg_dump's row-security refusal or the job's rc check is gone"
fi
write_env "$WORK/list-a.env" \
    "STORAGE_AUTH_MODE=connection-string" "STORAGE_CONNECTION_STRING=$AZURITE_CONN"
run_step list-a-job "$WORK/list-a.env" --entrypoint blobctl "$IMAGE" list "$BLOB_CONTAINER" "drill/$RUN_ID/A-job/"
if [ "$STEP_RC" -ne 0 ]; then
    fail "arm A-job: could not list drill/$RUN_ID/A-job/ (rc=$STEP_RC): $STEP_OUT"
else
    n_up=$(grep -c . <<< "$STEP_OUT" || true)
    note "        blobs under drill/$RUN_ID/A-job/: $n_up"
    [ "$n_up" -eq 0 ] || fail "arm A-job: the failed job still uploaded $n_up blob(s) — a partial dump reached Blob"
fi

# ---- Arm A-dump: the zero-row dump passes the pipeline's own checks, then restores 0 --------

echo
echo "Arm A-dump — pg_dump as $ARM_A_USER with --enable-row-security, through the pipeline's own content checks"
DUMP_A_SH='
set -u
floor=$(sed -n "s/^MIN_BACKUP_BYTES=\"\${MIN_BACKUP_BYTES:-\([0-9][0-9]*\)}\"$/\1/p" /usr/local/bin/backup)
[ -n "$floor" ] || { echo "ERR=could not read the MIN_BACKUP_BYTES default out of /usr/local/bin/backup"; exit 3; }
echo "FLOOR=$floor"
f=/tmp/jtoye-backup-armA.dump
pg_dump -h "$DB_HOST" -p 5432 -U "$DB_USER" -d "$DB_NAME" --enable-row-security \
    --no-owner --no-acl --format=custom --compress=6 -f "$f"; rc=$?
[ "$rc" -eq 0 ] || { echo "ERR=pg_dump --enable-row-security rc=$rc"; exit 3; }
size=$(stat -c%s "$f")
echo "SIZE=$size"
[ "$size" -ge "$floor" ] || { echo "ERR=the zero-row dump FAILS the size floor ($size < $floor)"; exit 3; }
toc=$(pg_restore --list "$f" 2>/dev/null); rc=$?
[ "$rc" -eq 0 ] || { echo "ERR=the zero-row dump FAILS pg_restore --list (rc=$rc)"; exit 3; }
echo "TOC=$(printf "%s\n" "$toc" | grep -c .)"
blobctl upload "$f" "$BACKUP_CONTAINER" "$BACKUP_PREFIX/jtoye-backup-armA.dump"; rc=$?
[ "$rc" -eq 0 ] || { echo "ERR=blobctl upload rc=$rc"; exit 1; }
echo "UPLOADED=1"
'
write_env "$WORK/dump-a.env" \
    "DB_HOST=$DB_HOST_IN_NET" "DB_NAME=$DB_NAME" "DB_USER=$ARM_A_USER" "PGPASSWORD=$ARM_A_PASS" \
    "STORAGE_AUTH_MODE=connection-string" "STORAGE_CONNECTION_STRING=$AZURITE_CONN" \
    "BACKUP_CONTAINER=$BLOB_CONTAINER" "BACKUP_PREFIX=drill/$RUN_ID/A"
run_step dump-a "$WORK/dump-a.env" --entrypoint sh "$IMAGE" -c "$DUMP_A_SH"
note "        rc=$STEP_RC $(grep -E '^(FLOOR|SIZE|TOC|UPLOADED|ERR)=' <<< "$STEP_OUT" | tr '\n' ' ')"
A_RESTORED=""
case "$STEP_RC" in
    0)
        note "        the zero-row dump PASSES the size floor and the TOC read, as documented"
        if restore_and_count A "drill/$RUN_ID/A" "$SCRATCH_A"; then
            A_RESTORED="$R_PRODUCTS"
            note "        restored products=$R_PRODUCTS in $R_DB  (orders|customers|shops=$R_OTHERS, reported only)"
            [ "$R_PRODUCTS" -eq 0 ] \
                || fail "arm A: a dump taken as $ARM_A_USER restored products=$R_PRODUCTS, not 0 — RLS did not hide the tenant rows"
        fi
        ;;
    3)
        void "the zero-row counterexample could not be constructed: $(sed -n 's/^ERR=//p' <<< "$STEP_OUT")"
        ;;
    *)
        fail "arm A-dump: the counterexample did not reach Blob (rc=$STEP_RC): $(sed -n 's/^ERR=//p' <<< "$STEP_OUT")"
        ;;
esac

# ---- Verdict --------------------------------------------------------------------------------

echo
echo "------------------------------------------------------------------------------"
echo " arm A restored products = ${A_RESTORED:-<none>}   (must be 0)"
echo " arm B restored products = ${B_RESTORED:-<none>}   (must equal live)"
echo " live           products = $LIVE_PRODUCTS   (must be > 0)"
echo "------------------------------------------------------------------------------"
if [ "$FAILED" -ne 0 ] || [ -z "$A_RESTORED" ] || [ -z "$B_RESTORED" ]; then
    echo "FAILED: the backup restored from Blob is not proven to carry the tenant data." >&2
    exit 1
fi
echo "PASS: arm A restored 0, arm B restored $B_RESTORED = live $LIVE_PRODUCTS (> 0)."
echo "      Not proven here: WORM, soft delete, lifecycle, RBAC (Azurite lacks them; Phase 29 read-backs)."
exit 0
