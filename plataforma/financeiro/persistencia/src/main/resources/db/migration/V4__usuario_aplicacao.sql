-- Usuário da aplicação: o dono do esquema (quem roda o Flyway) não é
-- quem a aplicação usa. A aplicação entra como financeiro_app, que só tem o mínimo:
--   * livro-razão (lancamento, movimento, item_lancamento) e registros (versões, endereços curtos,
--     efeitos, auditoria): só SELECT e INSERT; nunca UPDATE nem DELETE;
--   * operacao: UPDATE só em estado e desfeita_por;
--   * chave_fiscal: UPDATE só do ponteiro para o lançamento vigente;
--   * estrutura mutável (dono, instituicao, conta, ambito, meio_pagamento): SELECT, INSERT e UPDATE.
-- Ninguém além do dono do esquema apaga. A senha e o LOGIN são definidos fora das migrações.
-- Toda migração nova que criar tabela declara aqui embaixo, numa migração própria, o que a
-- aplicação pode fazer com ela: não há privilégio padrão.

do $$
begin
  if not exists (select from pg_roles where rolname = 'financeiro_app') then
    create role financeiro_app nologin;
  end if;
end
$$;

grant usage on schema public to financeiro_app;

grant select, insert on
  lancamento, movimento, item_lancamento,
  instituicao_versao, conta_versao, ambito_versao, meio_pagamento_versao,
  referencia_curta, efeito_operacao, resultado_operacao, evento_auditoria
  to financeiro_app;

grant select, insert, update on dono, instituicao, conta, ambito, meio_pagamento to financeiro_app;

grant select, insert on operacao to financeiro_app;
grant update (estado, desfeita_por) on operacao to financeiro_app;

grant select, insert on chave_fiscal to financeiro_app;
grant update (lancamento_id) on chave_fiscal to financeiro_app;

grant usage on all sequences in schema public to financeiro_app;
