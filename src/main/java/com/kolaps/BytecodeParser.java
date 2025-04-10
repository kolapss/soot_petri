package com.kolaps;

import boomerang.BackwardQuery;
import boomerang.Boomerang;
import boomerang.Query;
import boomerang.options.BoomerangOptions;
import boomerang.results.BackwardBoomerangResults;
import boomerang.scope.*;
import boomerang.scope.soot.BoomerangPretransformer;
import boomerang.scope.soot.SootCallGraph;
import boomerang.scope.soot.SootFrameworkScope;
import soot.*;
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

    private String path = null;

    BytecodeParser(String path) {
        this.path = path;
        parseProgram();
    }

    private String getMainClass(String jarFilePath) throws IOException {
        try (JarFile jarFile = new JarFile(jarFilePath)) {
            Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                Attributes attributes = manifest.getMainAttributes();
                return attributes.getValue("Main-Class");
            }
        }
        return null;
    }

    public void parseProgram() {
        Options.v().set_prepend_classpath(true);
        Options.v().set_whole_program(true);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_process_dir(Collections.singletonList(this.path));
        Options.v().set_keep_line_number(true);
        Options.v().set_keep_offset(true);
        SootClass mainClass;
        try {
            setupSoot(this.path, getMainClass(this.path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            mainClass = Scene.v().loadClassAndSupport(getMainClass(this.path));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        mainClass.getMethods();
        SootMethod mainMethod = mainClass.getMethodByName("main");
        UnitGraph g = new ExceptionalUnitGraph(mainMethod.retrieveActiveBody());
        for (Unit u : g) {
            System.out.println(u);
        }
        analyze(mainClass, mainMethod);


    }

    private static void setupSoot(String sootClassPath, String mainClass) {
        G.v().reset();
        Options.v().set_whole_program(true);
        //Options.v().setPhaseOption("cg.spark", "on");
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
            protected void internalTransform(
                    String phaseName, @SuppressWarnings("rawtypes") Map options) {
                SootCallGraph sootCallGraph = new SootCallGraph(Scene.v().getCallGraph(), Collections.singletonList(mainMethod));
                AnalysisScope scope =
                        new AnalysisScope(sootCallGraph) {

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
                FrameworkScope scopeSoot = new SootFrameworkScope(Scene.v(), Scene.v().getCallGraph(), Collections.singletonList(mainMethod), dat);
                BoomerangOptions options1 = BoomerangOptions.builder()
                        .enableTrackDataFlowPath(true)
                        .enableTrackPathConditions(true)
                        .enableTypeCheck(true)
                        .enableTrackImplicitFlows(true)
                        .enableTrackStaticFieldAtEntryPointToClinit(true)
                        .enablePrunePathConditions(true)
                        //.withStaticFieldStrategy(Strategies.StaticFieldStrategy.FLOW_SENSITIVE)
                        .build();

                Boomerang solver =
                        new Boomerang(scopeSoot,options1);

                // 2. Submit a query to the solver.
                Collection<boomerang.Query> seeds = scope.computeSeeds();
                for (
                        Query query : seeds) {
                    System.out.println("Solving query: " + query);
                    BackwardBoomerangResults<Weight.NoWeight> backwardQueryResults =
                            solver.solve((BackwardQuery) query);
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
