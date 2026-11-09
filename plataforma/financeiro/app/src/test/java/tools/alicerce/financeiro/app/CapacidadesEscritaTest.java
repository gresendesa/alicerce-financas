package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.AMBITO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.CONTA_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.EFEITO_OPERACAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.EVENTO_AUDITORIA;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.ITEM_LANCAMENTO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.LANCAMENTO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.MOVIMENTO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.OPERACAO;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.AmbitoDefinido;
import tools.alicerce.financeiro.aplicacao.ContaDefinida;
import tools.alicerce.financeiro.aplicacao.Contexto;
import tools.alicerce.financeiro.aplicacao.DefinirAmbito;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.LancamentoRegistrado;
import tools.alicerce.financeiro.aplicacao.MeioDefinido;
import tools.alicerce.financeiro.aplicacao.ObterContexto;
import tools.alicerce.financeiro.aplicacao.RegistrarGanho;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;

/** Testes de aceite, nos casos de uso, contra o banco. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class CapacidadesEscritaTest {

    private static final String NOTA = "35261011111111111111650010000000011000000019";

    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired DefinirAmbito definirAmbito;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired RegistrarGanho registrarGanho;
    @Autowired ObterContexto obterContexto;
    @Autowired DSLContext banco;

    private UUID dono;
    private Chamador chamador;
    private int chaves;

    @BeforeEach
    void dono() {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString()).execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:write", "fin:structure"));
    }

    @AfterEach
    void todaEscritaTemOperacao() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
    }

    // Estrutura

    @Test
    void contaComSaldoDeAberturaViraLancamentoContraContaTecnica() {
        ResultadoEscrita<ContaDefinida> criada = conta("corrente", "Itaú", "1500.00");

        assertThat(criada.valor().saldo()).isEqualTo("1500.00");
        assertThat(criada.valor().id()).startsWith("a-");
        assertThat(criada.valor().url()).contains("/fin/a/");
        assertThat(criada.valor().lancamentoAbertura()).startsWith("t-");
        assertThat(criada.operacao().referencia().id()).startsWith("o-");
        assertThat(banco.fetchCount(LANCAMENTO, LANCAMENTO.DONO_ID.eq(dono))).isEqualTo(1);
        assertThat(somaMovimentos()).isZero();

        conta("poupanca", "Poupança", "100.00");
        assertThat(banco.fetch("select id from conta where dono_id = ? and tipo = 'saldo_abertura'", dono)).hasSize(1);
    }

    @Test
    void emprestimoAbreComSaldoDevedor() {
        assertThat(conta("emprestimo", "Consignado", "900.00").valor().saldo()).isEqualTo("-900.00");
    }

    @Test
    void alterarEArquivarGeramOperacaoEVersao() {
        ContaDefinida categoria = conta("categoria", "Mercado", null).valor();

        ResultadoEscrita<ContaDefinida> alterada = definirConta.executar(chamador, new DefinirConta.Entrada(
                chave(), null, categoria.id(), null, "Supermercado", List.of("feira"), null, null, null, null, null));
        ResultadoEscrita<ContaDefinida> arquivada = definirConta.executar(chamador, new DefinirConta.Entrada(
                chave(), null, "feira", null, null, null, null, null, null, null, true));

        assertThat(alterada.valor().nome()).isEqualTo("Supermercado");
        assertThat(arquivada.valor().arquivada()).isTrue();
        assertThat(arquivada.valor().aliases()).containsExactly("feira");
        assertThat(banco.fetchCount(EFEITO_OPERACAO, EFEITO_OPERACAO.TIPO.eq("entidade_alterada")
                .and(EFEITO_OPERACAO.OPERACAO_ID.in(banco.select(OPERACAO.ID).from(OPERACAO).where(OPERACAO.DONO_ID.eq(dono)))))).isEqualTo(2);
        assertThat(banco.fetchCount(CONTA_VERSAO, CONTA_VERSAO.OPERACAO_ID.in(
                banco.select(OPERACAO.ID).from(OPERACAO).where(OPERACAO.DONO_ID.eq(dono))))).isEqualTo(3);
    }

    @Test
    void cartaoPedeOsDiasECreditoSoLiquidaEmCartao() {
        ErroTipado cartao = erro(() -> conta("cartao_credito", "Nubank", null));
        conta("corrente", "Itaú", null);
        ErroTipado credito = erro(() -> meio("credito", "Crédito", "Itaú", false));

        assertThat(cartao.codigo()).isEqualTo(CodigoErro.CAMPOS_FALTANDO);
        assertThat(credito.codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);
    }

    @Test
    void nomeRepetidoNoMesmoNivelEhConflitoEHonraAcentos() {
        conta("categoria", "Saúde", null);

        assertThat(erro(() -> conta("categoria", "saude", null)).codigo()).isEqualTo(CodigoErro.CONFLITO);
    }

    @Test
    void naoArquivaContaComSaldoNemContaDeMeioAtivo() {
        ContaDefinida comSaldo = conta("corrente", "Itaú", "10.00").valor();
        ContaDefinida semSaldo = conta("carteira", "Carteira", null).valor();
        meio("dinheiro", "Dinheiro", "Carteira", false);

        assertThat(erro(() -> arquivarConta(comSaldo.id())).codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);
        assertThat(erro(() -> arquivarConta(semSaldo.id())).codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);
    }

    @Test
    void primeiroAmbitoEhOPadraoENaoPodeSerArquivado() {
        AmbitoDefinido pessoal = ambito("Pessoal", null).valor();

        assertThat(pessoal.padrao()).isTrue();
        assertThat(erro(() -> definirAmbito.executar(chamador, new DefinirAmbito.Entrada(
                chave(), null, "pessoal", null, null, null, true, null))).codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);

        AmbitoDefinido familia = ambito("Família", true).valor();

        assertThat(familia.padrao()).isTrue();
        assertThat(banco.fetchCount(AMBITO, AMBITO.DONO_ID.eq(dono).and(AMBITO.PADRAO.isTrue()))).isEqualTo(1);
        assertThat(definirAmbito.executar(chamador, new DefinirAmbito.Entrada(
                chave(), null, "pessoal", null, null, null, true, null)).valor().arquivado()).isTrue();
    }

    @Test
    void padraoDoMeioTrocaDentroDoTipo() {
        conta("corrente", "Itaú", null);
        meio("pix", "Pix Itaú", "Itaú", true);

        MeioDefinido novo = meio("pix", "Pix Nu", "itau", true).valor();

        assertThat(novo.padrao()).isTrue();
        assertThat(novo.conta()).isEqualTo("Itaú");
        Contexto contexto = obterContexto.executar(chamador);
        assertThat(contexto.meiosPagamento()).filteredOn(Contexto.MeioContexto::padrao)
                .extracting(Contexto.MeioContexto::nome).containsExactly("Pix Nu");
    }

    @Test
    void referenciaInexistenteEAmbiguaTrazemCandidatos() {
        conta("categoria", "Casa", null);
        conta("categoria", "Lazer", null);
        filha("Assinaturas", "Casa");
        filha("Assinaturas", "Lazer");
        conta("corrente", "Itaú Conta", null);

        ErroTipado inexistente = erro(() -> meio("pix", "Pix", "Itaú", false));
        ErroTipado ambigua = erro(() -> conta("categoria", "Filmes", "Assinaturas", null));

        assertThat(inexistente.codigo()).isEqualTo(CodigoErro.REFERENCIA_INEXISTENTE);
        assertThat(inexistente.candidatos()).extracting(ErroTipado.Candidato::nome).containsExactly("Itaú Conta");
        assertThat(ambigua.codigo()).isEqualTo(CodigoErro.REFERENCIA_AMBIGUA);
        assertThat(ambigua.campo()).isEqualTo("conta_pai");
        assertThat(ambigua.candidatos()).hasSize(2);
    }

    // Gastos e ganhos

    @Test
    void gastoSemMeioNemCategoriaListaOsCamposEPerguntaAoDono() {
        ErroTipado erro = erro(() -> gasto("45.00", null, null, null, null, null, null, false));

        assertThat(erro.codigo()).isEqualTo(CodigoErro.CAMPOS_FALTANDO);
        assertThat(erro.camposFaltando()).containsExactly("meio_pagamento", "categoria");
        assertThat(erro.sugestao()).contains("como foi pago", "categoria");
    }

    @Test
    void gastoSomaZeroEVemComSaldoAmbitoEEnderecos() {
        estrutura();

        ResultadoEscrita<LancamentoRegistrado> gasto = gasto("45.90", "Pix Itaú", "Mercado", null, null, null, null, false);

        LancamentoRegistrado valor = gasto.valor();
        assertThat(valor.id()).startsWith("t-");
        assertThat(valor.url()).contains("/fin/t/");
        assertThat(gasto.operacao().referencia().id()).startsWith("o-");
        assertThat(valor.saldoConta()).isEqualTo("954.10");
        assertThat(valor.ambitoInferido()).isTrue();
        assertThat(valor.categoriaInferida()).isFalse();
        assertThat(valor.movimentos()).extracting(LancamentoRegistrado.MovimentoRegistrado::ambito).containsOnly("Pessoal");
        assertThat(somaMovimentos()).isZero();
        assertThat(banco.fetchCount(AMBITO, AMBITO.DONO_ID.eq(dono))).isEqualTo(1);
    }

    @Test
    void ambitoVemDoMeioAntesDoPadraoDoDono() {
        estrutura();
        ambito("Pessoal", null);
        definirAmbito.executar(chamador, new DefinirAmbito.Entrada(chave(), null, null, "Casa", null, null, null, "Pix Itaú"));

        LancamentoRegistrado valor = gasto("10.00", "Pix Itaú", "Mercado", null, null, null, null, false).valor();

        assertThat(valor.ambitoInferido()).isTrue();
        assertThat(valor.movimentos()).extracting(LancamentoRegistrado.MovimentoRegistrado::ambito).containsOnly("Casa");
    }

    @Test
    void mesmaChaveRepeteOResultadoSemGravarDeNovo() {
        estrutura();
        RegistrarGasto.Entrada entrada = entrada(chave(), "45.00", null);

        ResultadoEscrita<LancamentoRegistrado> primeira = registrarGasto.executar(chamador, entrada);
        ResultadoEscrita<LancamentoRegistrado> segunda = registrarGasto.executar(chamador, entrada);

        assertThat(segunda.repetido()).isTrue();
        assertThat(segunda.valor()).isEqualTo(primeira.valor());
        assertThat(banco.fetchCount(LANCAMENTO, LANCAMENTO.DONO_ID.eq(dono).and(LANCAMENTO.TIPO.eq(
                tools.alicerce.financeiro.persistencia.jooq.enums.TipoLancamento.gasto)))).isEqualTo(1);

        assertThat(erro(() -> registrarGasto.executar(chamador, entrada(entrada.chaveIdempotencia(), "46.00", null))).codigo())
                .isEqualTo(CodigoErro.CONFLITO_IDEMPOTENCIA);
    }

    @Test
    void mesmoValorMeioEDataEhPossivelDuplicataAteConfirmar() {
        estrutura();
        LancamentoRegistrado primeiro = gasto("45.00", "Pix Itaú", "Mercado", null, null, null, null, false).valor();

        ErroTipado erro = erro(() -> gasto("45.00", "Pix Itaú", "Mercado", null, null, null, null, false));

        assertThat(erro.codigo()).isEqualTo(CodigoErro.POSSIVEL_DUPLICATA);
        assertThat(erro.candidatos()).extracting(ErroTipado.Candidato::id).containsExactly(primeiro.id());
        assertThat(erro.candidatos().get(0).url()).isEqualTo(primeiro.url());
        assertThat(gasto("45.00", "Pix Itaú", "Mercado", null, null, null, Boolean.TRUE, false).valor().id()).isNotEqualTo(primeiro.id());
        assertThat(gasto("45.01", "Pix Itaú", "Mercado", null, null, null, null, false).valor().id()).isNotNull();
    }

    @Test
    void mesmaNotaDuasVezesAponteParaOLancamentoExistente() {
        estrutura();
        LancamentoRegistrado primeiro = gasto("45.00", "Pix Itaú", "Mercado", null, null, NOTA, null, false).valor();

        ErroTipado erro = erro(() -> gasto("99.00", "Pix Itaú", "Mercado", null, null, NOTA, Boolean.TRUE, false));

        assertThat(erro.codigo()).isEqualTo(CodigoErro.NOTA_DUPLICADA);
        assertThat(erro.candidatos()).extracting(ErroTipado.Candidato::id).containsExactly(primeiro.id());
    }

    @Test
    void itensDeCategoriasEAmbitosDiferentesViramMovimentosSeparados() {
        estrutura();
        conta("categoria", "Farmácia", null);
        ambito("Pessoal", null);
        ambito("Família", null);

        LancamentoRegistrado valor = gasto("100.00", "Pix Itaú", null, null, List.of(
                new RegistrarGasto.Item("Arroz", "2", "30.00", "Mercado", "Família"),
                new RegistrarGasto.Item("Feijão", "1.5", "20.00", "Mercado", "Família"),
                new RegistrarGasto.Item("Remédio", null, "50.00", "Farmácia", "Pessoal")), null, null, false).valor();

        assertThat(valor.ambitoInferido()).isFalse();
        assertThat(valor.movimentos()).extracting(m -> m.conta() + " " + m.valor() + " " + m.ambito()).containsExactlyInAnyOrder(
                "Mercado 50.00 Família", "Farmácia 50.00 Pessoal", "Itaú -50.00 Família", "Itaú -50.00 Pessoal");
        assertThat(banco.fetchCount(ITEM_LANCAMENTO, ITEM_LANCAMENTO.LANCAMENTO_ID.in(
                banco.select(LANCAMENTO.ID).from(LANCAMENTO).where(LANCAMENTO.DONO_ID.eq(dono))))).isEqualTo(3);
        assertThat(somaMovimentos()).isZero();
    }

    @Test
    void somaDosItensDiferenteDoTotalEhRecusadaENaoGravaNada() {
        estrutura();
        int antes = banco.fetchCount(OPERACAO, OPERACAO.DONO_ID.eq(dono));

        ErroTipado erro = erro(() -> gasto("100.00", "Pix Itaú", "Mercado", null, List.of(
                new RegistrarGasto.Item("Arroz", null, "99.99", null, null)), null, null, false));

        assertThat(erro.codigo()).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
        assertThat(banco.fetchCount(OPERACAO, OPERACAO.DONO_ID.eq(dono))).isEqualTo(antes);
        assertThat(banco.fetchCount(EVENTO_AUDITORIA, EVENTO_AUDITORIA.DONO_ID.eq(dono)
                .and(EVENTO_AUDITORIA.CODIGO_ERRO.eq("VALIDACAO_FALHOU").or(EVENTO_AUDITORIA.CODIGO_ERRO.eq("validacao_falhou"))))).isEqualTo(1);
    }

    @Test
    void simularDevolveOQueSeriaGravadoSemGravar() {
        estrutura();
        int operacoes = banco.fetchCount(OPERACAO, OPERACAO.DONO_ID.eq(dono));
        int lancamentos = banco.fetchCount(LANCAMENTO, LANCAMENTO.DONO_ID.eq(dono));
        int auditoria = banco.fetchCount(EVENTO_AUDITORIA, EVENTO_AUDITORIA.DONO_ID.eq(dono));

        ResultadoEscrita<LancamentoRegistrado> simulado = gasto("45.00", "Pix Itaú", "Mercado", null, null, NOTA, null, true);

        assertThat(simulado.simulado()).isTrue();
        assertThat(simulado.valor().id()).isNull();
        assertThat(simulado.valor().saldoConta()).isEqualTo("955.00");
        assertThat(simulado.valor().movimentos()).hasSize(2);
        assertThat(banco.fetchCount(OPERACAO, OPERACAO.DONO_ID.eq(dono))).isEqualTo(operacoes);
        assertThat(banco.fetchCount(LANCAMENTO, LANCAMENTO.DONO_ID.eq(dono))).isEqualTo(lancamentos);
        assertThat(banco.fetchCount(AMBITO, AMBITO.DONO_ID.eq(dono))).isZero();
        assertThat(banco.fetchCount(EVENTO_AUDITORIA, EVENTO_AUDITORIA.DONO_ID.eq(dono))).isEqualTo(auditoria + 1);
        // A nota não ficou reservada.
        assertThat(gasto("45.00", "Pix Itaú", "Mercado", null, null, NOTA, null, false).simulado()).isFalse();
    }

    @Test
    void ganhoEntraNaContaEExigeFonteDeRenda() {
        estrutura();
        conta("fonte_renda", "Salário", null);

        ErroTipado semFonte = erro(() -> ganho(null));
        LancamentoRegistrado valor = ganho("Salário").valor();

        assertThat(semFonte.codigo()).isEqualTo(CodigoErro.CAMPOS_FALTANDO);
        assertThat(semFonte.camposFaltando()).containsExactly("categoria");
        assertThat(valor.saldoConta()).isEqualTo("6000.00");
        assertThat(valor.movimentos()).extracting(m -> m.conta() + " " + m.valor())
                .containsExactlyInAnyOrder("Itaú 5000.00", "Salário -5000.00");
        assertThat(somaMovimentos()).isZero();
    }

    @Test
    void todoMovimentoDeGastoOuGanhoTemAmbito() {
        estrutura();
        conta("fonte_renda", "Salário", null);
        gasto("10.00", "Pix Itaú", "Mercado", null, null, null, null, false);
        ganho("Salário");

        assertThat(banco.fetchCount(MOVIMENTO, MOVIMENTO.AMBITO_ID.isNull().and(MOVIMENTO.LANCAMENTO_ID.in(
                banco.select(LANCAMENTO.ID).from(LANCAMENTO).where(LANCAMENTO.DONO_ID.eq(dono),
                        LANCAMENTO.TIPO.ne(tools.alicerce.financeiro.persistencia.jooq.enums.TipoLancamento.saldo_abertura)))))).isZero();
    }

    @Test
    void contextoMostraEstruturaComSaldosESemContaTecnica() {
        estrutura();
        gasto("100.00", "Pix Itaú", "Mercado", null, null, null, null, false);

        Contexto contexto = obterContexto.executar(chamador);

        assertThat(contexto.contas()).extracting(Contexto.ContaContexto::nome).containsExactlyInAnyOrder("Itaú", "Mercado");
        assertThat(contexto.contas()).filteredOn(c -> c.nome().equals("Itaú")).extracting(Contexto.ContaContexto::saldo).containsExactly("900.00");
        assertThat(contexto.contas()).filteredOn(c -> c.nome().equals("Mercado")).extracting(Contexto.ContaContexto::saldo).containsOnlyNulls();
        assertThat(contexto.contas()).allSatisfy(c -> assertThat(c.id()).startsWith("a-"));
        assertThat(contexto.ambitos()).extracting(Contexto.AmbitoContexto::nome).containsExactly("Pessoal");
    }

    // Apoio

    private void estrutura() {
        conta("corrente", "Itaú", "1000.00");
        conta("categoria", "Mercado", null);
        meio("pix", "Pix Itaú", "Itaú", true);
    }

    private ResultadoEscrita<ContaDefinida> conta(String tipo, String nome, String saldo) {
        return conta(tipo, nome, null, saldo);
    }

    private ResultadoEscrita<ContaDefinida> conta(String tipo, String nome, String pai, String saldo) {
        return definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, tipo, nome, null, null, pai, saldo, "2020-01-01", null));
    }

    private void filha(String nome, String pai) {
        conta("categoria", nome, banco.fetchValue("select id from conta where dono_id = ? and nome = ?", dono, pai).toString(), null);
    }

    private void arquivarConta(String ref) {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, ref, null, null, null, null, null, null, null, true));
    }

    private ResultadoEscrita<MeioDefinido> meio(String tipo, String nome, String conta, boolean padrao) {
        return definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, tipo, nome, null, conta, padrao, null));
    }

    private ResultadoEscrita<AmbitoDefinido> ambito(String nome, Boolean padrao) {
        return definirAmbito.executar(chamador, new DefinirAmbito.Entrada(chave(), null, null, nome, null, padrao, null, null));
    }

    private ResultadoEscrita<LancamentoRegistrado> gasto(String valor, String meio, String categoria, String ambito,
            List<RegistrarGasto.Item> itens, String nota, Boolean confirmar, boolean simular) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, "Compra", meio, "conversa",
                null, categoria, ambito, itens, null, nota, null, null, confirmar, simular));
    }

    private RegistrarGasto.Entrada entrada(String chave, String valor, Boolean confirmar) {
        return new RegistrarGasto.Entrada(chave, null, valor, "Compra", "Pix Itaú", "conversa", "2026-10-30", "Mercado",
                null, null, null, null, null, null, confirmar, null);
    }

    private ResultadoEscrita<LancamentoRegistrado> ganho(String fonte) {
        return registrarGanho.executar(chamador, new RegistrarGanho.Entrada(chave(), null, "5000.00", "Salário de outubro",
                "Itaú", fonte, null, null, null, null, null, null, null, null, null));
    }

    private long somaMovimentos() {
        Number soma = banco.fetchValue("""
                select coalesce(sum(valor_centavos), 0) from movimento m join lancamento l on l.id = m.lancamento_id
                where l.dono_id = ?""", dono) instanceof Number n ? n : 0;
        return soma.longValue();
    }

    private String chave() {
        return "chave-" + ++chaves;
    }

    private static ErroTipado erro(Runnable chamada) {
        return catchThrowableOfType(ErroTipado.class, chamada::run);
    }
}
