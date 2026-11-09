package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.RepositorioConferencias.Conferencia;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.PrazoConferencia;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/** A situação de conferência de cada conta com saldo: a última conferência, a pendência e o prazo. */
@Component
class Conferencias {

    private final RepositorioConferencias conferencias;
    private final RepositorioEstrutura estrutura;
    private final Enderecos enderecos;

    Conferencias(RepositorioConferencias conferencias, RepositorioEstrutura estrutura, Enderecos enderecos) {
        this.conferencias = conferencias;
        this.estrutura = estrutura;
        this.enderecos = enderecos;
    }

    /** A última conferência que vale de cada conta, inclusive pelas faturas de um cartão. */
    Map<UUID, Conferencia> ultimas(UUID dono) {
        Map<UUID, Conferencia> ultimas = new LinkedHashMap<>();
        for (Conferencia c : conferencias.conferencias(dono)) {
            ultimas.putIfAbsent(c.conta(), c);
        }
        return ultimas;
    }

    /** A última conferência que vale de uma conta, ou de uma fatura, se informada. */
    Optional<Conferencia> ultima(UUID dono, UUID conta, UUID fatura) {
        return conferencias.conferencias(dono).stream()
                .filter(c -> c.conta().equals(conta) && (fatura == null || fatura.equals(c.fatura()))).findFirst();
    }

    static PrazoConferencia.Situacao situacao(Conferencia c) {
        return PrazoConferencia.Situacao.de(c.diferencaCentavos(), c.ajusteVigente());
    }

    /** A situação de cada conta ativa com saldo, na ordem das contas. */
    Map<UUID, ConferenciaVista> porConta(UUID dono, LocalDate hoje) {
        Map<UUID, ConferenciaVista> vistas = new LinkedHashMap<>();
        estados(dono, hoje).forEach((conta, estado) -> vistas.put(conta, estado.vista()));
        return vistas;
    }

    /**
     * A situação de conferência de uma conta, com o que a calcula.
     *
     * @param situacao a da última conferência; nula se nunca foi conferida
     * @param ultima a data da última conferência; nula se nunca foi conferida
     */
    record Estado(ConferenciaVista vista, PrazoConferencia.Situacao situacao, LocalDate ultima, LocalDate marco) {}

    /** O estado de cada conta ativa com saldo, na ordem das contas. */
    Map<UUID, Estado> estados(UUID dono, LocalDate hoje) {
        Map<UUID, Conferencia> ultimas = ultimas(dono);
        Map<UUID, LocalDate> marcos = estrutura.marcos(dono);
        Map<UUID, String> codigos = estrutura.referenciasDeConta(dono);
        Map<UUID, Estado> estados = new LinkedHashMap<>();
        for (Conta conta : estrutura.contas(dono)) {
            if (conta.arquivada() || !conta.tipo().temSaldo()) {
                continue;
            }
            Conferencia ultima = ultimas.get(conta.id());
            LocalDate data = ultima == null ? null : ultima.data();
            LocalDate marco = marcos.get(conta.id());
            PrazoConferencia.Situacao situacao = ultima == null ? null : situacao(ultima);
            String codigo = codigos.get(conta.id());
            String url = codigo == null ? null : ReferenciaCurta.ler(codigo, TipoReferencia.MODULO).map(enderecos::url).orElse(null);
            LocalDate valeAte = PrazoConferencia.valeAte(data, marco);
            ConferenciaVista vista = new ConferenciaVista(codigo, url, conta.nome(), conta.moeda(), data == null ? null : data.toString(),
                    situacao == null ? null : situacao.codigo(),
                    situacao == PrazoConferencia.Situacao.PENDENTE
                            ? Dinheiro.deCentavos(ultima.diferencaCentavos(), Currency.getInstance(ultima.moeda())).texto() : null,
                    valeAte == null ? null : valeAte.toString(), PrazoConferencia.vencida(hoje, data, marco));
            estados.put(conta.id(), new Estado(vista, situacao, data, marco));
        }
        return estados;
    }

    /** As contas com conferência vencida, que toda saída de cálculo lista. */
    List<ConferenciaVista> vencidas(UUID dono, LocalDate hoje) {
        return porConta(dono, hoje).values().stream().filter(ConferenciaVista::vencida).toList();
    }
}
