package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.Procedencia;

/** A regra do marco aplicada a uma conta real. */
@Component
class Historico {

    private final RepositorioEstrutura estrutura;

    Historico(RepositorioEstrutura estrutura) {
        this.estrutura = estrutura;
    }

    Procedencia procedencia(UUID conta, LocalDate data) {
        return Procedencia.de(data, estrutura.marco(conta).orElse(null));
    }

    /** Transferência e pagamento antes do marco não mudam nada no período que o sistema cobre. */
    void exigirDepoisDoMarco(Conta conta, LocalDate data, String campo) {
        estrutura.marco(conta.id()).filter(data::isBefore).ifPresent(marco -> {
            throw ErroTipado.validacao(campo, "A data " + data + " é anterior ao marco de '" + conta.nome() + "' (" + marco
                    + "). Transferência e pagamento de fatura antes do marco não se registram: o saldo inicial já os reflete.");
        });
    }
}
