package tools.alicerce.financeiro.persistencia;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.COMPROMISSO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.OCORRENCIA_COMPROMISSO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.REFERENCIA_CURTA;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import tools.alicerce.financeiro.aplicacao.OperacaoEmCurso;
import tools.alicerce.financeiro.aplicacao.RepositorioCompromissos;
import tools.alicerce.financeiro.dominio.Compromisso;
import tools.alicerce.financeiro.dominio.DirecaoCompromisso;
import tools.alicerce.financeiro.dominio.FrequenciaCompromisso;
import tools.alicerce.financeiro.dominio.RegistroOcorrencia;
import tools.alicerce.financeiro.dominio.TipoCompromisso;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/** Persistência dos compromissos (estrutura mutável, com versões) e das ocorrências (só INSERT). */
@Repository
class RepositorioCompromissosJooq implements RepositorioCompromissos {

    /**
     * As gravações de ocorrência que valem, na ordem em que foram feitas: de operação aplicada e, se paga,
     * com o lançamento vigente. O estado de cada número sai de todas elas no domínio (pagamentos somam). O
     * valor pago é a soma das entradas do lançamento na moeda do compromisso.
     */
    private static final String VIGENTES = """
            select o.compromisso_id, o.numero, o.situacao, o.lancamento_id, o.nova_data,
                   o.observacao, o.operacao_id, l.data as data_pagamento,
                   (select sum(m.valor_centavos) from movimento m
                     where m.lancamento_id = o.lancamento_id and m.valor_centavos > 0 and m.moeda = c.moeda)::bigint as pago
              from ocorrencia_compromisso o
              join compromisso c on c.id = o.compromisso_id
              join operacao op on op.id = o.operacao_id
              left join lancamento l on l.id = o.lancamento_id
             where c.dono_id = ? and op.estado = 'aplicada'
               and (o.lancamento_id is null or not lancamento_anulado(o.lancamento_id))
               %s
             order by o.compromisso_id, o.numero, o.ordem
            """;

    private final DSLContext banco;

    RepositorioCompromissosJooq(DSLContext banco) {
        this.banco = banco;
    }

    @Override
    public List<Compromisso> compromissos(UUID dono) {
        return banco.selectFrom(COMPROMISSO).where(COMPROMISSO.DONO_ID.eq(dono)).orderBy(COMPROMISSO.NOME)
                .fetch(RepositorioCompromissosJooq::compromisso);
    }

    private static Compromisso compromisso(Record r) {
        Short parcelas = r.get(COMPROMISSO.PARCELAS);
        return new Compromisso(r.get(COMPROMISSO.ID), DirecaoCompromisso.deCodigo(r.get(COMPROMISSO.DIRECAO)).orElseThrow(),
                TipoCompromisso.deCodigo(r.get(COMPROMISSO.TIPO)).orElseThrow(), r.get(COMPROMISSO.NOME), List.of(r.get(COMPROMISSO.ALIASES)),
                r.get(COMPROMISSO.CONTRAPARTE), r.get(COMPROMISSO.REFERENCIA), r.get(COMPROMISSO.VALOR_CENTAVOS),
                r.get(COMPROMISSO.VALOR_TOTAL_CENTAVOS), r.get(COMPROMISSO.MOEDA),
                FrequenciaCompromisso.deCodigo(r.get(COMPROMISSO.FREQUENCIA)).orElseThrow(), r.get(COMPROMISSO.PRIMEIRO_VENCIMENTO),
                parcelas == null ? null : parcelas.intValue(), r.get(COMPROMISSO.PARCELAS_PAGAS_ANTES), r.get(COMPROMISSO.FIM),
                r.get(COMPROMISSO.CATEGORIA_ID), r.get(COMPROMISSO.CONTA_ID), r.get(COMPROMISSO.MEIO_PAGAMENTO_ID), r.get(COMPROMISSO.AMBITO_ID),
                r.get(COMPROMISSO.OBSERVACAO), r.get(COMPROMISSO.ENCERRADO), r.get(COMPROMISSO.VERSAO));
    }

