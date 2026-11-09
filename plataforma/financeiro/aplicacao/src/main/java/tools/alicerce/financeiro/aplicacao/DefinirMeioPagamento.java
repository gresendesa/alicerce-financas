package tools.alicerce.financeiro.aplicacao;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ChaveIdempotencia;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.ClasseConta;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.TipoConta;
import tools.alicerce.financeiro.dominio.TipoMeioPagamento;

/** Capacidade {@code definir_meio_pagamento}. */
@Component
public class DefinirMeioPagamento {

    public record Entrada(String chaveIdempotencia, String lote, String ref, String tipo, String nome,
            List<String> aliases, String conta, Boolean padrao, Boolean arquivar) {}

    private static final String CAPACIDADE = "definir_meio_pagamento";

    private final RegistradorOperacoes registrador;
    private final RepositorioEstrutura estrutura;
    private final Referencias referencias;

    DefinirMeioPagamento(RegistradorOperacoes registrador, RepositorioEstrutura estrutura, Referencias referencias) {
        this.registrador = registrador;
        this.estrutura = estrutura;
        this.referencias = referencias;
    }

    public ResultadoEscrita<MeioDefinido> executar(Chamador chamador, Entrada e) {
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, new Argumentos().com("ref", e.ref()).com("tipo", e.tipo())
                .com("nome", e.nome()).com("aliases", Argumentos.lista(e.aliases())).com("conta", e.conta())
                .com("padrao", e.padrao()).com("arquivar", e.arquivar()).mapa(),
                ChaveIdempotencia.ler(e.chaveIdempotencia()), e.lote());
        return registrador.registrar(chamador, pedido, MeioDefinido.class, operacao -> {
            MeioPagamento meio = Argumentos.vazio(e.ref()) ? criar(operacao, e) : alterar(operacao, e);
            return saida(operacao.dono(), meio);
        });
    }

    private MeioPagamento criar(OperacaoEmCurso operacao, Entrada e) {
        List<String> faltando = new ArrayList<>();
        if (Argumentos.vazio(e.tipo())) {
            faltando.add("tipo");
        }
        if (Argumentos.vazio(e.nome())) {
            faltando.add("nome");
        }
        if (Argumentos.vazio(e.conta())) {
            faltando.add("conta");
        }
        if (!faltando.isEmpty()) {
            throw ErroTipado.camposFaltando(faltando, "Pergunte ao dono o nome, o tipo e de que conta o dinheiro sai.");
        }
        UUID dono = operacao.dono();
        TipoMeioPagamento tipo = tipo(e.tipo());
        semNomeRepetido(dono, null, e.nome());
        boolean padrao = Boolean.TRUE.equals(e.padrao());
        if (padrao) {
            tirarPadraoAtual(operacao, tipo, null);
        }
        MeioPagamento meio = new MeioPagamento(UUID.randomUUID(), tipo, e.nome().strip(), aliases(e.aliases()),
                liquidacao(dono, e.conta(), tipo).id(), padrao, null, false, 1);
        estrutura.inserirMeio(operacao, meio);
        operacao.entidadeCriada(EntidadeVersionada.MEIO_PAGAMENTO, meio.id(), meio);
        return meio;
    }

    private MeioPagamento alterar(OperacaoEmCurso operacao, Entrada e) {
        UUID dono = operacao.dono();
        MeioPagamento atual = referencias.meioParaAlterar(dono, e.ref());
        TipoMeioPagamento tipo = Argumentos.vazio(e.tipo()) ? atual.tipo() : tipo(e.tipo());
        boolean arquivado = e.arquivar() == null ? atual.arquivado() : e.arquivar();
        // Um meio arquivado deixa de ser padrão: o banco não aceita os dois juntos.
        boolean padrao = !arquivado && (e.padrao() == null ? atual.padrao() : e.padrao());
        if (padrao && (!atual.padrao() || tipo != atual.tipo())) {
            tirarPadraoAtual(operacao, tipo, atual.id());
        }
        String nome = Argumentos.vazio(e.nome()) ? atual.nome() : e.nome().strip();
        semNomeRepetido(dono, atual.id(), nome);
        // Mudar só nome ou arquivamento não revalida a conta, que pode já estar arquivada.
        UUID conta = e.conta() == null && tipo == atual.tipo() ? atual.contaLiquidacao()
                : liquidacao(dono, e.conta() == null ? atual.contaLiquidacao().toString() : e.conta(), tipo).id();
        MeioPagamento novo = new MeioPagamento(atual.id(), tipo, nome,
                e.aliases() == null ? atual.aliases() : aliases(e.aliases()),
                conta, padrao,
                atual.ambitoPadrao(), arquivado, atual.versao() + 1);
        estrutura.atualizarMeio(operacao, novo);
        operacao.entidadeAlterada(EntidadeVersionada.MEIO_PAGAMENTO, novo.id(), atual.versao(), novo);
        return novo;
    }

    private static TipoMeioPagamento tipo(String codigo) {
        return TipoMeioPagamento.deCodigo(codigo.strip()).orElseThrow(() -> ErroTipado.validacao("tipo",
                "Tipo de meio inválido: use pix, debito, credito, dinheiro, transferencia ou boleto."));
    }

    /** Crédito liquida em cartão; os demais, em conta com dinheiro. */
    private Conta liquidacao(UUID dono, String texto, TipoMeioPagamento tipo) {
        boolean credito = tipo == TipoMeioPagamento.CREDITO;
        Conta conta = referencias.conta(dono, texto, "conta", c -> c.classe() == ClasseConta.ATIVO || c.tipo() == TipoConta.CARTAO_CREDITO);
        if (credito != (conta.tipo() == TipoConta.CARTAO_CREDITO)) {
            throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, "conta", credito ? "Meio de crédito só liquida em cartão."
                    : "Só meio de crédito liquida em cartão.");
        }
        return conta;
    }

    private void tirarPadraoAtual(OperacaoEmCurso operacao, TipoMeioPagamento tipo, UUID proprio) {
        Optional<MeioPagamento> atual = estrutura.meios(operacao.dono()).stream()
                .filter(m -> m.padrao() && m.tipo() == tipo && !m.id().equals(proprio)).findFirst();
        if (atual.isPresent()) {
            MeioPagamento m = atual.get();
            MeioPagamento semPadrao = new MeioPagamento(m.id(), m.tipo(), m.nome(), m.aliases(), m.contaLiquidacao(),
                    false, m.ambitoPadrao(), m.arquivado(), m.versao() + 1);
            estrutura.atualizarMeio(operacao, semPadrao);
            operacao.entidadeAlterada(EntidadeVersionada.MEIO_PAGAMENTO, m.id(), m.versao(), semPadrao);
        }
    }

    private void semNomeRepetido(UUID dono, UUID proprio, String nome) {
        String procurado = Referencias.normalizar(nome);
        boolean repetido = estrutura.meios(dono).stream()
                .anyMatch(m -> !m.id().equals(proprio) && Referencias.normalizar(m.nome()).equals(procurado));
        if (repetido) {
            throw ErroTipado.de(CodigoErro.CONFLITO, "nome", "Já existe um meio de pagamento '" + nome.strip() + "'.");
        }
    }

    private MeioDefinido saida(UUID dono, MeioPagamento meio) {
        String conta = estrutura.contas(dono).stream().filter(c -> c.id().equals(meio.contaLiquidacao()))
                .map(Conta::nome).findFirst().orElseThrow();
        return new MeioDefinido(meio.id().toString(), meio.nome(), meio.tipo().codigo(), meio.aliases(), conta,
                meio.padrao(), meio.arquivado());
    }

    private static List<String> aliases(List<String> aliases) {
        return aliases == null ? List.of() : aliases.stream().map(String::strip).filter(a -> !a.isEmpty()).distinct().toList();
    }
}
