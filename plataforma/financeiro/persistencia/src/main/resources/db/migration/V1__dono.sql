-- Identidade: quem é o dono, segundo o Keycloak (emissor + sub do token OIDC).
create table dono (
  id            uuid primary key,
  emissor_oidc  text not null,
  sujeito_oidc  text not null,
  fuso          text not null default 'America/Sao_Paulo',
  moeda         char(3) not null default 'BRL',
  criado_em     timestamptz not null default now(),
  unique (emissor_oidc, sujeito_oidc)
);
