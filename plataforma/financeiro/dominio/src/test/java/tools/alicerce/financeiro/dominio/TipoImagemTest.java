package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TipoImagemTest {

    private static byte[] b(String texto) {
        return texto.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void reconheceOsQuatroFormatosPelosBytes() {
        assertThat(TipoImagem.detectar(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).contains(TipoImagem.JPEG);
        assertThat(TipoImagem.detectar(b("\u0089PNG\r\n\u001a\n...."))).contains(TipoImagem.PNG);
        assertThat(TipoImagem.detectar(b("RIFF\0\0\0\0WEBPVP8 "))).contains(TipoImagem.WEBP);
        assertThat(TipoImagem.detectar(b("\0\0\0\u0018ftypheic\0\0\0\0mif1heic"))).contains(TipoImagem.HEIC);
        assertThat(TipoImagem.detectar(b("\0\0\0\u0018ftypmif1\0\0\0\0mif1heic"))).contains(TipoImagem.HEIC);
    }

    @Test
    void recusaOResto() {
        assertThat(TipoImagem.detectar(b("<svg></svg>"))).isEmpty();
        assertThat(TipoImagem.detectar(b("%PDF-1.7 aaaaaaaa"))).isEmpty();
        assertThat(TipoImagem.detectar(b("GIF89a aaaaaaaa"))).isEmpty();
        assertThat(TipoImagem.detectar(b("\0\0\0\u0018ftypavif\0\0\0\0mif1avif"))).isEmpty();
        assertThat(TipoImagem.detectar(b("\0\0\0\u0018ftypmif1\0\0\0\0mif1avif"))).isEmpty();
        assertThat(TipoImagem.detectar(new byte[0])).isEmpty();
    }
}
