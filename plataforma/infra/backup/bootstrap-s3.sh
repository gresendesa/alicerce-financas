#!/usr/bin/env bash
set -Eeuo pipefail

AWS_PROFILE="${AWS_PROFILE:-bootstrap}"
AWS_REGION="${AWS_REGION:-us-east-1}"
ENVIRONMENT="${ENVIRONMENT:?ENVIRONMENT is required}"
ACCOUNT_ID="$(aws sts get-caller-identity --profile "$AWS_PROFILE" --query Account --output text)"

IAM_USER="${IAM_USER:-${ENVIRONMENT}-postgres-backup}"
PREFIX="${PREFIX:-${ENVIRONMENT}/postgresql/restic}"
BUCKET="${BUCKET:-postgres-backup-${ACCOUNT_ID}-${AWS_REGION}-${ENVIRONMENT}}"
POLICY_NAME="${POLICY_NAME:-${ENVIRONMENT}-postgres-backup-s3}"
POLICY_ARN="arn:aws:iam::${ACCOUNT_ID}:policy/${POLICY_NAME}"

echo "Account: ${ACCOUNT_ID}"
echo "Region:  ${AWS_REGION}"
echo "Bucket:  ${BUCKET}"
echo "Prefix:  ${PREFIX}"
echo "IAM:     ${IAM_USER}"

if ! aws s3api head-bucket --bucket "$BUCKET" --profile "$AWS_PROFILE" >/dev/null 2>&1; then
  if [[ "$AWS_REGION" == us-east-1 ]]; then
    aws s3api create-bucket --bucket "$BUCKET" --region "$AWS_REGION" \
      --object-ownership BucketOwnerEnforced --profile "$AWS_PROFILE" >/dev/null
  else
    aws s3api create-bucket --bucket "$BUCKET" --region "$AWS_REGION" \
      --object-ownership BucketOwnerEnforced \
      --create-bucket-configuration "LocationConstraint=${AWS_REGION}" \
      --profile "$AWS_PROFILE" >/dev/null
  fi
fi

aws s3api put-public-access-block --bucket "$BUCKET" --profile "$AWS_PROFILE" \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-versioning --bucket "$BUCKET" --profile "$AWS_PROFILE" \
  --versioning-configuration Status=Enabled
aws s3api put-bucket-encryption --bucket "$BUCKET" --profile "$AWS_PROFILE" \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'

policy_file="$(mktemp)"
trap 'rm -f "$policy_file"' EXIT
cat > "$policy_file" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {"Sid":"BucketMetadata","Effect":"Allow","Action":["s3:GetBucketLocation"],"Resource":"arn:aws:s3:::${BUCKET}"},
    {"Sid":"ListPrefix","Effect":"Allow","Action":["s3:ListBucket"],"Resource":"arn:aws:s3:::${BUCKET}","Condition":{"StringLike":{"s3:prefix":["${PREFIX}","${PREFIX}/*"]}}},
    {"Sid":"ResticObjects","Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],"Resource":"arn:aws:s3:::${BUCKET}/${PREFIX}/*"}
  ]
}
EOF

aws accessanalyzer validate-policy --policy-document "file://${policy_file}" \
  --policy-type IDENTITY_POLICY --profile "$AWS_PROFILE" >/dev/null

if ! aws iam get-user --user-name "$IAM_USER" --profile "$AWS_PROFILE" >/dev/null 2>&1; then
  aws iam create-user --user-name "$IAM_USER" --profile "$AWS_PROFILE" >/dev/null
fi

if ! aws iam get-policy --policy-arn "$POLICY_ARN" --profile "$AWS_PROFILE" >/dev/null 2>&1; then
  aws iam create-policy --policy-name "$POLICY_NAME" \
    --description "Restic S3 access for ${ENVIRONMENT}" \
    --policy-document "file://${policy_file}" --profile "$AWS_PROFILE" >/dev/null
fi

aws iam attach-user-policy --user-name "$IAM_USER" --policy-arn "$POLICY_ARN" --profile "$AWS_PROFILE"
echo "Ready: s3://${BUCKET}/${PREFIX}"
