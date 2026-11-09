#!/usr/bin/env bash
# Aplica realm-alicerce.json no Keycloak com o keycloak-config-cli, de forma idempotente.
# Credenciais (conta de serviço restrita ao realm) vêm de um arquivo fora do repositório.
# Uso: ./aplicar-realm.sh [arquivo-de-segredos]   (padrão: ~/.config/alicerce/secrets.env)
#   Variáveis lidas: KEYCLOAK_URL, KEYCLOAK_REALM, KEYCLOAK_CLIENT_ID, KEYCLOAK_CLIENT_SECRET
#   DOCKER_NETWORK (opcional): rede do contêiner, para Keycloaks locais de teste.
set -euo pipefail
cd "$(dirname "$0")"
SEGREDOS="${1:-$HOME/.config/alicerce/secrets.env}"
set -a; . "$SEGREDOS"; set +a
DOCKER="${DOCKER:-sudo docker}"

exec $DOCKER run --rm ${DOCKER_NETWORK:+--network "$DOCKER_NETWORK"} \
  -v "$PWD/realm-alicerce.json":/config/realm-alicerce.json:ro \
  -e KEYCLOAK_URL="$KEYCLOAK_URL" \
  -e KEYCLOAK_GRANTTYPE=client_credentials \
  -e KEYCLOAK_LOGINREALM="$KEYCLOAK_REALM" \
  -e KEYCLOAK_CLIENTID="$KEYCLOAK_CLIENT_ID" \
  -e KEYCLOAK_CLIENTSECRET="$KEYCLOAK_CLIENT_SECRET" \
  -e KEYCLOAK_AVAILABILITYCHECK_ENABLED=true \
  -e IMPORT_FILES_LOCATIONS=/config/realm-alicerce.json \
  -e IMPORT_CACHE_ENABLED=false \
  -e LOGGING_LEVEL_KEYCLOAKCONFIGCLI=info \
  adorsys/keycloak-config-cli:6.5.1-26.5.5
