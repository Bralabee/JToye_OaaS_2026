#!/usr/bin/env bash
#
# k8s-backup.sh — JToye OaaS PostgreSQL backup for the Kubernetes CronJob (#90).
#
# Baked into infra/backups/Dockerfile and run as the CronJob's ENTRYPOINT. Unlike
# the host-oriented infra/backups/backup.sh (local dir, docker exec), this writes
# a custom-format dump and uploads it to Azure Blob Storage with blobctl
# (Phase 36, BLOB-06). It never prunes and never deletes.
#
# Hardening (why this is not the old inline CronJob script):
#   - Runs on a Debian base (GNU coreutils/grep) under `set -euo pipefail`.
#   - The uploader (blobctl, a static Go binary) is baked into the image — no
#     runtime package install (which the default-deny NetworkPolicy blocks).
#   - Connects as a BYPASSRLS role (DB_USER) so FORCE-RLS tables dump in full;
#     the app role would silently capture ZERO tenant rows. The size floor and
#     the TOC listing below both PASS on such a dump; only restore-and-count
#     catches it (scripts/check-backup-restore-drill.sh, run nightly).
#   - Fail-loud: explicit rc checks, size floor + `pg_restore --list` content
#     verification, and the half-written artifact is deleted on any failure so no
#     plausible-looking-but-empty dump is ever uploaded.
#   - Missing Blob configuration fails the job BEFORE the dump, not after it.
#
# Credentials: STORAGE_AUTH_MODE=workload-identity in staging/production (AKS
# Workload Identity; no key, SAS or connection string stored anywhere, D-02);
# STORAGE_AUTH_MODE=connection-string with the Azurite emulator string locally.
# blobctl reads STORAGE_ENDPOINT / STORAGE_CONNECTION_STRING / AZURE_* itself.
#
# All configuration comes from the environment (12-factor); nothing is hardcoded.
set -euo pipefail

# --- required config (fail fast if unset) ---
: "${DB_HOST:?DB_HOST is required}"
: "${DB_NAME:?DB_NAME is required}"
: "${DB_USER:?DB_USER is required (must be a BYPASSRLS role)}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${BACKUP_CONTAINER:?BACKUP_CONTAINER is required (the Blob container, e.g. jtoye-db-backups)}"
: "${STORAGE_AUTH_MODE:?STORAGE_AUTH_MODE is required (workload-identity | connection-string)}"

# --- optional config with defaults ---
DB_PORT="${DB_PORT:-5432}"
BACKUP_PREFIX="${BACKUP_PREFIX:-backups}"
MIN_BACKUP_BYTES="${MIN_BACKUP_BYTES:-1000}"

TIMESTAMP="$(date -u +%Y%m%d-%H%M%S)"
FILENAME="jtoye-backup-${TIMESTAMP}.dump"
TMP="/tmp/${FILENAME}"
ERRLOG="/tmp/pg_dump.err"
DEST="${BACKUP_PREFIX}/${FILENAME}"

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

# --- upload. blobctl never overwrites (If-None-Match: *): an existing blob of the
#     same name exits 3, which fails the job rather than replacing a good dump. ---
log "Uploading to ${BACKUP_CONTAINER}/${DEST}"
blobctl upload "$TMP" "$BACKUP_CONTAINER" "$DEST" || fail "Blob upload failed"

# --- retention. There is deliberately NO prune here. Retention belongs to the
#     backup container itself: a time-based immutability (WORM) policy, blob soft
#     delete and a lifecycle delete rule (D-01; docs/runbooks/azure-blob-provisioning.md).
#     So the CronJob identity is write-only and cannot destroy old dumps, even if
#     it is compromised. It also cannot LIST, so there is no post-upload read-back.
#     Against local Azurite there is no lifecycle rule, so dev dumps accumulate
#     (dev only, accepted; docs/runbooks/backups.md). ---

rm -f "$TMP" "$ERRLOG"
log "Backup complete: ${FILENAME}"
