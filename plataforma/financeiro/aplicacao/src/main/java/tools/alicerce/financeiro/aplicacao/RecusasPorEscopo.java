package tools.alicerce.financeiro.aplicacao;

import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.seguranca.RegistroRecusas;

/** A tool barrada pela segurança por falta de escopo entra na trilha de auditoria como recusada. */
@Component
class RecusasPorEscopo implements RegistroRecusas {

    private final RegistradorOperacoes registrador;

    RecusasPorEscopo(RegistradorOperacoes registrador) {
        this.registrador = registrador;
    }

    @Override
    public void escopoInsuficiente(Chamador chamador, String ferramenta) {
        registrador.registrarRecusa(chamador, ferramenta, CodigoErro.INSUFFICIENT_SCOPE);
    }
}
