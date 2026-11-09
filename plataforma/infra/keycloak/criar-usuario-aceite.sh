#!/usr/bin/env bash
# Cria (ou reativa) o usuário de aceite no realm, com o papel fin-owner, de forma idempotente. Os
# dados dele ficam separados dos do dono real: cada usuário do realm é um dono diferente no financeiro.
# A senha é gerada aqui e guardada só em ~/.config/alicerce/usuario-aceite.env (permissão 600).
# Uso: ./criar-usuario-aceite.sh [arquivo-de-segredos]   (padrão: ~/.config/alicerce/secrets.env)
#   Variáveis lidas: KEYCLOAK_URL, KEYCLOAK_REALM, KEYCLOAK_CLIENT_ID, KEYCLOAK_CLIENT_SECRET
set -euo pipefail
SEGREDOS="${1:-$HOME/.config/alicerce/secrets.env}"
set -a; . "$SEGREDOS"; set +a
USUARIO=aceite
SAIDA="$HOME/.config/alicerce/usuario-aceite.env"
ADMIN="$KEYCLOAK_URL/admin/realms/$KEYCLOAK_REALM"

valor() { python3 -c "import json,sys; d=json.load(sys.stdin); print(($1))"; }

TOKEN=$(curl -fsS "$KEYCLOAK_URL/realms/$KEYCLOAK_REALM/protocol/openid-connect/token" \
  -d grant_type=client_credentials -d client_id="$KEYCLOAK_CLIENT_ID" \
  --data-urlencode client_secret="$KEYCLOAK_CLIENT_SECRET" | valor "d['access_token']")
api() { curl -fsS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"; }

ID=$(api "$ADMIN/users?username=$USUARIO&exact=true" | valor "d[0]['id'] if d else ''")
if [ -z "$ID" ]; then
  api -X POST "$ADMIN/users" -d "{\"username\":\"$USUARIO\",\"enabled\":true,\"emailVerified\":true,\
\"email\":\"aceite@alicerce.tools\",\"firstName\":\"Aceite\",\"lastName\":\"Alicerce\"}" >/dev/null
  ID=$(api "$ADMIN/users?username=$USUARIO&exact=true" | valor "d[0]['id']")
else
  api -X PUT "$ADMIN/users/$ID" -d '{"enabled":true}' >/dev/null
fi

SENHA=$(openssl rand -hex 24)
api -X PUT "$ADMIN/users/$ID/reset-password" -d "{\"type\":\"password\",\"value\":\"$SENHA\",\"temporary\":false}" >/dev/null
PAPEL=$(api "$ADMIN/roles/fin-owner")
api -X POST "$ADMIN/users/$ID/role-mappings/realm" -d "[$PAPEL]" >/dev/null

umask 077
printf 'ACEITE_USUARIO=%s\nACEITE_SENHA=%s\n' "$USUARIO" "$SENHA" > "$SAIDA"
echo "Usuário $USUARIO pronto com fin-owner. Senha em $SAIDA."
