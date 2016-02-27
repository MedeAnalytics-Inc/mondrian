/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (c) 2015-2015 Pentaho Corporation.
// All Rights Reserved.
*/
package mondrian.rolap;

import mondrian.calc.DummyExp;
import mondrian.olap.Exp;
import mondrian.olap.Literal;
import mondrian.olap.fun.MondrianEvaluationException;
import mondrian.olap.type.NullType;
import mondrian.rolap.sql.SqlQuery;
import mondrian.spi.Dialect;

import junit.framework.TestCase;

import java.math.BigDecimal;
import java.util.HashMap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Andrey Khayrutdinov
 */
public class NumberSqlCompilerTest extends TestCase {

    private RolapNativeSql.NumberSqlCompiler compiler;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        Dialect dialect = mock(Dialect.class);
        when(dialect.getDatabaseProduct())
            .thenReturn(Dialect.DatabaseProduct.MYSQL);

        SqlQuery query = mock(SqlQuery.class);
        when(query.getDialect()).thenReturn(dialect);

        RolapNativeSql sql = new RolapNativeSql(query, null, null, null, new HashMap<String, String>());
        compiler = sql.new NumberSqlCompiler();
    }

    @Override
    public void tearDown() throws Exception {
        compiler = null;
        super.tearDown();
    }

    public void testRejectsNonLiteral() {
        Exp exp = new DummyExp(new NullType());
        assertNull(compiler.compile(exp));
    }

    public void testAcceptsNumeric() {
        Exp exp = Literal.create(BigDecimal.ONE);
        assertNotNull(compiler.compile(exp));
    }

    public void testAcceptsString() {
        Exp exp = Literal.createString("1");
        assertNotNull(compiler.compile(exp));
    }

    public void testRejectsStrings_ThatCannotBeParsedAsDouble() {
        Exp exp = Literal.createString("(select 100)");
        try {
            compiler.compile(exp);
        } catch (MondrianEvaluationException e) {
            return;
        }
        fail("Expected to get MondrianEvaluationException");
    }
}

// End RolapNativeSql_NumberSqlCompiler_Test.java
