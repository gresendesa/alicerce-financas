package tools.alicerce.financeiro.adaptador.mcp;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import tools.alicerce.seguranca.EscopoExigido;
import tools.alicerce.seguranca.EscoposDasFerramentas;

/** Escopo exigido por cada tool, lido de {@link EscopoExigido}. A aplicação não sobe se faltar algum. */
@Component
class RegistroEscopos implements EscoposDasFerramentas, SmartInitializingSingleton {

    private final ApplicationContext contexto;
    private volatile Map<String, String> escopos = Map.of();

    RegistroEscopos(ApplicationContext contexto) {
        this.contexto = contexto;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, String> lidos = new HashMap<>();
        for (String nome : contexto.getBeanDefinitionNames()) {
            Class<?> tipo = contexto.getType(nome);
            if (tipo == null || !tipo.getPackageName().startsWith(getClass().getPackageName())) {
                continue;
            }
            for (Method metodo : ReflectionUtils.getUniqueDeclaredMethods(tipo)) {
                McpTool tool = metodo.getAnnotation(McpTool.class);
                if (tool == null) {
                    continue;
                }
                EscopoExigido escopo = metodo.getAnnotation(EscopoExigido.class);
                if (escopo == null) {
                    throw new IllegalStateException("A tool " + tool.name() + " não declara @EscopoExigido");
                }
                lidos.put(tool.name(), escopo.value());
            }
        }
        escopos = Map.copyOf(lidos);
    }

    @Override
    public Optional<String> escopoDe(String ferramenta) {
        return Optional.ofNullable(escopos.get(ferramenta));
    }
}
