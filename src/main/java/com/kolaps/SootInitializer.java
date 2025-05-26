package com.kolaps;

import com.kolaps.analysis.BoomAnalysis;
import com.kolaps.utils.RetroLambda;
import soot.G;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.options.Options;

import java.io.IOException;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class SootInitializer {

    public static String mainClassName = null;

    public static String getMainClass(String jarFilePath) throws IOException {
        try (JarFile jarFile = new JarFile(jarFilePath)) {
            Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                Attributes attributes = manifest.getMainAttributes();
                return attributes.getValue("Main-Class");
            }
        }
        return null;
    }

    public static void setPath(String pathh) {
        com.kolaps.Options.INSTANCE.setOption("app.jar",pathh);
    }

    public static void parseProgram(String mypath, PetriNetBuilder builder) {
        com.kolaps.Options.INSTANCE.setOption("app.jar",mypath);
        try {
            mainClassName = getMainClass(com.kolaps.Options.INSTANCE.getStringOption("app.jar",""));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        RetroLambda.run(mypath);
        SootClass mainClass;

        setupSoot(com.kolaps.Options.INSTANCE.getStringOption("app.jar",""));

        mainClass = Scene.v().getMainClass();

        for(SootClass f : Scene.v().getApplicationClasses())
        {
            for(SootMethod m : f.getMethods())
            {
                m.retrieveActiveBody();
            }
        }

        //PackManager.v().writeOutput();
        mainClass.getMethods();
        SootMethod mainMethod = mainClass.getMethodByName("main");
        //System.out.println(mainMethod.retrieveActiveBody());

        BoomAnalysis.setup();

        builder.build(mainClass);

    }


    private static void setupSoot(String sootClassPath) {
        G.v().reset();
        Options.v().set_whole_program(true);
        Options.v().setPhaseOption("cg.spark", "on");
        Options.v().setPhaseOption("cg.spark","enabled:true");
        //Options.v().setPhaseOption("cg.spark", "cs-demand:true");
        Options.v().setPhaseOption("cg.spark","geom-pta:true");
        Options.v().setPhaseOption("cg.spark","simplify-offline:false");
        Options.v().setPhaseOption("cg.spark","geom-runs:1");
        Options.v().setPhaseOption("cg", "all-reachable:true");
        Options.v().set_output_format(Options.output_format_none);
        Options.v().set_no_bodies_for_excluded(true);
        Options.v().set_allow_phantom_refs(true);

        Options.v().setPhaseOption("jb", "use-original-names:true");
        Options.v().setPhaseOption("jb.sils", "enabled:false");

        Options.v().set_soot_classpath(sootClassPath);
        Options.v().set_prepend_classpath(true);
        Options.v().set_process_dir(Collections.singletonList(com.kolaps.Options.INSTANCE.getStringOption("app.jar","")));

        SootClass c = null;
        c = Scene.v().forceResolve(mainClassName, SootClass.BODIES);
        if (c != null) {
            c.setApplicationClass();
        }
        Scene.v().loadNecessaryClasses();


        for (SootMethod m : c.getMethods()) {
            System.out.println(m);
        }
    }

}
