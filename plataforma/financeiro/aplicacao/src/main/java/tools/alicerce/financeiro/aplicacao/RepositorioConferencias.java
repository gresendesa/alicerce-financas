package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Porta de persistência das conferências. Só valem as de operação aplicada. */
public interface RepositorioConferencias {

    /**
     * Uma conferência.
     *
     * @param fatura a fatura conferida; nula em conferência de conta
     * @param ajuste o lançamento de ajuste; nulo se não houve
     * @param ajusteVigente o ajuste não foi anulado
     * @param operacao o id curto da operação que a gravou
     */
    record Conferencia(UUID id, UUID conta, UUID fatura, LocalDate data, long informadoCentavos, long calculadoCentavos, String moeda,
            UUID ajuste, boolean ajusteVigente, String operacao, OffsetDateTime registradaEm) {

        public long diferencaCentavos() {
            return informadoCentavos - calculadoCentavos;
        }
    }

    void inserir(OperacaoEmCurso operacao, Conferencia conferencia);

    /** As conferências que valem do dono, da mais recente para a mais antiga (pela data e, na mesma data, pela gravação). */
    List<Conferencia> conferencias(UUID dono);
}
