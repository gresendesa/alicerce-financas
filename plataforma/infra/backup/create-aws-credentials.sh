#!/usr/bin/env bash
set -Eeuo pipefail

AWS_PROFILE="${AWS_PROFILE:-bootstrap}"
IAM_USER="${IAM_USER:?IAM_USER is required}"
OUTPUT_FILE="${OUTPUT_FILE:-secrets/aws-credentials}"

[[ ! -e "$OUTPUT_FILE" ]] || {
  echo "Refusing to overwrite existing path: $OUTPUT_FILE" >&2
  exit 1
}

mkdir -p "$(dirname "$OUTPUT_FILE")"
chmod 700 "$(dirname "$OUTPUT_FILE")"
umask 077

tmp_file="$(mktemp "$(dirname "$OUTPUT_FILE")/.aws-credentials.XXXXXX")"
trap 'rm -f "$tmp_file"' EXIT

read -r key_id secret_key < <(
  aws iam create-access-key --user-name "$IAM_USER" --profile "$AWS_PROFILE" \
    --query 'AccessKey.[AccessKeyId,SecretAccessKey]' --output text
)

cat > "$tmp_file" <<EOF
[default]
aws_access_key_id = ${key_id}
aws_secret_access_key = ${secret_key}
EOF
chmod 600 "$tmp_file"
mv "$tmp_file" "$OUTPUT_FILE"
trap - EXIT

echo "Credential created for ${IAM_USER}: ...${key_id: -4}"
echo "Written to ${OUTPUT_FILE}; SecretAccessKey was not displayed."
