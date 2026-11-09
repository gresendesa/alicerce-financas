package tools.alicerce.financeiro.aplicacao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ChaveIdempotencia;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.MeioPagamento;

/** Capacidade {@code definir_ambito}: cria, altera, arquiva e define âmbitos padrão. */
@Component
public class DefinirAmbito {

    public record Entrada(String chaveIdempotencia, String lote, String ref, String nome, List<String> aliases,
            Boolean padrao, Boolean arquivar, String padraoDoMeio) {}

    private static final String CAPACIDADE = "definir_ambito";

    private final RegistradorOperacoes registrador;
    private final RepositorioEstrutura estrutura;
    private final Referencias referencias;

    DefinirAmbito(RegistradorOperacoes registrador, RepositorioEstrutura estrutura, Referencias referencias) {
        this.registrador = registrador;
        this.estrutura = estrutura;
        this.referencias = referencias;
    }

    public ResultadoEscrita<AmbitoDefinido> executar(Chamador chamador, Entrada e) {
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, new Argumentos().com("ref", e.ref()).com("nome", e.nome())
                .com("aliases", Argumentos.lista(e.aliases())).com("padrao", e.padrao()).com("arquivar", e.arquivar())
                .com("padrao_do_meio", e.padraoDoMeio()).mapa(),
                ChaveIdempotencia.ler(e.chaveIdempotencia()), e.lote());
        return registrador.registrar(chamador, pedido, AmbitoDefinido.class, operacao -> {
            Ambito ambito = Argumentos.vazio(e.ref()) ? criar(operacao, e) : alterar(operacao, e);
            if (!Argumentos.vazio(e.padraoDoMeio())) {
                padraoDoMeio(operacao, ambito, e.padraoDoMeio());
            }
            return saida(operacao.dono(), ambito);
        });
    }

    private Ambito criar(OperacaoEmCurso operacao, Entrada e) {
        if (Argumentos.vazio(e.nome())) {
            throw ErroTipado.camposFaltando(List.of("nome"), "Pergunte ao dono o nome do âmbito.");
        }
        UUID dono = operacao.dono();
        semNomeRepetido(dono, null, e.nome());
        // O primeiro âmbito é o padrão: todo dono tem um.
        boolean semPadrao = estrutura.ambitos(dono).stream().noneMatch(Ambito::padrao);
        boolean padrao = semPadrao || Boolean.TRUE.equals(e.padrao());
        if (padrao) {
            tirarPadraoAtual(operacao);
        }
        Ambito ambito = new Ambito(UUID.randomUUID(), e.nome().strip(), aliases(e.aliases()), padrao, false, 1);
        estrutura.inserirAmbito(operacao, ambito);
        operacao.entidadeCriada(EntidadeVersionada.AMBITO, ambito.id(), ambito);
        return ambito;
    }

    private Ambito alterar(OperacaoEmCurso operacao, Entrada e) {
        UUID dono = operacao.dono();
        Ambito atual = referencias.ambitoParaAlterar(dono, e.ref());
        boolean arquivado = e.arquivar() == null ? atual.arquivado() : e.arquivar();
        boolean padrao = e.padrao() == null ? atual.padrao() : e.padrao();
        if (arquivado && !atual.arquivado()) {
            podeArquivar(dono, atual, padrao);
        }
        if (atual.padrao() && !padrao) {
            throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, "padrao",
                    "Todo dono tem um âmbito padrão. Para trocar, marque outro âmbito como padrão.");
        }
        if (arquivado && padrao) {
            throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, "padrao", "Um âmbito arquivado não pode ser o padrão.");
        }
        if (padrao && !atual.padrao()) {
            tirarPadraoAtual(operacao);
        }
        String nome = Argumentos.vazio(e.nome()) ? atual.nome() : e.nome().strip();
        semNomeRepetido(dono, atual.id(), nome);
        Ambito novo = new Ambito(atual.id(), nome, e.aliases() == null ? atual.aliases() : aliases(e.aliases()), padrao,
                arquivado, atual.versao() + 1);
        estrutura.atualizarAmbito(operacao, novo);
        operacao.entidadeAlterada(EntidadeVersionada.AMBITO, novo.id(), atual.versao(), novo);
        return novo;
    }

    private void podeArquivar(UUID dono, Ambito ambito, boolean padrao) {
        if (ambito.padrao() || padrao) {
            throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, "arquivar",
                    "O âmbito '" + ambito.nome() + "' é o padrão do dono. Marque outro como padrão antes de arquivar.");
        }
        Optional<MeioPagamento> usado = estrutura.meios(dono).stream()
                .filter(m -> !m.arquivado() && ambito.id().equals(m.ambitoPadrao())).findFirst();
        if (usado.isPresent()) {
            throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, "arquivar",
                    "O âmbito '" + ambito.nome() + "' é o padrão do meio '" + usado.get().nome() + "'.");
        }
    }

    private void tirarPadraoAtual(OperacaoEmCurso operacao) {
        Optional<Ambito> atual = estrutura.ambitos(operacao.dono()).stream().filter(Ambito::padrao).findFirst();
        if (atual.isPresent()) {
            Ambito a = atual.get();
            Ambito semPadrao = new Ambito(a.id(), a.nome(), a.aliases(), false, a.arquivado(), a.versao() + 1);
            estrutura.atualizarAmbito(operacao, semPadrao);
            operacao.entidadeAlterada(EntidadeVersionada.AMBITO, a.id(), a.versao(), semPadrao);
        }
    }

    private void padraoDoMeio(OperacaoEmCurso operacao, Ambito ambito, String texto) {
        if (ambito.arquivado()) {
            throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, "padrao_do_meio", "Um âmbito arquivado não pode ser padrão.");
        }
        MeioPagamento m = referencias.meio(operacao.dono(), texto, "padrao_do_meio");
        MeioPagamento novo = new MeioPagamento(m.id(), m.tipo(), m.nome(), m.aliases(), m.contaLiquidacao(), m.padrao(),
                ambito.id(), m.arquivado(), m.versao() + 1);
        estrutura.atualizarMeio(operacao, novo);
        operacao.entidadeAlterada(EntidadeVersionada.MEIO_PAGAMENTO, novo.id(), m.versao(), novo);
    }

    private void semNomeRepetido(UUID dono, UUID proprio, String nome) {
        String procurado = Referencias.normalizar(nome);
        boolean repetido = estrutura.ambitos(dono).stream()
                .anyMatch(a -> !a.id().equals(proprio) && Referencias.normalizar(a.nome()).equals(procurado));
        if (repetido) {
            throw ErroTipado.de(CodigoErro.CONFLITO, "nome", "Já existe um âmbito '" + nome.strip() + "'.");
        }
    }

    private AmbitoDefinido saida(UUID dono, Ambito ambito) {
        List<String> meios = estrutura.meios(dono).stream().filter(m -> ambito.id().equals(m.ambitoPadrao()))
                .map(MeioPagamento::nome).toList();
        return new AmbitoDefinido(ambito.id().toString(), ambito.nome(), ambito.aliases(), ambito.padrao(),
                ambito.arquivado(), meios);
    }

    private static List<String> aliases(List<String> aliases) {
        return aliases == null ? List.of() : aliases.stream().map(String::strip).filter(a -> !a.isEmpty()).distinct().toList();
    }
}
