-- Contas em moeda estrangeira: o tipo da conta técnica de conversão. Fica em migração própria
-- porque um valor novo de enum não pode ser usado na transação que o cria; a V11 o usa.
alter type tipo_conta add value 'conversao';
