-- Perfil declarado: o que o dono pretende, por tipo.
--
-- A declaração é um registro: só recebe INSERT, como a conferência e a ocorrência (V12). A modelagem
-- encerra a versão anterior com valid_to; aqui nada é encerrado. Cada linha leva a operação que a gravou,
-- e a vigente de cada tipo é a última gravada cuja operação não foi desfeita. Assim, desfazer a nova traz
-- de volta a anterior sem UPDATE e sem inverso próprio.
--
-- Tipos da modelagem, em português: intencao_poupanca, reserva_minima, teto_parcelas, meta_protegida,
-- nota, piso_periodo, categorias_essenciais. Esta versão só aceita piso_periodo e categorias_essenciais;
-- o check já conhece os outros para não pedir outra migração.
--
-- valor: o JSON do tipo, validado no domínio. piso_periodo: {"periodo": "mes", "saldo_minimo_centavos": 1,
-- "contas_pagas": true}; categorias_essenciais: {"categorias": ["<uuid da conta>", ...]}. Dinheiro em
-- centavos também no JSON.
--
-- Efeito novo, sem inverso próprio (o estado da operação basta):
--   * declaracao_registrada: uma declaração foi gravada.

create table declaracao (
  id             uuid primary key,
  dono_id        uuid not null references dono,
  tipo           text not null check (tipo in ('intencao_poupanca', 'reserva_minima', 'teto_parcelas', 'meta_protegida', 'nota',
                                               'piso_periodo', 'categorias_essenciais')),
  valor          jsonb not null,
  vigente_desde  date not null,
  observacao     text,
  operacao_id    uuid not null references operacao,
  registrada_em  timestamptz not null default now(),
  ordem          bigint generated always as identity   -- a última gravada de um tipo é a que vale
);
create index declaracao_dono on declaracao (dono_id, tipo, ordem desc);

alter table efeito_operacao drop constraint efeito_operacao_tipo_check;
alter table efeito_operacao add constraint efeito_operacao_tipo_check check (tipo in (
  'lancamento_criado', 'lancamento_estornado', 'chave_fiscal_movida',
  'entidade_criada', 'entidade_alterada',
  'operacao_desfeita', 'operacao_restabelecida',
  'comprovante_vinculado', 'token_anexo_emitido', 'retrato_gravado', 'fatura_criada',
  'conferencia_registrada', 'ocorrencia_registrada', 'declaracao_registrada'));
