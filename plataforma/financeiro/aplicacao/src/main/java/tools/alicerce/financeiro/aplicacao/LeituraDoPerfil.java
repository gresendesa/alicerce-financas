package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.dominio.CalculoPerfil;
import tools.alicerce.financeiro.dominio.CalculoResumo;
import tools.alicerce.financeiro.dominio.CompraNoCredito;
import tools.alicerce.financeiro.dominio.Compromisso;
import tools.alicerce.financeiro.dominio.DirecaoCompromisso;
import tools.alicerce.financeiro.dominio.Ocorrencia;
import tools.alicerce.financeiro.dominio.RegistroOcorrencia;
import tools.alicerce.financeiro.dominio.SituacaoOcorrencia;
import tools.alicerce.financeiro.dominio.TipoCompromisso;

/**
 * O que as regras do perfil leem do livro-razão: as parcelas de cada mês, a renda e as saídas
 * de um período em competência, como em {@code resumo_periodo}, e os aportes líquidos de uma meta. Só lê; as contas são de
 * {@link CalculoPerfil}.
 */
@Component
class LeituraDoPerfil {

    private final RepositorioCartoes cartoes;
    private final RepositorioCompromissos repositorioCompromissos;
    private final Compromissos compromissos;
    private final RepositorioLancamentos lancamentos;
    private final RepositorioMetas metas;

    LeituraDoPerfil(RepositorioCartoes cartoes, RepositorioCompromissos repositorioCompromissos, Compromissos compromissos,
            RepositorioLancamentos lancamentos, RepositorioMetas metas) {
        this.cartoes = cartoes;
        this.repositorioCompromissos = repositorioCompromissos;
        this.compromissos = compromissos;
        this.lancamentos = lancamentos;
        this.metas = metas;
    }

    /**
     * As parcelas de cada mês de {@code de} a {@code ate}: as de compras parceladas (2 ou mais) que vencem nas faturas do mês,
     * pagas ou não, mais as ocorrências de compromisso parcelado a pagar que vencem nele, menos as puladas. À vista no cartão
     * não é parcela. Só na moeda do dono.
     *
     * @return todo mês do intervalo, zero onde não há parcela
     */
    Map<YearMonth, Long> parcelasPorMes(UUID dono, Currency moeda, YearMonth de, YearMonth ate, LocalDate hoje) {
        Map<YearMonth, Long> porMes = new TreeMap<>();
        for (YearMonth m = de; !m.isAfter(ate); m = m.plusMonths(1)) {
            porMes.put(m, 0L);
        }
        for (CompraNoCredito compra : cartoes.comprasComVencimento(dono, de.atDay(1), ate.atEndOfMonth())) {
            List<CompraNoCredito.Lado> lados = compra.parcelas() == null ? List.of() : compra.parcelas();
            if (lados.stream().mapToInt(CompraNoCredito.Lado::parcela).max().orElse(0) < 2) {
                continue;
            }
            for (CompraNoCredito.Lado l : lados) {
                if (l.vencimento() == null || !l.valor().moeda().equals(moeda)) {
                    continue;
                }
                porMes.computeIfPresent(YearMonth.from(l.vencimento()), (k, v) -> Math.addExact(v, l.valor().centavos()));
            }
        }
        Map<UUID, Map<Integer, List<RegistroOcorrencia>>> registros = repositorioCompromissos.registrosDoDono(dono);
        for (Compromisso c : repositorioCompromissos.compromissos(dono)) {
            if (c.tipo() != TipoCompromisso.PARCELADO || c.direcao() != DirecaoCompromisso.SAIDA || !c.moeda().equals(moeda.getCurrencyCode())) {
                continue;
            }
            for (Ocorrencia o : compromissos.ocorrencias(c, registros.getOrDefault(c.id(), Map.of()), hoje, ate.atEndOfMonth())) {
                if (o.situacao() == SituacaoOcorrencia.PULADA) {
                    continue;
                }
                porMes.computeIfPresent(YearMonth.from(o.vencimento()), (k, v) -> Math.addExact(v, o.valorPrevistoCentavos()));
            }
        }
        return porMes;
    }

    /** A renda e as saídas de um período em competência, as mesmas de {@code resumo_periodo} sem âmbito. */
    CalculoPerfil.Poupanca rendaESaidas(UUID dono, Currency moeda, LocalDate de, LocalDate ate) {
        CalculoResumo.Totais t = CalculoResumo.totais(lancamentos.fatosDoPeriodo(dono, de, ate, null), moeda);
        return CalculoPerfil.poupanca(t.entradas().centavos(), t.saidas().centavos());
    }

    /** Os aportes menos os resgates vigentes da meta com data no período. */
    long aportesLiquidos(UUID meta, LocalDate de, LocalDate ate) {
        long soma = 0;
        for (RepositorioMetas.Aporte a : metas.aportes(meta)) {
            if (!a.data().isBefore(de) && !a.data().isAfter(ate)) {
                soma = Math.addExact(soma, a.resgate() ? -a.centavos() : a.centavos());
            }
        }
        return soma;
    }
}
