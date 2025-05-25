package com.kolaps;

import boomerang.ForwardQuery;
import boomerang.results.AbstractBoomerangResults;
import boomerang.scene.AllocVal;
import boomerang.scene.jimple.JimpleMethod;
import boomerang.scene.jimple.JimpleVal;
import boomerang.util.AccessPath;
import com.kolaps.analyses.BoomAnalysis;
import com.kolaps.analyses.SparkAnalysis;
import com.kolaps.model.LambdaMethods;
import com.kolaps.model.LockPlaces;
import fr.lip6.move.pnml.framework.general.PnmlExport;
import fr.lip6.move.pnml.framework.utils.ModelRepository;
import fr.lip6.move.pnml.framework.utils.exception.*;
import fr.lip6.move.pnml.ptnet.hlapi.*;
import javafx.util.Pair;
import soot.*;
import soot.jimple.*;
import soot.jimple.internal.*;
import soot.jimple.toolkits.annotation.logic.Loop;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toolkits.graph.LoopNestTree;
import soot.toolkits.graph.UnitGraph;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.kolaps.PetriNetModeler.*;

class MyPlace {
    PlaceHLAPI value;

    MyPlace(PlaceHLAPI value) {
        this.value = value;
    }

    PlaceHLAPI getPlace() {
        return value;
    }

    void setPlace(PlaceHLAPI place) {
        value = place;
    }
}


public class PetriNetBuilder {
    private final PetriNetDocHLAPI document;
    private final PetriNetHLAPI rootPetriNet;

    public static PageHLAPI getMainPage() {
        return mainPage;
    }

    private static PageHLAPI mainPage;
    private static String strAllocValue;
    private LockPlaces lockPlaces;
    private Map<SootMethod, PlaceHLAPI> methodEntryPlaces;
    private Map<Pair<SootMethod, Unit>, PlaceHLAPI> unitExitPlaces;
    private Deque<PlaceHLAPI> returnPlaceStack; // Имитирует точки возврата стека вызовов
    private String packageName;
    private TransitionHLAPI endTransition;

    PetriNetBuilder() throws InvalidIDException, VoidRepositoryException, OtherException, ValidationFailedException,
            BadFileFormatException, IOException, OCLValidationFailed, UnhandledNetType {
        ModelRepository.getInstance().createDocumentWorkspace("void");
        this.document = new PetriNetDocHLAPI();
        this.rootPetriNet = new PetriNetHLAPI("RootNet", PNTypeHLAPI.COREMODEL, new NameHLAPI("DeadlockFind"),
                this.document);
        mainPage = null;


        this.lockPlaces = new LockPlaces();
        this.methodEntryPlaces = new HashMap<>();
        this.unitExitPlaces = new HashMap<>();
        this.returnPlaceStack = new ArrayDeque<>();

    }

    private PageHLAPI createPage(String baseName, PetriNetHLAPI container) {
        String id = "page_" + baseName.replaceAll("[^a-zA-Z0-9_]", "_") + "_";
        NameHLAPI name = new NameHLAPI(baseName);
        PageHLAPI page = null;
        try {
            page = new PageHLAPI(id, name, null, container);
        } catch (InvalidIDException | VoidRepositoryException e) {
            throw new RuntimeException(e);
        }
        //System.out.println("Созданная страница: " + name.getText() + " (ID: " + id + ")");
        return page;
    }

    public PetriNetHLAPI build(SootClass mainClass) {
        SootMethod mainMethod = mainClass.getMethodByName("main"); // Или getMethodByNameUnsafe
        if (mainMethod == null || !mainMethod.isStatic() || !mainMethod.isConcrete()) {
            throw new RuntimeException("Не удается найти main метод");
        }

        int dotIndex = mainClass.toString().indexOf('.');
        this.packageName = mainClass.toString().substring(0, dotIndex);

        // Создаем главную страницу для main метода
        mainPage = createPage("main_thread", rootPetriNet);

        // Создаем начальное место для main
        PlaceHLAPI startMainPlace = createPlace(escapeXml(mainMethod.getSignature()), mainPage, mainMethod.retrieveActiveBody().getUnits().getFirst(), mainMethod);
        endTransition = createTransition("end_transition", mainPage);
        PlaceHLAPI endPlace = createPlace("END", mainPage, mainMethod.retrieveActiveBody().getUnits().getLast(), mainMethod);
        // Устанавливаем начальную маркировку для точки входа
        new PTMarkingHLAPI(Long.valueOf(1), startMainPlace);
        //System.out.println("Установка начальной отметки 1 для места старта: " + startMainPlace.getId());
        PlaceHLAPI mainPlaceEnd;
        methodEntryPlaces.put(mainMethod, startMainPlace);
        // старт обхода
        try {
            mainPlaceEnd = traverseMethod(mainMethod, startMainPlace, new HashSet<>(), endPlace);
        } catch (Exception e) {
            System.err.println("Ошибка во время обхода: " + e.getMessage());
            e.printStackTrace();
            return null; // Indicate failure
        }
        createArc(this.endTransition, endPlace, mainPage);
        return this.rootPetriNet;
    }

    private void connectToReturn(PlaceHLAPI sourcePlace, String reason, SootMethod method) {
        TransitionHLAPI skipTransition = createTransition(reason + "_" + sourcePlace.getId().hashCode(), mainPage);
        createArc(sourcePlace, skipTransition, mainPage);
        if (!returnPlaceStack.isEmpty()) {
            PlaceHLAPI callerReturnPlace = returnPlaceStack.peek();
            createArc(skipTransition, callerReturnPlace, mainPage);
//            System.out.println("      Соединяем переход " + skipTransition.getId() + " с местом возврата "
//                    + callerReturnPlace.getId());
        } else {
            PlaceHLAPI endPlace = createPlace("end_" + reason, mainPage, method.retrieveActiveBody().getUnits().getLast(), method);
            createArc(skipTransition, endPlace, mainPage);
//            System.out.println(
//                    "      Соединяем переход " + skipTransition.getId() + " с последним местом " + endPlace.getId());
        }
    }

