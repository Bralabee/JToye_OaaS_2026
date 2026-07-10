#!/bin/bash
#
# JToye OaaS PostgreSQL Backup Script
# Performs automated database backups with retention policy
#
# Usage:
#   ./backup.sh                    # Full backup
#   ./backup.sh --restore <file>   # Restore from backup
#
# Features:
# - Automated daily backups
# - 30-day retention policy
# - Compression (gzip)
# - Backup verification
# - Email notifications (optional)
#

set -e  # Exit on error
set -o pipefail  # Catch errors in pipes

# ===========================
# CONFIGURATION
# ===========================

# Backup directory (must exist and be writable)
# Default resolves OFF the repo tree so the nightly cron never writes dumps into
# a tracked directory (P0-3). Override with the BACKUP_DIR env var if needed.
BACKUP_DIR="${BACKUP_DIR:-$HOME/jtoye-db-backups}"

# Database connection (override with environment variables)
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5433}"
DB_NAME="${DB_NAME:-jtoye}"
DB_USER="${DB_USER:-jtoye}"

# Retention policy (days)
RETENTION_DAYS="${RETENTION_DAYS:-30}"

# Docker container name (if running in Docker)
DOCKER_CONTAINER="${DOCKER_CONTAINER:-jtoye-postgres}"

# Notification email (optional)
NOTIFY_EMAIL="${NOTIFY_EMAIL:-}"

# Minimum acceptable compressed dump size in bytes (Issue #119). A real gzipped
# pg_dump is far larger than this; anything smaller is treated as an error-log
# gzip / truncated dump and rejected by verify_backup. Override via env.
MIN_BACKUP_BYTES="${MIN_BACKUP_BYTES:-1000}"

# Metrics sinks (Issue #119). Both are env-gated and OFF by default, so this
# script has NO hard dependency on a node-exporter textfile collector or a
# Pushgateway existing in the stack — enable them only where one is wired up.
#   METRICS_TEXTFILE_DIR: a directory a node-exporter textfile collector scrapes.
#   PUSHGATEWAY_URL:       base URL of a Prometheus Pushgateway (no trailing /metrics).
METRICS_TEXTFILE_DIR="${METRICS_TEXTFILE_DIR:-}"
PUSHGATEWAY_URL="${PUSHGATEWAY_URL:-}"

# ===========================
# COLORS
# ===========================

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ===========================
# FUNCTIONS
# ===========================

log_info() {
    echo -e "${BLUE}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} INFO: $1"
}

log_success() {
    echo -e "${GREEN}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} SUCCESS: $1"
}

log_warning() {
    echo -e "${YELLOW}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} WARNING: $1"
}

log_error() {
    echo -e "${RED}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} ERROR: $1"
}

send_notification() {
    local subject="$1"
    local message="$2"

    if [ -n "$NOTIFY_EMAIL" ]; then
        echo "$message" | mail -s "$subject" "$NOTIFY_EMAIL" 2>/dev/null || true
    fi
}

