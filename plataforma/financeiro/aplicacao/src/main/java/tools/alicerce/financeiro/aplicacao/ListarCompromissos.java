package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.Compromisso;
import tools.alicerce.financeiro.dominio.DirecaoCompromisso;
import tools.alicerce.financeiro.dominio.RegistroOcorrencia;

/** Capacidade {@code listar_compromissos}: o que existe, com referência, parcelas pagas e valor restante. */
@Component
public class ListarCompromissos {

    /**
     * @param texto procura no nome, nos aliases, na referência, na contraparte e na observação
     * @param pessoa procura na contraparte
     */
    public record Entrada(String texto, String pessoa, String ambito, String direcao, Boolean incluirEncerrados) {}

    public record Pagina(List<CompromissoVisto> compromissos, String dataReferencia) {}

    private final RepositorioCompromissos repositorio;
    private final Compromissos compromissos;
    private final Referencias referencias;

    ListarCompromissos(RepositorioCompromissos repositorio, Compromissos compromissos, Referencias referencias) {
        this.repositorio = repositorio;
        this.compromissos = compromissos;
        this.referencias = referencias;
    }

    public Pagina executar(Chamador chamador, Entrada e) {
        UUID dono = chamador.dono();
        LocalDate hoje = compromissos.hoje(dono);
        UUID ambito = Argumentos.vazio(e.ambito()) ? null : referencias.ambitoParaConsultar(dono, e.ambito(), "ambito").id();
        DirecaoCompromisso direcao = Argumentos.vazio(e.direcao()) ? null : DirecaoCompromisso.deCodigo(e.direcao().strip())
                .orElseThrow(() -> ErroTipado.validacao("direcao", "Direção inválida: use saida ou entrada."));
        String texto = Argumentos.vazio(e.texto()) ? null : Referencias.normalizar(e.texto());
        String pessoa = Argumentos.vazio(e.pessoa()) ? null : Referencias.normalizar(e.pessoa());
        Map<UUID, Map<Integer, List<RegistroOcorrencia>>> registros = repositorio.registrosDoDono(dono);
        List<CompromissoVisto> lista = repositorio.compromissos(dono).stream()
                .filter(c -> Boolean.TRUE.equals(e.incluirEncerrados()) || !c.encerrado())
                .filter(c -> ambito == null || ambito.equals(c.ambito()))
                .filter(c -> direcao == null || direcao == c.direcao())
                .filter(c -> pessoa == null || contem(c.contraparte(), pessoa))
                .filter(c -> texto == null || contem(c.nome(), texto) || c.aliases().stream().anyMatch(a -> contem(a, texto))
                        || contem(c.referencia(), texto) || contem(c.contraparte(), texto) || contem(c.observacao(), texto))
                .map(c -> compromissos.ver(dono, c, registros.getOrDefault(c.id(), Map.of()), hoje))
                .toList();
        return new Pagina(lista, hoje.toString());
    }

    /** O compromisso com todas as ocorrências: num recorrente, até um ano à frente. */
    public CompromissoVisto ver(Chamador chamador, String ref) {
        UUID dono = chamador.dono();
        Compromisso c = compromissos.resolver(dono, ref, "ref");
        LocalDate hoje = compromissos.hoje(dono);
        Map<Integer, List<RegistroOcorrencia>> registros = repositorio.registros(c.id());
        return compromissos.ver(dono, c, registros, hoje).comOcorrencias(compromissos.vistas(c, compromissos.todas(c, registros, hoje)));
    }

    private static boolean contem(String campo, String procurado) {
        return campo != null && Referencias.normalizar(campo).contains(procurado);
    }
}
