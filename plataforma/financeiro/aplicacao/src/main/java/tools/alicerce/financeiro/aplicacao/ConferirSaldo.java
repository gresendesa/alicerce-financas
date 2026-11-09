package tools.alicerce.financeiro.aplicacao;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ChaveIdempotencia;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.ConferenciaRegistrada.CompraCandidata;
import tools.alicerce.financeiro.aplicacao.RepositorioCartoes.ItemDaFatura;
import tools.alicerce.financeiro.aplicacao.RepositorioCartoes.Totais;
import tools.alicerce.financeiro.aplicacao.RepositorioConferencias.Conferencia;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.Fatura;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.Movimento;
import tools.alicerce.financeiro.dominio.OrigemLancamento;
import tools.alicerce.financeiro.dominio.PrazoConferencia;
import tools.alicerce.financeiro.dominio.TipoConta;
import tools.alicerce.financeiro.dominio.TipoLancamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * Capacidade {@code conferir_saldo}: compara o saldo que o dono vê no banco com o calculado. Em conta, a
 * diferença fica pendente ou, com {@code ajustar}, vira lançamento contra Ajuste não identificado. Em
 * fatura, só aponta a diferença e as compras candidatas, sem ajuste.
 */
@Component
public class ConferirSaldo {

    public record Entrada(String chaveIdempotencia, String lote, String conta, String fatura, String saldo, String data, String resolucao,
            Boolean simular) {}

    static final String CAPACIDADE = "conferir_saldo";
    private static final String NENHUMA = "nenhuma";
    private static final String AJUSTAR = "ajustar";
    private static final Clock RELOGIO = Clock.systemUTC();

    private final RegistradorOperacoes registrador;
    private final RepositorioDono donos;
    private final RepositorioLancamentos lancamentos;
    private final RepositorioEstrutura estrutura;
    private final RepositorioConferencias conferencias;
    private final RepositorioCartoes cartoes;
    private final Referencias referencias;
    private final Faturas faturas;
    private final EstruturaBasica basica;
    private final RegistroDeLancamento registro;
    private final Compromissos compromissos;
    private final Enderecos enderecos;

    ConferirSaldo(RegistradorOperacoes registrador, RepositorioDono donos, RepositorioLancamentos lancamentos, RepositorioEstrutura estrutura,
            RepositorioConferencias conferencias, RepositorioCartoes cartoes, Referencias referencias, Faturas faturas, EstruturaBasica basica,
            RegistroDeLancamento registro, Compromissos compromissos, Enderecos enderecos) {
        this.registrador = registrador;
        this.donos = donos;
        this.lancamentos = lancamentos;
        this.estrutura = estrutura;
        this.conferencias = conferencias;
        this.cartoes = cartoes;
        this.referencias = referencias;
        this.faturas = faturas;
        this.basica = basica;
        this.registro = registro;
        this.compromissos = compromissos;
        this.enderecos = enderecos;
    }

