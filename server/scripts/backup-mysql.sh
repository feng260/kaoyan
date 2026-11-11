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
# Record the basename so the .sql.gz/.sha256 pair stays verifiable after being copied elsewhere.
(cd "$BACKUP_DIR" && sha256sum -- "$(basename "$file")" > "$(basename "$file").sha256")
log "Checksum written: $file.sha256"

if [[ "${BACKUP_SKIP_UPLOAD:-0}" != "1" ]]; then
  : "${COS_BUCKET:?COS_BUCKET is required for offsite upload}"
  command -v rclone >/dev/null || { log "rclone is required"; exit 2; }
  day_path="$(date -u '+%Y/%m/%d')"
  remote="cos:${COS_BUCKET}/yanzhong-backup/mysql/${day_path}"
  rclone copyto -- "$file" "${remote}/$(basename "$file")"
  rclone copyto -- "$file.sha256" "${remote}/$(basename "$file").sha256"
  log "Uploaded to ${remote}"
fi

if [[ -n "${HEALTHCHECK_BACKUP_URL:-}" ]]; then
  command -v curl >/dev/null || { log "curl is required for healthcheck ping"; exit 2; }
  curl -fsS -m 10 --retry 2 "$HEALTHCHECK_BACKUP_URL" >/dev/null || log "WARN backup healthcheck ping failed"
fi

# Expired dumps and their checksums share the same mtime, so both are removed together; newer pairs are kept.
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'yanzhong-*.sql.gz' -o -name 'yanzhong-*.sql.gz.sha256' \) -mtime "+$((10#$RETENTION_DAYS - 1))" -print -delete | while IFS= read -r old; do log "Removed expired backup: $old"; done
log 'Retention cleanup complete'
