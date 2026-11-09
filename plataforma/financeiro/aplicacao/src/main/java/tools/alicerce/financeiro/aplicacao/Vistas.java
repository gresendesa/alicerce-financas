package tools.alicerce.financeiro.aplicacao;

import static tools.alicerce.financeiro.aplicacao.TelaPadrao.coluna;
import static tools.alicerce.financeiro.aplicacao.TelaPadrao.colunaLink;
import static tools.alicerce.financeiro.aplicacao.TelaPadrao.mapa;
import static tools.alicerce.financeiro.aplicacao.TelaPadrao.ref;
import static tools.alicerce.financeiro.aplicacao.TelaPadrao.total;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.convencoes.vista.CatalogoVista;
import tools.alicerce.convencoes.vista.ValidadorVista;
import tools.alicerce.financeiro.aplicacao.Contexto.ContaContexto;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Abre os endereços estáveis da interface web e as vistas compostas pelo assistente. Só lê: todo
 * dado sai das capacidades de leitura, com o dono do chamador. A descrição padrão de cada tela é este
 * código versionado, por isso um endereço abre sempre igual.
 */
@Component
public class Vistas {

    /** O que acompanha a abertura de um endereço, vindo da URL. */
    public record Pedido(String caminho, String cursor, String de, String ate, String ambito, String cliente, String incluirRelatorios) {

        public Pedido(String caminho, String cursor, String de, String ate, String ambito) {
            this(caminho, cursor, de, ate, ambito, null, null);
        }
    }

    /**
     * Uma vista composta pelo assistente.
     *
     * @param endereco abre a tela padrão do endereço, em vez de compor
     * @param fontes de onde vêm os dados, cada uma com {@code id}, {@code capacidade} e os argumentos dela
     */
    public record Composicao(String endereco, String raiz, List<Map<String, Object>> componentes, List<Map<String, Object>> fontes) {}

    private static final int MAX_FONTES = 8;
    private static final Pattern ID_FONTE = Pattern.compile("[a-z][a-z0-9_]{0,30}");
    private static final List<String> CAPACIDADES = List.of("obter_contexto", "listar_lancamentos", "obter_fatura",
            "resolver_referencia", "resumo_periodo", "listar_operacoes", "listar_compromissos", "obter_agenda");
    private static final Map<String, Set<String>> ARGUMENTOS = Map.of(
            "listar_compromissos", Set.of("texto", "pessoa", "ambito", "direcao"),
            "obter_agenda", Set.of("de", "ate", "periodo"),
            "obter_contexto", Set.of(),
            "listar_lancamentos", Set.of("de", "ate", "conta", "categoria", "texto", "cursor", "procedencia"),
            "obter_fatura", Set.of("cartao", "periodo"),
            "resolver_referencia", Set.of("ref"),
            "resumo_periodo", Set.of("de", "ate", "periodo", "ambito", "visao"),
            "listar_operacoes", Set.of("de", "ate", "cursor"));
    private static final Map<String, String> TITULOS_QUEBRA = Map.of("categoria", "Por categoria", "conta", "Por conta",
            "meio_pagamento", "Por meio de pagamento", "ambito", "Por âmbito", "contraparte", "Por contraparte", "tag", "Por tag");
    private static final TypeReference<Map<String, Object>> MAPA = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LISTA = new TypeReference<>() {};

