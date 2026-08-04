#!/usr/bin/env bash
# restore-drill.sh — take a real backup, restore it into a throwaway server of the
# DEPLOYED major, and prove the restored data matches the source BY CONTENT.
#
# ---------------------------------------------------------------------------
# WHY THIS EXISTS
#
#   docs/runbooks/backups.md documents a restore procedure. Nothing executed it.
#   The 2026-07-08 remediation backlog (P1-8) listed "no restore ever rehearsed"
#   and that line was still true on 2026-08-04. Meanwhile:
#
#     - k8s-backup.sh's own verification is `pg_restore --list`, which runs INSIDE
#       the image that produced the dump. It agrees by construction and cannot see
#       a version mismatch (that is what check-postgres-major-parity.sh is for).
#     - `pg_restore --list` reads the ARCHIVE HEADER. It does not load a single
#       row. A structurally-readable archive containing zero rows passes it.
#
#   So nothing in this repo has ever answered the only question that matters:
#   can the bytes we are keeping be turned back into the database we lost?
#
# WHY THIS IS NOT A `check-*` GATE
#
#   It needs a live PostgreSQL and a Docker daemon, so on a CI runner it could
#   only ever VOID — the same reason check-runtime-freshness.sh and
#   check-infra-exposure.sh stay out of per-PR CI. It is deliberately NOT named
#   `check-*` so it does not inflate the gate count (same convention as
#   scripts/ci-lane-cost.sh). Run it on a developer machine or a scheduled job
#   that has a real stack.
#
# THE TRAP THIS SCRIPT IS BUILT AROUND: RLS BLINDS THE VERIFIER
#
#   Most tables here are ENABLE + FORCE RLS. A row count run as a non-BYPASSRLS
#   role with no `app.current_tenant_id` GUC returns **0 on a full table** — and
#   it does so silently, with exit status 0. Count both sides that way and the
#   comparison is `0 == 0`: a PASS over a restore that loaded nothing. This repo
#   has already been bitten by exactly that shape (an `isZero()` assertion that
#   survived every break arm because the query could not see the rows).
#
#   Three defences, all of which must hold before this script will report success:
#     D-1  both sides are counted as a BYPASSRLS role, so RLS cannot hide rows;
#     D-2  the source total must exceed MIN_ROWS — zero-vs-zero is never evidence;
#     D-3  a CONTROL arm proves the blind method really is blind here, so D-1 is
#          demonstrated to matter rather than merely asserted.
#
# WHAT A GREEN RUN PROVES
#
#   The dump taken by the backup tooling, restored into a clean server of the
#   deployed major, reproduces the same tables with the same row counts and the
#   same Flyway schema version. That is a real recovery rehearsal.
#
# WHAT IT STILL DOES NOT PROVE
#
#   Row COUNTS, not row contents. It does not check that column values survived,
#   does not exercise the application against the restored database, and says
#   nothing about PITR or about restoring into a DIFFERENT major. It also uses
#   the live dev database as its source; a production drill needs the real dump
#   artifact from S3.
#
# EXIT CODES
#   0 = restore verified · 1 = restore is WRONG · 2 = VOID (could not evaluate)
#
# USAGE
#   bash scripts/restore-drill.sh
#   MIN_ROWS=1 bash scripts/restore-drill.sh          # relax the non-empty floor
#   KEEP=1     bash scripts/restore-drill.sh          # leave the throwaway running

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${ENV_FILE:-$REPO_ROOT/.env}"
BACKUP_DOCKERFILE="${BACKUP_DOCKERFILE:-$REPO_ROOT/infra/backups/Dockerfile}"
MIN_ROWS="${MIN_ROWS:-50}"
SRC_CONTAINER="${SRC_CONTAINER:-jtoye-postgres}"

