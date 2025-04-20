package com.kolaps;

import boomerang.ForwardQuery;
import boomerang.results.AbstractBoomerangResults;
import boomerang.scene.AllocVal;
import boomerang.scene.jimple.JimpleMethod;
import boomerang.scene.jimple.JimpleVal;
import boomerang.util.AccessPath;
import fr.lip6.move.pnml.framework.general.PnmlExport;
import fr.lip6.move.pnml.framework.utils.ModelRepository;
import fr.lip6.move.pnml.framework.utils.exception.*;
import fr.lip6.move.pnml.ptnet.hlapi.*;
import javafx.util.Pair;
import soot.*;
import soot.jimple.*;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JInvokeStmt;
import soot.jimple.internal.JNewExpr;
import soot.jimple.internal.JStaticInvokeExpr;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.UnitGraph;

import java.io.IOException;
import java.util.*;

import static com.kolaps.PetriNetModeler.*;

public class PetriNetBuilder {
    private PetriNetDocHLAPI document;
    private PetriNetHLAPI rootPetriNet;
    private Map<SootMethod, PageHLAPI> methodPages;
    private Map<String, PlaceHLAPI> lockPlaces; // Ключ - Value из Soot (нужен хороший способ идентификации)
    // Кэш мест, представляющих состояние *ПОСЛЕ* выполнения Unit на определенной странице
    private Map<Pair<Unit, PageHLAPI>, PlaceHLAPI> unitPlaceMap;
    private Queue<TraversalState> worklist;
    private Set<Pair<Unit, PageHLAPI>> visitedUnitsOnPage; // Отслеживание посещенных Unit на странице
    private Map<SootMethod, UnitGraph> graphCache; // Кэш для CFG

    PetriNetBuilder() throws InvalidIDException, VoidRepositoryException, OtherException, ValidationFailedException, BadFileFormatException, IOException, OCLValidationFailed, UnhandledNetType {
        ModelRepository.getInstance().createDocumentWorkspace("void");
        this.document = new PetriNetDocHLAPI();
        this.rootPetriNet = new PetriNetHLAPI("RootNet", PNTypeHLAPI.COREMODEL, new NameHLAPI("DeadlockFind"), this.document);
        this.methodPages = new HashMap<>();


        this.lockPlaces = new HashMap<>();
        this.unitPlaceMap = new HashMap<>();
        this.worklist = new LinkedList<>();
        this.visitedUnitsOnPage = new HashSet<>();
        this.graphCache = new HashMap<>();


    }

    private PageHLAPI createPage(String baseName, PetriNetHLAPI container) {
        String id = "page_" + baseName.replaceAll("[^a-zA-Z0-9_]", "_") + "_" + methodPages.size();
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
        try {

            SootMethod mainMethod = mainClass.getMethodByName("main"); // Или getMethodByNameUnsafe
            if (mainMethod == null || !mainMethod.isStatic() || !mainMethod.isConcrete()) {
                throw new RuntimeException("Cannot find valid main method");
            }


            // Создаем главную страницу для main метода
            PageHLAPI mainPage = createPage("main_thread", rootPetriNet);
            methodPages.put(mainMethod, mainPage);

            // Создаем начальное место для main
            PlaceHLAPI startMainPlace = createPlace("start_main", mainPage);
            // Устанавливаем начальную маркировку для точки входа
            new PTMarkingHLAPI(Long.valueOf(1), startMainPlace);
            System.out.println("Setting initial marking 1 for Start Place: " + startMainPlace.getId());


            Unit entryUnit = getGraph(mainMethod).getHeads().get(0); // Получаем точку входа CFG

            // Добавляем начальное состояние в worklist
            worklist.add(new TraversalState(entryUnit, startMainPlace, mainPage));

            // Запускаем цикл обхода
            processWorklist();

            return rootPetriNet;

        } catch (Exception e) { // Ловим более общие исключения на этапе разработки
            System.err.println("Error during Petri net building:");
            e.printStackTrace();
            throw new RuntimeException("Failed to build Petri net", e);
        }
    }

