-- Partida dobrada: no commit, todo lançamento tem movimentos e, dentro de cada moeda, eles
-- somam zero. Adiável, para a transação gravar o lançamento e os movimentos em qualquer ordem.

create function verificar_soma_zero() returns trigger
language plpgsql as $$
declare
  alvo uuid;
  desequilibrada record;
begin
  -- Em comandos separados: NEW de lancamento não tem lancamento_id, e vice-versa.
  if tg_table_name = 'lancamento' then
    alvo := new.id;
  else
    alvo := new.lancamento_id;
  end if;
  if not exists (select 1 from movimento where lancamento_id = alvo) then
    raise exception 'lançamento % sem movimentos', alvo
      using errcode = '23514', constraint = 'lancamento_soma_zero';
  end if;
  select moeda, sum(valor_centavos) as soma into desequilibrada
    from movimento where lancamento_id = alvo
    group by moeda having sum(valor_centavos) <> 0
    limit 1;
  if found then
    raise exception 'lançamento % não soma zero em %: % centavos', alvo, desequilibrada.moeda, desequilibrada.soma
      using errcode = '23514', constraint = 'lancamento_soma_zero';
  end if;
  return null;
end;
$$;

create constraint trigger lancamento_soma_zero
  after insert on lancamento
  deferrable initially deferred
  for each row execute function verificar_soma_zero();

create constraint trigger movimento_soma_zero
  after insert on movimento
  deferrable initially deferred
  for each row execute function verificar_soma_zero();
