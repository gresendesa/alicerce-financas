package tools.alicerce.financeiro.aplicacao;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.DirecaoCompromisso;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.ItemLancamento;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.Movimento;
import tools.alicerce.financeiro.dominio.OrigemLancamento;
import tools.alicerce.financeiro.dominio.Partida;
import tools.alicerce.financeiro.dominio.Procedencia;
import tools.alicerce.financeiro.dominio.Repartir;
import tools.alicerce.financeiro.dominio.TipoConta;
import tools.alicerce.financeiro.dominio.TipoLancamento;
import tools.alicerce.financeiro.dominio.ValorOriginal;

/** Capacidade {@code registrar_gasto}: à vista ou no crédito, com parcelas uma por fatura. Antes do marco da conta, sem mexer nela. */
@Component
public class RegistrarGasto {

    public record Item(String descricao, String quantidade, String valor, String categoria, String ambito) {}

    public record Entrada(String chaveIdempotencia, String lote, String valor, String descricao, String meioPagamento,
            String origem, String data, String categoria, String ambito, List<Item> itens, String cnpjEstabelecimento,
            String chaveNfce, String contraparte, String observacao, Boolean confirmarDuplicata, Boolean simular,
            EntradaMoeda moeda, Integer parcelas, String totalComJuros, String valorParcela, Integer parcelaAtual,
            Boolean parcelaNaAbertura, String compromisso, String ocorrencia) {

        public Entrada(String chaveIdempotencia, String lote, String valor, String descricao, String meioPagamento,
                String origem, String data, String categoria, String ambito, List<Item> itens, String cnpjEstabelecimento,
                String chaveNfce, String contraparte, String observacao, Boolean confirmarDuplicata, Boolean simular,
                EntradaMoeda moeda, Integer parcelas, String totalComJuros, String valorParcela, Integer parcelaAtual,
                Boolean parcelaNaAbertura) {
            this(chaveIdempotencia, lote, valor, descricao, meioPagamento, origem, data, categoria, ambito, itens,
                    cnpjEstabelecimento, chaveNfce, contraparte, observacao, confirmarDuplicata, simular, moeda, parcelas, totalComJuros,
                    valorParcela, parcelaAtual, parcelaNaAbertura, null, null);
        }

        public Entrada(String chaveIdempotencia, String lote, String valor, String descricao, String meioPagamento,
                String origem, String data, String categoria, String ambito, List<Item> itens, String cnpjEstabelecimento,
                String chaveNfce, String contraparte, String observacao, Boolean confirmarDuplicata, Boolean simular,
                EntradaMoeda moeda, Integer parcelas, String totalComJuros) {
            this(chaveIdempotencia, lote, valor, descricao, meioPagamento, origem, data, categoria, ambito, itens,
                    cnpjEstabelecimento, chaveNfce, contraparte, observacao, confirmarDuplicata, simular, moeda, parcelas, totalComJuros,
                    null, null, null);
        }

        public Entrada(String chaveIdempotencia, String lote, String valor, String descricao, String meioPagamento,
                String origem, String data, String categoria, String ambito, List<Item> itens, String cnpjEstabelecimento,
                String chaveNfce, String contraparte, String observacao, Boolean confirmarDuplicata, Boolean simular,
                EntradaMoeda moeda) {
            this(chaveIdempotencia, lote, valor, descricao, meioPagamento, origem, data, categoria, ambito, itens,
                    cnpjEstabelecimento, chaveNfce, contraparte, observacao, confirmarDuplicata, simular, moeda, null, null);
        }

        public Entrada(String chaveIdempotencia, String lote, String valor, String descricao, String meioPagamento,
                String origem, String data, String categoria, String ambito, List<Item> itens, String cnpjEstabelecimento,
                String chaveNfce, String contraparte, String observacao, Boolean confirmarDuplicata, Boolean simular) {
            this(chaveIdempotencia, lote, valor, descricao, meioPagamento, origem, data, categoria, ambito, itens,
                    cnpjEstabelecimento, chaveNfce, contraparte, observacao, confirmarDuplicata, simular, null, null, null);
        }

        EntradaMoeda moedaOuVazia() {
            return moeda == null ? new EntradaMoeda(null, null, null, null) : moeda;
        }
    }

    private static final Clock RELOGIO = Clock.systemUTC();
    private static final String CAPACIDADE = "registrar_gasto";
    private static final Map<String, String> PERGUNTAS = Map.of(
            "valor", "quanto foi",
            "descricao", "o que foi comprado",
            "meio_pagamento", "como foi pago",
            "origem", "de onde veio a informação",
            "categoria", "qual é a categoria",
            "valor_original", "quanto foi na moeda original",
            "moeda_original", "em que moeda foi",
            "parcelas", "em quantas parcelas foi");