    private PlaceHLAPI getOrCreateLockPlace(Value lockRef, Unit monitorStmtUnit, SootMethod contextMethod) {
        // --- КРИТИЧЕСКИЙ МОМЕНТ: Идентификация монитора ---
        // Простой (неточный) вариант: использовать тип объекта
        // Value lockRef -> Type lockType = lockRef.getType();
        // Более сложный: анализ аллокации, псевдонимов (aliasing) - выходит за рамки простого примера
        // Пока используем сам Value как ключ (может не работать правильно, если Value не имеет стабильного equals/hashCode)
        // или его строковое представление. НУЖНА НАДЕЖНАЯ СТРАТЕГИЯ ИДЕНТИФИКАЦИИ!

        String strAllocValue = PointerAnalysis.getAllocSite(monitorStmtUnit, contextMethod);
        return lockPlaces.computeIfAbsent(strAllocValue, key -> {
            System.out.println("Creating new Lock Place for identifier: " + key);
            String placeName = "Lock_" + key;
            placeName = escapeXml(placeName);
            PlaceHLAPI lockPlace = createPlace(placeName, methodPages.get(contextMethod));
            // Установить начальную маркировку = 1 (блокировка свободна)
            new PTMarkingHLAPI(Long.valueOf(1),lockPlace);
            System.out.println("Setting initial marking 1 for Lock Place: " + lockPlace.getId() + " (ID: " + key + ")");
            return lockPlace;
        });


        /*return lockPlaces.computeIfAbsent(lockRef, k -> {
            PlaceHLAPI lockPlace = createPlace("Lock_" + k.toString().replaceAll("[^a-zA-Z0-9_]", "_"), rootPetriNet); // Блокировки - глобальные ресурсы (на корневом уровне)
            // Установить начальную маркировку = 1 (блокировка свободна)
            // lockPlace.setInitialMarking(new MarkingHLAPI(lockPlace, 1.0)); // Пример API
            System.out.println("Setting initial marking 1 for Lock Place: " + lockPlace.getName().getText());
            return lockPlace;
        });*/
    }


    private void processWorklist() {
        while (!worklist.isEmpty()) {
            TraversalState state = worklist.poll();
            Unit unit = state.currentUnit;
            PlaceHLAPI placeBeforeUnit = state.placeBeforeUnit;
            PageHLAPI page = state.currentPage;

            // Ключ для отслеживания посещенных и кэширования
            Pair<Unit, PageHLAPI> visitedKey = new Pair<>(unit, page);

            // Проверяем, обрабатывали ли мы уже *выход* из этого Unit на этой странице
            if (unitPlaceMap.containsKey(visitedKey)) {
                PlaceHLAPI existingPlaceAfterUnit = unitPlaceMap.get(visitedKey);
                // Этот Unit уже обработан, просто соединяем предыдущее место с местом *после* Unit
                TransitionHLAPI mergeTransition = createTransition("merge_to_" + unit.toString().hashCode(), page);
                createArc(placeBeforeUnit, mergeTransition, page);
                createArc(mergeTransition, existingPlaceAfterUnit, page);
                System.out.println("Merging path at Unit: " + unit + " on Page " + page.getId());
                continue; // Переходим к следующему элементу в worklist
            }

            // Проверяем, не зациклились ли мы (вошли в Unit, который уже в процессе обработки на этой странице)
            if (visitedUnitsOnPage.contains(visitedKey)) {
                // Обнаружен цикл в CFG, который мы еще не замкнули в сети Петри.
                // Это может быть нормально, если обработка еще идет по другому пути.
                // Или это может быть признак проблемы, если мы не вышли из него правильно.
                // Пока просто пропустим, чтобы избежать бесконечного цикла в ПОСТРОИТЕЛЕ.
                // Замыкание цикла произойдет, когда мы дойдем до уже обработанного unit (см. блок выше).
                System.out.println("Cycle detected (re-entering unit before processing finished): " + unit + " on Page " + page.getId());
                continue;
            }
            visitedUnitsOnPage.add(visitedKey); // Помечаем, что мы НАЧАЛИ обработку этого узла

            System.out.println("Processing Unit: [" + page.getId() + "] " + unit.getClass().getSimpleName() + ": " + unit);

            // --- Обработка разных типов инструкций ---
            handleUnit(unit, placeBeforeUnit, page, visitedKey);

            // После обработки узла, удаляем его из 'активно посещаемых'
            visitedUnitsOnPage.remove(visitedKey); // Это нужно, если мы хотим разрешить повторный вход после ПОЛНОЙ обработки
            // Но для предотвращения циклов построителя, лучше оставить в visitedUnitsOnPage навсегда.
            // Кэш unitPlaceMap гарантирует, что мы не будем дублировать структуру.
        }
        System.out.println("Worklist processed. Petri net structure built.");
    }

