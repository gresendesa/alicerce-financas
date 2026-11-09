# Regras de código

Valem para qualquer pessoa ou agente de IA (Claude, Codex ou outro) que altere `plataforma/`.

## Build e testes

- Tudo roda em contêiner: `plataforma/ci.sh` (padrão `build`; aceita outras tarefas do Gradle). Não instale JDK nem Gradle no host.
- Versões fixadas em `plataforma/gradle/libs.versions.toml`. Spring AI 2.x e o SDK Java do MCP 2.x mudaram muito desde a 1.x: antes de usar qualquer API deles, consulte a documentação ou o metadado da versão fixada; não confie em exemplos da 1.x.
- O build falha com qualquer aviso do compilador (`-Werror`).

## Arquitetura (verificada pelo ArchUnit em todo build)

- `financeiro/dominio` é Java puro: sem Spring, jOOQ ou adaptadores.
- `convencoes` é Java puro e não depende de nenhum outro módulo.
- Adaptadores (`adaptador-mcp`, `adaptador-http`) só chamam `aplicacao`; nunca `persistencia` nem outro adaptador.
- Um módulo de negócio nunca importa outro; o que é comum vive em `convencoes`.
- Pacote base: `tools.alicerce` (ex.: `tools.alicerce.financeiro.dominio`).

## Regras de negócio que o código não pode quebrar

- **Nomes** em português, sem acento nos identificadores (`lancamento`, `movimento`, `ambito`, `meio_pagamento`).
- **Dinheiro:** `BigDecimal` com escala 2 na interface e no domínio, centavos em `long` no banco, nunca `double`.
- **Números saem do domínio**, nunca do adaptador nem do modelo.
- **Livro-razão imutável:** nenhum `UPDATE` ou `DELETE` em `lancamento` e `movimento`.
- **Toda escrita passa pelo registrador de operações** e gera `efeito_operacao`.
- **Interface web:** desfazer e o pedido do link de envio de comprovante ou foto são as únicas escritas, cada uma com escopo próprio (`fin:undo`, `fin:attach`). As ações ficam na casca (`app.js`), nunca no catálogo das vistas.

## Testes

- Testes que precisam de banco ou Keycloak usam Testcontainers, nunca serviços externos.
- `ContextoSpringTest` sobe o contexto inteiro: mantenha-o passando.

## Exemplos de referência

Copie destes arquivos; eles são mantidos passando no build.

- **Tool MCP:** `plataforma/financeiro/adaptador-mcp/.../FerramentasContexto.java`.
  - `@McpTool` com `name` em `snake_case` português, `description` com acento e as `annotations` da classe da capacidade (leitura: `readOnlyHint = true, destructiveHint = false, idempotentHint = true`).
  - `@EscopoExigido("fin:...")` é obrigatório: sem ele a aplicação não sobe.
  - Recebe `McpTransportContext` e obtém o dono por `Chamadores.de(contexto)`; nunca lê token.
  - Chama um caso de uso de `aplicacao` e devolve um record de saída com nomes `snake_case` (`@JsonProperty`).
- **Teste de tool:** `plataforma/financeiro/app/src/test/.../AutorizacaoMcpTest.java`. `@SpringBootTest(webEnvironment = RANDOM_PORT)` com `@Import({PostgresDeTeste.class, KeycloakDeTeste.class})`; tokens por `Tokens.doGui("fin:read")`; chamadas JSON-RPC por HTTP em `/mcp`.
- **Tool de escrita:** `plataforma/financeiro/adaptador-mcp/.../FerramentasFatos.java`. Anotações de registro (`readOnlyHint = false, destructiveHint = false, idempotentHint = true`), parâmetros com `required = false` (a falta volta como `campos_faltando`) e retorno por `Respostas.escrita`. Teste em `CapacidadesEscritaTest` e `CapacidadesMcpTest`.
- **Tela:** nova descrição padrão em `Vistas` (`aplicacao`), com `TelaPadrao`; número só por `ref(...)`. Componente novo muda `CatalogoVista`, `renderizador.js` e os testes. Testes em `InterfaceWebTest` (API e MCP) e `InterfaceNavegadorTest` (navegador real e WCAG AA).
- **Migração Flyway:** `plataforma/financeiro/persistencia/src/main/resources/db/migration/V1__dono.sql`. Nomes de tabela e coluna em português, sem acento; dinheiro em `bigint` de centavos. Depois de criar ou mudar uma migração, rode `./ci.sh :financeiro:persistencia:gerarJooq` e versione as classes geradas em `src/generated/java`: o `check` falha se estiverem desatualizadas.
  - Tabela nova: declare em `db/callback/afterMigrate.sql` o que o `financeiro_app` pode fazer com ela (livro-razão e registros: só `SELECT`/`INSERT`). É ali, e não na migração, porque o backup não leva privilégios.
- **Escrita:** `RegistradorOperacoes.registrar` com uma `Escrita` que declara cada efeito na `OperacaoEmCurso`; o método de persistência que escreve recebe a `OperacaoEmCurso`. Teste em `RegistradorOperacoesTest`, conferindo `EscritasRegistradas.semEfeito`.

## Armadilhas já encontradas

- A página da interface tem CSP sem `unsafe-inline`: nada de `<script>` ou atributo `style` no HTML, e estilo dinâmico só por `elemento.style`.
- O build roda num contêiner e o daemon Docker é o do host: em Testcontainers, use `withCopyFileToContainer`, nunca bind mount de caminho do build.
- Filtros servlet que precisam valer também nas respostas da segurança (401/403) se registram com `FilterRegistrationBean` e ordem explícita; `@Order` no método `@Bean` não basta.
- Tela com prefixo novo (`/fin/x/{codigo}`) entra em `RotasInterface` e em `alicerce.seguranca.rotas-publicas` (`application.yaml`); sem a segunda, a casca volta 401, e só o teste de navegador acusa.
- No Keycloak, clientes têm `fullScopeAllowed: false`: todo papel que precisa chegar ao token exige `scopeMappings` em `realm-alicerce.json`.
