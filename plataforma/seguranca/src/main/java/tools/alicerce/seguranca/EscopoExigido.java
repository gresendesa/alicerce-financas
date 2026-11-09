package tools.alicerce.seguranca;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Escopo OAuth exigido por uma tool MCP. Toda {@code @McpTool} precisa declarar o seu. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EscopoExigido {

    String value();
}
