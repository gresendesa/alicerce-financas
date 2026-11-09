package tools.alicerce.financeiro.adaptador.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.TelasDesfazer;
import tools.alicerce.financeiro.aplicacao.Vista;

/**
 * A única escrita da interface web. Exige {@code fin:undo}, que a API de leitura não aceita, e
 * chama o mesmo caso de uso do assistente. Operação de outro dono responde como inexistente.
 */
@RestController
class RotasDesfazer {

    record SaidaPrevia(Vista vista, String confirmacao, String valida_ate) {}

    record SaidaAplicada(Vista vista) {}

    record Confirmacao(String confirmacao, String chave_idempotencia) {}

    private final TelasDesfazer telas;

    RotasDesfazer(TelasDesfazer telas) {
        this.telas = telas;
    }

    @PostMapping("/api/fin/operacoes/{codigo}/desfazer/previa")
    ResponseEntity<SaidaPrevia> previa(@RequestAttribute(Chamador.CHAVE_CONTEXTO) Chamador chamador, @PathVariable String codigo) {
        if (!autorizado(chamador)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        TelasDesfazer.Previa previa = telas.previa(chamador, codigo);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SaidaPrevia(previa.vista(), previa.confirmacao(), previa.validaAte()));
    }

    @PostMapping("/api/fin/operacoes/{codigo}/desfazer")
    ResponseEntity<SaidaAplicada> aplicar(@RequestAttribute(Chamador.CHAVE_CONTEXTO) Chamador chamador, @PathVariable String codigo,
            @RequestBody Confirmacao pedido) {
        if (!autorizado(chamador)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (pedido.confirmacao() == null || pedido.confirmacao().isBlank()) {
            throw ErroTipado.camposFaltando(List.of("confirmacao"), null); // sem isso, o caso de uso devolveria outra prévia
        }
        Vista vista = telas.aplicar(chamador, codigo, pedido.confirmacao(), pedido.chave_idempotencia());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SaidaAplicada(vista));
    }

    private static boolean autorizado(Chamador chamador) {
        return chamador.escopos().contains(Desfazer.ESCOPO_PROPRIO);
    }

    @ExceptionHandler(ErroTipado.class)
    ResponseEntity<Map<String, Object>> erro(ErroTipado erro) {
        HttpStatus status = switch (erro.codigo()) {
            case NAO_ENCONTRADO, REFERENCIA_INEXISTENTE -> HttpStatus.NOT_FOUND;
            case INSUFFICIENT_SCOPE -> HttpStatus.FORBIDDEN;
            case JA_DESFEITA, DESFAZER_BLOQUEADO, CONFIRMACAO_EXPIRADA, CONFIRMACAO_INVALIDA, CONFLITO_IDEMPOTENCIA -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("codigo", erro.codigo().codigo());
        corpo.put("mensagem", status == HttpStatus.NOT_FOUND ? "Não encontramos esta operação na sua conta." : erro.mensagem());
        corpo.put("candidatos", erro.candidatos());
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(corpo);
    }
}