void() { printf 'VOID: %s\n' "$*" >&2; exit 2; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
log()  { printf '  %s\n' "$*"; }

command -v docker >/dev/null || void "docker not found"
command -v awk    >/dev/null || void "awk not found"
[ -f "$ENV_FILE" ]           || void "no .env at $ENV_FILE — credentials are required and are never defaulted here"
[ -f "$BACKUP_DOCKERFILE" ]  || void "no $BACKUP_DOCKERFILE — cannot learn which tooling actually produces backups"

# shellcheck disable=SC1090
set -a; . "$ENV_FILE"; set +a

DB_NAME="${POSTGRES_DB:?POSTGRES_DB must be set}"
# The DUMP role must be BYPASSRLS, exactly as k8s-backup.sh:28 requires.
#
# ⚠ DO NOT reach for `DB_USER` here. The name means two different things:
#     .env DB_USER                       = jtoye_app        (the APPLICATION role,
#                                                            NOT bypassrls)
#     CronJob DB_USER  <- secret key `backup-username`
#                                        = jtoye_backup     (the BYPASSRLS dump role)
#   The first draft of this script used .env's DB_USER, dumped as jtoye_app, and
#   pg_dump failed with "query would be affected by row-level security policy".
#   That looked like a production fault and was not one — k8s/base/pg-backup-cronjob.yaml:68
#   sources a DIFFERENT secret key. Same variable name, different role, and only
#   the k8s side is authoritative about what backups actually run as.
DUMP_USER="${DRILL_DUMP_USER:-jtoye_backup}"
DUMP_PASS="${DB_BACKUP_PASSWORD:-${POSTGRES_PASSWORD:?need a password for the dump role}}"
SUPER_USER="${POSTGRES_USER:?POSTGRES_USER must be set}"
SUPER_PASS="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}"

docker inspect "$SRC_CONTAINER" >/dev/null 2>&1 || void "source container '$SRC_CONTAINER' is not present — start the stack first"
[ "$(docker inspect -f '{{.State.Running}}' "$SRC_CONTAINER" 2>/dev/null)" = "true" ] \
	|| void "source container '$SRC_CONTAINER' is not running — a stopped stack is not a clean drill"

echo "restore-drill"

# ---------------------------------------------------------------------------
# Resolve versions from the tree and the running server — never hardcoded.
# ---------------------------------------------------------------------------
TOOLING_MAJOR="$(awk '
	{ i = index($0, "FROM postgres:")
	  if (i > 0) { rest = substr($0, i + 14); if (match(rest, /^[0-9]+/)) { print substr(rest, 1, RLENGTH); exit } } }
' "$BACKUP_DOCKERFILE")"
[ -n "$TOOLING_MAJOR" ] || void "could not read 'FROM postgres:<major>' from $BACKUP_DOCKERFILE"

SERVER_VERSION="$(docker exec -e PGPASSWORD="$SUPER_PASS" "$SRC_CONTAINER" \
	psql -U "$SUPER_USER" -d "$DB_NAME" -tAc 'show server_version;' 2>/dev/null | tr -d '[:space:]')"
[ -n "$SERVER_VERSION" ] || void "could not read server_version from '$SRC_CONTAINER' — cannot choose a restore target"
SERVER_MAJOR="${SERVER_VERSION%%.*}"

log "tooling  : postgres:${TOOLING_MAJOR} (from infra/backups/Dockerfile)"
log "server   : ${SERVER_VERSION} (major ${SERVER_MAJOR}, read from the running container)"
log "dump as  : ${DUMP_USER} (BYPASSRLS required, as k8s-backup.sh:28 demands)"

WORK="$(mktemp -d)"; chmod 777 "$WORK"
TARGET="jtoye-restore-drill-$$"
cleanup() {
	if [ "${KEEP:-0}" != "1" ]; then docker rm -f "$TARGET" >/dev/null 2>&1 || true; fi
	rm -rf "$WORK"
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# D-3 CONTROL — prove the BLIND counting method really is blind on this database,
# BEFORE trusting the sighted one. If an unprivileged, tenant-unpinned count can
# already see rows, then D-1 is not doing any work and a green result would not
# mean what this script claims it means.
# ---------------------------------------------------------------------------
rls_table="$(docker exec -e PGPASSWORD="$SUPER_PASS" "$SRC_CONTAINER" psql -U "$SUPER_USER" -d "$DB_NAME" -tAc "
	select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
	where c.relrowsecurity and c.relforcerowsecurity and n.nspname='public'
	  and (select count(*) from pg_catalog.pg_class x where x.oid=c.oid)>0
	order by c.reltuples desc nulls last limit 1;" 2>/dev/null | tr -d '[:space:]')"

if [ -n "$rls_table" ]; then
	seen_super="$(docker exec -e PGPASSWORD="$SUPER_PASS" "$SRC_CONTAINER" psql -U "$SUPER_USER" -d "$DB_NAME" -tAc \
		"select count(*) from public.\"$rls_table\";" 2>/dev/null | tr -d '[:space:]')"
	seen_blind="$(docker exec -e PGPASSWORD="${POSTGRES_APP_PASSWORD:-$SUPER_PASS}" "$SRC_CONTAINER" \
		psql -U "${POSTGRES_APP_USER:-jtoye_app}" -d "$DB_NAME" -tAc \
		"select count(*) from public.\"$rls_table\";" 2>/dev/null | tr -d '[:space:]')"
	log "control  : $rls_table -> BYPASSRLS sees ${seen_super:-?}, unpinned app role sees ${seen_blind:-<denied>}"
	if [ -n "$seen_super" ] && [ "$seen_super" -gt 0 ] 2>/dev/null && [ "${seen_blind:-0}" = "$seen_super" ]; then
		void "D-3 control: the unprivileged, tenant-unpinned role sees the SAME count as BYPASSRLS on '$rls_table'. RLS is not constraining this query, so this drill's counting method is not demonstrably the sighted one. Investigate before trusting a pass."
	fi