    private PlaceHLAPI traverseMethod(SootMethod method, PlaceHLAPI entryPlace, Set<SootMethod> visitedOnPath,
                                      PlaceHLAPI afterPlace) {
        // --- Base Cases and Checks ---
        if (!method.isConcrete()) {
            //System.out.println("Skipping non-concrete method: " + method.getSignature());
            connectToReturn(entryPlace, "Пропускаем ", method); // Connect entry to return flow
            return null;
        }
        // Optional: Skip library methods (can configure this)
        if (method.isJavaLibraryMethod() || method.isPhantom()) {
            //System.out.println("Пропуск библиотечного/фантомного метода: " + method.getSignature());
            connectToReturn(entryPlace, "библиотечный метод", method);
            return null;
        }
        if (visitedOnPath.contains(method)) {
            //System.out.println("Обнаружен цикл рекурсивного вызова, дальше не идём: " + method.getSignature());
            connectToReturn(entryPlace, "пропуск рекурсии", method);
            return null;
        }
        if (!method.hasActiveBody()) {
            try {
                method.retrieveActiveBody(); // Пробуем получить тело метода
                if (!method.hasActiveBody()) {
                    //System.out.println("Метод не имеет активного тела, пропускаем: " + method.getSignature());
                    connectToReturn(entryPlace, "нет тела", method);
                    return null;
                }
            } catch (Exception e) {
                //System.out.println("Не удалось извлечь тело метода " + method.getSignature() + ", пропускаем: " + e.getMessage());
                connectToReturn(entryPlace, "ошибка, нет тела", method);
                return null;
            }
        }

        //System.out.println("Обрабатываем метод: " + method.getSignature() + " [Entry Place: " + entryPlace.getId() + "]");
        visitedOnPath.add(method); // Отмечаем этот путь как посещенный

        // --- Инициализируем межпроцедурный Worklist---
        Body body = method.getActiveBody();
        BriefUnitGraph graph = new BriefUnitGraph(body); // Use BriefUnitGraph!

        Queue<Pair<Unit, PlaceHLAPI>> worklist = new ArrayDeque<>();
        Set<Unit> visitedUnits = new HashSet<>(); // Units processed in this method activation

        // Добавляем начальные юниты в worklist
        for (Unit head : graph.getHeads()) {
            if (!head.toString().contains("caughtexception")) {
                worklist.offer(new Pair<>(head, entryPlace));
                visitedUnits.add(head);
            }
        }
        MyPlace endPlaceMethod = new MyPlace(entryPlace);
        endPlaceMethod.setPlace(null);

        // --- Цикл обработки worklist ---
        while (!worklist.isEmpty()) {
            Pair<Unit, PlaceHLAPI> currentPair = worklist.poll();
            Unit currentUnit = currentPair.getKey();
            PlaceHLAPI currentUnitEntryPlace = currentPair.getValue(); // Место перед текущим юнитом

            //System.out.println("  Обработка юнита: " + formatUnit(currentUnit) + " [Место входа: "+ currentUnitEntryPlace.getId() + "]");


            // --- Обрабатываем различные Jimple инструкции ---
            if (currentUnit instanceof EnterMonitorStmt) {
                processEnterMonitor(currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist,
                        visitedUnits, method, endPlaceMethod);
            } else if (currentUnit instanceof ExitMonitorStmt) {
                processExitMonitor(currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist,
                        visitedUnits, method, endPlaceMethod);
            } else if (currentUnit instanceof InvokeStmt) {
                processInvoke(currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist, visitedUnits, method,
                        visitedOnPath, endPlaceMethod);
            } else if (currentUnit instanceof IfStmt) {
                processIf((IfStmt) currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, visitedOnPath);
            } else if (currentUnit instanceof JGotoStmt) {
                processGoto((JGotoStmt) currentUnit, currentUnitEntryPlace, worklist, visitedUnits);
            } else if (currentUnit instanceof LookupSwitchStmt || currentUnit instanceof TableSwitchStmt) {
                processSwitch((SwitchStmt) currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, visitedOnPath);
            } else if (currentUnit instanceof ReturnStmt || currentUnit instanceof ReturnVoidStmt) {
                processReturn(endPlaceMethod);
            } else {
                // Путь по умолчанию, просто проходим мимо, не обрабатываем
                processDefault(currentUnit, currentUnitEntryPlace, graph, worklist, visitedUnits, method, endPlaceMethod);
            }
        } // Конец обработки цикла worklist

        visitedOnPath.remove(method); // Удаляем отметку при возврате из метода
        //System.out.println("Метод полностью обработан: " + method.getSignature());
        return endPlaceMethod.getPlace();
    }

    private void processGoto(JGotoStmt stmt, PlaceHLAPI currentPlace, Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits) {


        Unit unit = stmt.getTarget();

        if (unit.toString().contains("caughtexception")) {
            return;
        }
        if (unit == null || currentPlace == null) {
            System.err.println("!!! Попытка добавить пустой юнит в worklist !!!");
            return;
        }

        if (visitedUnits.add(unit)) { // возвращает true, если элемент еще не был в наборе
            worklist.offer(new Pair<>(stmt.getTarget(), currentPlace));
            //System.out.println("      Добавлен в worklist: [" + currentPlace.getId() + "] -> ");
        }
    }

    private void processReturn(MyPlace endPlaceMethod) {

        if (!returnPlaceStack.isEmpty()) {
            PlaceHLAPI callerReturnPlace = returnPlaceStack.peek();
            //System.out.println("    Return -> Connects to caller return place: " + callerReturnPlace.getId());
        } else {
            // Возврат из метода «main» или метода «run» потока
            //System.out.println("    Return from top-level method (main or run).");
            if (endPlaceMethod.getPlace() != null) {
                createArc(endPlaceMethod.getPlace(), this.endTransition, mainPage);
            }
        }
        // Return завершает текущий метод, не добавляем наследников здесь
    }

    private void processSwitch(SwitchStmt stmt, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph, Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod, Set<SootMethod> visitedOnPath) {

        JTableSwitchStmt table = (JTableSwitchStmt) stmt;
        Unit cUnit = table.getTarget(0);
        Unit endSwitchUnit = null;
        Iterator<Unit> it = graph.iterator();
        while (true) {
            cUnit = it.next();
            if (cUnit instanceof JGotoStmt) {
                break;
            }
        }
        endSwitchUnit = cUnit;
        List<Unit> targetBoxes = table.getTargets();
        //targetBoxes.add(table.getDefaultTargetBox());
        PlaceHLAPI entrySwitchPlace = createPlace(escapeXml("entrySwitch_"), mainPage, stmt, method);
        PlaceHLAPI endSwitchPlace = createPlace("endSwitch_", mainPage, stmt, method);
        for (Unit box : targetBoxes) {
            Queue<Pair<Unit, PlaceHLAPI>> ifWorklist = new ArrayDeque<>();
            Pair<Unit, PlaceHLAPI> key = new Pair<>(box, entrySwitchPlace);
            ifWorklist.add(key);
            PlaceHLAPI endBrunchPlace = processBrunch(method, entrySwitchPlace, visitedOnPath, afterPlace, ifWorklist, endSwitchUnit);
            if (endBrunchPlace != null) {
                TransitionHLAPI endSwitchTransition = createTransition("endSwitch_" + endBrunchPlace.getId(), mainPage);
                createArc(endBrunchPlace, endSwitchTransition, mainPage);
                createArc(endSwitchTransition, endSwitchPlace, mainPage);
                endPlaceMethod.setPlace(endSwitchPlace);
            }

        }

        List<ArcHLAPI> outArcs = entrySwitchPlace.getOutArcsHLAPI();
        if (outArcs.isEmpty()) {  //Если внутри switch не были созданы места
            mainPage.removeObjectsHLAPI(entrySwitchPlace);
            mainPage.removeObjectsHLAPI(endSwitchPlace);
            handleSuccessors(endSwitchUnit, currentPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, true);
        } else {
            TransitionHLAPI t = createTransition("beforeSwitch_", mainPage);
            createArc(currentPlace, t, mainPage);
            createArc(t, entrySwitchPlace, mainPage);
            handleSuccessors(endSwitchUnit, endSwitchPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, true);
        }

    }

