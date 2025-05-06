package com.kolaps;

import boomerang.ForwardQuery;
import boomerang.results.AbstractBoomerangResults;
import boomerang.scene.AllocVal;
import boomerang.scene.jimple.JimpleMethod;
import boomerang.scene.jimple.JimpleVal;
import boomerang.util.AccessPath;
import com.kolaps.analyses.AliasingAnalysis;
import com.kolaps.analyses.BoomAnalysis;
import com.kolaps.analyses.SparseAliasManager;
import fr.lip6.move.pnml.framework.general.PnmlExport;
import fr.lip6.move.pnml.framework.utils.ModelRepository;
import fr.lip6.move.pnml.framework.utils.exception.*;
import fr.lip6.move.pnml.ptnet.hlapi.*;
import javafx.util.Pair;
import polyglot.ast.If;
import soot.*;
import soot.jimple.*;
import soot.jimple.internal.*;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.UnitGraph;

import java.io.IOException;
import java.util.*;

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
    private PageHLAPI mainPage;
    private final Map<String, PlaceHLAPI> lockPlaces; // Ключ - Value из Soot (нужен хороший способ идентификации)
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
    private AliasingAnalysis analyzer = new AliasingAnalysis();

    PetriNetBuilder() throws InvalidIDException, VoidRepositoryException, OtherException, ValidationFailedException,
            BadFileFormatException, IOException, OCLValidationFailed, UnhandledNetType {
        ModelRepository.getInstance().createDocumentWorkspace("void");
        this.document = new PetriNetDocHLAPI();
        this.rootPetriNet = new PetriNetHLAPI("RootNet", PNTypeHLAPI.COREMODEL, new NameHLAPI("DeadlockFind"),
                this.document);
        this.mainPage = null;

        // --- Initialize State Maps ---
        this.lockPlaces = new HashMap<>();
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
        PlaceHLAPI startMainPlace = createPlace(escapeXml(mainMethod.getSignature()), mainPage);
        endTransition = createTransition("end_transition", this.mainPage);
        PlaceHLAPI endPlace = createPlace("END", mainPage);
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
        createArc(this.endTransition, endPlace, this.mainPage);

        // Return the constructed net
        // Option 1: HLAPI
        return this.rootPetriNet;
    }

    private void connectToReturn(PlaceHLAPI sourcePlace, String reason) {
        TransitionHLAPI skipTransition = createTransition(reason + "_" + sourcePlace.getId().hashCode(), this.mainPage);
        createArc(sourcePlace, skipTransition, this.mainPage);
        if (!returnPlaceStack.isEmpty()) {
            PlaceHLAPI callerReturnPlace = returnPlaceStack.peek();
            createArc(skipTransition, callerReturnPlace, this.mainPage);
            System.out.println("      Connecting skip transition " + skipTransition.getId() + " to caller return place "
                    + callerReturnPlace.getId());
        } else {
            PlaceHLAPI endPlace = createPlace("end_" + reason, this.mainPage);
            createArc(skipTransition, endPlace, this.mainPage);
            System.out.println(
                    "      Connecting skip transition " + skipTransition.getId() + " to end place " + endPlace.getId());
        }
    }

    private PlaceHLAPI traverseMethod(SootMethod method, PlaceHLAPI entryPlace, Set<SootMethod> visitedOnPath,
                                      PlaceHLAPI afterPlace) {

        // --- Base Cases and Checks ---
        if (!method.isConcrete()) {
            System.out.println("Skipping non-concrete method: " + method.getSignature());
            connectToReturn(entryPlace, "skip_non_concrete"); // Connect entry to return flow
            return null;
        }
        // Optional: Skip library methods (can configure this)
        if (method.isJavaLibraryMethod() || method.isPhantom()) {
            System.out.println("Skipping library/phantom method: " + method.getSignature());
            connectToReturn(entryPlace, "skip_library");
            return null;
        }
        if (visitedOnPath.contains(method)) {
            System.out
                    .println("Detected recursive call cycle, skipping deeper traversal for: " + method.getSignature());
            // Connect entry place of the recursive call back to its own entry place? Or to
            // return?
            // Connecting to return might be safer to avoid infinite loops in analysis if
            // not handled well.
            connectToReturn(entryPlace, "skip_recursion");
            return null;
        }
        if (!method.hasActiveBody()) {
            try {
                method.retrieveActiveBody(); // Try to get the body
                if (!method.hasActiveBody()) {
                    System.out.println("Method has no active body, skipping: " + method.getSignature());
                    connectToReturn(entryPlace, "skip_no_body");
                    return null;
                }
            } catch (Exception e) {
                System.out.println(
                        "Could not retrieve body for " + method.getSignature() + ", skipping: " + e.getMessage());
                connectToReturn(entryPlace, "skip_body_error");
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
                processSwitch((SwitchStmt) currentUnit, currentUnitEntryPlace, graph, worklist, visitedUnits, method);
            } else if (currentUnit instanceof ReturnStmt || currentUnit instanceof ReturnVoidStmt) {
                processReturn((Stmt) currentUnit, currentUnitEntryPlace, endPlaceMethod); // No successors added from here
            } else if (currentUnit instanceof ThrowStmt) {
                processThrow((ThrowStmt) currentUnit, currentUnitEntryPlace, graph, worklist, visitedUnits, method);
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

    private void processThrow(ThrowStmt currentUnit, PlaceHLAPI currentUnitEntryPlace, UnitGraph graph,
                              Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method) {
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

    private void processSwitch(SwitchStmt currentUnit, PlaceHLAPI currentUnitEntryPlace, UnitGraph graph,
                               Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method) {
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
                processSwitch((SwitchStmt) currentUnit, currentUnitEntryPlace, graph, worklist, visitedUnits, method);
            } else if (currentUnit.equals(gotounit)) {
                worklist.clear();
            } else if (currentUnit instanceof ThrowStmt) {
                processThrow((ThrowStmt) currentUnit, currentUnitEntryPlace, graph, worklist, visitedUnits, method);
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
        PlaceHLAPI entryIfPlace = createPlace(escapeXml("entry_" + stmt.toString()), this.mainPage);
        Queue<Pair<Unit, PlaceHLAPI>> ifWorklist = new ArrayDeque<>();
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
            TransitionHLAPI brunchStartTransition = createTransition("startIf_" + entryIfPlace.getId(), this.mainPage);
            createArc(currentPlace, brunchStartTransition, this.mainPage);
            createArc(brunchStartTransition, entryIfPlace, this.mainPage);
            TransitionHLAPI endIfTransition = createTransition("endTrueIf_" + endIfTruePlace.getId(), this.mainPage);
            createArc(endIfTruePlace, endIfTransition, this.mainPage);
            endIfPlace = createPlace("endIf_" + entryIfPlace.getId(), this.mainPage);
            createArc(endIfTransition, endIfPlace, this.mainPage);
            if (falseList.isEmpty()) { //Если нет блока else, то просто соединяем entryIfPlace c endIfPlace
                TransitionHLAPI elseTransition = createTransition("else_" + entryIfPlace.getId(), this.mainPage);
                createArc(currentPlace, elseTransition, this.mainPage);
                createArc(elseTransition, endIfPlace, this.mainPage);
                endPlaceMethod.setPlace(endIfPlace);
                handleSuccessors(endTrueBrunch,endIfPlace,afterPlace,graph,worklist,visitedUnits,method,endPlaceMethod);
            } else { //Обрабатываем ветку else
                endIfFalsePlace = processBrunch(method, entryIfPlace, visitedOnPath, afterPlace, ifWorklist, endTrueBrunch);
                if (endIfFalsePlace != null) {
                    TransitionHLAPI endFalseTransition = createTransition("endFalse_" + entryIfPlace.getId(), this.mainPage);
                    createArc(endIfFalsePlace, endFalseTransition, this.mainPage);
                    createArc(endFalseTransition, endIfPlace, this.mainPage);
                } else {
                    TransitionHLAPI elseTransition = createTransition("else_" + entryIfPlace.getId(), this.mainPage);
                    createArc(currentPlace, elseTransition, this.mainPage);
                    createArc(elseTransition, endIfPlace, this.mainPage);
                }
                endPlaceMethod.setPlace(endIfPlace);
                handleSuccessors(endTrueBrunch,endIfPlace,afterPlace,graph,worklist,visitedUnits,method,endPlaceMethod);
            }
        } else {
            this.mainPage.removeObjectsHLAPI(entryIfPlace);
            handleSuccessors(stmt,currentPlace,afterPlace,graph,ifWorklist,visitedUnits,method,endPlaceMethod);
        }



        // --- True Branch ---
        /*TransitionHLAPI trueTransition = createTransition("if_true_" + currentPlace.getId().hashCode(),this.mainPage);
        createArc(currentPlace, trueTransition,this.mainPage);
        PlaceHLAPI targetPlace = getOrCreateUnitEntryPlace(targetUnit, method); // Entry place of the target unit
        createArc(trueTransition, targetPlace, this.mainPage);
        addUnitToWorklist(targetUnit, targetPlace, worklist, visitedUnits);


        // --- False Branch ---
        if (fallThroughUnit != null) {
            TransitionHLAPI falseTransition = createTransition("if_false_" + currentPlace.getId().hashCode(),this.mainPage);
            createArc(currentPlace, falseTransition, this.mainPage);
            PlaceHLAPI fallThroughPlace = getOrCreateUnitEntryPlace(fallThroughUnit, method); // Entry place of the fall-through unit
            createArc(falseTransition, fallThroughPlace,this.mainPage);
            addUnitToWorklist(fallThroughUnit, currentPlace, worklist, visitedUnits);
        } else {
            // If no fallthrough, the false transition leads nowhere in this model
            System.out.println("      (False branch has no successor unit)");
        }*/
    }

    private PlaceHLAPI getOrCreateUnitEntryPlace(Unit unit, SootMethod method) {

        Pair<SootMethod, Unit> key = new Pair<>(method, unit); // Reuse Pair for consistency
        return unitExitPlaces.computeIfAbsent(key, k -> createPlace("entry_" + method.getName() + "_ln" + unit.getJavaSourceStartLineNumber() + "_" + unit.hashCode(), this.mainPage));

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
            } /*
             * else if ((signature.
             * equals("<java.lang.Object: void wait() throws java.lang.InterruptedException>"
             * ) ||
             * signature.
             * equals("<java.lang.Object: void wait(long) throws java.lang.InterruptedException>"
             * ) ||
             * signature.
             * equals("<java.lang.Object: void wait(long, int) throws java.lang.InterruptedException>"
             * ))
             * && invokeExpr instanceof InstanceInvokeExpr) {
             * processObjectWait(stmt, (InstanceInvokeExpr) invokeExpr, currentPlace, graph,
             * worklist, visitedUnits, currentMethod);
             * } else if ((signature.equals("<java.lang.Object: void notify()>") ||
             * signature.equals("<java.lang.Object: void notifyAll()>"))
             * && invokeExpr instanceof InstanceInvokeExpr) {
             * processObjectNotify(stmt, (InstanceInvokeExpr) invokeExpr, currentPlace,
             * graph, worklist, visitedUnits, currentMethod);
             * }
             */
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
        try {
            PlaceHLAPI endInvokePlace = traverseMethod(targetMethod, currentPlace, visitedOnPath, afterPlace); // Recurse
            if (endInvokePlace != null) {
                createArc(endInvokePlace, this.endTransition, this.mainPage);
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
        handleSuccessors(stmt, currentPlace, afterPlace, graph, worklist, visitedUnits, currentMethod, endPlaceMethod);
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

        TransitionHLAPI startTransition = createTransition(escapeXml(invokeExpr.toString()), this.mainPage);
        createArc(currentPlace, startTransition, this.mainPage);

        // 1. Continue current thread's execution path
        PlaceHLAPI placeAfterStart = getOrCreateUnitExitPlace(stmt, currentMethod);
        endPlaceMethod.setPlace(placeAfterStart);
        createArc(startTransition, placeAfterStart, this.mainPage);
        handleSuccessors(stmt, placeAfterStart, afterPlace, graph, worklist, visitedUnits, currentMethod, endPlaceMethod); // Continue
        // caller
        // thread
        // &&&&&&

        // 2. Start new thread at its run() method
        // This requires finding the actual 'run' method. Simplified approach:
        // SootMethod runMethod = findRunMethod(threadObject);
        SootMethod runMethod = newFindRunMethodForStartCall(unit, currentMethod);

        if (runMethod != null && runMethod.isConcrete()) {
            PlaceHLAPI runEntryPlace = methodEntryPlaces.computeIfAbsent(runMethod,
                    m -> createPlace(escapeXml("entry_" + m.toString()), this.mainPage));

            createArc(startTransition, runEntryPlace, this.mainPage); // Arc forks to the run method entry

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
                if (endInvokePlace != null) {
                    createArc(endInvokePlace, this.endTransition, this.mainPage);
                }
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

    private SootMethod findRunMethod(Value threadObject) {
        return null;
    }

    private void processExitMonitor(Unit unit, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph,
                                    Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod) {
        ExitMonitorStmt stmt = (ExitMonitorStmt) unit;
        Value monitor = stmt.getOp();
        PlaceHLAPI lockPlace = getOrCreateLockPlace(monitor, unit, method);
        String monitorId = getMonitorId(monitor);

        TransitionHLAPI exitTransition = createTransition(
                "exit_" + escapeXml(monitor.toString() + "_" + method.toString()), this.mainPage);
        createArc(currentPlace, exitTransition, this.mainPage); // Arc from current control flow place
        createArc(exitTransition, lockPlace, this.mainPage); // Arc from lock resource place (needs token)
        PlaceHLAPI afterLock = createPlace("after_" + lockPlace.getId(), this.mainPage);
        createArc(exitTransition, afterLock, this.mainPage);
        endPlaceMethod.setPlace(afterLock);

        System.out.println("    Added EnterMonitor: " + monitorId + " [" + exitTransition.getId() + "]");

        // Add successors to worklist (normal path after acquiring lock)
        handleSuccessors(stmt, afterLock, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod);
    }

    private void processEnterMonitor(Unit unit, PlaceHLAPI currentPlace, PlaceHLAPI afterPlace, UnitGraph graph,
                                     Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod) {
        EnterMonitorStmt stmt = (EnterMonitorStmt) unit;
        Value monitor = stmt.getOp();
        PlaceHLAPI lockPlace = getOrCreateLockPlace(monitor, unit, method);
        String monitorId = getMonitorId(monitor);

        TransitionHLAPI enterTransition = createTransition(
                "enter_" + escapeXml(monitor.toString() + "_" + method.toString()), this.mainPage);
        createArc(currentPlace, enterTransition, this.mainPage); // Arc from current control flow place
        createArc(lockPlace, enterTransition, this.mainPage); // Arc from lock resource place (needs token)
        PlaceHLAPI afterLock = createPlace("after_" + lockPlace.getId(), this.mainPage);
        createArc(enterTransition, afterLock, this.mainPage);
        endPlaceMethod.setPlace(afterLock);

        System.out.println("    Added EnterMonitor: " + monitorId + " [" + enterTransition.getId() + "]");

        // Add successors to worklist (normal path after acquiring lock)
        handleSuccessors(stmt, afterLock, afterPlace, graph, worklist, visitedUnits, method, endPlaceMethod);
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
                        this.mainPage);
                createArc(currentPlace, falloffTransition, this.mainPage);
                PlaceHLAPI falloffEndPlace = createPlace("falloff_end_" + method.getName(), this.mainPage);
                createArc(falloffTransition, falloffEndPlace, this.mainPage);
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
     * Connects the given transition's output to the entry places of the successor
     * units.
     * Adds successors to the worklist if they haven't been visited yet.
     * Use this after creating a transition for a unit (like EnterMonitor, Default,
     * etc.).
     *
     * @param unit             The current unit whose successors are being
     *                         processed.
     * @param sourceTransition The transition representing the execution of 'unit'.
     * @param graph            The UnitGraph.
     * @param worklist         The worklist.
     * @param visitedUnits     Set of visited units in this method activation.
     * @param method           The current method.
     */
    private void handleSuccessors(Unit unit, TransitionHLAPI sourceTransition, UnitGraph graph,
                                  Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod) {
        List<Unit> successors = graph.getSuccsOf(unit);
        if (successors.isEmpty()
                && !(unit instanceof ReturnStmt || unit instanceof ReturnVoidStmt || unit instanceof ThrowStmt)) {
            // Handle falling off the end of a method (implicit return void)
            if (method.getReturnType() instanceof VoidType) {
                System.out.println("      Unit falls off end of void method, adding implicit return.");
                // Create implicit return transition connected from sourceTransition's output
                // place
                PlaceHLAPI placeAfterUnit = getOrCreateUnitExitPlace(unit, method); // We need the place *after* the
                // unit
                createArc(sourceTransition, placeAfterUnit, this.mainPage); // Connect unit transition to its exit place
                processReturn((Stmt) unit, placeAfterUnit, endPlaceMethod); // Process return starting from that exit place
            } else {
                System.err.println("Warning: Non-void method path may fall off end without return: " + formatUnit(unit)
                        + " in " + method.getName());
                // Connect to a generic error/end state?
                PlaceHLAPI falloffEndPlace = createPlace("falloff_end_" + method.getName(), this.mainPage);
                createArc(sourceTransition, falloffEndPlace, this.mainPage);
            }
        } else {
            for (Unit successor : successors) {
                if (!(successor instanceof JIdentityStmt)) {
                    PlaceHLAPI placeBeforeSuccessor = getOrCreatePlaceBeforeUnit(successor, method);
                    createArc(sourceTransition, placeBeforeSuccessor, this.mainPage); // Arc from unit's transition to
                    // the place *before* the
                    // successor
                    addUnitToWorklist(successor, placeBeforeSuccessor, worklist, visitedUnits);
                }
            }
        }
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
                                  Queue<Pair<Unit, PlaceHLAPI>> worklist, Set<Unit> visitedUnits, SootMethod method, MyPlace endPlaceMethod) {
        // This case implies a direct P -> P flow which isn't standard in P/T nets.
        // Usually P -> T -> P'. Need to re-evaluate when this is called.
        // Example: After ExitMonitor, the transition T_exit produces token in
        // P_after_exit AND P_lock.
        // Successors should be processed starting from P_after_exit.

        List<Unit> successors = graph.getSuccsOf(unit);
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
                        this.mainPage);
                createArc(sourcePlace, falloffTransition, this.mainPage);
                PlaceHLAPI falloffEndPlace = createPlace("falloff_end_" + method.getName(), this.mainPage);
                createArc(falloffTransition, falloffEndPlace, this.mainPage);
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
                k -> createPlace("after_" + escapeXml(unit.toString()), this.mainPage));
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

    private PlaceHLAPI getOrCreatePlaceBeforeUnit(Unit unit, SootMethod method) {
        Pair<SootMethod, Unit> key = new Pair<>(method, unit);
        // Use the same map but maybe adjust naming convention or use a separate map if
        // clearer
        return unitExitPlaces.computeIfAbsent(key, k -> createPlace("before_" + method.getName(), this.mainPage));
    }

    private PlaceHLAPI getOrCreateLockPlace(Value lockRef, Unit monitorStmtUnit, SootMethod contextMethod) {
        // --- КРИТИЧЕСКИЙ МОМЕНТ: Идентификация монитора ---
        // Простой (неточный) вариант: использовать тип объекта
        // Value lockRef -> Type lockType = lockRef.getType();
        // Более сложный: анализ аллокации, псевдонимов (aliasing) - выходит за рамки
        // простого примера
        // Пока используем сам Value как ключ (может не работать правильно, если Value
        // не имеет стабильного equals/hashCode)
        // или его строковое представление. НУЖНА НАДЕЖНАЯ СТРАТЕГИЯ ИДЕНТИФИКАЦИИ!
        String strAllocValue = null;
        try {
            Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> ali = BoomAnalysis.runAnalysis(contextMethod, lockRef.toString());
            //Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> ali = analyzer.runAnalyses(lockRef.toString(), contextMethod.getDeclaringClass().getName(), contextMethod.getName());
            Map<ForwardQuery, AbstractBoomerangResults.Context> res = ali.getValue();
            if (!res.isEmpty()) {
                Map.Entry<ForwardQuery, AbstractBoomerangResults.Context> firstEntry = res.entrySet().iterator().next();
                ForwardQuery firstKey = firstEntry.getKey();
                strAllocValue = firstKey.var().toString();
            } else {
                Optional<Unit> unitOp =
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
                }
                strAllocValue = lockRef.toString() + " (" + contextMethod.getDeclaringClass().getName() + "." + contextMethod.getSignature() + ")";
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Query Variable not found")) {
                strAllocValue = null;
            }
        }
        if (strAllocValue == null) {
            strAllocValue = lockRef.toString() + contextMethod.getSignature();
        }
        //String strAllocValue = PointerAnalysis.getAllocSite(monitorStmtUnit, contextMethod);
        return lockPlaces.computeIfAbsent(strAllocValue, key -> {
            System.out.println("Creating new Lock Place for identifier: " + key);
            String placeName = "Lock_" + key;
            placeName = escapeXml(placeName);
            PlaceHLAPI lockPlace = createPlace(placeName, mainPage);
            // Установить начальную маркировку = 1 (блокировка свободна)
            new PTMarkingHLAPI(Long.valueOf(1), lockPlace);
            System.out.println("Setting initial marking 1 for Lock Place: " + lockPlace.getId() + " (ID: " + key + ")");
            return lockPlace;
        });

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
            String alloccVal = locallValue.getVariableName();
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
        Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> ali = BoomAnalysis.runAnalysis(contextMethod, query);
        //Pair<Set<AccessPath>, Map<ForwardQuery, AbstractBoomerangResults.Context>> ali = analyzer.runAnalyses(query, targetClass, contextMethod.getName());

        if (!startClass.toString().equals("java.lang.Thread")) {
            return startClass.getMethod("void run()");
        } else {
            Set<AccessPath> aliases = ali.getKey();
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
                            System.out.println(assign);
                            break;
                        }
                    }
                } else {
                    String lambdaVar = args.get(0).toString();
                    PointerAnalysis.getAllocThreadStart(invokeStmt, contextMethod);
                    Map<ForwardQuery, AbstractBoomerangResults.Context> allocNew = PointerAnalysis.allocSites;
                    ForwardQuery allocQuery = null;
                    if (!allocSites.isEmpty()) {
                        Unit lamU = searchLambdaInvoke(allocSites, lambdaVar);

                        JAssignStmt assign = (JAssignStmt) lamU;
                        SootClass runClass = ((JNewExpr) assign.getRightOp()).getBaseType().getSootClass();
                        runMethod = runClass.getMethod("void run()");
                    }
                }

            }
        }

        return runMethod;
    }


    private SootMethod findRunMethodForStartCall(Unit invokeStmt, SootMethod contextMethod) {
        // 1. Получить объект, на котором вызывается start():
        // invokeStmt.getInvokeExpr().getUseBoxes() -> Value base
        // 2. Определить тип этого объекта: base.getType() -> Type type
        // 3. Если тип - RefType, получить SootClass: ((RefType) type).getSootClass()
        // 4. Найти метод run() в этом классе или его суперклассах/интерфейсах:
        // sootClass.getMethodUnsafe("run", Collections.emptyList(), VoidType.v())
        // ИЛИ sootClass.getMethodByNameUnsafe("run") и проверить сигнатуру.
        // 5. Это может потребовать анализа, какой именно Runnable передается в
        // конструктор Thread, если start() вызывается на базовом Thread.
        // Это сложная задача, требующая анализа потока данных или упрощений.
        SootMethod runMethod = null;
        SootClass startClass = ((JInvokeStmt) invokeStmt).getInvokeExpr().getMethodRef().getDeclaringClass();
        if (!startClass.toString().equals("java.lang.Thread")) {
            return startClass.getMethod("void run()");
        } else {
            Set<AccessPath> aliases = PointerAnalysis.getAllocThreadStart(invokeStmt, contextMethod);
            Map<ForwardQuery, AbstractBoomerangResults.Context> allocSites = PointerAnalysis.allocSites;
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
                            System.out.println(assign);
                        }
                    }
                } else {
                    String lambdaVar = args.get(0).toString();
                    PointerAnalysis.getAllocThreadStart(invokeStmt, contextMethod);
                    Map<ForwardQuery, AbstractBoomerangResults.Context> allocNew = PointerAnalysis.allocSites;
                    ForwardQuery allocQuery = null;
                    if (!allocSites.isEmpty()) {
                        Unit lamU = searchLambdaInvoke(allocSites, lambdaVar);

                        JAssignStmt assign = (JAssignStmt) lamU;
                        SootClass runClass = ((JNewExpr) assign.getRightOp()).getBaseType().getSootClass();
                        runMethod = runClass.getMethod("void run()");
                    }
                }

            }
        }

        return runMethod;
    }

    public static String escapeXml(String input) {
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /*
     * public void buildTestNet() throws InvalidIDException,
     * VoidRepositoryException, OtherException, ValidationFailedException,
     * BadFileFormatException, IOException, OCLValidationFailed, UnhandledNetType {
     *
     *
     *
     * PlaceHLAPI p1 = new PlaceHLAPI("place1");
     * PlaceHLAPI p2 = new PlaceHLAPI("place2");
     * PlaceHLAPI p3 = new PlaceHLAPI("place3");
     * PlaceHLAPI p4 = new PlaceHLAPI("place4");
     *
     *
     * TransitionHLAPI t1 = new TransitionHLAPI("transistion1");
     * TransitionHLAPI t2 = new TransitionHLAPI("transistion2");
     *
     * new ArcHLAPI("a1", p1, t1, page);
     * new ArcHLAPI("a2", p2, t1, page);
     * new ArcHLAPI("a3", t1, p3, page);
     * new ArcHLAPI("a4", p4, t2, page);
     * new ArcHLAPI("a5", t2, p1, page);
     *
     *
     * p1.setContainerPageHLAPI(page);
     * p2.setContainerPageHLAPI(page);
     * p3.setContainerPageHLAPI(page);
     * t1.setContainerPageHLAPI(page);
     * t2.setContainerPageHLAPI(page);
     * p4.setContainerPageHLAPI(page);
     *
     * PTMarkingHLAPI ptMarking = new PTMarkingHLAPI(Long.valueOf(1),p4);
     * //PTMarkingHLAPI pt1Marking = new PTMarkingHLAPI(Long.valueOf(1),p2);
     *
     *
     *
     * PnmlExport pex = new PnmlExport();
     * pex.exportObject(doc, System.getenv("fpath") + "/exporttest.pnml");
     *
     * }
     */
    public void exportToPnml() throws OtherException, ValidationFailedException, BadFileFormatException, IOException,
            OCLValidationFailed, UnhandledNetType {
        PnmlExport pex = new PnmlExport();
        pex.exportObject(this.document, System.getenv("fpath") + "/exporttest.pnml");
    }

}