    private void handleGenericUnit(Unit unit, PlaceHLAPI placeBeforeUnit, PageHLAPI page, UnitGraph graph){
        // Простая инструкция: Place -> Transition -> Place
        String unitName = unit.getClass().getSimpleName() + "_" + unit.toString().hashCode();
        TransitionHLAPI genericTransition = createTransition(unitName, page);
        createArc(placeBeforeUnit, genericTransition, page);

        PlaceHLAPI placeAfterUnit = createPlace("after_" + unitName, page);
        createArc(genericTransition, placeAfterUnit, page);

        // Кэшируем место *после* выполнения unit
        Pair<Unit, PageHLAPI> visitedKey = new Pair<>(unit, page);
        unitPlaceMap.put(visitedKey, placeAfterUnit);

        // Добавляем всех преемников в worklist
        addSuccessorsToWorklist(graph, unit, placeAfterUnit, page);
    }


    // Вспомогательный метод: получить метод Soot для данной страницы
    private SootMethod getMethodForPage(PageHLAPI page) {
        return methodPages.entrySet().stream()
                .filter(entry -> entry.getValue().equals(page))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No SootMethod found for PageHLAPI: " + page.getId()));
    }

    // Получить CFG для метода (с кэшированием)
    private UnitGraph getGraph(SootMethod method) {
        return new ExceptionalUnitGraph(method.retrieveActiveBody());
    }

