package tools.alicerce.financeiro.aplicacao;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.ReferenciaCurta;

/** As URLs da interface web que as saídas trazem. */
@Component
public class Enderecos {

    private final String origem;

    Enderecos(@Value("${alicerce.financeiro.endereco-web}") String origem) {
        this.origem = origem.endsWith("/") ? origem.substring(0, origem.length() - 1) : origem;
    }

    /** {@code https://app.alicerce.tools/fin/t/8K3QZ2} */
    public String url(ReferenciaCurta referencia) {
        return origem + referencia.caminho();
    }

    /** A lista de lançamentos da interface web. */
    public String urlLancamentos() {
        return origem + "/fin/lancamentos";
    }

    /** A lista de operações da interface web. */
    public String urlOperacoes() {
        return origem + "/fin/operacoes";
    }

    /** A agenda da interface web. */
    public String urlAgenda() {
        return origem + "/fin/agenda";
    }

    /** A lista de compromissos da interface web. */
    public String urlCompromissos() {
        return origem + "/fin/compromissos";
    }

    /** A vista viva de um relatório, que recalcula a cada abertura. */
    public String urlRelatorio(String de, String ate, String ambito) {
        return origem + "/fin/relatorios?de=" + de + "&ate=" + ate + (ambito == null ? "" : "&ambito=" + ambito);
    }

    /** A página de envio de comprovante. */
    public String urlAnexo(String token) {
        return origem + "/fin/anexo/" + token;
    }

    /** A página pública de prestação de contas, que abre sem login. */
    public String urlPrestacao(String token) {
        return origem + "/fin/p/" + token;
    }

    /** Um comprovante pela página pública: só os que o retrato do link lista. */
    public String urlComprovantePrestacao(String token, String codigoComprovante) {
        return origem + "/fin/p/" + token + "/n/" + codigoComprovante;
    }

    /** O arquivo de um comprovante, por um link temporário. */
    public String urlComprovante(String token) {
        return origem + "/fin/comprovante/" + token;
    }
}
