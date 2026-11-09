package tools.alicerce.financeiro.aplicacao;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.EfeitoReferenciado;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.Filtro;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.FiltroRecusas;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.Operacao;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.Posicao;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.PosicaoRecusa;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.Recusa;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * A trilha de auditoria da interface web: as operações e as tentativas recusadas numa lista só,
 * quem fez, quando no fuso do dono, e o que cada operação mudou. Só lê.
 */
@Component
public class TrilhaAuditoria {

    /**
     * Filtros da lista, vindos da URL; nulo não filtra.
     *
     * @param incluirRelatorios mostra também as leituras que guardam retrato
     */
    public record Pedido(String cliente, String de, String ate, boolean incluirRelatorios, String cursor) {}

    /**
     * Uma linha da lista: uma operação ou uma recusa.
     *
     * @param horario a data e a hora no fuso do dono, {@code 2026-11-05T14:26}
     * @param url só a operação tem; a recusa não tem tela nem id curto
     */
    public record Evento(String horario, String agente, String cliente, String descricao, String estado, String url, boolean recusada) {}

    public record Pagina(List<Evento> eventos, String proximoCursor) {}

    /** Um registro que a operação mudou; {@code id} e {@code url} faltam quando o registro não tem endereço. */
    public record Afetado(String acao, String registro, String id, String url) {}

    static final int TAMANHO = 30;

    private static final Clock RELOGIO = Clock.systemUTC();
    private static final DateTimeFormatter MINUTO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final UUID MAIOR_UUID = new UUID(-1L, -1L);

    private static final Map<String, String> AGENTES = Map.of("claude-ai", "Claude", "chatgpt", "ChatGPT", "claude-code", "Claude Code",
            "app-web", "Interface web", "pagina-envio", "Página de envio de comprovante");

    /** A capacidade como o dono a lê, com acento; uma desconhecida perde só o sublinhado. */
    private static final Map<String, String> CAPACIDADES = Map.ofEntries(
            Map.entry("anexar_comprovante", "anexar comprovante"),
            Map.entry("conferir_saldo", "conferir saldo"),
            Map.entry("corrigir_lancamento", "corrigir lançamento"),
            Map.entry("definir_ambito", "definir âmbito"),
            Map.entry("definir_compromisso", "definir compromisso"),
            Map.entry("definir_meta", "definir meta"),
            Map.entry("declarar", "declarar"),
            Map.entry("prestar_contas", "prestar contas"),
            Map.entry("cortes_necessarios", "cortes necessários"),
            Map.entry("projetar", "projetar"),
            Map.entry("definir_conta", "definir conta"),
            Map.entry("definir_meio_pagamento", "definir meio de pagamento"),
            Map.entry("desfazer", "desfazer"),
            Map.entry("enviar_comprovante", "enviar comprovante"),
            Map.entry("registrar_ganho", "registrar ganho"),
            Map.entry("registrar_gasto", "registrar gasto"),
            Map.entry("registrar_transferencia", "registrar transferência"),
            Map.entry("resolver_ocorrencia", "resolver ocorrência"),
            Map.entry("resumo_periodo", "resumo do período"),
            Map.entry("ritmo_gastos", "ritmo de gastos"),
            Map.entry("obter_contexto", "obter contexto"),
            Map.entry("listar_lancamentos", "listar lançamentos"),
            Map.entry("listar_operacoes", "listar operações"),
            Map.entry("listar_compromissos", "listar compromissos"),
            Map.entry("obter_agenda", "obter agenda"),
            Map.entry("obter_fatura", "obter fatura"),
            Map.entry("resolver_referencia", "resolver referência"),
            Map.entry("mostrar_vista", "mostrar vista"),
            Map.entry("situacao", "situação"));

    private static final Map<String, String> MOTIVOS = Map.ofEntries(
            Map.entry("validacao_falhou", "dados inválidos"),
            Map.entry("campos_faltando", "faltaram campos obrigatórios"),
            Map.entry("referencia_inexistente", "referência inexistente"),
            Map.entry("referencia_ambigua", "referência ambígua"),
            Map.entry("possivel_duplicata", "possível duplicata"),
            Map.entry("nota_duplicada", "nota fiscal já registrada"),
            Map.entry("conflito_idempotencia", "chave de repetição já usada em outro pedido"),
            Map.entry("violacao_invariante", "quebraria uma regra do livro-razão"),
            Map.entry("nao_encontrado", "não encontrado"),
            Map.entry("conflito", "conflito com outra alteração"),
            Map.entry("modulo_errado", "endereço de outro módulo"),
            Map.entry("desfazer_bloqueado", "desfazer bloqueado"),
            Map.entry("ja_desfeita", "já estava desfeita"),
            Map.entry("confirmacao_expirada", "confirmação expirada"),
            Map.entry("confirmacao_invalida", "confirmação inválida"),
            Map.entry("insufficient_scope", "sem permissão para esta ação"),
            Map.entry("erro_interno", "erro interno"));

