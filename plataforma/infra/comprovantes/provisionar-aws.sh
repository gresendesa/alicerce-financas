#!/usr/bin/env bash
# alicerce: bucket privado dos comprovantes e usuário IAM próprio, separados do backup.
# Idempotente; a access key só é criada se .env não existir. Não imprime segredos.
# Exige um perfil administrativo (padrão: bootstrap). Uso: ./provisionar-aws.sh
set -Eeuo pipefail
cd "$(dirname "$0")"
export AWS_PROFILE="${AWS_PROFILE:-bootstrap}" AWS_REGION=us-east-1 AWS_PAGER=""
AMBIENTE=production-alicerce
USUARIO="${AMBIENTE}-comprovantes"
CONTA="$(aws sts get-caller-identity --query Account --output text)"
BUCKET="comprovantes-${CONTA}-${AWS_REGION}-${AMBIENTE}"

echo "Identidade: $(aws sts get-caller-identity --query Arn --output text)"

echo "== Bucket $BUCKET"
if ! aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  aws s3api create-bucket --bucket "$BUCKET" --object-ownership BucketOwnerEnforced
fi
aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption --bucket "$BUCKET" --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"},"BucketKeyEnabled":true}]}'
aws s3api put-bucket-versioning --bucket "$BUCKET" --versioning-configuration Status=Enabled

echo "== Só TLS"
aws s3api put-bucket-policy --bucket "$BUCKET" --policy "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [{
    \"Sid\": \"SoTLS\", \"Effect\": \"Deny\", \"Principal\": \"*\", \"Action\": \"s3:*\",
    \"Resource\": [\"arn:aws:s3:::${BUCKET}\", \"arn:aws:s3:::${BUCKET}/*\"],
    \"Condition\": {\"Bool\": {\"aws:SecureTransport\": \"false\"}}
  }]
}"

echo "== Lifecycle: versões não correntes expiram em 90 dias; correntes ficam sem prazo"
aws s3api put-bucket-lifecycle-configuration --bucket "$BUCKET" --lifecycle-configuration '{
  "Rules": [{
    "ID": "expirar-versoes-nao-correntes",
    "Status": "Enabled",
    "Filter": {},
    "NoncurrentVersionExpiration": {"NoncurrentDays": 90},
    "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 7}
  }]
}' >/dev/null

echo "== Usuário IAM $USUARIO: só grava e lê objetos; não lista nem apaga"
aws iam get-user --user-name "$USUARIO" >/dev/null 2>&1 || aws iam create-user --user-name "$USUARIO" >/dev/null
aws iam put-user-policy --user-name "$USUARIO" --policy-name comprovantes --policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [{
    \"Effect\": \"Allow\",
    \"Action\": [\"s3:PutObject\", \"s3:GetObject\"],
    \"Resource\": \"arn:aws:s3:::${BUCKET}/comprovantes/*\"
  }]
}"

echo "== Credenciais da aplicação"
if [[ -e .env ]]; then
  echo ".env já existe; nenhuma chave nova criada."
else
  ( umask 077
    aws iam create-access-key --user-name "$USUARIO" \
      --query 'AccessKey.[AccessKeyId,SecretAccessKey]' --output text \
      | awk -v b="$BUCKET" '{print "COMPROVANTES_BUCKET=" b; print "AWS_REGION=us-east-1"; print "AWS_ACCESS_KEY_ID=" $1; print "AWS_SECRET_ACCESS_KEY=" $2}' > .env )
  echo ".env criado (permissão 600)."
fi

echo "== Conferência"
aws s3api get-public-access-block --bucket "$BUCKET" --query 'PublicAccessBlockConfiguration' --output text
aws s3api get-bucket-versioning --bucket "$BUCKET" --query Status --output text
aws s3api get-bucket-encryption --bucket "$BUCKET" --query 'ServerSideEncryptionConfiguration.Rules[0].ApplyServerSideEncryptionByDefault.SSEAlgorithm' --output text
echo "Pronto. Para reconstruir e subir: cd .. && sudo docker compose up -d --build financeiro caddy"
