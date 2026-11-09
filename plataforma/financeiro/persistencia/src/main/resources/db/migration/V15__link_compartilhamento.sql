-- Links de prestação de contas: share_link da modelagem, com nomes em português.
--
-- O link aponta para um retrato de prestar_contas e nada mais: a página pública resolve o token, carrega
-- aquele retrato e não consulta o livro-razão. Como em token_anexo (V6), só o hash do token é guardado; o
-- token cru existe apenas na URL entregue ao dono.
--
-- Nenhum DELETE, e UPDATE só das colunas de revogação. A validade é lida do estado das operações:
--   * operacao_id é a operação que criou o link; desfeita, o link deixa de abrir;
--   * revogado_por é a operação que o revogou; desfeita, o link volta a abrir até expira_em. Uma revogação
--     nova depois de uma desfeita sobrescreve as duas colunas.
--
-- Efeitos novos, sem inverso próprio (o estado da operação basta):
--   * link_criado: um link de prestação de contas nasceu;
--   * link_revogado: um link foi revogado.

create table link_compartilhamento (
  id                   uuid primary key,
  hash_token           char(64) not null unique,
  dono_id              uuid not null references dono,
  retrato_id           uuid not null unique references retrato,
  incluir_comprovantes boolean not null default false,
  expira_em            timestamptz not null,
  operacao_id          uuid not null references operacao,
  revogado_em          timestamptz,
  revogado_por         uuid references operacao,
  criado_em            timestamptz not null default now(),
  check ((revogado_em is null) = (revogado_por is null))
);
create index link_compartilhamento_dono on link_compartilhamento (dono_id);

alter table efeito_operacao drop constraint efeito_operacao_tipo_check;
alter table efeito_operacao add constraint efeito_operacao_tipo_check check (tipo in (
  'lancamento_criado', 'lancamento_estornado', 'chave_fiscal_movida',
  'entidade_criada', 'entidade_alterada',
  'operacao_desfeita', 'operacao_restabelecida',
  'comprovante_vinculado', 'token_anexo_emitido', 'retrato_gravado', 'fatura_criada',
  'conferencia_registrada', 'ocorrencia_registrada', 'declaracao_registrada',
  'link_criado', 'link_revogado'));
