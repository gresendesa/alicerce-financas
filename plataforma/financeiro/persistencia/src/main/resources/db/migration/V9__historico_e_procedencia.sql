-- Histórico e procedência.
--
-- Marco: a data em que o Alicerce passa a responder pelo saldo de uma conta. Fato antes dele entra por
-- inteiro, sem mexer na conta, contra Saldo de abertura. A conta é mutável, então aqui o marco é preenchido
-- com UPDATE; ele fica fora de conta_versao porque não muda depois de criado (recuar o marco é outra operação), e as
-- versões antigas não têm esse campo.
--
-- Procedência: endogeno nasceu no sistema, exogeno veio de fora. O livro-razão não recebe UPDATE, e um
-- valor padrão chamaria de endógena a abertura antiga. Por isso a coluna fica nula nas linhas antigas, e a
-- leitura deduz pelo tipo (abertura é exógena; o resto, endógeno). Toda linha nova é obrigada a declarar.
--
-- Gastos antigos com data anterior ao marco continuam como estão: eles mexeram no saldo e são endógenos.

create type procedencia_lancamento as enum ('endogeno', 'exogeno');

-- conta_historica_id: a conta a que o fato exógeno pertence. Nenhum movimento a toca, e sem isso um ganho
-- anterior ao marco não diria em que conta entrou.
alter table lancamento
  add column procedencia        procedencia_lancamento,
  add column conta_historica_id uuid references conta,
  add constraint lancamento_conta_historica_exogena check (conta_historica_id is null or procedencia = 'exogeno');

create function exigir_procedencia() returns trigger
language plpgsql as $$
begin
  if new.procedencia is null then
    raise exception 'lançamento sem procedência: %', new.id
      using errcode = '23514', constraint = 'lancamento_com_procedencia';
  end if;
  return new;
end;
$$;

create trigger lancamento_com_procedencia
  before insert on lancamento
  for each row execute function exigir_procedencia();

alter table conta add column marco_inicial date;

update conta c set marco_inicial = coalesce(
  (select min(l.data) from lancamento l join movimento m on m.lancamento_id = l.id
    where m.conta_id = c.id and l.tipo = 'saldo_abertura' and not lancamento_anulado(l.id)),
  (select (o.criada_em at time zone d.fuso)::date
     from conta_versao v
     join operacao o on o.id = v.operacao_id
     join dono d on d.id = o.dono_id
    where v.conta_id = c.id and v.versao = 1),
  current_date)
where c.classe in ('ativo', 'passivo');

alter table conta add constraint conta_marco_em_conta_com_saldo
  check ((marco_inicial is not null) = (classe in ('ativo', 'passivo')));

-- Parcela de compra em andamento: sem fatura se é anterior, ligada à abertura da fatura se já estava
-- dentro dela. Esses movimentos ficam em Saldo de abertura, e por isso não usam fatura_id, que é só de cartão.
alter table movimento
  add column fatura_abertura_id uuid references fatura,
  drop constraint movimento_parcela_em_fatura;
create index movimento_fatura_abertura on movimento (fatura_abertura_id) where fatura_abertura_id is not null;

-- Parcela fora de fatura só existe na contrapartida do histórico. Só em INSERT: movimento não recebe UPDATE.
create function exigir_parcela_em_fatura() returns trigger
language plpgsql as $$
begin
  if new.parcela is not null and new.fatura_id is null
     and not exists (select 1 from conta where id = new.conta_id and tipo = 'saldo_abertura') then
    raise exception 'parcela sem fatura: conta %', new.conta_id
      using errcode = '23514', constraint = 'movimento_parcela_em_fatura';
  end if;
  if new.fatura_abertura_id is not null
     and not exists (select 1 from conta where id = new.conta_id and tipo = 'saldo_abertura') then
    raise exception 'só a contrapartida do histórico se liga à abertura: conta %', new.conta_id
      using errcode = '23514', constraint = 'movimento_abertura_em_saldo_abertura';
  end if;
  return new;
end;
$$;

create trigger movimento_parcela_em_fatura
  before insert on movimento
  for each row execute function exigir_parcela_em_fatura();