# Emit the backup outcome as Prometheus metrics (Issue #119). Both sinks are
# env-gated and OFF by default, so this is a no-op unless an operator wires one
# up. Never fails the backup — always returns 0.
#   arg1: 1 = success, 0 = failure
emit_metrics() {
    local status="$1"
    local now
    now=$(date +%s)

    # --- node-exporter textfile collector sink ---
    if [ -n "$METRICS_TEXTFILE_DIR" ]; then
        if [ -d "$METRICS_TEXTFILE_DIR" ] && [ -w "$METRICS_TEXTFILE_DIR" ]; then
            local promfile="$METRICS_TEXTFILE_DIR/jtoye_db_backup.prom"

            # Preserve the previous success timestamp on a failure so a staleness
            # alert keeps counting from the last GOOD backup, not from "now".
            local last_success_ts=""
            if [ "$status" -eq 1 ]; then
                last_success_ts="$now"
            elif [ -f "$promfile" ]; then
                last_success_ts=$(grep -E '^jtoye_db_backup_last_success_timestamp_seconds ' "$promfile" 2>/dev/null | awk '{print $2}' | tail -n1 || true)
            fi

            # Atomic write (tmp + mv) so the collector never reads a half-written file.
            local tmpfile
            tmpfile=$(mktemp "${promfile}.XXXXXX")
            {
                echo "# HELP jtoye_db_backup_success Result of the last DB backup run (1=success, 0=failure)."
                echo "# TYPE jtoye_db_backup_success gauge"
                echo "jtoye_db_backup_success $status"
                echo "# HELP jtoye_db_backup_last_success_timestamp_seconds Unix time of the last successful DB backup."
                echo "# TYPE jtoye_db_backup_last_success_timestamp_seconds gauge"
                if [ -n "$last_success_ts" ]; then
                    echo "jtoye_db_backup_last_success_timestamp_seconds $last_success_ts"
                fi
            } > "$tmpfile"
            mv -f "$tmpfile" "$promfile"
            log_info "Wrote backup metrics to $promfile (success=$status)"
        else
            log_warning "METRICS_TEXTFILE_DIR is set but not a writable directory: $METRICS_TEXTFILE_DIR"
        fi
    fi

    # --- Pushgateway sink ---
    if [ -n "$PUSHGATEWAY_URL" ]; then
        if command -v curl &> /dev/null; then
            local url="${PUSHGATEWAY_URL%/}/metrics/job/jtoye_db_backup/instance/${DB_NAME}"
            local body="jtoye_db_backup_success ${status}"$'\n'
            if [ "$status" -eq 1 ]; then
                body="${body}jtoye_db_backup_last_success_timestamp_seconds ${now}"$'\n'
            fi
            if curl -s --max-time 10 --data-binary "$body" "$url" >/dev/null 2>&1; then
                log_info "Pushed backup metrics to $PUSHGATEWAY_URL"
            else
                log_warning "Failed to push metrics to $PUSHGATEWAY_URL (non-fatal)"
            fi
        else
            log_warning "PUSHGATEWAY_URL is set but curl is not available — skipping push"
        fi
    fi

    return 0
}

# Centralised failure handling (Issue #119): log the pg_dump error tail, discard
# any half-written artifact so no plausible-looking dump is left behind, raise
# the failure alert signal, and notify. Caller must still `exit 1` afterwards.
handle_backup_failure() {
    local gz="$1"
    local errlog="$2"
    local reason="$3"

    log_error "$reason"
    if [ -f "$errlog" ] && [ -s "$errlog" ]; then
        log_error "pg_dump error log tail ($errlog):"
        while IFS= read -r line; do log_error "  $line"; done < <(tail -n 5 "$errlog")
    fi

    # Leave nothing behind that could be mistaken for a valid backup.
    rm -f "$gz"

    emit_metrics 0

    send_notification \
        "JToye Backup FAILED - $DB_NAME" \
        "Backup failed at $(date): ${reason}
Check the pg_dump error log for details: ${errlog}"
}

check_prerequisites() {
    log_info "Checking prerequisites..."

    # Check if backup directory exists
    if [ ! -d "$BACKUP_DIR" ]; then
        log_warning "Backup directory does not exist. Creating: $BACKUP_DIR"
        mkdir -p "$BACKUP_DIR"
    fi

    # Check if directory is writable
    if [ ! -w "$BACKUP_DIR" ]; then
        log_error "Backup directory is not writable: $BACKUP_DIR"
        exit 1
    fi

    # Check if Docker is available and container is running
    if command -v docker &> /dev/null; then
        if docker ps | grep -q "$DOCKER_CONTAINER"; then
            log_success "Docker container $DOCKER_CONTAINER is running"
            USE_DOCKER=true
        else
            log_warning "Docker container $DOCKER_CONTAINER not found, using direct connection"
            USE_DOCKER=false
        fi
    else
        log_warning "Docker not available, using direct connection"
        USE_DOCKER=false
    fi

    # Check if pg_dump is available (if not using Docker)
    if [ "$USE_DOCKER" = false ]; then
        if ! command -v pg_dump &> /dev/null; then
            log_error "pg_dump not found. Install PostgreSQL client tools."
            exit 1
        fi
    fi
}

