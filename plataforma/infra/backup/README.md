# Backup PostgreSQL com restic e S3

> Agentes de IA: leia primeiro [`AGENTS.md`](AGENTS.md). Ele define a
> arquitetura, os invariantes de segurança e o checklist obrigatório para
> aplicar este pacote em outro ambiente.

Pacote reutilizável para executar backups lógicos de PostgreSQL em um
repositório restic dedicado por ambiente e armazenado em um bucket S3 dedicado.

Fluxo:

```text
PostgreSQL -> pg_dump (custom, sem compressão) -> restic -> S3
```

O dump não é gravado em disco. O restic recebe a saída do `pg_dump`, comprime,
deduplica e criptografa os dados antes de enviá-los ao S3.

## Conteúdo

- `bootstrap-s3.sh`: cria/configura bucket, versionamento, criptografia,
  bloqueio público, usuário IAM e policy restrita ao prefixo do ambiente.
- `create-aws-credentials.sh`: cria uma access key e grava o arquivo AWS
  shared-credentials sem exibir o secret.
- `Dockerfile`: imagem com PostgreSQL client e restic.
- `backup-once.sh`: executa uma captura única.
- `backup-loop.sh`: executa capturas periódicas.
- `list-versions.sh`: lista as versões lógicas (snapshots) do repositório restic.
- `restore-latest.sh`: extrai o dump mais recente para restauração controlada.
- `export-credentials.sh`: gera um bundle temporário determinístico para
  armazenar as credenciais em um cofre seguro ou exportá-las para o Proton
  Pass.
- `credentials-bundle.sh`: interface para criar, validar e instalar o bundle
  operacional em outro ambiente sem imprimir seus valores.
- `setup-environment.sh`: wizard interativo para a configuração local humana.
- `docker-compose.backup.yml`: serviço para adicionar ao Compose do ambiente.
- `.env.example`: configuração sem segredos.

## Instalação por ambiente — caminho recomendado

Cada ambiente deve ter seu próprio bucket, prefixo, usuário IAM e repositório
restic. Não reutilize a senha restic entre ambientes.

1. Copie este pacote para `ops/backup/` no host do ambiente.

2. Como administrador AWS, crie o bucket e o IAM:

   ```bash
   AWS_PROFILE=bootstrap AWS_REGION=us-east-1 ENVIRONMENT=staging \
   IAM_USER=staging-postgres-backup ./ops/backup/bootstrap-s3.sh
   ```

3. Rode o wizard local. Ele fará perguntas simples, mostrará os valores
   escolhidos e gerará `.env` e as senhas locais sem exibi-las:

   ```bash
   ./ops/backup/setup-environment.sh
   ```

4. Crie a access key do IAM:

   ```bash
   AWS_PROFILE=bootstrap IAM_USER=staging-postgres-backup \
   OUTPUT_FILE=ops/backup/secrets/aws-credentials \
   ./ops/backup/create-aws-credentials.sh
   ```

5. Crie/verifique a role PostgreSQL read-only usando
   `ops/backup/postgres-readonly-role.sql`. A senha deve ser a mesma que o
   wizard gravou em `ops/backup/secrets/postgres-password`.

6. Gere o bundle operacional para armazenar como anexo no cofre:

   ```bash
   ENV_FILE=ops/backup/.env SECRETS_DIR=ops/backup/secrets \
   ./ops/backup/credentials-bundle.sh create \
     /tmp/postgres-restic-production-keycloak-bundle.txt
   ```

   O bundle é um arquivo transportável com campos codificados para evitar
   problemas de escaping; ele não é criptografado pelo script. Mantenha-o
   somente como anexo protegido no gerenciador de senhas e remova a cópia local
   depois de armazená-lo.

   Em um novo ambiente, o agente deve usar somente a interface de instalação:

   ```bash
   ./ops/backup/credentials-bundle.sh validate /path/to/bundle.txt
   TARGET_DIR=ops/backup \
   ./ops/backup/credentials-bundle.sh install /path/to/bundle.txt
   ```

   `install` recusa sobrescrever `.env` ou `secrets/`, cria os arquivos com
   `0600/0700` e não imprime os valores. `metadata` não faz parte da interface
   operacional justamente para evitar exposição acidental.

7. Valide, inicialize e teste:

   ```bash
   docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \
     --profile backup config --quiet
   docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \
     --profile backup build database_backup
   docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \
     --profile backup run --rm --no-deps database_backup restic init
   docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \
     --profile backup run --rm database_backup /usr/local/bin/backup-once.sh
   ```