    @Override
    public void inserir(OperacaoEmCurso operacao, Compromisso c) {
        banco.insertInto(COMPROMISSO).set(COMPROMISSO.ID, c.id()).set(COMPROMISSO.DONO_ID, operacao.dono())
                .set(COMPROMISSO.DIRECAO, c.direcao().codigo()).set(COMPROMISSO.TIPO, c.tipo().codigo())
                .set(campos(c)).execute();
    }

    @Override
    public void atualizar(OperacaoEmCurso operacao, Compromisso c) {
        banco.update(COMPROMISSO).set(COMPROMISSO.DIRECAO, c.direcao().codigo()).set(COMPROMISSO.TIPO, c.tipo().codigo())
                .set(campos(c)).where(COMPROMISSO.ID.eq(c.id()), COMPROMISSO.DONO_ID.eq(operacao.dono())).execute();
    }

    private Map<org.jooq.Field<?>, Object> campos(Compromisso c) {
        Map<org.jooq.Field<?>, Object> campos = new HashMap<>();
        campos.put(COMPROMISSO.NOME, c.nome());
        campos.put(COMPROMISSO.ALIASES, c.aliases().toArray(String[]::new));
        campos.put(COMPROMISSO.CONTRAPARTE, c.contraparte());
        campos.put(COMPROMISSO.REFERENCIA, c.referencia());
        campos.put(COMPROMISSO.VALOR_CENTAVOS, c.valorCentavos());
        campos.put(COMPROMISSO.VALOR_TOTAL_CENTAVOS, c.valorTotalCentavos());
        campos.put(COMPROMISSO.MOEDA, c.moeda());
        campos.put(COMPROMISSO.FREQUENCIA, c.frequencia().codigo());
        campos.put(COMPROMISSO.PRIMEIRO_VENCIMENTO, c.primeiroVencimento());
        campos.put(COMPROMISSO.PARCELAS, c.parcelas() == null ? null : c.parcelas().shortValue());
        campos.put(COMPROMISSO.PARCELAS_PAGAS_ANTES, (short) c.parcelasPagasAntes());
        campos.put(COMPROMISSO.FIM, c.fim());
        campos.put(COMPROMISSO.CATEGORIA_ID, c.categoria());
        campos.put(COMPROMISSO.CONTA_ID, c.conta());
        campos.put(COMPROMISSO.MEIO_PAGAMENTO_ID, c.meioPagamento());
        campos.put(COMPROMISSO.AMBITO_ID, c.ambito());
        campos.put(COMPROMISSO.OBSERVACAO, c.observacao());
        campos.put(COMPROMISSO.ENCERRADO, c.encerrado());
        campos.put(COMPROMISSO.VERSAO, c.versao());
        return campos;
    }

    @Override
    public Map<Integer, List<RegistroOcorrencia>> registros(UUID compromisso) {
        UUID dono = banco.select(COMPROMISSO.DONO_ID).from(COMPROMISSO).where(COMPROMISSO.ID.eq(compromisso)).fetchOne(COMPROMISSO.DONO_ID);
        Map<Integer, List<RegistroOcorrencia>> porNumero = new HashMap<>();
        vigentes(dono, "and c.id = ?", compromisso).stream().filter(v -> v.compromisso().equals(compromisso))
                .forEach(v -> porNumero.computeIfAbsent(v.registro().numero(), k -> new ArrayList<>()).add(v.registro()));
        return porNumero;
    }

    @Override
    public Map<UUID, Map<Integer, List<RegistroOcorrencia>>> registrosDoDono(UUID dono) {
        Map<UUID, Map<Integer, List<RegistroOcorrencia>>> porCompromisso = new HashMap<>();
        for (Vigente v : vigentes(dono, "")) {
            porCompromisso.computeIfAbsent(v.compromisso(), k -> new HashMap<>())
                    .computeIfAbsent(v.registro().numero(), k -> new ArrayList<>()).add(v.registro());
        }
        return porCompromisso;
    }

    private record Vigente(UUID compromisso, UUID operacao, RegistroOcorrencia registro) {}

