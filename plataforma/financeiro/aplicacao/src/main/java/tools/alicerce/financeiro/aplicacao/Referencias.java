package tools.alicerce.financeiro.aplicacao;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.ClasseConta;
import tools.alicerce.financeiro.dominio.Compromisso;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * Resolve o que o assistente escreveu num parâmetro: id completo, id curto, URL, nome ou alias. Nome
 * e alias ignoram maiúsculas e acentos. Uma correspondência é usada; nenhuma ou várias viram erro
 * com os candidatos, para o assistente perguntar ao dono em vez de adivinhar.
 */
@Component
public class Referencias {

    private static final int MAXIMO_SUGESTOES = 5;

    private final RepositorioEstrutura estrutura;

    Referencias(RepositorioEstrutura estrutura) {
        this.estrutura = estrutura;
    }

    /** A conta ativa com esta referência, entre as que passam no filtro. Contas técnicas nunca entram. */
    public Conta conta(UUID dono, String texto, String campo, Predicate<Conta> filtro) {
        List<Conta> contas = estrutura.contas(dono).stream()
                .filter(c -> c.classe() != ClasseConta.PATRIMONIO && !c.arquivada() && filtro.test(c))
                .toList();
        return resolver(dono, texto, campo, "conta", contas, Conta::id, Conta::nome, Conta::aliases);
    }

    /** Para alterar: alcança também o que está arquivado. */
    public Conta contaParaAlterar(UUID dono, String texto) {
        List<Conta> contas = estrutura.contas(dono).stream().filter(c -> c.classe() != ClasseConta.PATRIMONIO).toList();
        return resolver(dono, texto, "ref", "conta", contas, Conta::id, Conta::nome, Conta::aliases);
    }

    public MeioPagamento meio(UUID dono, String texto, String campo) {
        List<MeioPagamento> meios = estrutura.meios(dono).stream().filter(m -> !m.arquivado()).toList();
        return resolver(dono, texto, campo, "meio de pagamento", meios, MeioPagamento::id, MeioPagamento::nome,
                MeioPagamento::aliases);
    }

    public MeioPagamento meioParaAlterar(UUID dono, String texto) {
        return resolver(dono, texto, "ref", "meio de pagamento", estrutura.meios(dono), MeioPagamento::id,
                MeioPagamento::nome, MeioPagamento::aliases);
    }

    public Ambito ambito(UUID dono, String texto, String campo) {
        List<Ambito> ambitos = estrutura.ambitos(dono).stream().filter(a -> !a.arquivado()).toList();
        return resolver(dono, texto, campo, "âmbito", ambitos, Ambito::id, Ambito::nome, Ambito::aliases);
    }

    /** Para consultar: alcança também o que está arquivado. */
    public Ambito ambitoParaConsultar(UUID dono, String texto, String campo) {
        return resolver(dono, texto, campo, "âmbito", estrutura.ambitos(dono), Ambito::id, Ambito::nome, Ambito::aliases);
    }

    public Ambito ambitoParaAlterar(UUID dono, String texto) {
        return resolver(dono, texto, "ref", "âmbito", estrutura.ambitos(dono), Ambito::id, Ambito::nome, Ambito::aliases);
    }

    /** Para consultar: alcança o que está arquivado, sem as contas técnicas. */
    public Conta contaParaConsultar(UUID dono, String texto, String campo, Predicate<Conta> filtro) {
        List<Conta> contas = estrutura.contas(dono).stream()
                .filter(c -> c.classe() != ClasseConta.PATRIMONIO && filtro.test(c)).toList();
        return resolver(dono, texto, campo, "conta", contas, Conta::id, Conta::nome, Conta::aliases);
    }

    public MeioPagamento meioParaConsultar(UUID dono, String texto, String campo) {
        return resolver(dono, texto, campo, "meio de pagamento", estrutura.meios(dono), MeioPagamento::id,
                MeioPagamento::nome, MeioPagamento::aliases);
    }

    /** O compromisso por id, id curto, URL, nome ou alias, entre as opções dadas. */
    public Compromisso compromisso(UUID dono, String texto, String campo, List<Compromisso> opcoes) {
        return resolver(dono, texto, campo, "compromisso", opcoes, Compromisso::id, Compromisso::nome, Compromisso::aliases);
    }

