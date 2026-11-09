package tools.alicerce.seguranca;

import tools.alicerce.convencoes.Chamador;

/**
 * Grava a tool barrada por falta de escopo, que nunca chega ao registrador de operações: sem isso a
 * recusa não ficaria em lugar nenhum. Só é chamado com o dono já resolvido; sem token válido
 * (401) não há dono, e nada é gravado. Implementado por cada módulo.
 */
public interface RegistroRecusas {

    /** Sem os argumentos: a chamada não foi autorizada. */
    void escopoInsuficiente(Chamador chamador, String ferramenta);
}
