package tools.alicerce.financeiro.dominio;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import tools.alicerce.convencoes.ErroTipado;

/** O valor estruturado de uma declaração, um tipo por registro. Dinheiro em centavos da moeda do dono. */
public sealed interface ValorDeclaracao {

    TipoDeclaracao tipo();

    /**
     * {@code piso_periodo}: no último dia do mês, o saldo das contas líquidas, depois de pagas as faturas e os
     * compromissos que vencem nele, deve ser de pelo menos {@code saldoMinimoCentavos}. O período é sempre o mês,
     * e as contas sempre entram pagas.
     */
    record PisoPeriodo(long saldoMinimoCentavos) implements ValorDeclaracao {

        public static final String PERIODO = "mes";

        public PisoPeriodo {
            if (saldoMinimoCentavos < 0) {
                throw ErroTipado.validacao("saldo_minimo", "O saldo mínimo do fim do mês é zero ou mais.");
            }
        }

        @Override
        public TipoDeclaracao tipo() {
            return TipoDeclaracao.PISO_PERIODO;
        }
    }

    /** {@code categorias_essenciais}: as categorias que só entram num plano de cortes depois das outras. Vazia vale: nenhuma é. */
    record CategoriasEssenciais(List<UUID> categorias) implements ValorDeclaracao {

        public CategoriasEssenciais {
            categorias = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(categorias, "categorias")));
        }

        @Override
        public TipoDeclaracao tipo() {
            return TipoDeclaracao.CATEGORIAS_ESSENCIAIS;
        }
    }

    /**
     * {@code reserva_minima}: o saldo das contas líquidas que o dono quer ter sempre. Um valor fixo ou meses de gasto, nunca os
     * dois; em meses, o valor equivalente sai de {@link CalculoPerfil#reserva} a cada leitura.
     *
     * @param valorCentavos o valor fixo; nulo em meses
     * @param mesesDeGasto de 1 a {@value CalculoPerfil#MESES_RESERVA_MAXIMOS}; nulo em valor
     */
    record ReservaMinima(Long valorCentavos, Integer mesesDeGasto) implements ValorDeclaracao {

        public ReservaMinima {
            if ((valorCentavos == null) == (mesesDeGasto == null)) {
                throw ErroTipado.validacao("valor", "A reserva mínima é um valor ou meses de gasto, um dos dois.");
            }
            if (valorCentavos != null && valorCentavos <= 0) {
                throw ErroTipado.validacao("valor", "A reserva mínima é maior que zero.");
            }
            if (mesesDeGasto != null && (mesesDeGasto < 1 || mesesDeGasto > CalculoPerfil.MESES_RESERVA_MAXIMOS)) {
                throw ErroTipado.validacao("meses_de_gasto", "Os meses de gasto vão de 1 a " + CalculoPerfil.MESES_RESERVA_MAXIMOS + ".");
            }
        }

        @Override
        public TipoDeclaracao tipo() {
            return TipoDeclaracao.RESERVA_MINIMA;
        }
    }

    /** {@code intencao_poupanca}: quanto da renda o dono quer poupar, de 0 a 100 %; 0 é "não poupar" e nunca dispara sinal. */
    record IntencaoPoupanca(BigDecimal percentual) implements ValorDeclaracao {

        public IntencaoPoupanca {
            percentual = CalculoPerfil.percentual(percentual, BigDecimal.ZERO, "percentual");
        }

        @Override
        public TipoDeclaracao tipo() {
            return TipoDeclaracao.INTENCAO_POUPANCA;
        }
    }

    /** {@code teto_parcelas}: o máximo da renda de um mês que as parcelas podem levar, de 1 a 100 %. */
    record TetoParcelas(BigDecimal percentual) implements ValorDeclaracao {

        public TetoParcelas {
            percentual = CalculoPerfil.percentual(percentual, BigDecimal.ONE, "percentual");
        }

        @Override
        public TipoDeclaracao tipo() {
            return TipoDeclaracao.TETO_PARCELAS;
        }
    }

    /** {@code meta_protegida}: as metas que nenhum plano pode atrasar, a lista inteira. Vazia vale: nenhuma é. */
    record MetaProtegida(List<UUID> metas) implements ValorDeclaracao {

        public MetaProtegida {
            metas = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(metas, "metas")));
        }

        @Override
        public TipoDeclaracao tipo() {
            return TipoDeclaracao.META_PROTEGIDA;
        }
    }

    /** {@code nota}: um texto livre do dono, até {@value CalculoPerfil#NOTA_MAXIMA} caracteres. Nenhum cálculo o lê. */
    record Nota(String texto) implements ValorDeclaracao {

        public Nota {
            if (texto == null || texto.isBlank()) {
                throw ErroTipado.validacao("texto", "A nota precisa de um texto.");
            }
            texto = texto.strip();
            if (texto.length() > CalculoPerfil.NOTA_MAXIMA) {
                throw ErroTipado.validacao("texto", "A nota tem até " + CalculoPerfil.NOTA_MAXIMA + " caracteres; esta tem " + texto.length()
                        + ". Resuma com o dono.");
            }
        }

        @Override
        public TipoDeclaracao tipo() {
            return TipoDeclaracao.NOTA;
        }
    }
}
