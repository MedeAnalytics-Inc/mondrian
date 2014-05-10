/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2014-2014 Pentaho and others
// All Rights Reserved.
*/
package mondrian.rolap;

import mondrian.mdx.MemberExpr;
import mondrian.mdx.ResolvedFunCall;
import mondrian.olap.Exp;
import mondrian.olap.FunDef;
import mondrian.olap.Level;
import mondrian.olap.Member;
import mondrian.olap.MondrianProperties;
import mondrian.olap.NativeEvaluator;
import mondrian.olap.SchemaReader;
import mondrian.olap.Util;
import mondrian.olap.fun.CrossJoinFunDef;
import mondrian.olap.fun.FunDefBase;
import mondrian.olap.fun.NonEmptyCrossJoinFunDef;
import mondrian.rolap.RolapNativeFilter.FilterConstraint;
import mondrian.rolap.sql.CrossJoinArg;
import mondrian.rolap.sql.DescendantsCrossJoinArg;
import mondrian.rolap.sql.MemberListCrossJoinArg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RolapNativeNonEmptyFunction extends RolapNativeSet {

    public RolapNativeNonEmptyFunction() {
        super.setEnabled(
            MondrianProperties.instance().EnableNativeNonEmptyFunction.get());
    }

    protected boolean restrictMemberTypes() {
        // can't really handle calculated measures
        return true;
    }

    NativeEvaluator createEvaluator(
        RolapEvaluator evaluator,
        FunDef fun,
        Exp[] args)
    {
        if (!isEnabled()) {
            return null;
        }
        
        if (!FilterConstraint.isValidContext(
                evaluator, false, new Level[]{}, restrictMemberTypes()))
        {
            return null;
        }

        // Native Args Validation
        // the first set
        // 0 - arguments, 1 - additional constraints
        List<CrossJoinArg[]> mainArgs =
            crossJoinArgFactory().checkCrossJoinArg(evaluator, args[0]);
        if (failedCjArg(mainArgs)) {
            alertNonNative(evaluator, fun, args[0]);
            return null;
        }
        // we want the second arg to be added just as a crossjoin constraint
        boolean hasTwoArgs = args.length == 2;
        if (hasTwoArgs) {
            // this check verifies there isn't anything that would cause
            // overall native eval to fail in both arguments
            FunDef nonEmptyCrossJoin =
                new NonEmptyCrossJoinFunDef(
                    new FunDefBase("NonEmptyCrossJoin", null, "fxxx") {}
                );
            List<CrossJoinArg[]> allArgs = crossJoinArgFactory().checkCrossJoin(evaluator, nonEmptyCrossJoin, args, true);
            if (failedCjArg(allArgs)) {
                alertNonNative(evaluator, fun, args[1]);
                return null;
            }
        }

        List<CrossJoinArg[]> extraArgs =
            hasTwoArgs
                ? crossJoinArgFactory().checkCrossJoinArg(evaluator, args[1], true, false)
                : null;

        RolapStoredMeasure measure = null;
        if (hasTwoArgs) {

          // investigate all measures in second param,
          // if they are all regular measures from the same base cube
          // select the final one for non-empty evaluation

          Set<RolapCube> baseCubes = new HashSet<RolapCube>();
          Set<Member> measures = new HashSet<Member>();
          List<RolapCube> baseCubeList = new ArrayList<RolapCube>();
          findMeasures(args[1], baseCubes, baseCubeList, measures);
          boolean calculatedMeasures = false;

          for (Member m : measures) {
              if (m instanceof RolapStoredMeasure) {
                measure = (RolapStoredMeasure)m;
              }
              if (m.isCalculated()) {
                calculatedMeasures = true;
              }
          }

          if (baseCubeList.size() > 1 || calculatedMeasures) {
              // unable to perform
              alertNonNative(evaluator, fun, args[1]);
              return null;
          }
        }

        if (hasTwoArgs && extraArgs == null) {
          // set to false if there are no extra args
          // so that the two arg lists can be merged
          hasTwoArgs = false;
        }

        // what should end up in the select
        final CrossJoinArg[] returnArgs = mainArgs.get(0);
        // what will be in the constraint
        final CrossJoinArg[] constraintArgs =
            getConstraintArgs(mainArgs, extraArgs);
        // crossjoin members that will override context
        final CrossJoinArg[] cjArgs =
            hasTwoArgs
                ? Util.appendArrays(returnArgs, extraArgs.get(0))
                : returnArgs;

        SchemaReader schemaReader = evaluator.getSchemaReader();

        final int savepoint = evaluator.savepoint();
        try {
            overrideContext(evaluator, cjArgs, measure);
            NonEmptyFunctionConstraint constraint =
                new NonEmptyFunctionConstraint(
                    constraintArgs, evaluator, restrictMemberTypes());

            SetEvaluator nativeEvaluator =
                new SetEvaluator(returnArgs, schemaReader, constraint);
            RolapUtil.SQL_LOGGER.debug("NonEmpty() going native");
            return nativeEvaluator;
        } finally {
            evaluator.restore(savepoint);
        }
    }

    private static boolean failedCjArg(List<CrossJoinArg[]> args) {
        return args == null || args.isEmpty() || args.get(0) == null;
    }

    private CrossJoinArg[] getConstraintArgs(
        List<CrossJoinArg[]> firstSet, List<CrossJoinArg[]> secondSet)
    {
        //get everything into one array
        final CrossJoinArg[] empty = new CrossJoinArg[0];
        return Util.appendArrays(
            firstSet.get(0),
            (firstSet.size() > 1) ? firstSet.get(1) : empty,
            (secondSet != null) ? secondSet.get(0) : empty,
            (secondSet != null && secondSet.size() > 1)
                ? secondSet.get(1)
                : empty);
    }

    private static void alertNonNative(
        RolapEvaluator evaluator,
        FunDef fun,
        Exp offendingArg)
    {
        if (!evaluator.getQuery().shouldAlertForNonNative(fun)) {
            return;
        }
        RolapUtil.alertNonNative(
            fun.getName(),
            "set argument " + offendingArg.toString());
    }

    /**
     * Extracts the stored measures referenced in an expression
     *
     * @param exp expression
     * @param baseCubes set of base cubes
     */
    private static void findMeasures(
        Exp exp,
        Set<RolapCube> baseCubes,
        List<RolapCube> baseCubeList,
        Set<Member> foundMeasures)
    {
        if (exp instanceof MemberExpr) {
            MemberExpr memberExpr = (MemberExpr) exp;
            Member member = memberExpr.getMember();
            if (member instanceof RolapStoredMeasure) {
                foundMeasures.add(member);
                addMeasure(
                    (RolapStoredMeasure) member, baseCubes, baseCubeList);
            } else if (member instanceof RolapCalculatedMember) {
                if (!foundMeasures.contains(member)) {
                  foundMeasures.add(member);
                  findMeasures(member.getExpression(), baseCubes, baseCubeList, foundMeasures);
                }
            }
        } else if (exp instanceof ResolvedFunCall) {
            ResolvedFunCall funCall = (ResolvedFunCall) exp;
            Exp [] args = funCall.getArgs();
            for (Exp arg : args) {
                findMeasures(arg, baseCubes, baseCubeList, foundMeasures);
            }
        }
    }

    /**
     * Adds information regarding a stored measure to maps
     *
     * @param measure the stored measure
     * @param baseCubes set of base cubes
     */
    private static void addMeasure(
        RolapStoredMeasure measure,
        Set<RolapCube> baseCubes,
        List<RolapCube> baseCubeList)
    {
        RolapCube baseCube = measure.getCube();
        if (baseCubes.add(baseCube)) {
            baseCubeList.add(baseCube);
        }
    }

    static class NonEmptyFunctionConstraint extends SetConstraint {

        NonEmptyFunctionConstraint(
            CrossJoinArg[] args, RolapEvaluator evaluator, boolean restrict)
        {
            super(args, evaluator, restrict);
        }

        @Override
        public void constrainExtraLevels(
            RolapCube baseCube,
            BitKey levelBitKey)
        {
            super.constrainExtraLevels(baseCube, levelBitKey);
            for (CrossJoinArg arg : args) {
                if (arg instanceof DescendantsCrossJoinArg
                    || arg instanceof MemberListCrossJoinArg)
                {
                    final RolapLevel level = arg.getLevel();
                    if (level != null && !level.isAll()) {
                        RolapStar.Column column =
                            ((RolapCubeLevel)level)
                                .getBaseStarKeyColumn(baseCube);
                        levelBitKey.set(column.getBitPosition());
                    }
                }
            }
        }

        @Override
        public Object getCacheKey() {
            List<Object> key = new ArrayList<Object>();
            //  // we're "special"
            //  key.add(this.getClass());
            key.add(super.getCacheKey());
            if (this.getEvaluator() instanceof RolapEvaluator) {
                key.add(
                    ((RolapEvaluator)this.getEvaluator())
                    .getSlicerMembers());
            }
            return key;
        }
    }
}
// End RolapNativeNonEmptyFunction.java