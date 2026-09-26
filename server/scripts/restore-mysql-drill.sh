#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# This script has no production restore mode, even when ALLOW_PRODUCTION_RESTORE=YES.
if [[ "${ALLOW_PRODUCTION_RESTORE:-}" == YES ]]; then
  printf 'Production restore is not supported by this drill script.\n' >&2
  exit 2
fi
if (( $# != 1 )); then
  printf 'Usage: %s /absolute/path/to/yanzhong-....sql.gz\n' "$0" >&2
  exit 2
fi
dump="$1"
[[ -f "$dump" ]] || { printf 'Dump not found: %s\n' "$dump" >&2; exit 2; }
for tool in docker gzip; do command -v "$tool" >/dev/null || { printf '%s is required\n' "$tool" >&2; exit 2; }; done
gzip -t -- "$dump"
container="yanzhong-restore-drill-$(date -u '+%Y%m%d%H%M%S')-$$"
# The container has no network, published ports, bind mounts, or production volumes.
# docker rm -v removes only this container and its anonymous data volume.
cleanup() { docker rm -f -v "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
printf '[%s] Starting isolated restore drill: %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$container"
docker run -d --name "$container" --network none -e MYSQL_ALLOW_EMPTY_PASSWORD=yes mysql:8 >/dev/null
ready=false
for (( i=0; i<60; i++ )); do
  if docker exec "$container" mysql -uroot -N -e 'SELECT 1' >/dev/null 2>&1; then ready=true; break; fi
  if [[ "$(docker inspect -f '{{.State.Running}}' "$container")" != true ]]; then break; fi
  sleep 2
done
[[ "$ready" == true ]] || { printf 'Temporary MySQL did not become ready\n' >&2; exit 1; }
gzip -dc -- "$dump" | docker exec -i "$container" mysql -uroot
docker exec "$container" mysql -uroot -N -e 'SELECT 1 AS health; SELECT COUNT(*) AS user_rows FROM yanzhong.users;'
printf '[%s] Restore drill passed; temporary resources will be removed.\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
