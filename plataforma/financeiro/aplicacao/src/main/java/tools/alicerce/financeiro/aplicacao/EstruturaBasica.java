package tools.alicerce.financeiro.aplicacao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.ClasseConta;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.TipoConta;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * O que todo dono precisa ter e que nasce na primeira operação que usa: a criação do dono é
 * identidade e fica fora do registrador, então a conta técnica e o âmbito padrão não podem nascer lá.
 */
@Component
class EstruturaBasica {

    static final String SALDO_DE_ABERTURA = "Saldo de abertura";
    static final String CONVERSAO = "Conversão";
    static final String AJUSTE_NAO_IDENTIFICADO = "Ajuste não identificado";
    static final String AMBITO_PESSOAL = "Pessoal";

    private final RepositorioEstrutura estrutura;
    private final RepositorioDono donos;

    EstruturaBasica(RepositorioEstrutura estrutura, RepositorioDono donos) {
        this.estrutura = estrutura;
        this.donos = donos;
    }

    /** Uma por moeda. O nome leva a moeda só fora da moeda do dono, porque nome de conta técnica não se repete. */
    UUID contaSaldoDeAbertura(OperacaoEmCurso operacao, String moeda) {
        String doDono = donos.buscar(operacao.dono()).orElseThrow().moeda().getCurrencyCode();
        return contaTecnica(operacao, TipoConta.SALDO_ABERTURA, moeda.equals(doDono) ? SALDO_DE_ABERTURA : SALDO_DE_ABERTURA + " " + moeda, moeda);
    }

    /** A contrapartida de um ajuste de conferência, uma por moeda; aparece no resumo como linha própria. */
    UUID contaAjuste(OperacaoEmCurso operacao, String moeda) {
        String doDono = donos.buscar(operacao.dono()).orElseThrow().moeda().getCurrencyCode();
        return contaTecnica(operacao, TipoConta.NAO_IDENTIFICADO,
                moeda.equals(doDono) ? AJUSTE_NAO_IDENTIFICADO : AJUSTE_NAO_IDENTIFICADO + " " + moeda, moeda);
    }

    /** Por onde um câmbio passa, uma por moeda. */
    UUID contaConversao(OperacaoEmCurso operacao, String moeda) {
        return contaTecnica(operacao, TipoConta.CONVERSAO, CONVERSAO + " " + moeda, moeda);
    }

    private UUID contaTecnica(OperacaoEmCurso operacao, TipoConta tipo, String nome, String moeda) {
        Optional<Conta> existente = estrutura.contas(operacao.dono()).stream()
                .filter(c -> c.tipo() == tipo && c.moeda().equals(moeda)).findFirst();
        if (existente.isPresent()) {
            return existente.get().id();
        }
        Conta conta = new Conta(UUID.randomUUID(), ClasseConta.PATRIMONIO, tipo, nome, List.of(), null, null, moeda, false, 1);
        estrutura.inserirConta(operacao, conta, null);
        operacao.entidadeCriada(EntidadeVersionada.CONTA, conta.id(), conta);
        operacao.referenciar(TipoReferencia.CONTA, conta.id());
        return conta.id();
    }

    Ambito ambitoPadrao(OperacaoEmCurso operacao) {
        Optional<Ambito> existente = estrutura.ambitos(operacao.dono()).stream().filter(Ambito::padrao).findFirst();
        if (existente.isPresent()) {
            return existente.get();
        }
        // Desfazer o primeiro âmbito pode deixar um 'Pessoal' arquivado, e o nome não se repete.
        Optional<Ambito> pessoal = estrutura.ambitos(operacao.dono()).stream()
                .filter(a -> Referencias.normalizar(a.nome()).equals(Referencias.normalizar(AMBITO_PESSOAL))).findFirst();
        if (pessoal.isPresent()) {
            Ambito a = pessoal.get();
            Ambito reativado = new Ambito(a.id(), a.nome(), a.aliases(), true, false, a.versao() + 1);
            estrutura.atualizarAmbito(operacao, reativado);
            operacao.entidadeAlterada(EntidadeVersionada.AMBITO, a.id(), a.versao(), reativado);
            return reativado;
        }
        Ambito ambito = new Ambito(UUID.randomUUID(), AMBITO_PESSOAL, List.of(), true, false, 1);
        estrutura.inserirAmbito(operacao, ambito);
        operacao.entidadeCriada(EntidadeVersionada.AMBITO, ambito.id(), ambito);
        return ambito;
    }
}
