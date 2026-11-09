package tools.alicerce.financeiro.aplicacao;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.RepositorioRetratos.Retrato;
import tools.alicerce.financeiro.aplicacao.ResumoVisto.LinhaVista;
import tools.alicerce.financeiro.aplicacao.ResumoVisto.QuebraVista;
import tools.alicerce.financeiro.aplicacao.RetratoVisto.Comparacao;
import tools.alicerce.financeiro.aplicacao.RetratoVisto.Diferenca;
import tools.alicerce.financeiro.aplicacao.RetratoVisto.LinhaAlterada;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Abre retratos e os compara com o cálculo de agora. O JSON guardado sai como está: a comparação
 * nunca muda um retrato, e refaz o cálculo sem gravar nada.
 */
@Component
class Retratos {

    private static final Clock RELOGIO = Clock.systemUTC();

    // Os nomes que o assistente vê, para o retrato guardar o que ele recebeu.
    private final JsonMapper json = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(i -> i.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();
    private final RepositorioRetratos retratos;
    private final RepositorioDono donos;
    private final CalculoDeResumo calculo;
    private final CalculoDeRitmo ritmo;
    private final CalculoDeCortes cortes;
    private final CalculoDeProjecao projecao;
    private final Enderecos enderecos;

    Retratos(RepositorioRetratos retratos, RepositorioDono donos, CalculoDeResumo calculo, CalculoDeRitmo ritmo, CalculoDeCortes cortes,
            CalculoDeProjecao projecao, Enderecos enderecos) {
        this.projecao = projecao;
        this.ritmo = ritmo;
        this.cortes = cortes;
        this.retratos = retratos;
        this.donos = donos;
        this.calculo = calculo;
        this.enderecos = enderecos;
    }

    String json(Object valor) {
        return json.writeValueAsString(valor);
    }

    Optional<RetratoVisto> ver(UUID dono, UUID id, ReferenciaCurta referencia, boolean compararAgora) {
        return retratos.buscar(dono, id).map(r -> {
            RetratoVisto visto = new RetratoVisto(referencia.id(), enderecos.url(referencia), r.capacidade(),
                    r.dataReferencia().toString(), json.readTree(r.parametros()), json.readTree(r.resultado()), null);
            return compararAgora ? visto.comComparacao(comparar(dono, r)) : visto;
        });
    }

    private Comparacao comparar(UUID dono, Retrato r) {
        Currency moeda = donos.buscar(dono).orElseThrow().moeda();
        if (r.capacidade().equals(RitmoGastos.CAPACIDADE)) {
            return compararRitmo(dono, moeda, r);
        }
        if (r.capacidade().equals(CortesNecessarios.CAPACIDADE)) {
            return compararCortes(dono, moeda, r);
        }
        if (r.capacidade().equals(PrestarContas.CAPACIDADE)) {
            return compararPrestacao(dono, moeda, r);
        }
        if (r.capacidade().equals(Projetar.CAPACIDADE)) {
            return compararProjecao(dono, moeda, r);
        }
        ResumoVisto antes = json.readValue(r.resultado(), ResumoVisto.class);
        ParametrosResumo parametros = json.readValue(r.parametros(), ParametrosResumo.class);
        OffsetDateTime agora = OffsetDateTime.now(RELOGIO.withZone(ZoneOffset.UTC));
        ResumoVisto depois = calculo.calcular(dono, moeda, parametros, agora);

        Diferenca entradas = diferenca(antes.entradas(), depois.entradas(), moeda);
        Diferenca saidas = diferenca(antes.saidas(), depois.saidas(), moeda);
        Diferenca saldo = diferenca(antes.saldo(), depois.saldo(), moeda);
        List<LinhaAlterada> linhas = linhasAlteradas(antes, depois, moeda);
        // Retrato sem conversão, ou conta em outra moeda que deixou de existir: nada a comparar.
        Diferenca convertido = antes.saldos() == null || depois.saldos() == null ? null
                : diferenca(antes.saldos().total(), depois.saldos().total(), moeda);
        boolean mudou = !linhas.isEmpty() || !antes.entradas().equals(depois.entradas()) || !antes.saidas().equals(depois.saidas())
                || convertido != null && !convertido.retrato().equals(convertido.agora());
        return new Comparacao(agora.toString(), mudou, entradas, saidas, saldo, linhas, convertido, null, null, null, null, null);
    }

    /** O mesmo recorte do retrato, com os dados de agora: o dia de hoje fica o do retrato. */
    private Comparacao compararRitmo(UUID dono, Currency moeda, Retrato r) {
        RitmoVisto antes = json.readValue(r.resultado(), RitmoVisto.class);
        ParametrosRitmo parametros = json.readValue(r.parametros(), ParametrosRitmo.class);
        OffsetDateTime agora = OffsetDateTime.now(RELOGIO.withZone(ZoneOffset.UTC));
        RitmoVisto depois = ritmo.calcular(dono, moeda, parametros, agora);
        Diferenca acumulado = diferenca(antes.acumulado(), depois.acumulado(), moeda);
        Diferenca tipico = antes.tipico() == null || depois.tipico() == null ? null
                : diferenca(antes.tipico().acumulado(), depois.tipico().acumulado(), moeda);
        boolean mudou = !antes.acumulado().equals(depois.acumulado()) || (antes.tipico() == null) != (depois.tipico() == null)
                || tipico != null && !tipico.retrato().equals(tipico.agora());
        return new Comparacao(agora.toString(), mudou, null, null, null, List.of(), null, acumulado, tipico, null, null, null);
    }

    /** O mesmo dia, alvo e essenciais do retrato, com os dados de agora. */
    private Comparacao compararCortes(UUID dono, Currency moeda, Retrato r) {
        CortesVisto antes = json.readValue(r.resultado(), CortesVisto.class);
        ParametrosCortes parametros = json.readValue(r.parametros(), ParametrosCortes.class);
        OffsetDateTime agora = OffsetDateTime.now(RELOGIO.withZone(ZoneOffset.UTC));
        CortesVisto depois = cortes.calcular(dono, moeda, parametros, antes.alvo().operacaoDeclaracao(), agora);
        Diferenca saldo = diferenca(antes.saldoProjetado(), depois.saldoProjetado(), moeda);
        Diferenca distancia = diferenca(antes.distancia(), depois.distancia(), moeda);
        boolean mudou = !saldo.retrato().equals(saldo.agora()) || !antes.saidasConhecidas().equals(depois.saidasConhecidas())
                || !antes.saldoPartida().equals(depois.saldoPartida());
        return new Comparacao(agora.toString(), mudou, null, null, null, List.of(), null, null, null, saldo, distancia, null);
    }

    /**
     * A linha de base com o mesmo dia, horizonte e piso do retrato, com os dados de agora (decisão 9): os cenários não
     * são refeitos, porque a pergunta é se o ponto de partida mudou.
     */
    private Comparacao compararProjecao(UUID dono, Currency moeda, Retrato r) {
        ProjecaoVista antes = json.readValue(r.resultado(), ProjecaoVista.class);
        ParametrosProjecao parametros = json.readValue(r.parametros(), ParametrosProjecao.class).semCenarios();
        OffsetDateTime agora = OffsetDateTime.now(RELOGIO.withZone(ZoneOffset.UTC));
        ProjecaoVista depois = projecao.calcular(dono, moeda, parametros, agora);
        List<RetratoVisto.SaldoFinal> saldos = new ArrayList<>();
        boolean mudou = !antes.saldoPartida().equals(depois.saldoPartida());
        for (int i = 0; i < antes.linhaBase().meses().size(); i++) {
            ProjecaoVista.MesVisto a = antes.linhaBase().meses().get(i);
            ProjecaoVista.MesVisto d = depois.linhaBase().meses().get(i);
            saldos.add(new RetratoVisto.SaldoFinal(a.mes(), diferenca(a.saldoFinalP50(), d.saldoFinalP50(), moeda),
                    diferenca(a.saldoFinalP80(), d.saldoFinalP80(), moeda)));
            mudou |= !a.saldoFinalP50().equals(d.saldoFinalP50()) || !a.saldoFinalP80().equals(d.saldoFinalP80());
        }
        return new Comparacao(agora.toString(), mudou, null, null, null, List.of(), null, null, null, null, null, saldos);
    }

    /** Os totais do âmbito com os dados de agora: mudou, a página do link ficou para trás e o dono precisa de um link novo. */
    private Comparacao compararPrestacao(UUID dono, Currency moeda, Retrato r) {
        PrestacaoVista antes = TelaPrestacao.ler(r.resultado()).prestacao();
        ParametrosPrestacao parametros = TelaPrestacao.parametros(r.parametros());
        OffsetDateTime agora = OffsetDateTime.now(RELOGIO.withZone(ZoneOffset.UTC));
        ResumoVisto depois = calculo.calcular(dono, moeda, parametros.resumo(), agora);
        Diferenca entradas = diferenca(antes.entradas(), depois.entradas(), moeda);
        Diferenca saidas = diferenca(antes.gastos(), depois.saidas(), moeda);
        boolean mudou = !antes.entradas().equals(depois.entradas()) || !antes.gastos().equals(depois.saidas())
                || antes.quantidadeLancamentos() != depois.quantidadeLancamentos();
        return new Comparacao(agora.toString(), mudou, entradas, saidas, null, List.of(), null, null, null, null, null, null);
    }

    private static Diferenca diferenca(String antes, String agora, Currency moeda) {
        return new Diferenca(antes, agora, CalculoDeResumo.diferenca(agora, antes, moeda).texto());
    }

    private static List<LinhaAlterada> linhasAlteradas(ResumoVisto antes, ResumoVisto depois, Currency moeda) {
        List<LinhaAlterada> alteradas = new ArrayList<>();
        String zero = Dinheiro.zero(moeda).texto();
        for (int i = 0; i < antes.quebras().size(); i++) {
            QuebraVista quebra = antes.quebras().get(i);
            Map<String, LinhaVista> linhasAntes = porChave(quebra);
            Map<String, LinhaVista> linhasDepois = porChave(depois.quebras().get(i));
            Set<String> chaves = new LinkedHashSet<>(linhasAntes.keySet());
            chaves.addAll(linhasDepois.keySet());
            for (String chave : chaves) {
                LinhaVista a = linhasAntes.get(chave);
                LinhaVista d = linhasDepois.get(chave);
                LinhaVista qualquer = d == null ? a : d;
                String entradasAntes = a == null ? zero : a.entradas();
                String entradasDepois = d == null ? zero : d.entradas();
                String saidasAntes = a == null ? zero : a.saidas();
                String saidasDepois = d == null ? zero : d.saidas();
                if (!entradasAntes.equals(entradasDepois) || !saidasAntes.equals(saidasDepois)) {
                    alteradas.add(new LinhaAlterada(quebra.agruparPor(), qualquer.nome(), diferenca(entradasAntes, entradasDepois, moeda),
                            diferenca(saidasAntes, saidasDepois, moeda)));
                }
            }
        }
        return alteradas;
    }

    /** O id separa as linhas mesmo que a conta mude de nome; sem id, o nome. */
    private static Map<String, LinhaVista> porChave(QuebraVista quebra) {
        Map<String, LinhaVista> linhas = new HashMap<>();
        quebra.linhas().forEach(l -> linhas.put(l.id() == null ? l.nome() : l.id(), l));
        return linhas;
    }

    static boolean ehRetrato(ReferenciaCurta referencia) {
        return referencia.prefixo().equals(TipoReferencia.RETRATO.prefixo());
    }
}
