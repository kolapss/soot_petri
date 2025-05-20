package com.kolaps.model;

import soot.SootMethod;
import soot.Unit;

/**
 * Класс для хранения информации о методе, связанном с лямбда-выражением.
 */
public class LambdaInfoEntry {
    private SootMethod runMethod;    // Метод, подобный run() в Runnable или call() в Callable
    private SootMethod invokeMethod; // Метод, который фактически вызывается лямбдой (например, статический синтетический метод)
    private String lambdaVar;        // Имя переменной, которой присвоено лямбда-выражение (если применимо)
    private Unit invokeStmt;         // Инструкция (statement) в байт-коде, где происходит вызов invokeMethod

    public LambdaInfoEntry(SootMethod runMethod, SootMethod invokeMethod, String lambdaVar, Unit invokeStmt) {
        this.runMethod = runMethod;
        this.invokeMethod = invokeMethod;
        this.lambdaVar = lambdaVar;
        this.invokeStmt = invokeStmt;
    }

    // Геттеры для доступа к полям
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

    // Сеттеры (если нужна возможность изменять поля после создания объекта)
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