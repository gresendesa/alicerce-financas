package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/** Porta de persistência da estrutura: contas, âmbitos, meios de pagamento e instituições. */
public interface RepositorioEstrutura {

    List<Conta> contas(UUID dono);

    List<Ambito> ambitos(UUID dono);

    List<MeioPagamento> meios(UUID dono);

    /** A entidade por trás de um código curto deste dono. */
    Optional<UUID> entidadeDaReferencia(UUID dono, String codigo);

    /** O id curto da entidade. */
    Optional<String> referenciaDe(UUID dono, TipoReferencia tipo, UUID entidade);

    /** Os códigos curtos das contas do dono. */
    Map<UUID, String> referenciasDeConta(UUID dono);

    /** Os códigos curtos das faturas do dono. */
    Map<UUID, String> referenciasDeFatura(UUID dono);

    Optional<UUID> instituicao(UUID dono, String nome);

    void inserirInstituicao(OperacaoEmCurso operacao, UUID id, String nome);

    /** @param marco a data em que o sistema passa a responder pelo saldo; nula em conta sem saldo */
    void inserirConta(OperacaoEmCurso operacao, Conta conta, LocalDate marco);

    /** O marco da conta; vazio em conta sem saldo. */
    Optional<LocalDate> marco(UUID conta);

    /** Os marcos das contas do dono que têm saldo. */
    Map<UUID, LocalDate> marcos(UUID dono);

    /** Grava o estado novo de uma conta; a versão que chega já é a nova. */
    void atualizarConta(OperacaoEmCurso operacao, Conta conta);

    void inserirAmbito(OperacaoEmCurso operacao, Ambito ambito);

    void atualizarAmbito(OperacaoEmCurso operacao, Ambito ambito);

    void inserirMeio(OperacaoEmCurso operacao, MeioPagamento meio);

    void atualizarMeio(OperacaoEmCurso operacao, MeioPagamento meio);
}
