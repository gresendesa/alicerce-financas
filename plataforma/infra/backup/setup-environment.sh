#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
CONFIG_FILE="$SCRIPT_DIR/.env"
SECRETS_DIR="$SCRIPT_DIR/secrets"

if [[ -e "$CONFIG_FILE" ]]; then
  echo "Já existe: $CONFIG_FILE" >&2
  echo "Para preservar a configuração atual, este wizard não a sobrescreve." >&2
  exit 1
fi

command -v openssl >/dev/null 2>&1 || {
  echo "openssl é necessário para gerar a senha do repositório restic." >&2
  exit 1
}

ask() {
  local label="$1"
  local default_value="${2:-}"
  local answer
  if [[ -n "$default_value" ]]; then
    read -r -p "$label [$default_value]: " answer
    printf '%s' "${answer:-$default_value}"
  else
    read -r -p "$label: " answer
    [[ -n "$answer" ]] || { echo "Valor obrigatório." >&2; exit 1; }
    printf '%s' "$answer"
  fi
}

ask_secret() {
  local label="$1"
  local first second
  read -r -s -p "$label: " first
  printf '\n'
  read -r -s -p "Confirme $label: " second
  printf '\n'
  [[ "$first" == "$second" && -n "$first" ]] || {
    echo "Os valores não conferem." >&2
    exit 1
  }
  printf '%s' "$first"
}

cat <<'INTRO'

Configuração local do backup PostgreSQL + restic + S3
=====================================================

Este assistente vai:
  1. criar ops/backup/.env;
  2. gerar a senha do repositório restic;
  3. solicitar a senha da role PostgreSQL read-only;
  4. proteger os arquivos com permissões 0700/0600.

Ele NÃO vai criar bucket, IAM, role PostgreSQL ou repositório restic.
INTRO

environment="$(ask 'Nome do ambiente, por exemplo staging' '')"
region="$(ask 'Região AWS' 'us-east-1')"
bucket="$(ask 'Nome exato do bucket S3' '')"
prefix="$(ask 'Prefixo do repositório' "${environment}/postgresql/restic")"
network="$(ask 'Nome da rede Docker do banco' 'app_edital')"
db_host="$(ask 'Host Docker do PostgreSQL' 'database')"
db_port="$(ask 'Porta PostgreSQL' '5432')"
db_name="$(ask 'Nome do banco' 'postgres')"
db_user="$(ask 'Role PostgreSQL read-only' 'backup_readonly')"

mkdir -p "$SECRETS_DIR"
chmod 700 "$SECRETS_DIR"
umask 077

restic_password="$(openssl rand -hex 32)"
printf '%s\n' "$restic_password" > "$SECRETS_DIR/restic-password"
unset restic_password

postgres_password="$(ask_secret 'Senha da role PostgreSQL')"
printf '%s\n' "$postgres_password" > "$SECRETS_DIR/postgres-password"
unset postgres_password

cat > "$CONFIG_FILE" <<EOF
# Gerado por setup-environment.sh. Não versione este arquivo.
AWS_DEFAULT_REGION=${region}
AWS_SHARED_CREDENTIALS_FILE=/run/secrets/aws-credentials
RESTIC_REPOSITORY=s3:s3.amazonaws.com/${bucket}/${prefix}
RESTIC_PASSWORD_FILE=/run/secrets/restic-password
RESTIC_CACHE_DIR=/tmp/restic-cache
DB_HOST=${db_host}
DB_PORT=${db_port}
DB_NAME=${db_name}
DB_USER=${db_user}
DB_PASSWORD_FILE=/run/secrets/postgres-password
BACKUP_ENVIRONMENT=${environment}
BACKUP_NETWORK=${network}
BACKUP_INTERVAL_SECONDS=21600
BACKUP_RETRY_SECONDS=900
RESTIC_KEEP_LAST=14
RESTIC_KEEP_DAILY=7
RESTIC_KEEP_WEEKLY=4
RESTIC_KEEP_MONTHLY=12
EOF

chmod 600 "$CONFIG_FILE" "$SECRETS_DIR"/*

cat <<SUMMARY

Configuração local concluída.
============================
Ambiente:       ${environment}
Região AWS:     ${region}
Bucket/prefixo: s3://${bucket}/${prefix}
Rede Docker:    ${network}
Banco:          ${db_name}@${db_host}:${db_port}
Role:           ${db_user}

Arquivos criados:
  ${CONFIG_FILE}
  ${SECRETS_DIR}/restic-password
  ${SECRETS_DIR}/postgres-password

Ainda falta:
  1. colocar a credencial IAM em ${SECRETS_DIR}/aws-credentials;
  2. criar/verificar a role PostgreSQL usando postgres-readonly-role.sql;
  3. executar restic init;
  4. executar o primeiro backup manual.

Próximo comando recomendado:
  docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \\
    --profile backup config --quiet
SUMMARY
