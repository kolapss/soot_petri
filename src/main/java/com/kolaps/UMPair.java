package com.kolaps;

import soot.SootMethod;
import soot.Unit;

import java.util.Objects;

public class UMPair {

    private final Unit unit;
    private final SootMethod sootMethod;

    /**
     * Конструктор для создания пары Unit и SootMethod.
     *
     * @param unit       Объект Unit. Может быть null.
     * @param sootMethod Объект SootMethod. Может быть null.
     */
    public UMPair(Unit unit, SootMethod sootMethod) {
        this.unit = unit;
        this.sootMethod = sootMethod;
    }

    /**
     * Возвращает сохраненный объект Unit.
     *
     * @return объект Unit.
     */
    public Unit getUnit() {
        return unit;
    }

    /**
     * Возвращает сохраненный объект SootMethod.
     *
     * @return объект SootMethod.
     */
    public SootMethod getSootMethod() {
        return sootMethod;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UMPair that = (UMPair) o;
        return Objects.equals(unit, that.unit) &&
                Objects.equals(sootMethod, that.sootMethod);
    }

    @Override
    public int hashCode() {
        return Objects.hash(unit, sootMethod);
    }

    @Override
    public String toString() {
        return "UnitSootMethodPair{" +
                "unit=" + (unit != null ? unit.toString() : "null") +
                ", sootMethod=" + (sootMethod != null ? sootMethod.getSignature() : "null") +
                '}';
    }
}
