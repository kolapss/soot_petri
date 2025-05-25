package com.kolaps.model;

import soot.SootMethod;
import soot.Unit;

/**
 * Класс для хранения информации о методе, связанном с лямбда-выражением.
 */
public class LambdaInfoEntry {
    private SootMethod runMethod;
    private SootMethod invokeMethod;
    private String lambdaVar;
    private Unit invokeStmt;

    public LambdaInfoEntry(SootMethod runMethod, SootMethod invokeMethod, String lambdaVar, Unit invokeStmt) {
        this.runMethod = runMethod;
        this.invokeMethod = invokeMethod;
        this.lambdaVar = lambdaVar;
        this.invokeStmt = invokeStmt;
    }

    public SootMethod getRunMethod() {
        return runMethod;
    }

    public SootMethod getInvokeMethod() {
        return invokeMethod;
    }

    public String getLambdaVar() {
        return lambdaVar;
    }

    public Unit getInvokeStmt() {
        return invokeStmt;
    }

    public void setRunMethod(SootMethod runMethod) {
        this.runMethod = runMethod;
    }

    public void setInvokeMethod(SootMethod invokeMethod) {
        this.invokeMethod = invokeMethod;
    }

    public void setLambdaVar(String lambdaVar) {
        this.lambdaVar = lambdaVar;
    }

    public void setInvokeStmt(Unit invokeStmt) {
        this.invokeStmt = invokeStmt;
    }

    @Override
    public String toString() {
        return "MethodInfoEntry{" +
                "runMethod=" + (runMethod != null ? runMethod.getSignature() : "null") +
                ", invokeMethod=" + (invokeMethod != null ? invokeMethod.getSignature() : "null") +
                ", lambdaVar='" + lambdaVar + '\'' +
                ", invokeStmt=" + (invokeStmt != null ? invokeStmt.toString() : "null") +
                '}';
    }
}