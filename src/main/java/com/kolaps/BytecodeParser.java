package com.kolaps;

import boomerang.BackwardQuery;
import boomerang.Boomerang;
import boomerang.Query;

import boomerang.results.BackwardBoomerangResults;


import boomerang.scene.*;
import boomerang.scene.jimple.BoomerangPretransformer;
import boomerang.scene.jimple.SootCallGraph;
import com.kolaps.utils.RetroLambda;
import soot.*;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.spark.SparkTransformer;
import soot.options.Options;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.UnitGraph;
import wpds.impl.Weight;


import java.io.IOException;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class BytecodeParser {

    private static String path = null;

    private static String getMainClass(String jarFilePath) throws IOException {
        try (JarFile jarFile = new JarFile(jarFilePath)) {
            Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                Attributes attributes = manifest.getMainAttributes();
                return attributes.getValue("Main-Class");
            }
        }
        return null;
    }

    public static void setPath(String path) {path = path;}

    public static void parseProgram(String path) {
        path = path;
        RetroLambda.run(path);
        SootClass mainClass;
        try {
            setupSoot(path, getMainClass(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            mainClass = Scene.v().loadClassAndSupport(getMainClass(path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        mainClass.getMethods();
        SootMethod mainMethod = mainClass.getMethodByName("main");
        System.out.println(mainMethod.retrieveActiveBody());
        UnitGraph graph = new ExceptionalUnitGraph(mainMethod.retrieveActiveBody());
        /*SparkTransformer.v().transform();
        PointsToAnalysis pta = Scene.v().getPointsToAnalysis();
        PointsToSet pta1=null;
        PointsToSet pta2=null;*/
        /*for (Unit u : graph) {
            if (u instanceof JAssignStmt) {
                JAssignStmt assign = (JAssignStmt) u;
                if (assign.getLeftOp().toString().equals("a")) {
                    pta1 = pta.reachingObjects((Local) assign.getLeftOp());
                }
                if (assign.getLeftOp().toString().equals("b")) {
                    pta2 = pta.reachingObjects((Local) assign.getLeftOp());
                }
                System.out.println(u);
            }
            System.out.println(u);
        }
        if (pta1.hasNonEmptyIntersection(pta2)) {
            System.out.println(pta1);
        }*/
        analyze(mainClass, mainMethod);


    }


    private static void setupSoot(String sootClassPath, String mainClass) {
        G.v().reset();
        Options.v().set_whole_program(true);
        Options.v().setPhaseOption("cg.spark", "on");
        Options.v().set_output_format(Options.output_format_none);
        Options.v().set_no_bodies_for_excluded(true);
        Options.v().set_allow_phantom_refs(true);

        List<String> includeList = new LinkedList<String>();
        includeList.add("java.lang.*");
        includeList.add("java.util.*");
        includeList.add("java.io.*");
        includeList.add("sun.misc.*");
        includeList.add("java.net.*");
        includeList.add("javax.servlet.*");
        includeList.add("javax.crypto.*");

        Options.v().set_include(includeList);
        Options.v().setPhaseOption("jb", "use-original-names:true");

        Options.v().set_soot_classpath(sootClassPath);
        Options.v().set_prepend_classpath(true);
        // Options.v().set_main_class(this.getTargetClass());
        Scene.v().loadNecessaryClasses();
        SootClass c = Scene.v().forceResolve(mainClass, SootClass.BODIES);
        if (c != null) {
            c.setApplicationClass();
        }
        for (SootMethod m : c.getMethods()) {
            System.out.println(m);
        }
    }


    private static void analyze(SootClass mainClass, SootMethod mainMethod) {
        Transform transform = new Transform("wjtp.ifds", createAnalysisTransformer(mainClass, mainMethod));
        PackManager.v().getPack("wjtp").add(transform);
        PackManager.v().getPack("cg").apply();
        BoomerangPretransformer.v().apply();
        PackManager.v().getPack("wjtp").apply();
    }

    private static Transformer createAnalysisTransformer(SootClass mainClass, SootMethod mainMethod) {
        return new SceneTransformer() {
            protected void internalTransform(String phaseName, @SuppressWarnings("rawtypes") Map options) {
                SootCallGraph sootCallGraph = new SootCallGraph();
                AnalysisScope scope = new AnalysisScope(sootCallGraph) {

                    @Override
                    protected Collection<? extends boomerang.Query> generate(ControlFlowGraph.Edge seed) {
                        Statement statement = seed.getTarget();

                        if (statement.toString().contains("queryFor") && statement.containsInvokeExpr()) {
                            Val arg = statement.getInvokeExpr().getArg(0);
                            return Collections.singleton(BackwardQuery.make(seed, arg));
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
                // 1. Create a Boomerang solver.


                Boomerang solver = new Boomerang(sootCallGraph,dat);

                // 2. Submit a query to the solver.
                Collection<boomerang.Query> seeds = scope.computeSeeds();
                for (Query query : seeds) {
                    System.out.println("Solving query: " + query);
                    BackwardBoomerangResults<Weight.NoWeight> backwardQueryResults = solver.solve((BackwardQuery) query);
                    System.out.println("All allocation sites of the query variable are:");
                    System.out.println(backwardQueryResults.getAllocationSites());

                    System.out.println("All aliasing access path of the query variable are:");
                    System.out.println(backwardQueryResults.getAllAliases());
                }
            }
        }

                ;
    }
}
