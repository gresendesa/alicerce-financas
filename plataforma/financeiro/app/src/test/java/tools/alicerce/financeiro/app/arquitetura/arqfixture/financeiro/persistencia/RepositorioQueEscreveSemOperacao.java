package tools.alicerce.financeiro.app.arquitetura.arqfixture.financeiro.persistencia;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** Viola {@code escritaSoComOperacao}: grava sem receber a operação em curso. */
public class RepositorioQueEscreveSemOperacao {

    private final DSLContext banco;

    public RepositorioQueEscreveSemOperacao(DSLContext banco) {
        this.banco = banco;
    }

    public void gravar() {
        banco.insertInto(DSL.table("conta")).defaultValues().execute();
    }
}