    private PlaceHLAPI processBrunch(SootMethod method, PlaceHLAPI entryPlace, Set<SootMethod> visitedOnPath,
                                     PlaceHLAPI afterPlace, Queue<Pair<Unit, PlaceHLAPI>> worklist, Unit gotounit) {


        // --- Инициализация worklist ---
        Body body = method.getActiveBody();
        BriefUnitGraph graph = new BriefUnitGraph(body);

        Set<Unit> visitedUnits = new HashSet<>(); // Units processed in this method activation

        MyPlace endPlaceMethod = new MyPlace(entryPlace);
        endPlaceMethod.setPlace(null);

        // --- Цикл worklist ---
        while (!worklist.isEmpty()) {
            Pair<Unit, PlaceHLAPI> currentPair = worklist.poll();
            Unit currentUnit = currentPair.getKey();
            PlaceHLAPI currentUnitEntryPlace = currentPair.getValue(); // Place BEFORE executing currentUnit

            //System.out.println("  Обработка юнита: " + formatUnit(currentUnit) + " [Место входа: "+ currentUnitEntryPlace.getId() + "]");

            // --- Обработка различных Jimple инструкций ---
            if (currentUnit.toString().equals(gotounit.toString())) { //Если достигли конца ветки, то выходим
                worklist.clear();
                break;
            } else if (currentUnit instanceof EnterMonitorStmt) {
                processEnterMonitor(currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist,
                        visitedUnits, method, endPlaceMethod);
            } else if (currentUnit instanceof ExitMonitorStmt) {
                processExitMonitor(currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist,
                        visitedUnits, method, endPlaceMethod);
            } else if (currentUnit instanceof InvokeStmt) {
                processInvoke(currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist, visitedUnits, method,
                        visitedOnPath, endPlaceMethod);
            } else if (currentUnit instanceof IfStmt) {
                processIf((IfStmt) currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, visitedOnPath);
            } else if (currentUnit instanceof JGotoStmt) {
                processGoto((JGotoStmt) currentUnit, currentUnitEntryPlace, worklist, visitedUnits);
            } else if (currentUnit instanceof LookupSwitchStmt || currentUnit instanceof TableSwitchStmt) {
                processSwitch((SwitchStmt) currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, visitedOnPath);
            } else {
                // Путь по умолчанию, просто проходим мимо, не обрабатываем
                processDefault(currentUnit, currentUnitEntryPlace, graph, worklist, visitedUnits, method, endPlaceMethod);
            }
        } // Конец цикла обработки worklist

        //System.out.println("Обход метода завершен: " + method.getSignature());
        return endPlaceMethod.getPlace();
    }


    private void processIf(IfStmt stmt, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph, Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod, Set<SootMethod> visitedOnPath) {
        System.out.println("    Branch: " + stmt.getCondition());

        // «If» имеет два потенциальных преемника: true и false цели
        List<Unit> successors = graph.getSuccsOf(stmt);
        LoopNestTree loopNestTree = new LoopNestTree(method.retrieveActiveBody());

        Unit falseUnit = stmt.getTarget();
        Unit trueUnit = null;
        //Определяем начало true и false юнитов
        for (Unit successor : successors) {
            if (falseUnit != successor) {
                trueUnit = successor;
                break;
            }
        }
        Queue<Unit> trueList = new LinkedList<Unit>();
        trueList.add(trueUnit);
        Iterator<Unit> it = graph.iterator();
        Unit cUnit = it.next();
        //Ищем начало true блока в графе
        while (!cUnit.equals(trueUnit)) {
            cUnit = it.next();
        }
        //Заполняем true список юнитами
        while (true) {
            cUnit = it.next();
            if (cUnit instanceof JGotoStmt || cUnit.equals(falseUnit)) {
                break;
            }
            trueList.add(cUnit);
        }
        Queue<Pair<Unit, PlaceHLAPI>> ifWorklist = new ArrayDeque<>();
        Loop selectedCycle = null;
        //Определяем является ли if началом цикла
        for (Loop f : loopNestTree) {
            if (f.getBackJumpStmt().toString().equals(cUnit.toString())) {
                selectedCycle = f;
                break;
            }
        }
        if (selectedCycle == null) { // Если if не является частью цикла
            PlaceHLAPI entryIfPlace = createPlace(escapeXml("entry_" + stmt.toString()), mainPage, stmt, method);
            Pair<Unit, PlaceHLAPI> key = new Pair<>(trueUnit, entryIfPlace);
            ifWorklist.add(key);
            boolean isIfElse = !cUnit.equals(falseUnit);
            Unit endTrueBrunch = cUnit;
            Queue<Unit> falseList = new LinkedList<Unit>();

            if (isIfElse) {
                //If c else
                Unit endIfUnit = ((JGotoStmt) cUnit).getTarget();
                //Заполняем false список юнитами
                while (true) {
                    cUnit = it.next();
                    if (cUnit.equals(endIfUnit)) {
                        break;
                    }
                    falseList.add(cUnit);
                }
                //PlaceHLAPI endIF = processBrunch(method,entryIfPlace,visitedOnPath,afterPlace, worklist, endTrueBrunch);
            }

            //Если if без else
            PlaceHLAPI endIfFalsePlace;
            PlaceHLAPI endIfTruePlace = processBrunch(method, entryIfPlace, visitedOnPath, afterPlace, ifWorklist, endTrueBrunch);
            PlaceHLAPI endIfPlace;
            if (endIfTruePlace != null) {
                TransitionHLAPI brunchStartTransition = createTransition("startIf_" + entryIfPlace.getId(), mainPage);
                createArc(currentPlace, brunchStartTransition, mainPage);
                createArc(brunchStartTransition, entryIfPlace, mainPage);
                TransitionHLAPI endIfTransition = createTransition("endTrueIf_" + endIfTruePlace.getId(), mainPage);
                createArc(endIfTruePlace, endIfTransition, mainPage);
                endIfPlace = createPlace("endIf_" + entryIfPlace.getId(), mainPage, stmt, method);
                createArc(endIfTransition, endIfPlace, mainPage);
                if (falseList.isEmpty()) { //Если нет блока else, то просто соединяем entryIfPlace c endIfPlace
                    TransitionHLAPI elseTransition = createTransition("else_" + entryIfPlace.getId(), mainPage);
                    createArc(currentPlace, elseTransition, mainPage);
                    createArc(elseTransition, endIfPlace, mainPage);
                    endPlaceMethod.setPlace(endIfPlace);
                    handleSuccessors(endTrueBrunch, endIfPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, true);
                } else { //Обрабатываем ветку else
                    endIfFalsePlace = processBrunch(method, entryIfPlace, visitedOnPath, afterPlace, ifWorklist, endTrueBrunch);
                    if (endIfFalsePlace != null) {
                        TransitionHLAPI endFalseTransition = createTransition("endFalse_" + entryIfPlace.getId(), mainPage);
                        createArc(endIfFalsePlace, endFalseTransition, mainPage);
                        createArc(endFalseTransition, endIfPlace, mainPage);
                    } else {
                        TransitionHLAPI elseTransition = createTransition("else_" + entryIfPlace.getId(), mainPage);
                        createArc(currentPlace, elseTransition, mainPage);
                        createArc(elseTransition, endIfPlace, mainPage);
                    }
                    endPlaceMethod.setPlace(endIfPlace);
                    handleSuccessors(endTrueBrunch, endIfPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, true);
                }
            } else {
                mainPage.removeObjectsHLAPI(entryIfPlace);
                handleSuccessors(endTrueBrunch, currentPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, true);
            }
        } else {
            processCycle(stmt, trueUnit, cUnit, currentPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, visitedOnPath);
        }

    }

