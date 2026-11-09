package tools.alicerce.financeiro.aplicacao;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ChaveIdempotencia;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.dominio.AporteMeta;
import tools.alicerce.financeiro.dominio.CartaoCredito;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.Fatura;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.Meta;
import tools.alicerce.financeiro.dominio.Movimento;
import tools.alicerce.financeiro.dominio.OrigemLancamento;
import tools.alicerce.financeiro.dominio.Partidas;
import tools.alicerce.financeiro.dominio.TipoConta;
import tools.alicerce.financeiro.dominio.TipoLancamento;
import tools.alicerce.financeiro.dominio.ValorOriginal;

/**
 * Capacidade {@code registrar_transferencia}: dinheiro entre contas do dono. Com cartão no destino, é
 * pagamento de fatura. Entre moedas diferentes, é um câmbio, com a cotação guardada. Nenhuma delas conta
 * como entrada ou saída nos relatórios.
 */
@Component
public class RegistrarTransferencia {

    /**
     * @param meta a meta que a transferência aporta ou resgata: id m-, nome ou alias
     * @param resgate só em meta sem conta reservada: verdadeiro se o dinheiro sai da meta. Com conta reservada, o sentido sai
     *     dela: entrar na reservada é aporte, sair é resgate
     */
    public record Entrada(String chaveIdempotencia, String lote, String valor, String de, String ate, String data, String fatura,
            String descricao, String observacao, Boolean confirmarDuplicata, Boolean simular, EntradaMoeda moeda, String meta, Boolean resgate) {

        public Entrada(String chaveIdempotencia, String lote, String valor, String de, String ate, String data, String fatura,
                String descricao, String observacao, Boolean confirmarDuplicata, Boolean simular, EntradaMoeda moeda) {
            this(chaveIdempotencia, lote, valor, de, ate, data, fatura, descricao, observacao, confirmarDuplicata, simular, moeda, null, null);
        }

        public Entrada(String chaveIdempotencia, String lote, String valor, String de, String ate, String data, String fatura,
                String descricao, String observacao, Boolean confirmarDuplicata, Boolean simular) {
            this(chaveIdempotencia, lote, valor, de, ate, data, fatura, descricao, observacao, confirmarDuplicata, simular, null);
        }

        EntradaMoeda moedaOuVazia() {
            return moeda == null ? new EntradaMoeda(null, null, null, null) : moeda;
        }
    }

    private static final Clock RELOGIO = Clock.systemUTC();
    private static final String CAPACIDADE = "registrar_transferencia";

    private final RegistradorOperacoes registrador;
    private final RepositorioDono donos;
    private final RepositorioCartoes cartoes;
    private final Referencias referencias;
    private final RegistroDeLancamento registro;
    private final Faturas faturas;
    private final Enderecos enderecos;
    private final Historico historico;
    private final EstruturaBasica basica;
    private final Metas metas;

    RegistrarTransferencia(RegistradorOperacoes registrador, RepositorioDono donos, RepositorioCartoes cartoes, Referencias referencias,
            RegistroDeLancamento registro, Faturas faturas, Enderecos enderecos, Historico historico, EstruturaBasica basica, Metas metas) {
        this.metas = metas;
        this.registrador = registrador;
        this.donos = donos;
        this.cartoes = cartoes;
        this.referencias = referencias;
        this.registro = registro;
        this.faturas = faturas;
        this.enderecos = enderecos;
        this.historico = historico;
        this.basica = basica;
    }

