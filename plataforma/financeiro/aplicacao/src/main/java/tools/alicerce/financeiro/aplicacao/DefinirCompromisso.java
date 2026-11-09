package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ChaveIdempotencia;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.ClasseConta;
import tools.alicerce.financeiro.dominio.Compromisso;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.DirecaoCompromisso;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.FrequenciaCompromisso;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.TipoCompromisso;
import tools.alicerce.financeiro.dominio.TipoMeioPagamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * Capacidade {@code definir_compromisso}: cria, altera ou encerra um compromisso. Nada aqui gera
 * lançamento: o pagamento é um gasto ou ganho vinculado a uma ocorrência.
 */
@Component
public class DefinirCompromisso {

    public record Entrada(String chaveIdempotencia, String lote, String ref, String direcao, String tipo, String nome, List<String> aliases,
            String valor, String valorTotal, Integer parcelas, Integer parcelasPagasAntes, String frequencia, String primeiroVencimento,
            String fim, String contraparte, String referencia, String categoria, String conta, String meioPagamento, String ambito,
            String observacao, Boolean encerrar) {}

    static final String CAPACIDADE = "definir_compromisso";
    private static final int PROXIMAS = 3;

    private final RegistradorOperacoes registrador;
    private final RepositorioCompromissos repositorio;
    private final RepositorioDono donos;
    private final Referencias referencias;
    private final Compromissos compromissos;

    DefinirCompromisso(RegistradorOperacoes registrador, RepositorioCompromissos repositorio, RepositorioDono donos, Referencias referencias,
            Compromissos compromissos) {
        this.registrador = registrador;
        this.repositorio = repositorio;
        this.donos = donos;
        this.referencias = referencias;
        this.compromissos = compromissos;
    }

