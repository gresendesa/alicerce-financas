#!/usr/bin/env bash
# alicerce: roda dentro do contêiner verificador criado por restaurar-e-verificar.sh. Restaura o snapshot no
# Postgres descartável ($BANCO_TEMPORARIO) e o compara com a origem, lida só pela role backup_readonly.
set -Eeuo pipefail
: "${SNAPSHOT:=latest}" "${IDADE_MAXIMA_HORAS:=26}" "${BANCO_TEMPORARIO:?}"
falhou=0
falha() { echo "FALHA: $*" >&2; falhou=1; }

# 1. Idade do snapshot
criado=$(restic snapshots "$SNAPSHOT" --json --no-lock | sed -n 's/.*"time":"\([^"]*\)".*/\1/p' | head -1)
idade=$(( ($(date -u +%s) - $(date -u -d "$criado" +%s)) / 3600 ))
echo "snapshot de $criado (${idade} h)"
(( idade <= IDADE_MAXIMA_HORAS )) || falha "snapshot com mais de ${IDADE_MAXIMA_HORAS} h"

# 2. Restauração completa, parando no primeiro erro
restore-latest.sh /tmp/verificacao.dump "$SNAPSHOT" >/dev/null
pg_restore --exit-on-error --no-owner --no-privileges -h "$BANCO_TEMPORARIO" -U postgres -d verificacao \
  /tmp/verificacao.dump
echo "pg_restore concluído sem erros ($(du -h /tmp/verificacao.dump | cut -f1))"

origem() { PGPASSWORD="$(cat "$DB_PASSWORD_FILE")" psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -At -c "$1"; }
restaurado() { psql -h "$BANCO_TEMPORARIO" -U postgres -d verificacao -At -c "$1"; }

# 3. Esquema: mesmo histórico do Flyway e mesmo conjunto de tabelas
consulta_flyway="select version || ':' || checksum from flyway_schema_history where success order by installed_rank"
if [[ "$(origem "$consulta_flyway")" == "$(restaurado "$consulta_flyway")" ]]; then
  echo "histórico do Flyway idêntico ($(restaurado "select count(*) from flyway_schema_history") migrações)"
else
  falha "histórico do Flyway diferente da origem"
fi
consulta_tabelas="select table_schema || '.' || table_name from information_schema.tables
  where table_type = 'BASE TABLE' and table_schema not in ('pg_catalog', 'information_schema') order by 1"
tabelas=$(restaurado "$consulta_tabelas")
if [[ "$(origem "$consulta_tabelas")" == "$tabelas" ]]; then
  echo "mesmas $(wc -l <<<"$tabelas") tabelas da origem"
else
  falha "conjunto de tabelas diferente da origem"
fi

# 4. Linhas por tabela. O snapshot pode ser até uma hora mais antigo que a origem: a contagem é
# informativa, e só falha se o banco restaurado vier vazio.
total=0
while read -r tabela; do
  r=$(restaurado "select count(*) from $tabela"); o=$(origem "select count(*) from $tabela")
  printf '  %-45s restaurado %6s  origem %6s\n' "$tabela" "$r" "$o"; total=$((total + r))
done <<<"$tabelas"
(( total > 0 )) || falha "banco restaurado sem nenhuma linha"
(( $(restaurado "select count(*) from public.dono") > 0 )) || falha "nenhum dono restaurado"

# 5. Integridade do repositório (pequeno: lê todos os dados)
if restic check --read-data --no-lock >/dev/null; then echo "restic check --read-data sem erros"
else falha "restic check encontrou erros"; fi

exit "$falhou"