    private void processCycle(Unit unit, Unit startCycleUnit, Unit endLoopBrunch, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph, Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod, Set<SootMethod> visitedOnPath) {
        Queue<Pair<Unit, PlaceHLAPI>> ifWorklist = new ArrayDeque<>();
        //PlaceHLAPI entryLoopPlace = createPlace("enttyLoop_" + unit.toString(), this.mainPage);
        Pair<Unit, PlaceHLAPI> key = new Pair<>(startCycleUnit, currentPlace);
        ifWorklist.add(key);
        PlaceHLAPI endLoopPlace = processBrunch(method, currentPlace, visitedOnPath, afterPlace, ifWorklist, endLoopBrunch);
        if (endLoopPlace != null) {
            endPlaceMethod.setPlace(endLoopPlace);
            handleSuccessors(((JIfStmt) unit).getTarget(), endLoopPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, false);
        } else {
            handleSuccessors(((JIfStmt) unit).getTarget(), currentPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, false);
        }
    }

    private PlaceHLAPI getOrCreateUnitEntryPlace(Unit unit, SootMethod method) {

        Pair<SootMethod, Unit> key = new Pair<>(method, unit); // Reuse Pair for consistency
        return unitExitPlaces.computeIfAbsent(key, k -> createPlace("entry_" + method.getName() + "_ln" + unit.getJavaSourceStartLineNumber() + "_" + unit.hashCode(), mainPage, unit, method));

    }

    private void processInvoke(Unit unit, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph,
                               Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod currentMethod,
                               Set<SootMethod> visitedOnPath, MyPlace endPlaceMethod) {
        InvokeStmt stmt = (InvokeStmt) unit;
        InvokeExpr invokeExpr = stmt.getInvokeExpr();
        SootMethodRef methodRef = invokeExpr.getMethodRef();

        try {
            SootMethod targetMethod = invokeExpr.getMethod(); // Получаем целевой метод
            String signature = targetMethod.getSignature();
            String callDesc = signature.substring(1, signature.length() - 1); // Trim <>

            //System.out.println("    Вызов: " + callDesc);

            // --- Специальная обработка для параллелизма и механизмов синхронизации ---
            if (signature.equals("<java.lang.Thread: void start()>") && invokeExpr instanceof InstanceInvokeExpr) {
                processThreadStart(unit, (InstanceInvokeExpr) invokeExpr, currentPlace, afterPlace, graph, worklist,
                        visitedUnits, currentMethod, visitedOnPath, endPlaceMethod);
            } else if ((signature.equals("<java.lang.Object: void wait()>") ||
                    signature.equals("<java.lang.Object: void wait(long)>") ||
                    signature.equals("<java.lang.Object: void wait(long, int)>"))
                    && invokeExpr instanceof InstanceInvokeExpr) {
                processObjectWait(stmt, (InstanceInvokeExpr) invokeExpr, currentPlace, afterPlace, graph, worklist, visitedUnits, currentMethod, endPlaceMethod);
            } else if ((signature.equals("<java.lang.Object: void notify()>") ||
                    signature.equals("<java.lang.Object: void notifyAll()>"))
                    && invokeExpr instanceof InstanceInvokeExpr) {
                processObjectNotify(stmt, (InstanceInvokeExpr) invokeExpr, currentPlace, afterPlace, graph, worklist, visitedUnits, currentMethod, endPlaceMethod);
            }

            // --- Обработка обычного вызова метода ---
            else if (targetMethod.isConcrete() && isApplicationClass(targetMethod)) {

                processMethodCall(stmt, targetMethod, currentPlace, afterPlace, graph, worklist, visitedUnits,
                        currentMethod, visitedOnPath, endPlaceMethod);
            }
            // --- Обработка других вызовов (библиотечных, абстрактных и т.д) ---
            else {
                //System.out.println("      Skipping traversal into (treating as atomic step): " + callDesc);
                processDefault(stmt, currentPlace, graph, worklist, visitedUnits, currentMethod, endPlaceMethod);
            }

        } catch (Exception e) {
            System.err.println(
                    "Error resolving/processing method call: " + methodRef.getSignature() + " at " + formatUnit(stmt));
            e.printStackTrace();
            processDefault(stmt, currentPlace, graph, worklist, visitedUnits, currentMethod, endPlaceMethod);
        }
    }