create_backup() {
    local timestamp
    timestamp=$(date +%Y%m%d_%H%M%S)
    local backup_file="$BACKUP_DIR/jtoye_${DB_NAME}_${timestamp}.sql"
    local backup_file_gz="${backup_file}.gz"
    # pg_dump stderr is captured here so ONLY SQL ever lands in the .sql.gz.
    local errlog="${backup_file}.pg_dump.log"
    local pipestat dump_rc gzip_rc

    log_info "Starting backup: $DB_NAME"
    log_info "Backup file: $backup_file_gz"

    # Perform backup. `set -e`/`pipefail` are active, so disable them around the
    # pipe and read PIPESTATUS directly — otherwise the script would abort on a
    # pg_dump failure before we can clean up, and $? would only reflect gzip.
    set +e
    if [ "$USE_DOCKER" = true ]; then
        log_info "Using Docker exec for backup..."
        docker exec "$DOCKER_CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" \
            --clean --if-exists 2>"$errlog" | gzip > "$backup_file_gz"
        pipestat=("${PIPESTATUS[@]}")
    else
        log_info "Using direct pg_dump connection..."
        PGPASSWORD="$DB_PASSWORD" pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
            --clean --if-exists 2>"$errlog" | gzip > "$backup_file_gz"
        pipestat=("${PIPESTATUS[@]}")
    fi
    set -e
    dump_rc="${pipestat[0]}"
    gzip_rc="${pipestat[1]}"

    # Require BOTH pg_dump (index 0) and gzip (index 1) to have succeeded.
    if [ "$dump_rc" -ne 0 ] || [ "$gzip_rc" -ne 0 ]; then
        handle_backup_failure "$backup_file_gz" "$errlog" \
            "Backup failed (pg_dump exit=${dump_rc}, gzip exit=${gzip_rc})"
        exit 1
    fi

    # Content verification — reject truncated dumps / error-log gzips.
    if ! verify_backup "$backup_file_gz"; then
        handle_backup_failure "$backup_file_gz" "$errlog" \
            "Backup verification failed — content check did not pass"
        exit 1
    fi

    local file_size
    file_size=$(du -h "$backup_file_gz" | cut -f1)
    log_success "Backup completed successfully"
    log_info "Backup size: $file_size"

    # Without --verbose pg_dump is silent on success, so the errlog is normally
    # empty — drop it to keep the backup dir clean; keep it only if it has content.
    [ -s "$errlog" ] || rm -f "$errlog"

    # Apply retention policy
    apply_retention_policy

    # Success signal: metrics + optional email.
    emit_metrics 1

    send_notification \
        "JToye Backup Success - $DB_NAME" \
        "Backup completed successfully at $(date)
        File: $backup_file_gz
        Size: $file_size"

    echo "$backup_file_gz"
}

verify_backup() {
    local backup_file="$1"

    log_info "Verifying backup integrity..."

    # Check if file exists and is not empty
    if [ ! -f "$backup_file" ]; then
        log_error "Backup file not found: $backup_file"
        return 1
    fi

    if [ ! -s "$backup_file" ]; then
        log_error "Backup file is empty: $backup_file"
        return 1
    fi

    # Size floor — a real compressed dump is well above this; a tiny file is an
    # error-log gzip or a truncated dump (Issue #119).
    local size_bytes
    size_bytes=$(stat -c%s "$backup_file" 2>/dev/null || wc -c < "$backup_file")
    if [ "$size_bytes" -lt "$MIN_BACKUP_BYTES" ]; then
        log_error "Backup below minimum size floor (${size_bytes} < ${MIN_BACKUP_BYTES} bytes): $backup_file"
        return 1
    fi

    # Check gzip integrity
    if ! gzip -t "$backup_file" 2>/dev/null; then
        log_error "Backup file is corrupted (gzip -t failed)"
        return 1
    fi

    # Content marker — a complete plain-format pg_dump ends with this comment.
    # `tail` drains the whole decompressed stream (so gunzip never takes SIGPIPE
    # under pipefail) before grep checks the final lines.
    if gunzip -c "$backup_file" 2>/dev/null | tail -n 20 | grep -q "PostgreSQL database dump complete"; then
        log_success "Backup file integrity + content verified"
        return 0
    else
        log_error "Backup missing pg_dump completion marker — not a valid dump: $backup_file"
        return 1
    fi
}

apply_retention_policy() {
    log_info "Applying retention policy (keep last $RETENTION_DAYS days)..."

    local deleted_count=0

    # Find and delete old backups. Feed the loop via process substitution (not a
    # `find | while` pipe) so `deleted_count` survives in THIS shell, and use
    # `deleted_count=$((deleted_count + 1))` instead of `((deleted_count++))` —
    # the latter returns exit 1 when the pre-increment value is 0 and would abort
    # the whole run under `set -e` on the very first prune (Issue #119).
    while IFS= read -r -d '' file; do
        log_info "Deleting old backup: $(basename "$file")"
        rm -f "$file"
        deleted_count=$((deleted_count + 1))
    done < <(find "$BACKUP_DIR" -name "jtoye_*.sql.gz" -type f -mtime +"$RETENTION_DAYS" -print0)

    if [ "$deleted_count" -gt 0 ]; then
        log_info "Deleted $deleted_count old backup(s)"
    else
        log_info "No old backups to delete"
    fi

    # Show current backups
    local backup_count
    backup_count=$(find "$BACKUP_DIR" -name "jtoye_*.sql.gz" -type f | wc -l)
    log_info "Total backups retained: $backup_count"
}

