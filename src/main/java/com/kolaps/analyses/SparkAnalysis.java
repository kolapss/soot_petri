package com.kolaps.analyses;

import soot.Local;
import soot.PointsToAnalysis;
import soot.PointsToSet;
import soot.Scene;

public class SparkAnalysis {

    public static boolean isAlias(Local l1, Local l2) {
        PointsToAnalysis pta = Scene.v().getPointsToAnalysis();
        PointsToSet pts1 = pta.reachingObjects(l1);
        PointsToSet pts2 = pta.reachingObjects(l2);
        return pts1.hasNonEmptyIntersection(pts2);
    }
}