    private static final Map<TipoEfeito, String> ACOES = Map.ofEntries(
            Map.entry(TipoEfeito.LANCAMENTO_CRIADO, "criado"),
            Map.entry(TipoEfeito.LANCAMENTO_ESTORNADO, "estornado"),
            Map.entry(TipoEfeito.CHAVE_FISCAL_MOVIDA, "nota fiscal movida"),
            Map.entry(TipoEfeito.ENTIDADE_CRIADA, "criado"),
            Map.entry(TipoEfeito.ENTIDADE_ALTERADA, "alterado"),
            Map.entry(TipoEfeito.OPERACAO_DESFEITA, "desfeita"),
            Map.entry(TipoEfeito.OPERACAO_RESTABELECIDA, "restabelecida"),
            Map.entry(TipoEfeito.COMPROVANTE_VINCULADO, "vinculado"),
            Map.entry(TipoEfeito.TOKEN_ANEXO_EMITIDO, "emitido"),
            Map.entry(TipoEfeito.RETRATO_GRAVADO, "gravado"),
            Map.entry(TipoEfeito.FATURA_CRIADA, "criada"),
            Map.entry(TipoEfeito.CONFERENCIA_REGISTRADA, "registrada"),
            Map.entry(TipoEfeito.OCORRENCIA_REGISTRADA, "registrada"),
            Map.entry(TipoEfeito.DECLARACAO_REGISTRADA, "registrada"),
            Map.entry(TipoEfeito.LINK_CRIADO, "criado"),
            Map.entry(TipoEfeito.LINK_REVOGADO, "revogado"));

    private static final Map<String, String> REGISTROS = Map.ofEntries(
            Map.entry("lancamento", "lançamento"),
            Map.entry("conta", "conta"),
            Map.entry("cartao_credito", "cartão"),
            Map.entry("ambito", "âmbito"),
            Map.entry("meio_pagamento", "meio de pagamento"),
            Map.entry("instituicao", "instituição"),
            Map.entry("compromisso", "compromisso"),
            Map.entry("meta", "meta"),
            Map.entry("operacao", "operação"),
            Map.entry("comprovante", "comprovante"),
            Map.entry("token_anexo", "link de envio de comprovante"),
            Map.entry("retrato", "retrato"),
            Map.entry("fatura", "fatura"),
            Map.entry("conferencia", "conferência de saldo"),
            Map.entry("ocorrencia_compromisso", "ocorrência de compromisso"),
            Map.entry("declaracao", "declaração do perfil"),
            Map.entry("link_compartilhamento", "link de prestação de contas"));

    private final RepositorioHistorico historico;
    private final RepositorioDono donos;
    private final ResumoOperacao resumo;
    private final Referencias referencias;
    private final Enderecos enderecos;

    TrilhaAuditoria(RepositorioHistorico historico, RepositorioDono donos, ResumoOperacao resumo, Referencias referencias, Enderecos enderecos) {
        this.historico = historico;
        this.donos = donos;
        this.resumo = resumo;
        this.referencias = referencias;
        this.enderecos = enderecos;
    }

    /** O nome que o dono reconhece; um cliente desconhecido aparece pelo código. */
    public static String agente(String cliente) {
        return AGENTES.getOrDefault(cliente, cliente);
    }

    /** O motivo da recusa em texto; um código desconhecido aparece como veio. */
    static String motivo(String codigoErro) {
        return MOTIVOS.getOrDefault(codigoErro, codigoErro);
    }

    public ZoneId fuso(Chamador chamador) {
        return donos.buscar(chamador.dono()).orElseThrow().fuso();
    }

    /** O instante no fuso do dono, até o minuto: {@code 2026-11-05T14:26}. */
    public static String horario(String instante, ZoneId fuso) {
        return OffsetDateTime.parse(instante).atZoneSameInstant(fuso).format(MINUTO);
    }

    /** Hoje, no fuso do dono. */
    public LocalDate hoje(Chamador chamador) {
        return LocalDate.now(RELOGIO.withZone(fuso(chamador)));
    }

    public List<String> clientes(Chamador chamador) {
        return historico.clientes(chamador.dono());
    }

