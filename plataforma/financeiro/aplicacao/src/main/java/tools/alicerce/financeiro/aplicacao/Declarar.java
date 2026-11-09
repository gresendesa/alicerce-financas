package tools.alicerce.financeiro.aplicacao;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ChaveIdempotencia;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.RepositorioDeclaracoes.Declaracao;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.Meta;
import tools.alicerce.financeiro.dominio.TipoConta;
import tools.alicerce.financeiro.dominio.TipoDeclaracao;
import tools.alicerce.financeiro.dominio.ValorDeclaracao;

/**
 * Capacidade {@code declarar}: registra uma intenção ou regra do perfil. A nova substitui a vigente do mesmo
 * tipo, que continua guardada; desfazer a nova traz a anterior de volta. Os sete tipos da modelagem: {@code piso_periodo} e
 * {@code categorias_essenciais} e, depois, {@code reserva_minima}, {@code intencao_poupanca},
 * {@code teto_parcelas}, {@code meta_protegida} e {@code nota}.
 */
@Component
public class Declarar {

    /**
     * @param saldoMinimo em {@code piso_periodo}: o saldo mínimo no último dia do mês
     * @param categorias em {@code categorias_essenciais}: a lista inteira, que substitui a anterior; vazia diz que nenhuma é
     * @param valor em {@code reserva_minima}: o valor fixo
     * @param mesesDeGasto em {@code reserva_minima}: quantos meses de gasto típico
     * @param percentual em {@code intencao_poupanca} e {@code teto_parcelas}: o percentual da renda
     * @param metas em {@code meta_protegida}: a lista inteira, que substitui a anterior; vazia diz que nenhuma é
     * @param texto em {@code nota}: a nota inteira
     */
    public record Entrada(String chaveIdempotencia, String lote, String tipo, String saldoMinimo, List<String> categorias,
            String observacao, String valor, Integer mesesDeGasto, String percentual, List<String> metas, String texto) {

        public Entrada(String chaveIdempotencia, String lote, String tipo, String saldoMinimo, List<String> categorias, String observacao) {
            this(chaveIdempotencia, lote, tipo, saldoMinimo, categorias, observacao, null, null, null, null, null);
        }
    }

    private static final String TIPOS = "piso_periodo, categorias_essenciais, reserva_minima, intencao_poupanca, teto_parcelas, "
            + "meta_protegida ou nota";

    static final String CAPACIDADE = "declarar";
    private static final Clock RELOGIO = Clock.systemUTC();

    private final RegistradorOperacoes registrador;
    private final RepositorioDeclaracoes repositorio;
    private final RepositorioDono donos;
    private final Referencias referencias;
    private final Declaracoes declaracoes;
    private final Metas metas;

    Declarar(RegistradorOperacoes registrador, RepositorioDeclaracoes repositorio, RepositorioDono donos, Referencias referencias,
            Declaracoes declaracoes, Metas metas) {
        this.metas = metas;
        this.registrador = registrador;
        this.repositorio = repositorio;
        this.donos = donos;
        this.referencias = referencias;
        this.declaracoes = declaracoes;
    }

