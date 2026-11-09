package tools.alicerce.financeiro.aplicacao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ChaveIdempotencia;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.ClasseConta;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.Movimento;
import tools.alicerce.financeiro.dominio.Partida;
import tools.alicerce.financeiro.dominio.Partidas;
import tools.alicerce.financeiro.dominio.Procedencia;
import tools.alicerce.financeiro.dominio.Repartir;
import tools.alicerce.financeiro.dominio.TipoConta;
import tools.alicerce.financeiro.dominio.TipoLancamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.alicerce.financeiro.dominio.ValorOriginal;

/**
 * Capacidade {@code corrigir_lancamento}: sempre por estorno, porque o livro-razão é imutável. Ao
 * corrigir, a nota fiscal e os comprovantes passam para o lançamento novo; ao anular, ela fica livre para outro registro.
 */
@Component
public class CorrigirLancamento {

    /** O que muda no lançamento; nulo mantém. */
    public record Alteracoes(String valor, String descricao, String data, String categoria, String ambito,
            String meioPagamento, String conta, String contraparte, String observacao, EntradaMoeda moeda, Integer parcelas,
            String totalComJuros, Boolean parcelaNaAbertura) {

        public Alteracoes(String valor, String descricao, String data, String categoria, String ambito,
                String meioPagamento, String conta, String contraparte, String observacao, EntradaMoeda moeda, Integer parcelas,
                String totalComJuros) {
            this(valor, descricao, data, categoria, ambito, meioPagamento, conta, contraparte, observacao, moeda, parcelas, totalComJuros, null);
        }

        public Alteracoes(String valor, String descricao, String data, String categoria, String ambito,
                String meioPagamento, String conta, String contraparte, String observacao, EntradaMoeda moeda) {
            this(valor, descricao, data, categoria, ambito, meioPagamento, conta, contraparte, observacao, moeda, null, null, null);
        }

        public Alteracoes(String valor, String descricao, String data, String categoria, String ambito,
                String meioPagamento, String conta, String contraparte, String observacao) {
            this(valor, descricao, data, categoria, ambito, meioPagamento, conta, contraparte, observacao, null, null, null, null);
        }

        EntradaMoeda moedaOuVazia() {
            return moeda == null ? new EntradaMoeda(null, null, null, null) : moeda;
        }

        boolean mudaAlgo() {
            return moedaOuVazia().informada() || parcelas != null || parcelaNaAbertura != null || !Argumentos.vazio(totalComJuros) || !(Argumentos.vazio(valor) && Argumentos.vazio(descricao) && Argumentos.vazio(data)
                    && Argumentos.vazio(categoria) && Argumentos.vazio(ambito) && Argumentos.vazio(meioPagamento)
                    && Argumentos.vazio(conta) && contraparte == null && observacao == null);
        }
    }

    public record Entrada(String chaveIdempotencia, String lote, String lancamento, String acao, Alteracoes alteracoes,
            String motivo, Boolean simular) {}

    private static final String CAPACIDADE = "corrigir_lancamento";
    private static final String CORRIGIR = "corrigir";
    private static final String ANULAR = "anular";

    private final RegistradorOperacoes registrador;
    private final RepositorioLancamentos lancamentos;
    private final RepositorioEstrutura estrutura;
    private final RepositorioDono donos;
    private final Referencias referencias;
    private final RegistroDeLancamento registro;
    private final Enderecos enderecos;
    private final Comprovantes comprovantes;
    private final Compromissos compromissos;
    private final Faturas faturas;
    private final EstruturaBasica basica;
    private final Historico historico;

    CorrigirLancamento(RegistradorOperacoes registrador, RepositorioLancamentos lancamentos,
            RepositorioEstrutura estrutura, RepositorioDono donos, Referencias referencias, RegistroDeLancamento registro,
            Enderecos enderecos, Comprovantes comprovantes, Faturas faturas, EstruturaBasica basica, Historico historico,
            Compromissos compromissos) {
        this.compromissos = compromissos;
        this.registrador = registrador;
        this.lancamentos = lancamentos;
        this.estrutura = estrutura;
        this.donos = donos;
        this.referencias = referencias;
        this.registro = registro;
        this.enderecos = enderecos;
        this.comprovantes = comprovantes;
        this.faturas = faturas;
        this.basica = basica;
        this.historico = historico;
    }

