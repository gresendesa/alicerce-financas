#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SECRETS_DIR="${SECRETS_DIR:-$SCRIPT_DIR/secrets}"
if [[ -f "${ENV_FILE:-$SCRIPT_DIR/.env}" ]]; then
  set -a
  # shellcheck disable=SC1090
  . "${ENV_FILE:-$SCRIPT_DIR/.env}"
  set +a
fi
ENVIRONMENT="${BACKUP_ENVIRONMENT:-environment}"
DATABASE="${DB_NAME:-postgres}"
EXPORT_FORMAT="${EXPORT_FORMAT:-bundle}"
case "$EXPORT_FORMAT" in
  bundle) default_extension=txt ;;
  proton-pass-csv) default_extension=csv ;;
  *)
    echo "Unsupported EXPORT_FORMAT: $EXPORT_FORMAT (use bundle or proton-pass-csv)" >&2
    exit 1
    ;;
esac
OUTPUT_FILE="${OUTPUT_FILE:-/tmp/postgres-restic-${ENVIRONMENT}-${DATABASE}-credentials.${default_extension}}"

[[ ! -e "$OUTPUT_FILE" ]] || {
  echo "Refusing to overwrite existing path: $OUTPUT_FILE" >&2
  exit 1
}

for secret_file in restic-password postgres-password aws-credentials; do
  [[ -f "$SECRETS_DIR/$secret_file" ]] || {
    echo "Missing secret file: $SECRETS_DIR/$secret_file" >&2
    exit 1
  }
done

output_dir="$(dirname -- "$OUTPUT_FILE")"
mkdir -p "$output_dir"
umask 077

tmp_file="$(mktemp "$OUTPUT_FILE.tmp.XXXXXX")"
trap 'rm -f "$tmp_file"' EXIT

restic_password="$(sed -n '1p' "$SECRETS_DIR/restic-password")"
postgres_password="$(sed -n '1p' "$SECRETS_DIR/postgres-password")"
aws_access_key_id="$(awk -F'=' '/^[[:space:]]*aws_access_key_id[[:space:]]*=/ {gsub(/[[:space:]]/, "", $2); print $2; exit}' "$SECRETS_DIR/aws-credentials")"
aws_secret_access_key="$(awk -F'=' '/^[[:space:]]*aws_secret_access_key[[:space:]]*=/ {gsub(/[[:space:]]/, "", $2); print $2; exit}' "$SECRETS_DIR/aws-credentials")"

[[ -n "$aws_access_key_id" && -n "$aws_secret_access_key" ]] || {
  echo "Invalid AWS credentials file: $SECRETS_DIR/aws-credentials" >&2
  exit 1
}

csv_field() {
  local value="$1"
  value="${value//\"/\"\"}"
  printf '"%s"' "$value"
}

if [[ "$EXPORT_FORMAT" == "proton-pass-csv" ]]; then
  repository="${RESTIC_REPOSITORY:-}"
  region="${AWS_DEFAULT_REGION:-}"
  db_host="${DB_HOST:-}"
  db_port="${DB_PORT:-5432}"
  db_user="${DB_USER:-backup_readonly}"

  {
    printf '%s\n' 'name,url,email,password,note,totp'

    csv_field "Restic — ${ENVIRONMENT}/${DATABASE}"; printf ','
    csv_field "$repository"; printf ','
    csv_field ''; printf ','
    csv_field "$restic_password"; printf ','
    csv_field "Repositório de backup PostgreSQL. Região AWS: ${region}"; printf ',\n'

    csv_field "PostgreSQL — ${ENVIRONMENT}/${DATABASE}"; printf ','
    csv_field "postgresql://${db_host}:${db_port}/${DATABASE}"; printf ','
    csv_field "$db_user"; printf ','
    csv_field "$postgres_password"; printf ','
    csv_field 'Role dedicada somente leitura; default_transaction_read_only=on.'; printf ',\n'

    csv_field "AWS — ${ENVIRONMENT}/${DATABASE}"; printf ','
    csv_field 'https://console.aws.amazon.com/iam/'; printf ','
    csv_field "$aws_access_key_id"; printf ','
    csv_field "$aws_secret_access_key"; printf ','
    csv_field "Credencial IAM dedicada ao backup. Região AWS: ${region}"; printf ',\n'
  } > "$tmp_file"
else
  {
    printf '%s\n' \
      "# PostgreSQL + restic backup — ${ENVIRONMENT}/${DATABASE}" \
      '# Armazenar em cofre seguro e remover este arquivo depois.' \
      '' '[restic-password]'
    printf '%s\n' "$restic_password"
    printf '%s\n' '' '[postgres-backup-readonly-password]'
    printf '%s\n' "$postgres_password"
    printf '%s\n' '' '[aws-credentials]'
    sed -n '1,3p' "$SECRETS_DIR/aws-credentials"
  } > "$tmp_file"
fi

chmod 600 "$tmp_file"
mv "$tmp_file" "$OUTPUT_FILE"
trap - EXIT

echo "Temporary credentials bundle written to: $OUTPUT_FILE"