    private List<Vigente> vigentes(UUID dono, String filtro, Object... extras) {
        List<Object> parametros = new ArrayList<>(List.of(dono));
        parametros.addAll(List.of(extras));
        List<Vigente> lista = new ArrayList<>();
        for (Record r : banco.fetch(VIGENTES.formatted(filtro), parametros.toArray())) {
            RegistroOcorrencia.Situacao situacao = RegistroOcorrencia.Situacao.valueOf(r.get("situacao", String.class).toUpperCase(Locale.ROOT));
            lista.add(new Vigente(r.get("compromisso_id", UUID.class), r.get("operacao_id", UUID.class), new RegistroOcorrencia(
                    r.get("numero", Integer.class), situacao, r.get("lancamento_id", UUID.class), r.get("pago", Long.class),
                    r.get("data_pagamento", LocalDate.class), r.get("nova_data", LocalDate.class), r.get("observacao", String.class))));
        }
        return lista;
    }

    @Override
    public void inserirOcorrencia(OperacaoEmCurso operacao, OcorrenciaGravada o) {
        banco.insertInto(OCORRENCIA_COMPROMISSO).set(OCORRENCIA_COMPROMISSO.ID, o.id()).set(OCORRENCIA_COMPROMISSO.COMPROMISSO_ID, o.compromisso())
                .set(OCORRENCIA_COMPROMISSO.NUMERO, o.numero()).set(OCORRENCIA_COMPROMISSO.VENCIMENTO, o.vencimento())
                .set(OCORRENCIA_COMPROMISSO.SITUACAO, o.situacao().codigo()).set(OCORRENCIA_COMPROMISSO.LANCAMENTO_ID, o.lancamento())
                .set(OCORRENCIA_COMPROMISSO.NOVA_DATA, o.novaData()).set(OCORRENCIA_COMPROMISSO.OBSERVACAO, o.observacao())
                .set(OCORRENCIA_COMPROMISSO.OPERACAO_ID, operacao.id()).execute();
    }

    @Override
    public List<Pagamento> pagamentosDoLancamento(UUID lancamento) {
        UUID dono = banco.fetchOptional("select dono_id from lancamento where id = ?", lancamento).map(r -> r.get(0, UUID.class)).orElse(null);
        if (dono == null) {
            return List.of();
        }
        List<Pagamento> pagamentos = new ArrayList<>();
        Map<UUID, Compromisso> compromissos = compromissos(dono).stream().collect(Collectors.toMap(Compromisso::id, c -> c));
        for (Vigente v : vigentes(dono, "and c.id in (select compromisso_id from ocorrencia_compromisso where lancamento_id = ?)", lancamento)) {
            if (lancamento.equals(v.registro().lancamento())) {
                Compromisso c = compromissos.get(v.compromisso());
                pagamentos.add(new Pagamento(v.compromisso(), v.registro().numero(), c.vencimento(v.registro().numero()), v.registro().observacao()));
            }
        }
        return pagamentos;
    }

    @Override
    public Set<UUID> operacoesComOcorrencias(UUID compromisso) {
        UUID dono = banco.select(COMPROMISSO.DONO_ID).from(COMPROMISSO).where(COMPROMISSO.ID.eq(compromisso)).fetchOne(COMPROMISSO.DONO_ID);
        Set<UUID> operacoes = new LinkedHashSet<>();
        vigentes(dono, "and c.id = ?", compromisso).forEach(v -> operacoes.add(v.operacao()));
        return operacoes;
    }

    @Override
    public Map<UUID, String> referencias(UUID dono) {
        return banco.select(REFERENCIA_CURTA.ENTIDADE_ID, REFERENCIA_CURTA.CODIGO).from(REFERENCIA_CURTA)
                .where(REFERENCIA_CURTA.DONO_ID.eq(dono), REFERENCIA_CURTA.TIPO_ENTIDADE.eq(TipoReferencia.COMPROMISSO.entidade()))
                .fetchMap(REFERENCIA_CURTA.ENTIDADE_ID, REFERENCIA_CURTA.CODIGO);
    }
}
