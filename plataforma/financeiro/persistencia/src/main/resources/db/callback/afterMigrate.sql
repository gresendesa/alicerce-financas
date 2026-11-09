-- Roda a cada subida, depois das migrações, como o dono do esquema. É a fonte de verdade do usuário
-- da aplicação e dos privilégios dele (a V4 só os aplicou pela primeira vez): o backup é feito com
-- --no-privileges, e numa restauração o Flyway não reaplica a V4. Aqui tudo é refeito do zero a
-- cada subida, então uma restauração se corrige sozinha.
-- Fica fora de db/migration para não rodar na geração das classes jOOQ.
--
-- Privilégios de financeiro_app (decisão de 2026-10-29):
--   * livro-razão (lancamento, movimento, item_lancamento) e registros (versões, endereços curtos,
--     efeitos, resultados, auditoria, retratos, faturas, conferências, ocorrências de compromisso, declarações): só SELECT
--     e INSERT; nunca UPDATE nem DELETE;
--   * operacao: UPDATE só em estado e desfeita_por;
--   * chave_fiscal: UPDATE só do ponteiro para o lançamento vigente;
--   * comprovante: UPDATE só do lancamento_id; token_anexo: UPDATE só de usado_em;
--   * link_compartilhamento: UPDATE só de revogado_em e revogado_por;
--   * estrutura mutável (dono, instituicao, conta, cartao_credito, ambito, meio_pagamento, compromisso, meta): SELECT,
--     INSERT e UPDATE.
-- Ninguém além do dono do esquema apaga. Toda tabela nova entra aqui; sem isso a aplicação não a acessa.

do $$
begin
  if not exists (select from pg_roles where rolname = 'financeiro_app') then
    create role financeiro_app nologin;
  end if;
end
$$;

revoke all on all tables in schema public from financeiro_app;
revoke all on all sequences in schema public from financeiro_app;

grant usage on schema public to financeiro_app;

grant select, insert on
  lancamento, movimento, item_lancamento,
  instituicao_versao, conta_versao, cartao_credito_versao, ambito_versao, meio_pagamento_versao, compromisso_versao, meta_versao,
  referencia_curta, efeito_operacao, resultado_operacao, evento_auditoria, retrato, fatura,
  conferencia, ocorrencia_compromisso, declaracao
  to financeiro_app;

grant select, insert, update on dono, instituicao, conta, cartao_credito, ambito, meio_pagamento, compromisso, meta to financeiro_app;

grant select, insert on operacao to financeiro_app;
grant update (estado, desfeita_por) on operacao to financeiro_app;

grant select, insert on chave_fiscal to financeiro_app;
grant update (lancamento_id) on chave_fiscal to financeiro_app;

grant select, insert on comprovante, token_anexo to financeiro_app;
grant update (lancamento_id) on comprovante to financeiro_app;
grant update (usado_em) on token_anexo to financeiro_app;

grant select, insert on link_compartilhamento to financeiro_app;
grant update (revogado_em, revogado_por) on link_compartilhamento to financeiro_app;

grant usage on all sequences in schema public to financeiro_app;

-- A senha vem da configuração (spring.datasource.password) e não pode conter aspas: gere com
-- `openssl rand -hex 32`.
alter role financeiro_app with login password '${senha_aplicacao}';
