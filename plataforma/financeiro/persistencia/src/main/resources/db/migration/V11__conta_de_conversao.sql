-- Conta técnica de conversão, uma por moeda: num câmbio entre contas, cada moeda soma zero por
-- conta própria (V3), porque o valor passa pela conversão da moeda de origem e sai pela do destino.
alter table conta drop constraint conta_check;
alter table conta add constraint conta_check check (case classe
  when 'ativo'      then tipo in ('corrente', 'poupanca', 'carteira', 'investimento', 'reserva')
  when 'passivo'    then tipo in ('cartao_credito', 'emprestimo')
  when 'despesa'    then tipo = 'categoria'
  when 'receita'    then tipo in ('categoria', 'fonte_renda')
  when 'patrimonio' then tipo in ('saldo_abertura', 'nao_identificado', 'conversao')
end);
