package tools.alicerce.financeiro.aplicacao;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.CartaoCredito;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.TipoConta;

/** Capacidade {@code obter_fatura}. */
@Component
public class ObterFatura {

    private final Referencias referencias;
    private final RepositorioCartoes cartoes;
    private final Faturas faturas;

    ObterFatura(Referencias referencias, RepositorioCartoes cartoes, Faturas faturas) {
        this.referencias = referencias;
        this.cartoes = cartoes;
        this.faturas = faturas;
    }

    /** @param periodo {@code 2026-11} ou o id da fatura; vazio é a fatura em que uma compra de hoje entra */
    public FaturaVista executar(Chamador chamador, String cartao, String periodo) {
        if (Argumentos.vazio(cartao)) {
            throw ErroTipado.camposFaltando(List.of("cartao"), "Pergunte ao dono de qual cartão.");
        }
        UUID dono = chamador.dono();
        Conta conta = referencias.contaParaConsultar(dono, cartao, "cartao", c -> c.tipo() == TipoConta.CARTAO_CREDITO);
        if (!Argumentos.vazio(periodo)) {
            return faturas.ver(dono, faturas.informada(dono, conta, periodo.strip(), "periodo"));
        }
        CartaoCredito dados = cartoes.cartao(conta.id()).orElseThrow();
        return faturas.atual(dono, dados);
    }
}
