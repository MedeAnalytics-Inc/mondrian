/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2004-2005 TONBELLER AG
// Copyright (C) 2005-2005 Julian Hyde
// Copyright (C) 2005-2013 Pentaho and others
// All Rights Reserved.
*/
package mondrian.rolap;

import mondrian.mdx.MemberExpr;
import mondrian.olap.*;
import mondrian.rolap.aggmatcher.AggStar;
import mondrian.rolap.sql.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Computes Order() in SQL.
 *
 * Note that this utilizes the same approach as Native TopCount(), by defining an order by
 * clause based on the specified measure 
 *
 */
public class RolapNativeOrder extends RolapNativeSet {

    public RolapNativeOrder() {
        super.setEnabled(
            MondrianProperties.instance().EnableNativeOrder.get());
    }

    static class OrderConstraint extends SetConstraint {
        Exp orderByExpr;
        boolean ascending;
        Map<String, String> preEval;

        public OrderConstraint(
            CrossJoinArg[] args, RolapEvaluator evaluator,
            Exp orderByExpr, boolean ascending, Map<String, String> preEval)
        {
            super(args, evaluator, true);
            this.orderByExpr = orderByExpr;
            this.ascending = ascending;
            this.preEval = preEval;
        }

        /**
         * {@inheritDoc}
         *
         * <p>Order always needs to join the fact table because we want to
         * evaluate the order expression which involves a fact.
         */
        protected boolean isJoinRequired() {
            return true;
        }

        public void addConstraint(
            SqlQuery sqlQuery,
            RolapCube baseCube,
            AggStar aggStar)
        {
            if (orderByExpr != null) {
                RolapNativeSql sql =
                    new RolapNativeSql(
                        sqlQuery, aggStar, getEvaluator(), null, preEval);
                final String orderBySql =
                    sql.generateTopCountOrderBy(orderByExpr);
                boolean nullable =
                    deduceNullability(orderByExpr);
                final String orderByAlias =
                    sqlQuery.addSelect(orderBySql, null);
                sqlQuery.addOrderBy(
                    orderBySql,
                    orderByAlias,
                    ascending,
                    true,
                    nullable,
                    true);
            }
            super.addConstraint(sqlQuery, baseCube, aggStar);
        }

        private boolean deduceNullability(Exp expr) {
            if (!(expr instanceof MemberExpr)) {
                return true;
            }
            final MemberExpr memberExpr = (MemberExpr) expr;
            if (!(memberExpr.getMember() instanceof RolapStoredMeasure)) {
                return true;
            }
            final RolapStoredMeasure measure =
                (RolapStoredMeasure) memberExpr.getMember();
            return measure.getAggregator() != RolapAggregator.DistinctCount;
        }

        public Object getCacheKey() {
            List<Object> key = new ArrayList<Object>();
            key.add(super.getCacheKey());
            // Note: need to use string in order for caching to work
            if (orderByExpr != null) {
                key.add(orderByExpr.toString());
            }
            key.add(ascending);
            if (this.getEvaluator() instanceof RolapEvaluator) {
                key.add(
                    ((RolapEvaluator)this.getEvaluator())
                    .getSlicerMembers());
            }
            return key;
        }
    }

    protected boolean restrictMemberTypes() {
        return true;
    }

    NativeEvaluator createEvaluator(
        RolapEvaluator evaluator,
        FunDef fun,
        Exp[] args)
    {
        boolean ascending = true;

        if (!isEnabled()) {
            return null;
        }
        if (!OrderConstraint.isValidContext(
                evaluator, false, new Level[]{}, restrictMemberTypes()))
        {
            return null;
        }

        // is this "Order(<set>, [<numeric expr>, <string expr>], { ASC | DESC | BASC | BDESC })"
        String funName = fun.getName();
        if (!"Order".equalsIgnoreCase(funName)) {
            return null;
        }
        if (args.length < 2 || args.length > 3) {
            return null;
        }

        // extract the set expression
        List<CrossJoinArg[]> allArgs =
            crossJoinArgFactory().checkCrossJoinArg(evaluator, args[0]);

        // checkCrossJoinArg returns a list of CrossJoinArg arrays.  The first
        // array is the CrossJoin dimensions.  The second array, if any,
        // contains additional constraints on the dimensions. If either the list
        // or the first array is null, then native cross join is not feasible.
        if (allArgs == null || allArgs.isEmpty() || allArgs.get(0) == null) {
            return null;
        }

        CrossJoinArg[] cjArgs = allArgs.get(0);
        if (isPreferInterpreter(cjArgs, false)) {
            return null;
        }

        // Extract Order
        if (args.length == 3) {
            if (!(args[2] instanceof Literal)) {
                return null;
            }
            
            String val = ((Literal) args[2]).getValue().toString();
            if (val.equals( "ASC") || val.equals("BASC" )) {
                ascending = true;
            } else if (val.equals("DESC") || val.equals("BDESC")) {
                ascending = false;
            } else {
                return null;
            }
        }

        // extract "order by" expression
        SchemaReader schemaReader = evaluator.getSchemaReader();
        DataSource ds = schemaReader.getDataSource();

        // generate the ORDER BY Clause
        // Need to generate top count order by to determine whether
        // or not it can be created. The top count
        // could change to use an aggregate table later in evaulation
        SqlQuery sqlQuery = SqlQuery.newQuery(ds, "NativeOrder");
        RolapNativeSql sql =
            new RolapNativeSql(
                sqlQuery, null, evaluator, null, new HashMap<String, String>());
        Exp orderByExpr = null;
        if (args.length >= 2) {
            orderByExpr = args[1];
            String orderBySQL = sql.generateTopCountOrderBy(args[1]);
            if (orderBySQL == null) {
                return null;
            }
        }

        if (sql.addlContext.size() > 0 && sql.storedMeasureCount > 1) {
            // cannot natively evaluate, multiple tuples are possibly at play here.
            return null;
        }

        LOGGER.debug("using native order");
        final int savepoint = evaluator.savepoint();
        try {
            overrideContext(evaluator, cjArgs, sql.getStoredMeasure());
            evaluator.setInlineSubqueryNecessary(true);
            for (Member member : sql.addlContext) {
                evaluator.setContext(member);
            }
            CrossJoinArg[] predicateArgs = null;
            if (allArgs.size() == 2) {
                predicateArgs = allArgs.get(1);
            }

            CrossJoinArg[] combinedArgs;
            if (predicateArgs != null) {
                // Combined the CJ and the additional predicate args
                // to form the TupleConstraint.
                combinedArgs =
                        Util.appendArrays(cjArgs, predicateArgs);
            } else {
                combinedArgs = cjArgs;
            }
            TupleConstraint constraint =
                new OrderConstraint(
                    combinedArgs, evaluator, orderByExpr, ascending, sql.preEvalExprs);
            SetEvaluator sev =
                new SetEvaluator(cjArgs, schemaReader, constraint, sql.getStoredMeasure());
            return sev;
        } finally {
            evaluator.restore(savepoint);
        }
    }
}

// End RolapNativeOrder.java