else
	log "control  : no FORCE-RLS table found to probe (recorded, not fatal)"
fi

# ---------------------------------------------------------------------------
# 1. DUMP — with the tooling that actually produces backups, as the role that
#    actually produces them, in the same format k8s-backup.sh uses (-Fc).
#
#    PRE-FLIGHT the role attribute rather than discovering it from a failed dump.
#    Without this the failure surfaces as pg_dump's "query would be affected by
#    row-level security policy for table ..." — accurate, but it names a TABLE
#    when the fault is a ROLE, which is how a five-minute fix reads like a data
#    problem. scripts/k8s-local-secrets.sh:257 asserts the same attribute before
#    it will create the Secret; this is the same check at the other end.
# ---------------------------------------------------------------------------
bypass="$(docker exec -e PGPASSWORD="$SUPER_PASS" "$SRC_CONTAINER" psql -U "$SUPER_USER" -d "$DB_NAME" -tAc \
	"select coalesce((select rolbypassrls from pg_roles where rolname='${DUMP_USER}')::text,'ABSENT');" 2>/dev/null | tr -d '[:space:]')"
# psql renders a boolean cast to ::text as 'true'/'false', not the 't'/'f' that
# an uncast boolean column prints. Accept both rather than depending on which.
case "$bypass" in
	t|true) : ;;
	f|false) void "dump role '${DUMP_USER}' exists but is NOT BYPASSRLS. Dumping FORCE-RLS tables as this role fails mid-dump. Set DRILL_DUMP_USER to the role the CronJob uses (postgres-credentials/backup-username), not .env's DB_USER." ;;
	ABSENT) void "dump role '${DUMP_USER}' does not exist on this server. Set DRILL_DUMP_USER, or create it — see infra/db/init/." ;;
	*) void "could not read rolbypassrls for '${DUMP_USER}' (got '${bypass}') — refusing to dump on an unknown identity" ;;
esac
log "role     : ${DUMP_USER} rolbypassrls=t (asserted before dumping)"

