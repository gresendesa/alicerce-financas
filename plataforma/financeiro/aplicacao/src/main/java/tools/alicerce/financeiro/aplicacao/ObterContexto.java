package tools.alicerce.financeiro.aplicacao;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.financeiro.aplicacao.Contexto.AmbitoContexto;
import tools.alicerce.financeiro.aplicacao.Contexto.CartaoContexto;
import tools.alicerce.financeiro.aplicacao.Contexto.ContaContexto;
import tools.alicerce.financeiro.aplicacao.Contexto.MeioContexto;
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.ClasseConta;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.Dono;

/** Capacidade {@code obter_contexto}. */
@Component
public class ObterContexto {

    private final RepositorioDono donos;
    private final RepositorioEstrutura estrutura;
    private final RepositorioLancamentos lancamentos;
    private final RepositorioCartoes cartoes;
    private final Faturas faturas;
    private final Conferencias conferencias;
    private final RepositorioCompromissos repositorioCompromissos;
    private final Compromissos compromissos;
    private final Declaracoes declaracoes;
    private final Metas metas;
    private final java.util.Set<String> contasDeTeste;

    ObterContexto(RepositorioDono donos, RepositorioEstrutura estrutura, RepositorioLancamentos lancamentos, RepositorioCartoes cartoes,
            Faturas faturas, Conferencias conferencias, RepositorioCompromissos repositorioCompromissos, Compromissos compromissos,
            Declaracoes declaracoes, Metas metas, @org.springframework.beans.factory.annotation.Value("${alicerce.financeiro.contas-teste:}") String contasDeTeste) {
        this.declaracoes = declaracoes;
        this.metas = metas;
        this.conferencias = conferencias;
        this.repositorioCompromissos = repositorioCompromissos;
        this.compromissos = compromissos;
        this.contasDeTeste = java.util.Arrays.stream(contasDeTeste.split(",")).map(String::strip).filter(u -> !u.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.donos = donos;
        this.estrutura = estrutura;
        this.lancamentos = lancamentos;
        this.cartoes = cartoes;
        this.faturas = faturas;
    }

    public Contexto executar(Chamador chamador) {
        Dono dono = donos.buscar(chamador.dono())
                .orElseThrow(() -> new IllegalStateException("Dono resolvido pela segurança não existe: " + chamador.dono()));
        List<Conta> contas = estrutura.contas(dono.id());
        Map<UUID, String> nomes = contas.stream().collect(Collectors.toMap(Conta::id, Conta::nome));
        Map<UUID, String> codigos = estrutura.referenciasDeConta(dono.id());
        Map<UUID, Long> saldos = lancamentos.saldosCentavos(dono.id());
        Map<UUID, java.time.LocalDate> marcos = estrutura.marcos(dono.id());
        List<Ambito> ambitos = estrutura.ambitos(dono.id()).stream().filter(a -> !a.arquivado()).toList();
        Map<UUID, String> nomesAmbito = ambitos.stream().collect(Collectors.toMap(Ambito::id, Ambito::nome));

        java.time.LocalDate hoje = compromissos.hoje(dono.id());
        Map<UUID, ConferenciaVista> conferidas = conferencias.porConta(dono.id(), hoje);
        List<ContaContexto> visiveis = contas.stream()
                .filter(c -> !c.arquivada() && c.classe() != ClasseConta.PATRIMONIO)
                .map(c -> {
                    ConferenciaVista conf = conferidas.get(c.id());
                    return new ContaContexto(codigos.get(c.id()), c.nome(), c.tipo().codigo(), c.aliases(),
                        c.contaPai() == null ? null : nomes.get(c.contaPai()),
                        c.tipo().temSaldo() ? Dinheiro.deCentavos(saldos.getOrDefault(c.id(), 0L), java.util.Currency.getInstance(c.moeda())).texto() : null,
                        marcos.containsKey(c.id()) ? marcos.get(c.id()).toString() : null, c.moeda(),
                        conf == null ? null : conf.ultimaConferencia(), conf == null ? null : conf.situacao(),
                        conf == null ? null : conf.vencida());
                })
                .toList();
        List<MeioContexto> meios = estrutura.meios(dono.id()).stream().filter(m -> !m.arquivado())
                .map(m -> new MeioContexto(m.id().toString(), m.nome(), m.tipo().codigo(), m.aliases(),
                        nomes.get(m.contaLiquidacao()), m.padrao(),
                        m.ambitoPadrao() == null ? null : nomesAmbito.get(m.ambitoPadrao())))
                .toList();
        List<CartaoContexto> vistos = cartoes.cartoes(dono.id()).stream().map(c -> new CartaoContexto(codigos.get(c.conta()), nomes.get(c.conta()),
                c.diaFechamento(), c.diaVencimento(),
                c.limiteCentavos() == null ? null : Dinheiro.deCentavos(c.limiteCentavos(), dono.moeda()).texto(),
                faturas.disponivel(c.conta(), dono.moeda()), c.contaPagamento() == null ? null : nomes.get(c.contaPagamento()),
                faturas.atual(dono.id(), c).semDetalhes())).toList();
        return new Contexto(conectada(chamador), dono.fuso().getId(), dono.moeda().getCurrencyCode(), visiveis, vistos, meios,
                ambitos.stream().map(a -> new AmbitoContexto(a.id().toString(), a.nome(), a.aliases(), a.padrao())).toList(),
                declaracoes.vistas(dono.id(), dono.moeda()), metas(dono.id(), hoje), compromissos(dono.id(), hoje));
    }

    /** A marca de teste vem da configuração do servidor, fora do repositório, e não do Keycloak. */
    private Contexto.ContaConectada conectada(Chamador chamador) {
        boolean teste = chamador.usuario() != null && contasDeTeste.contains(chamador.usuario());
        String aviso = teste ? "Conta de teste, com dados fictícios: nada aqui é da vida real do dono." : null;
        return new Contexto.ContaConectada(chamador.usuario(), chamador.cliente(), teste, aviso);
    }

    private List<MetaVista> metas(UUID dono, java.time.LocalDate hoje) {
        Map<UUID, Long> progressos = metas.progressos(dono);
        return metas.visiveis(dono).stream().map(m -> metas.ver(dono, m, progressos.getOrDefault(m.id(), 0L), hoje)).toList();
    }

    private List<Contexto.CompromissoContexto> compromissos(UUID dono, java.time.LocalDate hoje) {
        var registros = repositorioCompromissos.registrosDoDono(dono);
        return repositorioCompromissos.compromissos(dono).stream().filter(c -> !c.encerrado()).map(c -> {
            CompromissoVisto v = compromissos.ver(dono, c, registros.getOrDefault(c.id(), Map.of()), hoje);
            return new Contexto.CompromissoContexto(v.id(), v.nome(), v.direcao(), v.tipo(), v.referencia(), v.valor(),
                    v.proxima() == null ? null : v.proxima().vencimento(), v.saldo().atrasadas(), v.saldo().valorRestante());
        }).filter(c -> c.proximoVencimento() != null || c.atrasadas() > 0).toList();
    }
}