    //--- Логика обработки конкретного Unit ---
    private void handleUnit(Unit unit, PlaceHLAPI placeBeforeUnit, PageHLAPI page, Pair<Unit, PageHLAPI> visitedKey) {
        UnitGraph graph = getGraph(getMethodForPage(page)); // Получаем CFG для текущего метода/страницы
        PlaceHLAPI placeAfterUnit; // Место *после* выполнения unit

        // === Обработка специальных инструкций ===

        if (unit instanceof EnterMonitorStmt) {
            EnterMonitorStmt monitorStmt = (EnterMonitorStmt) unit;
            Value lockRef = monitorStmt.getOp();
            PlaceHLAPI lockPlace = getOrCreateLockPlace(lockRef, unit, getMethodForPage(page));

            TransitionHLAPI acquireTransition = createTransition("acquire_" + lockRef.toString().hashCode(), page);
            createArc(placeBeforeUnit, acquireTransition, page); // Дуга от предыдущего состояния
            createArc(lockPlace, acquireTransition, page);       // Дуга от ресурса-блокировки (требует токен)

            placeAfterUnit = createPlace("in_monitor_" + unit.toString().hashCode(), page);
            createArc(acquireTransition, placeAfterUnit, page);  // Дуга к состоянию "внутри монитора"

            // Добавить преемников в worklist
            addSuccessorsToWorklist(graph, unit, placeAfterUnit, page);

        } else if (unit instanceof ExitMonitorStmt) {
            ExitMonitorStmt monitorStmt = (ExitMonitorStmt) unit;
            Value lockRef = monitorStmt.getOp();
            PlaceHLAPI lockPlace = getOrCreateLockPlace(lockRef, unit, getMethodForPage(page));

            TransitionHLAPI releaseTransition = createTransition("release_" + lockRef.toString().hashCode(), page);
            createArc(placeBeforeUnit, releaseTransition, page); // Дуга от предыдущего состояния (внутри монитора)

            placeAfterUnit = createPlace("after_monitor_" + unit.toString().hashCode(), page);
            createArc(releaseTransition, placeAfterUnit, page);  // Дуга к состоянию "после монитора"
            createArc(releaseTransition, lockPlace, page);       // Дуга к ресурсу-блокировке (возвращает токен)

            // Добавить преемников в worklist
            addSuccessorsToWorklist(graph, unit, placeAfterUnit, page);

        } else if (unit instanceof InvokeStmt && isThreadStartCall((InvokeStmt) unit)) {
            InvokeStmt invokeStmt = (InvokeStmt) unit;
            SootMethod runMethod = findRunMethodForStartCall(unit,getMethodForPage(page)); // Нужна реализация этого метода!

            if (runMethod != null && runMethod.isConcrete()) {
                // Создаем новую страницу для потока, если ее еще нет
                PageHLAPI threadPage = methodPages.computeIfAbsent(runMethod, m -> createPage(m.getName() + "_thread", rootPetriNet));

                // Начальное место для нового потока (маркировка 0 по умолчанию)
                PlaceHLAPI startRunPlace = createPlace("start_" + runMethod.getName(), threadPage);

                // Переход, моделирующий вызов start() в родительском потоке
                TransitionHLAPI startCallTransition = createTransition("call_start_" + runMethod.getName(), page);
                createArc(placeBeforeUnit, startCallTransition, page); // От предыдущего места родителя

                // Место после вызова start() в родительском потоке
                placeAfterUnit = createPlace("after_start_" + runMethod.getName(), page);
                createArc(startCallTransition, placeAfterUnit, page); // К следующему месту родителя

                // Дуга от перехода start() к начальному месту нового потока (активация)
                createArc(startCallTransition, startRunPlace, page); // Активируем новый поток

                // Добавляем точку входа нового потока в worklist
                try {
                    UnitGraph runGraph = getGraph(runMethod);
                    Unit runEntryUnit = runGraph.getHeads().get(0);
                    worklist.add(new TraversalState(runEntryUnit, startRunPlace, threadPage));
                    System.out.println("Added start state for new thread: " + runMethod.getName());
                } catch (Exception e) {
                    System.err.println("Could not process run method " + runMethod.getSignature() + ": " + e.getMessage());
                    // Возможно, стоит просто проигнорировать запуск потока, если run() недоступен/некорректен
                }


                // Добавляем преемника в РОДИТЕЛЬСКОМ потоке
                addSuccessorsToWorklist(graph, unit, placeAfterUnit, page);

            } else {
                System.err.println("Warning: Could not find or process run method for Thread.start call: " + invokeStmt);
                // Обработка как обычный вызов, если не удалось найти run()
                handleGenericUnit(unit, placeBeforeUnit, page, graph);
            }


        } else if (unit instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) unit;
            Unit targetUnit = ifStmt.getTarget(); // Цель при true
            List<Unit> fallThroughUnits = graph.getSuccsOf(unit); // Все преемники
            // Находим преемника для false (тот, который не targetUnit)
            Unit fallThroughUnit = null;
            for (Unit u : fallThroughUnits) {
                if (!u.equals(targetUnit)) {
                    fallThroughUnit = u;
                    break;
                }
            }
            // Fallthrough может быть тем же, что и target если это `if(..) goto L; L:`
            if (fallThroughUnit == null && !fallThroughUnits.isEmpty() && fallThroughUnits.get(0).equals(targetUnit)) {
                fallThroughUnit = targetUnit; // Особый случай
            }

            if (fallThroughUnit == null) {
                System.err.println("Warning: Could not determine fall-through unit for: " + unit);
                // Возможно, конец блока или исключение? Игнорируем ветку false для простоты
            }

            // Ветка TRUE
            TransitionHLAPI trueTransition = createTransition("if_true_" + unit.toString().hashCode(), page);
            createArc(placeBeforeUnit, trueTransition, page);
            PlaceHLAPI trueBranchPlace = createPlace("branch_true_" + unit.toString().hashCode(), page);
            createArc(trueTransition, trueBranchPlace, page);
            worklist.add(new TraversalState(targetUnit, trueBranchPlace, page));

            // Ветка FALSE (fall-through)
            if (fallThroughUnit != null) {
                TransitionHLAPI falseTransition = createTransition("if_false_" + unit.toString().hashCode(), page);
                createArc(placeBeforeUnit, falseTransition, page);
                PlaceHLAPI falseBranchPlace = createPlace("branch_false_" + unit.toString().hashCode(), page);
                createArc(falseTransition, falseBranchPlace, page);
                worklist.add(new TraversalState(fallThroughUnit, falseBranchPlace, page));
            }

            // Важно: НЕ кэшируем placeAfterUnit для IfStmt напрямую,
            // т.к. у него нет единого "места после". Кэширование произойдет
            // для targetUnit и fallThroughUnit, когда они будут обработаны.
            // Поэтому здесь нет вызова unitPlaceMap.put(visitedKey, ...)

        } else if (unit instanceof GotoStmt) {
            GotoStmt gotoStmt = (GotoStmt) unit;
            Unit targetUnit = gotoStmt.getTarget();

            TransitionHLAPI gotoTransition = createTransition("goto_" + unit.toString().hashCode(), page);
            createArc(placeBeforeUnit, gotoTransition, page);
            // Не создаем нового места *после* goto, т.к. управление безусловно
            // передается в targetUnit. Мы свяжемся с местом *перед* targetUnit.
            // Поэтому сразу добавляем targetUnit в worklist с placeBeforeUnit от goto.
            // НЕТ! Это неверно. Переход должен куда-то вести.
            // Правильно: создать место после goto и добавить преемника.
            placeAfterUnit = createPlace("after_goto_" + unit.toString().hashCode(), page);
            createArc(gotoTransition, placeAfterUnit, page);

            // Кэшируем место после goto
            unitPlaceMap.put(visitedKey, placeAfterUnit);

            // Добавляем реального преемника (target)
            addSuccessorsToWorklist(graph, unit, placeAfterUnit, page); // Должен добавить только targetUnit

        } else if (unit instanceof LookupSwitchStmt || unit instanceof TableSwitchStmt) {
            // Обработка switch (похоже на if, но больше веток)
            SwitchStmt switchStmt = (SwitchStmt) unit;
            List<Unit> targets = switchStmt.getTargets();
            Unit defaultTarget = switchStmt.getDefaultTarget();

            // Переход и место для default
            TransitionHLAPI defaultTransition = createTransition("switch_default_" + unit.toString().hashCode(), page);
            createArc(placeBeforeUnit, defaultTransition, page);
            PlaceHLAPI defaultPlace = createPlace("switch_default_target_" + unit.toString().hashCode(), page);
            createArc(defaultTransition, defaultPlace, page);
            worklist.add(new TraversalState(defaultTarget, defaultPlace, page));

            // Переходы и места для каждого case
            for (int i = 0; i < targets.size(); i++) {
                Unit caseTarget = targets.get(i);
                // Значение case можно получить из switchStmt.getLookupValues().get(i) для LookupSwitch
                TransitionHLAPI caseTransition = createTransition("switch_case_" + i + "_" + unit.toString().hashCode(), page);
                createArc(placeBeforeUnit, caseTransition, page);
                PlaceHLAPI casePlace = createPlace("switch_case_target_" + i + "_" + unit.toString().hashCode(), page);
                createArc(caseTransition, casePlace, page);
                worklist.add(new TraversalState(caseTarget, casePlace, page));
            }
            // Для switch также не кэшируем единое placeAfterUnit

        } else if (unit instanceof ReturnStmt || unit instanceof RetStmt || unit instanceof ThrowStmt) {
            // Конец пути выполнения (нормальный, исключение или возврат из подпрограммы)
            TransitionHLAPI endTransition = createTransition("end_path_" + unit.getClass().getSimpleName() + "_" + unit.toString().hashCode(), page);
            createArc(placeBeforeUnit, endTransition, page);
            PlaceHLAPI endPlace = createPlace("terminal_" + unit.getClass().getSimpleName() + "_" + unit.toString().hashCode(), page);
            createArc(endTransition, endPlace, page);
            // Нет преемников для добавления в worklist
            // Кэшируем конечное место
            unitPlaceMap.put(visitedKey, endPlace);
            System.out.println("Terminal Unit encountered: " + unit);

        }
        // === Обработка wait()/notify() - ПРОПУЩЕНО ДЛЯ ПРОСТОТЫ ===
        // else if (isWaitCall(unit)) { ... }
        // else if (isNotifyCall(unit)) { ... }

