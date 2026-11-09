-- Moeda original e retratos de cálculo.
--
-- O lançamento continua em uma só moeda: estas colunas só descrevem a compra original. Colunas novas e
-- nulas não tocam nas linhas antigas, que ficam sem moeda original: o livro-razão não recebe UPDATE.
-- valor_original_minimo está nas unidades mínimas da moeda (expoente ISO 4217: 2 no dólar, 0 no iene).
--
-- Efeito novo:
--   * retrato_gravado: um cálculo ficou guardado. Sem inverso: o retrato é imutável.

alter table lancamento
  add column valor_original_minimo bigint check (valor_original_minimo > 0),
  add column moeda_original        char(3) check (moeda_original ~ '^[A-Z]{3}$'),
  add column cotacao               numeric(20, 10) check (cotacao > 0),
  add column data_cotacao          date,
  add constraint lancamento_moeda_original_completa
    check (num_nulls(valor_original_minimo, moeda_original, cotacao, data_cotacao) in (0, 4));

-- Retratos de cálculo: só recebem INSERT. O resultado é o JSON que o assistente recebeu.
create table retrato (
  id              uuid primary key,
  dono_id         uuid not null references dono,
  capacidade      text not null,
  parametros      jsonb not null,
  data_referencia timestamptz not null,
  resultado       jsonb not null,
  cliente         text not null,
  criado_em       timestamptz not null default now()
);

alter table efeito_operacao drop constraint efeito_operacao_tipo_check;
alter table efeito_operacao add constraint efeito_operacao_tipo_check check (tipo in (
  'lancamento_criado', 'lancamento_estornado', 'chave_fiscal_movida',
  'entidade_criada', 'entidade_alterada',
  'operacao_desfeita', 'operacao_restabelecida',
  'comprovante_vinculado', 'token_anexo_emitido', 'retrato_gravado'));
