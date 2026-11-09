package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ChaveIdempotencia;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.Meta;
import tools.alicerce.financeiro.dominio.SituacaoMeta;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * Capacidade {@code definir_meta}: cria ou altera uma meta de valor. Nada aqui mexe em dinheiro: o progresso vem
 * dos aportes, que são transferências com {@code meta}.
 */
@Component
public class DefinirMeta {

    /**
     * @param valorAlvo quanto juntar, como {@code "6000.00"}
     * @param dataAlvo AAAA-MM-DD; ao alterar, texto vazio tira a data
     * @param contaReservada a conta (reserva ou poupança) separada para a meta; ao alterar, texto vazio tira a reserva
     * @param situacao {@code ativa}, {@code concluida} ou {@code encerrada}
     */
    public record Entrada(String chaveIdempotencia, String lote, String ref, String nome, List<String> aliases, String valorAlvo,
            String dataAlvo, Integer prioridade, String contaReservada, String situacao, String observacao) {}

    static final String CAPACIDADE = "definir_meta";

    private final RegistradorOperacoes registrador;
    private final RepositorioMetas repositorio;
    private final RepositorioDono donos;
    private final Referencias referencias;
    private final Metas metas;

    DefinirMeta(RegistradorOperacoes registrador, RepositorioMetas repositorio, RepositorioDono donos, Referencias referencias, Metas metas) {
        this.registrador = registrador;
        this.repositorio = repositorio;
        this.donos = donos;
        this.referencias = referencias;
        this.metas = metas;
    }

