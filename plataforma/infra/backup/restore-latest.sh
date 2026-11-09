#!/usr/bin/env bash
set -Eeuo pipefail

: "${RESTIC_REPOSITORY:?RESTIC_REPOSITORY is required}"
: "${RESTIC_PASSWORD_FILE:?RESTIC_PASSWORD_FILE is required}"
: "${AWS_SHARED_CREDENTIALS_FILE:?AWS_SHARED_CREDENTIALS_FILE is required}"

output_file="${1:?usage: restore-latest.sh OUTPUT_FILE [SNAPSHOT]}"
snapshot="${2:-latest}"

export RESTIC_CACHE_DIR="${RESTIC_CACHE_DIR:-/tmp/restic-cache}"
mkdir -p "$RESTIC_CACHE_DIR"

# alicerce: só a linha do arquivo. No restic 0.18 a primeira linha (o snapshot) também cita o dump em
# "paths":[...], e o filtro original parava nela com um caminho vazio.
path="$(restic ls "$snapshot" --json \
  | awk -F'\"path\":\"' '/"type":"file"/ && /postgresql\/.*\.dump/ { split($2, a, "\""); print a[1]; exit }')"

[[ -n "$path" ]] || {
  echo "No PostgreSQL dump found in snapshot ${snapshot}" >&2
  exit 1
}

umask 077
restic dump "$snapshot" "$path" > "$output_file"
echo "Restored ${path} to ${output_file}"