restore_backup() {
    local backup_file="$1"

    if [ ! -f "$backup_file" ]; then
        log_error "Backup file not found: $backup_file"
        exit 1
    fi

    log_warning "========================================="
    log_warning "WARNING: This will REPLACE the database!"
    log_warning "Database: $DB_NAME"
    log_warning "Backup: $(basename "$backup_file")"
    log_warning "========================================="
    read -p "Are you sure you want to continue? (yes/no): " -r
    echo

    if [[ ! $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
        log_info "Restore cancelled by user"
        exit 0
    fi

    log_info "Starting restore from: $backup_file"

    # Verify backup before restoring
    if ! verify_backup "$backup_file"; then
        log_error "Backup verification failed. Aborting restore."
        exit 1
    fi

    # Perform restore
    if [ "$USE_DOCKER" = true ]; then
        log_info "Using Docker exec for restore..."
        gunzip -c "$backup_file" | docker exec -i "$DOCKER_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME"
    else
        log_info "Using direct psql connection..."
        PGPASSWORD="$DB_PASSWORD" gunzip -c "$backup_file" | psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME"
    fi

    if [ $? -eq 0 ]; then
        log_success "Restore completed successfully"
        send_notification \
            "JToye Restore Success - $DB_NAME" \
            "Database restored successfully at $(date)
            From: $backup_file"
    else
        log_error "Restore failed"
        send_notification \
            "JToye Restore FAILED - $DB_NAME" \
            "Database restore failed at $(date)
            From: $backup_file"
        exit 1
    fi
}

list_backups() {
    log_info "Available backups in $BACKUP_DIR:"
    echo ""

    find "$BACKUP_DIR" -name "jtoye_*.sql.gz" -type f -printf "%T@ %Tc %p\n" | \
    sort -rn | \
    awk '{$1=""; print}' | \
    nl -w2 -s") "

    echo ""
    local total_size=$(du -sh "$BACKUP_DIR" | cut -f1)
    log_info "Total backup size: $total_size"
}

show_usage() {
    cat << EOF
JToye OaaS PostgreSQL Backup Script

Usage:
    $0                         # Create a new backup
    $0 --restore <file>        # Restore from backup file
    $0 --list                  # List all available backups
    $0 --verify <file>         # Verify backup integrity
    $0 --help                  # Show this help message

Environment Variables:
    BACKUP_DIR              Backup directory (default: \$HOME/jtoye-db-backups)
    DB_HOST                 Database host (default: localhost)
    DB_PORT                 Database port (default: 5433)
    DB_NAME                 Database name (default: jtoye)
    DB_USER                 Database user (default: jtoye)
    DB_PASSWORD             Database password (required for non-Docker)
    DOCKER_CONTAINER        Docker container name (default: jtoye-postgres)
    RETENTION_DAYS          Backup retention in days (default: 30)
    NOTIFY_EMAIL            Email for notifications (optional)
    MIN_BACKUP_BYTES        Minimum valid compressed dump size (default: 1000)
    METRICS_TEXTFILE_DIR    node-exporter textfile collector dir (optional, off if unset)
    PUSHGATEWAY_URL         Prometheus Pushgateway base URL (optional, off if unset)

Examples:
    # Create backup
    $0

    # Restore from specific backup
    $0 --restore /path/to/backup.sql.gz

    # List available backups
    $0 --list

Cron Job Setup:
    # Daily backup at 2 AM
    0 2 * * * /path/to/backup.sh >> /var/log/jtoye-backup.log 2>&1

EOF
}

# ===========================
# MAIN
# ===========================

main() {
    case "${1:-}" in
        --restore)
            if [ -z "${2:-}" ]; then
                log_error "Backup file path required for restore"
                show_usage
                exit 1
            fi
            check_prerequisites
            restore_backup "$2"
            ;;
        --list)
            list_backups
            ;;
        --verify)
            if [ -z "${2:-}" ]; then
                log_error "Backup file path required for verification"
                show_usage
                exit 1
            fi
            verify_backup "$2"
            ;;
        --help|-h)
            show_usage
            ;;
        "")
            check_prerequisites
            create_backup
            ;;
        *)
            log_error "Unknown option: $1"
            show_usage
            exit 1
            ;;
    esac
}

# Run main function
main "$@"
