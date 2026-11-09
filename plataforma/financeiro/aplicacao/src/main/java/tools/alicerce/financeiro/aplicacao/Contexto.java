package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Tudo o que o assistente precisa saber antes de qualquer outra coisa.
 *
 * @param declaracoes as declarações vigentes do perfil, uma por tipo
 * @param metas as metas não arquivadas, com o progresso e o aporte mensal necessário, da mais importante para a menos
 *
 * @param contaConectada qual conta do Alicerce respondeu, para o dono saber se está na real ou na de teste
 */
public record Contexto(
        ContaConectada contaConectada,
        String fuso,
        String moeda,
        List<ContaContexto> contas,
        List<CartaoContexto> cartoes,
        List<MeioContexto> meiosPagamento,
        List<AmbitoContexto> ambitos,
        List<DeclaracaoVista> declaracoes,
        List<MetaVista> metas,
        List<CompromissoContexto> compromissos) {

    /**
     * @param usuario o login no provedor de identidade; ausente se o token não o traz
     * @param cliente o assistente que chamou
     * @param contaDeTeste a conta é de teste, com dados fictícios
     */
    public record ContaConectada(String usuario, String cliente, boolean contaDeTeste, String aviso) {}

    /**
     * Um compromisso ativo, resumido; listar_compromissos traz o resto.
     *
     * @param valorRestante ausente num recorrente sem fim
     */
    public record CompromissoContexto(String id, String nome, String direcao, String tipo, String referencia, String valor,
            String proximoVencimento, int atrasadas, String valorRestante) {}

    /**
     * @param saldo {@code null} em categorias e fontes de renda
     * @param marco desde quando o sistema responde pelo saldo; {@code null} em categorias e fontes de renda
     * @param moeda a moeda da conta e do saldo, que pode não ser a do dono
     * @param ultimaConferencia a data da última conferência; ausente se nunca foi conferida ou se não tem saldo
     * @param situacaoConferencia {@code conferida}, {@code pendente} ou {@code ajustada}; ausente se nunca foi conferida
     * @param conferenciaVencida sem conferência há mais de 30 dias (ou, sem nenhuma, 30 dias depois do marco); ausente sem saldo
     */
    public record ContaContexto(String id, String nome, String tipo, List<String> aliases, String contaPai, String saldo, String marco,
            String moeda, String ultimaConferencia, String situacaoConferencia, Boolean conferenciaVencida) {

        public ContaContexto(String id, String nome, String tipo, List<String> aliases, String contaPai, String saldo, String marco,
                String moeda) {
            this(id, nome, tipo, aliases, contaPai, saldo, marco, moeda, null, null, null);
        }
    }

    /**
     * @param limiteDisponivel ausente em cartão sem limite informado
     * @param faturaAtual a fatura em que uma compra de hoje entra
     */
    public record CartaoContexto(String id, String nome, int diaFechamento, int diaVencimento, String limite, String limiteDisponivel,
            String contaPagamento, FaturaVista faturaAtual) {}

    public record MeioContexto(String id, String nome, String tipo, List<String> aliases, String conta, boolean padrao,
            String ambitoPadrao) {}

    public record AmbitoContexto(String id, String nome, List<String> aliases, boolean padrao) {}
}
