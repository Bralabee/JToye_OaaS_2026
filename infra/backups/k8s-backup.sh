#!/usr/bin/env bash
#
# k8s-backup.sh — JToye OaaS PostgreSQL backup for the Kubernetes CronJob (#90).
#
# Baked into infra/backups/Dockerfile and run as the CronJob's ENTRYPOINT. Unlike
# the host-oriented infra/backups/backup.sh (local dir, docker exec), this streams
# a custom-format dump straight to S3 and prunes S3 by embedded date.
#
# Hardening (why this is not the old inline CronJob script):
#   - Runs on a Debian base (GNU coreutils/grep) so `date -d` / `grep -oE` work
#     under `set -euo pipefail` (busybox postgres:15-alpine did not).
#   - aws-cli is baked into the image — no runtime `apk add` (which the
#     default-deny NetworkPolicy blocks anyway).
#   - Connects as a BYPASSRLS role (DB_USER) so FORCE-RLS tables dump in full;
#     the app role would silently capture ZERO tenant rows.
#   - Fail-loud: explicit rc checks, size floor + `pg_restore --list` content
#     verification, and the half-written artifact is deleted on any failure so no
#     plausible-looking-but-empty dump is ever uploaded.
#   - Retention prune tolerates an empty bucket (no pipefail abort) and never
#     aborts the job on a single failed delete.
#
# All configuration comes from the environment (12-factor); nothing is hardcoded.
set -euo pipefail

# --- required config (fail fast if unset) ---
: "${DB_HOST:?DB_HOST is required}"
: "${DB_NAME:?DB_NAME is required}"
: "${DB_USER:?DB_USER is required (must be a BYPASSRLS role)}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${S3_BUCKET:?S3_BUCKET is required}"

# --- optional config with defaults ---
DB_PORT="${DB_PORT:-5432}"
S3_PREFIX="${S3_PREFIX:-backups}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
MIN_BACKUP_BYTES="${MIN_BACKUP_BYTES:-1000}"
# S3_ENDPOINT is optional (set it for MinIO / non-AWS S3; unset for real AWS).
S3_ENDPOINT="${S3_ENDPOINT:-}"
ENDPOINT_ARG=()
[ -n "$S3_ENDPOINT" ] && ENDPOINT_ARG=(--endpoint-url "$S3_ENDPOINT")

TIMESTAMP="$(date -u +%Y%m%d-%H%M%S)"
FILENAME="jtoye-backup-${TIMESTAMP}.dump"
TMP="/tmp/${FILENAME}"
ERRLOG="/tmp/pg_dump.err"
DEST="s3://${S3_BUCKET}/${S3_PREFIX}/${FILENAME}"

log()  { echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }
fail() { log "ERROR: $*"; rm -f "$TMP"; exit 1; }

log "Starting backup of ${DB_NAME} on ${DB_HOST}:${DB_PORT} as ${DB_USER} (BYPASSRLS expected)"

# --- dump (custom format, compressed). Explicit rc check: pg_dump can write a
#     partial file then exit non-zero, so never trust the file's mere existence. ---
if ! pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
      --no-owner --no-acl --format=custom --compress=6 -f "$TMP" 2>"$ERRLOG"; then
  log "pg_dump stderr tail:"; tail -n 20 "$ERRLOG" 2>/dev/null || true
  fail "pg_dump failed"
fi

# --- verify: size floor rejects truncated/near-empty dumps; pg_restore --list
#     confirms the archive is structurally readable (custom-format equivalent of
#     the plain-format completion marker). ---
SIZE="$(stat -c%s "$TMP" 2>/dev/null || wc -c < "$TMP")"
[ "$SIZE" -ge "$MIN_BACKUP_BYTES" ] || fail "dump below size floor (${SIZE} < ${MIN_BACKUP_BYTES} bytes)"
pg_restore --list "$TMP" >/dev/null 2>&1 || fail "dump is not a readable pg_restore archive"
log "Dump verified: ${SIZE} bytes, archive readable"

# --- upload ---
log "Uploading to ${DEST}"
aws s3 cp "$TMP" "$DEST" "${ENDPOINT_ARG[@]}" || fail "S3 upload failed"

# --- prune old objects by embedded YYYYMMDD. Capture the listing to a var first
#     so an empty bucket (aws s3 ls exit 1) does not abort the job under pipefail. ---
CUTOFF="$(date -u -d "-${RETENTION_DAYS} days" +%Y%m%d)"
log "Pruning objects older than ${CUTOFF} (${RETENTION_DAYS}d retention)"
LISTING="$(aws s3 ls "s3://${S3_BUCKET}/${S3_PREFIX}/" "${ENDPOINT_ARG[@]}" 2>/dev/null || true)"
pruned=0
while IFS= read -r line; do
  [ -n "$line" ] || continue
  fname="$(printf '%s' "$line" | awk '{print $4}')"
  [ -n "$fname" ] || continue
  fdate="$(printf '%s' "$fname" | grep -oE '[0-9]{8}' | head -1 || true)"
  if [ -n "$fdate" ] && [ "$fdate" -lt "$CUTOFF" ]; then
    log "Pruning old backup: ${fname}"
    if aws s3 rm "s3://${S3_BUCKET}/${S3_PREFIX}/${fname}" "${ENDPOINT_ARG[@]}"; then
      pruned=$((pruned + 1))
    else
      log "WARN: failed to prune ${fname} (continuing)"
    fi
  fi
done <<< "$LISTING"
log "Pruned ${pruned} old backup(s)"

rm -f "$TMP" "$ERRLOG"
log "Backup complete: ${FILENAME}"
