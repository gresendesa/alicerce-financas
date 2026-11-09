package tools.alicerce.financeiro.aplicacao;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ChaveIdempotencia;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.dominio.CartaoCredito;
import tools.alicerce.financeiro.dominio.ClasseConta;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.Movimento;
import tools.alicerce.financeiro.dominio.OrigemLancamento;
import tools.alicerce.financeiro.dominio.Partidas;
import tools.alicerce.financeiro.dominio.Procedencia;
import tools.alicerce.financeiro.dominio.TipoConta;
import tools.alicerce.financeiro.dominio.TipoLancamento;
import tools.alicerce.financeiro.dominio.TipoMeioPagamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.alicerce.financeiro.dominio.ValorOriginal;

/**
 * Capacidade {@code definir_conta}: cria, altera ou arquiva uma conta, cartão, categoria ou fonte de renda.
 * Criar um cartão cria também o meio de pagamento de crédito dele.
 */
@Component
public class DefinirConta {

    /** O que o assistente pediu; só {@code chaveIdempotencia} é sempre obrigatória. */
    public record Entrada(String chaveIdempotencia, String lote, String ref, String tipo, String nome,
            List<String> aliases, String instituicao, String contaPai, String saldoAbertura, String dataAbertura,
            Boolean arquivar, DadosCartao cartao, String moeda) {

        public Entrada(String chaveIdempotencia, String lote, String ref, String tipo, String nome, List<String> aliases,
                String instituicao, String contaPai, String saldoAbertura, String dataAbertura, Boolean arquivar, DadosCartao cartao) {
            this(chaveIdempotencia, lote, ref, tipo, nome, aliases, instituicao, contaPai, saldoAbertura, dataAbertura, arquivar, cartao, null);
        }

        public Entrada(String chaveIdempotencia, String lote, String ref, String tipo, String nome, List<String> aliases,
                String instituicao, String contaPai, String saldoAbertura, String dataAbertura, Boolean arquivar) {
            this(chaveIdempotencia, lote, ref, tipo, nome, aliases, instituicao, contaPai, saldoAbertura, dataAbertura, arquivar, null, null);
        }
    }

    /** Os dados do cartão; ao alterar, nulo mantém. */
    public record DadosCartao(Integer diaFechamento, Integer diaVencimento, String limite, String contaPagamento) {}

    private static final Clock RELOGIO = Clock.systemUTC();
    private static final String CAPACIDADE = "definir_conta";

    private final RegistradorOperacoes registrador;
    private final RepositorioEstrutura estrutura;
    private final RepositorioLancamentos lancamentos;
    private final RepositorioDono donos;
    private final Referencias referencias;
    private final EstruturaBasica basica;
    private final Enderecos enderecos;
    private final RepositorioCartoes cartoes;
    private final Faturas faturas;

    DefinirConta(RegistradorOperacoes registrador, RepositorioEstrutura estrutura, RepositorioLancamentos lancamentos,
            RepositorioDono donos, Referencias referencias, EstruturaBasica basica, Enderecos enderecos, RepositorioCartoes cartoes,
            Faturas faturas) {
        this.registrador = registrador;
        this.estrutura = estrutura;
        this.lancamentos = lancamentos;
        this.donos = donos;
        this.referencias = referencias;
        this.basica = basica;
        this.enderecos = enderecos;
        this.cartoes = cartoes;
        this.faturas = faturas;
    }