    public ResultadoEscrita<CompromissoVisto> executar(Chamador chamador, Entrada e) {
        Map<String, ?> argumentos = new Argumentos().com("ref", e.ref()).com("direcao", e.direcao()).com("tipo", e.tipo()).com("nome", e.nome())
                .com("aliases", Argumentos.lista(e.aliases())).com("valor", e.valor()).com("valor_total", e.valorTotal())
                .com("parcelas", e.parcelas()).com("parcelas_pagas_antes", e.parcelasPagasAntes()).com("frequencia", e.frequencia())
                .com("primeiro_vencimento", e.primeiroVencimento()).com("fim", e.fim()).com("contraparte", e.contraparte())
                .com("referencia", e.referencia()).com("categoria", e.categoria()).com("conta", e.conta())
                .com("meio_pagamento", e.meioPagamento()).com("ambito", e.ambito()).com("observacao", e.observacao())
                .com("encerrar", e.encerrar()).mapa();
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, argumentos, ChaveIdempotencia.ler(e.chaveIdempotencia()), e.lote());
        return registrador.registrar(chamador, pedido, CompromissoVisto.class, operacao -> {
            Compromisso c = Argumentos.vazio(e.ref()) ? criar(operacao, e) : alterar(operacao, e);
            UUID dono = operacao.dono();
            LocalDate hoje = compromissos.hoje(dono);
            var registros = repositorio.registros(c.id());
            return compromissos.ver(dono, c, registros, hoje).comProximas(compromissos.proximas(c, registros, hoje, PROXIMAS));
        });
    }

    private Compromisso criar(OperacaoEmCurso operacao, Entrada e) {
        exigirParaCriar(e);
        UUID dono = operacao.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        DirecaoCompromisso direcao = direcao(e.direcao());
        TipoCompromisso tipo = tipo(e.tipo());
        semNomeRepetido(dono, null, e.nome());
        Compromisso c = montar(dono, dados, UUID.randomUUID(), direcao, tipo, e, null);
        repositorio.inserir(operacao, c);
        operacao.entidadeCriada(EntidadeVersionada.COMPROMISSO, c.id(), c);
        operacao.referenciar(TipoReferencia.COMPROMISSO, c.id());
        return c;
    }

    /**
     * O compromisso que criar montaria com esta entrada, sem gravar nada e sem conferir o nome: as hipóteses de
     * {@code projetar} geram as ocorrências pela mesma regra.
     */
    Compromisso simular(UUID dono, Entrada e) {
        exigirParaCriar(e);
        Dono dados = donos.buscar(dono).orElseThrow();
        return montar(dono, dados, UUID.randomUUID(), direcao(e.direcao()), tipo(e.tipo()), e, null);
    }

    private static void exigirParaCriar(Entrada e) {
        List<String> faltando = new ArrayList<>();
        exigir(faltando, "direcao", e.direcao());
        exigir(faltando, "tipo", e.tipo());
        exigir(faltando, "nome", e.nome());
        exigir(faltando, "primeiro_vencimento", e.primeiroVencimento());
        if (Argumentos.vazio(e.valor()) && Argumentos.vazio(e.valorTotal())) {
            faltando.add("valor");
        }
        if ("parcelado".equals(texto(e.tipo())) && e.parcelas() == null) {
            faltando.add("parcelas");
        }
        if (!faltando.isEmpty()) {
            throw ErroTipado.camposFaltando(faltando, "Pergunte ao dono o que é o compromisso, se ele paga ou recebe, quanto, "
                    + "quando vence a primeira vez e, se for parcelado, em quantas parcelas.");
        }
    }

    private Compromisso alterar(OperacaoEmCurso operacao, Entrada e) {
        UUID dono = operacao.dono();
        Compromisso atual = compromissos.resolver(dono, e.ref(), "ref");
        if (!Argumentos.vazio(e.direcao()) && direcao(e.direcao()) != atual.direcao()) {
            throw ErroTipado.validacao("direcao", "A direção de um compromisso não muda: encerre este e crie outro.");
        }
        if (!Argumentos.vazio(e.tipo()) && tipo(e.tipo()) != atual.tipo()) {
            throw ErroTipado.validacao("tipo", "O tipo de um compromisso não muda: encerre este e crie outro.");
        }
        if (!Argumentos.vazio(e.nome())) {
            semNomeRepetido(dono, atual.id(), e.nome());
        }
        Dono dados = donos.buscar(dono).orElseThrow();
        Compromisso novo = montar(dono, dados, atual.id(), atual.direcao(), atual.tipo(), e, atual);
        repositorio.atualizar(operacao, novo);
        operacao.entidadeAlterada(EntidadeVersionada.COMPROMISSO, novo.id(), atual.versao(), novo);
        return novo;
    }

    /** O compromisso com o que veio na entrada e, ao alterar, o resto como estava. */
    private Compromisso montar(UUID dono, Dono dados, UUID id, DirecaoCompromisso direcao, TipoCompromisso tipo, Entrada e, Compromisso a) {
        String moeda = a == null ? dados.moeda().getCurrencyCode() : a.moeda();
        java.util.Currency currency = java.util.Currency.getInstance(moeda);
        String nome = Argumentos.vazio(e.nome()) ? a.nome() : e.nome().strip();
        List<String> aliases = e.aliases() == null ? a == null ? List.of() : a.aliases()
                : e.aliases().stream().map(String::strip).filter(x -> !x.isEmpty()).distinct().toList();
        Integer parcelas = tipo == TipoCompromisso.PARCELADO ? (e.parcelas() != null ? e.parcelas() : a.parcelas()) : null;
        if (tipo != TipoCompromisso.PARCELADO && (e.parcelas() != null || e.parcelasPagasAntes() != null || !Argumentos.vazio(e.valorTotal()))) {
            throw ErroTipado.validacao("parcelas", "parcelas, parcelas_pagas_antes e valor_total só valem em compromisso parcelado.");
        }
        if (parcelas != null && (parcelas < 1 || parcelas > Compromisso.MAXIMO_PARCELAS)) {
            throw ErroTipado.validacao("parcelas", "Use de 1 a " + Compromisso.MAXIMO_PARCELAS + " parcelas.");
        }
        int pagasAntes = e.parcelasPagasAntes() != null ? e.parcelasPagasAntes() : a == null ? 0 : a.parcelasPagasAntes();
        if (pagasAntes < 0 || parcelas != null && pagasAntes > parcelas) {
            throw ErroTipado.validacao("parcelas_pagas_antes", "parcelas_pagas_antes vai de 0 ao número de parcelas.");
        }
        long valor;
        Long total;
        if (!Argumentos.vazio(e.valor()) && !Argumentos.vazio(e.valorTotal())) {
            throw ErroTipado.validacao("valor_total", "Informe valor (de cada parcela) ou valor_total, não os dois.");
        }
        if (!Argumentos.vazio(e.valorTotal())) {
            total = RegistroDeLancamento.positivo(e.valorTotal().strip(), currency, "valor_total").centavos();
            if (total < parcelas) {
                throw ErroTipado.validacao("valor_total", "O valor é pequeno demais para tantas parcelas.");
            }
            valor = total / parcelas;
        } else if (!Argumentos.vazio(e.valor())) {
            valor = RegistroDeLancamento.positivo(e.valor().strip(), currency, "valor").centavos();
            total = null;
        } else {
            valor = a.valorCentavos();
            total = a.valorTotalCentavos();
            if (total != null && !parcelas.equals(a.parcelas())) {
                valor = total / parcelas;
            }
        }
        FrequenciaCompromisso frequencia = Argumentos.vazio(e.frequencia()) ? a == null ? FrequenciaCompromisso.MENSAL : a.frequencia()
                : FrequenciaCompromisso.deCodigo(e.frequencia().strip()).orElseThrow(() ->
                        ErroTipado.validacao("frequencia", "Frequência inválida: use mensal ou anual."));
        if (tipo == TipoCompromisso.UNICO) {
            frequencia = FrequenciaCompromisso.MENSAL;
        }
        LocalDate primeiro = Argumentos.vazio(e.primeiroVencimento()) ? a.primeiroVencimento()
                : Datas.ler(e.primeiroVencimento().strip(), "primeiro_vencimento");
        boolean encerrado = e.encerrar() == null ? a != null && a.encerrado() : e.encerrar();
        LocalDate fim = Argumentos.vazio(e.fim()) ? a == null ? null : a.fim() : Datas.ler(e.fim().strip(), "fim");
        if (!Argumentos.vazio(e.fim()) && fim.isBefore(primeiro)) {
            throw ErroTipado.validacao("fim", "O fim é anterior ao primeiro vencimento.");
        }
        if (Boolean.TRUE.equals(e.encerrar()) && Argumentos.vazio(e.fim())) {
            // Encerrar sem data: nada vence depois de hoje; o que já venceu e está em aberto continua atrasado.
            LocalDate hoje = compromissos.hoje(dono);
            fim = fim == null || fim.isAfter(hoje) ? hoje : fim;
        }
        if (Boolean.FALSE.equals(e.encerrar()) && Argumentos.vazio(e.fim()) && a != null && a.encerrado()) {
            fim = null;
        }
        UUID categoria = Argumentos.vazio(e.categoria()) ? a == null ? null : a.categoria() : categoria(dono, direcao, e.categoria());
        UUID conta = Argumentos.vazio(e.conta()) ? a == null ? null : a.conta()
                : referencias.conta(dono, e.conta(), "conta", c -> c.classe() == ClasseConta.ATIVO).id();
        UUID meio = Argumentos.vazio(e.meioPagamento()) ? a == null ? null : a.meioPagamento() : meio(dono, tipo, e.meioPagamento());
        UUID ambito = Argumentos.vazio(e.ambito()) ? a == null ? null : a.ambito() : referencias.ambito(dono, e.ambito(), "ambito").id();
        return new Compromisso(id, direcao, tipo, nome, aliases, opcional(e.contraparte(), a == null ? null : a.contraparte()),
                opcional(e.referencia(), a == null ? null : a.referencia()), valor, total, moeda, frequencia, primeiro, parcelas, pagasAntes, fim,
                categoria, conta, meio, ambito, opcional(e.observacao(), a == null ? null : a.observacao()), encerrado,
                a == null ? 1 : a.versao() + 1);
    }

    private UUID categoria(UUID dono, DirecaoCompromisso direcao, String texto) {
        ClasseConta classe = direcao == DirecaoCompromisso.SAIDA ? ClasseConta.DESPESA : ClasseConta.RECEITA;
        Conta conta = referencias.conta(dono, texto, "categoria", c -> c.classe() == classe);
        return conta.id();
    }

    private UUID meio(UUID dono, TipoCompromisso tipo, String texto) {
        MeioPagamento m = referencias.meio(dono, texto, "meio_pagamento");
        if (m.tipo() == TipoMeioPagamento.CREDITO && tipo == TipoCompromisso.PARCELADO) {
            throw ErroTipado.validacao("meio_pagamento", "Compra parcelada no cartão não é compromisso: registre com registrar_gasto e parcelas, "
                    + "e as parcelas aparecem nas faturas. Cartão de outra pessoa é compromisso pago por Pix, débito ou outro meio seu.");
        }
        return m.id();
    }

    private void semNomeRepetido(UUID dono, UUID proprio, String nome) {
        String procurado = Referencias.normalizar(nome);
        boolean repetido = repositorio.compromissos(dono).stream()
                .anyMatch(c -> !c.id().equals(proprio) && !c.encerrado() && Referencias.normalizar(c.nome()).equals(procurado));
        if (repetido) {
            throw ErroTipado.de(CodigoErro.CONFLITO, "nome", "Já existe um compromisso ativo '" + nome.strip() + "'.");
        }
    }

    private static DirecaoCompromisso direcao(String texto) {
        return DirecaoCompromisso.deCodigo(texto(texto)).orElseThrow(() ->
                ErroTipado.validacao("direcao", "Direção inválida: use saida (o dono paga) ou entrada (o dono recebe)."));
    }

    private static TipoCompromisso tipo(String texto) {
        return TipoCompromisso.deCodigo(texto(texto)).orElseThrow(() ->
                ErroTipado.validacao("tipo", "Tipo inválido: use recorrente, parcelado ou unico."));
    }

    private static String texto(String valor) {
        return valor == null ? "" : valor.strip();
    }

    private static String opcional(String novo, String atual) {
        if (novo == null) {
            return atual;
        }
        return novo.isBlank() ? null : novo.strip();
    }

    private static void exigir(List<String> faltando, String campo, String valor) {
        if (Argumentos.vazio(valor)) {
            faltando.add(campo);
        }
    }
}
