package com.kolaps;

import boomerang.BackwardQuery;
import boomerang.Boomerang;
import boomerang.ForwardQuery;
import boomerang.Query;
import boomerang.results.AbstractBoomerangResults;
import boomerang.results.BackwardBoomerangResults;
import boomerang.scene.*;
import boomerang.scene.jimple.JimpleStatement;
import boomerang.scene.jimple.SootCallGraph;
import boomerang.util.AccessPath;
import soot.*;
import soot.jimple.internal.JInvokeStmt;
import soot.jimple.internal.JVirtualInvokeExpr;
import wpds.impl.Weight;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class PointerAnalysis {

    public static Val value;
    public static ControlFlowGraph.Edge lastEdge;
    public static Map<ForwardQuery, AbstractBoomerangResults.Context> allocSites;
    public static Set<AccessPath> allAliases;
    //public static Boomerang solver;

    public static void setupAnalyze() {
        /*PackManager.v().getPack("cg").apply();
        BoomerangPretransformer.v().apply();*/
    }


    public static Set<AccessPath> getAllocThreadStart(Unit unit, SootMethod method) {
        allocSites = null;
        allAliases = null;
        Transformer executor = new SceneTransformer() {
            protected void internalTransform(
                    String phaseName, @SuppressWarnings("rawtypes") Map options) {
                SootClass cl = method.getDeclaringClass();
                JInvokeStmt gg = (JInvokeStmt) unit;
                JVirtualInvokeExpr f = (JVirtualInvokeExpr) gg.getInvokeExpr();
                String var = f.getBase().toString() + " =";
                SootCallGraph sootCallGraph = new SootCallGraph();
                AnalysisScope scope = new AnalysisScope(sootCallGraph) {

                    @Override
                    protected Collection<? extends boomerang.Query> generate(ControlFlowGraph.Edge seed) {
                        JimpleStatement statement = (JimpleStatement) seed.getStart();

                        if (statement.getDelegate().toString().startsWith(var) && statement.getMethod().getDeclaringClass().getName().equals(cl.getName())) {
                            value = statement.getLeftOp();
                            lastEdge = seed;
                        }

                        if (statement.getDelegate().toString().equals(unit.toString()) && statement.getMethod().toString().equals(method.toString())) {
                            return Collections.singleton(BackwardQuery.make(lastEdge, value));
                        }


                        return Collections.emptyList();
                    }

                };

                DataFlowScope dat = new DataFlowScope() {
                    @Override
                    public boolean isExcluded(DeclaredMethod method) {
                        return false;
                    }

                    @Override
                    public boolean isExcluded(Method method) {
                        return false;
                    }
                };
                //MyBoomerangOptions boomerangOptions = new MyBoomerangOptions();
                // 1. Create a Boomerang solver.
                Boomerang solver = new Boomerang(sootCallGraph, dat, new MyBoomerangOptions());

                // 2. Submit a query to the solver.
                Collection<boomerang.Query> seeds = scope.computeSeeds();
                for (Query query : seeds) {
                    BackwardBoomerangResults<Weight.NoWeight> backwardQueryResults = solver.solve((BackwardQuery) query);
                    allocSites = backwardQueryResults.getAllocationSites();
                    allAliases = backwardQueryResults.getAllAliases();
                }

            }

        };
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.ifds", executor));
        PackManager.v().getPack("wjtp").apply();
        PackManager.v().getPack("wjtp").remove("wjtp.ifds");

        return allAliases;
    }

}