    public ResultadoEscrita<ContaDefinida> executar(Chamador chamador, Entrada e) {
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, new Argumentos().com("ref", e.ref()).com("tipo", e.tipo())
                .com("nome", e.nome()).com("aliases", Argumentos.lista(e.aliases())).com("instituicao", e.instituicao())
                .com("conta_pai", e.contaPai()).com("saldo_abertura", e.saldoAbertura())
                .com("data_abertura", e.dataAbertura()).com("arquivar", e.arquivar()).com("cartao", argumentos(e.cartao())).com("moeda", e.moeda()).mapa(),
                ChaveIdempotencia.ler(e.chaveIdempotencia()), e.lote());
        return registrador.registrar(chamador, pedido, ContaDefinida.class,
                operacao -> Argumentos.vazio(e.ref()) ? criar(operacao, e) : alterar(operacao, e));
    }

    private static Map<String, Object> argumentos(DadosCartao c) {
        return c == null ? null : new Argumentos().com("dia_fechamento", c.diaFechamento()).com("dia_vencimento", c.diaVencimento())
                .com("limite", c.limite()).com("conta_pagamento", c.contaPagamento()).mapa();
    }

    private ContaDefinida criar(OperacaoEmCurso operacao, Entrada e) {
        List<String> faltando = new ArrayList<>();
        if (Argumentos.vazio(e.tipo())) {
            faltando.add("tipo");
        }
        if (Argumentos.vazio(e.nome())) {
            faltando.add("nome");
        }
        if (!faltando.isEmpty()) {
            throw ErroTipado.camposFaltando(faltando, "Pergunte ao dono o nome e o tipo da conta.");
        }
        UUID dono = operacao.dono();
        TipoConta tipo = tipo(e.tipo());
        exigirDadosDoCartao(tipo, e.cartao());
        UUID pai = pai(dono, tipo, e.contaPai(), null);
        Dono dados = donos.buscar(dono).orElseThrow();
        String moeda = Argumentos.vazio(e.moeda()) ? dados.moeda().getCurrencyCode() : moeda(e.moeda(), tipo, dados);
        Conta conta = new Conta(UUID.randomUUID(), tipo.classe(), tipo, e.nome().strip(), aliases(e.aliases()), pai,
                instituicao(operacao, e.instituicao()), moeda, false, 1);
        semNomeRepetido(dono, conta);
        estrutura.inserirConta(operacao, conta, tipo.temSaldo() ? marco(e, dados) : null);
        operacao.entidadeCriada(EntidadeVersionada.CONTA, conta.id(), conta);
        if (tipo == TipoConta.CARTAO_CREDITO) {
            criarCartao(operacao, conta, dados, e.cartao());
        }
        String abertura = saldoAbertura(operacao, conta, dados, e);
        return saida(operacao, conta, abertura);
    }

    private ContaDefinida alterar(OperacaoEmCurso operacao, Entrada e) {
        UUID dono = operacao.dono();
        Conta atual = referencias.contaParaAlterar(dono, e.ref());
        if (e.cartao() != null) {
            alterarCartao(operacao, atual, e.cartao());
        }
        if (!Argumentos.vazio(e.saldoAbertura())) {
            throw ErroTipado.validacao("saldo_abertura",
                    "O saldo de abertura só é informado ao criar a conta. Para acertar o valor, use corrigir_lancamento no lançamento da abertura.");
        }
        if (!Argumentos.vazio(e.tipo()) && !tipo(e.tipo()).equals(atual.tipo())) {
            throw ErroTipado.validacao("tipo", "O tipo de uma conta não muda. Crie uma conta nova.");
        }
        String moeda = moedaNova(dono, atual, e.moeda());
        boolean arquivada = e.arquivar() == null ? atual.arquivada() : e.arquivar();
        if (arquivada && !atual.arquivada()) {
            podeArquivar(dono, atual);
        }
        Conta nova = new Conta(atual.id(), atual.classe(), atual.tipo(),
                Argumentos.vazio(e.nome()) ? atual.nome() : e.nome().strip(),
                e.aliases() == null ? atual.aliases() : aliases(e.aliases()),
                e.contaPai() == null ? atual.contaPai() : pai(dono, atual.tipo(), e.contaPai(), atual.id()),
                e.instituicao() == null ? atual.instituicao() : instituicao(operacao, e.instituicao()),
                moeda, arquivada, atual.versao() + 1);
        semNomeRepetido(dono, nova);
        estrutura.atualizarConta(operacao, nova);
        operacao.entidadeAlterada(EntidadeVersionada.CONTA, nova.id(), atual.versao(), nova);
        return saida(operacao, nova, null);
    }

    /** Só contas de dinheiro têm outra moeda: categoria, cartão e empréstimo seguem a do dono. */
    private static String moeda(String texto, TipoConta tipo, Dono dono) {
        Currency moeda = ValorOriginal.lerMoedaDeConta(texto, "moeda");
        if (!moeda.equals(dono.moeda()) && tipo.classe() != ClasseConta.ATIVO) {
            throw ErroTipado.validacao("moeda", "Só contas de dinheiro (corrente, poupanca, carteira, investimento ou reserva) podem ficar em outra moeda.");
        }
        return moeda.getCurrencyCode();
    }

    /** A moeda fica nos movimentos, e o livro-razão não muda: depois do primeiro, inclusive anulado, ela é fixa. */
    private String moedaNova(UUID dono, Conta atual, String texto) {
        if (Argumentos.vazio(texto)) {
            return atual.moeda();
        }
        String moeda = moeda(texto, atual.tipo(), donos.buscar(dono).orElseThrow());
        if (!moeda.equals(atual.moeda()) && !lancamentos.operacoesComMovimentos(atual.id()).isEmpty()) {
            throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, "moeda", "A moeda da conta '" + atual.nome()
                    + "' não muda, porque ela já tem movimentos. Crie uma conta nova e transfira o saldo.");
        }
        return moeda;
    }

    /** O marco é a data da abertura. Sem ela, hoje: o sistema responde pelo saldo a partir daqui. */
    private static LocalDate marco(Entrada e, Dono dono) {
        LocalDate hoje = Datas.hoje(RELOGIO, dono.fuso());
        LocalDate marco = Datas.lerOuHoje(e.dataAbertura(), "data_abertura", RELOGIO, dono.fuso());
        if (marco.isAfter(hoje)) {
            throw ErroTipado.validacao("data_abertura", "A data de abertura não pode estar no futuro: ela é o marco da conta.");
        }
        return marco;
    }

    private static TipoConta tipo(String codigo) {
        TipoConta tipo = TipoConta.deCodigo(codigo.strip()).orElseThrow(() -> ErroTipado.validacao("tipo",
                "Tipo de conta inválido: use corrente, poupanca, carteira, investimento, reserva, cartao_credito, emprestimo, categoria ou fonte_renda."));
        if (tipo.classe() == ClasseConta.PATRIMONIO) {
            throw ErroTipado.validacao("tipo", "Essa conta é técnica e é criada pelo próprio sistema.");
        }
        return tipo;
    }

    private static void exigirDadosDoCartao(TipoConta tipo, DadosCartao c) {
        if (tipo != TipoConta.CARTAO_CREDITO) {
            if (c != null) {
                throw ErroTipado.validacao("cartao", "Dados de cartão só valem em conta do tipo cartao_credito.");
            }
            return;
        }
        List<String> faltando = new ArrayList<>();
        if (c == null || c.diaFechamento() == null) {
            faltando.add("cartao.dia_fechamento");
        }
        if (c == null || c.diaVencimento() == null) {
            faltando.add("cartao.dia_vencimento");
        }
        if (!faltando.isEmpty()) {
            throw ErroTipado.camposFaltando(faltando, "Pergunte ao dono o dia de fechamento e o dia de vencimento do cartão.");
        }
    }

    private void criarCartao(OperacaoEmCurso operacao, Conta conta, Dono dono, DadosCartao c) {
        CartaoCredito cartao = new CartaoCredito(conta.id(), c.diaFechamento(), c.diaVencimento(), limite(c, dono, null),
                contaPagamento(operacao.dono(), c, null), 1);
        cartoes.inserir(operacao, cartao);
        operacao.entidadeCriada(EntidadeVersionada.CARTAO_CREDITO, conta.id(), cartao);
        String nome = Referencias.normalizar(conta.nome());
        List<MeioPagamento> meios = estrutura.meios(operacao.dono());
        if (meios.stream().anyMatch(m -> Referencias.normalizar(m.nome()).equals(nome))) {
            throw ErroTipado.de(CodigoErro.CONFLITO, "nome", "Já existe um meio de pagamento '" + conta.nome()
                    + "', e o cartão cria um com o mesmo nome. Use outro nome no cartão.");
        }
        boolean primeiro = meios.stream().noneMatch(m -> m.padrao() && m.tipo() == TipoMeioPagamento.CREDITO);
        MeioPagamento meio = new MeioPagamento(UUID.randomUUID(), TipoMeioPagamento.CREDITO, conta.nome(), conta.aliases(), conta.id(),
                primeiro, null, false, 1);
        estrutura.inserirMeio(operacao, meio);
        operacao.entidadeCriada(EntidadeVersionada.MEIO_PAGAMENTO, meio.id(), meio);
    }

    /** Dias novos valem para as faturas que ainda vão nascer: as já criadas não mudam. */
    private void alterarCartao(OperacaoEmCurso operacao, Conta conta, DadosCartao c) {
        if (conta.tipo() != TipoConta.CARTAO_CREDITO) {
            throw ErroTipado.validacao("cartao", "Dados de cartão só valem em conta do tipo cartao_credito.");
        }
        CartaoCredito atual = cartoes.cartao(conta.id()).orElseThrow();
        Dono dono = donos.buscar(operacao.dono()).orElseThrow();
        CartaoCredito novo = new CartaoCredito(atual.conta(),
                c.diaFechamento() == null ? atual.diaFechamento() : c.diaFechamento(),
                c.diaVencimento() == null ? atual.diaVencimento() : c.diaVencimento(),
                limite(c, dono, atual.limiteCentavos()), contaPagamento(operacao.dono(), c, atual.contaPagamento()), atual.versao() + 1);
        cartoes.atualizar(operacao, novo);
        operacao.entidadeAlterada(EntidadeVersionada.CARTAO_CREDITO, novo.conta(), atual.versao(), novo);
    }

    private static Long limite(DadosCartao c, Dono dono, Long atual) {
        return Argumentos.vazio(c.limite()) ? atual : Long.valueOf(Dinheiro.ler(c.limite().strip(), dono.moeda(), "cartao.limite").centavos());
    }

    private UUID contaPagamento(UUID dono, DadosCartao c, UUID atual) {
        return Argumentos.vazio(c.contaPagamento()) ? atual
                : referencias.conta(dono, c.contaPagamento(), "cartao.conta_pagamento", x -> x.classe() == ClasseConta.ATIVO).id();
    }

    /** A categoria-mãe, conferindo que a árvore continua sendo árvore. */
    private UUID pai(UUID dono, TipoConta tipo, String texto, UUID propria) {
        if (Argumentos.vazio(texto)) {
            return null;
        }
        if (tipo != TipoConta.CATEGORIA) {
            throw ErroTipado.validacao("conta_pai", "Só categorias têm conta_pai.");
        }
        Conta pai = referencias.conta(dono, texto, "conta_pai", c -> c.tipo() == TipoConta.CATEGORIA);
        List<Conta> contas = estrutura.contas(dono);
        for (UUID atual = pai.id(); atual != null; ) {
            if (atual.equals(propria)) {
                throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, "conta_pai",
                        "Uma categoria não pode ficar dentro dela mesma.");
            }
            UUID procurada = atual;
            atual = contas.stream().filter(c -> c.id().equals(procurada)).findFirst().map(Conta::contaPai).orElse(null);
        }
        return pai.id();
    }

    private void semNomeRepetido(UUID dono, Conta conta) {
        String nome = Referencias.normalizar(conta.nome());
        boolean repetido = estrutura.contas(dono).stream().anyMatch(c -> !c.id().equals(conta.id())
                && c.classe() == conta.classe() && java.util.Objects.equals(c.contaPai(), conta.contaPai())
                && Referencias.normalizar(c.nome()).equals(nome));
        if (repetido) {
            throw ErroTipado.de(CodigoErro.CONFLITO, "nome", "Já existe uma conta '" + conta.nome() + "' nesse nível.");
        }
    }

    private void podeArquivar(UUID dono, Conta conta) {
        // Categorias acumulam gastos para sempre: o saldo só impede arquivar conta com dinheiro.
        if (conta.tipo().temSaldo() && lancamentos.saldoCentavos(conta.id()) != 0) {
            throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, "arquivar",
                    "A conta '" + conta.nome() + "' tem saldo. Zere o saldo antes de arquivar.");
        }
        boolean emUso = estrutura.meios(dono).stream()
                .anyMatch(m -> !m.arquivado() && m.contaLiquidacao().equals(conta.id()));
        if (emUso) {
            throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, "arquivar",
                    "A conta '" + conta.nome() + "' é usada por um meio de pagamento ativo.");
        }
    }

    private UUID instituicao(OperacaoEmCurso operacao, String nome) {
        if (Argumentos.vazio(nome)) {
            return null;
        }
        Optional<UUID> existente = estrutura.instituicao(operacao.dono(), nome.strip());
        if (existente.isPresent()) {
            return existente.get();
        }
        UUID id = UUID.randomUUID();
        estrutura.inserirInstituicao(operacao, id, nome.strip());
        operacao.entidadeCriada(EntidadeVersionada.INSTITUICAO, id, new Instituicao(id, nome.strip()));
        return id;
    }

    /** O estado guardado na versão de uma instituição. */
    record Instituicao(UUID id, String nome) {}

    private String saldoAbertura(OperacaoEmCurso operacao, Conta conta, Dono dono, Entrada e) {
        if (Argumentos.vazio(e.saldoAbertura())) {
            return null;
        }
        if (!conta.tipo().temSaldo()) {
            throw ErroTipado.validacao("saldo_abertura", "Categorias e fontes de renda não têm saldo de abertura.");
        }
        Dinheiro saldo = Dinheiro.ler(e.saldoAbertura(), Currency.getInstance(conta.moeda()), "saldo_abertura");
        if (saldo.ehZero()) {
            return null;
        }
        LocalDate data = Datas.lerOuHoje(e.dataAbertura(), "data_abertura", RELOGIO, dono.fuso());
        UUID tecnica = basica.contaSaldoDeAbertura(operacao, conta.moeda());
        List<Movimento> movimentos = Partidas.saldoAbertura(saldo, conta, tecnica);
        if (conta.tipo() == TipoConta.CARTAO_CREDITO) {
            UUID fatura = faturas.proximaAVencer(operacao, conta.id(), data).id();
            movimentos = movimentos.stream().map(m -> m.conta().equals(conta.id()) ? m.naFatura(fatura) : m).toList();
        }
        Lancamento lancamento = new Lancamento(UUID.randomUUID(), TipoLancamento.SALDO_ABERTURA, data,
                "Saldo de abertura de " + conta.nome(), null, null, null, OrigemLancamento.CONVERSA, null, movimentos, List.of())
                .comProcedencia(Procedencia.EXOGENO, null);
        lancamentos.inserir(operacao, lancamento);
        operacao.lancamentoCriado(lancamento.id());
        return operacao.referenciar(TipoReferencia.LANCAMENTO, lancamento.id()).id();
    }

    private ContaDefinida saida(OperacaoEmCurso operacao, Conta conta, String abertura) {
        ReferenciaCurta referencia = operacao.referenciar(TipoReferencia.CONTA, conta.id());
        String saldo = conta.tipo().temSaldo()
                ? Dinheiro.deCentavos(lancamentos.saldoCentavos(conta.id()), java.util.Currency.getInstance(conta.moeda())).texto()
                : null;
        return new ContaDefinida(referencia.id(), enderecos.url(referencia), conta.nome(), conta.tipo().codigo(),
                conta.classe().name().toLowerCase(java.util.Locale.ROOT), conta.aliases(),
                conta.contaPai() == null ? null : conta.contaPai().toString(), conta.arquivada(), saldo, conta.moeda(),
                abertura, cartao(operacao, conta), estrutura.marco(conta.id()).map(LocalDate::toString).orElse(null));
    }

    private ContaDefinida.CartaoDefinido cartao(OperacaoEmCurso operacao, Conta conta) {
        if (conta.tipo() != TipoConta.CARTAO_CREDITO) {
            return null;
        }
        CartaoCredito c = cartoes.cartao(conta.id()).orElseThrow();
        java.util.Currency moeda = java.util.Currency.getInstance(conta.moeda());
        String meio = estrutura.meios(operacao.dono()).stream().filter(m -> m.contaLiquidacao().equals(conta.id()) && !m.arquivado())
                .map(MeioPagamento::nome).findFirst().orElse(null);
        return new ContaDefinida.CartaoDefinido(c.diaFechamento(), c.diaVencimento(),
                c.limiteCentavos() == null ? null : Dinheiro.deCentavos(c.limiteCentavos(), moeda).texto(), faturas.disponivel(conta.id(), moeda), meio);
    }

    private static List<String> aliases(List<String> aliases) {
        return aliases == null ? List.of() : aliases.stream().map(String::strip).filter(a -> !a.isEmpty()).distinct().toList();
    }
}