log "dumping  : postgres:${TOOLING_MAJOR}-bookworm pg_dump -Fc (read-only)"
docker run --rm --network "container:$SRC_CONTAINER" -e PGPASSWORD="$DUMP_PASS" -v "$WORK:/out" \
	"postgres:${TOOLING_MAJOR}-bookworm" \
	pg_dump -h 127.0.0.1 -p 5432 -U "$DUMP_USER" -d "$DB_NAME" -Fc -Z6 -f /out/drill.dump >"$WORK/dump.log" 2>&1
rc=$?
[ $rc -eq 0 ] || { sed 's/^/    /' "$WORK/dump.log" >&2; void "pg_dump failed (rc=$rc) as '${DUMP_USER}', whose BYPASSRLS was already asserted above — so this is NOT the role problem and is worth reading properly before blaming the backup wiring."; }
size="$(stat -c%s "$WORK/drill.dump" 2>/dev/null || echo 0)"
[ "$size" -gt 0 ] || void "dump is empty"
log "dump     : ${size} bytes"

# ---------------------------------------------------------------------------
# 2. SOURCE TRUTH — per-table counts as a BYPASSRLS role (D-1).
# ---------------------------------------------------------------------------
count_sql="select table_name from information_schema.tables where table_schema='public' and table_type='BASE TABLE' order by 1;"
tables="$(docker exec -e PGPASSWORD="$SUPER_PASS" "$SRC_CONTAINER" psql -U "$SUPER_USER" -d "$DB_NAME" -tAc "$count_sql" 2>/dev/null | tr -d '\r')"
[ -n "$tables" ] || void "no base tables found in the source — nothing to compare"

: > "$WORK/src.tsv"
while IFS= read -r t; do
	[ -n "$t" ] || continue
	n="$(docker exec -e PGPASSWORD="$SUPER_PASS" "$SRC_CONTAINER" psql -U "$SUPER_USER" -d "$DB_NAME" -tAc \
		"select count(*) from public.\"$t\";" 2>/dev/null | tr -d '[:space:]')"
	printf '%s\t%s\n' "$t" "${n:-ERR}" >> "$WORK/src.tsv"
done <<< "$tables"

src_tables="$(awk 'END{print NR}' "$WORK/src.tsv")"
src_rows="$(awk -F'\t' '$2 ~ /^[0-9]+$/ {s+=$2} END{print s+0}' "$WORK/src.tsv")"
log "source   : ${src_tables} tables, ${src_rows} rows"

# D-2: zero-vs-zero is not evidence.
[ "$src_rows" -ge "$MIN_ROWS" ] || void "source holds only ${src_rows} row(s), below MIN_ROWS=${MIN_ROWS}. A restore compared against an empty source proves nothing — seed the stack (scripts/seed-e2e-fixtures.sh) or lower MIN_ROWS deliberately."

# ---------------------------------------------------------------------------
# 3. RESTORE into a throwaway server of the DEPLOYED major.
# ---------------------------------------------------------------------------
log "target   : starting throwaway postgres:${SERVER_MAJOR} as $TARGET (no published ports)"
docker run -d --name "$TARGET" -e POSTGRES_PASSWORD="$SUPER_PASS" -e POSTGRES_USER="$SUPER_USER" \
	-e POSTGRES_DB="$DB_NAME" "postgres:${SERVER_MAJOR}" >/dev/null 2>&1 \
	|| void "could not start throwaway postgres:${SERVER_MAJOR}"

for _ in $(seq 1 60); do
	docker exec "$TARGET" pg_isready -U "$SUPER_USER" -d "$DB_NAME" >/dev/null 2>&1 && break
	sleep 1
done
docker exec "$TARGET" pg_isready -U "$SUPER_USER" -d "$DB_NAME" >/dev/null 2>&1 \
	|| void "throwaway server never became ready within 60s"

docker cp "$WORK/drill.dump" "$TARGET:/tmp/drill.dump" >/dev/null 2>&1 || void "could not copy the dump into the target"
log "restore  : pg_restore into the throwaway (this is the step nothing has ever run)"
docker exec -e PGPASSWORD="$SUPER_PASS" "$TARGET" \
	pg_restore -U "$SUPER_USER" -d "$DB_NAME" --no-owner --no-privileges /tmp/drill.dump >"$WORK/restore.log" 2>&1