    private void processObjectNotify(Unit unit, InstanceInvokeExpr invokeExpr, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph, Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod) {

        InvokeStmt stmt = (InvokeStmt) unit;
        Value monitor = invokeExpr.getBase();
        LockPlaces.PlaceTriple triple = getOrCreateLockPlace(monitor, stmt, method, LockPlaces.PlaceType.WAIT);
        Unit allocUnit = getVariableUnit(strAllocValue);
        PlaceHLAPI waitPlace = triple.getWait(allocUnit, method);
        PlaceHLAPI notifyPlace = triple.getNotify(allocUnit, method);
        triple.setVar((Local) monitor);

        String monitorId = monitor.toString();


        // --- Wait переход ---
        TransitionHLAPI notifyTransitionLeft = createTransition("notifyEntry_" + monitorId, mainPage);
        TransitionHLAPI notifyTransitionRight = createTransition("notifyEntry_" + monitorId, mainPage);

        PlaceHLAPI endNotifyPlace = createPlace("endNotify_" + monitorId, mainPage, unit, method);
        createArc(currentPlace, notifyTransitionRight, mainPage);
        createArc(currentPlace, notifyTransitionLeft, mainPage);
        createArc(notifyTransitionRight, endNotifyPlace, mainPage);
        createArc(notifyTransitionLeft, endNotifyPlace, mainPage);
        createArc(notifyTransitionLeft, notifyPlace, mainPage);
        createArc(waitPlace, notifyTransitionLeft, mainPage);
        //Добавляем ингибиторную дугу
        ArcHLAPI inhib = createArc(waitPlace, notifyTransitionRight, mainPage);
        PTExtension.addInhibitorArc(inhib.getId());
        endPlaceMethod.setPlace(endNotifyPlace);

        // Добавить преемника после wait в worklist (начиная с endNotifyPlace)
        handleSuccessors(unit, endNotifyPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, true);

    }

    private void processObjectWait(Unit unit, InstanceInvokeExpr invokeExpr, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph,
                                   Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod) {
        InvokeStmt stmt = (InvokeStmt) unit;
        Value monitor = invokeExpr.getBase();
        LockPlaces.PlaceTriple triple = getOrCreateLockPlace(monitor, stmt, method, LockPlaces.PlaceType.WAIT);
        Unit allocUnit = getVariableUnit(strAllocValue);
        PlaceHLAPI waitPlace = triple.getWait(allocUnit, method);
        PlaceHLAPI lockPlace = triple.getLock(allocUnit, method);
        PlaceHLAPI notifyPlace = triple.getNotify(allocUnit, method);
        triple.setVar((Local) monitor);
        String monitorId = monitor.toString();


        // --- Wait переход ---
        TransitionHLAPI waitTransition = createTransition("waitEntry_" + monitorId, mainPage);
        createArc(currentPlace, waitTransition, mainPage);
        createArc(waitTransition, lockPlace, mainPage);
        createArc(waitTransition, waitPlace, mainPage);

        PlaceHLAPI afterWaitPlace = createPlace("afterWait_" + monitorId, mainPage, unit, method);
        createArc(waitTransition, afterWaitPlace, mainPage);


        // --- Путь для повторного получения блокировки после notify ---
        TransitionHLAPI notifyTransition = createTransition("notify_" + monitorId, mainPage);
        createArc(afterWaitPlace, notifyTransition, mainPage);
        createArc(notifyPlace, notifyTransition, mainPage);

        PlaceHLAPI afterNotifyPlace = createPlace("afterNotify_" + monitorId, mainPage, unit, method);
        createArc(notifyTransition, afterNotifyPlace, mainPage);
        TransitionHLAPI endWaitTransition = createTransition("endWait_" + monitorId, mainPage);
        createArc(afterNotifyPlace, endWaitTransition, mainPage);
        createArc(lockPlace, endWaitTransition, mainPage);
        PlaceHLAPI placeAfterWait = createPlace("endWait_" + monitorId, mainPage, unit, method);
        createArc(endWaitTransition, placeAfterWait, mainPage);
        endPlaceMethod.setPlace(placeAfterWait);

        // Добавить преемника после wait в worklist (начиная с placeAfterWait)
        handleSuccessors(unit, placeAfterWait, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, true);

    }


    private void processMethodCall(InvokeStmt stmt, SootMethod targetMethod, PlaceHLAPI currentPlace,
                                   PlaceHLAPI afterPlace, UnitGraph graph, Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits,
                                   SootMethod currentMethod, Set<SootMethod> visitedOnPath, MyPlace endPlaceMethod) {

        // Поместить место возврата в стек перед рекурсивным вызовом
        returnPlaceStack.push(afterPlace);

        // --- Рекурсивный вызов ---
        PlaceHLAPI endInvokePlace = null;
        try {
            endInvokePlace = traverseMethod(targetMethod, currentPlace, visitedOnPath, afterPlace); // Recurse
            if (endInvokePlace != null) {
                endPlaceMethod.setPlace(endInvokePlace);
                //createArc(endInvokePlace, this.endTransition, this.mainPage);
            }
        } finally {
            // Извлечение из стека после возврата рекурсивного вызова
            if (!returnPlaceStack.isEmpty()) {
                PlaceHLAPI poppedPlace = returnPlaceStack.pop();
                if (poppedPlace != afterPlace) {
                    System.err.println("!!! Возврат места стека несоответствие! Ожидается, что " + afterPlace.getId() + ", соответствует "
                            + poppedPlace.getId() + " !!!");
                }
            } else {
                System.err.println("!!! Стек пустой после обхода " + targetMethod.getName() + " !!!");
            }
        }

        // --- Добавить наследника to Worklist ---
        if (endInvokePlace == null) {
            handleSuccessors(stmt, currentPlace, afterPlace, graph, worklist, visitedUnits, currentMethod, endPlaceMethod, true);
        } else {
            handleSuccessors(stmt, endInvokePlace, afterPlace, graph, worklist, visitedUnits, currentMethod, endPlaceMethod, true);
        }
    }

    private boolean isApplicationClass(SootMethod method) {
        return method.getDeclaringClass().getName().contains(this.packageName);
    }

