package tools.alicerce.financeiro.aplicacao;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.RepositorioComprovantes.Indice;
import tools.alicerce.financeiro.dominio.NaturezaImagem;
import tools.alicerce.financeiro.dominio.TipoImagem;
import tools.alicerce.financeiro.dominio.TipoLancamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * O que as entradas de comprovante têm em comum. Banco e S3 não têm transação comum: o arquivo é gravado
 * antes do índice, então uma falha deixa no máximo um objeto órfão, nunca um índice sem arquivo. A chave é
 * o sha256 do conteúdo, e repetir o envio da mesma imagem não cria outro objeto.
 */
@Component
public class Comprovantes {

    /** Um arquivo validado e já guardado. */
    public record Arquivo(TipoImagem tipo, int tamanho, String chave, String sha256) {}

    static final Duration VALIDADE_LINK = Duration.ofHours(24);

    private static final Clock RELOGIO = Clock.systemUTC();
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final RepositorioComprovantes repositorio;
    private final RepositorioLancamentos lancamentos;
    private final RepositorioEstrutura estrutura;
    private final ArmazenamentoComprovantes armazenamento;
    private final LinksComprovante links;
    private final Enderecos enderecos;

    Comprovantes(RepositorioComprovantes repositorio, RepositorioLancamentos lancamentos, RepositorioEstrutura estrutura,
            ArmazenamentoComprovantes armazenamento, LinksComprovante links, Enderecos enderecos) {
        this.repositorio = repositorio;
        this.lancamentos = lancamentos;
        this.estrutura = estrutura;
        this.armazenamento = armazenamento;
        this.links = links;
        this.enderecos = enderecos;
    }

    String sortearToken() {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(byte[] conteudo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (GeneralSecurityException impossivel) {
            throw new IllegalStateException(impossivel);
        }
    }

    static String hashToken(String token) {
        return sha256(token.getBytes(StandardCharsets.UTF_8));
    }

    static OffsetDateTime agora() {
        return OffsetDateTime.now(RELOGIO).withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** Grava o link de envio. Só o hash vai para o banco. */
    void emitirLink(OperacaoEmCurso operacao, UUID lancamento, String token) {
        UUID id = UUID.randomUUID();
        repositorio.inserirToken(operacao, id, hashToken(token), lancamento, agora().plus(VALIDADE_LINK));
        operacao.tokenAnexoEmitido(id);
    }

    String urlDoLancamento(String id) {
        return enderecos.url(ReferenciaCurta.ler(id, TipoReferencia.MODULO).orElseThrow());
    }

    String urlAnexo(String token) {
        return enderecos.urlAnexo(token);
    }

    String validadeLink() {
        return agora().plus(VALIDADE_LINK).toString();
    }

    /** O link sai só na resposta: a repetição devolve o resultado gravado, sem ele. */
    ResultadoEscrita<LancamentoRegistrado> comLink(ResultadoEscrita<LancamentoRegistrado> resultado, String token) {
        if (resultado.repetido() || !Boolean.TRUE.equals(resultado.valor().comprovantePendente())) {
            return resultado;
        }
        return new ResultadoEscrita<>(resultado.valor().comLink(urlAnexo(token), validadeLink()), resultado.operacao(), false);
    }

    /** Recusa o que não é imagem aceita. */
    static TipoImagem validar(byte[] conteudo) {
        if (conteudo.length == 0) {
            throw ErroTipado.validacao("arquivo", "O arquivo está vazio.");
        }
        if (conteudo.length > TipoImagem.TAMANHO_MAXIMO) {
            throw tamanhoExcedido();
        }
        return TipoImagem.detectar(conteudo).orElseThrow(() -> ErroTipado.validacao("arquivo",
                "O arquivo não é uma imagem aceita: envie JPEG, PNG, WebP ou HEIC."));
    }

    static ErroTipado tamanhoExcedido() {
        return ErroTipado.validacao("arquivo", "O arquivo passa de 10 MB.");
    }

    /** Valida e grava no bucket; é chamado fora da transação do registrador. */
    Arquivo guardar(UUID dono, byte[] conteudo) {
        TipoImagem tipo = validar(conteudo);
        String sha256 = sha256(conteudo);
        String chave = "comprovantes/" + dono + "/" + sha256;
        armazenamento.guardar(chave, conteudo, tipo.midia());
        return new Arquivo(tipo, conteudo.length, chave, sha256);
    }

    /** Indexa o arquivo e o liga ao lançamento. A natureza não muda depois. */
    ReferenciaCurta indexar(OperacaoEmCurso operacao, Arquivo arquivo, UUID lancamento, NaturezaImagem natureza) {
        UUID id = UUID.randomUUID();
        repositorio.inserir(operacao, new Indice(id, lancamento, arquivo.tipo().midia(), arquivo.tamanho(), arquivo.chave(),
                arquivo.sha256(), null, natureza.codigo()));
        operacao.comprovanteVinculado(id, null, lancamento);
        return operacao.referenciar(TipoReferencia.COMPROVANTE, id);
    }

    /** A natureza pedida; ausente é comprovante, o padrão. */
    static NaturezaImagem natureza(String texto, String campo) {
        if (Argumentos.vazio(texto)) {
            return NaturezaImagem.COMPROVANTE;
        }
        return NaturezaImagem.ler(texto).orElseThrow(() -> ErroTipado.validacao(campo,
                "A natureza é comprovante (nota, recibo, print do Pix) ou foto (o que foi comprado)."));
    }

    /** Só um lançamento vigente, que não seja estorno, recebe comprovante. */
    void exigirVigente(UUID dono, UUID lancamento, String campo) {
        if (lancamentos.buscar(dono, lancamento).orElseThrow().tipo() == TipoLancamento.ESTORNO) {
            throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, campo, "Um estorno não recebe comprovante.");
        }
        if (lancamentos.cadeia(dono, lancamento).tamanho() % 2 == 1) {
            throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, campo, "Esse lançamento foi anulado.");
        }
    }

