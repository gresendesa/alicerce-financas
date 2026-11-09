-- Comprovantes. O arquivo fica no bucket privado; aqui só o índice, com referência curta n-XXXXXX.
--
-- comprovante.lancamento_id é o único campo que muda: desfazer desvincula e o arquivo continua guardado.
-- token_anexo guarda só o hash: o token cru existe apenas na URL entregue ao dono.
--
-- Efeitos novos:
--   * comprovante_vinculado: o comprovante mudou de lançamento; detalhe guarda "de>para" (- é nenhum).
--   * token_anexo_emitido: um link de envio nasceu. Sem inverso: a página recusa lançamento anulado.

create table comprovante (
  id                  uuid primary key,
  dono_id             uuid not null references dono,
  lancamento_id       uuid references lancamento,
  tipo_midia          text not null check (tipo_midia in ('image/jpeg', 'image/png', 'image/webp', 'image/heic')),
  tamanho_bytes       integer not null check (tamanho_bytes between 1 and 10485760),
  chave_armazenamento text not null,
  sha256              char(64) not null,
  enviado_em          timestamptz not null default now()
);
create index comprovante_lancamento on comprovante (lancamento_id);

create table token_anexo (
  id            uuid primary key,
  hash_token    char(64) not null unique,
  dono_id       uuid not null references dono,
  lancamento_id uuid not null references lancamento,
  expira_em     timestamptz not null,
  usado_em      timestamptz
);

alter table efeito_operacao drop constraint efeito_operacao_tipo_check;
alter table efeito_operacao add constraint efeito_operacao_tipo_check check (tipo in (
  'lancamento_criado', 'lancamento_estornado', 'chave_fiscal_movida',
  'entidade_criada', 'entidade_alterada',
  'operacao_desfeita', 'operacao_restabelecida',
  'comprovante_vinculado', 'token_anexo_emitido'));
