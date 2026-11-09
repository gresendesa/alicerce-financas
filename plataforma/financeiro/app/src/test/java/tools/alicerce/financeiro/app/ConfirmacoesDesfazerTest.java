package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.ConfirmacoesDesfazer;

class ConfirmacoesDesfazerTest {

    /** Um relógio que os testes adiantam. */
    private static final class Relogio extends Clock {
        private Instant agora = Instant.parse("2026-10-31T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }

    private final Relogio relogio = new Relogio();
    private final ConfirmacoesDesfazer confirmacoes = new ConfirmacoesDesfazer(relogio);
    private final UUID dono = UUID.randomUUID();

    @Test
    void valeDezMinutosEDevolveAImpressao() {
        String token = confirmacoes.emitir(dono, "abc");

        relogio.agora = relogio.agora.plus(Duration.ofMinutes(10));
        assertThat(confirmacoes.conferir(dono, token)).isEqualTo("abc");

        relogio.agora = relogio.agora.plusSeconds(1);
        ErroTipado vencida = catchThrowableOfType(ErroTipado.class, () -> confirmacoes.conferir(dono, token));
        assertThat(vencida.codigo()).isEqualTo(CodigoErro.CONFIRMACAO_EXPIRADA);
    }

    @Test
    void tokenDeOutraSubidaOuAlteradoNaoVale() {
        String token = confirmacoes.emitir(dono, "abc");
        ConfirmacoesDesfazer outraSubida = new ConfirmacoesDesfazer(relogio);

        assertThat(catchThrowableOfType(ErroTipado.class, () -> outraSubida.conferir(dono, token)).codigo())
                .isEqualTo(CodigoErro.CONFIRMACAO_INVALIDA);
        assertThat(catchThrowableOfType(ErroTipado.class, () -> confirmacoes.conferir(dono, "x" + token)).codigo())
                .isEqualTo(CodigoErro.CONFIRMACAO_INVALIDA);
        assertThat(catchThrowableOfType(ErroTipado.class, () -> confirmacoes.conferir(dono, "!.!")).codigo())
                .isEqualTo(CodigoErro.CONFIRMACAO_INVALIDA);
    }
}
