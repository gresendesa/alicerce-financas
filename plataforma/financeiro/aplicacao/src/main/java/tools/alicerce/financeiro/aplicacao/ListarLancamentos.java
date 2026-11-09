package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.RepositorioLancamentos.Filtro;
import tools.alicerce.financeiro.aplicacao.RepositorioLancamentos.Linha;
import tools.alicerce.financeiro.aplicacao.RepositorioLancamentos.Posicao;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.Procedencia;
import tools.alicerce.financeiro.dominio.TipoConta;

/**
 * Capacidade {@code listar_lancamentos}. Um lançamento anulado e seu estorno ficam de fora, a menos
 * que se peça {@code incluirAnulados}.
 */
@Component
public class ListarLancamentos {

    public record Entrada(String de, String ate, String conta, String categoria, String meioPagamento, String texto,
            String valorMinimo, String valorMaximo, Boolean incluirAnulados, String cursor, String procedencia) {

        public Entrada(String de, String ate, String conta, String categoria, String meioPagamento, String texto, String valorMinimo,
                String valorMaximo, Boolean incluirAnulados, String cursor) {
            this(de, ate, conta, categoria, meioPagamento, texto, valorMinimo, valorMaximo, incluirAnulados, cursor, null);
        }
    }

    public record Pagina(List<LancamentoVisto> lancamentos, String proximoCursor) {}

    static final int TAMANHO_PAGINA = 50;

    private final RepositorioLancamentos lancamentos;
    private final RepositorioEstrutura estrutura;
    private final RepositorioDono donos;
    private final Referencias referencias;
    private final LeituraDeLancamentos leitura;

    ListarLancamentos(RepositorioLancamentos lancamentos, RepositorioEstrutura estrutura, RepositorioDono donos,
            Referencias referencias, LeituraDeLancamentos leitura) {
        this.lancamentos = lancamentos;
        this.estrutura = estrutura;
        this.donos = donos;
        this.referencias = referencias;
        this.leitura = leitura;
    }

    public Pagina executar(Chamador chamador, Entrada e) {
        UUID dono = chamador.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        Set<UUID> contas = null;
        if (!Argumentos.vazio(e.conta())) {
            contas = new HashSet<>(Set.of(referencias.contaParaConsultar(dono, e.conta(), "conta", c -> true).id()));
        }
        if (!Argumentos.vazio(e.categoria())) {
            Conta raiz = referencias.contaParaConsultar(dono, e.categoria(), "categoria",
                    c -> c.tipo() == TipoConta.CATEGORIA || c.tipo() == TipoConta.FONTE_RENDA);
            contas = subarvore(dono, raiz.id());
        }
        Filtro filtro = new Filtro(data(e.de(), "de"), data(e.ate(), "ate"), contas,
                Argumentos.vazio(e.meioPagamento()) ? null : referencias.meioParaConsultar(dono, e.meioPagamento(), "meio_pagamento").id(),
                Argumentos.vazio(e.texto()) ? null : e.texto().strip(),
                centavos(e.valorMinimo(), dados, "valor_minimo"), centavos(e.valorMaximo(), dados, "valor_maximo"),
                Boolean.TRUE.equals(e.incluirAnulados()), Argumentos.vazio(e.cursor()) ? null : posicao(e.cursor()), procedencia(e.procedencia()));
        List<Linha> linhas = lancamentos.listar(dono, filtro, TAMANHO_PAGINA + 1);
        boolean temMais = linhas.size() > TAMANHO_PAGINA;
        List<Linha> pagina = temMais ? linhas.subList(0, TAMANHO_PAGINA) : linhas;
        String proximo = null;
        if (temMais) {
            Linha ultima = pagina.get(pagina.size() - 1);
            proximo = Cursores.escrever(ultima.data().toString(), ultima.registradoEm().toString(), ultima.id().toString());
        }
        return new Pagina(leitura.ver(dono, pagina), proximo);
    }

    private static Procedencia procedencia(String texto) {
        return Argumentos.vazio(texto) ? null : Procedencia.deCodigo(texto.strip()).orElseThrow(() ->
                ErroTipado.validacao("procedencia", "Procedência inválida: use endogeno ou exogeno."));
    }

    private Set<UUID> subarvore(UUID dono, UUID raiz) {
        List<Conta> contas = estrutura.contas(dono);
        Set<UUID> ids = new HashSet<>(Set.of(raiz));
        List<UUID> abertos = new ArrayList<>(ids);
        while (!abertos.isEmpty()) {
            UUID pai = abertos.remove(abertos.size() - 1);
            contas.stream().filter(c -> pai.equals(c.contaPai())).map(Conta::id).filter(ids::add).forEach(abertos::add);
        }
        return ids;
    }

    private static LocalDate data(String texto, String campo) {
        return Argumentos.vazio(texto) ? null : Datas.ler(texto.strip(), campo);
    }

    private static Long centavos(String texto, Dono dono, String campo) {
        return Argumentos.vazio(texto) ? null : Long.valueOf(Dinheiro.ler(texto.strip(), dono.moeda(), campo).centavos());
    }

    private static Posicao posicao(String cursor) {
        List<String> partes = Cursores.ler(cursor, 3);
        try {
            return new Posicao(LocalDate.parse(partes.get(0)), OffsetDateTime.parse(partes.get(1)), UUID.fromString(partes.get(2)));
        } catch (DateTimeParseException | IllegalArgumentException invalido) {
            throw ErroTipado.validacao("cursor", Cursores.invalido().mensagem());
        }
    }
}
