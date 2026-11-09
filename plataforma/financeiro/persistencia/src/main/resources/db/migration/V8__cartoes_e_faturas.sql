-- Cartões, faturas e parcelamento.
--
-- O movimento ganha a fatura e a parcela. Colunas novas e nulas não tocam nas linhas antigas: o
-- livro-razão não recebe UPDATE. Até aqui nenhuma conta podia ser cartão de crédito; o bloco abaixo
-- confere isso, porque a invariante "movimento de cartão sempre tem fatura" não vale para o passado.
--
-- A situação da fatura não é gravada: sai das datas e da soma dos pagamentos a cada leitura. Assim
-- desfazer um pagamento funciona sozinho, e a fatura só recebe INSERT.
--
-- Efeito novo:
--   * fatura_criada: a fatura nasceu sob demanda. Sem inverso: vazia, ela não muda nenhum número.

do $$
begin
  if exists (select 1 from movimento m join conta c on c.id = m.conta_id where c.tipo = 'cartao_credito') then
    raise exception 'Há movimentos em cartão de crédito sem fatura: a V8 supõe que não existem.';
  end if;
end
$$;

-- Os dados do cartão, com versões próprias: as versões antigas de conta não têm esses campos.
create table cartao_credito (
  conta_id           uuid primary key references conta,
  dia_fechamento     smallint not null check (dia_fechamento between 1 and 31),
  dia_vencimento     smallint not null check (dia_vencimento between 1 and 31),
  limite_centavos    bigint check (limite_centavos >= 0),
  conta_pagamento_id uuid references conta,          -- conta padrão para pagar a fatura
  versao             bigint not null default 1
);

create table cartao_credito_versao (
  cartao_credito_id uuid not null references cartao_credito,
  versao            bigint not null,
  dados             jsonb not null,
  operacao_id       uuid not null references operacao,
  primary key (cartao_credito_id, versao)
);

create table fatura (
  id        uuid primary key,
  dono_id   uuid not null references dono,
  cartao_id uuid not null references cartao_credito,
  periodo   text not null check (periodo ~ '^[0-9]{4}-[0-9]{2}$'),   -- o mês do vencimento
  fecha_em  date not null,
  vence_em  date not null,
  criada_em timestamptz not null default now(),
  unique (cartao_id, periodo),
  unique (id, cartao_id),                            -- alvo da chave composta de movimento
  check (vence_em >= fecha_em)
);
create index fatura_dono_vence_em on fatura (dono_id, vence_em);

-- A chave composta garante que a fatura é do cartão do movimento, e portanto "fatura só em cartão".
alter table movimento
  add column fatura_id      uuid,
  add column parcela        smallint,
  add column total_parcelas smallint,
  add constraint movimento_fatura_do_cartao foreign key (fatura_id, conta_id) references fatura (id, cartao_id),
  add constraint movimento_parcela_completa check (num_nulls(parcela, total_parcelas) in (0, 2)),
  add constraint movimento_parcela_valida check (total_parcelas between 2 and 48 and parcela between 1 and total_parcelas),
  add constraint movimento_parcela_em_fatura check (parcela is null or fatura_id is not null);
create index movimento_fatura on movimento (fatura_id) where fatura_id is not null;

-- Com juros, o gasto entra pelo total. O preço à vista fica só para consulta.
alter table lancamento
  add column preco_a_vista_centavos bigint check (preco_a_vista_centavos > 0);

-- "Movimento de cartão sempre tem fatura". Só em INSERT: movimento não recebe UPDATE.
create function exigir_fatura_em_cartao() returns trigger
language plpgsql as $$
begin
  if new.fatura_id is null and exists (select 1 from conta where id = new.conta_id and tipo = 'cartao_credito') then
    raise exception 'movimento em cartão sem fatura: conta %', new.conta_id
      using errcode = '23514', constraint = 'movimento_cartao_com_fatura';
  end if;
  return new;
end;
$$;

create trigger movimento_cartao_com_fatura
  before insert on movimento
  for each row execute function exigir_fatura_em_cartao();

alter table efeito_operacao drop constraint efeito_operacao_tipo_check;
alter table efeito_operacao add constraint efeito_operacao_tipo_check check (tipo in (
  'lancamento_criado', 'lancamento_estornado', 'chave_fiscal_movida',
  'entidade_criada', 'entidade_alterada',
  'operacao_desfeita', 'operacao_restabelecida',
  'comprovante_vinculado', 'token_anexo_emitido', 'retrato_gravado', 'fatura_criada'));
