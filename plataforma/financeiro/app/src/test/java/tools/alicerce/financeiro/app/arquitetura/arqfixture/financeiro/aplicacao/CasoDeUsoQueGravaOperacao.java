package tools.alicerce.financeiro.app.arquitetura.arqfixture.financeiro.aplicacao;

/** Viola {@code portaDoRegistradorSoNoRegistrador}: um caso de uso usando a porta do registrador. */
public class CasoDeUsoQueGravaOperacao {

    private final RepositorioOperacoes operacoes;

    public CasoDeUsoQueGravaOperacao(RepositorioOperacoes operacoes) {
        this.operacoes = operacoes;
    }

    public RepositorioOperacoes operacoes() {
        return operacoes;
    }
}
