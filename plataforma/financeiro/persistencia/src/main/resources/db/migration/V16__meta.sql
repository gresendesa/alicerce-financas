-- Metas de valor: goal da modelagem, com nomes em português.
--
-- A meta é estrutura mutável e versionada, como o compromisso (V12): definir_meta cria e altera, e cada versão fica
-- em meta_versao para o desfazer restaurar. Desfazer a criação arquiva; nada é apagado.
--
-- situacao: ativa, concluida (o dono deu por cumprida) ou encerrada (o dono desistiu). A modelagem tinha
-- active/achieved/paused/abandoned; aqui ficaram três.
--
-- O progresso não é guardado: é a soma dos aportes menos os resgates, lida dos lançamentos aporte_meta vigentes
-- (decisão 2). Por isso o lançamento ganha meta_id e meta_resgate, que a V2 deixou para esta migração. São colunas
-- novas e anuláveis, preenchidas só no INSERT: o livro-razão continua sem UPDATE nem DELETE. O estorno de um aporte
-- não leva a meta: a leitura ignora lançamento anulado.

create table meta (
  id                  uuid primary key,
  dono_id             uuid not null references dono,
  nome                text not null,
  aliases             text[] not null default '{}',
  alvo_centavos       bigint not null check (alvo_centavos > 0),
  moeda               char(3) not null check (moeda ~ '^[A-Z]{3}$'),
  data_alvo           date,
  prioridade          smallint not null default 3 check (prioridade between 1 and 5),
  conta_reservada_id  uuid references conta,
  situacao            text not null default 'ativa' check (situacao in ('ativa', 'concluida', 'encerrada')),
  observacao          text,
  arquivada           boolean not null default false,
  versao              bigint not null default 1
);
create index meta_dono on meta (dono_id);

create table meta_versao (
  meta_id     uuid not null references meta,
  versao      bigint not null,
  dados       jsonb not null,
  operacao_id uuid not null references operacao,
  primary key (meta_id, versao)
);

alter table lancamento add column meta_id uuid references meta;
alter table lancamento add column meta_resgate boolean;
alter table lancamento add constraint lancamento_meta_so_em_aporte
  check ((tipo = 'aporte_meta') = (meta_id is not null) and (meta_id is null) = (meta_resgate is null));
create index lancamento_meta on lancamento (meta_id) where meta_id is not null;
