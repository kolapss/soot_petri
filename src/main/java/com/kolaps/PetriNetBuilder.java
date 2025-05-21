package com.kolaps;

import boomerang.ForwardQuery;
import boomerang.results.AbstractBoomerangResults;
import boomerang.scene.AllocVal;
import boomerang.scene.Val;
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
    //private final Map<String, PlaceHLAPI> lockPlaces; // Ключ - Value из Soot (нужен хороший способ идентификации)
    private LockPlaces lockPlaces;
    private Map<Value, PlaceHLAPI> waitPlaces;
    private Map<Value, PlaceHLAPI> notifyPlaces;
    private Map<SootMethod, PlaceHLAPI> methodEntryPlaces;
    private Map<Pair<SootMethod, Unit>, PlaceHLAPI> unitExitPlaces;
    private Deque<PlaceHLAPI> returnPlaceStack; // Simulates call stack return points
    private Map<Object, String> objectIdMap; // Helper to give monitors more stable IDs
    private int uniqueIdCounter;
    private int monitorCounter;
    private String packageName;
    private TransitionHLAPI endTransition;

    PetriNetBuilder() throws InvalidIDException, VoidRepositoryException, OtherException, ValidationFailedException,
            BadFileFormatException, IOException, OCLValidationFailed, UnhandledNetType {
        ModelRepository.getInstance().createDocumentWorkspace("void");
        this.document = new PetriNetDocHLAPI();
        this.rootPetriNet = new PetriNetHLAPI("RootNet", PNTypeHLAPI.COREMODEL, new NameHLAPI("DeadlockFind"),
                this.document);
        this.mainPage = null;

        // --- Initialize State Maps ---
        this.lockPlaces = new LockPlaces();
        this.waitPlaces = new HashMap<>();
        this.notifyPlaces = new HashMap<>();
        this.methodEntryPlaces = new HashMap<>();
        this.unitExitPlaces = new HashMap<>();
        this.returnPlaceStack = new ArrayDeque<>();
        this.objectIdMap = new HashMap<>();
        this.uniqueIdCounter = 0;
        this.monitorCounter = 0;

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
        System.out.println("Created Page: " + name.getText() + " (ID: " + id + ")");
        return page;
    }

    public PetriNetHLAPI build(SootClass mainClass) {
        SootMethod mainMethod = mainClass.getMethodByName("main"); // Или getMethodByNameUnsafe
        if (mainMethod == null || !mainMethod.isStatic() || !mainMethod.isConcrete()) {
            throw new RuntimeException("Cannot find valid main method");
        }

        int dotIndex = mainClass.toString().indexOf('.');
        this.packageName = mainClass.toString().substring(0, dotIndex);

        // Создаем главную страницу для main метода
        mainPage = createPage("main_thread", rootPetriNet);

        // Создаем начальное место для main
        PlaceHLAPI startMainPlace = createPlace(escapeXml(mainMethod.getSignature()), mainPage, mainMethod.retrieveActiveBody().getUnits().getFirst(), mainMethod);
        endTransition = createTransition("end_transition", this.mainPage);
        PlaceHLAPI endPlace = createPlace("END", mainPage, mainMethod.retrieveActiveBody().getUnits().getLast(), mainMethod);
        // Устанавливаем начальную маркировку для точки входа
        new PTMarkingHLAPI(Long.valueOf(1), startMainPlace);
        System.out.println("Setting initial marking 1 for Start Place: " + startMainPlace.getId());
        PlaceHLAPI mainPlaceEnd;
        methodEntryPlaces.put(mainMethod, startMainPlace);
        // Start traversal
        try {
            mainPlaceEnd = traverseMethod(mainMethod, startMainPlace, new HashSet<>(), endPlace);
        } catch (Exception e) {
            System.err.println("Error during traversal: " + e.getMessage());
            e.printStackTrace();
            return null; // Indicate failure
        }
        //createArc(mainPlaceEnd, this.endTransition, this.mainPage);
        createArc(this.endTransition, endPlace, mainPage);

        // Return the constructed net
        // Option 1: HLAPI
        return this.rootPetriNet;
    }

    private void connectToReturn(PlaceHLAPI sourcePlace, String reason, SootMethod method) {
        TransitionHLAPI skipTransition = createTransition(reason + "_" + sourcePlace.getId().hashCode(), mainPage);
        createArc(sourcePlace, skipTransition, mainPage);
        if (!returnPlaceStack.isEmpty()) {
            PlaceHLAPI callerReturnPlace = returnPlaceStack.peek();
            createArc(skipTransition, callerReturnPlace, mainPage);
            System.out.println("      Connecting skip transition " + skipTransition.getId() + " to caller return place "
                    + callerReturnPlace.getId());
        } else {
            PlaceHLAPI endPlace = createPlace("end_" + reason, mainPage,method.retrieveActiveBody().getUnits().getLast(), method);
            createArc(skipTransition, endPlace, mainPage);
            System.out.println(
                    "      Connecting skip transition " + skipTransition.getId() + " to end place " + endPlace.getId());
        }
    }

    private PlaceHLAPI traverseMethod(SootMethod method, PlaceHLAPI entryPlace, Set<SootMethod> visitedOnPath,
                                      PlaceHLAPI afterPlace) {
        // --- Base Cases and Checks ---
        if (!method.isConcrete()) {
            System.out.println("Skipping non-concrete method: " + method.getSignature());
            connectToReturn(entryPlace, "skip_non_concrete", method); // Connect entry to return flow
            return null;
        }
        // Optional: Skip library methods (can configure this)
        if (method.isJavaLibraryMethod() || method.isPhantom()) {
            System.out.println("Skipping library/phantom method: " + method.getSignature());
            connectToReturn(entryPlace, "skip_library", method);
            return null;
        }
        if (visitedOnPath.contains(method)) {
            System.out
                    .println("Detected recursive call cycle, skipping deeper traversal for: " + method.getSignature());
            // Connect entry place of the recursive call back to its own entry place? Or to
            // return?
            // Connecting to return might be safer to avoid infinite loops in analysis if
            // not handled well.
            connectToReturn(entryPlace, "skip_recursion", method);
            return null;
        }
        if (!method.hasActiveBody()) {
            try {
                method.retrieveActiveBody(); // Try to get the body
                if (!method.hasActiveBody()) {
                    System.out.println("Method has no active body, skipping: " + method.getSignature());
                    connectToReturn(entryPlace, "skip_no_body", method);
                    return null;
                }
            } catch (Exception e) {
                System.out.println(
                        "Could not retrieve body for " + method.getSignature() + ", skipping: " + e.getMessage());
                connectToReturn(entryPlace, "skip_body_error", method);
                return null;
            }
        }

        System.out
                .println("Traversing method: " + method.getSignature() + " [Entry Place: " + entryPlace.getId() + "]");
        visitedOnPath.add(method); // Mark as visited for this path

        // --- Intra-procedural Worklist Setup ---
        Body body = method.getActiveBody();
        BriefUnitGraph graph = new BriefUnitGraph(body); // Use BriefUnitGraph!

        Queue<Pair<Unit, PlaceHLAPI>> worklist = new ArrayDeque<>();
        Set<Unit> visitedUnits = new HashSet<>(); // Units processed in this method activation

        // Add initial units to worklist
        for (Unit head : graph.getHeads()) {
            if (!head.toString().contains("caughtexception")) {
                worklist.offer(new Pair<>(head, entryPlace));
                visitedUnits.add(head);
            }// Mark heads as visited initially
        }
        MyPlace endPlaceMethod = new MyPlace(entryPlace);
        endPlaceMethod.setPlace(null);

        // --- Worklist Processing Loop ---
        while (!worklist.isEmpty()) {
            Pair<Unit, PlaceHLAPI> currentPair = worklist.poll();
            Unit currentUnit = currentPair.getKey();
            PlaceHLAPI currentUnitEntryPlace = currentPair.getValue(); // Place BEFORE executing currentUnit

            System.out.println("  Processing Unit: " + formatUnit(currentUnit) + " [Entry Place: "
                    + currentUnitEntryPlace.getId() + "]");
            if (currentUnit.toString().contains("thread2") && currentUnit.toString().contains("start()")) {
                System.out.println("test");
            }

            // --- Handle different statement types ---
            if (currentUnit instanceof EnterMonitorStmt) {
                processEnterMonitor((EnterMonitorStmt) currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist,
                        visitedUnits, method, endPlaceMethod);
            } else if (currentUnit instanceof ExitMonitorStmt) {
                processExitMonitor((ExitMonitorStmt) currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist,
                        visitedUnits, method, endPlaceMethod);
            } else if (currentUnit instanceof InvokeStmt) {
                processInvoke(currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist, visitedUnits, method,
                        visitedOnPath, endPlaceMethod);
            } else if (currentUnit instanceof IfStmt) {
                processIf((IfStmt) currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, visitedOnPath);
            } else if (currentUnit instanceof JGotoStmt) {
                processGoto((JGotoStmt) currentUnit, currentUnitEntryPlace, graph, worklist, visitedUnits, method);
            } else if (currentUnit instanceof LookupSwitchStmt || currentUnit instanceof TableSwitchStmt) {
                processSwitch((SwitchStmt) currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, visitedOnPath);
            } else if (currentUnit instanceof ReturnStmt || currentUnit instanceof ReturnVoidStmt) {
                processReturn((Stmt) currentUnit, currentUnitEntryPlace, endPlaceMethod); // No successors added from here
            }
            // Add more handlers if needed (e.g., AssignStmt if tracking specific values
            // matters)
            else {
                // Default: sequential execution for unhandled instructions
                processDefault(currentUnit, currentUnitEntryPlace, graph, worklist, visitedUnits, method, endPlaceMethod);
            }
        } // End worklist loop

        visitedOnPath.remove(method); // Unmark when returning from this method level
        System.out.println("Finished traversing method: " + method.getSignature());
        return endPlaceMethod.getPlace();
    }

    private void processGoto(JGotoStmt stmt, PlaceHLAPI currentPlace, UnitGraph graph, Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method) {


        Unit unit = stmt.getTarget();

        if (unit.toString().contains("caughtexception")) {
            return;
        }
        if (unit == null || currentPlace == null) {
            System.err.println("!!! Attempted to add null unit or place to worklist !!!");
            return;
        }
        // Use visitedUnits set for the current method activation context
        if (visitedUnits.add(unit)) { // .add() returns true if the element was not already in the set
            worklist.offer(new Pair<>(stmt.getTarget(), currentPlace));
            System.out.println("      Added to worklist: [" + currentPlace.getId() + "] -> ");
            // Goto has no fall-through, only the target.
        }
    }

    private void processReturn(Stmt stmt, PlaceHLAPI currentPlace, MyPlace endPlaceMethod) {
        // TransitionHLAPI returnTransition = createTransition("return_" +
        // currentPlace.getId(),this.mainPage);
        // createArc(currentPlace, returnTransition,this.mainPage);

        if (!returnPlaceStack.isEmpty()) {
            PlaceHLAPI callerReturnPlace = returnPlaceStack.peek(); // Peek, don't pop here. Popped by caller.
            // createArc(returnTransition, callerReturnPlace,this.mainPage);
            System.out.println("    Return -> Connects to caller return place: " + callerReturnPlace.getId());
        } else {
            // Return from the initial 'main' method or a thread's 'run' method
            System.out.println("    Return from top-level method (main or run).");
            if (endPlaceMethod.getPlace() != null) {
                createArc(endPlaceMethod.getPlace(), this.endTransition, this.mainPage);
            }
            /*
             * TransitionHLAPI returnTransition = createTransition("return_" +
             * currentPlace.getId(),this.mainPage);
             * // Optionally create a final "end" place and connect returns from main/run to
             * it.
             * PlaceHLAPI endPlace = createPlace("program_end_" +
             * currentPlace.getId(),this.mainPage); // Unique end place
             * createArc(returnTransition, endPlace,this.mainPage);
             */
        }
        // Return statement terminates this path in the current method. No successors to
        // add to worklist from here.
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
            Unit entryUnit = box;
            Queue<Pair<Unit, PlaceHLAPI>> ifWorklist = new ArrayDeque<>();
            Pair<Unit, PlaceHLAPI> key = new Pair<>(entryUnit, entrySwitchPlace);
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
        if (outArcs.isEmpty()) {
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


        // --- Intra-procedural Worklist Setup ---
        Body body = method.getActiveBody();
        BriefUnitGraph graph = new BriefUnitGraph(body); // Use BriefUnitGraph!

        Set<Unit> visitedUnits = new HashSet<>(); // Units processed in this method activation

        MyPlace endPlaceMethod = new MyPlace(entryPlace);
        endPlaceMethod.setPlace(null);

        // --- Worklist Processing Loop ---
        while (!worklist.isEmpty()) {
            Pair<Unit, PlaceHLAPI> currentPair = worklist.poll();
            Unit currentUnit = currentPair.getKey();
            PlaceHLAPI currentUnitEntryPlace = currentPair.getValue(); // Place BEFORE executing currentUnit

            System.out.println("  Processing Unit: " + formatUnit(currentUnit) + " [Entry Place: "
                    + currentUnitEntryPlace.getId() + "]");
            if (currentUnit.toString().contains("thread2") && currentUnit.toString().contains("start()")) {
                System.out.println("test");
            }

            // --- Handle different statement types ---
            if (currentUnit.toString().equals(gotounit.toString())) {
                worklist.clear();
                break;
            } else if (currentUnit instanceof EnterMonitorStmt) {
                processEnterMonitor((EnterMonitorStmt) currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist,
                        visitedUnits, method, endPlaceMethod);
            } else if (currentUnit instanceof ExitMonitorStmt) {
                processExitMonitor((ExitMonitorStmt) currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist,
                        visitedUnits, method, endPlaceMethod);
            } else if (currentUnit instanceof InvokeStmt) {
                processInvoke(currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist, visitedUnits, method,
                        visitedOnPath, endPlaceMethod);
            } else if (currentUnit instanceof IfStmt) {
                processIf((IfStmt) currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, visitedOnPath);
            } else if (currentUnit instanceof JGotoStmt) {
                processGoto((JGotoStmt) currentUnit, currentUnitEntryPlace, graph, worklist, visitedUnits, method);
            } else if (currentUnit instanceof LookupSwitchStmt || currentUnit instanceof TableSwitchStmt) {
                processSwitch((SwitchStmt) currentUnit, currentUnitEntryPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, visitedOnPath);
            }
            // Add more handlers if needed (e.g., AssignStmt if tracking specific values
            // matters)
            else {
                // Default: sequential execution for unhandled instructions
                processDefault(currentUnit, currentUnitEntryPlace, graph, worklist, visitedUnits, method, endPlaceMethod);
            }
        } // End worklist loop

        System.out.println("Finished traversing method: " + method.getSignature());
        return endPlaceMethod.getPlace();
    }


    private void processIf(IfStmt stmt, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph, Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod, Set<SootMethod> visitedOnPath) {
        System.out.println("    Branch: " + stmt.getCondition());

        // 'If' has two potential successors: the target (if condition true) and the fall-through (if false)
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
        for (Loop f : loopNestTree) {
            if (f.getBackJumpStmt().toString().equals(cUnit.toString())) {
                selectedCycle = f;
                break;
            }
        }
        if (selectedCycle == null) {
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
                this.mainPage.removeObjectsHLAPI(entryIfPlace);
                handleSuccessors(endTrueBrunch, currentPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, true);
            }
        } else {
            Unit endLoopBrunch = cUnit;
            processCycle(stmt, selectedCycle, trueUnit, endLoopBrunch, currentPlace, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, visitedOnPath);
        }

    }

    private void processCycle(Unit unit, Loop loop, Unit startCycleUnit, Unit endLoopBrunch, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph, Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod, Set<SootMethod> visitedOnPath) {
        System.out.println("gdgd");
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
        return unitExitPlaces.computeIfAbsent(key, k -> createPlace("entry_" + method.getName() + "_ln" + unit.getJavaSourceStartLineNumber() + "_" + unit.hashCode(), this.mainPage, unit, method));

    }

    private void processInvoke(Unit unit, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph,
                               Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod currentMethod,
                               Set<SootMethod> visitedOnPath, MyPlace endPlaceMethod) {
        InvokeStmt stmt = (InvokeStmt) unit;
        InvokeExpr invokeExpr = stmt.getInvokeExpr();
        SootMethodRef methodRef = invokeExpr.getMethodRef();

        try {
            SootMethod targetMethod = invokeExpr.getMethod(); // Resolve the target method
            String signature = targetMethod.getSignature();
            String callDesc = signature.substring(1, signature.length() - 1); // Trim <>

            System.out.println("    Invoke: " + callDesc);

            // --- Special Handling for Concurrency/Synchronization ---
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

            // --- Handle Regular Method Call ---
            else if (targetMethod.isConcrete() && isApplicationClass(targetMethod)) { // Analyze concrete application
                // methods
                processMethodCall(stmt, targetMethod, currentPlace, afterPlace, graph, worklist, visitedUnits,
                        currentMethod, visitedOnPath, endPlaceMethod);
            }
            // --- Default Handling for Other Calls (Library, Abstract, etc.) ---
            else {
                System.out.println("      Skipping traversal into (treating as atomic step): " + callDesc);
                processDefault(stmt, currentPlace, graph, worklist, visitedUnits, currentMethod, endPlaceMethod);
            }

        } catch (Exception e) { // Catch potential Soot resolution errors or others
            System.err.println(
                    "Error resolving/processing method call: " + methodRef.getSignature() + " at " + formatUnit(stmt));
            e.printStackTrace();
            // Fallback to default processing on error to keep analysis going
            processDefault(stmt, currentPlace, graph, worklist, visitedUnits, currentMethod, endPlaceMethod);
        }
    }

    private void processObjectNotify(Unit unit, InstanceInvokeExpr invokeExpr, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph, Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod) {

        InvokeStmt stmt = (InvokeStmt) unit;
        Value monitor = invokeExpr.getBase();
        LockPlaces.PlaceTriple triple = getOrCreateLockPlace(monitor, stmt, method, LockPlaces.PlaceType.WAIT);
        Unit allocUnit = getVariableUnit(strAllocValue);
        PlaceHLAPI waitPlace = triple.getWait(allocUnit, method);
        PlaceHLAPI lockPlace = triple.getLock(allocUnit, method);
        PlaceHLAPI notifyPlace = triple.getNotify(allocUnit, method);
        triple.setVar((Local)monitor);

        String monitorId = monitor.toString();


        // --- Wait Transition ---
        TransitionHLAPI notifyTransitionLeft = createTransition("notifyEntry_" + monitorId, mainPage);
        TransitionHLAPI notifyTransitionRight = createTransition("notifyEntry_" + monitorId, mainPage);

        PlaceHLAPI endNotifyPlace = createPlace("endNotify_" + monitorId, mainPage, unit, method);
        createArc(currentPlace, notifyTransitionRight, mainPage);
        createArc(currentPlace, notifyTransitionLeft, mainPage);
        createArc(notifyTransitionRight, endNotifyPlace, mainPage);
        createArc(notifyTransitionLeft, endNotifyPlace, mainPage);
        createArc(notifyTransitionLeft, notifyPlace, mainPage);
        createArc(waitPlace, notifyTransitionLeft, mainPage);
        //add inhibitor arc
        ArcHLAPI inhib = createArc(waitPlace, notifyTransitionRight, mainPage);
        PTExtension.addInhibitorArc(inhib.getId());
        endPlaceMethod.setPlace(endNotifyPlace);

        // Add successor AFTER wait to the worklist (originating from placeAfterWait)
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
        triple.setVar((Local)monitor);
        String monitorId = monitor.toString();


        // --- Wait Transition ---
        TransitionHLAPI waitTransition = createTransition("waitEntry_" + monitorId, mainPage);
        createArc(currentPlace, waitTransition, mainPage);
        createArc(waitTransition, lockPlace, mainPage);
        createArc(waitTransition, waitPlace, mainPage);

        PlaceHLAPI afterWaitPlace = createPlace("afterWait_" + monitorId, mainPage, unit, method);
        createArc(waitTransition, afterWaitPlace, mainPage);


        System.out.println("    Added Wait on: " + monitorId + " [Transition: " + waitTransition.getId() + ", Wait Place: " + monitorId + "]");

        // --- Path for Re-acquiring Lock After Notify ---
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

        // Add successor AFTER wait to the worklist (originating from placeAfterWait)
        handleSuccessors(unit, placeAfterWait, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, true);

    }


    private void processMethodCall(InvokeStmt stmt, SootMethod targetMethod, PlaceHLAPI currentPlace,
                                   PlaceHLAPI afterPlace, UnitGraph graph, Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits,
                                   SootMethod currentMethod, Set<SootMethod> visitedOnPath, MyPlace endPlaceMethod) {
        String targetDesc = targetMethod.getSignature();

        String baseName = "call_" + escapeXml(currentMethod.getSignature()) + "_to_" + escapeXml(targetDesc);
        // --- Call Transition ---
        /*
         * TransitionHLAPI callTransition = createTransition(baseName,this.mainPage);
         * createArc(currentPlace, callTransition,this.mainPage);
         *
         * // --- Target Method Entry Place ---
         * // Use cache or create if first time calling this target
         * PlaceHLAPI targetEntryPlace = methodEntryPlaces.computeIfAbsent(targetMethod,
         * m -> createPlace("entry_" + escapeXml(m.getSignature()),this.mainPage));
         * createArc(callTransition, targetEntryPlace,this.mainPage); // Arc from call
         * to target entry
         *
         * System.out.println("      Calling: " + targetMethod.getSignature() +
         * " [Target Entry: " + targetEntryPlace.getId() + "]");
         */

        // --- Return Place Handling ---
        // Place where control flow resumes *after* the call returns in the *caller*
        // PlaceHLAPI returnPlace = getOrCreateUnitExitPlace(stmt, currentMethod); //
        // Place after the invoke statement
        // System.out.println(" Return Place (after call): " + returnPlace.getId());

        // Push the return place onto the stack *before* recursive call
        returnPlaceStack.push(afterPlace);

        // --- Recursive Call ---
        PlaceHLAPI endInvokePlace=null;
        try {
            endInvokePlace = traverseMethod(targetMethod, currentPlace, visitedOnPath, afterPlace); // Recurse
            if (endInvokePlace != null) {
                endPlaceMethod.setPlace(endInvokePlace);
                //createArc(endInvokePlace, this.endTransition, this.mainPage);
            }
        } finally {
            // Pop the stack after the recursive call returns (or throws)
            if (!returnPlaceStack.isEmpty()) {
                PlaceHLAPI poppedPlace = returnPlaceStack.pop();
                if (poppedPlace != afterPlace) {
                    // This shouldn't happen with single-threaded traversal logic
                    System.err.println("!!! Return place stack mismatch! Expected " + afterPlace.getId() + ", got "
                            + poppedPlace.getId() + " !!!");
                }
            } else {
                System.err.println("!!! Return stack empty after traversing " + targetMethod.getName() + " !!!");
            }
        }

        // --- Add Successor to Worklist ---
        // The successor unit(s) after the call statement should be processed,
        // starting from the 'returnPlace'.
        if(endInvokePlace==null){
        handleSuccessors(stmt, currentPlace, afterPlace, graph, worklist, visitedUnits, currentMethod, endPlaceMethod, true);
        }else
        {
            handleSuccessors(stmt, endInvokePlace, afterPlace, graph, worklist, visitedUnits, currentMethod, endPlaceMethod, true);
        }
    }

    private boolean isApplicationClass(SootMethod method) {
        return method.getDeclaringClass().getName().contains(this.packageName);
    }

    private String getMonitorId(Value monitor) {
        return objectIdMap.computeIfAbsent(monitor, k -> {
            if (monitor instanceof StaticFieldRef) {
                StaticFieldRef sfr = (StaticFieldRef) monitor;
                return "Static_" + sfr.getFieldRef().declaringClass().getShortName() + "_" + sfr.getFieldRef().name();
            } else if (monitor instanceof ThisRef) {
                return "This_" + ((ThisRef) monitor).getType().toString().replace('.', '_');
            } else if (monitor instanceof ParameterRef) {
                return "Param" + ((ParameterRef) monitor).getIndex();
            } else if (monitor instanceof NewExpr) { // ID for the object created
                return "New_" + ((NewExpr) monitor).getBaseType().getSootClass().getShortName() + "_"
                        + (monitorCounter++);
            } else if (monitor instanceof StringConstant) {
                // Use hash for potentially long strings
                return "String_" + monitor.toString().hashCode();
            } else if (monitor instanceof ClassConstant) {
                return "Class_" + ((ClassConstant) monitor).getValue().replace('.', '_').replace('/', '_');
            }
            // Fallback for local variables or other types
            // Using hashCode might not be stable across runs, but best effort
            return monitor.getClass().getSimpleName() + "_" + System.identityHashCode(monitor); // Or monitor.hashCode()
            // return "Monitor_" + (monitorCounter++); // Simplest unique ID
        });
    }

    private void processThreadStart(Unit unit, InstanceInvokeExpr invokeExpr, PlaceHLAPI currentPlace,
                                    PlaceHLAPI afterPlace, UnitGraph graph, Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits,
                                    SootMethod currentMethod, Set<SootMethod> visitedOnPath, MyPlace endPlaceMethod) {
        InvokeStmt stmt = (InvokeStmt) unit;
        Value threadObject = invokeExpr.getBase(); // The thread object being started
        String threadId = getMonitorId(threadObject); // Use monitor ID logic for thread obj ID

        TransitionHLAPI startTransition = createTransition(escapeXml(invokeExpr.toString()), mainPage);
        createArc(currentPlace, startTransition, mainPage);

        // 1. Continue current thread's execution path
        PlaceHLAPI placeAfterStart = getOrCreateUnitExitPlace(stmt, currentMethod);
        endPlaceMethod.setPlace(placeAfterStart);
        createArc(startTransition, placeAfterStart, mainPage);
        handleSuccessors(stmt, placeAfterStart, afterPlace, graph, worklist, visitedUnits, currentMethod, endPlaceMethod, true); // Continue
        // caller
        // thread
        // &&&&&&

        // 2. Start new thread at its run() method
        // This requires finding the actual 'run' method. Simplified approach:
        // SootMethod runMethod = findRunMethod(threadObject);
        SootMethod runMethod = newFindRunMethodForStartCall(unit, currentMethod);

        if (runMethod != null && runMethod.isConcrete()) {
            PlaceHLAPI runEntryPlace = methodEntryPlaces.computeIfAbsent(runMethod,
                    m -> createPlace(escapeXml("entry_" + m.toString()), mainPage,unit, currentMethod));

            createArc(startTransition, runEntryPlace, mainPage); // Arc forks to the run method entry

            System.out.println("      Forking new thread for: " + runMethod.getSignature() + " [Entry Place: "
                    + runEntryPlace.getId() + "]");

            // Schedule the run method for traversal using a copy of the visited path stack
            // Note: In static analysis, this doesn't mean immediate execution, just adding
            // it to the model.
            // We can call traverseMethod directly here, but be aware it explores one
            // possible interleaving path at a time recursively.
            // A full analysis requires considering all paths in the final Petri net.
            try {
                PlaceHLAPI endInvokePlace = traverseMethod(runMethod, runEntryPlace, new HashSet<>(visitedOnPath), afterPlace); // Use copy of stack
                /*if (endInvokePlace != null) {
                    createArc(endInvokePlace, this.endTransition, this.mainPage);
                }*/
            } catch (Exception e) {
                System.err.println("Error traversing run() method for " + runMethod.getSignature());
                e.printStackTrace();
                // Continue anyway, the fork arc is still created
            }
        } else {
            System.out.println("      Could not find or analyze run() method for thread " + threadId
                    + ". Model may be incomplete.");
            // If run() can't be found/analyzed, the startTransition only leads to the
            // continuation of the parent thread.
        }
    }


    private void processExitMonitor(Unit unit, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph,
                                    Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod) {
        ExitMonitorStmt stmt = (ExitMonitorStmt) unit;
        Value monitor = stmt.getOp();
        LockPlaces.PlaceTriple triple = getOrCreateLockPlace(monitor, unit, method, LockPlaces.PlaceType.LOCK);
        Unit allocUnit = getVariableUnit(strAllocValue);
        PlaceHLAPI lockPlace = triple.getLock(allocUnit,method);
        String monitorId = getMonitorId(monitor);

        TransitionHLAPI exitTransition = createTransition(
                "exit_" + escapeXml(monitor.toString() + "_" + method.toString()), mainPage);
        createArc(currentPlace, exitTransition, mainPage); // Arc from current control flow place
        createArc(exitTransition, lockPlace, mainPage); // Arc from lock resource place (needs token)
        PlaceHLAPI afterLock = createPlace("after_" + lockPlace.getId(), mainPage, unit,method);
        createArc(exitTransition, afterLock, mainPage);
        endPlaceMethod.setPlace(afterLock);

        System.out.println("    Added EnterMonitor: " + monitorId + " [" + exitTransition.getId() + "]");

        // Add successors to worklist (normal path after acquiring lock)
        handleSuccessors(stmt, afterLock, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, true);
    }

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


        Unit assignmentUnits = null;

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
        String monitorId = getMonitorId(monitor);

        TransitionHLAPI enterTransition = createTransition(
                "enter_" + escapeXml(monitor.toString() + "_" + method.toString()), mainPage);
        createArc(currentPlace, enterTransition, mainPage); // Arc from current control flow place
        createArc(lockPlace, enterTransition, mainPage); // Arc from lock resource place (needs token)
        PlaceHLAPI afterLock = createPlace("afterLock_" + monitor.toString() + escapeXml(method.toString()), mainPage, unit, method);
        createArc(enterTransition, afterLock, mainPage);
        endPlaceMethod.setPlace(afterLock);

        System.out.println("    Added EnterMonitor: " + monitorId + " [" + enterTransition.getId() + "]");

        // Add successors to worklist (normal path after acquiring lock)
        handleSuccessors(stmt, afterLock, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod, true);
    }

    private void processDefault(Unit unit, PlaceHLAPI currentPlace, UnitGraph graph,
                                Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod) {
        List<Unit> successors = graph.getSuccsOf(unit);
        if (successors.isEmpty()
                && !(unit instanceof ReturnStmt || unit instanceof ReturnVoidStmt || unit instanceof ThrowStmt)) {
            if (method.getReturnType() instanceof VoidType) {
                System.out.println("      Unit falls off end of void method, adding implicit return from place: "
                        + currentPlace.getId());
                processReturn((Stmt) unit, currentPlace, endPlaceMethod); // Treat the state as requiring a return
            } else {
                System.err.println("Warning: Non-void method path may fall off end without return from place: "
                        + currentPlace.getId() + " after " + formatUnit(unit));
                // Need a transition from sourcePlace to end state
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
        // Connect to successor(s) starting FROM placeAfterStep
        // handleSuccessors(unit, currentPlace, graph, worklist, visitedUnits, method);
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
     * Connects the output of a *place* (representing a state) to the entry places
     * of successor units.
     * Use this when the control flow decision happens *at* the place (e.g., after
     * releasing a lock, after a call returns).
     *
     * @param unit         The current unit whose successors are being processed.
     * @param sourcePlace  The place representing the state *before* the successors
     *                     execute.
     * @param graph        The UnitGraph.
     * @param worklist     The worklist.
     * @param visitedUnits Set of visited units in this method activation.
     * @param method       The current method.
     */
    private void handleSuccessors(Unit unit, PlaceHLAPI beforeSuccessor, PlaceHLAPI sourcePlace, UnitGraph graph,
                                  Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod, boolean extractSuc) {
        // This case implies a direct P -> P flow which isn't standard in P/T nets.
        // Usually P -> T -> P'. Need to re-evaluate when this is called.
        // Example: After ExitMonitor, the transition T_exit produces token in
        // P_after_exit AND P_lock.
        // Successors should be processed starting from P_after_exit.
        List<Unit> successors = null;
        if (extractSuc == true) {
            successors = graph.getSuccsOf(unit);
        } else {
            successors = Collections.singletonList(unit);
        }
        if (successors.isEmpty()
                && !(unit instanceof ReturnStmt || unit instanceof ReturnVoidStmt || unit instanceof ThrowStmt)) {
            if (method.getReturnType() instanceof VoidType) {
                System.out.println("      Unit falls off end of void method, adding implicit return from place: "
                        + sourcePlace.getId());
                processReturn((Stmt) unit, sourcePlace, endPlaceMethod); // Treat the state as requiring a return
            } else {
                System.err.println("Warning: Non-void method path may fall off end without return from place: "
                        + sourcePlace.getId() + " after " + formatUnit(unit));
                // Need a transition from sourcePlace to end state
                TransitionHLAPI falloffTransition = createTransition("falloff_" + sourcePlace.getId().hashCode(),
                        mainPage);
                createArc(sourcePlace, falloffTransition, mainPage);
                PlaceHLAPI falloffEndPlace = createPlace("falloff_end_" + method.getName(), mainPage, unit, method);
                createArc(falloffTransition, falloffEndPlace, mainPage);
            }
        } else {
            for (Unit successor : successors) {
                // PlaceHLAPI placeBeforeSuccessor = getOrCreatePlaceBeforeUnit(successor,
                // method);
                // Need a transition between sourcePlace and placeBeforeSuccessor
                // TransitionHLAPI implicitStep = createTransition("step_" + sourcePlace.getId()
                // + "_to_" + beforeSuccessor.getId(), this.mainPage);
                // createArc(sourcePlace, implicitStep, this.mainPage);
                // createArc(implicitStep, beforeSuccessor, this.mainPage);
                /*if(returnPlaceStack.isEmpty() && (successor instanceof ReturnStmt || successor instanceof ReturnVoidStmt))
                {
                    returnPlaceStack.add(beforeSuccessor);
                }*/
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
     * Adds a unit and its corresponding entry place to the worklist if not already
     * visited.
     */
    private void addUnitToWorklist(Unit unit, PlaceHLAPI unitEntryPlace, Queue<Pair<Unit, PlaceHLAPI>> worklist,
                                   Set<Unit> visitedUnits) {
        if (unit.toString().contains("caughtexception")) {
            return;
        }
        if (unit == null || unitEntryPlace == null) {
            System.err.println("!!! Attempted to add null unit or place to worklist !!!");
            return;
        }
        // Use visitedUnits set for the current method activation context
        if (visitedUnits.add(unit)) { // .add() returns true if the element was not already in the set
            worklist.offer(new Pair<>(unit, unitEntryPlace));
            System.out.println("      Added to worklist: [" + unitEntryPlace.getId() + "] -> " + formatUnit(unit));
        } else {
            System.out.println(
                    "      Unit already visited/in worklist: [" + unitEntryPlace.getId() + "] -> " + formatUnit(unit));
            // This handles merging paths and loop back-edges naturally. The arc to the
            // existing
            // unitEntryPlace is already created by the caller (e.g., processIf,
            // handleSuccessors).
        }
    }


    private LockPlaces.PlaceTriple getOrCreateLockPlace(Value lockRef, Unit monitorStmtUnit, SootMethod contextMethod, LockPlaces.PlaceType placeType) {
        // --- КРИТИЧЕСКИЙ МОМЕНТ: Идентификация монитора ---
        // Простой (неточный) вариант: использовать тип объекта
        // Value lockRef -> Type lockType = lockRef.getType();
        // Более сложный: анализ аллокации, псевдонимов (aliasing) - выходит за рамки
        // простого примера
        // Пока используем сам Value как ключ (может не работать правильно, если Value
        // не имеет стабильного equals/hashCode)
        // или его строковое представление. НУЖНА НАДЕЖНАЯ СТРАТЕГИЯ ИДЕНТИФИКАЦИИ!
        strAllocValue = null;
        try {
            Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> ali = BoomAnalysis.runAnalysis(contextMethod, lockRef.toString(), monitorStmtUnit);
            //PointerAnalysis.getAllocSite(monitorStmtUnit,contextMethod);
            //Map<ForwardQuery, AbstractBoomerangResults.Context> alt = PointerAnalysis.allocSites;
            //Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> ali = analyzer.runAnalyses(lockRef.toString(), contextMethod.getDeclaringClass().getName(), contextMethod.getName());
            Map<ForwardQuery, AbstractBoomerangResults.Context> res = ali.getValue();
            if (!res.isEmpty()) {
                Map.Entry<ForwardQuery, AbstractBoomerangResults.Context> firstEntry = res.entrySet().iterator().next();
                ForwardQuery firstKey = firstEntry.getKey();
                strAllocValue = firstKey.var().toString();
            } else {

                /*Optional<Unit> unitOp =
                        contextMethod.getActiveBody().getUnits().stream()
                                .filter(e -> e.toString().startsWith(lockRef.toString()))
                                .findFirst();
                if (unitOp.isPresent()) {
                    Unit unit = unitOp.get();
                    if (unit instanceof JAssignStmt) {
                        JAssignStmt stmt = (JAssignStmt) unit;
                        Value leftOp = stmt.getLeftOp();
                    }
                    if (unit instanceof JIdentityStmt) {
                        JIdentityStmt stmt = (JIdentityStmt) unit;
                        Value leftOp = stmt.getLeftOp();
                    }
                }*/
                strAllocValue = lockRef.toString() + " (" + contextMethod.getDeclaringClass().getName() + "." + contextMethod.getSignature() + ")";
                Map<String, LockPlaces.PlaceTriple> places = lockPlaces.getPlaces();

                /*PointsToAnalysis pa = Scene.v().getPointsToAnalysis();
                GeomPointsTo geomPTA = (GeomPointsTo) pa;
                GeomQueries queryTester = new GeomQueries(geomPTA);*/
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
            if (e.getMessage() != null && e.getMessage().startsWith("Query Variable not found")) {
                strAllocValue = null;
            }
        }
        if (strAllocValue == null) {
            strAllocValue = lockRef.toString() + contextMethod.getSignature();
        }

        return lockPlaces.getPlace(strAllocValue);
        //String strAllocValue = PointerAnalysis.getAllocSite(monitorStmtUnit, contextMethod);
        /*return lockPlaces.computeIfAbsent(strAllocValue, key -> {
            System.out.println("Creating new Lock Place for identifier: " + key);
            String placeName = "Lock_" + key;
            placeName = escapeXml(placeName);
            PlaceHLAPI lockPlace = createPlace(placeName, mainPage);
            // Установить начальную маркировку = 1 (блокировка свободна)
            new PTMarkingHLAPI(Long.valueOf(1), lockPlace);
            System.out.println("Setting initial marking 1 for Lock Place: " + lockPlace.getId() + " (ID: " + key + ")");
            return lockPlace;
        });*/

        /*
         * return lockPlaces.computeIfAbsent(lockRef, k -> {
         * PlaceHLAPI lockPlace = createPlace("Lock_" +
         * k.toString().replaceAll("[^a-zA-Z0-9_]", "_"), rootPetriNet); // Блокировки -
         * глобальные ресурсы (на корневом уровне)
         * // Установить начальную маркировку = 1 (блокировка свободна)
         * // lockPlace.setInitialMarking(new MarkingHLAPI(lockPlace, 1.0)); // Пример
         * API
         * System.out.println("Setting initial marking 1 for Lock Place: " +
         * lockPlace.getName().getText());
         * return lockPlace;
         * });
         */
    }

    // Вспомогательный метод: Проверка, является ли вызов Thread.start()
    private boolean isThreadStartCall(InvokeStmt invokeStmt) {
        SootMethodRef methodRef = invokeStmt.getInvokeExpr().getMethodRef();
        // Проверяем класс и имя метода
        return methodRef.getDeclaringClass().getName().equals("java.lang.Thread") &&
                methodRef.getName().equals("start") &&
                methodRef.getParameterTypes().isEmpty() && // start() не имеет параметров
                methodRef.getReturnType().equals(VoidType.v());
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
        String targetClass = contextMethod.getDeclaringClass().toString();
        JVirtualInvokeExpr expp = (JVirtualInvokeExpr) ((JInvokeStmt) invokeStmt).getInvokeExpr();
        String query = expp.getBase().toString();
        Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> ali = BoomAnalysis.runAnalysis(contextMethod, query, invokeStmt);
        //Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> ali = analyzer.runAnalyses(query, targetClass, contextMethod.getName());

        if (!startClass.toString().equals("java.lang.Thread")) {
            LambdaMethods.addEntry(startClass.getMethod("void run()"),contextMethod, query, invokeStmt);
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
                        SootClass runClass=null;
                        if (assign.getRightOp() instanceof JNewExpr) {
                            runClass = ((JNewExpr) assign.getRightOp()).getBaseType().getSootClass();
                        } else if(assign.getRightOp() instanceof JStaticInvokeExpr) {
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
            // Нас интересуют только инструкции присваивания
            if (unit instanceof JAssignStmt) {
                JAssignStmt assignStmt = (JAssignStmt) unit;
                Value leftOp = assignStmt.getLeftOp(); // Левый операнд (чему присваиваем)

                // Проверяем, является ли левый операнд локальной переменной
                if (leftOp instanceof Local) {
                    Local local = (Local) leftOp;
                    // Сравниваем имя локальной переменной с запрошенным именем
                    if (local.getName().equals(query)) {
                        return unit; // Нашли! Возвращаем юнит присваивания.
                    }
                }
            }
        }

        // Если ничего не нашли после прохода по всем юнитам
        return null;
    }

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
        pex.exportObject(this.document, Options.INSTANCE.getStringOption("app.pnml_file",""));
    }

}