    public ResultadoEscrita<CorrecaoRegistrada> executar(Chamador chamador, Entrada e) {
        boolean simular = Boolean.TRUE.equals(e.simular());
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, argumentos(e),
                simular ? null : ChaveIdempotencia.ler(e.chaveIdempotencia()), e.lote());
        if (simular) {
            ResultadoEscrita<CorrecaoRegistrada> simulado = registrador.simular(chamador, pedido, o -> gravar(o, e));
            return new ResultadoEscrita<>(simulado.valor().simulado(), null, false);
        }
        return registrador.registrar(chamador, pedido, CorrecaoRegistrada.class, o -> gravar(o, e));
    }

    private static Map<String, ?> argumentos(Entrada e) {
        Alteracoes a = e.alteracoes();
        Map<String, Object> alteracoes = a == null ? null : a.moedaOuVazia().em(new Argumentos()).com("valor", a.valor())
                .com("descricao", a.descricao()).com("data", a.data()).com("categoria", a.categoria())
                .com("ambito", a.ambito()).com("meio_pagamento", a.meioPagamento()).com("conta", a.conta())
                .com("contraparte", a.contraparte()).com("observacao", a.observacao()).com("parcelas", a.parcelas())
                .com("total_com_juros", a.totalComJuros()).com("parcela_na_abertura", a.parcelaNaAbertura()).mapa();
        return new Argumentos().com("lancamento", e.lancamento()).com("acao", e.acao()).com("alteracoes", alteracoes)
                .com("motivo", e.motivo()).mapa();
    }

    private CorrecaoRegistrada gravar(OperacaoEmCurso operacao, Entrada e) {
        exigirCampos(e);
        String acao = e.acao().strip();
        if (!acao.equals(CORRIGIR) && !acao.equals(ANULAR)) {
            throw ErroTipado.validacao("acao", "Ação inválida: use corrigir ou anular.");
        }
        UUID dono = operacao.dono();
        UUID id = referencias.lancamento(dono, e.lancamento(), "lancamento");
        Lancamento original = lancamentos.buscar(dono, id).orElseThrow();
        if (original.tipo() == TipoLancamento.ESTORNO) {
            throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, "lancamento",
                    "Um estorno não se corrige. Para voltar atrás, desfaça a operação que o gerou.");
        }
        if (lancamentos.cadeia(dono, id).tamanho() % 2 == 1) {
            throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, "lancamento", "Esse lançamento já foi estornado.");
        }
        Lancamento novo = acao.equals(CORRIGIR) ? corrigido(operacao, original, e.alteracoes()) : null;
        faturas.semSobrepagar(dono, original, novo);
        // Antes do estorno: depois dele o lançamento antigo não paga mais nada.
        var pagos = compromissos.pagamentosDe(id);
        Lancamento estorno = registro.estornar(operacao, id, e.motivo().strip());
        ReferenciaCurta refEstorno = operacao.referenciar(TipoReferencia.LANCAMENTO, estorno.id());
        ReferenciaCurta refOriginal = operacao.referenciar(TipoReferencia.LANCAMENTO, id);
        Set<UUID> contas = new HashSet<>(contas(original));
        ReferenciaCurta refNovo = null;
        if (novo != null) {
            lancamentos.inserir(operacao, novo);
            operacao.lancamentoCriado(novo.id());
            refNovo = operacao.referenciar(TipoReferencia.LANCAMENTO, novo.id());
            contas.addAll(contas(novo));
            UUID idNovo = novo.id();
            lancamentos.chaveDoLancamento(dono, id).ifPresent(chave -> registro.moverChave(operacao, chave, idNovo));
            comprovantes.moverTodos(operacao, id, idNovo);
            compromissos.moverPagamentos(operacao, pagos, idNovo);
        }
        return new CorrecaoRegistrada(acao, refOriginal.id(), refEstorno.id(), enderecos.url(refEstorno),
                refNovo == null ? null : refNovo.id(), refNovo == null ? null : enderecos.url(refNovo),
                novo == null ? List.of() : movimentos(dono, novo), registro.saldos(dono, contas),
                novo == null ? null : OriginalVisto.de(novo.original()), faturasTocadas(dono, original, novo),
                novo == null ? null : novo.procedencia().codigo());
    }

    private List<FaturaVista> faturasTocadas(UUID dono, Lancamento original, Lancamento novo) {
        List<UUID> ids = new ArrayList<>(original.movimentos().stream().map(Movimento::fatura).filter(Objects::nonNull).toList());
        if (novo != null) {
            ids.addAll(novo.movimentos().stream().map(Movimento::fatura).filter(Objects::nonNull).toList());
        }
        return faturas.afetadas(dono, ids);
    }

    private static Collection<UUID> contas(Lancamento l) {
        return l.movimentos().stream().map(Movimento::conta).toList();
    }

    private List<LancamentoVisto.MovimentoVisto> movimentos(UUID dono, Lancamento l) {
        Map<UUID, String> contas = estrutura.contas(dono).stream().collect(Collectors.toMap(Conta::id, Conta::nome));
        Map<UUID, String> ambitos = estrutura.ambitos(dono).stream().collect(Collectors.toMap(Ambito::id, Ambito::nome));
        Map<UUID, String> codigos = estrutura.referenciasDeFatura(dono);
        return l.movimentos().stream().map(m -> new LancamentoVisto.MovimentoVisto(contas.get(m.conta()), m.valor().texto(),
                m.ambito() == null ? null : ambitos.get(m.ambito()), m.fatura() == null ? null : codigos.get(m.fatura()), m.parcelaTexto(),
                m.faturaAbertura() == null ? null : codigos.get(m.faturaAbertura()), m.valor().moeda().getCurrencyCode())).toList();
    }

    private Lancamento corrigido(OperacaoEmCurso operacao, Lancamento o, Alteracoes a) {
        UUID dono = operacao.dono();
        if (o.tipo() == TipoLancamento.PAGAMENTO_FATURA) {
            throw ErroTipado.validacao("acao", "Pagamento de fatura não se corrige: anule e registre de novo, para conferir a fatura.");
        }
        if (o.tipo() == TipoLancamento.APORTE_META) {
            throw ErroTipado.validacao("acao", "Aporte em meta não se corrige: anule e registre de novo, para o progresso da meta conferir.");
        }
        Dono dados = donos.buscar(dono).orElseThrow();
        Map<UUID, Conta> contas = estrutura.contas(dono).stream().collect(Collectors.toMap(Conta::id, Function.identity()));
        boolean comItens = !o.itens().isEmpty();
        boolean compra = o.tipo() == TipoLancamento.GASTO;
        // Numa compra, o lado do dinheiro é refeito no fim, para valer no cartão com parcelas e faturas.
        List<Movimento> movimentos = compra ? o.movimentos().stream().filter(m -> contas.get(m.conta()).tipo().ehResultado()).toList()
                : o.movimentos();
        UUID meio = o.meioPagamento();

        LocalDate data = Argumentos.vazio(a.data()) ? o.data() : Datas.ler(a.data().strip(), "alteracoes.data");
        if (!data.equals(o.data())) {
            semMudarDataForaDoMarco(o, contas, data);
        }
        Dinheiro base = o.precoAVista() == null ? o.total() : o.precoAVista();
        UUID contaNova = null;
        UUID contaFinal = null;
        // A moeda do próprio lançamento: transferência e abertura de uma conta em outra moeda ficam nela.
        Dinheiro valor = Argumentos.vazio(a.valor()) ? null
                : RegistroDeLancamento.positivo(a.valor().strip(), base.moeda(), "alteracoes.valor");
        ValorOriginal original = o.original();
        EntradaMoeda moeda = a.moedaOuVazia();
        if (moeda.informada() || (original != null && valor != null)) {
            ValorOriginal.Conversao conversao = conversao(o, base, moeda, valor, data, dados.moeda());
            original = conversao.original();
            valor = conversao.valor();
        } else if (original != null && !o.data().equals(data) && original.dataCotacao().equals(o.data())) {
            original = original.comDataCotacao(data);
        }
        Dinheiro novoValor = valor == null ? base : valor;
        if (novoValor.compareTo(base) != 0) {
            semItens(comItens, "valor");
        }
        int parcelas = o.parcelas();
        if (a.parcelas() != null) {
            semMudarSeNaoForCompra(compra, "parcelas");
            Repartir.exigirQuantidade(a.parcelas(), "alteracoes.parcelas");
            parcelas = a.parcelas();
        }
        Dinheiro[] valores = totais(o, a, novoValor, parcelas);
        Dinheiro total = valores[0];
        if (o.ehCambio()) {
            movimentos = Partidas.comCambio(movimentos, total, new Dinheiro(original.valor(), original.moeda()));
        } else if (total.compareTo(o.total()) != 0) {
            semItens(comItens, "valor");
            movimentos = compra ? movimentos.stream().map(m -> m.comValor(total)).toList() : Partidas.comValor(movimentos, total);
        }
        if (!Argumentos.vazio(a.categoria())) {
            semItens(comItens, "categoria");
            UUID categoria = categoria(dono, o, a.categoria());
            movimentos = trocarConta(movimentos, contas, c -> c.tipo().ehResultado(), categoria);
        }
        if (!Argumentos.vazio(a.ambito())) {
            semItens(comItens, "ambito");
            if (o.tipo() != TipoLancamento.GASTO && o.tipo() != TipoLancamento.GANHO) {
                throw ErroTipado.validacao("alteracoes.ambito", "Saldo de abertura e transferência não têm âmbito.");
            }
            UUID ambito = referencias.ambito(dono, a.ambito(), "alteracoes.ambito").id();
            movimentos = movimentos.stream().map(m -> m.comAmbito(ambito)).toList();
        }
        UUID contaAtual = compra || o.tipo() == TipoLancamento.GANHO ? contaDoFato(o, contas) : null;
        UUID liquidacao = compra ? contaAtual : null;
        if (!Argumentos.vazio(a.meioPagamento())) {
            if (!compra) {
                throw ErroTipado.validacao("alteracoes.meio_pagamento", "Só gastos têm meio de pagamento.");
            }
            MeioPagamento novoMeio = referencias.meio(dono, a.meioPagamento(), "alteracoes.meio_pagamento");
            meio = novoMeio.id();
            liquidacao = novoMeio.contaLiquidacao();
        }
        if (!Argumentos.vazio(a.conta())) {
            if (o.tipo() != TipoLancamento.GANHO) {
                throw ErroTipado.validacao("alteracoes.conta", "A conta só se troca em ganhos; em gastos, troque o meio de pagamento.");
            }
            Conta nova = referencias.conta(dono, a.conta(), "alteracoes.conta", c -> c.classe() == ClasseConta.ATIVO);
            RegistroDeLancamento.exigirMoedaDoDono(nova, dados.moeda(), "alteracoes.conta");
            contaNova = nova.id();
        }
        Procedencia procedencia = o.procedencia();
        if (compra) {
            Conta noFim = contas.get(liquidacao);
            RegistroDeLancamento.exigirMoedaDoDono(noFim, dados.moeda(), "alteracoes.meio_pagamento");
            if (parcelas > 1 && noFim.tipo() != TipoConta.CARTAO_CREDITO) {
                throw ErroTipado.validacao("alteracoes.parcelas", "Parcelas só valem no crédito. Informe parcelas: 1 para sair do cartão.");
            }
            List<Partida> partidas = movimentos.stream().map(m -> new Partida(m.conta(), m.ambito(), m.valor())).toList();
            procedencia = procedencia(o, contaAtual, liquidacao, data);
            Boolean resposta = a.parcelaNaAbertura() != null || noFim.tipo() != TipoConta.CARTAO_CREDITO ? a.parcelaNaAbertura()
                    : faturas.respostaAnterior(o, noFim.id());
            movimentos = faturas.movimentosDoGasto(operacao, new Faturas.Compra(noFim, data, total, partidas, parcelas, procedencia, resposta, o,
                    "alteracoes.parcela_na_abertura"));
        }
        if (o.tipo() == TipoLancamento.GANHO) {
            contaFinal = contaNova == null ? contaAtual : contaNova;
            procedencia = procedencia(o, contaAtual, contaFinal, data);
            UUID entrada = procedencia == Procedencia.EXOGENO ? basica.contaSaldoDeAbertura(operacao, dados.moeda().getCurrencyCode()) : contaFinal;
            Movimento fonte = movimentos.stream().filter(m -> contas.get(m.conta()).tipo().ehResultado()).findFirst().orElseThrow();
            movimentos = Partidas.ganho(total, entrada, fonte.conta(), fonte.ambito());
        }
        UUID contaDoFato = compra ? liquidacao : contaFinal;
        return new Lancamento(UUID.randomUUID(), o.tipo(), data,
                Argumentos.vazio(a.descricao()) ? o.descricao() : a.descricao().strip(),
                a.contraparte() == null ? o.contraparte() : a.contraparte(),
                a.observacao() == null ? o.observacao() : a.observacao(), meio, o.origem(), o.cnpjEstabelecimento(),
                movimentos, o.itens()).comOriginal(original).comPrecoAVista(valores[1])
                .comProcedencia(procedencia, procedencia == Procedencia.EXOGENO ? contaDoFato : null);
    }

    /** A conta a que o gasto ou ganho pertence: a gravada, num fato exógeno, ou a que ele movimentou. */
    private static UUID contaDoFato(Lancamento o, Map<UUID, Conta> contas) {
        return o.contaHistorica() != null ? o.contaHistorica()
                : o.movimentos().stream().map(Movimento::conta).filter(c -> contas.get(c).tipo().temSaldo()).findFirst().orElseThrow();
    }

    /**
     * Com a mesma data e a mesma conta, a procedência fica: corrigir a descrição de um gasto antigo
     * anterior ao marco não pode mudar saldo nenhum sem o dono pedir.
     */
    private Procedencia procedencia(Lancamento o, UUID contaAtual, UUID contaNova, LocalDate data) {
        return data.equals(o.data()) && contaAtual.equals(contaNova) ? o.procedencia() : historico.procedencia(contaNova, data);
    }

    private void semMudarDataForaDoMarco(Lancamento o, Map<UUID, Conta> contas, LocalDate data) {
        if (o.tipo() == TipoLancamento.SALDO_ABERTURA) {
            throw ErroTipado.validacao("alteracoes.data", "A data da abertura é o marco da conta e não muda. Para outro marco, é preciso esperar o recuo do marco.");
        }
        if (o.tipo() == TipoLancamento.TRANSFERENCIA) {
            o.movimentos().forEach(m -> historico.exigirDepoisDoMarco(contas.get(m.conta()), data, "alteracoes.data"));
        }
    }

    /**
     * O total e o preço à vista depois da correção. Numa compra com juros, {@code valor} é o preço à
     * vista, e o total só muda por {@code total_com_juros}.
     */
    private static Dinheiro[] totais(Lancamento o, Alteracoes a, Dinheiro valor, int parcelas) {
        if (!Argumentos.vazio(a.totalComJuros())) {
            semMudarSeNaoForCompra(o.tipo() == TipoLancamento.GASTO, "total_com_juros");
            return RegistroDeLancamento.comJuros(valor, a.totalComJuros(), parcelas, "alteracoes.total_com_juros");
        }
        if (o.precoAVista() == null) {
            return new Dinheiro[] {valor, null};
        }
        return RegistroDeLancamento.comJuros(valor, o.total().texto(), parcelas, "alteracoes.parcelas");
    }

    private static void semMudarSeNaoForCompra(boolean compra, String campo) {
        if (!compra) {
            throw ErroTipado.validacao("alteracoes." + campo, "Só gastos têm parcelas.");
        }
    }

    /**
     * Junta o que o lançamento tem com o que mudou. O que vier informado manda. Sem valor nem cotação:
     * num câmbio, o valor fica e a cotação passa a ser a implícita; num gasto ou ganho na mesma moeda,
     * fica a cotação, e o valor só muda se o original mudou; em moeda nova, o valor fica e a cotação
     * passa a ser a implícita.
     */
    private static ValorOriginal.Conversao conversao(Lancamento o, Dinheiro base, EntradaMoeda m, Dinheiro valorNovo, LocalDate data,
            Currency moedaDaConta) {
        if (o.tipo() != TipoLancamento.GASTO && o.tipo() != TipoLancamento.GANHO && !o.ehCambio()) {
            throw ErroTipado.validacao("alteracoes.moeda_original", "Só gastos, ganhos e câmbios têm moeda original.");
        }
        ValorOriginal atual = o.original();
        if (Argumentos.vazio(m.moedaOriginal()) && atual == null) {
            throw ErroTipado.camposFaltando(List.of("alteracoes.moeda_original"), "Pergunte ao dono em que moeda foi.");
        }
        Currency moeda = Argumentos.vazio(m.moedaOriginal()) ? atual.moeda()
                : ValorOriginal.lerMoeda(m.moedaOriginal(), moedaDaConta, "alteracoes.moeda_original");
        if (o.ehCambio() && !moeda.equals(atual.moeda())) {
            throw ErroTipado.validacao("alteracoes.moeda_original", "Num câmbio, a moeda é a da conta e não muda. Anule e registre de novo com a conta certa.");
        }
        if (Argumentos.vazio(m.valorOriginal()) && atual == null) {
            throw ErroTipado.camposFaltando(List.of("alteracoes.valor_original"), "Pergunte ao dono quanto foi na moeda original.");
        }
        BigDecimal valor = Argumentos.vazio(m.valorOriginal()) ? atual.valor()
                : ValorOriginal.lerValor(m.valorOriginal(), moeda, "alteracoes.valor_original");
        BigDecimal cotacao = Argumentos.vazio(m.cotacao()) ? null : ValorOriginal.lerCotacao(m.cotacao(), "alteracoes.cotacao");
        Dinheiro informado = valorNovo;
        if (informado == null && cotacao == null && o.ehCambio()) {
            // Num câmbio, os dois lados são o que o extrato mostra: corrigir um mantém o outro.
            informado = base;
        } else if (informado == null && cotacao == null) {
            boolean mesmaMoeda = atual != null && atual.moeda().equals(moeda);
            cotacao = mesmaMoeda ? atual.cotacao() : null;
            informado = mesmaMoeda && valor.compareTo(atual.valor()) != 0 ? null : base;
        }
        return ValorOriginal.converter(valor, moeda, informado, cotacao, dataCotacao(o, m, data), moedaDaConta, "alteracoes.");
    }

    /** A data informada; sem ela, a atual, que acompanha o lançamento se era a data dele. */
    private static LocalDate dataCotacao(Lancamento o, EntradaMoeda m, LocalDate data) {
        if (!Argumentos.vazio(m.dataCotacao())) {
            return Datas.ler(m.dataCotacao().strip(), "alteracoes.data_cotacao");
        }
        ValorOriginal atual = o.original();
        return atual == null || atual.dataCotacao().equals(o.data()) ? data : atual.dataCotacao();
    }

    private UUID categoria(UUID dono, Lancamento o, String texto) {
        return switch (o.tipo()) {
            case GASTO -> referencias.conta(dono, texto, "alteracoes.categoria", c -> c.tipo() == TipoConta.CATEGORIA).id();
            case GANHO -> referencias.conta(dono, texto, "alteracoes.categoria", c -> c.classe() == ClasseConta.RECEITA).id();
            default -> throw ErroTipado.validacao("alteracoes.categoria", "Esse lançamento não tem categoria.");
        };
    }

    private static List<Movimento> trocarConta(List<Movimento> movimentos, Map<UUID, Conta> contas, Predicate<Conta> lado, UUID nova) {
        List<Movimento> trocados = new ArrayList<>();
        for (Movimento m : movimentos) {
            trocados.add(lado.test(contas.get(m.conta())) ? new Movimento(nova, m.valor(), m.ambito(), m.fatura(), m.parcela(), m.totalParcelas()) : m);
        }
        return trocados;
    }

    private static void semItens(boolean comItens, String campo) {
        if (comItens) {
            throw ErroTipado.validacao("alteracoes." + campo,
                    "Esse lançamento tem itens. Para mudar valor, categoria ou âmbito, anule e registre de novo com os itens certos.");
        }
    }

    private static void exigirCampos(Entrada e) {
        List<String> faltando = new ArrayList<>();
        if (Argumentos.vazio(e.lancamento())) {
            faltando.add("lancamento");
        }
        if (Argumentos.vazio(e.acao())) {
            faltando.add("acao");
        }
        if (Argumentos.vazio(e.motivo())) {
            faltando.add("motivo");
        }
        if (CORRIGIR.equals(e.acao()) && (e.alteracoes() == null || !e.alteracoes().mudaAlgo())) {
            faltando.add("alteracoes");
        }
        if (!faltando.isEmpty()) {
            throw ErroTipado.camposFaltando(faltando, "Pergunte ao dono qual lançamento, o que muda e por quê.");
        }
    }
}