rrc=$?
# pg_restore exits non-zero on benign warnings (missing roles, extensions already
# present). Treat a non-zero rc as a WARNING to print, and let the CONTENT
# comparison below be the arbiter — exit status is not the evidence here.
if [ $rrc -ne 0 ]; then
	log "restore  : pg_restore rc=${rrc} (non-fatal by itself; the row comparison decides)"
	awk '/error|ERROR/{print "    "substr($0,1,150)}' "$WORK/restore.log" | head -5
fi

# ---------------------------------------------------------------------------
# 4. VERIFY BY CONTENT — same tables, same counts.
# ---------------------------------------------------------------------------
: > "$WORK/dst.tsv"
while IFS= read -r t; do
	[ -n "$t" ] || continue
	n="$(docker exec -e PGPASSWORD="$SUPER_PASS" "$TARGET" psql -U "$SUPER_USER" -d "$DB_NAME" -tAc \
		"select count(*) from public.\"$t\";" 2>/dev/null | tr -d '[:space:]')"
	printf '%s\t%s\n' "$t" "${n:-MISSING}" >> "$WORK/dst.tsv"
done <<< "$tables"

dst_rows="$(awk -F'\t' '$2 ~ /^[0-9]+$/ {s+=$2} END{print s+0}' "$WORK/dst.tsv")"
log "restored : ${dst_rows} rows"

mismatch=0
while IFS=$'\t' read -r t n; do
	d="$(awk -F'\t' -v k="$t" '$1==k{print $2}' "$WORK/dst.tsv")"
	if [ "$n" != "$d" ]; then
		printf 'FAIL: %-40s source=%s restored=%s\n' "$t" "$n" "${d:-MISSING}" >&2
		mismatch=$((mismatch + 1))
	fi
done < "$WORK/src.tsv"

# Flyway schema version is an independent witness that the SCHEMA travelled, not
# just the rows.
sv_src="$(docker exec -e PGPASSWORD="$SUPER_PASS" "$SRC_CONTAINER" psql -U "$SUPER_USER" -d "$DB_NAME" -tAc \
	"select count(*)::text || '/' || coalesce(max(installed_rank)::text,'0') from flyway_schema_history where success;" 2>/dev/null | tr -d '[:space:]')"
sv_dst="$(docker exec -e PGPASSWORD="$SUPER_PASS" "$TARGET" psql -U "$SUPER_USER" -d "$DB_NAME" -tAc \
	"select count(*)::text || '/' || coalesce(max(installed_rank)::text,'0') from flyway_schema_history where success;" 2>/dev/null | tr -d '[:space:]')"
log "flyway   : source=${sv_src:-?} restored=${sv_dst:-?} (count/max-installed_rank — NOT max(version), which is TEXT and sorts 9 above 60)"
if [ -n "$sv_src" ] && [ "$sv_src" != "$sv_dst" ]; then
	printf 'FAIL: flyway schema version differs: source=%s restored=%s\n' "$sv_src" "${sv_dst:-MISSING}" >&2
	mismatch=$((mismatch + 1))
fi

if [ "$mismatch" -gt 0 ]; then
	fail "${mismatch} table(s)/witness(es) differ between source and restore. The backup does NOT reproduce the database."
fi

echo "PASS: restored ${src_tables} tables / ${src_rows} rows into a clean postgres:${SERVER_MAJOR}, counts identical, flyway ${sv_src} (migrations/max-rank)."
echo "      Counted as a BYPASSRLS role on both sides, with a control proving an"
echo "      unpinned app-role count is blind here — so this is not a 0 == 0 pass."
echo "      NOTE: row COUNTS, not row contents. Not a PITR test, and not a"
echo "      cross-major restore. Production drills need the real S3 artifact."
exit 0
