package tools.alicerce.financeiro.aplicacao;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.RepositorioComprovantes.TokenAnexo;
import tools.alicerce.financeiro.dominio.NaturezaImagem;

/**
 * A página de envio: a única escrita que não vem de um assistente. Quem autoriza é o token de uso único
 * da URL, preso a um lançamento e válido por 24 horas. Abrir a página não gasta o token, porque prévias
 * de link também a abrem; só o envio de uma imagem válida o consome.
 */
@Component
public class EnviarComprovante {

    public static final String CAPACIDADE = "enviar_comprovante";
    /** Desfazer um envio pede o mesmo escopo de anexar pelo assistente. */
    static final String ESCOPO_DESFAZER = "fin:write";
    private static final String CLIENTE = "pagina-envio";
    /** O link pedido pela interface web: o envio aparece na trilha como dela. */
    private static final String CLIENTE_INTERFACE = "app-web";

    private final RepositorioComprovantes repositorio;
    private final Comprovantes comprovantes;
    private final RegistradorOperacoes registrador;

    EnviarComprovante(RepositorioComprovantes repositorio, Comprovantes comprovantes, RegistradorOperacoes registrador) {
        this.repositorio = repositorio;
        this.comprovantes = comprovantes;
        this.registrador = registrador;
    }

    /** O token ainda serve para um envio? */
    public boolean valido(String token) {
        return vigente(token).isPresent();
    }

    /** @param natureza {@code comprovante} ou {@code foto}, como a página perguntou; ausente é comprovante */
    public ResultadoEscrita<AnexoRegistrado> enviar(String token, byte[] conteudo, String natureza) {
        NaturezaImagem escolhida = Comprovantes.natureza(natureza, "natureza");
        Comprovantes.validar(conteudo);
        String hash = Comprovantes.hashToken(token);
        TokenAnexo vigente = vigente(token).orElseThrow(EnviarComprovante::linkInvalido);
        Comprovantes.Arquivo arquivo = comprovantes.guardar(vigente.dono(), conteudo);
        String cliente = CLIENTE_INTERFACE.equals(vigente.emitidoPor()) ? CLIENTE_INTERFACE : CLIENTE;
        Chamador chamador = new Chamador(vigente.dono(), cliente, Set.of());
        Map<String, ?> argumentos = new Argumentos().com("lancamento", vigente.lancamento().toString())
                .com("sha256", arquivo.sha256()).com("natureza", escolhida.codigo()).mapa();
        return registrador.registrar(chamador, new PedidoEscrita(CAPACIDADE, argumentos, null, null), AnexoRegistrado.class, o -> {
            if (!repositorio.consumirToken(o, hash, Comprovantes.agora())) {
                throw linkInvalido();
            }
            comprovantes.exigirVigente(o.dono(), vigente.lancamento(), "token");
            return new AnexoRegistrado(comprovantes.codigoDoLancamento(vigente.lancamento()),
                    comprovantes.indexar(o, arquivo, vigente.lancamento(), escolhida).id(), escolhida.codigo(), null, null);
        });
    }

    /** O endereço do lançamento na interface, para a página voltar a ele depois do envio. */
    public String urlDoLancamento(String id) {
        return comprovantes.urlDoLancamento(id);
    }

    private Optional<TokenAnexo> vigente(String token) {
        return repositorio.token(Comprovantes.hashToken(token))
                .filter(t -> t.usadoEm() == null && t.expiraEm().isAfter(Comprovantes.agora()));
    }

    private static ErroTipado linkInvalido() {
        return ErroTipado.de(CodigoErro.NAO_ENCONTRADO, "token", "Este link não vale mais: já foi usado ou venceu.");
    }
}
