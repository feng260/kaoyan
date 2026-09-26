#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

COMPOSE_PROJECT_DIR="${COMPOSE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/yanzhong/mysql}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

[[ "$RETENTION_DAYS" =~ ^[0-9]+$ ]] && (( 10#$RETENTION_DAYS > 0 )) || { printf 'RETENTION_DAYS must be a positive integer\n' >&2; exit 2; }
[[ -f "$COMPOSE_PROJECT_DIR/docker-compose.yml" && -f "$COMPOSE_PROJECT_DIR/.env" ]] || { printf 'Missing compose file or .env in %s\n' "$COMPOSE_PROJECT_DIR" >&2; exit 2; }
command -v docker >/dev/null || { printf 'docker is required\n' >&2; exit 2; }
command -v gzip >/dev/null || { printf 'gzip is required\n' >&2; exit 2; }
mkdir -p -- "$BACKUP_DIR"
BACKUP_DIR="$(cd "$BACKUP_DIR" && pwd)"
log_file="$BACKUP_DIR/backup.log"
log() { printf '[%s] %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*" | tee -a "$log_file" >&2; }
cd "$COMPOSE_PROJECT_DIR"
file="$BACKUP_DIR/yanzhong-$(date -u '+%Y%m%dT%H%M%SZ')-$$.sql.gz"
tmp="$file.partial"
cleanup() { rm -f -- "$tmp"; }
on_error() { log "ERROR backup failed (exit $1)"; }
trap cleanup EXIT
trap 'on_error $?' ERR
log "Starting backup to $file"
# Password comes from the Compose service environment; it is never printed in host logs.
docker compose exec -T mysql sh -c 'exec mysqldump --single-transaction --quick --routines --triggers --events --databases yanzhong -uroot -p"$MYSQL_ROOT_PASSWORD"' | gzip -c > "$tmp"
gzip -t -- "$tmp"
mv -- "$tmp" "$file"
log "Backup complete: $file ($(wc -c < "$file") bytes)"
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'yanzhong-*.sql.gz' -mtime "+$((10#$RETENTION_DAYS - 1))" -print -delete | while IFS= read -r old; do log "Removed expired backup: $old"; done
log 'Retention cleanup complete'
