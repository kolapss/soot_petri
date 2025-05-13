package com.kolaps.analyses;

import boomerang.*;
import boomerang.results.AbstractBoomerangResults;
import boomerang.results.BackwardBoomerangResults;
import boomerang.scene.*;
import boomerang.scene.jimple.*;
import boomerang.scene.sparse.SparseCFGCache;
import boomerang.solver.BackwardBoomerangSolver;
import boomerang.util.AccessPath;
import boomerang.util.DefaultValueMap;
import javafx.util.Pair;
import soot.*;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.MonitorStmt;
import soot.jimple.Stmt;
import soot.jimple.internal.*;
import wpds.impl.Weight;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class BoomAnalysis {

    private static SootCallGraph sootCallGraph;
    private static DataFlowScope dat;
    public static Boomerang boomerangSolver;
    private static Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> pp;

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

    public static void setup() {
        PackManager.v().getPack("cg").apply();
        BoomerangPretransformer.v().reset();
        BoomerangPretransformer.v().apply();
        //PackManager.v().getPack("wjtp").apply();
        sootCallGraph = new SootCallGraph();
        dat = new DataFlowScope() {
            @Override
            public boolean isExcluded(DeclaredMethod method) {
                JimpleDeclaredMethod m = (JimpleDeclaredMethod)method;
                //return !((SootClass)m.getDeclaringClass().getDelegate()).isApplicationClass();
                return false;
            }

            @Override
            public boolean isExcluded(Method method) {
                JimpleMethod m = (JimpleMethod)method;
                //return !((SootClass)m.getDeclaringClass().getDelegate()).isApplicationClass();
                return false;
            }
        };
        boomerangSolver = new Boomerang(sootCallGraph, dat, new BoomerangOptions(SparseCFGCache.SparsificationStrategy.NONE, false));

    }

    protected static Transformer createAnalysisTransformer(
            BackwardQuery query) {
        return new SceneTransformer() {
            @Override
            protected void internalTransform(String phaseName, Map<String, String> options) {
                boomerangSolver = new Boomerang(sootCallGraph, dat, new BoomerangOptions(SparseCFGCache.SparsificationStrategy.NONE, false));
                BackwardBoomerangResults<Weight.NoWeight> results = boomerangSolver.solve(query);
                pp =  new Pair<>(results.getAllAliases(), results.getAllocationSites());
                System.out.println("results: " + results);
            }
        };
    }

    private static Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> getAliases(Stmt stmt, SootMethod method, Value value) {

        //Boomerang boomerangSolver = new Boomerang(sootCallGraph, SootDataFlowScope.make(Scene.v()),new BoomerangOptions(SparseCFGCache.SparsificationStrategy.NONE,false));
        BackwardQuery query = createQuery(stmt, method, value);
        Transform transform =
                new Transform(
                        "wjtp.ifds",
                        createAnalysisTransformer(query));
        PackManager.v().getPack("wjtp").add(transform);
        PackManager.v().getPack("wjtp").apply();
        PackManager.v().getPack("wjtp").remove("wjtp.ifds");
        /*boomerangSolver = new Boomerang(sootCallGraph, dat, new BoomerangOptions(SparseCFGCache.SparsificationStrategy.NONE, false));
        BackwardBoomerangResults<Weight.NoWeight> results = boomerangSolver.solve(query);
        Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> pp =  new Pair<>(results.getAllAliases(), results.getAllocationSites());
        return pp;*/
        return pp;
        //WeightedBoomerang<Weight> boomerang = new WeightedBoomerang<>(Scene.v().getCallGraph(), SootDataFlowScope.make(Scene.v()),new BoomerangOptions(SparseCFGCache.SparsificationStrategy.NONE,false));
    }

    private static BackwardQuery createQuery(Stmt stmt, SootMethod method, Value value) {
        JimpleMethod jimpleMethod = JimpleMethod.of(method);
        Statement statement = JimpleStatement.create(stmt, jimpleMethod);
        JimpleVal val = new JimpleVal(value, jimpleMethod);
        /*Optional<Statement> first =
                statement.getMethod().getControlFlowGraph().getSuccsOf(statement).stream().findFirst();*/
        Statement first = null, second=null;
        List<Statement> sts = statement.getMethod().getControlFlowGraph().getStatements();

        for (Statement st : sts) {
            if(st.toString().startsWith(value.toString())) {
                first = st;
                break;
            }
        }
        second = statement.getMethod().getControlFlowGraph().getSuccsOf(first).stream().findFirst().get();

            return BackwardQuery.make(new ControlFlowGraph.Edge(first, second), val);


    }

    public static Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> runAnalysis(
            SootMethod method,
            String queryLHS, Unit unit) {
        String[] split = queryLHS.split("\\.");
        /*Optional<Unit> unitOp;
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
        }*/


        if(unit instanceof JInvokeStmt)
        {
            InvokeExpr stmt = ((JInvokeStmt) unit).getInvokeExpr();
            Value leftOp=null;
            if(stmt instanceof JVirtualInvokeExpr)
            {
                leftOp = ((JVirtualInvokeExpr) stmt).getBase();
            }
            return getAliases((InvokeStmt) unit, method, leftOp);

        }
        if(unit instanceof MonitorStmt)
        {
            MonitorStmt stmt = (MonitorStmt) unit;
            Value leftOp = stmt.getOp();
            return getAliases(stmt, method, leftOp);
        }
        if (unit instanceof JAssignStmt) {
            JAssignStmt stmt = (JAssignStmt) unit;
            Value leftOp = stmt.getLeftOp();
            if (leftOp instanceof JInstanceFieldRef) {
                // get base
                leftOp = ((JInstanceFieldRef) leftOp).getBase();
            }
            return getAliases(stmt, method, leftOp);
        }
        if (unit instanceof JIdentityStmt) {
            JIdentityStmt stmt = (JIdentityStmt) unit;
            Value leftOp = stmt.getLeftOp();
            if (leftOp instanceof JInstanceFieldRef) {
                // get base
                leftOp = ((JInstanceFieldRef) leftOp).getBase();
            }
            return getAliases(stmt, method, leftOp);
        }

        throw new RuntimeException(
                "Query Variable not found. Does variable:"
                        + queryLHS
                        + " exist in the method:"
                        + method.getName());
    }

}
