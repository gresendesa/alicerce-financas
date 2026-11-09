#!/usr/bin/env bash
# alicerce: restauração verificada do backup. Restaura o snapshot mais recente num Postgres
# descartável, numa rede isolada, e confere contra o banco de origem. Nunca toca no banco de produção:
# a origem só é lida pela role backup_readonly. Apaga contêineres e rede no fim, com sucesso ou não.
# Uso: ./restaurar-e-verificar.sh [SNAPSHOT]   (padrão: latest). Sai com erro se alguma verificação falhar.
# Agendado mensalmente pelo crontab do host; uma falha avisa o Healthchecks.io (/fail).
set -Eeuo pipefail
cd "$(dirname "$0")"
DOCKER="${DOCKER:-sudo -n docker}"
SNAPSHOT="${1:-latest}"
IDADE_MAXIMA_HORAS="${IDADE_MAXIMA_HORAS:-26}"
SUFIXO="$(date -u +%Y%m%d%H%M%S)-$$"
REDE="alicerce-verificacao-$SUFIXO"
BANCO_TEMPORARIO="alicerce-verificacao-pg-$SUFIXO"
REDE_ORIGEM=alicerce_default

limpar() {
  $DOCKER rm -f "$BANCO_TEMPORARIO" "alicerce-verificacao-$SUFIXO" >/dev/null 2>&1 || true
  $DOCKER network rm "$REDE" >/dev/null 2>&1 || true
}
avisar_falha() {
  echo "[$(date -u +%FT%TZ)] VERIFICAÇÃO FALHOU" >&2
  if [[ -s secrets/healthcheck-url ]]; then
    curl --fail --silent --max-time 10 --output /dev/null "$(cat secrets/healthcheck-url)/fail" || true
  fi
}
trap limpar EXIT
trap avisar_falha ERR

echo "[$(date -u +%FT%TZ)] restaurando snapshot $SNAPSHOT num Postgres descartável"
$DOCKER network create --internal "$REDE" >/dev/null
$DOCKER run -d --name "$BANCO_TEMPORARIO" --network "$REDE" --tmpfs /var/lib/postgresql/data:size=1g \
  -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=verificacao postgres:16.15-alpine >/dev/null
for _ in $(seq 60); do
  $DOCKER exec "$BANCO_TEMPORARIO" pg_isready -q -U postgres -d verificacao 2>/dev/null && break; sleep 1
done

# O verificador usa a imagem do backup (restic + cliente Postgres 16) e as mesmas credenciais, só leitura.
$DOCKER create --name "alicerce-verificacao-$SUFIXO" --network "$REDE_ORIGEM" -u 1000:1000 --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=512m --env-file .env \
  -v "$PWD/secrets/restic-password:/run/secrets/restic-password:ro" \
  -v "$PWD/secrets/postgres-password:/run/secrets/postgres-password:ro" \
  -v "$PWD/secrets/aws-credentials:/run/secrets/aws-credentials:ro" \
  -e SNAPSHOT="$SNAPSHOT" -e BANCO_TEMPORARIO="$BANCO_TEMPORARIO" -e IDADE_MAXIMA_HORAS="$IDADE_MAXIMA_HORAS" \
  -v "$PWD/verificar-restauracao.sh:/usr/local/bin/verificar-restauracao.sh:ro" \
  --entrypoint bash alicerce/backup:local /usr/local/bin/verificar-restauracao.sh >/dev/null
$DOCKER network connect "$REDE" "alicerce-verificacao-$SUFIXO"
$DOCKER start -a "alicerce-verificacao-$SUFIXO"
echo "[$(date -u +%FT%TZ)] restauração verificada com sucesso"
