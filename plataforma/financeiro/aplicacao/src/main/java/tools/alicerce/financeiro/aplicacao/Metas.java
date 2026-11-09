package tools.alicerce.financeiro.aplicacao;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.dominio.CalculoMeta;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.Meta;
import tools.alicerce.financeiro.dominio.TipoConta;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/** O que as capacidades de meta têm em comum: achar a meta, saber o progresso e mostrar. */
@Component
class Metas {

    /** Os tipos de conta que podem ser reservados a uma meta. */
    static final java.util.Set<TipoConta> RESERVAVEIS = java.util.Set.of(TipoConta.RESERVA, TipoConta.POUPANCA);

    private static final Clock RELOGIO = Clock.systemUTC();

    private final RepositorioMetas metas;
    private final RepositorioEstrutura estrutura;
    private final RepositorioDono donos;
    private final Referencias referencias;
    private final Enderecos enderecos;

    Metas(RepositorioMetas metas, RepositorioEstrutura estrutura, RepositorioDono donos, Referencias referencias, Enderecos enderecos) {
        this.metas = metas;
        this.estrutura = estrutura;
        this.donos = donos;
        this.referencias = referencias;
        this.enderecos = enderecos;
    }

    LocalDate hoje(UUID dono) {
        return Datas.hoje(RELOGIO, donos.buscar(dono).orElseThrow().fuso());
    }

    /** A meta por id, id curto, URL, nome ou alias, entre as não arquivadas. */
    Meta resolver(UUID dono, String texto, String campo) {
        return referencias.meta(dono, texto, campo, visiveis(dono));
    }

    /** As metas que o dono vê: todas as não arquivadas, da mais importante para a menos, e pelo nome. */
    List<Meta> visiveis(UUID dono) {
        return metas.metas(dono).stream().filter(m -> !m.arquivada())
                .sorted(java.util.Comparator.comparingInt(Meta::prioridade).thenComparing(m -> Referencias.normalizar(m.nome()))).toList();
    }

    /** As metas em andamento, para as contas do futuro. */
    List<Meta> emAndamento(UUID dono) {
        return visiveis(dono).stream().filter(Meta::emAndamento).toList();
    }

    long progresso(UUID meta, UUID dono) {
        return metas.progressos(dono).getOrDefault(meta, 0L);
    }

    Map<UUID, Long> progressos(UUID dono) {
        return metas.progressos(dono);
    }

    /** As contas reservadas a metas em andamento: ficam fora do saldo disponível (decisão 4). */
    java.util.Set<UUID> contasReservadas(UUID dono) {
        return emAndamento(dono).stream().map(Meta::contaReservada).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /** A situação de hoje, pelo domínio. */
    CalculoMeta.Situacao situacao(Meta m, long progresso, LocalDate hoje) {
        return CalculoMeta.calcular(m, progresso, hoje);
    }

    MetaVista ver(UUID dono, Meta m, long progresso, LocalDate hoje) {
        Currency moeda = Currency.getInstance(m.moeda());
        Function<Long, String> dinheiro = c -> Dinheiro.deCentavos(c, moeda).texto();
        CalculoMeta.Situacao s = situacao(m, progresso, hoje);
        String codigo = metas.referencias(dono).get(m.id());
        Map<UUID, Conta> contas = estrutura.contas(dono).stream().collect(Collectors.toMap(Conta::id, c -> c));
        Conta reservada = m.contaReservada() == null ? null : contas.get(m.contaReservada());
        String codigoReservada = reservada == null ? null : estrutura.referenciasDeConta(dono).get(reservada.id());
        return new MetaVista(codigo, url(codigo), m.nome(), m.aliases(), dinheiro.apply(m.alvoCentavos()), m.moeda(),
                m.dataAlvo() == null ? null : m.dataAlvo().toString(), m.prioridade(), m.situacao().codigo(),
                reservada == null ? null : reservada.nome(), codigoReservada, m.observacao(), dinheiro.apply(s.progresso()),
                dinheiro.apply(s.falta()), s.percentual().toPlainString(), s.mesesRestantes(),
                s.aporteMensal() == null ? null : dinheiro.apply(s.aporteMensal()), s.prazoVencido(), s.alcancada(), null);
    }

    /** A meta com cada aporte e resgate vigente, para a consulta. */
    MetaVista verComAportes(UUID dono, Meta m, LocalDate hoje) {
        Currency moeda = Currency.getInstance(m.moeda());
        List<MetaVista.AporteVisto> aportes = metas.aportes(m.id()).stream().map(a -> new MetaVista.AporteVisto(a.codigo(), url(a.codigo()),
                a.data().toString(), a.descricao(), a.resgate() ? "resgate" : "aporte", Dinheiro.deCentavos(a.centavos(), moeda).texto())).toList();
        return ver(dono, m, progresso(m.id(), dono), hoje).comAportes(aportes);
    }

    /** A consulta de uma meta pelo id curto; arquivada não aparece. */
    MetaVista consultar(UUID dono, String codigo) {
        UUID id = estrutura.entidadeDaReferencia(dono, codigo).orElseThrow(() -> naoEncontrada(codigo));
        Meta m = metas.meta(dono, id).filter(x -> !x.arquivada()).orElseThrow(() -> naoEncontrada(codigo));
        return verComAportes(dono, m, hoje(dono));
    }

    private static ErroTipado naoEncontrada(String codigo) {
        return ErroTipado.de(tools.alicerce.convencoes.CodigoErro.NAO_ENCONTRADO, "ref", "Nenhuma meta corresponde a '" + codigo + "'.");
    }

    private String url(String codigo) {
        return codigo == null ? null : ReferenciaCurta.ler(codigo, TipoReferencia.MODULO).map(enderecos::url).orElse(null);
    }
}
