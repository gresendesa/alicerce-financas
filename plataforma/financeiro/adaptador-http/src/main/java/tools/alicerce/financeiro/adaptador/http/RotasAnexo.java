package tools.alicerce.financeiro.adaptador.http;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.AnexarComprovante;
import tools.alicerce.financeiro.aplicacao.AnexoRegistrado;

/**
 * A segunda escrita da interface web: pede o link de envio de comprovante ou foto de um
 * lançamento. Exige {@code fin:attach}, que nada mais aceita, e chama o mesmo caso de uso do assistente,
 * com registrador, efeito e auditoria; o cliente é o {@code azp}, {@code app-web}. O link sai só na
 * resposta, nunca gravado. Lançamento de outro dono responde como inexistente.
 */
@RestController
class RotasAnexo {

    record Saida(String url_anexo, String valida_ate) {}

    private final AnexarComprovante anexar;

    RotasAnexo(AnexarComprovante anexar) {
        this.anexar = anexar;
    }

    @PostMapping("/api/fin/lancamentos/{codigo}/anexo")
    ResponseEntity<Saida> link(@RequestAttribute(Chamador.CHAVE_CONTEXTO) Chamador chamador, @PathVariable String codigo) {
        if (!chamador.escopos().contains(AnexarComprovante.ESCOPO_INTERFACE)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        AnexoRegistrado anexo = anexar.executar(chamador, new AnexarComprovante.Entrada(null, null, "t-" + codigo, null, null)).valor();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Saida(anexo.urlAnexo(), anexo.validaAte()));
    }

    @ExceptionHandler(ErroTipado.class)
    ResponseEntity<Map<String, Object>> erro(ErroTipado erro) {
        HttpStatus status = switch (erro.codigo()) {
            case NAO_ENCONTRADO, REFERENCIA_INEXISTENTE -> HttpStatus.NOT_FOUND;
            case INSUFFICIENT_SCOPE -> HttpStatus.FORBIDDEN;
            case VIOLACAO_INVARIANTE -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("codigo", erro.codigo().codigo());
        corpo.put("mensagem", status == HttpStatus.NOT_FOUND ? "Não encontramos este lançamento na sua conta." : erro.mensagem());
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(corpo);
    }
}