    private void processThreadStart(Unit unit, InstanceInvokeExpr invokeExpr, PlaceHLAPI currentPlace,
                                    PlaceHLAPI afterPlace, UnitGraph graph, Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits,
                                    SootMethod currentMethod, Set<SootMethod> visitedOnPath, MyPlace endPlaceMethod) {
        InvokeStmt stmt = (InvokeStmt) unit;

        TransitionHLAPI startTransition = createTransition(escapeXml(invokeExpr.toString()), mainPage);
        createArc(currentPlace, startTransition, mainPage);

        // 1. Создаем места, если в потоке обнаружатся методы синхронизации
        PlaceHLAPI placeAfterStart = getOrCreateUnitExitPlace(stmt, currentMethod);
        endPlaceMethod.setPlace(placeAfterStart);
        createArc(startTransition, placeAfterStart, mainPage);
        handleSuccessors(stmt, placeAfterStart, afterPlace, graph, worklist, visitedUnits, currentMethod, endPlaceMethod, true);

        // 2. Найти и запустить обход нового потока в его run() методе
        SootMethod runMethod = newFindRunMethodForStartCall(unit, currentMethod);

        if (runMethod != null && runMethod.isConcrete()) {
            PlaceHLAPI runEntryPlace = methodEntryPlaces.computeIfAbsent(runMethod,
                    m -> createPlace(escapeXml("entry_" + m.toString()), mainPage, unit, currentMethod));

            createArc(startTransition, runEntryPlace, mainPage);

            //System.out.println("      Новый поток: " + runMethod.getSignature() + " [Место входа: " + runEntryPlace.getId() + "]");

            try {
                PlaceHLAPI endInvokePlace = traverseMethod(runMethod, runEntryPlace, new HashSet<>(visitedOnPath), afterPlace);
                /*if (endInvokePlace != null) {
                    createArc(endInvokePlace, this.endTransition, this.mainPage);
                }*/
            } catch (Exception e) {
                System.err.println("Error traversing run() method for " + runMethod.getSignature());
                e.printStackTrace();
            }
        }
    }


    private void processExitMonitor(Unit unit, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph,
                                    Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod) {
        ExitMonitorStmt stmt = (ExitMonitorStmt) unit;
        Value monitor = stmt.getOp();
        LockPlaces.PlaceTriple triple = getOrCreateLockPlace(monitor, unit, method, LockPlaces.PlaceType.LOCK);
        Unit allocUnit = getVariableUnit(strAllocValue);
        PlaceHLAPI lockPlace = triple.getLock(allocUnit, method);

        TransitionHLAPI exitTransition = createTransition(
                "exit_" + escapeXml(monitor.toString() + "_" + method.toString()), mainPage);
        createArc(currentPlace, exitTransition, mainPage);
        createArc(exitTransition, lockPlace, mainPage);
        PlaceHLAPI afterLock = createPlace("after_" + lockPlace.getId(), mainPage, unit, method);
        createArc(exitTransition, afterLock, mainPage);
        endPlaceMethod.setPlace(afterLock);


        // Добавить преемников в worklist после освобождения блокировки
        handleSuccessors(stmt, afterLock, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, true);
    }

    /**
     * Поиск аллокации переменной по имени и методы. Данные извлекаются из inputStr
     * @param inputStr
     * @return
     */
    public static Unit getVariableUnit(String inputStr) {
        Pattern mainPattern = Pattern.compile("^([^ (]+)\\s*\\((.+)\\)$");
        Matcher mainMatcher = mainPattern.matcher(inputStr);
        String variableName = null;
        String methodFullInfo;
        String sootMethodSignature = null;
        if (mainMatcher.find()) {
            variableName = mainMatcher.group(1);
            methodFullInfo = mainMatcher.group(2);

            Pattern sigPattern = Pattern.compile("(<.+>)");
            Matcher sigMatcher = sigPattern.matcher(methodFullInfo);
            if (sigMatcher.find()) {
                sootMethodSignature = sigMatcher.group(1);
                System.out.println(sootMethodSignature);

            } else {
                System.err.println("Не удалось извлечь Soot-совместимую сигнатуру из: " + methodFullInfo);
            }
        }

        SootMethod sootMethod = null;
        try {
            sootMethod = Scene.v().getMethod(sootMethodSignature);
        } catch (RuntimeException e) {
            System.err.println("Ошибка: не удалось найти метод с сигнатурой '" + sootMethodSignature + "': " + e.getMessage());
            //return Collections.emptyList();
        }

        if (!sootMethod.isConcrete()) {
            System.err.println("Метод " + sootMethodSignature + " не является конкретным (может быть abstract или native).");
            //return Collections.emptyList();
        }

        Body activeBody = null;
        try {
            activeBody = sootMethod.retrieveActiveBody();
        } catch (RuntimeException e) {
            System.err.println("Ошибка при получении тела метода " + sootMethodSignature + ": " + e.getMessage());
            e.printStackTrace(); // Добавим стек вызовов для отладки
            //return Collections.emptyList();
        }

        if (activeBody == null) {
            System.err.println("Тело метода для " + sootMethodSignature + " не найдено (null).");
            //return Collections.emptyList();
        }


        for (Unit unit : activeBody.getUnits()) {
            // 1. Присваивания через AssignStmt (например, x = y;)
            if (unit instanceof AssignStmt) {
                AssignStmt assignStmt = (AssignStmt) unit;
                Value leftOp = assignStmt.getLeftOp();
                if (leftOp instanceof Local) {
                    Local local = (Local) leftOp;
                    if (local.getName().equals(variableName)) {
                        return unit;
                    }
                }
            }
            // 2. Присваивания через IdentityStmt (например, x := @parameter0: type; или x := @this: type;)
            else if (unit instanceof IdentityStmt) {
                IdentityStmt idStmt = (IdentityStmt) unit;
                Value leftOp = idStmt.getLeftOp();
                if (leftOp instanceof Local) {
                    Local local = (Local) leftOp;
                    if (local.getName().equals(variableName)) {
                        return unit;
                    }
                }
            }
        }
        return null;
    }

    private void processEnterMonitor(Unit unit, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph,
                                     Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod) {
        EnterMonitorStmt stmt = (EnterMonitorStmt) unit;
        Value monitor = stmt.getOp();
        LockPlaces.PlaceTriple triple = getOrCreateLockPlace(monitor, unit, method, LockPlaces.PlaceType.LOCK);
        Unit allocUnit = getVariableUnit(strAllocValue);
        triple.setVar((Local) monitor);
        PlaceHLAPI lockPlace = triple.getLock(allocUnit, method);

        TransitionHLAPI enterTransition = createTransition(
                "enter_" + escapeXml(monitor.toString() + "_" + method.toString()), mainPage);
        createArc(currentPlace, enterTransition, mainPage);
        createArc(lockPlace, enterTransition, mainPage);
        PlaceHLAPI afterLock = createPlace("afterLock_" + monitor.toString() + escapeXml(method.toString()), mainPage, unit, method);
        createArc(enterTransition, afterLock, mainPage);
        endPlaceMethod.setPlace(afterLock);

        handleSuccessors(stmt, afterLock, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, true);
    }

