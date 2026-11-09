package tools.alicerce.financeiro.adaptador.http;

/**
 * A página mínima de envio: sem script, sem recurso de fora e, antes do envio, sem dados do lançamento, por
 * causa das prévias de link. A escolha da natureza é um grupo de opções do próprio HTML, com
 * comprovante marcado. Cores com contraste AA nos dois temas, e nada passa da largura de 360 px.
 */
final class PaginaEnvio {

    private static final String MOLDE = """
            <!doctype html>
            <html lang="pt-BR">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex, nofollow, noarchive">
            <meta name="color-scheme" content="light dark">
            <title>%s</title>
            <style>
            :root{color-scheme:light dark;--fundo:#ffffff;--texto:#1c1c1a;--suave:#55554f;--borda:#8a8a82;--link:#0b5394;--acento:#0b6b57;--sobre-acento:#ffffff}
            @media (prefers-color-scheme: dark){:root{--fundo:#171716;--texto:#f1f1ec;--suave:#c2c2ba;--borda:#8a8a82;--link:#8fc4f5;--acento:#62cdb4;--sobre-acento:#10201c}}
            *{box-sizing:border-box}
            body{font-family:system-ui,sans-serif;max-width:32rem;margin:0 auto;padding:2rem 1rem;line-height:1.5;background:var(--fundo);color:var(--texto)}
            h1{font-size:1.4rem}
            fieldset{border:1px solid var(--borda);border-radius:.5rem;margin:1rem 0 0;padding:.5rem 1rem 1rem}
            legend{font-weight:600;padding:0 .25rem}
            .opcao{display:flex;gap:.75rem;align-items:flex-start;margin-top:.75rem}
            .opcao input{width:1.5rem;height:1.5rem;margin:0;flex:none}
            .opcao small{display:block;color:var(--suave)}
            .campo{display:block;margin-top:1rem;font-weight:600}
            input[type=file]{display:block;width:100%%;margin-top:.25rem;font-size:1rem;color:var(--texto)}
            button{display:block;width:100%%;margin-top:1rem;font-size:1rem;padding:.75rem;cursor:pointer;border:0;border-radius:.5rem;background:var(--acento);color:var(--sobre-acento)}
            a{color:var(--link)}
            .opcao input{accent-color:var(--acento)}
            :focus-visible{outline:3px solid var(--link);outline-offset:2px}
            </style>
            </head>
            <body>
            <main>
            %s
            </main>
            </body>
            </html>
            """;

    private PaginaEnvio() {}

    static String formulario() {
        return MOLDE.formatted("Enviar comprovante ou foto", """
                <h1>Enviar comprovante ou foto</h1>
                <p>JPEG, PNG, WebP ou HEIC, até 10 MB. O link vale para um envio.</p>
                <form method="post" enctype="multipart/form-data">
                <fieldset>
                <legend>O que é esta imagem?</legend>
                <label class="opcao"><input type="radio" name="natureza" value="comprovante" checked>
                <span>Comprovante<small>Nota fiscal, recibo ou print do Pix: prova do pagamento.</small></span></label>
                <label class="opcao"><input type="radio" name="natureza" value="foto">
                <span>Foto do que foi comprado<small>O prato, o produto, o item. Só lembrança, não é prova.</small></span></label>
                </fieldset>
                <label for="arquivo" class="campo">Imagem</label>
                <input type="file" id="arquivo" name="arquivo" accept="image/jpeg,image/png,image/webp,image/heic,.heic" required>
                <button type="submit">Enviar</button>
                </form>""");
    }

    /** Os textos vêm do próprio sistema, nunca do envio. */
    static String aviso(String titulo, String texto) {
        return MOLDE.formatted(titulo, "<h1>" + titulo + "</h1>\n<p>" + texto + "</p>");
    }

    /** Depois do envio: o link volta ao lançamento, que o dono já tem. */
    static String recebido(String titulo, String texto, String urlLancamento) {
        return MOLDE.formatted(titulo, "<h1>" + titulo + "</h1>\n<p>" + texto + "</p>\n<p><a href=\"" + urlLancamento
                + "\">Voltar ao lançamento</a></p>");
    }
}
