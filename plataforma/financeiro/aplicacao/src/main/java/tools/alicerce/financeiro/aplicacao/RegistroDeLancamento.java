package tools.alicerce.financeiro.aplicacao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.RepositorioLancamentos.Resumo;
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.Movimento;
import tools.alicerce.financeiro.dominio.OrigemLancamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.alicerce.financeiro.dominio.ValorOriginal;

/** O que {@code registrar_gasto} e {@code registrar_ganho} têm em comum. */
@Component
class RegistroDeLancamento {

    /** Janela da detecção de duplicata provável. */
    static final int MINUTOS_DUPLICATA = 10;

    private static final Pattern CHAVE_NFCE = Pattern.compile("[0-9]{44}");
    private static final Pattern CNPJ = Pattern.compile("[0-9A-Z]{14}");

    private final RepositorioLancamentos lancamentos;
    private final RepositorioEstrutura estrutura;
    private final Enderecos enderecos;
    private final Faturas faturas;

    RegistroDeLancamento(RepositorioLancamentos lancamentos, RepositorioEstrutura estrutura, Enderecos enderecos, Faturas faturas) {
        this.lancamentos = lancamentos;
        this.estrutura = estrutura;
        this.enderecos = enderecos;
        this.faturas = faturas;
    }

    static OrigemLancamento origem(String codigo) {
        return OrigemLancamento.deCodigo(codigo.strip()).orElseThrow(() -> ErroTipado.validacao("origem",
                "Origem inválida: use conversa, foto_nota ou print_app."));
    }

    static String chaveNfce(String texto) {
        if (Argumentos.vazio(texto)) {
            return null;
        }
        String chave = texto.replaceAll("\\s", "");
        if (!CHAVE_NFCE.matcher(chave).matches()) {
            throw ErroTipado.validacao("chave_nfce", "A chave da NFC-e tem 44 dígitos.");
        }
        return chave;
    }

    static String cnpj(String texto) {
        if (Argumentos.vazio(texto)) {
            return null;
        }
        String cnpj = texto.replaceAll("[.\\-/\\s]", "").toUpperCase(java.util.Locale.ROOT);
        if (!CNPJ.matcher(cnpj).matches()) {
            throw ErroTipado.validacao("cnpj_estabelecimento", "O CNPJ tem 14 caracteres.");
        }
        return cnpj;
    }

    static Dinheiro positivo(String texto, Currency moeda, String campo) {
        Dinheiro valor = Dinheiro.ler(texto, moeda, campo);
        if (valor.ehNegativo() || valor.ehZero()) {
            throw ErroTipado.validacao(campo, "O valor tem de ser maior que zero.");
        }
        return valor;
    }

    /**
     * O total de uma compra e o preço à vista, se houve juros. Igual ao preço, não há juros a guardar.
     *
     * @return {@code [total, preco à vista ou nulo]}
     */
    static Dinheiro[] comJuros(Dinheiro valor, String totalComJuros, int parcelas, String campo) {
        if (Argumentos.vazio(totalComJuros)) {
            return new Dinheiro[] {valor, null};
        }
        if (parcelas < 2) {
            throw ErroTipado.validacao(campo, "total_com_juros só vale em compra parcelada.");
        }
        Dinheiro total = positivo(totalComJuros.strip(), valor.moeda(), campo);
        if (total.compareTo(valor) < 0) {
            throw ErroTipado.validacao(campo, "O total com juros é menor que o preço à vista.");
        }
        return new Dinheiro[] {total, total.compareTo(valor) == 0 ? null : valor};
    }

    /** Categoria é conta na moeda do dono, então gasto e ganho numa conta em outra moeda misturariam moedas. */
    static void exigirMoedaDoDono(Conta conta, Currency moedaDoDono, String campo) {
        if (!conta.moeda().equals(moedaDoDono.getCurrencyCode())) {
            throw ErroTipado.validacao(campo, "A conta '" + conta.nome() + "' está em " + conta.moeda()
                    + " e, por enquanto, só recebe e envia transferências: gasto e ganho usam conta em " + moedaDoDono.getCurrencyCode()
                    + ". Registre a saída com registrar_transferencia, ou use uma conta em " + moedaDoDono.getCurrencyCode() + ".");
        }
    }