    private void processDefault(Unit unit, PlaceHLAPI currentPlace, UnitGraph graph,
                                Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod) {
        List<Unit> successors = graph.getSuccsOf(unit);
        if (successors.isEmpty()
                && !(unit instanceof ReturnStmt || unit instanceof ReturnVoidStmt || unit instanceof ThrowStmt)) {
            if (method.getReturnType() instanceof VoidType) {
                processReturn(endPlaceMethod);
            } else {
                // Нужен переход из исходного места в конечное состояние
                TransitionHLAPI falloffTransition = createTransition("falloff_" + currentPlace.getId().hashCode(),
                        mainPage);
                createArc(currentPlace, falloffTransition, mainPage);
                PlaceHLAPI falloffEndPlace = createPlace("falloff_end_" + method.getName(), mainPage, unit, method);
                createArc(falloffTransition, falloffEndPlace, mainPage);
            }
        } else {
            for (Unit successor : successors) {
                addUnitToWorklist(successor, currentPlace, worklist, visitedUnits);
            }
        }
    }

    /**
     * Helper to format units for logging.
     */
    private String formatUnit(Unit unit) {
        String uStr = unit.toString();
        int line = unit.getJavaSourceStartLineNumber();
        String lineStr = (line > 0) ? " (L" + line + ")" : "";
        return uStr.substring(0, Math.min(uStr.length(), 100)) + lineStr; // Limit length
    }


    /**
     * Соединяет выходную позицию (представляющую состояние программы)
     * с входными позициями последующих инструкций (юнитов).
     * Этот метод используется, когда решение о дальнейшем потоке управления
     * принимается в данной позиции (например, после освобождения блокировки
     * или после возврата из вызова метода).
     *
     * @param unit         Текущая инструкция (юнит), для которой обрабатываются последующие инструкции.
     * @param beforeSuccessor Место в Сети Петри, представляющее состояние *перед* выполнением
     *                     последующих инструкций. Именно от этого места будут создаваться дуги к ним.
     * @param sourcePlace  Текущее место в Сети Петри, от которого идет управление (используется для логики "падения с конца метода")
     * @param graph        Граф потока управления (UnitGraph), содержащий информацию о связях между инструкциями.
     * @param worklist     Рабочий список (очередь) для алгоритма обхода, содержащий пары (инструкция, входная позиция).
     * @param visitedUnits Множество уже посещенных инструкций в текущей активации метода (для предотвращения зацикливания).
     * @param method       Текущий обрабатываемый метод (SootMethod).
     * @param endPlaceMethod Обёртка для хранения конечного места текущего обрабатываемого метода или ветви.
     * @param extractSuc   Флаг, указывающий, нужно ли извлекать последующие инструкции из графа (`true`) или `unit` сам является следующей инструкцией (`false`).
     */
    private void handleSuccessors(Unit unit, PlaceHLAPI beforeSuccessor, PlaceHLAPI sourcePlace, UnitGraph graph,
                                  Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod, boolean extractSuc) {
        List<Unit> successors = null;
        if (extractSuc == true) {
            successors = graph.getSuccsOf(unit);
        } else {
            successors = Collections.singletonList(unit);
        }
        if (successors.isEmpty()
                && !(unit instanceof ReturnStmt || unit instanceof ReturnVoidStmt || unit instanceof ThrowStmt)) {
            if (method.getReturnType() instanceof VoidType) {
                System.out.println("      Обнаружено завершение void-метода без явной инструкции return. Моделируется неявный возврат из позиции: "
                        + sourcePlace.getId());
                processReturn(endPlaceMethod); // Treat the state as requiring a return
            } else {
                System.err.println("Предупреждение: Обнаружен путь выполнения в не-void методе, который может завершиться без инструкции return: "
                        + sourcePlace.getId() + " после инструкции " + formatUnit(unit));

                TransitionHLAPI falloffTransition = createTransition("falloff_" + sourcePlace.getId().hashCode(),
                        mainPage);
                createArc(sourcePlace, falloffTransition, mainPage);
                PlaceHLAPI falloffEndPlace = createPlace("falloff_end_" + method.getName(), mainPage, unit, method);
                createArc(falloffTransition, falloffEndPlace, mainPage);
            }
        } else {
            for (Unit successor : successors) {
                addUnitToWorklist(successor, beforeSuccessor, worklist, visitedUnits);
            }
        }
    }

    private PlaceHLAPI getOrCreateUnitExitPlace(Unit unit, SootMethod method) {
        Pair<SootMethod, Unit> key = new Pair<>(method, unit);
        return unitExitPlaces.computeIfAbsent(key,
                k -> createPlace("after_" + escapeXml(unit.toString()), mainPage, unit, method));
    }

    /**
     * Добавляет объект и соответствующее ему место входа в worklist, если оно еще не посещено.
     */
    private void addUnitToWorklist(Unit unit, PlaceHLAPI unitEntryPlace, Queue<Pair<Unit, PlaceHLAPI>> worklist,
                                   Set<Unit> visitedUnits) {
        if (unit.toString().contains("caughtexception")) {
            return;
        }
        if (unit == null || unitEntryPlace == null) {
            System.err.println("!!! Попытка добавить пустой юнит или место в рабочий список !!!");
            return;
        }
        // Используем набор visitedUnits для текущего метода
        if (visitedUnits.add(unit)) {
            worklist.offer(new Pair<>(unit, unitEntryPlace));
            //System.out.println("      Добавляем в worklist: [" + unitEntryPlace.getId() + "] -> " + formatUnit(unit));
        } else {
            //System.out.println("      Юнит уже находится в worklist: [" + unitEntryPlace.getId() + "] -> " + formatUnit(unit));
        }
    }


