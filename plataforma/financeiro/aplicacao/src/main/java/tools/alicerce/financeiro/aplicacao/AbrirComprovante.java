package tools.alicerce.financeiro.aplicacao;

import java.util.Optional;
import org.springframework.stereotype.Component;

/** Entrega o arquivo de um link temporário. O bucket nunca fica exposto. */
@Component
public class AbrirComprovante {

    public record ArquivoAberto(String tipoMidia, byte[] conteudo) {}

    private final LinksComprovante links;
    private final RepositorioComprovantes repositorio;
    private final ArmazenamentoComprovantes armazenamento;

    AbrirComprovante(LinksComprovante links, RepositorioComprovantes repositorio, ArmazenamentoComprovantes armazenamento) {
        this.links = links;
        this.repositorio = repositorio;
        this.armazenamento = armazenamento;
    }

    public Optional<ArquivoAberto> abrir(String token) {
        return links.abrir(token).flatMap(a -> repositorio.buscar(a.dono(), a.comprovante()))
                .map(i -> new ArquivoAberto(i.tipoMidia(), armazenamento.ler(i.chave())));
    }
}