    public ResultadoEscrita<MetaVista> executar(Chamador chamador, Entrada e) {
        Map<String, ?> argumentos = new Argumentos().com("ref", e.ref()).com("nome", e.nome()).com("aliases", Argumentos.lista(e.aliases()))
                .com("valor_alvo", e.valorAlvo()).com("data_alvo", e.dataAlvo()).com("prioridade", e.prioridade())
                .com("conta_reservada", e.contaReservada()).com("situacao", e.situacao()).com("observacao", e.observacao()).mapa();
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, argumentos, ChaveIdempotencia.ler(e.chaveIdempotencia()), e.lote());
        return registrador.registrar(chamador, pedido, MetaVista.class, operacao -> {
            Meta m = Argumentos.vazio(e.ref()) ? criar(operacao, e) : alterar(operacao, e);
            UUID dono = operacao.dono();
            return metas.ver(dono, m, metas.progresso(m.id(), dono), metas.hoje(dono));
        });
    }

    private Meta criar(OperacaoEmCurso operacao, Entrada e) {
        List<String> faltando = new ArrayList<>();
        if (Argumentos.vazio(e.nome())) {
            faltando.add("nome");
        }
        if (Argumentos.vazio(e.valorAlvo())) {
            faltando.add("valor_alvo");
        }
        if (!faltando.isEmpty()) {
            throw ErroTipado.camposFaltando(faltando, "Pergunte ao dono para que é a meta, quanto ele quer juntar e, se tiver, até quando.");
        }
        UUID dono = operacao.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        semNomeRepetido(dono, null, e.nome());
        Meta m = montar(dono, UUID.randomUUID(), dados.moeda().getCurrencyCode(), e, null);
        repositorio.inserir(operacao, m);
        operacao.entidadeCriada(EntidadeVersionada.META, m.id(), m);
        operacao.referenciar(TipoReferencia.META, m.id());
        return m;
    }

    private Meta alterar(OperacaoEmCurso operacao, Entrada e) {
        UUID dono = operacao.dono();
        Meta atual = metas.resolver(dono, e.ref(), "ref");
        if (!Argumentos.vazio(e.nome())) {
            semNomeRepetido(dono, atual.id(), e.nome());
        }
        Meta nova = montar(dono, atual.id(), atual.moeda(), e, atual);
        repositorio.atualizar(operacao, nova);
        operacao.entidadeAlterada(EntidadeVersionada.META, nova.id(), atual.versao(), nova);
        return nova;
    }

    /** A meta com o que veio na entrada e, ao alterar, o resto como estava. */
    private Meta montar(UUID dono, UUID id, String moeda, Entrada e, Meta a) {
        String nome = Argumentos.vazio(e.nome()) ? a.nome() : e.nome().strip();
        List<String> aliases = e.aliases() == null ? a == null ? List.of() : a.aliases()
                : e.aliases().stream().map(String::strip).filter(x -> !x.isEmpty()).distinct().toList();
        long alvo = Argumentos.vazio(e.valorAlvo()) ? a.alvoCentavos()
                : RegistroDeLancamento.positivo(e.valorAlvo().strip(), Currency.getInstance(moeda), "valor_alvo").centavos();
        LocalDate dataAlvo = a == null ? null : a.dataAlvo();
        if (e.dataAlvo() != null) {
            dataAlvo = e.dataAlvo().isBlank() ? null : Datas.ler(e.dataAlvo().strip(), "data_alvo");
            if (dataAlvo != null && dataAlvo.isBefore(metas.hoje(dono))) {
                throw ErroTipado.validacao("data_alvo", "A data alvo já passou: use hoje ou uma data futura.");
            }
        }
        int prioridade = e.prioridade() != null ? e.prioridade() : a == null ? Meta.PRIORIDADE_PADRAO : a.prioridade();
        if (prioridade < Meta.PRIORIDADE_MINIMA || prioridade > Meta.PRIORIDADE_MAXIMA) {
            throw ErroTipado.validacao("prioridade", "A prioridade vai de 1 (a mais importante) a 5.");
        }
        SituacaoMeta situacao = Argumentos.vazio(e.situacao()) ? a == null ? SituacaoMeta.ATIVA : a.situacao()
                : SituacaoMeta.deCodigo(e.situacao().strip()).orElseThrow(() ->
                        ErroTipado.validacao("situacao", "Situação inválida: use ativa, concluida ou encerrada."));
        UUID reservada = a == null ? null : a.contaReservada();
        if (e.contaReservada() != null) {
            reservada = e.contaReservada().isBlank() ? null : contaReservada(dono, id, moeda, e.contaReservada());
        }
        String observacao = e.observacao() == null ? a == null ? null : a.observacao() : e.observacao().isBlank() ? null : e.observacao().strip();
        return new Meta(id, nome, aliases, alvo, moeda, dataAlvo, prioridade, reservada, situacao, observacao, false,
                a == null ? 1 : a.versao() + 1);
    }

    /** Reserva ou poupança na moeda da meta, ainda sem outra meta em andamento. */
    private UUID contaReservada(UUID dono, UUID propria, String moeda, String texto) {
        Conta conta = referencias.conta(dono, texto, "conta_reservada", c -> c.tipo().temSaldo());
        if (!Metas.RESERVAVEIS.contains(conta.tipo())) {
            throw ErroTipado.validacao("conta_reservada", "A conta reservada é uma conta do tipo reserva ou poupança; '" + conta.nome()
                    + "' é " + conta.tipo().codigo() + ".");
        }
        if (!conta.moeda().equals(moeda)) {
            throw ErroTipado.validacao("conta_reservada", "A conta reservada precisa estar na moeda da meta (" + moeda + ").");
        }
        metas.emAndamento(dono).stream().filter(m -> !m.id().equals(propria) && conta.id().equals(m.contaReservada())).findFirst()
                .ifPresent(outra -> {
                    throw ErroTipado.de(CodigoErro.CONFLITO, "conta_reservada", "A conta '" + conta.nome() + "' já está reservada à meta '"
                            + outra.nome() + "'.");
                });
        return conta.id();
    }

    private void semNomeRepetido(UUID dono, UUID propria, String nome) {
        String procurado = Referencias.normalizar(nome);
        boolean repetido = metas.visiveis(dono).stream()
                .anyMatch(m -> !m.id().equals(propria) && Referencias.normalizar(m.nome()).equals(procurado));
        if (repetido) {
            throw ErroTipado.de(CodigoErro.CONFLITO, "nome", "Já existe uma meta '" + nome.strip() + "'.");
        }
    }
}
