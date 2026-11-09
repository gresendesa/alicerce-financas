package tools.alicerce.financeiro.aplicacao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.alicerce.financeiro.aplicacao.ReversaoRegistrada.EntidadeRevertida;

/** O que um {@code desfazer} vai acumulando enquanto aplica os inversos. */
final class ReversaoEmCurso {

    final List<String> impressao = new ArrayList<>();
    final Set<UUID> dependentes = new LinkedHashSet<>();
    /** As notas que a operação em reversão ainda vai mover: o ponteiro fora do lugar é passageiro. */
    final Set<String> chavesDoAlvo = new LinkedHashSet<>();
    final Set<UUID> contasTocadas = new LinkedHashSet<>();
    /** Conferidas no fim: no meio da reversão, a fatura pode estar sem o lançamento que vai voltar. */
    final Set<UUID> faturasTocadas = new LinkedHashSet<>();
    /** Por lançamento original: verdadeiro se volta a valer. O último inverso decide. */
    final Map<UUID, Boolean> lancamentos = new LinkedHashMap<>();
    final List<EntidadeRevertida> restauradas = new ArrayList<>();
    final List<EntidadeRevertida> arquivadas = new ArrayList<>();
    final List<String> mantidas = new ArrayList<>();
    /** O que não se desfaz, ou que muda fora do Alicerce, como um link público que volta a abrir. */
    final List<String> externos = new ArrayList<>();
}