    public ResultadoEscrita<ConferenciaRegistrada> executar(Chamador chamador, Entrada e) {
        boolean simular = Boolean.TRUE.equals(e.simular());
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, new Argumentos().com("conta", e.conta()).com("fatura", e.fatura())
                .com("saldo", e.saldo()).com("data", e.data()).com("resolucao", e.resolucao()).mapa(),
                simular ? null : ChaveIdempotencia.ler(e.chaveIdempotencia()), e.lote());
        if (simular) {
            ResultadoEscrita<ConferenciaRegistrada> simulado = registrador.simular(chamador, pedido, conferirOuFalhar(e));
            return new ResultadoEscrita<>(simulado.valor().simulada(), null, false);
        }
        return registrador.registrar(chamador, pedido, ConferenciaRegistrada.class, conferirOuFalhar(e));
    }

    private Escrita<ConferenciaRegistrada> conferirOuFalhar(Entrada e) {
        return operacao -> conferir(operacao, e);
    }

    private ConferenciaRegistrada conferir(OperacaoEmCurso operacao, Entrada e) {
        List<String> faltando = new ArrayList<>();
        if (Argumentos.vazio(e.saldo())) {
            faltando.add("saldo");
        }
        if (Argumentos.vazio(e.conta()) && !faturaPorId(e.fatura())) {
            faltando.add("conta");
        }
        if (!faltando.isEmpty()) {
            throw ErroTipado.camposFaltando(faltando, "Pergunte ao dono qual conta ou fatura conferir e quanto o banco mostra.");
        }
        String resolucao = Argumentos.vazio(e.resolucao()) ? NENHUMA : e.resolucao().strip();
        if (!resolucao.equals(NENHUMA) && !resolucao.equals(AJUSTAR)) {
            throw ErroTipado.validacao("resolucao", "Resolução inválida: use nenhuma ou ajustar.");
        }
        UUID dono = operacao.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        LocalDate hoje = Datas.hoje(RELOGIO, dados.fuso());
        LocalDate data = Datas.lerOuHoje(e.data(), "data", RELOGIO, dados.fuso());
        if (data.isAfter(hoje)) {
            throw ErroTipado.validacao("data", "A conferência é do saldo de hoje ou de uma data passada.");
        }
        Conta conta = Argumentos.vazio(e.conta()) ? cartaoDaFatura(dono, e.fatura())
                : referencias.conta(dono, e.conta(), "conta", c -> c.tipo().temSaldo());
        estrutura.marco(conta.id()).filter(data::isBefore).ifPresent(marco -> {
            throw ErroTipado.validacao("data", "A data " + data + " é anterior ao marco de '" + conta.nome() + "' (" + marco
                    + "): antes dele o sistema não responde pelo saldo.");
        });
        boolean cartao = conta.tipo() == TipoConta.CARTAO_CREDITO;
        if (cartao && Argumentos.vazio(e.fatura())) {
            throw ErroTipado.camposFaltando(List.of("fatura"), "Num cartão, a conferência é de uma fatura: pergunte ao dono qual (o mês do vencimento).");
        }
        if (!cartao && !Argumentos.vazio(e.fatura())) {
            throw ErroTipado.validacao("fatura", "Fatura só se confere num cartão; '" + conta.nome() + "' não é cartão.");
        }
        if (cartao && resolucao.equals(AJUSTAR)) {
            throw ErroTipado.validacao("resolucao", "Fatura não tem ajuste automático: ele mexeria em compras, parcelas e pagamentos. "
                    + "Mostre ao dono a diferença e as compras candidatas; ele registra ou corrige o que faltou.");
        }
        Currency moeda = Currency.getInstance(conta.moeda());
        Dinheiro informado = Dinheiro.ler(e.saldo(), moeda, "saldo");
        return cartao ? fatura(operacao, conta, faturas.informada(dono, conta, e.fatura().strip(), "fatura"), informado, data, hoje)
                : conta(operacao, conta, informado, data, hoje, resolucao.equals(AJUSTAR));
    }

    private static boolean faturaPorId(String texto) {
        return !Argumentos.vazio(texto) && ReferenciaCurta.ler(texto, TipoReferencia.MODULO)
                .filter(r -> r.prefixo().equals(TipoReferencia.FATURA.prefixo())).isPresent();
    }

    private Conta cartaoDaFatura(UUID dono, String texto) {
        ReferenciaCurta ref = ReferenciaCurta.ler(texto, TipoReferencia.MODULO).orElseThrow();
        Fatura fatura = estrutura.entidadeDaReferencia(dono, ref.id()).flatMap(id -> cartoes.faturaPorId(dono, id))
                .orElseThrow(() -> ErroTipado.de(tools.alicerce.convencoes.CodigoErro.NAO_ENCONTRADO, "fatura",
                        "Nenhuma fatura corresponde a '" + texto.strip() + "'."));
        return estrutura.contas(dono).stream().filter(c -> c.id().equals(fatura.cartao())).findFirst().orElseThrow();
    }

    private ConferenciaRegistrada conta(OperacaoEmCurso operacao, Conta conta, Dinheiro informado, LocalDate data, LocalDate hoje,
            boolean ajustar) {
        UUID dono = operacao.dono();
        Currency moeda = informado.moeda();
        long calculado = lancamentos.saldosCentavosAte(dono, data).getOrDefault(conta.id(), 0L);
        long diferenca = informado.centavos() - calculado;
        Optional<Conferencia> anterior = conferencias.conferencias(dono).stream()
                .filter(c -> c.conta().equals(conta.id()) && !c.data().isAfter(data)).findFirst();
        ConferenciaRegistrada.LancamentoAjuste ajuste = null;
        UUID ajusteId = null;
        if (ajustar && diferenca != 0) {
            UUID tecnica = basica.contaAjuste(operacao, conta.moeda());
            Dinheiro valor = Dinheiro.deCentavos(diferenca, moeda);
            Lancamento lancamento = new Lancamento(UUID.randomUUID(), TipoLancamento.AJUSTE, data, "Ajuste da conferência de " + conta.nome(),
                    null, "Diferença aceita pelo dono na conferência", null, OrigemLancamento.CONVERSA, null,
                    List.of(new Movimento(conta.id(), valor, null), new Movimento(tecnica, valor.negar(), null)), List.of());
            ReferenciaCurta ref = registro.gravar(operacao, lancamento, null);
            ajusteId = lancamento.id();
            String nomeTecnica = estrutura.contas(dono).stream().filter(c -> c.id().equals(tecnica)).map(Conta::nome).findFirst().orElseThrow();
            ajuste = new ConferenciaRegistrada.LancamentoAjuste(ref.id(), enderecos.url(ref), valor.texto(), nomeTecnica);
        }
        gravar(operacao, conta, null, data, informado.centavos(), calculado, ajusteId);
        PrazoConferencia.Situacao situacao = PrazoConferencia.Situacao.de(diferenca, ajusteId != null);
        List<ItemAgenda> ocorrencias = diferenca == 0 ? List.of() : ocorrenciasCandidatas(dono, conta, anterior, data, hoje);
        return saida(dono, conta, null, data, informado, calculado, situacao, ajuste, anterior, ocorrencias, List.of());
    }

    private ConferenciaRegistrada fatura(OperacaoEmCurso operacao, Conta cartao, Fatura fatura, Dinheiro informado, LocalDate data,
            LocalDate hoje) {
        UUID dono = operacao.dono();
        long calculado = cartoes.totais(cartao.id()).getOrDefault(fatura.id(), new Totais(0, 0)).comprado();
        long diferenca = informado.centavos() - calculado;
        Optional<Conferencia> anterior = conferencias.conferencias(dono).stream().filter(c -> fatura.id().equals(c.fatura())).findFirst();
        gravar(operacao, cartao, fatura.id(), data, informado.centavos(), calculado, null);
        List<CompraCandidata> compras = diferenca == 0 ? List.of() : comprasCandidatas(fatura, diferenca, informado.moeda());
        List<ItemAgenda> ocorrencias = diferenca == 0 ? List.of() : ocorrenciasNoCartao(dono, cartao, fatura, hoje);
        return saida(dono, cartao, fatura, data, informado, calculado, PrazoConferencia.Situacao.de(diferenca, false), null, anterior,
                ocorrencias, compras);
    }

    private void gravar(OperacaoEmCurso operacao, Conta conta, UUID fatura, LocalDate data, long informado, long calculado, UUID ajuste) {
        UUID id = UUID.randomUUID();
        conferencias.inserir(operacao, new Conferencia(id, conta.id(), fatura, data, informado, calculado, conta.moeda(), ajuste, ajuste != null,
                null, null));
        operacao.conferenciaRegistrada(id);
    }

    /** Ocorrências esperadas desde a conferência anterior (ou o marco) até a data, de compromissos desta conta ou sem conta. */
    private List<ItemAgenda> ocorrenciasCandidatas(UUID dono, Conta conta, Optional<Conferencia> anterior, LocalDate data, LocalDate hoje) {
        LocalDate desde = anterior.map(Conferencia::data).orElseGet(() -> estrutura.marco(conta.id()).orElse(LocalDate.MIN));
        Map<UUID, UUID> contaDoMeio = new java.util.HashMap<>();
        estrutura.meios(dono).forEach(m -> contaDoMeio.put(m.id(), m.contaLiquidacao()));
        return compromissos.abertas(dono, hoje, data).stream()
                .filter(a -> a.ocorrencia().vencimento().isAfter(desde) || a.ocorrencia().vencimento().equals(desde))
                .filter(a -> daConta(a, conta.id(), contaDoMeio))
                .map(a -> compromissos.item(dono, a, hoje)).toList();
    }

    private static boolean daConta(Compromissos.Aberta a, UUID conta, Map<UUID, UUID> contaDoMeio) {
        UUID propria = a.compromisso().conta();
        UUID doMeio = a.compromisso().meioPagamento() == null ? null : contaDoMeio.get(a.compromisso().meioPagamento());
        return propria == null && doMeio == null || conta.equals(propria) || conta.equals(doMeio);
    }

    /** No cartão, as ocorrências de compromissos pagos nele que deviam ter entrado nesta fatura. */
    private List<ItemAgenda> ocorrenciasNoCartao(UUID dono, Conta cartao, Fatura fatura, LocalDate hoje) {
        List<UUID> meiosDoCartao = estrutura.meios(dono).stream().filter(m -> m.contaLiquidacao().equals(cartao.id())).map(MeioPagamento::id).toList();
        LocalDate desde = fatura.fechaEm().minusMonths(1);
        return compromissos.abertas(dono, hoje, fatura.fechaEm()).stream()
                .filter(a -> a.compromisso().meioPagamento() != null && meiosDoCartao.contains(a.compromisso().meioPagamento()))
                .filter(a -> a.ocorrencia().vencimento().isAfter(desde))
                .map(a -> compromissos.item(dono, a, hoje)).toList();
    }

    private List<CompraCandidata> comprasCandidatas(Fatura fatura, long diferenca, Currency moeda) {
        List<CompraCandidata> lista = new ArrayList<>();
        for (ItemDaFatura i : cartoes.itens(fatura.id())) {
            String url = ReferenciaCurta.ler(i.codigo(), TipoReferencia.MODULO).map(enderecos::url).orElse(null);
            lista.add(new CompraCandidata(i.codigo(), url, i.data().toString(), i.descricao(), i.parcela(),
                    Dinheiro.deCentavos(i.centavos(), moeda).texto(), Math.abs(i.centavos()) == Math.abs(diferenca)));
        }
        lista.sort(Comparator.comparing((CompraCandidata c) -> !c.igualADiferenca()).thenComparing(CompraCandidata::data, Comparator.reverseOrder()));
        return lista;
    }

    private ConferenciaRegistrada saida(UUID dono, Conta conta, Fatura fatura, LocalDate data, Dinheiro informado, long calculado,
            PrazoConferencia.Situacao situacao, ConferenciaRegistrada.LancamentoAjuste ajuste, Optional<Conferencia> anterior,
            List<ItemAgenda> ocorrencias, List<CompraCandidata> compras) {
        Currency moeda = informado.moeda();
        String contaId = estrutura.referenciasDeConta(dono).get(conta.id());
        ReferenciaCurta refFatura = fatura == null ? null : estrutura.referenciaDe(dono, TipoReferencia.FATURA, fatura.id())
                .flatMap(c -> ReferenciaCurta.ler(c, TipoReferencia.MODULO)).orElse(null);
        ConferenciaRegistrada.Anterior antes = anterior.map(c -> new ConferenciaRegistrada.Anterior(c.data().toString(),
                Conferencias.situacao(c).codigo(), Dinheiro.deCentavos(c.diferencaCentavos(), Currency.getInstance(c.moeda())).texto())).orElse(null);
        return new ConferenciaRegistrada(conta.nome(), contaId, refFatura == null ? null : refFatura.id(),
                refFatura == null ? null : enderecos.url(refFatura), data.toString(), informado.texto(),
                Dinheiro.deCentavos(calculado, moeda).texto(), Dinheiro.deCentavos(informado.centavos() - calculado, moeda).texto(),
                moeda.getCurrencyCode(), situacao.codigo(), ajuste, antes, ocorrencias, compras,
                PrazoConferencia.valeAte(data, null).toString(), proximoPasso(situacao, fatura != null, !ocorrencias.isEmpty()));
    }

    private static String proximoPasso(PrazoConferencia.Situacao situacao, boolean fatura, boolean comOcorrencias) {
        return switch (situacao) {
            case CONFERIDA -> null;
            case AJUSTADA -> "A diferença virou lançamento contra Ajuste não identificado e aparece no resumo como linha própria. "
                    + "Desfazer esta operação estorna o ajuste.";
            case PENDENTE -> fatura
                    ? "Mostre ao dono a diferença e as compras candidatas. Ele registra o que faltou ou corrige o que veio errado e confere de novo."
                    : (comOcorrencias ? "As ocorrências candidatas podem explicar a diferença: pergunte ao dono se foram pagas. " : "")
                            + "Se o dono lembrar do que faltou, registre e confira de novo; se não, repita com resolucao ajustar, depois do sim dele.";
        };
    }
}