        else {
            // === Обработка прочих (generic) инструкций ===
            handleGenericUnit(unit, placeBeforeUnit, page, graph);
        }

        // Кэшируем созданное место *после* unit (если оно было создано и не является ветвлением)
        // Это делается внутри специфичных обработчиков или в handleGenericUnit
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

    private SootMethod findRunMethodForStartCall(Unit invokeStmt, SootMethod contextMethod) {
        // 1. Получить объект, на котором вызывается start(): invokeStmt.getInvokeExpr().getUseBoxes() -> Value base
        // 2. Определить тип этого объекта: base.getType() -> Type type
        // 3. Если тип - RefType, получить SootClass: ((RefType) type).getSootClass()
        // 4. Найти метод run() в этом классе или его суперклассах/интерфейсах: sootClass.getMethodUnsafe("run", Collections.emptyList(), VoidType.v())
        //    ИЛИ sootClass.getMethodByNameUnsafe("run") и проверить сигнатуру.
        // 5. Это может потребовать анализа, какой именно Runnable передается в конструктор Thread, если start() вызывается на базовом Thread.
        // Это сложная задача, требующая анализа потока данных или упрощений.
        SootMethod runMethod = null;
        SootClass startClass = ((JInvokeStmt) invokeStmt).getInvokeExpr().getMethodRef().getDeclaringClass();
        if(!startClass.toString().equals("java.lang.Thread"))
        {
            return startClass.getMethod("void run()");
        }
        else
        {
            Set<AccessPath> aliases = PointerAnalysis.getAllocThreadStart(invokeStmt,contextMethod);
            aliases.stream().forEach( value -> {
                value.getBase();
            });
            Map<ForwardQuery, AbstractBoomerangResults.Context> allocSites = PointerAnalysis.allocSites;
            ForwardQuery firstQuery = null;
            if (!allocSites.isEmpty()) {
                firstQuery = allocSites.entrySet().iterator().next().getKey();
                JimpleVal localValue = ((JimpleVal)((AllocVal)firstQuery.var()).getDelegate());
                SootMethod allocMethod= ((JimpleMethod)localValue.m()).getDelegate();
                String allocVal = localValue.getVariableName();
                UnitGraph allocGraph = new ExceptionalUnitGraph(allocMethod.retrieveActiveBody());
                for(Unit u:allocGraph)
                {
                    if(u instanceof JInvokeStmt && u.toString().contains(allocVal + ".") && u.toString().contains("void <init>"))
                    {
                        List<Value> args = ((JInvokeStmt) u).getInvokeExpr().getArgs();
                        if(args.size()==1) //Если лямбда
                        {
                            String lambdaVar = args.get(0).toString();
                            for(Unit lamU:allocGraph)
                            {
                                if(lamU.toString().startsWith(lambdaVar+" =") && lamU.toString().contains("lambda") && lamU.toString().contains("java.lang.Runnable"))
                                {
                                    JAssignStmt assign = (JAssignStmt) lamU;
                                    SootClass lamClass = ((JStaticInvokeExpr)assign.getRightOpBox().getValue()).getMethodRef().getDeclaringClass();
                                    runMethod = lamClass.getMethod("void run()");
                                    System.out.println(assign);
                                }
                            }
                        }
                        else
                        {
                            String lambdaVar = args.get(0).toString();
                            PointerAnalysis.getAllocThreadStart(invokeStmt, contextMethod);
                            Map<ForwardQuery, AbstractBoomerangResults.Context> allocNew = PointerAnalysis.allocSites;
                            ForwardQuery allocQuery = null;
                            if (!allocSites.isEmpty()) {
                                firstQuery = allocSites.entrySet().iterator().next().getKey();
                                JimpleVal locallValue = ((JimpleVal)((AllocVal)firstQuery.var()).getDelegate());
                                SootMethod alloccMethod= ((JimpleMethod)locallValue.m()).getDelegate();
                                String alloccVal = locallValue.getVariableName();
                                UnitGraph alloccGraph = new ExceptionalUnitGraph(alloccMethod.retrieveActiveBody());
                                for(Unit lamU:alloccGraph)
                                {
                                    if(lamU.toString().startsWith(lambdaVar+" = "))
                                    {
                                        JAssignStmt assign = (JAssignStmt) lamU;
                                        SootClass runClass = ((JNewExpr)assign.getRightOp()).getBaseType().getSootClass();
                                        runMethod = runClass.getMethod("void run()");
                                    }
                                }
                            }
                        }
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



    private void addSuccessorsToWorklist(UnitGraph graph, Unit unit, PlaceHLAPI placeAfterUnit, PageHLAPI page) {
        try{
            List<Unit> successors = graph.getSuccsOf(unit);
            if(successors.isEmpty()){
                System.out.println("No successors for: [" + page.getId() + "] " + unit);
            } else {
                for (Unit succ : successors) {
                    System.out.println("Adding successor to worklist: [" + page.getId() + "] " + succ + " after place " + placeAfterUnit.getId());
                    worklist.add(new TraversalState(succ, placeAfterUnit, page));
                }
            }


        } catch(Exception e){
            System.err.println("Error getting successors for: " + unit + " in graph of " + getMethodForPage(page).getSignature() + " - " + e.getMessage());
            // Обработать ошибку (например, если граф некорректен)
        }

    }

    /*public void buildTestNet() throws InvalidIDException, VoidRepositoryException, OtherException, ValidationFailedException, BadFileFormatException, IOException, OCLValidationFailed, UnhandledNetType {



        PlaceHLAPI p1 = new PlaceHLAPI("place1");
        PlaceHLAPI p2 = new PlaceHLAPI("place2");
        PlaceHLAPI p3 = new PlaceHLAPI("place3");
        PlaceHLAPI p4 = new PlaceHLAPI("place4");


        TransitionHLAPI t1 = new TransitionHLAPI("transistion1");
        TransitionHLAPI t2 = new TransitionHLAPI("transistion2");

        new ArcHLAPI("a1", p1, t1, page);
        new ArcHLAPI("a2", p2, t1, page);
        new ArcHLAPI("a3", t1, p3, page);
        new ArcHLAPI("a4", p4, t2, page);
        new ArcHLAPI("a5", t2, p1, page);


        p1.setContainerPageHLAPI(page);
        p2.setContainerPageHLAPI(page);
        p3.setContainerPageHLAPI(page);
        t1.setContainerPageHLAPI(page);
        t2.setContainerPageHLAPI(page);
        p4.setContainerPageHLAPI(page);

        PTMarkingHLAPI ptMarking = new PTMarkingHLAPI(Long.valueOf(1),p4);
        //PTMarkingHLAPI pt1Marking = new PTMarkingHLAPI(Long.valueOf(1),p2);



        PnmlExport pex = new PnmlExport();
        pex.exportObject(doc, System.getenv("fpath") + "/exporttest.pnml");

    }*/
    public void exportToPnml() throws OtherException, ValidationFailedException, BadFileFormatException, IOException, OCLValidationFailed, UnhandledNetType {
        PnmlExport pex = new PnmlExport();
        pex.exportObject(this.document, System.getenv("fpath") + "/exporttest.pnml");
    }


}
