package tools.alicerce.financeiro.aplicacao;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.LancamentoVisto.ItemVisto;
import tools.alicerce.financeiro.aplicacao.LancamentoVisto.MovimentoVisto;
import tools.alicerce.financeiro.aplicacao.RepositorioLancamentos.Linha;
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.ItemLancamento;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/** Traduz lançamentos lidos do banco para a saída: nomes no lugar de ids, id curto e URL. */
@Component
class LeituraDeLancamentos {

    private final RepositorioEstrutura estrutura;
    private final Enderecos enderecos;

    LeituraDeLancamentos(RepositorioEstrutura estrutura, Enderecos enderecos) {
        this.estrutura = estrutura;
        this.enderecos = enderecos;
    }

    List<LancamentoVisto> ver(UUID dono, List<Linha> linhas) {
        Nomes nomes = nomes(dono);
        return linhas.stream().map(l -> ver(nomes, l, null)).toList();
    }

    LancamentoVisto ver(UUID dono, Linha linha, List<ItemLancamento> itens) {
        Nomes nomes = nomes(dono);
        List<ItemVisto> vistos = itens.stream().map(i -> new ItemVisto(i.descricao(),
                i.quantidade() == null ? null : i.quantidade().toPlainString(), i.valor().texto(),
                nomes.contas().get(i.categoria()), nomes.ambitos().get(i.ambito()))).toList();
        return ver(nomes, linha, vistos.isEmpty() ? null : vistos);
    }

    private record Nomes(Map<UUID, String> contas, Map<UUID, String> ambitos, Map<UUID, String> meios, Map<UUID, String> faturas) {}

    private Nomes nomes(UUID dono) {
        return new Nomes(estrutura.contas(dono).stream().collect(Collectors.toMap(Conta::id, Conta::nome)),
                estrutura.ambitos(dono).stream().collect(Collectors.toMap(Ambito::id, Ambito::nome)),
                estrutura.meios(dono).stream().collect(Collectors.toMap(MeioPagamento::id, MeioPagamento::nome)),
                estrutura.referenciasDeFatura(dono));
    }

    private LancamentoVisto ver(Nomes nomes, Linha l, List<ItemVisto> itens) {
        Dinheiro total = Lancamento.total(l.movimentos(), l.original());
        List<MovimentoVisto> movimentos = l.movimentos().stream()
                .map(m -> new MovimentoVisto(nomes.contas().get(m.conta()), m.valor().texto(),
                        m.ambito() == null ? null : nomes.ambitos().get(m.ambito()),
                        m.fatura() == null ? null : nomes.faturas().get(m.fatura()), m.parcelaTexto(),
                        m.faturaAbertura() == null ? null : nomes.faturas().get(m.faturaAbertura()), m.valor().moeda().getCurrencyCode()))
                .toList();
        Integer parcelas = l.movimentos().stream().map(m -> m.totalParcelas()).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        String url = ReferenciaCurta.ler(l.codigo(), TipoReferencia.MODULO).map(enderecos::url).orElse(null);
        return new LancamentoVisto(l.codigo(), url, l.tipo().name().toLowerCase(java.util.Locale.ROOT), l.data().toString(),
                l.descricao(), total.texto(), total.moeda().getCurrencyCode(), l.meio() == null ? null : nomes.meios().get(l.meio()),
                l.contraparte(), l.observacao(), l.origem(), l.anulado(), l.estornaCodigo(), l.anulado() ? l.estornadoPorCodigo() : null,
                l.operacaoCodigo(), movimentos, itens, null, OriginalVisto.de(l.original()), parcelas,
                l.precoAVista() == null ? null : l.precoAVista().texto(), l.procedencia().codigo(),
                l.contaHistorica() == null ? null : nomes.contas().get(l.contaHistorica()), null);
    }
}
