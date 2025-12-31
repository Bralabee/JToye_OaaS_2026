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
BACKUP_DIR="${BACKUP_DIR:-/home/sanmi/IdeaProjects/JToye_OaaS_2026/backups}"

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
    local timestamp=$(date +%Y%m%d_%H%M%S)
    local backup_file="$BACKUP_DIR/jtoye_${DB_NAME}_${timestamp}.sql"
    local backup_file_gz="${backup_file}.gz"

    log_info "Starting backup: $DB_NAME"
    log_info "Backup file: $backup_file_gz"

    # Perform backup
    if [ "$USE_DOCKER" = true ]; then
        log_info "Using Docker exec for backup..."
        docker exec "$DOCKER_CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" --clean --if-exists --verbose 2>&1 | \
            gzip > "$backup_file_gz"
    else
        log_info "Using direct pg_dump connection..."
        PGPASSWORD="$DB_PASSWORD" pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
            --clean --if-exists --verbose 2>&1 | gzip > "$backup_file_gz"
    fi

    if [ $? -eq 0 ]; then
        local file_size=$(du -h "$backup_file_gz" | cut -f1)
        log_success "Backup completed successfully"
        log_info "Backup size: $file_size"

        # Verify backup integrity
        verify_backup "$backup_file_gz"

        # Apply retention policy
        apply_retention_policy

        # Send success notification
        send_notification \
            "JToye Backup Success - $DB_NAME" \
            "Backup completed successfully at $(date)
            File: $backup_file_gz
            Size: $file_size"

        echo "$backup_file_gz"
    else
        log_error "Backup failed"
        send_notification \
            "JToye Backup FAILED - $DB_NAME" \
            "Backup failed at $(date)
            Check logs for details."
        exit 1
    fi
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

    # Check gzip integrity
    if gzip -t "$backup_file" 2>/dev/null; then
        log_success "Backup file integrity verified"
        return 0
    else
        log_error "Backup file is corrupted"
        return 1
    fi
}

apply_retention_policy() {
    log_info "Applying retention policy (keep last $RETENTION_DAYS days)..."

    local deleted_count=0

    # Find and delete old backups
    find "$BACKUP_DIR" -name "jtoye_*.sql.gz" -type f -mtime +$RETENTION_DAYS -print0 | \
    while IFS= read -r -d '' file; do
        log_info "Deleting old backup: $(basename "$file")"
        rm -f "$file"
        ((deleted_count++))
    done

    if [ $deleted_count -gt 0 ]; then
        log_info "Deleted $deleted_count old backup(s)"
    else
        log_info "No old backups to delete"
    fi

    # Show current backups
    local backup_count=$(find "$BACKUP_DIR" -name "jtoye_*.sql.gz" -type f | wc -l)
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
    BACKUP_DIR              Backup directory (default: ./backups)
    DB_HOST                 Database host (default: localhost)
    DB_PORT                 Database port (default: 5433)
    DB_NAME                 Database name (default: jtoye)
    DB_USER                 Database user (default: jtoye)
    DB_PASSWORD             Database password (required for non-Docker)
    DOCKER_CONTAINER        Docker container name (default: jtoye-postgres)
    RETENTION_DAYS          Backup retention in days (default: 30)
    NOTIFY_EMAIL            Email for notifications (optional)

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