    private LockPlaces.PlaceTriple getOrCreateLockPlace(Value lockRef, Unit monitorStmtUnit, SootMethod contextMethod, LockPlaces.PlaceType placeType) {
        // --- Идентификация монитора. Используется анализ указателей ---

        strAllocValue = null; // Строковое представление переменной
        try {
            Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> ali = BoomAnalysis.runAnalysis(contextMethod, lockRef.toString(), monitorStmtUnit);
            Map<ForwardQuery, AbstractBoomerangResults.Context> res = ali.getValue();
            if (!res.isEmpty()) {
                Map.Entry<ForwardQuery, AbstractBoomerangResults.Context> firstEntry = res.entrySet().iterator().next();
                ForwardQuery firstKey = firstEntry.getKey();
                strAllocValue = firstKey.var().toString();
            } else {

                strAllocValue = lockRef.toString() + " (" + contextMethod.getDeclaringClass().getName() + "." + contextMethod.getSignature() + ")";
                Map<String, LockPlaces.PlaceTriple> places = lockPlaces.getPlaces();

                if (!places.isEmpty()) {
                    for (Map.Entry<String, LockPlaces.PlaceTriple> entry : places.entrySet()) {
                        LockPlaces.PlaceTriple value = entry.getValue();
                        if (SparkAnalysis.isAlias((Local) lockRef, value.getVar())) {
                            return lockPlaces.getPlace(value.getVarName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Запрашиваемая переменная не найдена")) {
                strAllocValue = null;
            }
        }
        if (strAllocValue == null) {
            strAllocValue = lockRef.toString() + contextMethod.getSignature();
        }

        return lockPlaces.getPlace(strAllocValue);
    }


    private Pair<Unit, UnitGraph> searchThreadInit(Map<ForwardQuery, AbstractBoomerangResults.Context> allocSites) {
        for (ForwardQuery query : allocSites.keySet()) {
            JimpleVal localValue = ((JimpleVal) ((AllocVal) query.var()).getDelegate());
            SootMethod allocMethod = ((JimpleMethod) localValue.m()).getDelegate();
            String allocVal = localValue.getVariableName();
            UnitGraph allocGraph = new BriefUnitGraph(allocMethod.retrieveActiveBody());
            for (Unit u : allocGraph) {
                if (u instanceof JInvokeStmt && u.toString().contains(allocVal + ".")
                        && u.toString().contains("void <init>")) {
                    return new Pair<>(u, allocGraph);
                }
            }
        }
        return null;
    }

    private Unit searchLambdaInvoke(Map<ForwardQuery, AbstractBoomerangResults.Context> allocSites, String lambdaVar) {
        for (ForwardQuery query : allocSites.keySet()) {
            JimpleVal locallValue = ((JimpleVal) ((AllocVal) query.var()).getDelegate());
            SootMethod alloccMethod = ((JimpleMethod) locallValue.m()).getDelegate();
            UnitGraph alloccGraph = new BriefUnitGraph(alloccMethod.retrieveActiveBody());
            for (Unit lamU : alloccGraph) {
                if (lamU.toString().startsWith(lambdaVar + " = ")) {
                    return lamU;
                }
            }
        }
        return null;
    }

    private SootMethod newFindRunMethodForStartCall(Unit invokeStmt, SootMethod contextMethod) {
        SootMethod runMethod = null;
        SootClass startClass = ((JInvokeStmt) invokeStmt).getInvokeExpr().getMethodRef().getDeclaringClass();
        JVirtualInvokeExpr expp = (JVirtualInvokeExpr) ((JInvokeStmt) invokeStmt).getInvokeExpr();
        String query = expp.getBase().toString();
        Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> ali = BoomAnalysis.runAnalysis(contextMethod, query, invokeStmt);

        if (!startClass.toString().equals("java.lang.Thread")) { // Если запускаемый поток является наследником класса Thread
            LambdaMethods.addEntry(startClass.getMethod("void run()"), contextMethod, query, invokeStmt);
            return startClass.getMethod("void run()");
        } else {
            Map<ForwardQuery, AbstractBoomerangResults.Context> allocSites = ali.getValue();
            if (!allocSites.isEmpty()) {
                Pair<Unit, UnitGraph> ser = searchThreadInit(allocSites);
                Unit u = ser.getKey();
                UnitGraph allocGraph = ser.getValue();
                List<Value> args = ((JInvokeStmt) u).getInvokeExpr().getArgs();
                if (args.size() == 1) // Если лямбда
                {
                    String lambdaVar = args.get(0).toString();
                    for (Unit lamU : allocGraph) {
                        if (lamU.toString().startsWith(lambdaVar + " =") && lamU.toString().contains("lambda")
                                && lamU.toString().contains("java.lang.Runnable")) {
                            JAssignStmt assign = (JAssignStmt) lamU;
                            SootClass lamClass = ((JStaticInvokeExpr) assign.getRightOpBox().getValue()).getMethodRef()
                                    .getDeclaringClass();
                            runMethod = lamClass.getMethod("void run()");
                            LambdaMethods.addEntry(runMethod, contextMethod, lambdaVar, invokeStmt);
                            System.out.println(assign);
                            break;
                        }
                    }
                } else {
                    String lambdaVar = args.get(0).toString();
                    //PointerAnalysis.getAllocThreadStart(invokeStmt, contextMethod);
                    Unit allU = getAllocUnit(contextMethod, lambdaVar);
                    Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> labPair = BoomAnalysis.runAnalysis(contextMethod, lambdaVar, allU);
                    allocSites = labPair.getValue();
                    if (!allocSites.isEmpty()) {
                        ForwardQuery g = allocSites.keySet().iterator().next();
                        lambdaVar = g.var().getVariableName();
                        Unit lamU = searchLambdaInvoke(allocSites, lambdaVar);

                        JAssignStmt assign = (JAssignStmt) lamU;
                        SootClass runClass = null;
                        if (assign.getRightOp() instanceof JNewExpr) {
                            runClass = ((JNewExpr) assign.getRightOp()).getBaseType().getSootClass();
                        } else if (assign.getRightOp() instanceof JStaticInvokeExpr) {
                            runClass = ((JStaticInvokeExpr) assign.getRightOp()).getMethodRef().getDeclaringClass();
                        }
                        runMethod = runClass.getMethod("void run()");
                        LambdaMethods.addEntry(runMethod, contextMethod, lambdaVar, invokeStmt);
                    }
                }

            }
        }

        return runMethod;
    }

    private Unit getAllocUnit(SootMethod method, String query) {
        if (method == null || query == null || query.isEmpty()) {
            System.err.println("Метод или имя переменной не могут быть null/пустыми.");
            return null;
        }

        // Проверяем, есть ли у метода активное тело (например, абстрактные методы его не имеют)
        if (!method.hasActiveBody()) {
            // System.out.println("Метод " + method.getSignature() + " не имеет активного тела.");
            return null;
        }

        Body body = method.getActiveBody();

        // Проходим по всем юнитам (инструкциям) в теле метода
        for (Unit unit : body.getUnits()) {
            if (unit instanceof JAssignStmt) {
                JAssignStmt assignStmt = (JAssignStmt) unit;
                Value leftOp = assignStmt.getLeftOp();

                // Проверяем, является ли левый операнд локальной переменной
                if (leftOp instanceof Local) {
                    Local local = (Local) leftOp;
                    // Сравниваем имя локальной переменной с запрошенным именем
                    if (local.getName().equals(query)) {
                        return unit; //Возвращаем юнит присваивания.
                    }
                }
            }
        }

        // Если ничего не нашли после прохода по всем юнитам
        return null;
    }

    /**
     * Функция экранирует символы для последующей записи в XML
     * @param input
     * @return
     */
    public static String escapeXml(String input) {
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    public void exportToPnml() throws OtherException, ValidationFailedException, BadFileFormatException, IOException,
            OCLValidationFailed, UnhandledNetType {
        PnmlExport pex = new PnmlExport();
        pex.exportObject(this.document, Options.INSTANCE.getStringOption("app.pnml_file", ""));
    }

}
