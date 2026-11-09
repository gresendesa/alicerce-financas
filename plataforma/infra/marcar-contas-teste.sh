#!/usr/bin/env bash
# Define em infra/.env quais logins do Keycloak são contas de teste (dados fictícios) e recria o
# contêiner financeiro para valer. obter_contexto passa a responder conta_de_teste: true para eles.
# Idempotente: troca a linha CONTAS_TESTE se existir, ou a acrescenta.
# Uso: ./marcar-contas-teste.sh [logins separados por vírgula]   (padrão: aceite)
set -euo pipefail
cd "$(dirname "$0")"
CONTAS="${1:-aceite}"
DOCKER="${DOCKER:-sudo docker}"

if grep -q '^CONTAS_TESTE=' .env; then
  sed -i "s/^CONTAS_TESTE=.*/CONTAS_TESTE=${CONTAS}/" .env
else
  printf 'CONTAS_TESTE=%s\n' "$CONTAS" >> .env
fi
echo "infra/.env: $(grep '^CONTAS_TESTE=' .env)"

$DOCKER compose up -d financeiro
echo "No contêiner: CONTAS_TESTE=$($DOCKER compose exec -T financeiro printenv CONTAS_TESTE)"
