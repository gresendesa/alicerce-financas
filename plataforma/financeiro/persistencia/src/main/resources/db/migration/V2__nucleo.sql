-- Núcleo do livro-razão: estrutura, fatos imutáveis, endereços curtos, operações e auditoria.
-- Nomes conforme a tabela de nomenclatura da modelagem. Dinheiro em centavos (bigint), datas no fuso
-- do dono. Colunas que apontam para tabelas futuras (meta, ocorrencia_compromisso, fatura) entram nas
-- migrações que criarem essas tabelas.
-- Multimoeda (decisão de 2026-10-28): conta e movimento têm moeda, e o movimento só entra em conta da
-- mesma moeda (chave estrangeira composta). A soma zero vale por moeda (V3).

-- Operações (história 7): toda escrita gera uma, com seus efeitos em ordem.
create table operacao (
  id                 uuid primary key,
  dono_id            uuid not null references dono,
  capacidade         text not null,                -- 'registrar_gasto', 'desfazer', ...
  argumentos         jsonb not null,
  lote               text,                         -- escolhido pelo assistente; agrupa um pedido
  cliente            text not null,                -- azp do token: qual assistente
  chave_idempotencia text,
  hash_argumentos    text,                         -- SHA-256 da forma canônica dos argumentos
  estado             text not null default 'aplicada' check (estado in ('aplicada', 'desfeita')),
  desfeita_por       uuid references operacao,     -- a operação 'desfazer' que a reverteu
  criada_em          timestamptz not null default now(),
  unique (dono_id, chave_idempotencia),
  check ((chave_idempotencia is null) = (hash_argumentos is null))
);
create index operacao_dono_lote on operacao (dono_id, lote);
create index operacao_dono_criada_em on operacao (dono_id, criada_em desc);

-- O resultado devolvido de novo na repetição da mesma chamada. Tabela à parte porque é gravado no fim
-- da escrita, e a operação não recebe UPDATE além de estado e desfeita_por.
create table resultado_operacao (
  operacao_id uuid primary key references operacao,
  resultado   jsonb not null
);

create table efeito_operacao (
  operacao_id     uuid not null references operacao,
  ordem           smallint not null check (ordem > 0),
  tipo            text not null check (tipo in (
                    'lancamento_criado', 'lancamento_estornado', 'entidade_criada', 'entidade_alterada')),
  entidade        text not null,                   -- 'lancamento', 'conta', 'ambito', ...
  entidade_id     uuid not null,
  versao_anterior bigint,                          -- entidades mutáveis: a versão a restaurar
  primary key (operacao_id, ordem)
);
create index efeito_operacao_entidade on efeito_operacao (entidade, entidade_id);

-- Estrutura (mutável, com versões para o desfazer restaurar)
create table instituicao (
  id           uuid primary key,
  dono_id      uuid not null references dono,
  nome         text not null,
  aliases      text[] not null default '{}',
  arquivado_em timestamptz,
  versao       bigint not null default 1,
  unique (dono_id, nome)
);

create type classe_conta as enum ('ativo', 'passivo', 'receita', 'despesa', 'patrimonio');
create type tipo_conta as enum (
  'corrente', 'poupanca', 'carteira', 'investimento', 'reserva',  -- ativo
  'cartao_credito', 'emprestimo',                                  -- passivo
  'categoria', 'fonte_renda',                                      -- despesa / receita
  'saldo_abertura', 'nao_identificado'                             -- patrimonio
);

create table conta (
  id             uuid primary key,
  dono_id        uuid not null references dono,
  classe         classe_conta not null,
  tipo           tipo_conta not null,
  nome           text not null,
  aliases        text[] not null default '{}',
  conta_pai_id   uuid references conta,             -- hierarquia de categorias
  instituicao_id uuid references instituicao,
  moeda          char(3) not null check (moeda ~ '^[A-Z]{3}$'),  -- ISO 4217; a aplicação usa a do dono
  arquivado_em   timestamptz,
  versao         bigint not null default 1,
  unique nulls not distinct (dono_id, classe, conta_pai_id, nome),
  unique (id, moeda),                               -- alvo da chave composta de movimento
  check (case classe
           when 'ativo'      then tipo in ('corrente', 'poupanca', 'carteira', 'investimento', 'reserva')
           when 'passivo'    then tipo in ('cartao_credito', 'emprestimo')
           when 'despesa'    then tipo = 'categoria'
           when 'receita'    then tipo in ('categoria', 'fonte_renda')
           when 'patrimonio' then tipo in ('saldo_abertura', 'nao_identificado')
         end)
);

create table ambito (
  id           uuid primary key,
  dono_id      uuid not null references dono,
  nome         text not null,
  aliases      text[] not null default '{}',
  padrao       boolean not null default false,       -- exatamente um por dono
  arquivado_em timestamptz,
  versao       bigint not null default 1,
  unique (dono_id, nome),
  check (not (padrao and arquivado_em is not null))
);
create unique index ambito_um_padrao_por_dono on ambito (dono_id) where padrao;

