package com.kolaps.analyses;

import boomerang.ForwardQuery;
import boomerang.results.AbstractBoomerangResults;
import boomerang.scene.jimple.BoomerangPretransformer;
import boomerang.scene.sparse.SparseCFGCache;
import boomerang.util.AccessPath;
import javafx.util.Pair;
import soot.*;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JInstanceFieldRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class AliasingAnalysis {

    Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> aliases = null;

    protected boolean FalsePositiveInDefaultBoomerang;

    public Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> runAnalyses(String queryLHS, String targetClass, String targetMethod) {

        return getAliases(
                targetClass, queryLHS, targetMethod, SparseCFGCache.SparsificationStrategy.NONE, true);

    }

    public Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> getAliases(
            SootMethod method,
            String queryLHS,
            SparseCFGCache.SparsificationStrategy sparsificationStrategy,
            boolean ignoreAfterQuery) {
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
                SparseAliasManager sparseAliasManager =
                        SparseAliasManager.getInstance(sparsificationStrategy, ignoreAfterQuery);
                return sparseAliasManager.getAliases(stmt, method, leftOp);
            }
        }
        throw new RuntimeException(
                "Query Variable not found. Does variable:"
                        + queryLHS
                        + " exist in the method:"
                        + method.getName());
    }

    protected Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> getAliases(
            String targetClass,
            String queryLHS,
            String targetMethod,
            SparseCFGCache.SparsificationStrategy sparsificationStrategy,
            boolean ignoreAfterQuery) {
        Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> aliases =
                executeStaticAnalysis(
                        targetClass, targetMethod, queryLHS, sparsificationStrategy, ignoreAfterQuery);
        return aliases;
    }

    public Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> executeStaticAnalysis(
            String targetClassName,
            String targetMethod,
            String queryLHS,
            SparseCFGCache.SparsificationStrategy sparsificationStrategy,
            boolean ignoreAfterQuery) {
        registerSootTransformers(queryLHS, sparsificationStrategy, targetMethod, ignoreAfterQuery);
        executeSootTransformers();
        return aliases;
    }

    protected void registerSootTransformers(
            String queryLHS,
            SparseCFGCache.SparsificationStrategy sparsificationStrategy,
            String targetMethod,
            boolean ignoreAfterQuery) {
        Transform transform =
                new Transform(
                        "wjtp.ifds",
                        createAnalysisTransformer(
                                queryLHS, sparsificationStrategy, targetMethod, ignoreAfterQuery));
        try {
            PackManager.v().getPack("wjtp").add(transform);
        } catch (Exception e) {
            PackManager.v().getPack("wjtp").remove("wjtp.ifds");
            PackManager.v().getPack("wjtp").add(transform);
        }

    }

    protected void executeSootTransformers() {
        // Apply all necessary packs of soot. This will execute the respective Transformer
        PackManager.v().getPack("cg").apply();
        // Must have for Boomerang
        BoomerangPretransformer.v().reset();
        BoomerangPretransformer.v().apply();
        PackManager.v().getPack("wjtp").apply();
    }

    protected Transformer createAnalysisTransformer(
            String queryLHS,
            SparseCFGCache.SparsificationStrategy sparsificationStrategy,
            String targetMethod,
            boolean ignoreAfterQuery) {
        return new SceneTransformer() {
            @Override
            protected void internalTransform(String phaseName, Map<String, String> options) {
                aliases =
                        getAliases(
                                getEntryPointMethod(targetMethod),
                                queryLHS,
                                sparsificationStrategy,
                                ignoreAfterQuery);
            }
        };
    }

    protected SootMethod getEntryPointMethod(String targetMethod) {
        for (SootClass c : Scene.v().getApplicationClasses()) {
            for (SootMethod m : c.getMethods()) {
                if (!m.hasActiveBody()) {
                    continue;
                }
                if (targetMethod != null && m.getName().equals(targetMethod)) {
                    return m;
                }
                if (m.getName().equals("entryPoint")
                        || m.toString().contains("void main(java.lang.String[])")) {
                    return m;
                }
            }
        }
        throw new IllegalArgumentException("Method does not exist in scene!");
    }
}
