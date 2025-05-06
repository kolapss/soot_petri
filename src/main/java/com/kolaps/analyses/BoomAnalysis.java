package com.kolaps.analyses;

import boomerang.*;
import boomerang.results.AbstractBoomerangResults;
import boomerang.results.BackwardBoomerangResults;
import boomerang.scene.ControlFlowGraph;
import boomerang.scene.SootDataFlowScope;
import boomerang.scene.Statement;
import boomerang.scene.jimple.*;
import boomerang.scene.sparse.SparseCFGCache;
import boomerang.solver.BackwardBoomerangSolver;
import boomerang.util.AccessPath;
import boomerang.util.DefaultValueMap;
import javafx.util.Pair;
import soot.*;
import soot.jimple.Stmt;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JIdentityStmt;
import soot.jimple.internal.JInstanceFieldRef;
import wpds.impl.Weight;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class BoomAnalysis {

    private static SootCallGraph sootCallGraph;
    public static  Boomerang boomerangSolver;

    static class BoomerangOptions extends DefaultBoomerangOptions {

        private SparseCFGCache.SparsificationStrategy sparsificationStrategy;
        private boolean ignoreAfterQuery;

        public BoomerangOptions(
                SparseCFGCache.SparsificationStrategy sparsificationStrategy, boolean ignoreAfterQuery) {
            this.sparsificationStrategy = sparsificationStrategy;
            this.ignoreAfterQuery = ignoreAfterQuery;
        }

        @Override
        public SparseCFGCache.SparsificationStrategy getSparsificationStrategy() {
            if (this.sparsificationStrategy == null) {
                return SparseCFGCache.SparsificationStrategy.NONE;
            }
            return this.sparsificationStrategy;
        }

        @Override
        public boolean ignoreSparsificationAfterQuery() {
            return this.ignoreAfterQuery;
        }

        @Override
        public int analysisTimeoutMS() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean onTheFlyCallGraph() {
            return false;
        }

        @Override
        public StaticFieldStrategy getStaticFieldStrategy() {
            return StaticFieldStrategy.FLOW_SENSITIVE;
        }

        @Override
        public boolean allowMultipleQueries() {
            return true;
        }

        @Override
        public boolean throwFlows() {
            return true;
        }

        @Override
        public boolean trackAnySubclassOfThrowable() {
            return true;
        }

        @Override
        public boolean trackStaticFieldAtEntryPointToClinit() {
            return true;
        }
    }

    public static void setup()
    {
        PackManager.v().getPack("cg").apply();
        BoomerangPretransformer.v().reset();
        BoomerangPretransformer.v().apply();
        sootCallGraph = new SootCallGraph();
        boomerangSolver = new Boomerang(sootCallGraph, SootDataFlowScope.make(Scene.v()),new BoomerangOptions(SparseCFGCache.SparsificationStrategy.NONE,false));

    }
    private static Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> getAliases(Stmt stmt, SootMethod method, Value value)
    {
        //Boomerang boomerangSolver = new Boomerang(sootCallGraph, SootDataFlowScope.make(Scene.v()),new BoomerangOptions(SparseCFGCache.SparsificationStrategy.NONE,false));
        BackwardQuery query = createQuery(stmt, method, value);
        BackwardBoomerangResults<Weight.NoWeight> results = boomerangSolver.solve(query);
       return new Pair<>(results.getAllAliases(),results.getAllocationSites());
        //WeightedBoomerang<Weight> boomerang = new WeightedBoomerang<>(Scene.v().getCallGraph(), SootDataFlowScope.make(Scene.v()),new BoomerangOptions(SparseCFGCache.SparsificationStrategy.NONE,false));
    }

    private static BackwardQuery createQuery(Stmt stmt, SootMethod method, Value value) {
        JimpleMethod jimpleMethod = JimpleMethod.of(method);
        Statement statement = JimpleStatement.create(stmt, jimpleMethod);
        JimpleVal val = new JimpleVal(value, jimpleMethod);
        Optional<Statement> first =
                statement.getMethod().getControlFlowGraph().getSuccsOf(statement).stream().findFirst();
        if (first.isPresent()) {
            return BackwardQuery.make(new ControlFlowGraph.Edge(statement, first.get()), val);
        }
        throw new RuntimeException("No successors for: " + statement);
    }

    public static Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> runAnalysis(
            SootMethod method,
            String queryLHS) {
        String[] split = queryLHS.split("\\.");
        Optional<Unit> unitOp;
        if (split.length > 1) {
            unitOp =
                    method.getActiveBody().getUnits().stream()
                            .filter(e -> e.toString().startsWith(split[0]) && e.toString().contains(split[1]))
                            .findFirst();
        } else {
            unitOp =
                    method.getActiveBody().getUnits().stream()
                            .filter(e -> e.toString().startsWith(split[0]))
                            .findFirst();
        }

        if (unitOp.isPresent()) {
            Unit unit = unitOp.get();
            if (unit instanceof JAssignStmt) {
                JAssignStmt stmt = (JAssignStmt) unit;
                Value leftOp = stmt.getLeftOp();
                if (leftOp instanceof JInstanceFieldRef) {
                    // get base
                    leftOp = ((JInstanceFieldRef) leftOp).getBase();
                }
                return getAliases(stmt, method, leftOp);
            }
            if(unit instanceof JIdentityStmt) {
                JIdentityStmt stmt = (JIdentityStmt) unit;
                Value leftOp = stmt.getLeftOp();
                if (leftOp instanceof JInstanceFieldRef) {
                    // get base
                    leftOp = ((JInstanceFieldRef) leftOp).getBase();
                }
                return getAliases(stmt, method, leftOp);
            }
        }
        throw new RuntimeException(
                "Query Variable not found. Does variable:"
                        + queryLHS
                        + " exist in the method:"
                        + method.getName());
    }

}