    /** O total na moeda da conta e, se a compra foi em outra moeda, a descrição dela. */
    static ValorOriginal.Conversao valores(String valor, EntradaMoeda moeda, Currency moedaDaConta, LocalDate data) {
        if (moeda == null || !moeda.informada()) {
            return new ValorOriginal.Conversao(positivo(valor, moedaDaConta, "valor"), null);
        }
        Currency original = ValorOriginal.lerMoeda(moeda.moedaOriginal(), moedaDaConta, "moeda_original");
        BigDecimal valorOriginal = ValorOriginal.lerValor(moeda.valorOriginal(), original, "valor_original");
        Dinheiro informado = Argumentos.vazio(valor) ? null : positivo(valor, moedaDaConta, "valor");
        BigDecimal cotacao = Argumentos.vazio(moeda.cotacao()) ? null : ValorOriginal.lerCotacao(moeda.cotacao(), "cotacao");
        LocalDate dataCotacao = Argumentos.vazio(moeda.dataCotacao()) ? data : Datas.ler(moeda.dataCotacao().strip(), "data_cotacao");
        return ValorOriginal.converter(valorOriginal, original, informado, cotacao, dataCotacao, moedaDaConta, "");
    }

    void semNotaRepetida(UUID dono, String chave) {
        if (chave != null) {
            lancamentos.lancamentoDaChaveFiscal(dono, chave).ifPresent(RegistroDeLancamento::notaDuplicada);
        }
    }

    /** @param conta onde procurar: a conta do fato ou, se ele não a tocou, Saldo de abertura */
    void semParecido(UUID dono, Lancamento lancamento, UUID conta, Boolean confirmar) {
        if (Boolean.TRUE.equals(confirmar)) {
            return;
        }
        Dinheiro valor = lancamento.movimentos().stream().filter(m -> m.conta().equals(conta)).map(Movimento::valor).reduce(Dinheiro::somar).orElseThrow();
        List<Resumo> parecidos = lancamentos.parecidos(dono, lancamento.tipo(), lancamento.data(),
                lancamento.meioPagamento(), conta, valor.centavos(), MINUTOS_DUPLICATA);
        if (!parecidos.isEmpty()) {
            throw ErroTipado.referencia(CodigoErro.POSSIVEL_DUPLICATA, "confirmar_duplicata",
                    "Há um lançamento igual registrado há menos de " + MINUTOS_DUPLICATA + " minutos.",
                    parecidos.stream().map(this::candidato).toList(),
                    "Confirme com o dono; se for outro lançamento, repita com confirmar_duplicata: true.");
        }
    }

    /** Grava o lançamento e a chave da nota e devolve o id curto. */
    ReferenciaCurta gravar(OperacaoEmCurso operacao, Lancamento lancamento, String chaveNfce) {
        lancamentos.inserir(operacao, lancamento);
        operacao.lancamentoCriado(lancamento.id());
        if (chaveNfce != null && !lancamentos.inserirChaveFiscal(operacao, chaveNfce, lancamento.id())) {
            Optional<Resumo> vigente = lancamentos.lancamentoDaChaveFiscal(operacao.dono(), chaveNfce);
            if (vigente.isPresent()) {
                // Outra chamada gravou a mesma nota enquanto esta rodava.
                notaDuplicada(vigente.get());
            }
            // A nota era de um lançamento anulado: agora é deste.
            moverChave(operacao, chaveNfce, lancamento.id());
        }
        return operacao.referenciar(TipoReferencia.LANCAMENTO, lancamento.id());
    }