create type tipo_meio_pagamento as enum ('pix', 'debito', 'credito', 'dinheiro', 'transferencia', 'boleto');
create table meio_pagamento (
  id                   uuid primary key,
  dono_id              uuid not null references dono,
  tipo                 tipo_meio_pagamento not null,
  nome                 text not null,
  aliases              text[] not null default '{}',
  conta_liquidacao_id  uuid not null references conta,
  padrao               boolean not null default false,   -- padrão para o seu tipo
  ambito_padrao_id     uuid references ambito,
  arquivado_em         timestamptz,
  versao               bigint not null default 1,
  unique (dono_id, nome),
  check (not (padrao and arquivado_em is not null))
);
create unique index meio_pagamento_um_padrao_por_tipo on meio_pagamento (dono_id, tipo) where padrao;

-- Versões: o estado completo de cada versão, gravado pelo registrador na mesma transação.
create table instituicao_versao (
  instituicao_id uuid not null references instituicao,
  versao         bigint not null,
  dados          jsonb not null,
  operacao_id    uuid not null references operacao,
  primary key (instituicao_id, versao)
);
create table conta_versao (
  conta_id    uuid not null references conta,
  versao      bigint not null,
  dados       jsonb not null,
  operacao_id uuid not null references operacao,
  primary key (conta_id, versao)
);
create table ambito_versao (
  ambito_id   uuid not null references ambito,
  versao      bigint not null,
  dados       jsonb not null,
  operacao_id uuid not null references operacao,
  primary key (ambito_id, versao)
);
create table meio_pagamento_versao (
  meio_pagamento_id uuid not null references meio_pagamento,
  versao            bigint not null,
  dados             jsonb not null,
  operacao_id       uuid not null references operacao,
  primary key (meio_pagamento_id, versao)
);

-- Fatos (imutáveis: sem UPDATE nem DELETE para a aplicação, ver V4)
create type tipo_lancamento as enum (
  'gasto', 'ganho', 'transferencia', 'pagamento_fatura',
  'aporte_meta', 'ajuste', 'saldo_abertura', 'estorno'
);
create type origem_lancamento as enum ('conversa', 'foto_nota', 'print_app', 'importacao_banco');

create table lancamento (
  id                    uuid primary key,
  dono_id               uuid not null references dono,
  operacao_id           uuid not null references operacao,     -- a operação que o registrou
  tipo                  tipo_lancamento not null,
  data                  date not null,
  descricao             text not null,
  contraparte           text,
  observacao            text,
  meio_pagamento_id     uuid references meio_pagamento,
  estorna_lancamento_id uuid unique references lancamento,     -- o estorno aponta para o original
  origem                origem_lancamento not null default 'conversa',
  cnpj_estabelecimento  text,
  registrado_em         timestamptz not null default now(),
  check ((tipo = 'estorno') = (estorna_lancamento_id is not null))
);
create index lancamento_dono_data on lancamento (dono_id, data);

create table movimento (
  id             uuid primary key,
  lancamento_id  uuid not null references lancamento,
  conta_id       uuid not null,
  moeda          char(3) not null,
  valor_centavos bigint not null check (valor_centavos <> 0),
  ambito_id      uuid references ambito,
  foreign key (conta_id, moeda) references conta (id, moeda)
);
create index movimento_lancamento on movimento (lancamento_id);
create index movimento_conta on movimento (conta_id);

create table item_lancamento (
  id             uuid primary key,
  lancamento_id  uuid not null references lancamento,
  posicao        smallint not null check (posicao > 0),
  descricao      text not null,
  quantidade     numeric(12, 3),
  conta_id       uuid not null,                               -- a categoria do item
  moeda          char(3) not null,
  valor_centavos bigint not null,
  ambito_id      uuid not null references ambito,
  unique (lancamento_id, posicao),
  foreign key (conta_id, moeda) references conta (id, moeda)
);

-- Chave da NFC-e: aponta sempre para o lançamento vigente; uma correção move o ponteiro.
create table chave_fiscal (
  dono_id       uuid not null references dono,
  chave_nfce    char(44) not null check (chave_nfce ~ '^[0-9]{44}$'),
  lancamento_id uuid not null references lancamento,
  primary key (dono_id, chave_nfce)
);

-- Endereços curtos: um por entidade, estáveis para sempre ('t-8K3QZ2').
create table referencia_curta (
  codigo        text primary key check (codigo ~ '^[a-z]-[0-9ABCDEFGHJKMNPQRSTVWXYZ]{6}$'),
  dono_id       uuid not null references dono,
  tipo_entidade text not null,       -- lancamento | conta | operacao | ...
  entidade_id   uuid not null,
  unique (tipo_entidade, entidade_id)
);

-- Auditoria: cada chamada de capacidade, com quem chamou e o resultado.
create table evento_auditoria (
  id                 bigint generated always as identity primary key,
  dono_id            uuid not null,
  ocorrido_em        timestamptz not null default now(),
  cliente            text not null,
  capacidade         text not null,
  escopos            text[] not null,
  chave_idempotencia text,
  argumentos         jsonb not null,
  resultado          text not null check (resultado in ('sucesso', 'erro')),
  codigo_erro        text,
  operacao_id        uuid references operacao,
  check ((resultado = 'erro') = (codigo_erro is not null))
);
create index evento_auditoria_dono_ocorrido_em on evento_auditoria (dono_id, ocorrido_em desc);
