-- Conferência de saldos e compromissos com ocorrências.
--
-- Conferência e ocorrência são registros: só recebem INSERT, como o livro-razão. Nenhuma das duas guarda
-- situação: a da conferência sai da diferença e do ajuste, e a da ocorrência sai da regra do compromisso e
-- das linhas gravadas. Cada linha leva a operação que a gravou, e a leitura ignora a linha de operação
-- desfeita; assim desfazer e refazer funcionam sem UPDATE, como na fatura (V8).
--
-- Ocorrência: só se grava a que foi paga, pulada ou adiada. As datas das demais saem da regra, e nada é
-- gerado para o futuro. O vínculo vai da ocorrência para o lançamento, e não
-- o contrário, porque o lançamento é imutável e o vínculo pode nascer depois dele (resolver_ocorrencia).
-- Paga com lançamento anulado não vale: anular ou desfazer o pagamento reabre a ocorrência.
--
-- Efeitos novos, sem inverso próprio (o estado da operação basta):
--   * conferencia_registrada: uma conferência foi gravada;
--   * ocorrencia_registrada: uma ocorrência foi paga, pulada ou adiada.

create table compromisso (
  id                   uuid primary key,
  dono_id              uuid not null references dono,
  direcao              text not null check (direcao in ('entrada', 'saida')),
  tipo                 text not null check (tipo in ('recorrente', 'parcelado', 'unico')),
  nome                 text not null,
  aliases              text[] not null default '{}',
  contraparte          text,
  referencia           text,                           -- número do parcelamento ou protocolo; nunca senha
  valor_centavos       bigint not null check (valor_centavos > 0),   -- de cada ocorrência
  valor_total_centavos bigint check (valor_total_centavos > 0),      -- só parcelado: a sobra vai na primeira
  moeda                char(3) not null check (moeda ~ '^[A-Z]{3}$'),
  frequencia           text not null check (frequencia in ('mensal', 'anual')),
  primeiro_vencimento  date not null,
  parcelas             smallint check (parcelas between 1 and 480),
  parcelas_pagas_antes smallint not null default 0 check (parcelas_pagas_antes >= 0),
  fim                  date,
  categoria_id         uuid references conta,
  conta_id             uuid references conta,
  meio_pagamento_id    uuid references meio_pagamento,
  ambito_id            uuid references ambito,
  observacao           text,
  encerrado            boolean not null default false,
  versao               bigint not null default 1,
  check ((tipo = 'parcelado') = (parcelas is not null)),
  check (valor_total_centavos is null or tipo = 'parcelado'),
  check (parcelas is null or parcelas_pagas_antes <= parcelas)
);
create index compromisso_dono on compromisso (dono_id);

create table compromisso_versao (
  compromisso_id uuid not null references compromisso,
  versao         bigint not null,
  dados          jsonb not null,
  operacao_id    uuid not null references operacao,
  primary key (compromisso_id, versao)
);

create table ocorrencia_compromisso (
  id             uuid primary key,
  compromisso_id uuid not null references compromisso,
  numero         integer not null check (numero > 0),
  vencimento     date not null,                     -- pela regra, quando a linha foi gravada
  situacao       text not null check (situacao in ('paga', 'pulada', 'adiada')),
  lancamento_id  uuid references lancamento,
  nova_data      date,
  observacao     text,
  operacao_id    uuid not null references operacao,
  registrada_em  timestamptz not null default now(),
  ordem          bigint generated always as identity,   -- a última gravada de um número é a que vale
  check ((situacao = 'paga') = (lancamento_id is not null)),
  check ((situacao = 'adiada') = (nova_data is not null))
);
create index ocorrencia_compromisso_compromisso on ocorrencia_compromisso (compromisso_id, numero);
create index ocorrencia_compromisso_lancamento on ocorrencia_compromisso (lancamento_id) where lancamento_id is not null;

create table conferencia (
  id                   uuid primary key,
  dono_id              uuid not null references dono,
  conta_id             uuid not null references conta,
  fatura_id            uuid references fatura,
  data                 date not null,
  informado_centavos   bigint not null,
  calculado_centavos   bigint not null,
  diferenca_centavos   bigint generated always as (informado_centavos - calculado_centavos) stored,
  moeda                char(3) not null check (moeda ~ '^[A-Z]{3}$'),
  ajuste_lancamento_id uuid references lancamento,
  operacao_id          uuid not null references operacao,
  registrada_em        timestamptz not null default now(),
  ordem                bigint generated always as identity,   -- entre conferências da mesma data, a última vale
  -- Fatura não tem ajuste automático.
  check (fatura_id is null or ajuste_lancamento_id is null)
);
create index conferencia_conta on conferencia (conta_id, data desc);

alter table efeito_operacao drop constraint efeito_operacao_tipo_check;
alter table efeito_operacao add constraint efeito_operacao_tipo_check check (tipo in (
  'lancamento_criado', 'lancamento_estornado', 'chave_fiscal_movida',
  'entidade_criada', 'entidade_alterada',
  'operacao_desfeita', 'operacao_restabelecida',
  'comprovante_vinculado', 'token_anexo_emitido', 'retrato_gravado', 'fatura_criada',
  'conferencia_registrada', 'ocorrencia_registrada'));
