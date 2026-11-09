package tools.alicerce.financeiro.aplicacao;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.Efeito;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.Operacao;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.Versao;
import tools.alicerce.financeiro.aplicacao.RepositorioLancamentos.Cadeia;
import tools.alicerce.financeiro.aplicacao.ReversaoRegistrada.EntidadeRevertida;
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.CartaoCredito;
import tools.alicerce.financeiro.dominio.ClasseConta;
import tools.alicerce.financeiro.dominio.Compromisso;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.Meta;
import tools.alicerce.financeiro.dominio.Movimento;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * O inverso de cada efeito. Um efeito só se inverte se o que ele deixou continua como ele deixou;
 * senão, a operação que mudou aquilo entra em {@link ReversaoEmCurso#dependentes} e o inverso é pulado.
 * Quem chama recusa o desfazer no fim, para listar todos os dependentes de uma vez.
 *
 * <p>A comparação é pelo estado, não pelo número da versão: desfazer o desfazer grava uma versão nova
 * com o mesmo conteúdo, e a operação original precisa continuar reversível.
 */
@Component
class Inversos {

    static final String MOTIVO = "Desfeito";

    private final RepositorioLancamentos lancamentos;
    private final RepositorioEstrutura estrutura;
    private final RepositorioHistorico historico;
    private final RegistroDeLancamento registro;
    private final Comprovantes comprovantes;
    private final RepositorioCartoes cartoes;
    private final Faturas faturas;
    private final RepositorioCompromissos compromissos;
    private final RepositorioMetas metas;
    private final JsonMapper json = JsonMapper.builder().build();

    Inversos(RepositorioLancamentos lancamentos, RepositorioEstrutura estrutura, RepositorioHistorico historico,
            RegistroDeLancamento registro, Comprovantes comprovantes, RepositorioCartoes cartoes, Faturas faturas,
            RepositorioCompromissos compromissos, RepositorioMetas metas) {
        this.compromissos = compromissos;
        this.metas = metas;
        this.lancamentos = lancamentos;
        this.estrutura = estrutura;
        this.historico = historico;
        this.registro = registro;
        this.comprovantes = comprovantes;
        this.cartoes = cartoes;
        this.faturas = faturas;
    }

    void aplicar(OperacaoEmCurso operacao, Operacao alvo, Efeito e, ReversaoEmCurso r) {
        switch (e.tipo()) {
            case LANCAMENTO_CRIADO, LANCAMENTO_ESTORNADO -> lancamento(operacao, e.entidadeId(), r);
            case CHAVE_FISCAL_MOVIDA -> chave(operacao, e, r);
            case ENTIDADE_CRIADA -> criada(operacao, alvo, e, r);
            case ENTIDADE_ALTERADA -> alterada(operacao, e, r);
            case OPERACAO_DESFEITA -> operacaoDesfeita(operacao, e, r);
            case OPERACAO_RESTABELECIDA -> operacaoRestabelecida(operacao, e, r);
            case COMPROVANTE_VINCULADO -> comprovante(operacao, e, r);
            // Sem inverso: a página recusa lançamento anulado, e o link volta a valer se ele for restabelecido.
            case TOKEN_ANEXO_EMITIDO -> r.impressao.add("token " + e.entidadeId());
            case FATURA_CRIADA -> {
                // Sem inverso: vazia, a fatura não muda nenhum número.
                r.impressao.add("fatura " + e.entidadeId());
                r.mantidas.add("fatura");
            }
            // Sem inverso próprio: a leitura ignora conferência e ocorrência de operação desfeita, e o ajuste é
            // um lançamento, estornado pelo seu próprio efeito.
            case CONFERENCIA_REGISTRADA -> r.impressao.add("conferencia " + e.entidadeId());
            case OCORRENCIA_REGISTRADA -> r.impressao.add("ocorrencia " + e.entidadeId());
            // Sem inverso próprio: a leitura ignora a declaração de operação desfeita, e a anterior volta a valer.
            case DECLARACAO_REGISTRADA -> r.impressao.add("declaracao " + e.entidadeId());
            // Sem inverso próprio: a página pública só abre link de operação aplicada e revogação desfeita não conta.
            case LINK_CRIADO -> {
                r.impressao.add("link " + e.entidadeId());
                r.externos.add("O link de prestação de contas deixa de abrir, mas quem já o abriu pode ter guardado o que viu.");
            }
            case LINK_REVOGADO -> {
                r.impressao.add("revogacao " + e.entidadeId());
                r.externos.add("O link de prestação de contas volta a abrir sem login, até o fim da validade.");
            }
            case RETRATO_GRAVADO -> {
                // Sem inverso: o retrato é imutável.
                r.impressao.add("retrato " + e.entidadeId());
                r.mantidas.add("retrato");
            }
        }
    }

    private void lancamento(OperacaoEmCurso operacao, UUID id, ReversaoEmCurso r) {
        UUID dono = operacao.dono();
        Cadeia cadeia = lancamentos.cadeia(dono, id);
        r.impressao.add("lancamento " + id + " " + cadeia.tamanho());
        if (cadeia.passosDoAlvo() % 2 == 1) {
            r.dependentes.add(cadeia.operacaoDaPonta());
            return;
        }
        boolean restabelece = (cadeia.tamanho() + 1) % 2 == 0;
        if (restabelece) {
            // Duas vigentes com a mesma nota não podem existir: a nota precisa estar de volta aqui.
            for (String chave : historico.chavesQueSairamDe(cadeia.raiz())) {
                UUID atual = lancamentos.lancamentoApontadoPelaChave(dono, chave).orElseThrow();
                if (!atual.equals(cadeia.raiz()) && !r.chavesDoAlvo.contains(chave)) {
                    r.dependentes.add(lancamentos.operacaoDe(atual));
                    return;
                }
            }
        }
        Lancamento estorno = registro.estornar(operacao, id, MOTIVO);
        estorno.movimentos().stream().map(Movimento::conta).forEach(r.contasTocadas::add);
        estorno.movimentos().stream().flatMap(m -> java.util.stream.Stream.of(m.fatura(), m.faturaAbertura())).filter(Objects::nonNull)
                .forEach(r.faturasTocadas::add);
        r.lancamentos.put(cadeia.raiz(), restabelece);
    }

    /** Depois de todos os inversos: uma reversão não pode deixar pagamento sobrando nem abertura menor que o detalhe. */
    void conferirFaturas(OperacaoEmCurso operacao, ReversaoEmCurso r) {
        r.dependentes.addAll(faturas.impedimentos(operacao.dono(), r.faturasTocadas));
    }

    private void chave(OperacaoEmCurso operacao, Efeito e, ReversaoEmCurso r) {
        r.impressao.add("chave " + e.detalhe() + " " + e.entidadeId());
        registro.moverChave(operacao, e.detalhe(), e.entidadeId());
    }

    /** Devolve o comprovante para onde estava. O arquivo nunca sai do bucket. */
    private void comprovante(OperacaoEmCurso operacao, Efeito e, ReversaoEmCurso r) {
        VinculoComprovante vinculo = VinculoComprovante.ler(e.detalhe());
        r.impressao.add("comprovante " + e.entidadeId() + " " + e.detalhe());
        if (!comprovantes.vinculadoA(operacao.dono(), e.entidadeId(), vinculo.para())) {
            r.dependentes.add(historico.ultimoVinculo(e.entidadeId()).orElseThrow());
            return;
        }
        comprovantes.mover(operacao, e.entidadeId(), vinculo.para(), vinculo.de());
        if (vinculo.de() == null) {
            r.mantidas.add("arquivo do comprovante");
        }
    }

    private void criada(OperacaoEmCurso operacao, Operacao alvo, Efeito e, ReversaoEmCurso r) {
        EntidadeVersionada tipo = EntidadeVersionada.valueOf(e.entidade().toUpperCase(java.util.Locale.ROOT));
        UUID dono = operacao.dono();
        if (tipo == EntidadeVersionada.INSTITUICAO) {
            r.impressao.add("mantida " + e.entidadeId());
            r.mantidas.add("instituição");
            return;
        }
        if (tipo == EntidadeVersionada.CARTAO_CREDITO) {
            // Os dados ficam com a conta, que é arquivada pelo inverso seguinte.
            r.impressao.add("mantida " + e.entidadeId());
            return;
        }
        Object atual = atual(dono, tipo, e.entidadeId());
        if (basica(alvo, atual)) {
            r.impressao.add("mantida " + e.entidadeId());
            r.mantidas.add(nome(atual));
            return;
        }
        r.impressao.add("arquivar " + e.entidadeId() + " " + versao(atual));
        if (!comoDeixou(tipo, e.entidadeId(), 1, atual, r)) {
            return;
        }
        switch (atual) {
            case Conta c -> arquivarConta(operacao, c, r);
            case Ambito a -> arquivarAmbito(operacao, a, r);
            case MeioPagamento m -> arquivarMeio(operacao, m, r);
            case Compromisso c -> arquivarCompromisso(operacao, c, r);
            case Meta m -> arquivarMeta(operacao, m, r);
            default -> throw new IllegalStateException("Entidade sem inverso: " + tipo);
        }
    }

    /**
     * Estrutura que o sistema cria sozinho na primeira operação que precisa dela. Outras operações
     * passam a contar com ela, então desfazer a primeira não a arquiva.
     */
    private static boolean basica(Operacao alvo, Object entidade) {
        return entidade instanceof Conta c && c.classe() == ClasseConta.PATRIMONIO
                || entidade instanceof Ambito && !alvo.capacidade().equals("definir_ambito");
    }

    private void alterada(OperacaoEmCurso operacao, Efeito e, ReversaoEmCurso r) {
        EntidadeVersionada tipo = EntidadeVersionada.valueOf(e.entidade().toUpperCase(java.util.Locale.ROOT));
        Object atual = atual(operacao.dono(), tipo, e.entidadeId());
        r.impressao.add("restaurar " + e.entidadeId() + " " + versao(atual));
        if (!comoDeixou(tipo, e.entidadeId(), e.versaoAnterior() + 1, atual, r)) {
            return;
        }
        String dados = historico.versao(tipo, e.entidadeId(), e.versaoAnterior()).orElseThrow().dados();
        switch (atual) {
            case Conta c -> restaurarConta(operacao, c, json.readValue(dados, Conta.class), r);
            case CartaoCredito c -> restaurarCartao(operacao, c, json.readValue(dados, CartaoCredito.class), r);
            case Ambito a -> restaurarAmbito(operacao, a, json.readValue(dados, Ambito.class), r);
            case MeioPagamento m -> restaurarMeio(operacao, m, json.readValue(dados, MeioPagamento.class), r);
            case Compromisso c -> restaurarCompromisso(operacao, c, json.readValue(dados, Compromisso.class), r);
            case Meta m -> restaurarMeta(operacao, m, json.readValue(dados, Meta.class), r);
            default -> throw new IllegalStateException("Entidade sem inverso: " + tipo);
        }
    }

    private void restaurarCartao(OperacaoEmCurso operacao, CartaoCredito atual, CartaoCredito anterior, ReversaoEmCurso r) {
        CartaoCredito novo = new CartaoCredito(atual.conta(), anterior.diaFechamento(), anterior.diaVencimento(),
                anterior.limiteCentavos(), anterior.contaPagamento(), atual.versao() + 1);
        cartoes.atualizar(operacao, novo);
        operacao.entidadeAlterada(EntidadeVersionada.CARTAO_CREDITO, novo.conta(), atual.versao(), novo);
        r.restauradas.add(new EntidadeRevertida("cartão", nome(atual, operacao.dono())));
    }

    private String nome(CartaoCredito cartao, UUID dono) {
        return estrutura.contas(dono).stream().filter(c -> c.id().equals(cartao.conta())).map(Conta::nome).findFirst().orElseThrow();
    }

    private void operacaoDesfeita(OperacaoEmCurso operacao, Efeito e, ReversaoEmCurso r) {
        Operacao outra = historico.travar(operacao.dono(), e.entidadeId()).orElseThrow();
        r.impressao.add("operacao " + outra.id() + " " + outra.desfeita());
        if (!outra.desfeita()) {
            r.dependentes.add(outra.id());
            return;
        }
        operacao.operacaoRestabelecida(outra.id());
    }

    private void operacaoRestabelecida(OperacaoEmCurso operacao, Efeito e, ReversaoEmCurso r) {
        Operacao outra = historico.travar(operacao.dono(), e.entidadeId()).orElseThrow();
        r.impressao.add("operacao " + outra.id() + " " + outra.desfeita());
        if (outra.desfeita()) {
            r.dependentes.add(outra.desfeitaPor());
            return;
        }
        operacao.operacaoDesfeita(outra.id());
    }

    /** O estado atual é o que a operação gravou nesta versão? Se não, quem mudou por último é dependente. */
    private boolean comoDeixou(EntidadeVersionada tipo, UUID id, long versao, Object atual, ReversaoEmCurso r) {
        String deixado = historico.versao(tipo, id, versao).orElseThrow().dados();
        // Pelo texto, nos dois lados: número lido de JSON e número de campo long são nós diferentes.
        if (semVersao(json.readTree(deixado)).equals(semVersao(json.readTree(json.writeValueAsString(atual))))) {
            return true;
        }
        r.dependentes.add(historico.ultimaVersao(tipo, id).map(Versao::operacao).orElseThrow());
        return false;
    }

    private static JsonNode semVersao(JsonNode estado) {
        ObjectNode copia = (ObjectNode) estado.deepCopy();
        copia.remove("versao");
        return copia;
    }

    private void arquivarConta(OperacaoEmCurso operacao, Conta c, ReversaoEmCurso r) {
        if (!semDependentesDaConta(operacao.dono(), c, r)) {
            return;
        }
        gravarConta(operacao, c, new Conta(c.id(), c.classe(), c.tipo(), c.nome(), c.aliases(), c.contaPai(), c.instituicao(),
                c.moeda(), true, c.versao() + 1));
        r.arquivadas.add(new EntidadeRevertida("conta", c.nome()));
    }

    private void restaurarConta(OperacaoEmCurso operacao, Conta atual, Conta anterior, ReversaoEmCurso r) {
        UUID dono = operacao.dono();
        if (anterior.arquivada() && !atual.arquivada() && !semDependentesDaConta(dono, atual, r)) {
            return;
        }
        if (!anterior.moeda().equals(atual.moeda())) {
            // Um movimento já gravado prende a conta à moeda de agora.
            Set<UUID> comMovimentos = lancamentos.operacoesComMovimentos(atual.id());
            if (!comMovimentos.isEmpty()) {
                r.dependentes.addAll(comMovimentos);
                return;
            }
        }
        Optional<Conta> mesmoNome = estrutura.contas(dono).stream().filter(c -> !c.id().equals(atual.id())
                && c.classe() == anterior.classe() && Objects.equals(c.contaPai(), anterior.contaPai())
                && Referencias.normalizar(c.nome()).equals(Referencias.normalizar(anterior.nome()))).findFirst();
        if (mesmoNome.isPresent()) {
            r.dependentes.add(ultima(EntidadeVersionada.CONTA, mesmoNome.get().id()));
            return;
        }
        gravarConta(operacao, atual, new Conta(anterior.id(), anterior.classe(), anterior.tipo(), anterior.nome(),
                anterior.aliases(), anterior.contaPai(), anterior.instituicao(), anterior.moeda(), anterior.arquivada(),
                atual.versao() + 1));
        r.restauradas.add(new EntidadeRevertida("conta", anterior.nome()));
    }

    private void gravarConta(OperacaoEmCurso operacao, Conta atual, Conta nova) {
        estrutura.atualizarConta(operacao, nova);
        operacao.entidadeAlterada(EntidadeVersionada.CONTA, nova.id(), atual.versao(), nova);
    }

    private boolean semDependentesDaConta(UUID dono, Conta c, ReversaoEmCurso r) {
        int antes = r.dependentes.size();
        r.dependentes.addAll(lancamentos.operacoesComConta(c.id()));
        estrutura.meios(dono).stream().filter(m -> !m.arquivado() && m.contaLiquidacao().equals(c.id()))
                .forEach(m -> r.dependentes.add(ultima(EntidadeVersionada.MEIO_PAGAMENTO, m.id())));
        estrutura.contas(dono).stream().filter(f -> !f.arquivada() && c.id().equals(f.contaPai()))
                .forEach(f -> r.dependentes.add(ultima(EntidadeVersionada.CONTA, f.id())));
        return r.dependentes.size() == antes;
    }

    private void arquivarAmbito(OperacaoEmCurso operacao, Ambito a, ReversaoEmCurso r) {
        if (!semDependentesDoAmbito(operacao.dono(), a, r)) {
            return;
        }
        // Deixa de ser padrão aqui; se havia outro antes, o inverso seguinte o restaura.
        gravarAmbito(operacao, a, new Ambito(a.id(), a.nome(), a.aliases(), false, true, a.versao() + 1));
        r.arquivadas.add(new EntidadeRevertida("âmbito", a.nome()));
    }

    private void restaurarAmbito(OperacaoEmCurso operacao, Ambito atual, Ambito anterior, ReversaoEmCurso r) {
        UUID dono = operacao.dono();
        if (anterior.arquivado() && !atual.arquivado() && !semDependentesDoAmbito(dono, atual, r)) {
            return;
        }
        Optional<Ambito> conflito = estrutura.ambitos(dono).stream().filter(a -> !a.id().equals(atual.id())
                && (anterior.padrao() && a.padrao()
                        || Referencias.normalizar(a.nome()).equals(Referencias.normalizar(anterior.nome())))).findFirst();
        if (conflito.isPresent()) {
            r.dependentes.add(ultima(EntidadeVersionada.AMBITO, conflito.get().id()));
            return;
        }
        gravarAmbito(operacao, atual, new Ambito(anterior.id(), anterior.nome(), anterior.aliases(), anterior.padrao(),
                anterior.arquivado(), atual.versao() + 1));
        r.restauradas.add(new EntidadeRevertida("âmbito", anterior.nome()));
    }

    private void gravarAmbito(OperacaoEmCurso operacao, Ambito atual, Ambito novo) {
        estrutura.atualizarAmbito(operacao, novo);
        operacao.entidadeAlterada(EntidadeVersionada.AMBITO, novo.id(), atual.versao(), novo);
    }

    private boolean semDependentesDoAmbito(UUID dono, Ambito a, ReversaoEmCurso r) {
        int antes = r.dependentes.size();
        r.dependentes.addAll(lancamentos.operacoesComAmbito(a.id()));
        estrutura.meios(dono).stream().filter(m -> !m.arquivado() && a.id().equals(m.ambitoPadrao()))
                .forEach(m -> r.dependentes.add(ultima(EntidadeVersionada.MEIO_PAGAMENTO, m.id())));
        return r.dependentes.size() == antes;
    }

    private void arquivarMeio(OperacaoEmCurso operacao, MeioPagamento m, ReversaoEmCurso r) {
        if (!semDependentesDoMeio(m, r)) {
            return;
        }
        gravarMeio(operacao, m, new MeioPagamento(m.id(), m.tipo(), m.nome(), m.aliases(), m.contaLiquidacao(), false,
                m.ambitoPadrao(), true, m.versao() + 1));
        r.arquivadas.add(new EntidadeRevertida("meio de pagamento", m.nome()));
    }

    private void restaurarMeio(OperacaoEmCurso operacao, MeioPagamento atual, MeioPagamento anterior, ReversaoEmCurso r) {
        if (anterior.arquivado() && !atual.arquivado() && !semDependentesDoMeio(atual, r)) {
            return;
        }
        Optional<MeioPagamento> conflito = estrutura.meios(operacao.dono()).stream().filter(m -> !m.id().equals(atual.id())
                && (anterior.padrao() && m.padrao() && m.tipo() == anterior.tipo()
                        || Referencias.normalizar(m.nome()).equals(Referencias.normalizar(anterior.nome())))).findFirst();
        if (conflito.isPresent()) {
            r.dependentes.add(ultima(EntidadeVersionada.MEIO_PAGAMENTO, conflito.get().id()));
            return;
        }
        gravarMeio(operacao, atual, new MeioPagamento(anterior.id(), anterior.tipo(), anterior.nome(), anterior.aliases(),
                anterior.contaLiquidacao(), anterior.padrao(), anterior.ambitoPadrao(), anterior.arquivado(), atual.versao() + 1));
        r.restauradas.add(new EntidadeRevertida("meio de pagamento", anterior.nome()));
    }

    private void gravarMeio(OperacaoEmCurso operacao, MeioPagamento atual, MeioPagamento novo) {
        estrutura.atualizarMeio(operacao, novo);
        operacao.entidadeAlterada(EntidadeVersionada.MEIO_PAGAMENTO, novo.id(), atual.versao(), novo);
    }

    private boolean semDependentesDoMeio(MeioPagamento m, ReversaoEmCurso r) {
        int antes = r.dependentes.size();
        r.dependentes.addAll(lancamentos.operacoesComMeio(m.id()));
        return r.dependentes.size() == antes;
    }

    /**
     * Desfazer a criação encerra o compromisso antes do primeiro vencimento, para nenhuma ocorrência existir.
     * Pagamento, pulo ou adiamento que ainda valem impedem: desfaça-os antes.
     */
    private void arquivarCompromisso(OperacaoEmCurso operacao, Compromisso c, ReversaoEmCurso r) {
        Set<UUID> comOcorrencias = compromissos.operacoesComOcorrencias(c.id());
        if (!comOcorrencias.isEmpty()) {
            r.dependentes.addAll(comOcorrencias);
            return;
        }
        gravarCompromisso(operacao, c, comEstado(c, c, true, c.primeiroVencimento().minusDays(1)));
        r.arquivadas.add(new EntidadeRevertida("compromisso", c.nome()));
    }

    private void restaurarCompromisso(OperacaoEmCurso operacao, Compromisso atual, Compromisso anterior, ReversaoEmCurso r) {
        if (!anterior.encerrado()) {
            Optional<Compromisso> mesmoNome = compromissos.compromissos(operacao.dono()).stream().filter(c -> !c.id().equals(atual.id())
                    && !c.encerrado() && Referencias.normalizar(c.nome()).equals(Referencias.normalizar(anterior.nome()))).findFirst();
            if (mesmoNome.isPresent()) {
                r.dependentes.add(ultima(EntidadeVersionada.COMPROMISSO, mesmoNome.get().id()));
                return;
            }
        }
        gravarCompromisso(operacao, atual, comEstado(anterior, atual, anterior.encerrado(), anterior.fim()));
        r.restauradas.add(new EntidadeRevertida("compromisso", anterior.nome()));
    }

    /** O conteúdo de {@code base}, na versão seguinte à de {@code atual}. */
    private static Compromisso comEstado(Compromisso base, Compromisso atual, boolean encerrado, java.time.LocalDate fim) {
        return new Compromisso(base.id(), base.direcao(), base.tipo(), base.nome(), base.aliases(), base.contraparte(), base.referencia(),
                base.valorCentavos(), base.valorTotalCentavos(), base.moeda(), base.frequencia(), base.primeiroVencimento(), base.parcelas(),
                base.parcelasPagasAntes(), fim, base.categoria(), base.conta(), base.meioPagamento(), base.ambito(), base.observacao(), encerrado,
                atual.versao() + 1);
    }

    private void gravarCompromisso(OperacaoEmCurso operacao, Compromisso atual, Compromisso novo) {
        compromissos.atualizar(operacao, novo);
        operacao.entidadeAlterada(EntidadeVersionada.COMPROMISSO, novo.id(), atual.versao(), novo);
    }

    /**
     * Desfazer a criação arquiva a meta. Aporte ou resgate que ainda vale impede: desfaça-os antes, para o dinheiro não
     * ficar marcado com uma meta que some.
     */
    private void arquivarMeta(OperacaoEmCurso operacao, Meta m, ReversaoEmCurso r) {
        Set<UUID> comAportes = metas.operacoesComAportes(m.id());
        if (!comAportes.isEmpty()) {
            r.dependentes.addAll(comAportes);
            return;
        }
        gravarMeta(operacao, m, new Meta(m.id(), m.nome(), m.aliases(), m.alvoCentavos(), m.moeda(), m.dataAlvo(), m.prioridade(),
                m.contaReservada(), m.situacao(), m.observacao(), true, m.versao() + 1));
        r.arquivadas.add(new EntidadeRevertida("meta", m.nome()));
    }

    /** Restaura a versão anterior, se o nome e a conta reservada dela não foram tomados por outra meta desde então. */
    private void restaurarMeta(OperacaoEmCurso operacao, Meta atual, Meta anterior, ReversaoEmCurso r) {
        if (anterior.arquivada() && !atual.arquivada()) {
            Set<UUID> comAportes = metas.operacoesComAportes(atual.id());
            if (!comAportes.isEmpty()) {
                r.dependentes.addAll(comAportes);
                return;
            }
        }
        if (!anterior.arquivada()) {
            Optional<Meta> conflito = metas.metas(operacao.dono()).stream().filter(m -> !m.id().equals(atual.id()) && !m.arquivada()
                    && (Referencias.normalizar(m.nome()).equals(Referencias.normalizar(anterior.nome()))
                            || anterior.emAndamento() && m.emAndamento() && anterior.contaReservada() != null
                                    && anterior.contaReservada().equals(m.contaReservada()))).findFirst();
            if (conflito.isPresent()) {
                r.dependentes.add(ultima(EntidadeVersionada.META, conflito.get().id()));
                return;
            }
        }
        gravarMeta(operacao, atual, new Meta(anterior.id(), anterior.nome(), anterior.aliases(), anterior.alvoCentavos(), anterior.moeda(),
                anterior.dataAlvo(), anterior.prioridade(), anterior.contaReservada(), anterior.situacao(), anterior.observacao(),
                anterior.arquivada(), atual.versao() + 1));
        r.restauradas.add(new EntidadeRevertida("meta", anterior.nome()));
    }

    private void gravarMeta(OperacaoEmCurso operacao, Meta atual, Meta nova) {
        metas.atualizar(operacao, nova);
        operacao.entidadeAlterada(EntidadeVersionada.META, nova.id(), atual.versao(), nova);
    }

    private UUID ultima(EntidadeVersionada tipo, UUID id) {
        return historico.ultimaVersao(tipo, id).map(Versao::operacao).orElseThrow();
    }

    private Object atual(UUID dono, EntidadeVersionada tipo, UUID id) {
        if (tipo == EntidadeVersionada.CARTAO_CREDITO) {
            return cartoes.cartao(id).orElseThrow();
        }
        List<?> entidades = switch (tipo) {
            case CONTA -> estrutura.contas(dono);
            case AMBITO -> estrutura.ambitos(dono);
            case MEIO_PAGAMENTO -> estrutura.meios(dono);
            case COMPROMISSO -> compromissos.compromissos(dono);
            case META -> metas.metas(dono);
            case INSTITUICAO, CARTAO_CREDITO -> throw new IllegalStateException("Sem lista: " + tipo);
        };
        return entidades.stream().filter(e -> id(e).equals(id)).findFirst().orElseThrow();
    }

    private static UUID id(Object e) {
        return switch (e) {
            case Conta c -> c.id();
            case Ambito a -> a.id();
            case MeioPagamento m -> m.id();
            case Compromisso c -> c.id();
            case Meta m -> m.id();
            default -> throw new IllegalArgumentException();
        };
    }

    private static String nome(Object e) {
        return switch (e) {
            case Conta c -> c.nome();
            case Ambito a -> a.nome();
            case MeioPagamento m -> m.nome();
            case Compromisso c -> c.nome();
            case Meta m -> m.nome();
            default -> throw new IllegalArgumentException();
        };
    }

    private static long versao(Object e) {
        return switch (e) {
            case Conta c -> c.versao();
            case CartaoCredito c -> c.versao();
            case Ambito a -> a.versao();
            case MeioPagamento m -> m.versao();
            case Compromisso c -> c.versao();
            case Meta m -> m.versao();
            default -> throw new IllegalArgumentException();
        };
    }
}
