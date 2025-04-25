package com.kolaps;

import fr.lip6.move.pnml.ptnet.hlapi.PageHLAPI;
import fr.lip6.move.pnml.ptnet.hlapi.PlaceHLAPI;
import soot.SootMethod;
import soot.Unit;

import java.util.Objects;
import java.util.Stack;

class TraversalState {
    final Unit currentUnit;
    final PlaceHLAPI placeBeforeUnit; // Место *перед* выполнением currentUnit
    final PageHLAPI currentPage;
    final Stack<PlaceHLAPI> returnStack;
    final SootMethod contectMethod;

    TraversalState(Unit currentUnit, PlaceHLAPI placeBeforeUnit, PageHLAPI currentPage, SootMethod myMethod) {
        this.currentUnit = currentUnit;
        this.placeBeforeUnit = placeBeforeUnit;
        this.currentPage = currentPage;
        this.contectMethod = myMethod;
        this.returnStack = null;

    }
    TraversalState(Unit currentUnit, PlaceHLAPI placeBeforeUnit, PageHLAPI currentPage, SootMethod myMethod, Stack<PlaceHLAPI> stack) {
        this.currentUnit = currentUnit;
        this.placeBeforeUnit = placeBeforeUnit;
        this.currentPage = currentPage;
        this.returnStack = (Stack<PlaceHLAPI>) stack.clone();
        this.contectMethod = myMethod;
    }

    // Важно реализовать equals и hashCode, если используется в Set/Map напрямую
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TraversalState that = (TraversalState) o;
        return Objects.equals(currentUnit, that.currentUnit) &&
                Objects.equals(placeBeforeUnit, that.placeBeforeUnit) && // Возможно, стоит сравнивать только Unit и Page?
                Objects.equals(currentPage, that.currentPage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(currentUnit, placeBeforeUnit, currentPage); // См. комментарий выше
    }
}
