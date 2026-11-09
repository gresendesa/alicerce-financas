#!/usr/bin/env bash
set -Eeuo pipefail

: "${RESTIC_REPOSITORY:?RESTIC_REPOSITORY is required}"
: "${RESTIC_PASSWORD_FILE:?RESTIC_PASSWORD_FILE is required}"
: "${AWS_SHARED_CREDENTIALS_FILE:?AWS_SHARED_CREDENTIALS_FILE is required}"

[[ -r "$RESTIC_PASSWORD_FILE" ]] || {
  echo "RESTIC_PASSWORD_FILE is not readable" >&2
  exit 1
}
[[ -r "$AWS_SHARED_CREDENTIALS_FILE" ]] || {
  echo "AWS_SHARED_CREDENTIALS_FILE is not readable" >&2
  exit 1
}

export RESTIC_CACHE_DIR="${RESTIC_CACHE_DIR:-/tmp/restic-cache}"
mkdir -p "$RESTIC_CACHE_DIR"

usage() {
  cat <<'USAGE'
Usage: list-versions.sh [restic snapshots options]

Lists logical backup versions (restic snapshots). Examples:
  list-versions.sh
  list-versions.sh --latest 5
  list-versions.sh --tag environment:production --json

This command is read-only. It does not list S3 object versions.
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

exec restic snapshots --no-lock "$@"