    void moverTodos(OperacaoEmCurso operacao, UUID de, UUID para) {
        for (Indice indice : repositorio.doLancamento(operacao.dono(), de)) {
            mover(operacao, indice.id(), de, para);
        }
    }

    void mover(OperacaoEmCurso operacao, UUID comprovante, UUID de, UUID para) {
        repositorio.vincular(operacao, comprovante, para);
        operacao.comprovanteVinculado(comprovante, de, para);
    }

    boolean vinculadoA(UUID dono, UUID comprovante, UUID lancamento) {
        return Objects.equals(repositorio.buscar(dono, comprovante).orElseThrow().lancamento(), lancamento);
    }

    String codigoDoLancamento(UUID lancamento) {
        return lancamentos.codigos(List.of(lancamento)).get(lancamento);
    }

    Optional<ComprovanteVisto> ver(UUID dono, UUID id) {
        return repositorio.buscar(dono, id).map(i -> ver(dono, i));
    }

    List<ComprovanteVisto> doLancamento(UUID dono, UUID lancamento) {
        List<ComprovanteVisto> vistos = new ArrayList<>();
        repositorio.doLancamento(dono, lancamento).forEach(i -> vistos.add(ver(dono, i)));
        return vistos;
    }

    private ComprovanteVisto ver(UUID dono, Indice i) {
        String codigo = estrutura.referenciaDe(dono, TipoReferencia.COMPROVANTE, i.id()).orElseThrow();
        return new ComprovanteVisto(codigo, i.natureza(), i.tipoMidia(), i.tamanhoBytes(), i.enviadoEm().toString(),
                i.lancamento() == null ? null : codigoDoLancamento(i.lancamento()),
                enderecos.urlComprovante(links.emitir(dono, i.id())), links.validoAte().toString());
    }
}
