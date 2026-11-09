package tools.alicerce.financeiro.dominio;

import java.util.Arrays;
import tools.alicerce.convencoes.ErroTipado;

/** Divide centavos sem perder nenhum. Sem {@code double}: toda conta é em {@code long}. */
public final class Repartir {

    public static final int MAXIMO_PARCELAS = 48;

    private Repartir() {}

    /** O total em parcelas iguais; a sobra vai para a primeira: 100,00 em 3x dá 33,34 + 33,33 + 33,33. */
    public static long[] parcelas(long centavos, int quantidade) {
        exigirQuantidade(quantidade, "parcelas");
        if (centavos < quantidade) {
            throw ErroTipado.validacao("parcelas", "O valor é pequeno demais para tantas parcelas.");
        }
        long[] parcelas = new long[quantidade];
        Arrays.fill(parcelas, centavos / quantidade);
        parcelas[0] += centavos % quantidade;
        return parcelas;
    }

    public static void exigirQuantidade(int quantidade, String campo) {
        if (quantidade < 1 || quantidade > MAXIMO_PARCELAS) {
            throw ErroTipado.validacao(campo, "Use de 1 a " + MAXIMO_PARCELAS + " parcelas.");
        }
    }

    /**
     * Cruza dois jeitos de dividir o mesmo total: cada linha soma {@code linhas[i]}, cada coluna soma
     * {@code colunas[j]}, e nenhuma célula é negativa. Cada célula começa proporcional, arredondada
     * para baixo; os centavos que sobram entram na primeira célula em que ainda cabem.
     */
    public static long[][] matriz(long[] linhas, long[] colunas) {
        long total = Arrays.stream(linhas).sum();
        if (total != Arrays.stream(colunas).sum()) {
            throw new IllegalArgumentException("Linhas e colunas com totais diferentes");
        }
        long[][] celulas = new long[linhas.length][colunas.length];
        long[] faltaLinha = linhas.clone();
        long[] faltaColuna = colunas.clone();
        for (int i = 0; i < linhas.length; i++) {
            for (int j = 0; j < colunas.length; j++) {
                celulas[i][j] = Math.multiplyExact(linhas[i], colunas[j]) / total;
                faltaLinha[i] -= celulas[i][j];
                faltaColuna[j] -= celulas[i][j];
            }
        }
        for (int i = 0; i < linhas.length; i++) {
            for (int j = 0; j < colunas.length && faltaLinha[i] > 0; j++) {
                long resto = Math.min(faltaLinha[i], faltaColuna[j]);
                celulas[i][j] += resto;
                faltaLinha[i] -= resto;
                faltaColuna[j] -= resto;
            }
        }
        return celulas;
    }
}