    private final JsonMapper json = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(i -> i.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();
    private final ObterContexto obterContexto;
    private final ListarLancamentos listarLancamentos;
    private final ListarOperacoes listarOperacoes;
    private final ObterFatura obterFatura;
    private final ResolverReferencia resolverReferencia;
    private final ResumirPeriodo resumirPeriodo;
    private final Referencias referencias;
    private final Enderecos enderecos;
    private final ListarCompromissos listarCompromissos;
    private final ObterAgenda obterAgenda;
    private final TrilhaAuditoria trilha;
    private final Metas metas;

    Vistas(ObterContexto obterContexto, ListarLancamentos listarLancamentos, ListarOperacoes listarOperacoes, ObterFatura obterFatura,
            ResolverReferencia resolverReferencia, ResumirPeriodo resumirPeriodo, Referencias referencias, Enderecos enderecos,
            ListarCompromissos listarCompromissos, ObterAgenda obterAgenda, TrilhaAuditoria trilha, Metas metas) {
        this.metas = metas;
        this.trilha = trilha;
        this.listarCompromissos = listarCompromissos;
        this.obterAgenda = obterAgenda;
        this.obterContexto = obterContexto;
        this.listarLancamentos = listarLancamentos;
        this.listarOperacoes = listarOperacoes;
        this.obterFatura = obterFatura;
        this.resolverReferencia = resolverReferencia;
        this.resumirPeriodo = resumirPeriodo;
        this.referencias = referencias;
        this.enderecos = enderecos;
    }

    public Vista abrir(Chamador chamador, Pedido p) {
        String caminho = p.caminho() == null ? "" : p.caminho().strip();
        return switch (caminho) {
            case "", "/", "/fin", "/fin/contas" -> contas(chamador);
            case "/fin/lancamentos" -> lancamentos(chamador, p.cursor());
            case "/fin/operacoes" -> operacoes(chamador, p);
            case "/fin/relatorios" -> relatorio(chamador, p);
            case "/fin/compromissos" -> compromissos(chamador);
            case "/fin/agenda" -> agenda(chamador, p);
            default -> porReferencia(chamador, caminho, p.cursor());
        };
    }

    /** A tela do endereço pedido, ou uma vista nova sobre as fontes pedidas. */
    public Vista compor(Chamador chamador, Composicao c) {
        if (!Argumentos.vazio(c.endereco())) {
            return abrir(chamador, new Pedido(semOrigem(c.endereco()), null, null, null, null));
        }
        if (c.componentes() == null || c.fontes() == null || c.fontes().isEmpty()) {
            throw ErroTipado.camposFaltando(List.of("componentes", "fontes"), "Passe endereco para a tela padrão, ou os componentes e as fontes.");
        }
        if (c.fontes().size() > MAX_FONTES) {
            throw ErroTipado.validacao("fontes", "Use até " + MAX_FONTES + " fontes.");
        }
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("moeda", obterContexto.executar(chamador).moeda());
        List<Map<String, Object>> fontes = new ArrayList<>();
        for (Map<String, Object> fonte : c.fontes()) {
            String id = fonte.get("id") instanceof String texto ? texto : "";
            if (!ID_FONTE.matcher(id).matches() || dados.containsKey(id)) {
                throw ErroTipado.validacao("fontes", "Cada fonte precisa de um id único, em minúsculas, sem ser moeda.");
            }
            dados.put(id, lerFonte(chamador, fonte));
            fontes.add(fonte);
        }
        ValidadorVista.validar(c.raiz(), c.componentes(), dados);
        return new Vista(CatalogoVista.ID, c.raiz(), c.componentes(), dados, fontes);
    }

    private Map<String, Object> lerFonte(Chamador chamador, Map<String, Object> fonte) {
        String capacidade = fonte.get("capacidade") instanceof String texto ? texto : "";
        if (!ARGUMENTOS.containsKey(capacidade)) {
            throw ErroTipado.validacao("fontes", "Capacidade fora da lista: use " + CAPACIDADES + ".");
        }
        for (String chave : fonte.keySet()) {
            if (!chave.equals("id") && !chave.equals("capacidade") && !ARGUMENTOS.get(capacidade).contains(chave)) {
                throw ErroTipado.validacao("fontes", "Argumento " + chave + " não vale para " + capacidade + ".");
            }
        }
        return mapaDe(switch (capacidade) {
            case "obter_contexto" -> obterContexto.executar(chamador);
            case "listar_lancamentos" -> listarLancamentos.executar(chamador, new ListarLancamentos.Entrada(arg(fonte, "de"), arg(fonte, "ate"),
                    arg(fonte, "conta"), arg(fonte, "categoria"), null, arg(fonte, "texto"), null, null, null, arg(fonte, "cursor"),
                    arg(fonte, "procedencia")));
            case "obter_fatura" -> obterFatura.executar(chamador, arg(fonte, "cartao"), arg(fonte, "periodo"));
            case "resolver_referencia" -> resolverReferencia.resolver(chamador, arg(fonte, "ref"));
            case "resumo_periodo" -> resumirPeriodo.calcularAgora(chamador, new ResumirPeriodo.Entrada(arg(fonte, "de"), arg(fonte, "ate"),
                    arg(fonte, "periodo"), null, arg(fonte, "ambito"), null, arg(fonte, "visao"), null));
            case "listar_compromissos" -> listarCompromissos.executar(chamador, new ListarCompromissos.Entrada(arg(fonte, "texto"),
                    arg(fonte, "pessoa"), arg(fonte, "ambito"), arg(fonte, "direcao"), null));
            case "obter_agenda" -> obterAgenda.executar(chamador, new ObterAgenda.Entrada(arg(fonte, "de"), arg(fonte, "ate"), arg(fonte, "periodo")));
            default -> listarOperacoes.executar(chamador, new ListarOperacoes.Entrada(null, null, arg(fonte, "de"), arg(fonte, "ate"),
                    null, null, arg(fonte, "cursor")));
        });
    }

    private static String arg(Map<String, Object> fonte, String nome) {
        Object valor = fonte.get(nome);
        if (valor != null && !(valor instanceof String)) {
            throw ErroTipado.validacao("fontes", "O argumento " + nome + " precisa ser um texto.");
        }
        return (String) valor;
    }

    private String semOrigem(String endereco) {
        String texto = endereco.strip();
        int inicio = texto.indexOf("/fin/");
        return texto.startsWith("http") && inicio > 0 ? texto.substring(inicio) : texto;
    }

    private Vista porReferencia(Chamador chamador, String caminho, String cursor) {
        ReferenciaCurta referencia = ReferenciaCurta.ler(caminho, TipoReferencia.MODULO).orElseThrow(() -> naoEncontrado(caminho));
        String prefixo = referencia.prefixo();
        if (prefixo.equals(TipoReferencia.CONTA.prefixo())) {
            return conta(chamador, referencia, cursor);
        }
        if (prefixo.equals(TipoReferencia.OPERACAO.prefixo())) {
            return operacao(chamador, referencia);
        }
        if (prefixo.equals(TipoReferencia.COMPROMISSO.prefixo())) {
            return compromisso(chamador, referencia);
        }
        if (prefixo.equals(TipoReferencia.META.prefixo())) {
            return meta(chamador, referencia);
        }
        if (prefixo.equals(TipoReferencia.LANCAMENTO.prefixo())) {
            return lancamento(chamador, resolverReferencia.executar(chamador, referencia.id()));
        }
        Object visto = prefixo.equals(TipoReferencia.FATURA.prefixo()) || prefixo.equals(TipoReferencia.RETRATO.prefixo())
                || prefixo.equals(TipoReferencia.COMPROVANTE.prefixo()) ? resolverReferencia.resolver(chamador, referencia.id()) : null;
        return switch (visto) {
            case FaturaVista fatura -> fatura(chamador, fatura);
            case RetratoVisto retrato -> retrato(chamador, retrato);
            case ComprovanteVisto comprovante -> comprovante(chamador, comprovante);
            case null, default -> throw naoEncontrado(caminho);
        };
    }

    private static ErroTipado naoEncontrado(String caminho) {
        return ErroTipado.de(CodigoErro.NAO_ENCONTRADO, "caminho", "Nada foi encontrado em " + caminho + ".");
    }

    private Vista contas(Chamador chamador) {
        Contexto contexto = obterContexto.executar(chamador);
        List<Map<String, Object>> contas = new ArrayList<>();
        for (ContaContexto conta : contexto.contas()) {
            if (conta.saldo() != null) {
                Map<String, Object> linha = mapaDe(conta);
                linha.put("url", urlDe(conta.id()));
                contas.add(linha);
            }
        }
        List<Map<String, Object>> cartoes = new ArrayList<>();
        for (Contexto.CartaoContexto cartao : contexto.cartoes()) {
            FaturaVista fatura = cartao.faturaAtual();
            Map<String, Object> linha = mapa("nome", cartao.nome(), "url", urlDe(cartao.id()), "limite_disponivel", cartao.limiteDisponivel(),
                    "fatura_total", fatura.total(), "fatura_vencimento", fatura.venceEm(), "fatura_url", fatura.url());
            linha.values().removeIf(java.util.Objects::isNull);
            cartoes.add(linha);
        }
        Map<String, Object> dados = dadosCom(contexto.moeda());
        dados.put("contas", contas);
        dados.put("cartoes", cartoes);
        dados.put("lancamentos_url", enderecos.urlLancamentos());
        dados.put("agenda_url", enderecos.urlAgenda());
        dados.put("compromissos_url", enderecos.urlCompromissos());
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        filhos.add(tela.tabela("Contas", "/contas", List.of(colunaLink("Conta", "nome", "url"), coluna("Tipo", "tipo", "texto"),
                coluna("Saldo", "saldo", "dinheiro"))));
        if (!cartoes.isEmpty()) {
            filhos.add(tela.tabela("Cartões e faturas atuais", "/cartoes", List.of(colunaLink("Cartão", "nome", "url"),
                    colunaLink("Fatura atual", "fatura_total", "fatura_url", "dinheiro"), coluna("Vencimento", "fatura_vencimento", "data"))));
        }
        filhos.add(tela.componente("link", "rotulo", "Ver todos os lançamentos", "url", ref("/lancamentos_url")));
        filhos.add(tela.componente("link", "rotulo", "Agenda: o que vence e o que está atrasado", "url", ref("/agenda_url")));
        filhos.add(tela.componente("link", "rotulo", "Compromissos a pagar e a receber", "url", ref("/compromissos_url")));
        return tela.pagina("Contas", filhos);
    }

    private Vista compromissos(Chamador chamador) {
        ListarCompromissos.Pagina pagina = listarCompromissos.executar(chamador, new ListarCompromissos.Entrada(null, null, null, null, null));
        Map<String, Object> dados = dadosCom(obterContexto.executar(chamador).moeda());
        List<Map<String, Object>> linhas = new ArrayList<>();
        for (CompromissoVisto c : pagina.compromissos()) {
            Map<String, Object> linha = mapa("nome", c.nome(), "url", c.url(), "direcao", c.direcao().equals("saida") ? "a pagar" : "a receber",
                    "referencia", c.referencia(), "contraparte", c.contraparte(), "valor", c.valor(), "moeda", c.moeda(),
                    "proximo_vencimento", c.proxima() == null ? null : c.proxima().vencimento(), "valor_restante", c.saldo().valorRestante(),
                    "situacao", c.situacao());
            linha.values().removeIf(java.util.Objects::isNull);
            linhas.add(linha);
        }
        dados.put("compromissos", linhas);
        dados.put("agenda_url", enderecos.urlAgenda());
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        if (linhas.isEmpty()) {
            filhos.add(tela.texto("Nenhum compromisso ativo.", "normal"));
        }
        filhos.add(tela.tabela("Compromissos ativos", "/compromissos", List.of(colunaLink("Compromisso", "nome", "url"),
                coluna("Sentido", "direcao", "texto"), coluna("Referência", "referencia", "texto"), coluna("Valor", "valor", "dinheiro"),
                coluna("Próximo vencimento", "proximo_vencimento", "data"), coluna("Falta", "valor_restante", "dinheiro"))));
        filhos.add(tela.componente("link", "rotulo", "Ver a agenda", "url", ref("/agenda_url")));
        return tela.pagina("Compromissos", filhos);
    }

    private Vista compromisso(Chamador chamador, ReferenciaCurta referencia) {
        CompromissoVisto visto;
        try {
            visto = listarCompromissos.ver(chamador, referencia.id());
        } catch (ErroTipado inexistente) {
            throw naoEncontrado(referencia.caminho());
        }
        Map<String, Object> dados = dadosCom(visto.moeda());
        Map<String, Object> compromisso = mapaDe(visto);
        compromisso.put("sentido", visto.direcao().equals("saida") ? "a pagar" : "a receber");
        dados.put("compromisso", compromisso);
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        List<Map<String, Object>> itens = new ArrayList<>(List.of(total("Sentido", "/compromisso/sentido", "texto"),
                total("Tipo", "/compromisso/tipo", "texto"), total("Valor de cada ocorrência", "/compromisso/valor", "dinheiro"),
                total("Situação", "/compromisso/situacao", "texto")));
        if (visto.referencia() != null) {
            itens.add(total("Referência", "/compromisso/referencia", "texto"));
        }
        if (visto.contraparte() != null) {
            itens.add(total("Com quem", "/compromisso/contraparte", "texto"));
        }
        filhos.add(tela.totais("Dados do compromisso", itens));
        List<Map<String, Object>> saldo = new ArrayList<>(List.of(total("Pagas", "/compromisso/saldo/pagas", "numero"),
                total("Em aberto", "/compromisso/saldo/em_aberto", "numero"), total("Atrasadas", "/compromisso/saldo/atrasadas", "numero"),
                total("Valor pago", "/compromisso/saldo/valor_pago", "dinheiro")));
        if (visto.saldo().pagasEmParte() > 0) {
            saldo.add(total("Pagas em parte", "/compromisso/saldo/pagas_em_parte", "numero"));
        }
        if (visto.saldo().valorRestante() != null) {
            saldo.add(total("Valor restante", "/compromisso/saldo/valor_restante", "dinheiro"));
        }
        filhos.add(tela.totais("Quanto já foi e quanto falta", saldo));
        if (visto.saldo().atrasadas() > 0) {
            filhos.add(tela.texto("Há ocorrências vencidas sem o pagamento completo registrado.", "aviso"));
        }
        filhos.add(tela.enderecoCurto("/compromisso/id", "/compromisso/url"));
        filhos.add(tela.tabela("Ocorrências", "/compromisso/ocorrencias", List.of(coluna("Parcela", "parcela", "texto"),
                coluna("Vencimento", "vencimento", "data"), coluna("Previsto", "valor_previsto", "dinheiro"), coluna("Situação", "situacao", "texto"),
                colunaLink("Pago", "valor_pago", "url_lancamento", "dinheiro"), coluna("Falta", "falta_na_ocorrencia", "dinheiro"),
                coluna("Diferença", "diferenca", "dinheiro"))));
        filhos.addAll(origemDoRegistro(chamador, tela, compromisso, "/compromisso", referencia.id(), "Operação que criou"));
        return tela.pagina(ref("/compromisso/nome"), filhos);
    }

    /** A tela padrão da meta: o progresso, o que falta, o aporte necessário e cada aporte. */
    private Vista meta(Chamador chamador, ReferenciaCurta referencia) {
        MetaVista visto;
        try {
            visto = metas.consultar(chamador.dono(), referencia.id());
        } catch (ErroTipado inexistente) {
            throw naoEncontrado(referencia.caminho());
        }
        Map<String, Object> dados = dadosCom(visto.moeda());
        Map<String, Object> meta = mapaDe(visto);
        meta.put("situacao_texto", switch (visto.situacao()) {
            case "concluida" -> "Concluída";
            case "encerrada" -> "Encerrada";
            default -> "Ativa";
        });
        List<Map<String, Object>> aportes = new ArrayList<>();
        for (MetaVista.AporteVisto a : visto.aportes()) {
            Map<String, Object> linha = mapa("data", a.data(), "descricao", a.descricao(), "url", a.url(),
                    "tipo", a.tipo().equals("resgate") ? "Resgate" : "Aporte", "valor", a.valor());
            linha.values().removeIf(java.util.Objects::isNull);
            aportes.add(linha);
        }
        dados.put("meta", meta);
        dados.put("aportes", aportes);
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        List<Map<String, Object>> itens = new ArrayList<>(List.of(total("Valor alvo", "/meta/valor_alvo", "dinheiro"),
                total("Prioridade", "/meta/prioridade", "numero"), total("Situação", "/meta/situacao_texto", "texto")));
        if (visto.dataAlvo() != null) {
            itens.add(1, total("Data alvo", "/meta/data_alvo", "data"));
        }
        if (visto.contaReservada() != null) {
            itens.add(total("Conta reservada", "/meta/conta_reservada", "texto"));
        }
        filhos.add(tela.totais("Dados da meta", itens));
        List<Map<String, Object>> progresso = new ArrayList<>(List.of(total("Juntado até agora", "/meta/progresso", "dinheiro"),
                total("Falta", "/meta/falta", "dinheiro"), total("Percentual do alvo, em %", "/meta/percentual", "numero")));
        if (visto.aporteMensalNecessario() != null) {
            progresso.add(total("Aporte por mês para chegar na data", "/meta/aporte_mensal_necessario", "dinheiro"));
            progresso.add(total("Meses até a data, contando este", "/meta/meses_restantes", "numero"));
        }
        filhos.add(tela.totais("Quanto já foi e quanto falta", progresso));
        if (visto.prazoVencido()) {
            filhos.add(tela.texto("A data alvo já passou e a meta ainda não chegou no valor.", "aviso"));
        } else if (visto.alcancada()) {
            filhos.add(tela.texto("A meta já chegou no valor alvo.", "normal"));
        } else if (visto.aporteMensalNecessario() == null) {
            filhos.add(tela.texto("Sem data alvo, não há aporte mensal necessário.", "normal"));
        }
        filhos.add(tela.enderecoCurto("/meta/id", "/meta/url"));
        if (aportes.isEmpty()) {
            filhos.add(tela.texto("Nenhum aporte registrado ainda.", "normal"));
        } else {
            filhos.add(tela.tabela("Aportes e resgates", "/aportes", List.of(coluna("Data", "data", "data"),
                    colunaLink("Descrição", "descricao", "url"), coluna("Tipo", "tipo", "texto"), coluna("Valor", "valor", "dinheiro"))));
        }
        filhos.addAll(origemDoRegistro(chamador, tela, meta, "/meta", referencia.id(), "Operação que criou"));
        return tela.pagina(ref("/meta/nome"), filhos);
    }

    private Vista agenda(Chamador chamador, Pedido p) {
        Agenda visto = obterAgenda.executar(chamador, new ObterAgenda.Entrada(p.de(), p.ate(), null));
        Map<String, Object> agenda = mapaDe(visto);
        Map<String, Object> dados = dadosCom(visto.moeda());
        dados.put("agenda", agenda);
        dados.put("compromissos_url", enderecos.urlCompromissos());
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        filhos.add(tela.totais("Período", List.of(total("De", "/agenda/de", "data"), total("Até", "/agenda/ate", "data"))));
        filhos.add(tela.totais("Totais", List.of(total("A pagar atrasado", "/agenda/totais/a_pagar_atrasado", "dinheiro"),
                total("A receber atrasado", "/agenda/totais/a_receber_atrasado", "dinheiro"),
                total("A pagar no período", "/agenda/totais/a_pagar_no_periodo", "dinheiro"),
                total("A receber no período", "/agenda/totais/a_receber_no_periodo", "dinheiro"),
                total("Saldo previsto", "/agenda/totais/saldo_previsto", "dinheiro"))));
        filhos.add(tela.texto("Compra parcelada no cartão aparece só dentro da fatura, pelo que falta pagar dela.", "normal"));
        List<Map<String, Object>> colunas = List.of(coluna("Vencimento", "vencimento", "data"), colunaLink("O quê", "nome", "url"),
                coluna("Sentido", "direcao", "texto"), coluna("Parcela", "parcela", "texto"), coluna("Falta", "valor", "dinheiro"));
        if (!visto.atrasados().isEmpty()) {
            filhos.add(tela.texto("Há vencimentos atrasados.", "aviso"));
            filhos.add(tela.tabela("Atrasados", "/agenda/atrasados", colunas));
        }
        if (visto.vencimentos().isEmpty()) {
            filhos.add(tela.texto("Nada vence no período.", "normal"));
        } else {
            filhos.add(tela.tabela("Vencimentos do período", "/agenda/vencimentos", colunas));
        }
        if (!visto.conferenciasVencidas().isEmpty()) {
            filhos.add(tela.texto("Estas contas estão sem conferência com o banco há mais de um mês.", "aviso"));
            filhos.add(tela.tabela("Conferência vencida", "/agenda/conferencias_vencidas", List.of(colunaLink("Conta", "nome", "url"),
                    coluna("Última conferência", "ultima_conferencia", "data"), coluna("Valia até", "vale_ate", "data"))));
        }
        filhos.add(tela.componente("link", "rotulo", "Ver os compromissos", "url", ref("/compromissos_url")));
        return tela.pagina("Agenda", filhos);
    }

    private Vista lancamentos(Chamador chamador, String cursor) {
        return telaDeLancamentos(chamador, "Lançamentos", null, cursor, enderecos.urlLancamentos());
    }

    private Vista telaDeLancamentos(Chamador chamador, Object titulo, String conta, String cursor, String base) {
        Contexto contexto = obterContexto.executar(chamador);
        ListarLancamentos.Pagina pagina = listarLancamentos.executar(chamador,
                new ListarLancamentos.Entrada(null, null, conta, null, null, null, null, null, null, cursor, null));
        Map<String, Object> dados = dadosCom(contexto.moeda());
        dados.put("lancamentos", mapaDe(pagina).get("lancamentos"));
        if (pagina.proximoCursor() != null) {
            dados.put("proxima", base + "?cursor=" + pagina.proximoCursor());
        }
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>(List.of(tabelaLancamentos(tela)));
        if (pagina.proximoCursor() != null) {
            filhos.add(tela.componente("link", "rotulo", "Próxima página", "url", ref("/proxima")));
        }
        return tela.pagina(titulo, filhos);
    }

    private static String tabelaLancamentos(TelaPadrao tela) {
        return tela.tabela("Lançamentos, dos mais recentes aos mais antigos", "/lancamentos", List.of(coluna("Data", "data", "data"),
                colunaLink("Descrição", "descricao", "url"), coluna("Tipo", "tipo", "texto"), coluna("Valor", "valor", "dinheiro")));
    }

    private Vista conta(Chamador chamador, ReferenciaCurta referencia, String cursor) {
        Contexto contexto = obterContexto.executar(chamador);
        Conta conta;
        try {
            conta = referencias.contaParaConsultar(chamador.dono(), referencia.id(), "ref", c -> true);
        } catch (ErroTipado inexistente) {
            throw naoEncontrado(referencia.caminho());
        }
        Optional<ContaContexto> vista = contexto.contas().stream().filter(c -> referencia.id().equals(c.id())).findFirst();
        ListarLancamentos.Pagina pagina = listarLancamentos.executar(chamador, new ListarLancamentos.Entrada(null, null, referencia.id(), null, null,
                null, null, null, null, cursor, null));
        Map<String, Object> dados = dadosCom(contexto.moeda());
        Map<String, Object> dadosConta = mapa("nome", conta.nome(), "tipo", conta.tipo().codigo(), "id", referencia.id(), "url", enderecos.url(referencia));
        vista.map(ContaContexto::saldo).ifPresent(saldo -> dadosConta.put("saldo", saldo));
        vista.map(ContaContexto::marco).ifPresent(marco -> dadosConta.put("marco", marco));
        vista.map(ContaContexto::ultimaConferencia).ifPresent(data -> dadosConta.put("ultima_conferencia", data));
        vista.map(ContaContexto::situacaoConferencia).ifPresent(situacao -> dadosConta.put("situacao_conferencia", situacao));
        boolean vencida = vista.map(ContaContexto::conferenciaVencida).orElse(false);
        dadosConta.put("moeda", conta.moeda());
        dados.put("conta", dadosConta);
        Object lancamentos = mapaDe(pagina).get("lancamentos");
        dados.put("lancamentos", conta.moeda().equals(contexto.moeda()) ? lancamentos : naMoedaDaConta(lancamentos, conta.nome()));
        if (pagina.proximoCursor() != null) {
            dados.put("proxima", enderecos.url(referencia) + "?cursor=" + pagina.proximoCursor());
        }
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        if (dadosConta.containsKey("saldo")) {
            List<Map<String, Object>> itens = new ArrayList<>(List.of(total("Saldo", "/conta/saldo", "dinheiro")));
            if (dadosConta.containsKey("marco")) {
                itens.add(total("Marco", "/conta/marco", "data"));
            }
            if (dadosConta.containsKey("ultima_conferencia")) {
                itens.add(total("Última conferência", "/conta/ultima_conferencia", "data"));
                itens.add(total("Situação da conferência", "/conta/situacao_conferencia", "texto"));
            }
            filhos.add(tela.totais("Situação da conta", itens));
            if (!dadosConta.containsKey("ultima_conferencia")) {
                filhos.add(tela.texto("Esta conta nunca foi conferida com o saldo do banco.", vencida ? "aviso" : "normal"));
            } else if (vencida) {
                filhos.add(tela.texto("A conferência está vencida: o saldo não é comparado com o banco há mais de um mês.", "aviso"));
            }
            if ("pendente".equals(dadosConta.get("situacao_conferencia"))) {
                filhos.add(tela.texto("A última conferência deixou uma diferença pendente com o saldo do banco.", "aviso"));
            }
        }
        filhos.add(tela.enderecoCurto("/conta/id", "/conta/url"));
        filhos.addAll(origemDoRegistro(chamador, tela, dadosConta, "/conta", referencia.id(), "Operação que criou"));
        filhos.add(tabelaLancamentos(tela));
        if (pagina.proximoCursor() != null) {
            filhos.add(tela.componente("link", "rotulo", "Próxima página", "url", ref("/proxima")));
        }
        return tela.pagina(ref("/conta/nome"), filhos);
    }

    /**
     * No extrato de uma conta em outra moeda, o valor de cada linha é o movimento da própria conta: num
     * câmbio de R$ 640,00 por € 100,00, a Revolut mostra € 100,00. Só escolhe o movimento, sem fazer conta;
     * com mais de um movimento na conta, a linha fica como veio.
     */
    private static List<Map<String, Object>> naMoedaDaConta(Object lancamentos, String nomeConta) {
        List<Map<String, Object>> linhas = new ArrayList<>();
        for (Object item : (List<?>) lancamentos) {
            Map<String, Object> linha = new LinkedHashMap<>();
            ((Map<?, ?>) item).forEach((chave, valor) -> linha.put((String) chave, valor));
            List<Map<?, ?>> daConta = new ArrayList<>();
            for (Object m : (List<?>) linha.getOrDefault("movimentos", List.of())) {
                if (nomeConta.equals(((Map<?, ?>) m).get("conta"))) {
                    daConta.add((Map<?, ?>) m);
                }
            }
            if (daConta.size() == 1) {
                linha.put("valor", daConta.get(0).get("valor"));
                linha.put("moeda", daConta.get(0).get("moeda"));
            }
            linhas.add(linha);
        }
        return linhas;
    }

    private Vista lancamento(Chamador chamador, LancamentoVisto visto) {
        Map<String, Object> lancamento = mapaDe(visto);
        lancamento.computeIfPresent("operacao", (chave, id) -> mapa("id", id, "url", urlDe((String) id)));
        Map<String, Object> dados = dadosCom(visto.moeda());
        dados.put("lancamento", lancamento);
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        filhos.add(tela.totais("Resumo do lançamento", List.of(total("Valor", "/lancamento/valor", "dinheiro"),
                total("Data", "/lancamento/data", "data"), total("Tipo", "/lancamento/tipo", "texto"))));
        if (visto.anulado()) {
            filhos.add(tela.texto("Este lançamento foi anulado.", "aviso"));
        }
        if (visto.original() != null) {
            filhos.add(tela.totais("Moeda original e cotação", List.of(total("Valor original", "/lancamento/original/valor_original", "dinheiro"),
                    total("Moeda original", "/lancamento/original/moeda_original", "texto"), total("Cotação", "/lancamento/original/cotacao", "numero"),
                    total("Data da cotação", "/lancamento/original/data_cotacao", "data"))));
        }
        filhos.add(tela.enderecoCurto("/lancamento/id", "/lancamento/url"));
        filhos.add(tela.tabela("Movimentos", "/lancamento/movimentos", List.of(coluna("Conta", "conta", "texto"), coluna("Valor", "valor", "dinheiro"),
                coluna("Parcela", "parcela", "texto"))));
        if (visto.itens() != null && !visto.itens().isEmpty()) {
            filhos.add(tela.tabela("Itens", "/lancamento/itens", List.of(coluna("Descrição", "descricao", "texto"), coluna("Categoria", "categoria", "texto"),
                    coluna("Valor", "valor", "dinheiro"))));
        }
        if (visto.comprovantes() != null || visto.fotos() != null) {
            ZoneId fuso = ZoneId.of(obterContexto.executar(chamador).fuso());
            imagens(tela, filhos, lancamento, "comprovantes", "Comprovantes", visto.comprovantes(), fuso);
            imagens(tela, filhos, lancamento, "fotos", "Fotos", visto.fotos(), fuso);
        }
        // Só a casca da PWA lê este campo, para oferecer o envio; vista composta nunca o ganha.
        lancamento.put("anexavel", visto.anexavel());
        if (visto.operacao() != null) {
            filhos.add(tela.componente("link", "rotulo", "Operação que registrou", "url", ref("/lancamento/operacao/url")));
            List<String> origem = trilha.origemDoRegistro(chamador, visto.id());
            if (origem.size() > 1) {
                lancamento.put("alterou_url", origem.get(1));
                filhos.add(tela.componente("link", "rotulo", "Última operação que o alterou", "url", ref("/lancamento/alterou_url")));
            }
        }
        return tela.pagina(ref("/lancamento/descricao"), filhos);
    }

    /** Comprovantes e fotos em listas separadas: a foto é lembrança, não prova. */
    private void imagens(TelaPadrao tela, List<String> filhos, Map<String, Object> lancamento, String campo, String rotulo,
            List<ComprovanteVisto> lista, ZoneId fuso) {
        if (lista == null) {
            return;
        }
        List<Map<String, Object>> linhas = new ArrayList<>();
        for (ComprovanteVisto c : lista) {
            Map<String, Object> linha = mapaDe(c);
            linha.put("titulo", tituloComprovante(c, fuso));
            linhas.add(linha);
        }
        lancamento.put(campo, linhas);
        filhos.add(tela.componente("lista", "rotulo", rotulo, "origem", ref("/lancamento/" + campo), "campo_titulo", "titulo",
                "campo_url", "url_temporaria", "campo_detalhe", "id"));
    }

    private Vista fatura(Chamador chamador, FaturaVista visto) {
        Map<String, Object> dados = dadosCom(visto.moeda());
        dados.put("fatura", mapaDe(visto));
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        filhos.add(tela.totais("Valores da fatura", List.of(total("Total", "/fatura/total", "dinheiro"), total("Pago", "/fatura/pago", "dinheiro"),
                total("Restante", "/fatura/restante", "dinheiro"), total("Vencimento", "/fatura/vence_em", "data"), total("Situação", "/fatura/situacao", "texto"))));
        if (visto.id() != null) {
            filhos.add(tela.enderecoCurto("/fatura/id", "/fatura/url"));
        }
        if (visto.abertura() != null) {
            filhos.add(tela.totais("Saldo de abertura, já dentro do total", List.of(total("Abertura", "/fatura/abertura/valor", "dinheiro"),
                    total("Detalhado", "/fatura/abertura/detalhado", "dinheiro"), total("Sem detalhe", "/fatura/abertura/sem_detalhe", "dinheiro"))));
        }
        filhos.add(tela.tabela("Itens da fatura", "/fatura/itens", List.of(coluna("Data", "data", "data"), colunaLink("Descrição", "descricao", "url"),
                coluna("Parcela", "parcela", "texto"), coluna("Valor na fatura", "valor_na_fatura", "dinheiro"))));
        if (visto.pagamentos() != null && !visto.pagamentos().isEmpty()) {
            filhos.add(tela.tabela("Pagamentos", "/fatura/pagamentos", List.of(coluna("Data", "data", "data"), colunaLink("Lançamento", "lancamento", "url"),
                    coluna("Valor", "valor", "dinheiro"))));
        }
        return tela.pagina(ref("/fatura/cartao"), filhos);
    }

    private Vista operacao(Chamador chamador, ReferenciaCurta referencia) {
        OperacaoVista visto;
        try {
            visto = listarOperacoes.ver(chamador, referencia.id());
        } catch (ErroTipado inexistente) {
            throw naoEncontrado(referencia.caminho());
        }
        Map<String, Object> dados = dadosCom(obterContexto.executar(chamador).moeda());
        Map<String, Object> operacao = mapaDe(visto);
        // Só a casca da PWA lê este campo: o catálogo não tem ação, e vista composta nunca a ganha.
        // definir_compromisso, definir_meta e declarar exigem fin:profile, fora do alcance fixo de fin:undo: pela tela não se desfazem.
        operacao.put("desfazivel", visto.estado().equals("aplicada") && !ListarOperacoes.SO_RETRATO.contains(visto.capacidade())
                && !visto.capacidade().equals(DefinirCompromisso.CAPACIDADE) && !visto.capacidade().equals(DefinirMeta.CAPACIDADE)
                && !visto.capacidade().equals(Declarar.CAPACIDADE));
        operacao.put("agente", TrilhaAuditoria.agente(visto.cliente()));
        operacao.put("horario_local", TrilhaAuditoria.horario(visto.horario(), trilha.fuso(chamador)));
        List<TrilhaAuditoria.Afetado> afetados = trilha.afetados(chamador, referencia.id());
        operacao.put("afetados", json.convertValue(afetados, LISTA));
        dados.put("operacao", operacao);
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        filhos.add(tela.texto(ref("/operacao/resumo"), "normal"));
        filhos.add(tela.totais("Dados da operação", List.of(total("Data e hora", "/operacao/horario_local", "data_hora"),
                total("Agente", "/operacao/agente", "texto"), total("Estado", "/operacao/estado", "texto"),
                total("Capacidade", "/operacao/capacidade", "texto"), total("Cliente", "/operacao/cliente", "texto"))));
        if (!afetados.isEmpty()) {
            filhos.add(tela.tabela("Registros afetados", "/operacao/afetados", List.of(coluna("O que aconteceu", "acao", "texto"),
                    coluna("Registro", "registro", "texto"), colunaLink("Endereço", "id", "url"))));
        }
        filhos.add(tela.enderecoCurto("/operacao/id", "/operacao/url"));
        return tela.pagina("Operação", filhos);
    }

    /**
     * A trilha de auditoria: operações e tentativas recusadas, das mais recentes às mais antigas,
     * com as já desfeitas, porque desfazer um desfazer refaz. Os filtros vêm da URL; a tela só lê.
     */
    private Vista operacoes(Chamador chamador, Pedido p) {
        String cliente = Argumentos.vazio(p.cliente()) ? null : p.cliente().strip();
        String de = Argumentos.vazio(p.de()) ? null : p.de().strip();
        String ate = Argumentos.vazio(p.ate()) ? null : p.ate().strip();
        boolean relatorios = "true".equals(p.incluirRelatorios());
        TrilhaAuditoria.Pagina pagina = trilha.eventos(chamador, new TrilhaAuditoria.Pedido(cliente, de, ate, relatorios, p.cursor()));
        Map<String, Object> dados = dadosCom(obterContexto.executar(chamador).moeda());
        dados.put("eventos", json.convertValue(pagina.eventos(), LISTA));

        List<Map<String, Object>> filtros = new ArrayList<>();
        for (String outro : trilha.clientes(chamador)) {
            if (!outro.equals(cliente)) {
                filtros.add(mapa("titulo", "Só " + TrilhaAuditoria.agente(outro), "url", urlOperacoes(outro, de, ate, relatorios, null)));
            }
        }
        java.time.LocalDate hoje = trilha.hoje(chamador);
        filtros.add(mapa("titulo", "Últimos 7 dias", "url", urlOperacoes(cliente, hoje.minusDays(6).toString(), hoje.toString(), relatorios, null)));
        filtros.add(mapa("titulo", relatorios ? "Ocultar relatórios e ritmos de gastos" : "Incluir relatórios e ritmos de gastos",
                "url", urlOperacoes(cliente, de, ate, !relatorios, null)));
        boolean filtrada = cliente != null || de != null || ate != null || relatorios;
        if (filtrada) {
            filtros.add(mapa("titulo", "Todos os eventos, sem filtro", "url", enderecos.urlOperacoes()));
        }
        dados.put("filtros", filtros);
        if (pagina.proximoCursor() != null) {
            dados.put("proxima", urlOperacoes(cliente, de, ate, relatorios, pagina.proximoCursor()));
        }

        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        if (filtrada) {
            Map<String, Object> filtro = new LinkedHashMap<>();
            List<Map<String, Object>> itens = new ArrayList<>();
            if (cliente != null) {
                filtro.put("agente", TrilhaAuditoria.agente(cliente));
                itens.add(total("Agente", "/filtro/agente", "texto"));
            }
            if (de != null) {
                filtro.put("de", de);
                itens.add(total("De", "/filtro/de", "data"));
            }
            if (ate != null) {
                filtro.put("ate", ate);
                itens.add(total("Até", "/filtro/ate", "data"));
            }
            if (relatorios) {
                filtro.put("relatorios", "incluídos");
                itens.add(total("Relatórios e ritmos", "/filtro/relatorios", "texto"));
            }
            dados.put("filtro", filtro);
            filhos.add(tela.totais("Filtros aplicados", itens));
        }
        filhos.add(tela.tabelaDestacada("Eventos, dos mais recentes aos mais antigos; as tentativas recusadas aparecem destacadas", "/eventos",
                List.of(coluna("Data e hora", "horario", "data_hora"), colunaLink("O que foi feito", "descricao", "url"),
                        coluna("Agente", "agente", "texto"), coluna("Estado", "estado", "texto")), "recusada"));
        if (pagina.proximoCursor() != null) {
            filhos.add(tela.componente("link", "rotulo", "Próxima página", "url", ref("/proxima")));
        }
        filhos.add(tela.componente("lista", "rotulo", "Filtros", "origem", ref("/filtros"), "campo_titulo", "titulo", "campo_url", "url"));
        return tela.pagina("Operações", filhos);
    }

    private String urlOperacoes(String cliente, String de, String ate, boolean relatorios, String cursor) {
        List<String> partes = new ArrayList<>();
        if (cliente != null) {
            partes.add("cliente=" + java.net.URLEncoder.encode(cliente, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (de != null) {
            partes.add("de=" + java.net.URLEncoder.encode(de, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (ate != null) {
            partes.add("ate=" + java.net.URLEncoder.encode(ate, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (relatorios) {
            partes.add("incluir_relatorios=true");
        }
        if (cursor != null) {
            partes.add("cursor=" + cursor);
        }
        return enderecos.urlOperacoes() + (partes.isEmpty() ? "" : "?" + String.join("&", partes));
    }

    /** A operação que criou o registro e a última que o mudou, com link: do registro se volta à operação. */
    private List<String> origemDoRegistro(Chamador chamador, TelaPadrao tela, Map<String, Object> registro, String caminho, String referencia,
            String rotuloCriou) {
        List<String> urls = trilha.origemDoRegistro(chamador, referencia);
        List<String> filhos = new ArrayList<>();
        if (!urls.isEmpty()) {
            registro.put("criou_url", urls.get(0));
            filhos.add(tela.componente("link", "rotulo", rotuloCriou, "url", ref(caminho + "/criou_url")));
        }
        if (urls.size() > 1) {
            registro.put("alterou_url", urls.get(1));
            filhos.add(tela.componente("link", "rotulo", "Última operação que o alterou", "url", ref(caminho + "/alterou_url")));
        }
        return filhos;
    }

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** O que o item é, e não só o id: "Imagem JPEG, enviada em 01/11/2026". */
    static String tituloComprovante(ComprovanteVisto c, ZoneId fuso) {
        String tipo = c.tipoMidia() == null ? "Arquivo" : "Imagem " + c.tipoMidia().substring(c.tipoMidia().indexOf('/') + 1).toUpperCase(Locale.ROOT);
        return tipo + ", enviada em " + OffsetDateTime.parse(c.enviadoEm()).atZoneSameInstant(fuso).format(DIA);
    }

    private Vista comprovante(Chamador chamador, ComprovanteVisto visto) {
        Map<String, Object> dados = dadosCom(obterContexto.executar(chamador).moeda());
        dados.put("comprovante", mapaDe(visto));
        TelaPadrao tela = new TelaPadrao(dados);
        String nome = visto.foto() ? "Foto do que foi comprado" : "Comprovante";
        String situacao = tela.totais(visto.foto() ? "Dados da foto" : "Dados do comprovante", List.of(total("Id", "/comprovante/id", "texto"),
                total("Tipo", "/comprovante/tipo_midia", "texto"), total("Enviado em", "/comprovante/enviado_em", "data"),
                total("Link válido até", "/comprovante/valida_ate", "data")));
        List<String> filhos = new ArrayList<>();
        filhos.add(situacao);
        if (visto.foto()) {
            filhos.add(tela.texto("Lembrança da compra: não é prova de pagamento.", "normal"));
        }
        filhos.add(tela.componente("link", "rotulo", "Abrir o arquivo", "url", ref("/comprovante/url_temporaria")));
        if (visto.lancamento() != null) {
            dados.put("lancamento_url", urlDe(visto.lancamento()));
            filhos.add(tela.componente("link", "rotulo", "Lançamento", "url", ref("/lancamento_url")));
        }
        return tela.pagina(nome, filhos);
    }

    private Vista retrato(Chamador chamador, RetratoVisto visto) {
        if (visto.capacidade().equals(RitmoGastos.CAPACIDADE)) {
            return retratoRitmo(visto);
        }
        if (visto.capacidade().equals(CortesNecessarios.CAPACIDADE)) {
            return retratoCortes(visto);
        }
        if (visto.capacidade().equals(Projetar.CAPACIDADE)) {
            return retratoProjecao(visto);
        }
        if (visto.capacidade().equals(PrestarContas.CAPACIDADE)) {
            // A mesma tela que a família vê pelo link, sem os endereços dos comprovantes, que dependem do token.
            TelaPrestacao.Guardada guardada = TelaPrestacao.ler(visto.resultado().toString());
            return TelaPrestacao.abrir(guardada, guardada.prestacao());
        }
        Map<String, Object> resumo = json.convertValue(visto.resultado(), MAPA);
        Map<String, Object> dados = dadosCom(String.valueOf(resumo.get("moeda")));
        dados.put("resumo", resumo);
        dados.put("retrato", mapa("id", visto.id(), "url", visto.url(), "data_referencia", visto.dataReferencia()));
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        filhos.add(tela.texto("Retrato imutável: mostra o que foi calculado naquele momento.", "aviso"));
        filhos.add(tela.totais("Quando foi calculado", List.of(total("Calculado em", "/retrato/data_referencia", "data"))));
        filhos.add(tela.enderecoCurto("/retrato/id", "/retrato/url"));
        filhos.addAll(corpoDoResumo(tela, resumo));
        return tela.pagina("Retrato de relatório", filhos);
    }

    /** O retrato de {@code ritmo_gastos}: os números são os guardados, só repostos em linhas para as tabelas. */
    private Vista retratoRitmo(RetratoVisto visto) {
        Map<String, Object> ritmo = json.convertValue(visto.resultado(), MAPA);
        Map<String, Object> dados = dadosCom(String.valueOf(ritmo.get("moeda")));
        dados.put("ritmo", ritmo);
        dados.put("retrato", mapa("id", visto.id(), "url", visto.url(), "data_referencia", visto.dataReferencia()));
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        filhos.add(tela.texto("Retrato imutável: mostra o que foi calculado naquele momento.", "aviso"));
        filhos.add(tela.totais("Quando foi calculado", List.of(total("Mês", "/ritmo/mes", "texto"), total("Até o dia", "/ritmo/hoje", "data"),
                total("Calculado em", "/retrato/data_referencia", "data"))));
        filhos.add(tela.enderecoCurto("/retrato/id", "/retrato/url"));
        ressalvas(tela, filhos, ritmo, "/ritmo", Map.of("historico_insuficiente",
                "Ainda não há meses completos suficientes para calcular o gasto típico."));
        List<Map<String, Object>> totais = new ArrayList<>(List.of(total("Gasto no mês até o dia", "/ritmo/acumulado", "dinheiro"),
                total("Ritmo diário", "/ritmo/ritmo_diario", "dinheiro"), total("Dias decorridos", "/ritmo/dias_decorridos", "numero")));
        if (ritmo.get("tipico") instanceof Map<?, ?>) {
            totais.add(total("Típico até o mesmo dia", "/ritmo/tipico/acumulado", "dinheiro"));
            totais.add(total("Ritmo diário típico", "/ritmo/tipico/ritmo_diario", "dinheiro"));
            totais.add(total("Diferença", "/ritmo/tipico/diferenca", "dinheiro"));
            if (((Map<?, ?>) ritmo.get("tipico")).containsKey("percentual")) {
                totais.add(total("Diferença, em %", "/ritmo/tipico/percentual", "numero"));
            }
            totais.add(total("Meses de referência usados", "/ritmo/tipico/meses_usados", "numero"));
        }
        filhos.add(tela.totais("Ritmo do mês", totais));
        if (ritmo.get("projecoes") instanceof Map<?, ?> projecoes) {
            List<Map<String, Object>> linhas = new ArrayList<>(List.of(total("Mantido o ritmo atual", "/ritmo/projecoes/mantido_ritmo_atual", "dinheiro")));
            if (projecoes.containsKey("acumulado_mais_restante_tipico")) {
                linhas.add(total("Acumulado mais o restante típico", "/ritmo/projecoes/acumulado_mais_restante_tipico", "dinheiro"));
            }
            String explicacao = projecoes.containsKey("acumulado_mais_restante_tipico")
                    ? "Pelo ritmo de hoje, e pelo gasto de hoje somado ao que os meses de referência costumam gastar até o fim do mês."
                    : "Pelo ritmo de hoje. Sem típico, não há a projeção pelo que os meses de referência costumam gastar.";
            filhos.add(tela.cartao("Projeções de fim de mês", tela.texto(explicacao, "normal"), tela.totais("Fim do mês", linhas)));
        }
        if (ritmo.get("responsaveis") instanceof Map<?, ?> responsaveis) {
            List<Map<String, Object>> colunas = List.of(coluna("Nome", "nome", "texto"), coluna("Agora", "atual", "dinheiro"),
                    coluna("Típico", "tipico", "dinheiro"), coluna("Contribuição", "contribuicao", "dinheiro"));
            filhos.add(tela.cartao("Categorias que puxam a diferença", tela.tabela("Categorias", "/ritmo/responsaveis/categorias", colunas)));
            filhos.add(tela.cartao("Estabelecimentos que puxam a diferença", tela.tabela("Estabelecimentos", "/ritmo/responsaveis/contrapartes", colunas)));
            if (responsaveis.get("compras_atipicas") instanceof List<?> compras && !compras.isEmpty()) {
                filhos.add(tela.cartao("Compras atípicas", tela.texto("Acima do percentil noventa e cinco da categoria nos meses de referência: maiores que quase todas as compras dela.", "normal"),
                        tela.tabela("Compras atípicas", "/ritmo/responsaveis/compras_atipicas", List.of(colunaLink("Compra", "descricao", "url"),
                                coluna("Data", "data", "data"), coluna("Categoria", "categoria", "texto"), coluna("Valor", "valor", "dinheiro"),
                                coluna("Limite da categoria", "percentil95", "dinheiro")))));
            }
        }
        if (ritmo.get("meses_referencia") instanceof List<?> meses && !meses.isEmpty()) {
            filhos.add(tela.cartao("Meses de referência", tela.tabela("Meses de referência", "/ritmo/meses_referencia", List.of(
                    coluna("Mês", "mes", "texto"), coluna("Até o mesmo dia", "ate_o_dia", "dinheiro"), coluna("Mês inteiro", "total", "dinheiro")))));
        }
        if (ritmo.get("tendencia") instanceof Map<?, ?> tendencia && tendencia.get("categorias") instanceof List<?> categorias
                && !categorias.isEmpty()) {
            List<?> rotulos = (List<?>) tendencia.get("meses");
            List<Map<String, Object>> linhas = new ArrayList<>();
            for (Object item : categorias) {
                Map<?, ?> c = (Map<?, ?>) item;
                List<?> saidas = (List<?>) c.get("saidas");
                Map<String, Object> linha = mapa("nome", c.get("nome"), "url", c.get("url"), "situacao",
                        Boolean.TRUE.equals(c.get("subindo")) ? "Subindo" : "");
                for (int i = 0; i < saidas.size(); i++) {
                    linha.put("mes" + i, saidas.get(i));
                }
                linhas.add(linha);
            }
            dados.put("tendencia", linhas);
            // Os meses vão pelos dados: título com número escrito à mão é recusado.
            List<String> titulos = List.of("Há três meses", "Há dois meses", "Mês anterior");
            List<Map<String, Object>> colunas = new ArrayList<>(List.of(colunaLink("Categoria", "nome", "url")));
            for (int i = 0; i < rotulos.size(); i++) {
                colunas.add(coluna(titulos.get(i), "mes" + i, "dinheiro"));
            }
            colunas.add(coluna("Tendência", "situacao", "texto"));
            filhos.add(tela.cartao("Tendência por categoria", tela.totais("Meses fechados", List.of(total("Há três meses", "/ritmo/tendencia/meses/0", "texto"),
                    total("Mês anterior", "/ritmo/tendencia/meses/" + (rotulos.size() - 1), "texto"))),
                    tela.tabela("Tendência por categoria", "/tendencia", colunas)));
        }
        if (ritmo.get("variabilidade") instanceof Map<?, ?> variabilidade && variabilidade.get("categorias") instanceof List<?> lista
                && !lista.isEmpty()) {
            List<Map<String, Object>> linhas = new ArrayList<>();
            for (Object item : lista) {
                Map<?, ?> c = (Map<?, ?>) item;
                Map<String, Object> linha = mapa("nome", c.get("nome"), "url", c.get("url"), "menor", c.get("menor"), "mediana", c.get("mediana"),
                        "maior", c.get("maior"), "dispersao", c.get("dispersao"));
                linha.values().removeIf(java.util.Objects::isNull);
                linhas.add(linha);
            }
            dados.put("variabilidade", linhas);
            filhos.add(tela.cartao("Quanto cada categoria varia", tela.texto("Quanto maior a dispersão, mais o gasto da categoria muda de um mês para outro.",
                    "normal"),
                    tela.tabela("Variação por categoria", "/variabilidade", List.of(colunaLink("Categoria", "nome", "url"),
                            coluna("Menor mês", "menor", "dinheiro"), coluna("Mês do meio", "mediana", "dinheiro"), coluna("Maior mês", "maior", "dinheiro"),
                            coluna("Dispersão, em %", "dispersao", "numero")))));
        }
        if (ritmo.get("poupanca") instanceof Map<?, ?> poupanca) {
            List<Map<String, Object>> itens = new ArrayList<>(List.of(total("Quanto você quer poupar, em %", "/ritmo/poupanca/declarada", "numero")));
            if (poupanca.get("observada") != null) {
                itens.add(total("Quanto poupou, em %", "/ritmo/poupanca/observada", "numero"));
                itens.add(total("Renda nos meses fechados", "/ritmo/poupanca/renda", "dinheiro"));
                itens.add(total("Saídas nos meses fechados", "/ritmo/poupanca/saidas", "dinheiro"));
            }
            String explicacao = poupanca.get("observada") == null ? "Ainda não dá para medir quanto você poupou nos últimos meses fechados."
                    : Boolean.TRUE.equals(poupanca.get("abaixo")) ? "Nos últimos meses fechados, você poupou menos do que declarou."
                    : "Nos últimos meses fechados, você poupou o que declarou ou mais.";
            filhos.add(tela.cartao("Poupança", tela.texto(explicacao, Boolean.TRUE.equals(poupanca.get("abaixo")) ? "aviso" : "normal"),
                    tela.totais("Poupança nos meses fechados", itens)));
        }
        return tela.pagina("Ritmo de gastos", filhos);
    }

    /**
     * O retrato de {@code cortes_necessarios}: o saldo projetado, a distância até o alvo, o plano e o que não se corta. Os
     * números são os guardados; as linhas só ganham o texto de "essencial" para a tabela.
     */
    private Vista retratoCortes(RetratoVisto visto) {
        Map<String, Object> cortes = json.convertValue(visto.resultado(), MAPA);
        Map<String, Object> dados = dadosCom(String.valueOf(cortes.get("moeda")));
        dados.put("cortes", cortes);
        dados.put("retrato", mapa("id", visto.id(), "url", visto.url(), "data_referencia", visto.dataReferencia()));
        boolean deficit = !String.valueOf(cortes.get("deficit")).matches("0+(\\.0+)?");
        Object tipoAlvo = ((Map<?, ?>) cortes.get("alvo")).get("tipo");
        dados.put("alvo_tipo", "piso_periodo".equals(tipoAlvo) ? "Fechar o mês com o piso declarado"
                : "meta".equals(tipoAlvo) ? "Fechar o mês no piso e guardar para a meta" : "Saldo avulso");
        boolean comMeta = ((Map<?, ?>) cortes.get("alvo")).get("meta") instanceof Map<?, ?>;
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        filhos.add(tela.texto("Retrato imutável: mostra o que foi calculado naquele momento.", "aviso"));
        filhos.add(tela.totais("Quando foi calculado", List.of(total("Hoje", "/cortes/hoje", "data"), total("Fim do período", "/cortes/fim", "data"),
                total("Calculado em", "/retrato/data_referencia", "data"))));
        filhos.add(tela.enderecoCurto("/retrato/id", "/retrato/url"));
        ressalvas(tela, filhos, cortes, "/cortes", Map.of("historico_insuficiente",
                "Ainda não há meses completos suficientes para um plano de cortes: o gasto restante é uma estimativa fraca.",
                "conferencia_vencida", CONFERENCIA_VENCIDA));
        List<Map<String, Object>> alvo = new ArrayList<>(List.of(total("Alvo", "/alvo_tipo", "texto")));
        if (comMeta) {
            alvo.add(total("Meta", "/cortes/alvo/meta/nome", "texto"));
            alvo.add(total("Piso no fim do mês", "/cortes/alvo/meta/piso", "dinheiro"));
            alvo.add(total("Aporte do mês na meta", "/cortes/alvo/meta/aporte_mes", "dinheiro"));
        }
        alvo.add(total("Saldo mínimo no fim", "/cortes/alvo/valor", "dinheiro"));
        filhos.add(tela.totais("Alvo", alvo));
        filhos.add(tela.totais("Fim do período", List.of(total("Saldo de partida", "/cortes/saldo_partida", "dinheiro"),
                total("Entradas conhecidas", "/cortes/entradas_conhecidas", "dinheiro"), total("Saídas conhecidas", "/cortes/saidas_conhecidas", "dinheiro"),
                total("Gasto variável restante", "/cortes/variavel_restante", "dinheiro"), total("Saldo projetado", "/cortes/saldo_projetado", "dinheiro"),
                deficit ? total("Déficit", "/cortes/deficit", "dinheiro") : total("Folga", "/cortes/folga", "dinheiro"))));
        if (cortes.get("plano") instanceof Map<?, ?> plano) {
            List<Map<String, Object>> linhas = new ArrayList<>();
            if (plano.get("cortes") instanceof List<?> lista) {
                for (Object item : lista) {
                    Map<?, ?> c = (Map<?, ?>) item;
                    linhas.add(mapa("nome", c.get("nome"), "url", c.get("url"), "essencial", Boolean.TRUE.equals(c.get("essencial")) ? "Essencial" : "",
                            "corte", c.get("corte"), "restante_esperado", c.get("restante_esperado"), "restante_com_corte", c.get("restante_com_corte")));
                }
            }
            dados.put("plano", linhas);
            String explicacao = deficit ? (Boolean.TRUE.equals(plano.get("precisa_essenciais"))
                    ? "Para cobrir o déficit, o plano também corta categorias essenciais."
                    : "O plano cobre o déficit sem tocar nas categorias essenciais.")
                    : "Sem déficit: nada precisa ser cortado.";
            List<String> partes = new ArrayList<>(List.of(tela.texto(explicacao, "normal"), tela.totais("Plano", List.of(
                    total("Coberto pelo plano", "/cortes/plano/coberto", "dinheiro"), total("Sem cobertura", "/cortes/plano/descoberto", "dinheiro"),
                    total("Espaço de corte total", "/cortes/plano/espaco_total", "dinheiro")))));
            if (!linhas.isEmpty()) {
                partes.add(tela.tabela("Plano de cortes", "/plano", List.of(colunaLink("Categoria", "nome", "url"), coluna("Essencial", "essencial", "texto"),
                        coluna("Cortar", "corte", "dinheiro"), coluna("Restante esperado", "restante_esperado", "dinheiro"),
                        coluna("Pode gastar até o fim", "restante_com_corte", "dinheiro"))));
            }
            filhos.add(tela.cartao("Plano mínimo de cortes", partes.toArray(String[]::new)));
        }
        if (cortes.get("categorias") instanceof List<?> categorias && !categorias.isEmpty()) {
            List<Map<String, Object>> linhas = new ArrayList<>();
            for (Object item : categorias) {
                Map<?, ?> c = (Map<?, ?>) item;
                linhas.add(mapa("nome", c.get("nome"), "url", c.get("url"), "essencial", Boolean.TRUE.equals(c.get("essencial")) ? "Essencial" : "",
                        "gasto_ate_hoje", c.get("gasto_ate_hoje"), "tipico", c.get("tipico"), "restante_esperado", c.get("restante_esperado"),
                        "espaco_corte", c.get("espaco_corte")));
            }
            dados.put("categorias", linhas);
            filhos.add(tela.cartao("Gasto variável por categoria", tela.texto("Espaço de corte: quanto o restante esperado passa do que a categoria gasta "
                    + "num fim de mês enxuto.", "normal"),
                    tela.tabela("Categorias", "/categorias", List.of(colunaLink("Categoria", "nome", "url"), coluna("Essencial", "essencial", "texto"),
                            coluna("Gasto até hoje", "gasto_ate_hoje", "dinheiro"), coluna("Típico", "tipico", "dinheiro"),
                            coluna("Restante esperado", "restante_esperado", "dinheiro"), coluna("Espaço de corte", "espaco_corte", "dinheiro")))));
        }
        List<Map<String, Object>> colunasAgenda = List.of(colunaLink("Item", "nome", "url"), coluna("Vence", "vencimento", "data"),
                coluna("Valor", "valor", "dinheiro"), coluna("Situação", "situacao", "texto"));
        if (cortes.get("nao_cortaveis") instanceof List<?> nao && !nao.isEmpty()) {
            filhos.add(tela.cartao("Não cortáveis neste período", tela.texto("Faturas, com as parcelas dentro, e compromissos: estão listados para "
                    + "o dono decidir se renegocia algum.", "normal"), tela.tabela("Não cortáveis", "/cortes/nao_cortaveis", colunasAgenda)));
        }
        if (cortes.get("entradas") instanceof List<?> entradas && !entradas.isEmpty()) {
            filhos.add(tela.cartao("Entradas conhecidas", tela.tabela("Entradas conhecidas", "/cortes/entradas", colunasAgenda)));
        }
        if (cortes.get("contas_partida") instanceof List<?> contas && !contas.isEmpty()) {
            filhos.add(tela.cartao("Saldo de partida", tela.tabela("Contas do saldo de partida", "/cortes/contas_partida", List.of(
                    colunaLink("Conta", "nome", "url"), coluna("Saldo", "saldo", "dinheiro")))));
        }
        filhos.add(tela.texto("Conservador de propósito: o gasto restante conta inteiro neste mês, mesmo a parte que for no cartão.", "normal"));
        return tela.pagina("Cortes necessários", filhos);
    }

    private static final String CONFERENCIA_VENCIDA = "Há conta sem conferência há mais de um mês: o saldo de partida pode estar errado.";

    /**
     * As ressalvas de um retrato na tela. A mensagem guardada fala com o assistente e às vezes lhe dá ordens ("repasse ao
     * dono", "sugira conferir_saldo"); essas ganham um texto fixo para o dono, e texto vazio as omite.
     * As notas do assistente nunca vão para a tela.
     */
    private static void ressalvas(TelaPadrao tela, List<String> filhos, Map<String, Object> resultado, String caminho,
            Map<String, String> paraODono) {
        if (!(resultado.get("ressalvas") instanceof List<?> ressalvas)) {
            return;
        }
        for (int i = 0; i < ressalvas.size(); i++) {
            String texto = paraODono.get(String.valueOf(((Map<?, ?>) ressalvas.get(i)).get("codigo")));
            if (texto == null) {
                filhos.add(tela.texto(ref(caminho + "/ressalvas/" + i + "/mensagem"), "aviso"));
            } else if (!texto.isEmpty()) {
                filhos.add(tela.texto(texto, "aviso"));
            }
        }
    }

    /** Os títulos dos cenários: o rótulo do assistente vai pelos dados, porque título com número escrito é recusado. */
    private static final List<String> TITULOS_CENARIO = List.of("Primeiro cenário", "Segundo cenário", "Terceiro cenário");

    /**
     * O retrato de {@code projetar}: a linha de base e cada cenário, com os indicadores e o saldo mês a mês nas duas
     * estimativas. Os números são os guardados; as linhas só ganham o texto da situação diante do piso.
     */
    private Vista retratoProjecao(RetratoVisto visto) {
        Map<String, Object> projecao = json.convertValue(visto.resultado(), MAPA);
        Map<String, Object> dados = dadosCom(String.valueOf(projecao.get("moeda")));
        dados.put("projecao", projecao);
        dados.put("retrato", mapa("id", visto.id(), "url", visto.url(), "data_referencia", visto.dataReferencia()));
        boolean comPiso = projecao.get("piso") instanceof Map<?, ?>;
        boolean comVariavel = Boolean.TRUE.equals(projecao.get("estimativa_variavel"));
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        filhos.add(tela.texto("Retrato imutável: mostra o que foi calculado naquele momento.", "aviso"));
        filhos.add(tela.totais("Quando foi calculado", List.of(total("Hoje", "/projecao/hoje", "data"), total("Até", "/projecao/ate", "data"),
                total("Calculado em", "/retrato/data_referencia", "data"))));
        filhos.add(tela.enderecoCurto("/retrato/id", "/retrato/url"));
        // O histórico curto já é dito logo abaixo, no texto das estimativas.
        ressalvas(tela, filhos, projecao, "/projecao", Map.of("historico_insuficiente", "", "conferencia_vencida", CONFERENCIA_VENCIDA,
                "reserva_sem_valor", "A reserva em meses de gasto ainda não tem valor: faltam meses completos para medir o gasto típico.",
                "mes_sem_renda", "Em algum mês não há renda prevista: nele, o peso das parcelas não foi medido."));
        List<Map<String, Object>> partida = new ArrayList<>(List.of(total("Saldo de partida", "/projecao/saldo_partida", "dinheiro"),
                total("Meses projetados", "/projecao/horizonte_meses", "numero")));
        if (comPiso) {
            partida.add(total("Piso declarado no fim do mês", "/projecao/piso/saldo_minimo", "dinheiro"));
        }
        Map<?, ?> regras = projecao.get("regras") instanceof Map<?, ?> r ? r : Map.of();
        boolean comReserva = regras.get("reserva_minima") instanceof Map<?, ?> reserva && reserva.get("valor") != null;
        if (comReserva) {
            partida.add(total("Reserva mínima", "/projecao/regras/reserva_minima/valor", "dinheiro"));
            if (((Map<?, ?>) regras.get("reserva_minima")).get("meses_de_gasto") != null) {
                partida.add(total("Meses de gasto da reserva", "/projecao/regras/reserva_minima/meses_de_gasto", "numero"));
            }
        }
        if (regras.get("teto_parcelas") instanceof Map<?, ?>) {
            partida.add(total("Teto das parcelas sobre a renda, em %", "/projecao/regras/teto_parcelas/percentual", "numero"));
        }
        filhos.add(tela.totais("Ponto de partida", partida));
        filhos.add(tela.texto(comVariavel ? "Otimista usa o gasto típico de cada categoria; pessimista, um mês de gasto alto em cada uma, "
                + "de propósito." : "Sem histórico suficiente, os saldos não descontam o gasto do dia a dia: ficam otimistas demais.", "normal"));
        filhos.add(cartaoCenario(tela, dados, "Linha de base", "/projecao/linha_base", "linha_base", comPiso, comVariavel, comReserva));
        if (projecao.get("cenarios") instanceof List<?> cenarios) {
            for (int i = 0; i < cenarios.size() && i < TITULOS_CENARIO.size(); i++) {
                filhos.add(cartaoCenario(tela, dados, TITULOS_CENARIO.get(i), "/projecao/cenarios/" + i, "cenario" + i, comPiso, comVariavel,
                        comReserva));
            }
        }
        if (projecao.get("contas_partida") instanceof List<?> contas && !contas.isEmpty()) {
            filhos.add(tela.cartao("Saldo de partida", tela.tabela("Contas do saldo de partida", "/projecao/contas_partida", List.of(
                    colunaLink("Conta", "nome", "url"), coluna("Saldo", "saldo", "dinheiro")))));
        }
        filhos.add(tela.texto("Visão de caixa: o que vai para o cartão conta no mês em que a fatura vence.", "normal"));
        return tela.pagina("Projeção", filhos);
    }

    /** O nome de cada regra para o dono. */
    private static final Map<String, String> REGRAS = Map.of("piso_periodo", "Piso do fim do mês", "reserva_minima", "Reserva mínima",
            "teto_parcelas", "Teto das parcelas", "meta_protegida", "Meta protegida");

    private String cartaoCenario(TelaPadrao tela, Map<String, Object> dados, String titulo, String caminho, String chave, boolean comPiso,
            boolean comVariavel, boolean comReserva) {
        Map<?, ?> cenario = (Map<?, ?>) json.convertValue(caminhoEm(dados, caminho), MAPA);
        List<String> partes = new ArrayList<>();
        boolean base = cenario.get("diferenca_linha_base") == null;
        if (!base) {
            partes.add(tela.texto(ref(caminho + "/rotulo"), "normal"));
        }
        if (cenario.get("hipoteses") instanceof List<?> hipoteses) {
            for (int k = 0; k < hipoteses.size(); k++) {
                partes.add(tela.texto(ref(caminho + "/hipoteses/" + k + "/resumo"), "normal"));
                if (((Map<?, ?>) hipoteses.get(k)).get("itens") instanceof List<?> itens && itens.size() > 1) {
                    partes.add(tela.tabela("Onde cada valor entra", caminho + "/hipoteses/" + k + "/itens", List.of(coluna("Destino", "destino", "texto"),
                            coluna("Data", "data", "data"), coluna("Valor", "valor", "dinheiro"))));
                }
            }
        }
        boolean comAportes = false;
        for (Object item : (List<?>) cenario.get("meses")) {
            Object aporte = ((Map<?, ?>) item).get("aportes");
            comAportes |= aporte != null && !String.valueOf(aporte).matches("0+(\\.0+)?");
        }
        Map<?, ?> indicadores = (Map<?, ?>) cenario.get("indicadores");
        List<Map<String, Object>> itens = new ArrayList<>(List.of(total("Menor saldo, pessimista", caminho + "/indicadores/menor_saldo_p80/saldo", "dinheiro"),
                total("Mês do menor saldo, pessimista", caminho + "/indicadores/menor_saldo_p80/mes", "texto"),
                total("Menor saldo, otimista", caminho + "/indicadores/menor_saldo_p50/saldo", "dinheiro"),
                total("Mês do menor saldo, otimista", caminho + "/indicadores/menor_saldo_p50/mes", "texto")));
        if (indicadores.get("pico_faturas") instanceof Map<?, ?>) {
            itens.add(total("Maior peso das faturas sobre as entradas, em %", caminho + "/indicadores/pico_faturas/percentual", "numero"));
            itens.add(total("Mês desse peso", caminho + "/indicadores/pico_faturas/mes", "texto"));
        }
        if (indicadores.get("pico_parcelas") instanceof Map<?, ?>) {
            itens.add(total("Maior peso das parcelas sobre a renda, em %", caminho + "/indicadores/pico_parcelas/percentual", "numero"));
            itens.add(total("Mês do maior peso das parcelas", caminho + "/indicadores/pico_parcelas/mes", "texto"));
        }
        if (!base) {
            itens.add(total("Diferença no fim, pessimista", caminho + "/diferenca_linha_base/saldo_final_p80", "dinheiro"));
            itens.add(total("Diferença no fim, otimista", caminho + "/diferenca_linha_base/saldo_final_p50", "dinheiro"));
            itens.add(total("Diferença no menor saldo, pessimista", caminho + "/diferenca_linha_base/menor_saldo_p80", "dinheiro"));
        }
        partes.add(tela.totais("Indicadores", itens));
        List<Map<String, Object>> linhas = new ArrayList<>();
        boolean comParcelas = false;
        for (Object item : (List<?>) cenario.get("meses")) {
            Map<?, ?> m = (Map<?, ?>) item;
            boolean abaixo80 = Boolean.TRUE.equals(m.get("abaixo_piso_p80"));
            boolean abaixo50 = Boolean.TRUE.equals(m.get("abaixo_piso_p50"));
            boolean reserva80 = Boolean.TRUE.equals(m.get("abaixo_reserva_p80"));
            boolean reserva50 = Boolean.TRUE.equals(m.get("abaixo_reserva_p50"));
            List<String> situacao = new ArrayList<>();
            if (comPiso) {
                situacao.add(abaixo50 ? "Abaixo do piso nas duas" : abaixo80 ? "Abaixo do piso no pessimista" : "Acima do piso");
            }
            if (comReserva) {
                situacao.add(reserva50 ? "Abaixo da reserva nas duas" : reserva80 ? "Abaixo da reserva no pessimista" : "Acima da reserva");
            }
            Object parcelas = m.get("parcelas");
            comParcelas |= parcelas != null && !String.valueOf(parcelas).matches("0+(\\.0+)?");
            Map<String, Object> linha = mapa("mes", m.get("mes"), "entradas", m.get("entradas"), "saidas_conhecidas", m.get("saidas_conhecidas"),
                    "faturas", m.get("faturas"), "aportes", m.get("aportes"), "variavel_p50", m.get("variavel_p50"), "variavel_p80", m.get("variavel_p80"),
                    "saldo_final_p50", m.get("saldo_final_p50"), "saldo_final_p80", m.get("saldo_final_p80"), "abaixo", abaixo80 || reserva80,
                    "situacao", String.join("; ", situacao), "parcelas", parcelas, "comprometimento", m.get("comprometimento"));
            linha.values().removeIf(java.util.Objects::isNull);
            linhas.add(linha);
        }
        dados.put(chave, linhas);
        List<Map<String, Object>> colunas = new ArrayList<>(List.of(coluna("Mês", "mes", "texto"), coluna("Entradas", "entradas", "dinheiro"),
                coluna("Saídas conhecidas", "saidas_conhecidas", "dinheiro"), coluna("Faturas", "faturas", "dinheiro")));
        if (comAportes) {
            colunas.add(coluna("Aportes em metas", "aportes", "dinheiro"));
        }
        if (comVariavel) {
            colunas.add(coluna("Gasto variável, otimista", "variavel_p50", "dinheiro"));
            colunas.add(coluna("Gasto variável, pessimista", "variavel_p80", "dinheiro"));
        }
        colunas.add(coluna("Saldo no fim, otimista", "saldo_final_p50", "dinheiro"));
        colunas.add(coluna("Saldo no fim, pessimista", "saldo_final_p80", "dinheiro"));
        if (comParcelas) {
            colunas.add(coluna("Parcelas", "parcelas", "dinheiro"));
            colunas.add(coluna("Parcelas sobre a renda, em %", "comprometimento", "numero"));
        }
        if (comPiso || comReserva) {
            colunas.add(coluna("Situação", "situacao", "texto"));
        }
        partes.add(tela.tabelaDestacada("Mês a mês", "/" + chave, colunas, "abaixo"));
        if (cenario.get("violacoes") instanceof List<?> violacoes && !violacoes.isEmpty()) {
            List<Map<String, Object>> vLinhas = new ArrayList<>();
            for (Object item : violacoes) {
                Map<?, ?> v = (Map<?, ?>) item;
                String regra = REGRAS.getOrDefault(String.valueOf(v.get("regra")), String.valueOf(v.get("regra")));
                Map<String, Object> linha = mapa("regra", v.get("nome_meta") == null ? regra : regra + ": " + v.get("nome_meta"), "url", v.get("url_meta"),
                        "mes", v.get("mes"), "saldo_final_p80", v.get("saldo_final_p80"), "limite", v.get("limite"),
                        "comprometimento", v.get("comprometimento"), "teto", v.get("teto"));
                linha.values().removeIf(java.util.Objects::isNull);
                vLinhas.add(linha);
            }
            dados.put(chave + "_violacoes", vLinhas);
            partes.add(tela.texto("Regras que você declarou e que este cenário quebra, pelo lado pessimista.", "aviso"));
            partes.add(tela.tabela("Regras quebradas", "/" + chave + "_violacoes", List.of(colunaLink("Regra", "regra", "url"),
                    coluna("Mês", "mes", "texto"), coluna("Saldo no fim, pessimista", "saldo_final_p80", "dinheiro"),
                    coluna("Mínimo pela regra", "limite", "dinheiro"), coluna("Parcelas sobre a renda, em %", "comprometimento", "numero"),
                    coluna("Teto, em %", "teto", "numero"))));
        }
        if (cenario.get("metas") instanceof List<?> lista && !lista.isEmpty()) {
            List<Map<String, Object>> metasLinhas = new ArrayList<>();
            for (Object item : lista) {
                Map<?, ?> m = (Map<?, ?>) item;
                Map<String, Object> linha = mapa("nome", m.get("nome"), "url", m.get("url"), "valor_alvo", m.get("valor_alvo"),
                        "progresso", m.get("progresso"), "aportes_planejados", m.get("aportes_planejados"), "data_alvo", m.get("data_alvo"),
                        "mes_projetado", m.get("mes_projetado") == null ? "" : m.get("mes_projetado"), "situacao", m.get("situacao"),
                        "atrasada", Boolean.FALSE.equals(m.get("no_prazo")) || !Boolean.TRUE.equals(m.get("alcanca_no_horizonte")));
                linha.values().removeIf(java.util.Objects::isNull);
                metasLinhas.add(linha);
            }
            dados.put(chave + "_metas", metasLinhas);
            partes.add(tela.tabelaDestacada("Metas", "/" + chave + "_metas", List.of(colunaLink("Meta", "nome", "url"),
                    coluna("Valor alvo", "valor_alvo", "dinheiro"), coluna("Juntado hoje", "progresso", "dinheiro"),
                    coluna("Aportes planejados", "aportes_planejados", "dinheiro"), coluna("Data alvo", "data_alvo", "data"),
                    coluna("Alcança em", "mes_projetado", "texto"), coluna("Situação", "situacao", "texto")), "atrasada"));
        }
        return tela.cartao(titulo, partes.toArray(String[]::new));
    }

    /** O valor num caminho dos dados, como o renderizador o resolveria. */
    private static Object caminhoEm(Map<String, Object> dados, String caminho) {
        Object atual = dados;
        for (String parte : caminho.substring(1).split("/")) {
            atual = atual instanceof Map<?, ?> m ? m.get(parte) : ((List<?>) atual).get(Integer.parseInt(parte));
        }
        return atual;
    }

    private Vista relatorio(Chamador chamador, Pedido p) {
        ResumoVisto visto = resumirPeriodo.calcularAgora(chamador, new ResumirPeriodo.Entrada(p.de(), p.ate(), null, null, p.ambito(), null, null, null));
        Map<String, Object> resumo = mapaDe(visto);
        Map<String, Object> dados = dadosCom(visto.moeda());
        dados.put("resumo", resumo);
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        filhos.add(tela.texto("Vista viva: recalcula a cada abertura.", "normal"));
        filhos.addAll(corpoDoResumo(tela, resumo));
        return tela.pagina("Relatório", filhos);
    }

    private List<String> corpoDoResumo(TelaPadrao tela, Map<String, Object> resumo) {
        List<String> filhos = new ArrayList<>();
        filhos.add(tela.totais("Período e totais", List.of(total("De", "/resumo/de", "data"), total("Até", "/resumo/ate", "data"),
                total("Entradas", "/resumo/entradas", "dinheiro"), total("Saídas", "/resumo/saidas", "dinheiro"), total("Saldo", "/resumo/saldo", "dinheiro"))));
        if (resumo.containsKey("aviso_historico")) {
            filhos.add(tela.texto(ref("/resumo/aviso_historico"), "aviso"));
        }
        if (resumo.containsKey("aviso_visao")) {
            filhos.add(tela.texto(ref("/resumo/aviso_visao"), "aviso"));
        }
        if (resumo.get("saldos") instanceof Map<?, ?> saldos) {
            filhos.add(cartaoDeSaldos(tela, saldos));
        }
        if (resumo.get("quebras") instanceof List<?> quebras) {
            for (int i = 0; i < quebras.size(); i++) {
                String nome = ((Map<?, ?>) quebras.get(i)).get("agrupar_por").toString();
                String titulo = TITULOS_QUEBRA.getOrDefault(nome, "Agrupamento");
                String linhas = "/resumo/quebras/" + i + "/linhas";
                List<String> partes = new ArrayList<>();
                if (i == 0) {
                    partes.add(tela.componente("grafico", "rotulo", "Saídas, em moeda da conta", "origem", ref(linhas), "campo_rotulo", "nome",
                            "campo_valor", "saidas", "formato", "dinheiro"));
                }
                partes.add(tela.tabela(titulo, linhas, List.of(coluna("Nome", "nome", "texto"), coluna("Entradas", "entradas", "dinheiro"),
                        coluna("Saídas", "saidas", "dinheiro"))));
                filhos.add(tela.cartao(titulo, partes.toArray(String[]::new)));
            }
        }
        return filhos;
    }

    /** O renderizador lê a moeda de cada valor da própria linha: nada aqui assume a moeda da tela. */
    private static String cartaoDeSaldos(TelaPadrao tela, Map<?, ?> saldos) {
        List<String> partes = new ArrayList<>();
        // A nota de /resumo/saldos/nota fala com o assistente ("nunca some"); a tela explica para o dono.
        partes.add(tela.texto("Saldos no fim do período, convertidos pela última cotação que você registrou até essa data.", "normal"));
        partes.add(tela.texto("Não é a cotação do dia, e ganho ou perda cambial não é calculado.", "normal"));
        partes.add(tela.totais("Saldos em moeda do dono", List.of(total("Saldos até", "/resumo/saldos/data", "data"),
                total("Contas em moeda do dono", "/resumo/saldos/saldo_moeda_dono", "dinheiro"),
                total("Contas em outra moeda, convertidas", "/resumo/saldos/saldo_convertido", "dinheiro"),
                total("Total", "/resumo/saldos/total", "dinheiro"))));
        if (saldos.containsKey("aviso_sem_cotacao")) {
            partes.add(tela.texto("Conta sem cotação na tabela fica fora do total: não há cotação registrada até essa data.", "aviso"));
        }
        partes.add(tela.tabela("Contas em outra moeda", "/resumo/saldos/contas", List.of(coluna("Conta", "nome", "texto"), coluna("Saldo", "saldo", "dinheiro"),
                coluna("Cotação", "cotacao", "numero"), coluna("Data da cotação", "data_cotacao", "data"),
                coluna("Convertido", "saldo_convertido", "dinheiro"))));
        return tela.cartao("Saldos convertidos", partes.toArray(String[]::new));
    }

    private static Map<String, Object> dadosCom(String moeda) {
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("moeda", moeda);
        return dados;
    }

    private String urlDe(String id) {
        return enderecos.url(ReferenciaCurta.ler(id, TipoReferencia.MODULO).orElseThrow());
    }

    private Map<String, Object> mapaDe(Object valor) {
        return json.convertValue(valor, MAPA);
    }
}
