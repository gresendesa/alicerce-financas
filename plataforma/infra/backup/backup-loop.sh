#!/usr/bin/env bash
set -Eeuo pipefail

: "${BACKUP_INTERVAL_SECONDS:=21600}"

if (( $# > 0 )); then
  exec "$@"
fi

if [[ "${RESTIC_SKIP_REPOSITORY_CHECK:-false}" != "true" ]]; then
  echo "[$(date -u +%FT%TZ)] checking restic repository"
  restic snapshots --no-lock >/dev/null
fi

while true; do
  if /usr/local/bin/backup-once.sh; then
    sleep "$BACKUP_INTERVAL_SECONDS"
  else
    status=$?
    echo "[$(date -u +%FT%TZ)] backup failed with exit code ${status}; retrying in ${BACKUP_RETRY_SECONDS:-900}s" >&2
    # alicerce: avisa a falha ao alerta externo imediatamente, sem esperar o prazo.
    if [[ -n "${HEALTHCHECK_URL_FILE:-}" && -s "$HEALTHCHECK_URL_FILE" ]]; then
      curl --fail --silent --max-time 10 --output /dev/null "$(cat "$HEALTHCHECK_URL_FILE")/fail" || true
    fi
    sleep "${BACKUP_RETRY_SECONDS:-900}"
  fi
done
