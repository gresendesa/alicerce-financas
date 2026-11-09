# Origem deste diretório

Cópia do pacote `postgres-restic-s3-backup` do autor (commit `55db0c0`, após o revert da interface de terminal),
a mesma metodologia do backup do Keycloak de `credencie.me`. O `AGENTS.md` e o `README.md` são os do pacote.

Mudanças do alicerce, marcadas com `# alicerce:` no código:

* `backup-once.sh`: retenção só com as políticas definidas no `.env` (inclui `--keep-hourly` e `--keep-yearly`);
  ping do alerta externo (Healthchecks.io) após cada backup bem-sucedido.
* `backup-loop.sh`: ping de falha (`/fail`) quando um backup falha.
* `Dockerfile`: `curl` permanece na imagem; base `postgres:16.15-bookworm`, a mesma versão do banco.
* `.env.exemplo`: valores do ambiente `production-alicerce`; o serviço está em `../docker-compose.yml`.
* `restaurar-e-verificar.sh`: restauração verificada em Postgres descartável.

Não usados neste projeto: `setup-environment.sh` (o `.env` sai de `.env.exemplo`).
O bundle de `credentials-bundle.sh` não leva `RESTIC_KEEP_HOURLY`, `RESTIC_KEEP_YEARLY` nem a URL do alerta: numa
recuperação, a retenção vem do `.env.exemplo` versionado e a URL, da conta do Healthchecks.io.
* `provisionar-aws.sh`: chama `bootstrap-s3.sh` e `create-aws-credentials.sh` com os parâmetros de `production-alicerce` e aplica o lifecycle de 90 dias.
* `restore-latest.sh`: corrigido para achar o dump no restic 0.18 (o original pegava a linha do snapshot e saía com caminho vazio).
* `restaurar-e-verificar.sh` + `verificar-restauracao.sh`: restaura o snapshot num Postgres descartável (rede
  `--internal`, dados em tmpfs) e confere idade do snapshot, `pg_restore --exit-on-error`, histórico do Flyway,
  conjunto de tabelas, contagem de linhas e `restic check --read-data`. Limpa tudo no fim; falha avisa `/fail`.

## Agendamento no host

O serviço `backup` do Compose faz o backup horário. A verificação mensal fica no crontab do usuário que roda o Docker
(o host está em CEST; o cron usa a hora local):

```
0 5 1 * * /caminho/para/alicerce/plataforma/infra/backup/restaurar-e-verificar.sh >> $HOME/.local/state/alicerce/verificacao-backup.log 2>&1
```