    public ResultadoEscrita<DeclaracaoRegistrada> executar(Chamador chamador, Entrada e) {
        Map<String, ?> argumentos = new Argumentos().com("tipo", e.tipo()).com("saldo_minimo", e.saldoMinimo())
                .com("categorias", Argumentos.lista(e.categorias())).com("observacao", e.observacao()).com("valor", e.valor())
                .com("meses_de_gasto", e.mesesDeGasto()).com("percentual", e.percentual()).com("metas", Argumentos.lista(e.metas()))
                .com("texto", e.texto()).mapa();
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, argumentos, ChaveIdempotencia.ler(e.chaveIdempotencia()), e.lote());
        return registrador.registrar(chamador, pedido, DeclaracaoRegistrada.class, operacao -> declarar(operacao, e));
    }

    private DeclaracaoRegistrada declarar(OperacaoEmCurso operacao, Entrada e) {
        if (Argumentos.vazio(e.tipo())) {
            throw ErroTipado.camposFaltando(List.of("tipo"), "Pergunte ao dono o que ele quer declarar: o saldo mínimo do fim do mês "
                    + "(piso_periodo), as categorias essenciais (categorias_essenciais), a reserva que quer ter sempre (reserva_minima), "
                    + "quanto quer poupar (intencao_poupanca), o teto das parcelas (teto_parcelas), as metas que não podem atrasar "
                    + "(meta_protegida) ou uma nota (nota).");
        }
        TipoDeclaracao tipo = TipoDeclaracao.deCodigo(e.tipo().strip())
                .orElseThrow(() -> ErroTipado.validacao("tipo", "Tipo inválido: use " + TIPOS + "."));
        if (!tipo.aceito()) {
            throw ErroTipado.validacao("tipo", "O tipo " + tipo.codigo() + " ainda não pode ser declarado.");
        }
        somenteDoTipo(tipo, e);
        UUID dono = operacao.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        ValorDeclaracao valor = switch (tipo) {
            case PISO_PERIODO -> piso(e, dados);
            case CATEGORIAS_ESSENCIAIS -> essenciais(dono, e);
            case RESERVA_MINIMA -> reserva(e, dados);
            case INTENCAO_POUPANCA -> new ValorDeclaracao.IntencaoPoupanca(percentual(e, "Pergunte ao dono quanto da renda ele quer "
                    + "poupar, em %, de 0 a 100. \"Não quero poupar agora\" é 0."));
            case TETO_PARCELAS -> new ValorDeclaracao.TetoParcelas(percentual(e, "Pergunte ao dono qual é o máximo da renda de um mês "
                    + "que as parcelas podem levar, em %, de 1 a 100."));
            case META_PROTEGIDA -> protegidas(dono, e);
            case NOTA -> nota(e);
        };
        Declaracoes.Vigente anterior = declaracoes.vigentes(dono).get(tipo);
        LocalDate hoje = Datas.hoje(RELOGIO, dados.fuso());
        String observacao = Argumentos.vazio(e.observacao()) ? null : e.observacao().strip();
        Declaracao nova = new Declaracao(UUID.randomUUID(), tipo, declaracoes.escrever(valor), hoje, observacao, null, null);
        repositorio.inserir(operacao, nova);
        operacao.declaracaoRegistrada(nova.id());
        Declaracao comOperacao = new Declaracao(nova.id(), tipo, nova.valor(), hoje, observacao, operacao.referencia().id(), null);
        DeclaracaoVista vista = declaracoes.vista(dono, new Declaracoes.Vigente(comOperacao, valor), dados.moeda());
        return new DeclaracaoRegistrada(vista, anterior == null ? null : declaracoes.vista(dono, anterior, dados.moeda()), proximoPasso(tipo));
    }

    /** Cada campo só vale no tipo dele: um campo de outro tipo é erro, para o assistente não achar que ele valeu. */
    private static void somenteDoTipo(TipoDeclaracao tipo, Entrada e) {
        Map<String, TipoDeclaracao[]> campos = new java.util.LinkedHashMap<>();
        campos.put("saldo_minimo", new TipoDeclaracao[] {TipoDeclaracao.PISO_PERIODO});
        campos.put("categorias", new TipoDeclaracao[] {TipoDeclaracao.CATEGORIAS_ESSENCIAIS});
        campos.put("valor", new TipoDeclaracao[] {TipoDeclaracao.RESERVA_MINIMA});
        campos.put("meses_de_gasto", new TipoDeclaracao[] {TipoDeclaracao.RESERVA_MINIMA});
        campos.put("percentual", new TipoDeclaracao[] {TipoDeclaracao.INTENCAO_POUPANCA, TipoDeclaracao.TETO_PARCELAS});
        campos.put("metas", new TipoDeclaracao[] {TipoDeclaracao.META_PROTEGIDA});
        campos.put("texto", new TipoDeclaracao[] {TipoDeclaracao.NOTA});
        Map<String, Boolean> presentes = Map.of("saldo_minimo", !Argumentos.vazio(e.saldoMinimo()),
                "categorias", e.categorias() != null && !e.categorias().isEmpty(),
                "valor", !Argumentos.vazio(e.valor()), "meses_de_gasto", e.mesesDeGasto() != null,
                "percentual", !Argumentos.vazio(e.percentual()),
                "metas", e.metas() != null && !e.metas().isEmpty(),
                "texto", !Argumentos.vazio(e.texto()));
        campos.forEach((campo, tipos) -> {
            if (presentes.get(campo) && !java.util.Arrays.asList(tipos).contains(tipo)) {
                throw ErroTipado.validacao(campo, campo + " só vale em " + String.join(" e ",
                        java.util.Arrays.stream(tipos).map(TipoDeclaracao::codigo).toList()) + ".");
            }
        });
    }

    private static ValorDeclaracao piso(Entrada e, Dono dados) {
        if (Argumentos.vazio(e.saldoMinimo())) {
            throw ErroTipado.camposFaltando(List.of("saldo_minimo"), "Pergunte ao dono com quanto, no mínimo, ele quer terminar o mês, "
                    + "depois de pagas as contas do mês. \"No verde\" é 0.01.");
        }
        return new ValorDeclaracao.PisoPeriodo(Dinheiro.ler(e.saldoMinimo(), dados.moeda(), "saldo_minimo").centavos());
    }

    private ValorDeclaracao essenciais(UUID dono, Entrada e) {
        if (e.categorias() == null) {
            throw ErroTipado.camposFaltando(List.of("categorias"), "Pergunte ao dono quais categorias são essenciais. A lista substitui a "
                    + "anterior inteira: inclua as que já eram.");
        }
        List<UUID> ids = new ArrayList<>();
        for (String texto : e.categorias()) {
            if (Argumentos.vazio(texto)) {
                continue;
            }
            ids.add(referencias.conta(dono, texto.strip(), "categorias", c -> c.tipo() == TipoConta.CATEGORIA).id());
        }
        return new ValorDeclaracao.CategoriasEssenciais(ids);
    }

    private static ValorDeclaracao reserva(Entrada e, Dono dados) {
        boolean temValor = !Argumentos.vazio(e.valor());
        if (!temValor && e.mesesDeGasto() == null) {
            throw ErroTipado.camposFaltando(List.of("valor", "meses_de_gasto"), "Pergunte ao dono quanto ele quer ter sempre guardado: um "
                    + "valor (valor) ou quantos meses de gasto (meses_de_gasto), um dos dois.");
        }
        if (temValor && e.mesesDeGasto() != null) {
            throw ErroTipado.validacao("meses_de_gasto", "A reserva mínima é um valor ou meses de gasto, não os dois. Pergunte ao dono qual vale.");
        }
        Long centavos = temValor ? RegistroDeLancamento.positivo(e.valor().strip(), dados.moeda(), "valor").centavos() : null;
        return new ValorDeclaracao.ReservaMinima(centavos, e.mesesDeGasto());
    }

    private static BigDecimal percentual(Entrada e, String pergunta) {
        if (Argumentos.vazio(e.percentual())) {
            throw ErroTipado.camposFaltando(List.of("percentual"), pergunta);
        }
        try {
            return new BigDecimal(e.percentual().strip().replace("%", "").strip());
        } catch (NumberFormatException invalido) {
            throw ErroTipado.validacao("percentual", "Percentual inválido: use um número com ponto, como \"15\" ou \"12.5\".");
        }
    }

    private ValorDeclaracao protegidas(UUID dono, Entrada e) {
        if (e.metas() == null) {
            throw ErroTipado.camposFaltando(List.of("metas"), "Pergunte ao dono quais metas não podem atrasar. A lista substitui a anterior "
                    + "inteira: inclua as que já eram.");
        }
        LocalDate hoje = metas.hoje(dono);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < e.metas().size(); i++) {
            String texto = e.metas().get(i);
            if (Argumentos.vazio(texto)) {
                continue;
            }
            String campo = "metas[" + i + "]";
            Meta meta = metas.resolver(dono, texto.strip(), campo);
            if (!meta.emAndamento()) {
                throw ErroTipado.validacao(campo, "A meta '" + meta.nome() + "' está " + meta.situacao().codigo() + ": só meta ativa é protegida.");
            }
            if (meta.dataAlvo() == null) {
                throw ErroTipado.validacao(campo, "A meta '" + meta.nome() + "' não tem data alvo, e sem data não há o que atrasar. Pergunte ao "
                        + "dono até quando, e grave com definir_meta antes.");
            }
            if (meta.dataAlvo().isBefore(hoje)) {
                throw ErroTipado.validacao(campo, "A data alvo da meta '" + meta.nome() + "' (" + meta.dataAlvo() + ") já passou. Pergunte ao "
                        + "dono a nova data, e grave com definir_meta antes.");
            }
            ids.add(meta.id());
        }
        return new ValorDeclaracao.MetaProtegida(ids);
    }

    private static ValorDeclaracao nota(Entrada e) {
        if (Argumentos.vazio(e.texto())) {
            throw ErroTipado.camposFaltando(List.of("texto"), "Pergunte ao dono o que ele quer que fique anotado. A nota é uma só: para "
                    + "acrescentar, reescreva a nota inteira, com o que já havia.");
        }
        return new ValorDeclaracao.Nota(e.texto());
    }

    private static String proximoPasso(TipoDeclaracao tipo) {
        return switch (tipo) {
            case PISO_PERIODO -> "Para saber se o mês fecha acima do piso e o que cortar, chame cortes_necessarios.";
            case CATEGORIAS_ESSENCIAIS -> "As essenciais só entram no plano de cortes_necessarios depois das outras; as subcategorias delas também.";
            case RESERVA_MINIMA -> "situacao avisa (reserva_abaixo_projetada) quando o saldo projetado fica abaixo da reserva, e projetar "
                    + "mostra os meses abaixo dela. Em meses de gasto, mostre ao dono o valor equivalente e de que meses ele veio.";
            case INTENCAO_POUPANCA -> "situacao avisa (poupanca_abaixo) quando a poupança dos últimos 3 meses fechados fica abaixo da "
                    + "declarada, e ritmo_gastos mostra as duas.";
            case TETO_PARCELAS -> "situacao avisa (parcelas_acima_teto) quando as parcelas de um mês passam do teto, e projetar mostra o "
                    + "comprometimento de cada mês e as violações de um cenário.";
            case META_PROTEGIDA -> "Uma meta protegida atrasada é crítica em situacao (meta_atrasada), e projetar aponta o cenário que a atrasa. "
                    + "cortes_necessarios não a lê: não diga ao dono que os planos de corte a poupam.";
            case NOTA -> "A nota sai em obter_contexto; nenhum cálculo a lê.";
        };
    }
}