7. Só depois do primeiro backup bem-sucedido, ative a rotina:

   ```bash
   docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \
     --profile backup up -d database_backup
   ```

## Instalação manual detalhada

As etapas abaixo são equivalentes ao wizard e servem para automação, auditoria
ou ambientes que não permitem interação.

1. Como administrador AWS, defina os parâmetros e execute:

   ```bash
   AWS_PROFILE=bootstrap AWS_REGION=us-east-1 ENVIRONMENT=staging \
   IAM_USER=staging-postgres-backup ./ops/backup/bootstrap-s3.sh
   ```

   O script cria um bucket com nome derivado da conta, região e ambiente.
   Use `BUCKET=...` para definir um nome explícito.

2. Crie a access key do usuário IAM:

   ```bash
   AWS_PROFILE=bootstrap IAM_USER=staging-postgres-backup \
   OUTPUT_FILE=ops/backup/secrets/aws-credentials ./ops/backup/create-aws-credentials.sh
   ```

3. Crie os secrets locais:

   ```bash
   mkdir -p ops/backup/secrets
   chmod 700 ops/backup/secrets
   umask 077
   openssl rand -hex 32 > ops/backup/secrets/restic-password
   openssl rand -hex 32 > ops/backup/secrets/postgres-password
   chmod 600 ops/backup/secrets/*
   ```

4. Crie uma role PostgreSQL dedicada e somente leitura. A senha deve ser
   gravada em `ops/backup/secrets/postgres-password`; não use a role administrativa da
   aplicação. O arquivo `postgres-readonly-role.sql` fornece um ponto de
   partida para essa criação; ajuste o nome do banco na concessão `CONNECT`.

5. Copie e preencha a configuração:

   ```bash
   cp ops/backup/.env.example ops/backup/.env
   chmod 600 ops/backup/.env
   ```

6. Use `ops/backup/docker-compose.backup.yml` como segundo arquivo Compose.
   Ajuste `BACKUP_NETWORK` e, se necessário, `DB_HOST` em `ops/backup/.env`.

7. Construa e inicialize o repositório uma única vez:

   ```bash
   docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \
     --profile backup build database_backup
   docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \
     --profile backup run --rm --no-deps database_backup restic init
   ```

8. Execute e valide o primeiro backup:

   ```bash
   docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \
     --profile backup run --rm database_backup /usr/local/bin/backup-once.sh
   docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \
     --profile backup run --rm --no-deps database_backup restic snapshots
   ```

9. Ative a rotina periódica:

   ```bash
   docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \
     --profile backup up -d database_backup
   ```

## Segurança operacional

- Não use credenciais root no container.
- Não versione `.env` ou `secrets/`.
- Mantenha um bucket e um repositório restic por ambiente.
- Guarde a senha restic em um gerenciador externo; sem ela, os dados não podem
  ser restaurados.
- Teste restauração periodicamente em um PostgreSQL temporário.
- O versionamento S3 protege contra exclusões acidentais, mas exige uma
  política lifecycle para versões não correntes em ambientes de longa duração.

## Verificação e restauração

As versões de backup são os snapshots do restic. Depois de instalar o bundle
operacional, liste-as sem expor qualquer credencial:

```bash
docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \
  --profile backup run --rm --no-deps database_backup \
  /usr/local/bin/list-versions.sh
```

Também é possível filtrar ou obter JSON; os argumentos são encaminhados ao
comando somente leitura `restic snapshots`:

```bash
docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \
  --profile backup run --rm --no-deps database_backup \
  /usr/local/bin/list-versions.sh --latest 5
```

Isso lista versões lógicas do restic, não versões de objetos S3. O versionamento
do S3 é uma camada distinta, administrada pela API do bucket e normalmente
consultada com uma credencial AWS que tenha `s3:ListBucketVersions`; a policy
operacional do backup não precisa conceder essa ação.

```bash
docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \
  --profile backup run --rm --no-deps database_backup restic check
docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml \
  --profile backup run --rm --no-deps database_backup \
  /usr/local/bin/restore-latest.sh /tmp/postgresql-latest.dump
```

O arquivo gerado é um dump customizado e deve ser restaurado com `pg_restore`
em um banco temporário antes de qualquer promoção para produção.
