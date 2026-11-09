#!/usr/bin/env bash
# alicerce: provisiona na AWS o backup do ambiente production-alicerce com os scripts do pacote.
# Idempotente no bucket e no IAM; a access key só é criada se secrets/aws-credentials não existir.
# Exige um perfil administrativo (padrão: bootstrap). Uso: ./provisionar-aws.sh
set -Eeuo pipefail
cd "$(dirname "$0")"
export AWS_PROFILE="${AWS_PROFILE:-bootstrap}" AWS_REGION=us-east-1
export ENVIRONMENT=production-alicerce IAM_USER=production-alicerce-backup
CONTA="$(aws sts get-caller-identity --query Account --output text)"
BUCKET="postgres-backup-${CONTA}-${AWS_REGION}-${ENVIRONMENT}"

echo "Identidade: $(aws sts get-caller-identity --query Arn --output text)"

echo "== Bucket, usuário e policy IAM (bootstrap-s3.sh do pacote)"
./bootstrap-s3.sh

echo "== Lifecycle: versões não correntes expiram em 90 dias"
# O usuário do backup não tem s3:DeleteObjectVersion: o que o restic apaga continua recuperável por 90 dias.
aws s3api put-bucket-lifecycle-configuration --bucket "$BUCKET" --lifecycle-configuration '{
  "Rules": [{
    "ID": "expirar-versoes-nao-correntes",
    "Status": "Enabled",
    "Filter": {},
    "NoncurrentVersionExpiration": {"NoncurrentDays": 90},
    "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 7}
  }]
}'
aws s3api get-bucket-lifecycle-configuration --bucket "$BUCKET" --query 'Rules[].ID' --output text

echo "== Access key do usuário do backup"
if [[ -e secrets/aws-credentials ]]; then
  echo "secrets/aws-credentials já existe; nenhuma chave nova criada."
else
  OUTPUT_FILE=secrets/aws-credentials ./create-aws-credentials.sh
fi
