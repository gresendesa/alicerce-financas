#!/usr/bin/env bash
set -Eeuo pipefail

: "${RESTIC_REPOSITORY:?RESTIC_REPOSITORY is required}"
: "${RESTIC_PASSWORD_FILE:?RESTIC_PASSWORD_FILE is required}"
: "${AWS_SHARED_CREDENTIALS_FILE:?AWS_SHARED_CREDENTIALS_FILE is required}"
: "${DB_HOST:=database}"
: "${DB_PORT:=5432}"
: "${DB_NAME:=postgres}"
: "${DB_USER:?DB_USER is required}"
: "${DB_PASSWORD_FILE:?DB_PASSWORD_FILE is required}"

export PGPASSWORD="$(cat "$DB_PASSWORD_FILE")"
export RESTIC_CACHE_DIR="${RESTIC_CACHE_DIR:-/tmp/restic-cache}"
mkdir -p "$RESTIC_CACHE_DIR"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
filename="postgresql/${DB_NAME}-${timestamp}.dump"

echo "[$(date -u +%FT%TZ)] starting PostgreSQL backup: database=${DB_NAME} host=${DB_HOST}"

restic backup \
  --stdin-from-command \
  --stdin-filename="$filename" \
  --tag=postgresql \
  --tag="environment:${BACKUP_ENVIRONMENT:-unknown}" \
  --tag="database:${DB_NAME}" \
  -- \
  pg_dump \
    --format=custom \
    --compress=none \
    --no-owner \
    --no-privileges \
    --host="$DB_HOST" \
    --port="$DB_PORT" \
    --username="$DB_USER" \
    --dbname="$DB_NAME"

# alicerce: cada política de retenção só entra se estiver definida no .env, para permitir
# --keep-hourly e --keep-yearly (unlimited = sem prazo) sem herdar os padrões do pacote.
retencao=()
for politica in last hourly daily weekly monthly yearly; do
  variavel="RESTIC_KEEP_${politica^^}"
  [[ -n "${!variavel:-}" ]] && retencao+=("--keep-${politica}=${!variavel}")
done
(( ${#retencao[@]} > 0 )) || { echo "no RESTIC_KEEP_* retention policy defined" >&2; exit 1; }
restic forget "${retencao[@]}" --prune

date -u +%s > /tmp/restic-last-success
echo "[$(date -u +%FT%TZ)] backup completed successfully"

# alicerce: alerta externo (dead man's switch). A URL fica num arquivo de segredo.
if [[ -n "${HEALTHCHECK_URL_FILE:-}" && -s "$HEALTHCHECK_URL_FILE" ]]; then
  curl --fail --silent --show-error --max-time 10 --retry 3 --output /dev/null "$(cat "$HEALTHCHECK_URL_FILE")" \
    || echo "[$(date -u +%FT%TZ)] healthcheck ping failed" >&2
fi