    private final RegistradorOperacoes registrador;
    private final RepositorioDono donos;
    private final Referencias referencias;
    private final EstruturaBasica basica;
    private final RegistroDeLancamento registro;
    private final Comprovantes comprovantes;
    private final Faturas faturas;
    private final Historico historico;
    private final Compromissos compromissos;

    RegistrarGasto(RegistradorOperacoes registrador, RepositorioDono donos, Referencias referencias,
            EstruturaBasica basica, RegistroDeLancamento registro, Comprovantes comprovantes, Faturas faturas, Historico historico,
            Compromissos compromissos) {
        this.compromissos = compromissos;
        this.registrador = registrador;
        this.donos = donos;
        this.referencias = referencias;
        this.basica = basica;
        this.registro = registro;
        this.comprovantes = comprovantes;
        this.faturas = faturas;
        this.historico = historico;
    }

    public ResultadoEscrita<LancamentoRegistrado> executar(Chamador chamador, Entrada e) {
        boolean simular = Boolean.TRUE.equals(e.simular());
        String token = comprovantes.sortearToken();
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, argumentos(e),
                simular ? null : ChaveIdempotencia.ler(e.chaveIdempotencia()), e.lote());
        if (simular) {
            ResultadoEscrita<LancamentoRegistrado> simulado = registrador.simular(chamador, pedido, o -> gravar(o, e, token));
            return new ResultadoEscrita<>(simulado.valor().simulado(), null, false);
        }
        return comprovantes.comLink(registrador.registrar(chamador, pedido, LancamentoRegistrado.class, o -> gravar(o, e, token)), token);
    }

    private static Map<String, ?> argumentos(Entrada e) {
        List<Object> itens = null;
        if (e.itens() != null) {
            itens = new ArrayList<>();
            for (Item item : e.itens()) {
                itens.add(new Argumentos().com("descricao", item.descricao()).com("quantidade", item.quantidade())
                        .com("valor", item.valor()).com("categoria", item.categoria()).com("ambito", item.ambito()).mapa());
            }
        }
        return e.moedaOuVazia().em(new Argumentos()).com("valor", e.valor()).com("descricao", e.descricao())
                .com("meio_pagamento", e.meioPagamento()).com("origem", e.origem()).com("data", e.data())
                .com("categoria", e.categoria()).com("ambito", e.ambito()).com("itens", itens)
                .com("cnpj_estabelecimento", e.cnpjEstabelecimento()).com("chave_nfce", e.chaveNfce())
                .com("contraparte", e.contraparte()).com("observacao", e.observacao())
                .com("confirmar_duplicata", e.confirmarDuplicata()).com("parcelas", e.parcelas())
                .com("total_com_juros", e.totalComJuros()).com("valor_parcela", e.valorParcela()).com("parcela_atual", e.parcelaAtual())
                .com("parcela_na_abertura", e.parcelaNaAbertura()).com("compromisso", e.compromisso()).com("ocorrencia", e.ocorrencia()).mapa();
    }

    private LancamentoRegistrado gravar(OperacaoEmCurso operacao, Entrada e, String token) {
        List<Item> itens = e.itens() == null ? List.of() : e.itens();
        exigirCampos(e, itens);
        UUID dono = operacao.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        var data = Datas.lerOuHoje(e.data(), "data", RELOGIO, dados.fuso());
        ValorOriginal.Conversao conversao = RegistroDeLancamento.valores(valorDaCompra(e, dados), e.moeda(), dados.moeda(), data);
        MeioPagamento meio = referencias.meio(dono, e.meioPagamento(), "meio_pagamento");
        Conta liquidacao = referencias.conta(dono, meio.contaLiquidacao().toString(), "meio_pagamento", c -> true);
        RegistroDeLancamento.exigirMoedaDoDono(liquidacao, dados.moeda(), "meio_pagamento");
        int parcelas = parcelas(e.parcelas(), liquidacao, "parcelas");
        faturas.conferirParcelaAtual(dono, liquidacao, data, parcelas, e.parcelaAtual());
        Dinheiro[] valores = RegistroDeLancamento.comJuros(conversao.valor(), e.totalComJuros(), parcelas, "total_com_juros");
        Dinheiro total = valores[0];
        var origem = RegistroDeLancamento.origem(e.origem());
        String chave = RegistroDeLancamento.chaveNfce(e.chaveNfce());
        String cnpj = RegistroDeLancamento.cnpj(e.cnpjEstabelecimento());
        registro.semNotaRepetida(dono, chave);

        boolean itensComAmbito = !itens.isEmpty() && itens.stream().noneMatch(i -> Argumentos.vazio(i.ambito()));
        boolean ambitoInferido = Argumentos.vazio(e.ambito()) && !itensComAmbito;
        UUID ambitoGeral = itensComAmbito && Argumentos.vazio(e.ambito()) ? null : ambitoGeral(operacao, e, meio);

        List<Partida> partidas = new ArrayList<>();
        List<ItemLancamento> linhas = new ArrayList<>();
        if (itens.isEmpty()) {
            partidas.add(new Partida(categoria(dono, e.categoria(), "categoria"), ambitoGeral, total));
        }
        for (int i = 0; i < itens.size(); i++) {
            Item item = itens.get(i);
            String campo = "itens[" + i + "]";
            String textoCategoria = Argumentos.vazio(item.categoria()) ? e.categoria() : item.categoria();
            UUID categoria = categoria(dono, textoCategoria, campo + ".categoria");
            UUID ambito = Argumentos.vazio(item.ambito()) ? ambitoGeral
                    : referencias.ambito(dono, item.ambito(), campo + ".ambito").id();
            Dinheiro valor = RegistroDeLancamento.positivo(item.valor(), dados.moeda(), campo + ".valor");
            partidas.add(new Partida(categoria, ambito, valor));
            linhas.add(new ItemLancamento(item.descricao().strip(), quantidade(item.quantidade(), campo), categoria, ambito, valor));
        }
        Procedencia procedencia = historico.procedencia(liquidacao.id(), data);
        List<Movimento> movimentos = faturas.movimentosDoGasto(operacao, new Faturas.Compra(liquidacao, data, total, partidas, parcelas,
                procedencia, e.parcelaNaAbertura(), null, "parcela_na_abertura"));
        Lancamento lancamento = new Lancamento(UUID.randomUUID(), TipoLancamento.GASTO, data, e.descricao().strip(),
                e.contraparte(), e.observacao(), meio.id(), origem, cnpj, movimentos, linhas).comOriginal(conversao.original()).comPrecoAVista(valores[1])
                .comProcedencia(procedencia, procedencia == Procedencia.EXOGENO ? liquidacao.id() : null);
        boolean tocouConta = movimentos.stream().anyMatch(m -> m.conta().equals(liquidacao.id()));
        registro.semParecido(dono, lancamento, tocouConta ? liquidacao.id() : movimentos.get(movimentos.size() - 1).conta(), e.confirmarDuplicata());
        ReferenciaCurta referencia = registro.gravar(operacao, lancamento, chave);
        if (lancamento.origem() != OrigemLancamento.CONVERSA) {
            comprovantes.emitirLink(operacao, lancamento.id(), token);
        }
        OcorrenciaVinculada vinculada = Argumentos.vazio(e.compromisso()) ? null
                : compromissos.vincular(operacao, e.compromisso(), e.ocorrencia(), lancamento, DirecaoCompromisso.SAIDA);
        return registro.saida(operacao, lancamento, referencia, "gasto", total, meio.nome(), liquidacao, ambitoInferido).comOcorrencia(vinculada);
    }

    /** O total informado ou, sem ele, a parcela vezes o número de parcelas. Com os dois, eles têm de bater. */
    private static String valorDaCompra(Entrada e, Dono dados) {
        if (Argumentos.vazio(e.valorParcela())) {
            return e.valor();
        }
        if (e.moedaOuVazia().informada() || !Argumentos.vazio(e.totalComJuros())) {
            throw ErroTipado.validacao("valor_parcela", "Com moeda original ou juros, informe o total da compra em valor, e não valor_parcela.");
        }
        if (e.parcelas() == null || e.parcelas() < 2) {
            throw ErroTipado.validacao("valor_parcela", "valor_parcela só vale em compra parcelada: informe parcelas.");
        }
        Repartir.exigirQuantidade(e.parcelas(), "parcelas");
        Dinheiro parcela = RegistroDeLancamento.positivo(e.valorParcela().strip(), dados.moeda(), "valor_parcela");
        long total = Math.multiplyExact(parcela.centavos(), e.parcelas().longValue());
        if (Argumentos.vazio(e.valor())) {
            return Dinheiro.deCentavos(total, dados.moeda()).texto();
        }
        // A sobra de centavos fica numa parcela só, então o total pode diferir por menos de um centavo por parcela.
        Dinheiro informado = RegistroDeLancamento.positivo(e.valor(), dados.moeda(), "valor");
        if (Math.abs(informado.centavos() - total) >= e.parcelas()) {
            throw ErroTipado.validacao("valor_parcela", e.parcelas() + " parcelas de " + parcela.texto() + " dão " + Dinheiro.deCentavos(total, dados.moeda()).texto()
                    + ", e o valor informado é " + informado.texto() + ". Confira com o dono.");
        }
        return e.valor();
    }

    /** Quantas parcelas: uma sem informar; mais de uma só no crédito. {@code projetar} usa a mesma regra nas compras simuladas. */
    static int parcelas(Integer parcelas, Conta liquidacao, String campo) {
        if (parcelas == null) {
            return 1;
        }
        Repartir.exigirQuantidade(parcelas, campo);
        if (parcelas > 1 && liquidacao.tipo() != TipoConta.CARTAO_CREDITO) {
            throw ErroTipado.validacao(campo, "Parcelas só valem no crédito.");
        }
        return parcelas;
    }

    /** Âmbito: o informado, o padrão do meio ou o padrão do dono. */
    private UUID ambitoGeral(OperacaoEmCurso operacao, Entrada e, MeioPagamento meio) {
        if (!Argumentos.vazio(e.ambito())) {
            return referencias.ambito(operacao.dono(), e.ambito(), "ambito").id();
        }
        if (meio.ambitoPadrao() != null) {
            return meio.ambitoPadrao();
        }
        Ambito padrao = basica.ambitoPadrao(operacao);
        return padrao.id();
    }

    private UUID categoria(UUID dono, String texto, String campo) {
        return referencias.conta(dono, texto, campo, c -> c.tipo() == TipoConta.CATEGORIA).id();
    }

    private static BigDecimal quantidade(String texto, String campo) {
        if (Argumentos.vazio(texto)) {
            return null;
        }
        try {
            BigDecimal quantidade = new BigDecimal(texto.strip());
            if (quantidade.signum() > 0 && quantidade.stripTrailingZeros().scale() <= 3 && quantidade.precision() <= 12) {
                return quantidade;
            }
        } catch (NumberFormatException invalida) {
            // Cai na mensagem abaixo.
        }
        throw ErroTipado.validacao(campo + ".quantidade", "Quantidade inválida: use um decimal com ponto e até três casas.");
    }

    private static void exigirCampos(Entrada e, List<Item> itens) {
        if (Argumentos.vazio(e.compromisso()) && !Argumentos.vazio(e.ocorrencia())) {
            throw ErroTipado.validacao("ocorrencia", "ocorrencia só vale com compromisso.");
        }
        Map<String, String> faltando = new LinkedHashMap<>();
        String valor = Argumentos.vazio(e.valor()) ? e.valorParcela() : e.valor();
        for (String campo : e.moedaOuVazia().faltando(valor)) {
            faltando.put(campo, campo.equals("valor") && e.moedaOuVazia().informada()
                    ? "quanto saiu na moeda da conta, ou qual foi a cotação" : PERGUNTAS.get(campo));
        }
        exigir(faltando, "descricao", e.descricao());
        exigir(faltando, "meio_pagamento", e.meioPagamento());
        exigir(faltando, "origem", e.origem());
        if (itens.isEmpty()) {
            exigir(faltando, "categoria", e.categoria());
        }
        List<String> campos = new ArrayList<>(faltando.keySet());
        for (int i = 0; i < itens.size(); i++) {
            Item item = itens.get(i);
            if (Argumentos.vazio(item.descricao())) {
                campos.add("itens[" + i + "].descricao");
            }
            if (Argumentos.vazio(item.valor())) {
                campos.add("itens[" + i + "].valor");
            }
            if (Argumentos.vazio(item.categoria()) && Argumentos.vazio(e.categoria())) {
                campos.add("itens[" + i + "].categoria");
            }
        }
        if (!campos.isEmpty()) {
            String pergunta = faltando.isEmpty() ? "Pergunte ao dono o que falta nos itens."
                    : "Pergunte ao dono " + String.join(", ", faltando.values()) + ".";
            throw ErroTipado.camposFaltando(campos, pergunta);
        }
    }

    private static void exigir(Map<String, String> faltando, String campo, String valor) {
        if (Argumentos.vazio(valor)) {
            faltando.put(campo, PERGUNTAS.get(campo));
        }
    }
}