    /**
     * As operações e as recusas, juntas, das mais recentes às mais antigas. Na mesma hora, a recusa vem
     * antes. O cursor guarda a última linha mostrada, e cada fonte continua de onde parou, sem perder nem
     * repetir.
     */
    public Pagina eventos(Chamador chamador, Pedido p) {
        UUID dono = chamador.dono();
        ZoneId fuso = fuso(chamador);
        OffsetDateTime de = inicio(p.de(), "de", fuso, 0);
        OffsetDateTime ate = inicio(p.ate(), "ate", fuso, 1);
        String cliente = Argumentos.vazio(p.cliente()) ? null : p.cliente().strip();
        Set<String> ocultas = p.incluirRelatorios() ? Set.of() : ListarOperacoes.SO_RETRATO;
        Posicao depoisOperacao = null;
        PosicaoRecusa depoisRecusa = null;
        if (!Argumentos.vazio(p.cursor())) {
            List<String> partes = Cursores.ler(p.cursor(), 3);
            try {
                OffsetDateTime em = OffsetDateTime.parse(partes.get(0));
                if (partes.get(1).equals("r")) {
                    long id = Long.parseLong(partes.get(2));
                    depoisRecusa = new PosicaoRecusa(em, id);
                    depoisOperacao = new Posicao(em, MAIOR_UUID); // as operações da mesma hora ainda não saíram
                } else if (partes.get(1).equals("o")) {
                    depoisOperacao = new Posicao(em, UUID.fromString(partes.get(2)));
                    depoisRecusa = new PosicaoRecusa(em, Long.MIN_VALUE); // as recusas da mesma hora já saíram
                } else {
                    throw Cursores.invalido();
                }
            } catch (DateTimeParseException | IllegalArgumentException invalido) {
                throw Cursores.invalido();
            }
        }
        List<Operacao> operacoes = historico.listar(dono, new Filtro(null, cliente, de, ate, null, ocultas, true, depoisOperacao), TAMANHO + 1);
        List<Recusa> recusas = historico.recusas(dono, new FiltroRecusas(cliente, de, ate, ocultas, depoisRecusa), TAMANHO + 1);

        List<Evento> eventos = new ArrayList<>();
        String ultimo = null;
        int o = 0;
        int r = 0;
        while (eventos.size() < TAMANHO && (o < operacoes.size() || r < recusas.size())) {
            boolean recusa = o >= operacoes.size()
                    || (r < recusas.size() && !recusas.get(r).ocorridaEm().isBefore(operacoes.get(o).criadaEm()));
            if (recusa) {
                Recusa x = recusas.get(r++);
                eventos.add(new Evento(horario(x.ocorridaEm().toString(), fuso), agente(x.cliente()), x.cliente(),
                        "tentativa de " + CAPACIDADES.getOrDefault(x.capacidade(), x.capacidade().replace('_', ' ')) + ": " + motivo(x.codigoErro()), "Recusada", null, true));
                ultimo = Cursores.escrever(x.ocorridaEm().toString(), "r", Long.toString(x.id()));
            } else {
                OperacaoVista x = resumo.ver(operacoes.get(o));
                Operacao gravada = operacoes.get(o++);
                eventos.add(new Evento(horario(x.horario(), fuso), agente(x.cliente()), x.cliente(), x.resumo(),
                        x.estado().equals("desfeita") ? "Desfeita" : "Aplicada", x.url(), false));
                ultimo = Cursores.escrever(gravada.criadaEm().toString(), "o", gravada.id().toString());
            }
        }
        boolean temMais = o < operacoes.size() || r < recusas.size();
        return new Pagina(eventos, temMais ? ultimo : null);
    }

    /** O que a operação do dono mudou, efeito por efeito, com o endereço do registro quando ele tem um. */
    public List<Afetado> afetados(Chamador chamador, String refOperacao) {
        UUID operacao = referencias.operacao(chamador.dono(), refOperacao, "ref");
        List<Afetado> afetados = new ArrayList<>();
        for (EfeitoReferenciado e : historico.efeitosReferenciados(operacao)) {
            String url = e.referencia() == null ? null
                    : ReferenciaCurta.ler(e.referencia(), TipoReferencia.MODULO).map(enderecos::url).orElse(null);
            afetados.add(new Afetado(ACOES.getOrDefault(e.tipo(), e.tipo().codigo()), REGISTROS.getOrDefault(e.entidade(), e.entidade()),
                    url == null ? null : e.referencia(), url));
        }
        return afetados;
    }

    /**
     * A operação que criou o registro e a última que o mudou, como URLs; a segunda falta quando é a mesma.
     *
     * @return lista vazia, de um ou de dois itens
     */
    public List<String> origemDoRegistro(Chamador chamador, String referencia) {
        List<String> codigos = historico.operacoesDoRegistro(chamador.dono(), referencia);
        List<String> urls = new ArrayList<>();
        if (!codigos.isEmpty()) {
            urls.add(urlDe(codigos.get(0)));
            if (codigos.size() > 1) {
                urls.add(urlDe(codigos.get(codigos.size() - 1)));
            }
        }
        return urls;
    }

    private String urlDe(String codigo) {
        return enderecos.url(ReferenciaCurta.ler(codigo, TipoReferencia.MODULO).orElseThrow());
    }

    /** O começo do dia no fuso do dono, somando {@code dias}: {@code ate} inclui o dia inteiro. */
    private static OffsetDateTime inicio(String texto, String campo, ZoneId fuso, int dias) {
        if (Argumentos.vazio(texto)) {
            return null;
        }
        return Datas.ler(texto.strip(), campo).plusDays(dias).atStartOfDay(fuso).toOffsetDateTime();
    }
}