    /** Passa a nota para {@code destino} e declara de onde ela saiu. */
    void moverChave(OperacaoEmCurso operacao, String chave, UUID destino) {
        UUID anterior = lancamentos.lancamentoApontadoPelaChave(operacao.dono(), chave).orElseThrow();
        lancamentos.moverChaveFiscal(operacao, chave, destino);
        operacao.chaveFiscalMovida(chave, anterior);
    }

    /**
     * Grava o estorno de {@code alvo}. A cadeia só aceita um estorno por lançamento, então quem é
     * estornado é a ponta dela: num lançamento restabelecido, a ponta é o estorno do estorno.
     */
    Lancamento estornar(OperacaoEmCurso operacao, UUID alvo, String motivo) {
        UUID dono = operacao.dono();
        Lancamento ponta = lancamentos.buscar(dono, lancamentos.cadeia(dono, alvo).ponta()).orElseThrow();
        Lancamento estorno = ponta.estorno(UUID.randomUUID(), motivo);
        lancamentos.inserir(operacao, estorno);
        operacao.lancamentoEstornado(estorno.id());
        operacao.referenciar(TipoReferencia.LANCAMENTO, estorno.id());
        return estorno;
    }

    /** O saldo atual de cada conta com dinheiro entre as informadas. */
    List<SaldoConta> saldos(UUID dono, java.util.Collection<UUID> contas) {
        return estrutura.contas(dono).stream().filter(c -> contas.contains(c.id()) && c.tipo().temSaldo())
                .map(c -> new SaldoConta(c.nome(), Dinheiro.deCentavos(lancamentos.saldoCentavos(c.id()),
                        Currency.getInstance(c.moeda())).texto(), c.moeda()))
                .toList();
    }

    LancamentoRegistrado saida(OperacaoEmCurso operacao, Lancamento lancamento, ReferenciaCurta referencia, String tipo,
            Dinheiro total, String meio, Conta conta, boolean ambitoInferido) {
        Map<UUID, String> contas = estrutura.contas(operacao.dono()).stream()
                .collect(Collectors.toMap(Conta::id, Conta::nome));
        Map<UUID, String> ambitos = estrutura.ambitos(operacao.dono()).stream()
                .collect(Collectors.toMap(Ambito::id, Ambito::nome));
        List<LancamentoRegistrado.MovimentoRegistrado> movimentos = new ArrayList<>();
        for (Movimento m : lancamento.movimentos()) {
            movimentos.add(new LancamentoRegistrado.MovimentoRegistrado(contas.get(m.conta()), m.valor().texto(),
                    Optional.ofNullable(m.ambito()).map(ambitos::get).orElse(null), m.parcelaTexto()));
        }
        String saldo = Dinheiro.deCentavos(lancamentos.saldoCentavos(conta.id()), total.moeda()).texto();
        return new LancamentoRegistrado(referencia.id(), enderecos.url(referencia), tipo, total.texto(),
                total.moeda().getCurrencyCode(), lancamento.data().toString(), lancamento.descricao(), meio, conta.nome(),
                saldo, false, ambitoInferido, List.copyOf(movimentos), lancamento.origem() != OrigemLancamento.CONVERSA, null, null,
                OriginalVisto.de(lancamento.original()), faturas.credito(operacao.dono(), lancamento, total.moeda()),
                lancamento.procedencia().codigo(), null);
    }

    private static void notaDuplicada(Resumo existente) {
        throw ErroTipado.referencia(CodigoErro.NOTA_DUPLICADA, "chave_nfce", "Essa nota já foi registrada.",
                List.of(new ErroTipado.Candidato(existente.codigo(), existente.descricao(), null)),
                "Mostre ao dono o lançamento que já existe.");
    }

    private ErroTipado.Candidato candidato(Resumo resumo) {
        Function<ReferenciaCurta, String> url = enderecos::url;
        return new ErroTipado.Candidato(resumo.codigo(), resumo.descricao(),
                ReferenciaCurta.ler(resumo.codigo(), TipoReferencia.MODULO).map(url).orElse(null));
    }
}
