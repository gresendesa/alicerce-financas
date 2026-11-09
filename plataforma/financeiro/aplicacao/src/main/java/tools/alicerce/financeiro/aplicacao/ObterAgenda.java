package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.RepositorioCartoes.Totais;
import tools.alicerce.financeiro.dominio.CalculoAgenda;
import tools.alicerce.financeiro.dominio.CartaoCredito;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.Fatura;
import tools.alicerce.financeiro.dominio.Periodo;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * Capacidade {@code obter_agenda}: o que está atrasado e o que vence num período, com as faturas de cartão.
 * Uma compra parcelada no cartão aparece só dentro da fatura, pelo restante dela, nunca também como
 * compromisso: compromisso parcelado no crédito é recusado, e uma compra vinculada já está paga.
 */
@Component
public class ObterAgenda {

    public record Entrada(String de, String ate, String periodo) {}

    /** Sem período, de hoje até tantos dias depois. */
    static final int DIAS_PADRAO = 30;

    private static final String NOTA = "Os totais já somam atrasados e vencimentos: nunca some de novo. Fatura entra pelo que falta pagar, com as "
            + "parcelas de compras dentro dela; uma fatura ainda aberta pode crescer até fechar. Compromisso não gera lançamento: o pagamento é "
            + "registrar_gasto ou registrar_ganho com compromisso.";

    private final RepositorioDono donos;
    private final RepositorioCartoes cartoes;
    private final RepositorioEstrutura estrutura;
    private final Compromissos compromissos;
    private final Conferencias conferencias;
    private final Enderecos enderecos;

    ObterAgenda(RepositorioDono donos, RepositorioCartoes cartoes, RepositorioEstrutura estrutura, Compromissos compromissos,
            Conferencias conferencias, Enderecos enderecos) {
        this.donos = donos;
        this.cartoes = cartoes;
        this.estrutura = estrutura;
        this.compromissos = compromissos;
        this.conferencias = conferencias;
        this.enderecos = enderecos;
    }

    public Agenda executar(Chamador chamador, Entrada e) {
        UUID dono = chamador.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        LocalDate hoje = compromissos.hoje(dono);
        Periodo periodo = periodo(e, hoje);
        LocalDate inicio = periodo.de().isAfter(hoje) ? periodo.de() : hoje;
        List<ItemAgenda> atrasados = new ArrayList<>();
        List<ItemAgenda> vencimentos = new ArrayList<>();
        List<CalculoAgenda.Item> itens = new ArrayList<>();
        for (Compromissos.Aberta a : compromissos.abertas(dono, hoje, periodo.ate())) {
            LocalDate vence = a.ocorrencia().vencimento();
            boolean atrasado = vence.isBefore(hoje);
            if (!atrasado && vence.isBefore(inicio)) {
                continue;
            }
            (atrasado ? atrasados : vencimentos).add(compromissos.item(dono, a, hoje));
            itens.add(new CalculoAgenda.Item(a.saida(), a.ocorrencia().valorRestanteCentavos(), atrasado));
        }
        faturas(dono, dados.moeda(), hoje, inicio, periodo.ate(), atrasados, vencimentos, itens);
        Comparator<ItemAgenda> ordem = Comparator.comparing(ItemAgenda::vencimento).thenComparing(ItemAgenda::nome);
        atrasados.sort(ordem);
        vencimentos.sort(ordem);
        CalculoAgenda.Totais t = CalculoAgenda.totais(itens);
        Currency moeda = dados.moeda();
        return new Agenda(hoje.toString(), periodo.de().toString(), periodo.ate().toString(), moeda.getCurrencyCode(), atrasados, vencimentos,
                new Agenda.Totais(texto(t.aPagarAtrasado(), moeda), texto(t.aReceberAtrasado(), moeda), texto(t.aPagarNoPeriodo(), moeda),
                        texto(t.aReceberNoPeriodo(), moeda), texto(t.aPagar(), moeda), texto(t.aReceber(), moeda), texto(t.saldoPrevisto(), moeda)),
                NOTA, conferencias.vencidas(dono, hoje));
    }

    private static Periodo periodo(Entrada e, LocalDate hoje) {
        if (!Argumentos.vazio(e.periodo())) {
            if (!Argumentos.vazio(e.de()) || !Argumentos.vazio(e.ate())) {
                throw ErroTipado.validacao("periodo", "Informe periodo ou de e ate, não os dois.");
            }
            return Periodo.ler(e.periodo(), "periodo");
        }
        LocalDate de = Argumentos.vazio(e.de()) ? hoje : Datas.ler(e.de().strip(), "de");
        LocalDate ate = Argumentos.vazio(e.ate()) ? (de.isAfter(hoje) ? de : hoje).plusDays(DIAS_PADRAO) : Datas.ler(e.ate().strip(), "ate");
        return new Periodo(de, ate);
    }

    /** Cada fatura com restante e vencimento até o fim do período entra pelo que falta pagar. */
    private void faturas(UUID dono, Currency moeda, LocalDate hoje, LocalDate inicio, LocalDate ate, List<ItemAgenda> atrasados,
            List<ItemAgenda> vencimentos, List<CalculoAgenda.Item> itens) {
        for (FaturaEmAberto f : faturasEmAberto(dono, moeda, hoje)) {
            if (f.venceEm().isAfter(ate) || !f.atrasada() && f.venceEm().isBefore(inicio)) {
                continue;
            }
            (f.atrasada() ? atrasados : vencimentos).add(f.item());
            itens.add(new CalculoAgenda.Item(true, f.restanteCentavos(), f.atrasada()));
        }
    }

    /** Uma fatura com restante, como a agenda a mostra. */
    record FaturaEmAberto(ItemAgenda item, LocalDate venceEm, long restanteCentavos, boolean atrasada) {}

    /** Todas as faturas com restante, de qualquer vencimento: a agenda e a {@code situacao} leem daqui. */
    List<FaturaEmAberto> faturasEmAberto(UUID dono, Currency moeda, LocalDate hoje) {
        Map<UUID, String> nomes = new java.util.HashMap<>();
        estrutura.contas(dono).forEach(c -> nomes.put(c.id(), c.nome()));
        Map<UUID, String> codigos = estrutura.referenciasDeFatura(dono);
        List<FaturaEmAberto> lista = new ArrayList<>();
        for (CartaoCredito cartao : cartoes.cartoes(dono)) {
            Map<UUID, Totais> totais = cartoes.totais(cartao.conta());
            for (Fatura f : cartoes.faturas(cartao.conta())) {
                Totais t = totais.getOrDefault(f.id(), new Totais(0, 0));
                long restante = t.comprado() - t.pago();
                if (restante <= 0) {
                    continue;
                }
                boolean atrasado = f.venceEm().isBefore(hoje);
                String codigo = codigos.get(f.id());
                String url = codigo == null ? null : ReferenciaCurta.ler(codigo, TipoReferencia.MODULO).map(enderecos::url).orElse(null);
                lista.add(new FaturaEmAberto(new ItemAgenda("fatura", codigo, url, "Fatura " + nomes.get(cartao.conta()) + " " + f.periodo(),
                        "saida", f.venceEm().toString(), texto(restante, moeda), moeda.getCurrencyCode(), atrasado ? "atrasada" : "prevista", null,
                        null, null, null, null, null), f.venceEm(), restante, atrasado));
            }
        }
        return lista;
    }

    private static String texto(long centavos, Currency moeda) {
        return Dinheiro.deCentavos(centavos, moeda).texto();
    }
}
