# AGENTS.md — guia para agentes de IA

Este repositório é um pacote operacional reutilizável para backup lógico de
PostgreSQL com restic em S3. Agentes devem ler este arquivo antes de modificar
qualquer script, Compose ou documentação.

## Objetivo e fronteiras

O pacote deve permitir que uma equipe instale, opere, verifique e restaure
backups de PostgreSQL em qualquer ambiente, mantendo um bucket e um repositório
restic separados por ambiente.

O pacote não deve:

- armazenar credenciais AWS, senhas, dumps ou configurações reais;
- copiar o volume físico do PostgreSQL;
- usar a role administrativa da aplicação para backups permanentes;
- executar restauração diretamente em produção sem confirmação explícita;
- compartilhar automaticamente um repositório entre ambientes.

## Ordem de leitura

1. `AGENTS.md` — regras e invariantes deste pacote.
2. `README.md` — instalação humana e operação de ponta a ponta.
3. `setup-environment.sh` — wizard preferencial para configuração local.
4. `.env.example` — contrato de configuração sem valores secretos.
5. `docker-compose.backup.yml` — integração no ambiente consumidor.
6. `backup-once.sh` e `backup-loop.sh` — fluxo de execução.
7. `list-versions.sh` — listagem somente leitura dos snapshots restic.
8. `bootstrap-s3.sh` e `create-aws-credentials.sh` — provisionamento AWS.
9. `postgres-readonly-role.sql` e `restore-latest.sh` — recuperação e acesso.

## Arquitetura que deve ser preservada

```text
PostgreSQL
  │ pg_dump --format=custom --compress=none
  ▼
restic --stdin-from-command
  │ compressão, chunking, deduplicação e criptografia
  ▼
S3: um bucket/prefixo/repositório por ambiente
```

`--stdin-from-command` é obrigatório. Não substituir por um pipeline
`pg_dump | restic --stdin`: o restic precisa observar o exit code do produtor e
recusar o snapshot quando o `pg_dump` falhar.

`--compress=none` é obrigatório no `pg_dump`: a compressão deve ficar sob o
controle do restic para preservar deduplicação entre snapshots.

## Invariantes de segurança

- `RESTIC_PASSWORD_FILE`, `DB_PASSWORD_FILE` e
  `AWS_SHARED_CREDENTIALS_FILE` devem apontar para arquivos montados read-only.
- Arquivos em `secrets/` devem ter modo `0600`; o diretório deve ter modo `0700`.
- Nunca imprimir ou registrar valores de secrets.
- O container deve usar filesystem read-only e um `/tmp` temporário.
- A policy IAM deve limitar `ListBucket` ao prefixo do ambiente e objetos às
  ações necessárias do restic.
- A role PostgreSQL deve ser dedicada, somente leitura e com
  `default_transaction_read_only=on`.
- O Compose deve permanecer sem portas publicadas.
- `restic forget --prune` só é seguro quando o repositório é exclusivo do
  ambiente/banco. Se isso mudar, adicionar filtragem por tags antes de alterar
  a retenção.
- `list-versions.sh` lista versões lógicas (`restic snapshots`) e nunca executa
  operações destrutivas. Isso é diferente das versões de objetos S3, que são
  consultadas pela API do bucket e exigem `s3:ListBucketVersions`; essa ação não
  deve ser adicionada à policy operacional apenas para listar snapshots restic.

## Como aplicar em um novo ambiente

O agente deve parametrizar `ENVIRONMENT`, `AWS_REGION`, `IAM_USER`, `PREFIX`,
`BUCKET`, `BACKUP_NETWORK`, `DB_HOST`, `DB_NAME` e `DB_USER`. Nunca inferir ou
copiar valores de outro ambiente.

Sequência obrigatória:

1. Executar `bootstrap-s3.sh` com credencial administrativa AWS.
2. Executar `setup-environment.sh` para gerar `.env` e secrets locais.
3. Criar access key dedicada com `create-aws-credentials.sh`.
4. Criar a role read-only usando `postgres-readonly-role.sql`.
5. Confirmar que os secrets estão protegidos com `0700/0600`.
6. Confirmar `.env` sem senhas nele.
7. Validar o Compose.
8. Construir a imagem.
9. Executar `restic init` uma única vez.
10. Executar um backup manual.
11. Listar snapshots e executar `restic check`.
12. Só então ativar o serviço periódico.

## Validação antes de concluir uma alteração

Execute, no mínimo:

```bash
bash -n *.sh
docker build -f Dockerfile .
docker compose -f docker-compose.yml -f ops/backup/docker-compose.backup.yml config --quiet
```

Quando o pacote estiver sendo validado isoladamente, ajuste os caminhos do
Compose ao diretório de extração. Testes que exigem AWS ou PostgreSQL real só
podem usar um ambiente explicitamente autorizado.

## Alterações que exigem confirmação humana

Pare e peça confirmação antes de:

- criar, rotacionar, desativar ou remover credenciais AWS;
- alterar policy IAM, bucket, lifecycle ou versionamento;
- executar `restic forget --prune` em repositório compartilhado;
- restaurar em banco de produção;
- alterar retenção, frequência, RPO ou RTO;
- tornar o repositório público ou publicar dados específicos de um ambiente.

## Critério de conclusão

Uma alteração só está pronta quando:

- não contém segredos ou identificadores de ambiente indevidos;
- preserva os invariantes de segurança acima;
- possui documentação atualizada;
- passa nas validações estáticas e de Compose;
- inclui instruções de restauração quando altera o formato ou o caminho do dump;
- informa claramente qualquer teste real que não foi executado.
