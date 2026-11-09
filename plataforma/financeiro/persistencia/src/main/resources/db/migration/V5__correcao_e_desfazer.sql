-- Correção e desfazer.
--
-- Efeitos novos:
--   * chave_fiscal_movida: o ponteiro da nota saiu de entidade_id (um lançamento); detalhe guarda a chave.
--     A chave_fiscal não tem id próprio, e sem isso o desfazer não saberia devolver o ponteiro.
--   * operacao_desfeita e operacao_restabelecida: a mudança de estado de outra operação, para desfazer
--     o desfazer trazer de volta o estado certo e para saber o que um desfazer reverteu.
-- O efeito lancamento_estornado aponta para a linha de estorno, de onde se chega ao original.

alter table efeito_operacao drop constraint efeito_operacao_tipo_check;
alter table efeito_operacao add constraint efeito_operacao_tipo_check check (tipo in (
  'lancamento_criado', 'lancamento_estornado', 'chave_fiscal_movida',
  'entidade_criada', 'entidade_alterada',
  'operacao_desfeita', 'operacao_restabelecida'));
alter table efeito_operacao add column detalhe text;

-- Um lançamento está anulado quando a cadeia de estornos que parte dele tem tamanho ímpar: estorno do
-- estorno restabelece o original. O índice único de estorna_lancamento_id sustenta a busca.
create function lancamento_anulado(alvo uuid) returns boolean
language sql stable as $$
  with recursive cadeia(id, passos) as (
    select alvo, 0
    union all
    select l.id, c.passos + 1 from cadeia c join lancamento l on l.estorna_lancamento_id = c.id
  )
  select max(passos) % 2 = 1 from cadeia
$$;