    /** A meta por id, id curto, URL, nome ou alias, entre as opções dadas. */
    public tools.alicerce.financeiro.dominio.Meta meta(UUID dono, String texto, String campo, List<tools.alicerce.financeiro.dominio.Meta> opcoes) {
        return resolver(dono, texto, campo, "meta", opcoes, tools.alicerce.financeiro.dominio.Meta::id, tools.alicerce.financeiro.dominio.Meta::nome,
                tools.alicerce.financeiro.dominio.Meta::aliases);
    }

    /** O lançamento por id, id curto ou URL. */
    public UUID lancamento(UUID dono, String texto, String campo) {
        return entidade(dono, texto, campo, TipoReferencia.LANCAMENTO, "lançamento");
    }

    /** A operação por id, id curto ou URL. */
    public UUID operacao(UUID dono, String texto, String campo) {
        return entidade(dono, texto, campo, TipoReferencia.OPERACAO, "operação");
    }

    /** O retrato por id curto ou URL. */
    public UUID retrato(UUID dono, String texto, String campo) {
        return entidade(dono, texto, campo, TipoReferencia.RETRATO, "retrato");
    }

    private UUID entidade(UUID dono, String texto, String campo, TipoReferencia tipo, String nome) {
        Optional<ReferenciaCurta> curta = ReferenciaCurta.ler(texto, TipoReferencia.MODULO);
        if (curta.isPresent() && !curta.get().prefixo().equals(tipo.prefixo())) {
            throw ErroTipado.validacao(campo, "'" + texto.strip() + "' não é o id de um(a) " + nome + ".");
        }
        return curta.flatMap(r -> estrutura.entidadeDaReferencia(dono, r.id()))
                .orElseThrow(() -> ErroTipado.referencia(CodigoErro.REFERENCIA_INEXISTENTE, campo,
                        "Nenhum(a) " + nome + " corresponde a '" + texto.strip() + "'.", List.of(),
                        "Confira o id com o dono ou procure na listagem."));
    }

    /** O nome como é comparado: sem acento, em minúsculas e com espaços simples. */
    public static String normalizar(String nome) {
        String semAcento = Normalizer.normalize(nome, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return semAcento.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private <T> T resolver(UUID dono, String texto, String campo, String tipo, List<T> opcoes, Function<T, UUID> id,
            Function<T, String> nome, Function<T, List<String>> aliases) {
        Optional<UUID> porId = idDe(dono, texto);
        if (porId.isPresent()) {
            Optional<T> achada = opcoes.stream().filter(o -> id.apply(o).equals(porId.get())).findFirst();
            if (achada.isPresent()) {
                return achada.get();
            }
        }
        String procurado = normalizar(texto);
        List<T> iguais = opcoes.stream()
                .filter(o -> normalizar(nome.apply(o)).equals(procurado)
                        || aliases.apply(o).stream().anyMatch(a -> normalizar(a).equals(procurado)))
                .toList();
        if (iguais.size() == 1) {
            return iguais.get(0);
        }
        if (iguais.size() > 1) {
            throw ErroTipado.referencia(CodigoErro.REFERENCIA_AMBIGUA, campo,
                    "'" + texto + "' corresponde a " + iguais.size() + " opções de " + tipo + ".",
                    candidatos(iguais, id, nome), "Pergunte ao dono qual delas ele quis dizer.");
        }
        List<T> proximas = opcoes.stream()
                .filter(o -> normalizar(nome.apply(o)).contains(procurado) || procurado.contains(normalizar(nome.apply(o))))
                .limit(MAXIMO_SUGESTOES).toList();
        throw ErroTipado.referencia(CodigoErro.REFERENCIA_INEXISTENTE, campo,
                "Nenhum " + tipo + " corresponde a '" + texto + "'.", candidatos(proximas, id, nome),
                "Pergunte ao dono qual é, ou se é para criar.");
    }

    private Optional<UUID> idDe(UUID dono, String texto) {
        try {
            return Optional.of(UUID.fromString(texto.strip()));
        } catch (IllegalArgumentException naoEhUuid) {
            return ReferenciaCurta.ler(texto, TipoReferencia.MODULO)
                    .flatMap(referencia -> estrutura.entidadeDaReferencia(dono, referencia.id()));
        }
    }

    private static <T> List<ErroTipado.Candidato> candidatos(List<T> opcoes, Function<T, UUID> id, Function<T, String> nome) {
        List<ErroTipado.Candidato> candidatos = new ArrayList<>();
        for (T opcao : opcoes) {
            candidatos.add(new ErroTipado.Candidato(id.apply(opcao).toString(), nome.apply(opcao), null));
        }
        return candidatos;
    }
}
