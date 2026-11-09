package tools.alicerce.financeiro.aplicacao;

import java.time.Clock;
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
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.dominio.ClasseConta;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.DirecaoCompromisso;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.OrigemLancamento;
import tools.alicerce.financeiro.dominio.Partidas;
import tools.alicerce.financeiro.dominio.Procedencia;
import tools.alicerce.financeiro.dominio.TipoLancamento;
import tools.alicerce.financeiro.dominio.ValorOriginal;

/** Capacidade {@code registrar_ganho}: o dinheiro entra numa conta, vindo de uma fonte de renda. */
@Component
public class RegistrarGanho {

    public record Entrada(String chaveIdempotencia, String lote, String valor, String descricao, String conta,
            String categoria, String origem, String data, String ambito, String cnpjEstabelecimento, String chaveNfce,
            String contraparte, String observacao, Boolean confirmarDuplicata, Boolean simular, EntradaMoeda moeda, String compromisso,
            String ocorrencia) {

        public Entrada(String chaveIdempotencia, String lote, String valor, String descricao, String conta,
                String categoria, String origem, String data, String ambito, String cnpjEstabelecimento, String chaveNfce,
                String contraparte, String observacao, Boolean confirmarDuplicata, Boolean simular, EntradaMoeda moeda) {
            this(chaveIdempotencia, lote, valor, descricao, conta, categoria, origem, data, ambito, cnpjEstabelecimento,
                    chaveNfce, contraparte, observacao, confirmarDuplicata, simular, moeda, null, null);
        }

        public Entrada(String chaveIdempotencia, String lote, String valor, String descricao, String conta,
                String categoria, String origem, String data, String ambito, String cnpjEstabelecimento, String chaveNfce,
                String contraparte, String observacao, Boolean confirmarDuplicata, Boolean simular) {
            this(chaveIdempotencia, lote, valor, descricao, conta, categoria, origem, data, ambito, cnpjEstabelecimento,
                    chaveNfce, contraparte, observacao, confirmarDuplicata, simular, null);
        }

        EntradaMoeda moedaOuVazia() {
            return moeda == null ? new EntradaMoeda(null, null, null, null) : moeda;
        }
    }

    private static final Clock RELOGIO = Clock.systemUTC();
    private static final String CAPACIDADE = "registrar_ganho";

    private final RegistradorOperacoes registrador;
    private final RepositorioDono donos;
    private final Referencias referencias;
    private final EstruturaBasica basica;
    private final RegistroDeLancamento registro;
    private final Comprovantes comprovantes;
    private final Historico historico;
    private final Compromissos compromissos;

    RegistrarGanho(RegistradorOperacoes registrador, RepositorioDono donos, Referencias referencias,
            EstruturaBasica basica, RegistroDeLancamento registro, Comprovantes comprovantes, Historico historico,
            Compromissos compromissos) {
        this.compromissos = compromissos;
        this.registrador = registrador;
        this.donos = donos;
        this.referencias = referencias;
        this.basica = basica;
        this.registro = registro;
        this.comprovantes = comprovantes;
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
        return e.moedaOuVazia().em(new Argumentos()).com("valor", e.valor()).com("descricao", e.descricao()).com("conta", e.conta())
                .com("categoria", e.categoria()).com("origem", e.origem()).com("data", e.data())
                .com("ambito", e.ambito()).com("cnpj_estabelecimento", e.cnpjEstabelecimento())
                .com("chave_nfce", e.chaveNfce()).com("contraparte", e.contraparte()).com("observacao", e.observacao())
                .com("confirmar_duplicata", e.confirmarDuplicata()).com("compromisso", e.compromisso()).com("ocorrencia", e.ocorrencia()).mapa();
    }

    private LancamentoRegistrado gravar(OperacaoEmCurso operacao, Entrada e, String token) {
        List<String> faltando = new ArrayList<>(e.moedaOuVazia().faltando(e.valor()));
        if (Argumentos.vazio(e.descricao())) {
            faltando.add("descricao");
        }
        if (Argumentos.vazio(e.conta())) {
            faltando.add("conta");
        }
        if (Argumentos.vazio(e.categoria())) {
            faltando.add("categoria");
        }
        if (Argumentos.vazio(e.compromisso()) && !Argumentos.vazio(e.ocorrencia())) {
            throw ErroTipado.validacao("ocorrencia", "ocorrencia só vale com compromisso.");
        }
        if (!faltando.isEmpty()) {
            throw ErroTipado.camposFaltando(faltando,
                    "Pergunte ao dono quanto entrou, em que conta e qual é a fonte de renda.");
        }
        UUID dono = operacao.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        var data = Datas.lerOuHoje(e.data(), "data", RELOGIO, dados.fuso());
        ValorOriginal.Conversao conversao = RegistroDeLancamento.valores(e.valor(), e.moeda(), dados.moeda(), data);
        Dinheiro total = conversao.valor();
        Conta conta = referencias.conta(dono, e.conta(), "conta", c -> c.classe() == ClasseConta.ATIVO);
        RegistroDeLancamento.exigirMoedaDoDono(conta, dados.moeda(), "conta");
        Conta fonte = referencias.conta(dono, e.categoria(), "categoria", c -> c.classe() == ClasseConta.RECEITA);
        OrigemLancamento origem = Argumentos.vazio(e.origem()) ? OrigemLancamento.CONVERSA
                : RegistroDeLancamento.origem(e.origem());
        String chave = RegistroDeLancamento.chaveNfce(e.chaveNfce());
        String cnpj = RegistroDeLancamento.cnpj(e.cnpjEstabelecimento());
        registro.semNotaRepetida(dono, chave);
        boolean ambitoInferido = Argumentos.vazio(e.ambito());
        UUID ambito = ambitoInferido ? basica.ambitoPadrao(operacao).id()
                : referencias.ambito(dono, e.ambito(), "ambito").id();
        // Antes do marco, o dinheiro já está no saldo inicial: entra pela conta técnica, sem mexer na conta.
        Procedencia procedencia = historico.procedencia(conta.id(), data);
        boolean exogeno = procedencia == Procedencia.EXOGENO;
        UUID entrada = exogeno ? basica.contaSaldoDeAbertura(operacao, conta.moeda()) : conta.id();
        Lancamento lancamento = new Lancamento(UUID.randomUUID(), TipoLancamento.GANHO, data, e.descricao().strip(),
                e.contraparte(), e.observacao(), null, origem, cnpj,
                Partidas.ganho(total, entrada, fonte.id(), ambito), List.of()).comOriginal(conversao.original())
                .comProcedencia(procedencia, exogeno ? conta.id() : null);
        registro.semParecido(dono, lancamento, entrada, e.confirmarDuplicata());
        ReferenciaCurta referencia = registro.gravar(operacao, lancamento, chave);
        if (lancamento.origem() != OrigemLancamento.CONVERSA) {
            comprovantes.emitirLink(operacao, lancamento.id(), token);
        }
        OcorrenciaVinculada vinculada = Argumentos.vazio(e.compromisso()) ? null
                : compromissos.vincular(operacao, e.compromisso(), e.ocorrencia(), lancamento, DirecaoCompromisso.ENTRADA);
        return registro.saida(operacao, lancamento, referencia, "ganho", total, null, conta, ambitoInferido).comOcorrencia(vinculada);
    }
}
