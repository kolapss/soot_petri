package com.kolaps;

import com.kolaps.utils.RetroLambda;
import soot.G;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.options.Options;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
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

    public static void parseProgram(String path, PetriNetBuilder builder) {
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
        PointerAnalysis.setupAnalyze();
        builder.build(mainClass);

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

}