    public ResultadoEscrita<TransferenciaRegistrada> executar(Chamador chamador, Entrada e) {
        boolean simular = Boolean.TRUE.equals(e.simular());
        Map<String, ?> argumentos = e.moedaOuVazia().em(new Argumentos()).com("valor", e.valor()).com("de", e.de()).com("ate", e.ate()).com("data", e.data())
                .com("fatura", e.fatura()).com("descricao", e.descricao()).com("observacao", e.observacao())
                .com("confirmar_duplicata", e.confirmarDuplicata()).com("meta", e.meta()).com("resgate", e.resgate()).mapa();
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, argumentos, simular ? null : ChaveIdempotencia.ler(e.chaveIdempotencia()), e.lote());
        if (simular) {
            ResultadoEscrita<TransferenciaRegistrada> simulado = registrador.simular(chamador, pedido, o -> gravar(o, e));
            return new ResultadoEscrita<>(simulado.valor().simulado(), null, false);
        }
        return registrador.registrar(chamador, pedido, TransferenciaRegistrada.class, o -> gravar(o, e));
    }

    private TransferenciaRegistrada gravar(OperacaoEmCurso operacao, Entrada e) {
        List<String> faltando = new ArrayList<>();
        if (Argumentos.vazio(e.valor()) && !e.moedaOuVazia().informada()) {
            faltando.add("valor");
        }
        if (Argumentos.vazio(e.ate())) {
            faltando.add("ate");
        }
        if (!faltando.isEmpty()) {
            throw ErroTipado.camposFaltando(faltando, "Pergunte ao dono quanto foi e para qual conta.");
        }
        UUID dono = operacao.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        LocalDate data = Datas.lerOuHoje(e.data(), "data", RELOGIO, dados.fuso());
        Conta destino = referencias.conta(dono, e.ate(), "ate", c -> c.tipo().temSaldo());
        boolean pagamento = destino.tipo() == TipoConta.CARTAO_CREDITO;
        if (!pagamento && !Argumentos.vazio(e.fatura())) {
            throw ErroTipado.validacao("fatura", "Fatura só vale quando o destino é um cartão.");
        }
        Conta origem = origem(dono, e, destino, pagamento);
        historico.exigirDepoisDoMarco(origem, data, "data");
        historico.exigirDepoisDoMarco(destino, data, "data");

        Currency moedaOrigem = Currency.getInstance(origem.moeda());
        Currency moedaDestino = Currency.getInstance(destino.moeda());
        if (!Argumentos.vazio(e.meta())) {
            return gravarAporte(operacao, e, origem, destino, data, pagamento);
        }
        if (e.resgate() != null) {
            throw ErroTipado.validacao("resgate", "resgate só vale com meta.");
        }
        if (!moedaOrigem.equals(moedaDestino)) {
            return gravarCambio(operacao, e, dados, origem, destino, data);
        }
        if (e.moedaOuVazia().informada()) {
            throw ErroTipado.validacao("cotacao", "Cotação e valor_original só valem entre contas de moedas diferentes.");
        }
        Dinheiro valor = RegistroDeLancamento.positivo(e.valor(), moedaOrigem, "valor");
        Fatura fatura = pagamento ? faturas.aPagar(dono, destino, e.fatura(), data) : null;
        if (pagamento) {
            Dinheiro restante = faturas.restante(fatura, moedaOrigem);
            if (valor.compareTo(restante) > 0) {
                throw ErroTipado.validacao("valor", "O valor passa do que falta pagar na fatura " + fatura.periodo() + ": " + restante.texto() + ".");
            }
        }
        String descricao = Argumentos.vazio(e.descricao()) ? padrao(origem, destino, fatura) : e.descricao().strip();
        List<Movimento> movimentos = pagamento ? Partidas.pagamentoFatura(valor, origem.id(), destino.id(), fatura.id())
                : Partidas.transferencia(valor, origem.id(), destino.id());
        Lancamento lancamento = new Lancamento(UUID.randomUUID(), pagamento ? TipoLancamento.PAGAMENTO_FATURA : TipoLancamento.TRANSFERENCIA,
                data, descricao, null, e.observacao(), null, OrigemLancamento.CONVERSA, null, movimentos, List.of());
        registro.semParecido(dono, lancamento, destino.id(), e.confirmarDuplicata());
        ReferenciaCurta referencia = registro.gravar(operacao, lancamento, null);
        return new TransferenciaRegistrada(referencia.id(), enderecos.url(referencia), lancamento.tipo().name().toLowerCase(java.util.Locale.ROOT),
                valor.texto(), valor.moeda().getCurrencyCode(), data.toString(), descricao, origem.nome(), destino.nome(),
                registro.saldos(dono, List.of(origem.id(), destino.id())),
                fatura == null ? null : faturas.ver(dono, fatura).semDetalhes(), null, null);
    }

    /**
     * Aporte ou resgate numa meta: uma transferência simples, na moeda da meta, marcada com ela.
     * O progresso é a soma dos aportes menos os resgates; nada mais é gravado na meta.
     */
    private TransferenciaRegistrada gravarAporte(OperacaoEmCurso operacao, Entrada e, Conta origem, Conta destino, LocalDate data,
            boolean pagamento) {
        UUID dono = operacao.dono();
        if (pagamento) {
            throw ErroTipado.validacao("meta", "Pagamento de fatura não é aporte em meta.");
        }
        if (e.moedaOuVazia().informada()) {
            throw ErroTipado.validacao("cotacao", "Aporte em meta é entre contas da moeda da meta, sem câmbio.");
        }
        Meta meta = metas.resolver(dono, e.meta(), "meta");
        Currency moeda = Currency.getInstance(meta.moeda());
        if (!origem.moeda().equals(meta.moeda()) || !destino.moeda().equals(meta.moeda())) {
            throw ErroTipado.validacao("meta", "Aporte em meta é entre contas na moeda da meta (" + meta.moeda()
                    + "). Faça antes o câmbio para uma conta nessa moeda.");
        }
        boolean resgate = sentido(meta, e, origem, destino);
        if (!resgate && !meta.emAndamento()) {
            throw ErroTipado.validacao("meta", "A meta '" + meta.nome() + "' está " + meta.situacao().codigo()
                    + ": só recebe aporte ativa. Reative com definir_meta (situacao ativa), se o dono quiser.");
        }
        Dinheiro valor = RegistroDeLancamento.positivo(e.valor(), moeda, "valor");
        long progresso = metas.progresso(meta.id(), dono);
        if (resgate && valor.centavos() > progresso) {
            throw ErroTipado.validacao("valor", "O resgate passa do que a meta tem: " + Dinheiro.deCentavos(Math.max(0, progresso), moeda).texto() + ".");
        }
        String descricao = Argumentos.vazio(e.descricao()) ? (resgate ? "Resgate da meta " : "Aporte na meta ") + meta.nome() : e.descricao().strip();
        Lancamento lancamento = new Lancamento(UUID.randomUUID(), TipoLancamento.TRANSFERENCIA, data, descricao, null, e.observacao(), null,
                OrigemLancamento.CONVERSA, null, Partidas.transferencia(valor, origem.id(), destino.id()), List.of())
                .comoAporte(new AporteMeta(meta.id(), resgate));
        registro.semParecido(dono, lancamento, destino.id(), e.confirmarDuplicata());
        ReferenciaCurta referencia = registro.gravar(operacao, lancamento, null);
        MetaVista vista = metas.ver(dono, meta, metas.progresso(meta.id(), dono), metas.hoje(dono));
        TransferenciaRegistrada.MetaMovimentada movimentada = new TransferenciaRegistrada.MetaMovimentada(vista.id(), vista.url(), vista.nome(),
                resgate ? "resgate" : "aporte", vista.progresso(), vista.valorAlvo(), vista.falta(), vista.aporteMensalNecessario());
        return new TransferenciaRegistrada(referencia.id(), enderecos.url(referencia), "aporte_meta", valor.texto(), meta.moeda(), data.toString(),
                descricao, origem.nome(), destino.nome(), registro.saldos(dono, List.of(origem.id(), destino.id())), null, null, movimentada);
    }

    /** Com conta reservada, o sentido sai dela; sem, do parâmetro {@code resgate}, e aporte é o padrão. */
    private static boolean sentido(Meta meta, Entrada e, Conta origem, Conta destino) {
        if (meta.contaReservada() == null) {
            return Boolean.TRUE.equals(e.resgate());
        }
        boolean resgate;
        if (destino.id().equals(meta.contaReservada())) {
            resgate = false;
        } else if (origem.id().equals(meta.contaReservada())) {
            resgate = true;
        } else {
            throw ErroTipado.validacao("ate", "A meta '" + meta.nome() + "' tem conta reservada: o aporte vai para ela, e o resgate sai dela.");
        }
        if (e.resgate() != null && e.resgate() != resgate) {
            throw ErroTipado.validacao("resgate", resgate ? "O dinheiro sai da conta reservada da meta: é um resgate."
                    : "O dinheiro entra na conta reservada da meta: é um aporte.");
        }
        return resgate;
    }

    /** Cada moeda soma zero pela sua conta de conversão, e a cotação fica no lançamento, em {@code original}. */
    private TransferenciaRegistrada gravarCambio(OperacaoEmCurso operacao, Entrada e, Dono dados, Conta origem, Conta destino, LocalDate data) {
        if (destino.tipo() == TipoConta.CARTAO_CREDITO) {
            throw ErroTipado.validacao("de", "A fatura se paga da mesma moeda do cartão: transfira antes para uma conta em " + destino.moeda() + ".");
        }
        String doDono = dados.moeda().getCurrencyCode();
        if (!origem.moeda().equals(doDono) && !destino.moeda().equals(doDono)) {
            throw ErroTipado.validacao("ate", "Um câmbio entre duas moedas estrangeiras não se registra direto: passe por uma conta em " + doDono + ", em duas transferências.");
        }
        boolean saiDoDono = origem.moeda().equals(doDono);
        Currency estrangeira = Currency.getInstance(saiDoDono ? destino.moeda() : origem.moeda());
        ValorOriginal.Conversao conversao = conversao(e, estrangeira, dados.moeda(), data);
        Dinheiro naMoedaDoDono = conversao.valor();
        Dinheiro naEstrangeira = new Dinheiro(conversao.original().valor(), estrangeira);
        Dinheiro naOrigem = saiDoDono ? naMoedaDoDono : naEstrangeira;
        Dinheiro noDestino = saiDoDono ? naEstrangeira : naMoedaDoDono;
        UUID conversaoOrigem = basica.contaConversao(operacao, origem.moeda());
        UUID conversaoDestino = basica.contaConversao(operacao, destino.moeda());
        String descricao = Argumentos.vazio(e.descricao()) ? "Câmbio de " + origem.nome() + " para " + destino.nome() : e.descricao().strip();
        Lancamento lancamento = new Lancamento(UUID.randomUUID(), TipoLancamento.TRANSFERENCIA, data, descricao, null, e.observacao(), null,
                OrigemLancamento.CONVERSA, null, Partidas.cambio(naOrigem, origem.id(), conversaoOrigem, noDestino, conversaoDestino, destino.id()),
                List.of()).comOriginal(conversao.original());
        registro.semParecido(operacao.dono(), lancamento, destino.id(), e.confirmarDuplicata());
        ReferenciaCurta referencia = registro.gravar(operacao, lancamento, null);
        return new TransferenciaRegistrada(referencia.id(), enderecos.url(referencia), "transferencia", naMoedaDoDono.texto(), doDono, data.toString(),
                descricao, origem.nome(), destino.nome(), registro.saldos(operacao.dono(), List.of(origem.id(), destino.id())), null,
                OriginalVisto.de(conversao.original()), null);
    }

    /** Dois dos três bastam: valor na moeda do dono, valor na outra moeda e cotação. O servidor calcula o terceiro. */
    private static ValorOriginal.Conversao conversao(Entrada e, Currency estrangeira, Currency doDono, LocalDate data) {
        EntradaMoeda m = e.moedaOuVazia();
        boolean temValor = !Argumentos.vazio(e.valor());
        boolean temOriginal = !Argumentos.vazio(m.valorOriginal());
        boolean temCotacao = !Argumentos.vazio(m.cotacao());
        if ((temValor ? 1 : 0) + (temOriginal ? 1 : 0) + (temCotacao ? 1 : 0) < 2) {
            List<String> faltando = new ArrayList<>();
            if (!temValor) {
                faltando.add("valor");
            }
            if (!temOriginal) {
                faltando.add("valor_original");
            }
            if (!temCotacao) {
                faltando.add("cotacao");
            }
            throw ErroTipado.camposFaltando(faltando, "Entre moedas diferentes, pergunte ao dono dois destes três: quanto foi em " + doDono.getCurrencyCode()
                    + ", quanto foi em " + estrangeira.getCurrencyCode() + " e a cotação.");
        }
        Dinheiro valor = temValor ? RegistroDeLancamento.positivo(e.valor(), doDono, "valor") : null;
        BigDecimal cotacao = temCotacao ? ValorOriginal.lerCotacao(m.cotacao(), "cotacao") : null;
        LocalDate dataCotacao = Argumentos.vazio(m.dataCotacao()) ? data : Datas.ler(m.dataCotacao().strip(), "data_cotacao");
        if (!temOriginal) {
            return ValorOriginal.daMoedaDoDono(valor, estrangeira, cotacao, dataCotacao, "");
        }
        BigDecimal original = ValorOriginal.lerValor(m.valorOriginal(), estrangeira, "valor_original");
        return ValorOriginal.converter(original, estrangeira, valor, cotacao, dataCotacao, doDono, "");
    }

    /** A informada; ao pagar fatura, sem nenhuma, a conta de pagamento do cartão. */
    private Conta origem(UUID dono, Entrada e, Conta destino, boolean pagamento) {
        String texto = e.de();
        if (Argumentos.vazio(texto) && pagamento) {
            CartaoCredito cartao = cartoes.cartao(destino.id()).orElseThrow();
            texto = cartao.contaPagamento() == null ? null : cartao.contaPagamento().toString();
        }
        if (Argumentos.vazio(texto)) {
            throw ErroTipado.camposFaltando(List.of("de"), "Pergunte ao dono de qual conta saiu.");
        }
        Conta origem = referencias.conta(dono, texto, "de", c -> c.tipo().temSaldo());
        if (origem.tipo() == TipoConta.CARTAO_CREDITO) {
            throw ErroTipado.validacao("de", "Dinheiro não sai de cartão por transferência. Compra no crédito é registrar_gasto.");
        }
        if (origem.id().equals(destino.id())) {
            throw ErroTipado.validacao("ate", "A origem e o destino são a mesma conta.");
        }
        return origem;
    }

    private static String padrao(Conta origem, Conta destino, Fatura fatura) {
        return fatura == null ? "Transferência de " + origem.nome() + " para " + destino.nome()
                : "Pagamento da fatura " + fatura.periodo() + " de " + destino.nome();
    }
}
