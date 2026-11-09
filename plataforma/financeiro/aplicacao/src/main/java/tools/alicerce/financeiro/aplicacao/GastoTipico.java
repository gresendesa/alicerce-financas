package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.dominio.CalculoPerfil;
import tools.alicerce.financeiro.dominio.CalculoRitmo;
import tools.alicerce.financeiro.dominio.Conta;

/**
 * O gasto mensal típico da reserva em meses: a mediana do total de saídas em competência, fixos
 * incluídos, dos meses de referência de {@code ritmo_gastos}. Os totais são os mesmos de {@code resumo_periodo} no mês.
 */
@Component
class GastoTipico {

    /**
     * @param centavos nulo com menos de {@value CalculoRitmo#MESES_MINIMOS} meses de referência
     * @param meses os meses de referência válidos, do mais antigo ao mais recente
     * @param totais o total de saídas de cada um
     * @param disponivelEm sem valor, o primeiro mês em que ele sai: o histórico completo mais os meses mínimos; nulo com valor
     */
    record Visto(Long centavos, List<YearMonth> meses, List<Long> totais, YearMonth disponivelEm) {

        Visto {
            meses = List.copyOf(meses);
            totais = List.copyOf(totais);
        }
    }

    private final CalculoDeCortes cortes;
    private final RepositorioEstrutura estrutura;

    GastoTipico(CalculoDeCortes cortes, RepositorioEstrutura estrutura) {
        this.cortes = cortes;
        this.estrutura = estrutura;
    }

    Visto calcular(UUID dono, LocalDate hoje) {
        Map<UUID, Conta> contas = estrutura.contas(dono).stream().collect(Collectors.toMap(Conta::id, Function.identity()));
        return de(cortes.baseVariavel(dono, contas, YearMonth.from(hoje)));
    }

    /** Da mesma base do gasto variável, sem ler de novo. */
    static Visto de(CalculoDeCortes.BaseVariavel base) {
        List<Long> totais = base.validos().stream()
                .map(m -> base.porMes().getOrDefault(m, List.of()).stream().mapToLong(CalculoRitmo.Gasto::centavos).sum()).toList();
        Long centavos = CalculoPerfil.gastoMensalTipico(totais);
        LocalDate marco = base.marcoMaisRecente();
        YearMonth disponivel = centavos != null || marco == null ? null
                : CalculoRitmo.completoDesde(marco).plusMonths(CalculoRitmo.MESES_MINIMOS);
        return new Visto(centavos, base.validos(), totais, disponivel);
    }
}
