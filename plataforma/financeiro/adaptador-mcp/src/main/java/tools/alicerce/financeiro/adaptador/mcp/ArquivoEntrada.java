package tools.alicerce.financeiro.adaptador.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.mcp.annotation.McpToolParam;
import tools.alicerce.financeiro.aplicacao.AnexarComprovante;

/** O arquivo que o ChatGPT repassa à tool ({@code openai/fileParams}), com os quatro campos que ele exige no esquema. */
record ArquivoEntrada(
        @JsonProperty(value = "download_url", required = true) @McpToolParam(description = "Endereço temporário do arquivo.", required = true) String downloadUrl,
        @JsonProperty(value = "file_id", required = true) @McpToolParam(description = "Id do arquivo no cliente.", required = true) String fileId,
        @JsonProperty("mime_type") @McpToolParam(description = "Tipo declarado; o servidor confere o real.", required = false) String mimeType,
        @JsonProperty("file_name") @McpToolParam(description = "Nome do arquivo.", required = false) String fileName) {

    AnexarComprovante.ArquivoRecebido recebido() {
        return new AnexarComprovante.ArquivoRecebido(downloadUrl, fileId);
    }
}
