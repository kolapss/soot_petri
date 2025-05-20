package com.kolaps;

import com.kolaps.model.LambdaInfoEntry;
import com.kolaps.model.LambdaMethods;
import com.kolaps.model.UMPair;
import fr.lip6.move.pnml.ptnet.Arc;
import fr.lip6.move.pnml.ptnet.hlapi.PlaceHLAPI;
import soot.Body;
import soot.PatchingChain;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.InvokeExpr;
import soot.jimple.MonitorStmt;
import soot.jimple.Stmt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.fusesource.jansi.Ansi.ansi;

public class DeadlockDetector {

    // Путь к исполняемому файлу tedd. Настройте, если он не в PATH.
    //private static final String TEDD_EXECUTABLE = "tedd";
    // Или полный путь, например: "/usr/local/bin/tedd" или "C:\\tina\\tedd.exe"

    private static final String SKIP_MARKING_TOKEN = "END_p1";

    /**
     * Вызывает tedd с указанными аргументами и возвращает его стандартный вывод.
     *
     * @param pnmlFilePath Путь к PNML файлу.
     * @return Строка, содержащая стандартный вывод tedd.
     * @throws IOException          Если возникает ошибка при выполнении команды.
     * @throws InterruptedException Если поток был прерван во время ожидания процесса.
     */
    public String runTedd(String pnmlFilePath) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Options.INSTANCE.getStringOption("tina_dir","")+"tedd");
        command.add(pnmlFilePath);
        command.add("-dead-states");
        command.add("-v");

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true); // Объединяем stdout и stderr

        System.out.println("Executing command: " + String.join(" ", command));

        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }

        int exitCode = process.waitFor();
        System.out.println("Tedd process finished with exit code: " + exitCode);

        // Tina/tedd часто выводит полезную информацию в stderr, даже при успехе (код 0)
        // или использует ненулевые коды для определенных результатов, которые не являются ошибками.
        // Поэтому мы не будем строго проверять exitCode == 0, а просто вернем вывод.
        // Если вам нужна строгая проверка, добавьте:
        // if (exitCode != 0) {
        //     throw new IOException("Tedd execution failed with exit code " + exitCode + ". Output:\n" + output.toString());
        // }

        return output.toString();
    }

    /**
     * Парсит вывод tedd и извлекает маркировки взаимных блокировок.
     * Каждая маркировка - это список имен мест.
     *
     * @param teddOutput Вывод команды tedd.
     * @return Список списков имен мест (каждый внутренний список - одна маркировка).
     */
    public List<List<String>> parseDeadStatesFromTeddOutput(String teddOutput) {
        List<List<String>> deadStateMarkings = new ArrayList<>();
        boolean inDeadStatesSection = false;

        Pattern placePattern = Pattern.compile("\\{([^}]+)\\}");
        // Паттерн для поиска SKIP_MARKING_TOKEN как отдельного слова.
        // Используем \b для границ слова, чтобы "END_p1" не совпало с "MY_END_p1_SPECIAL".
        // Pattern.quote экранирует сам токен, если он содержит спецсимволы regex.
        Pattern skipTokenPattern = Pattern.compile("\\b" + Pattern.quote(SKIP_MARKING_TOKEN) + "\\b");

        for (String line : teddOutput.split("\\r?\\n")) {
            if (line.matches("\\d+\\s+dead\\s+state\\(s\\)")) {
                inDeadStatesSection = true;
                continue;
            }
            if (line.startsWith("-------------------") || line.startsWith("------------------")) {
                continue;
            }

            if (inDeadStatesSection) {
                // Пропускаем строки, которые явно не являются маркировками
                if (line.trim().isEmpty() || line.startsWith("WARNING:") || line.startsWith("CHOICE") || line.contains("state(s)") || line.matches(".*\\d+\\.\\d+s.*")) {
                    continue;
                }

                // Создаем версию строки БЕЗ содержимого фигурных скобок,
                // чтобы проверить наличие SKIP_MARKING_TOKEN вне их.
                String lineWithoutBracedContent = line.replaceAll("\\{[^}]*\\}", " "); // Заменяем на пробел для сохранения границ слов

                if (skipTokenPattern.matcher(lineWithoutBracedContent).find()) {
                    System.out.println("Skipping marking line due to presence of '" + SKIP_MARKING_TOKEN + "' outside {}: " + line);
                    continue; // Пропускаем всю эту строку (маркировку)
                }

                Matcher matcher = placePattern.matcher(line);
                List<String> currentMarking = new ArrayList<>();
                while (matcher.find()) {
                    currentMarking.add(matcher.group(1));
                }

                if (!currentMarking.isEmpty()) {
                    deadStateMarkings.add(currentMarking);
                }
            }
        }
        return deadStateMarkings;
    }

    /**
     * Сопоставляет имена мест из маркировок с объектами Unit.
     *
     * @param deadStatePlaceNames Список маркировок (списков имен мест).
     * @param ptUnitsMap          Карта из PetriNetModeler, где ключ - Unit, значение - PlaceHLAPI.
     * @return Список списков Unit, соответствующий входным маркировкам.
     */
    public List<Map<PlaceHLAPI, UMPair>> mapPlacesToUnits(List<List<String>> deadStatePlaceNames, Map<PlaceHLAPI, UMPair> ptUnitsMap) {
        // Создаем обратную карту для быстрого поиска Unit по имени места
        Map<String, UMPair> placeNameToUnitMap = new HashMap<>();
        for (Map.Entry<PlaceHLAPI, UMPair> entry : ptUnitsMap.entrySet()) {
            placeNameToUnitMap.put(String.valueOf(entry.getKey().getId()), entry.getValue());
        }

        List<Map<PlaceHLAPI, UMPair>> resultDeadlockUnits = new ArrayList<>();

        for (List<String> placeNameMarking : deadStatePlaceNames) {
            Map<PlaceHLAPI, UMPair> unitMarking = new HashMap<>();
            for (String placeName : placeNameMarking) {
                UMPair unit = null;
                PlaceHLAPI place = null;
                for(Map.Entry<PlaceHLAPI, UMPair> entry :ptUnitsMap.entrySet()) {
                    place = entry.getKey();
                    if(place.getId().equals(PetriNetBuilder.escapeXml(placeName))) {
                        unit = entry.getValue();
                        break;
                    }
                }

                //UMPair unit = placeNameToUnitMap.get(PetriNetBuilder.escapeXml(placeName));
                if (unit != null) {
                    unitMarking.put(place,unit);
                } else {
                    System.err.println("Warning: Place name '" + placeName + "' from tedd output not found in ptUnitsMap.");
                    // Можно добавить "заглушку" Unit или проигнорировать
                    // unitMarking.add(new UnitImpl("UNKNOWN_UNIT_FOR_" + placeName));
                }
            }
            if (!unitMarking.isEmpty()) {
                resultDeadlockUnits.add(unitMarking);
            }
        }
        return resultDeadlockUnits;
    }

    private void outputDeadlocks(List<Map<PlaceHLAPI, UMPair>> deadStatePlaceNames)
    {
        if (deadStatePlaceNames.isEmpty()) {
            System.out.println("Взаимные блокировки не обнаружены");
            return;
        }
        System.out.println(ansi().fgRgb(255,0,0).a("Найдено потенциальных взаимных блокировок: "+deadStatePlaceNames.size()).reset());
        int i=1;
        for(Map<PlaceHLAPI, UMPair> dState : deadStatePlaceNames) {
            System.out.println(ansi().fgRgb(255,0,0).a("Блокировка №"+String.valueOf(i)).reset());
            for(Map.Entry<PlaceHLAPI, UMPair> entry : dState.entrySet()) {
                PlaceHLAPI place = entry.getKey();
                List<Arc> outArcs = place.getOutArcs();
                boolean isPreEndPlace = false;
                for(Arc arc : outArcs) {
                    if(arc.getTarget().getId().equals("END_p1"))
                    {
                        isPreEndPlace = true;
                        break;
                    }
                }
                if(isPreEndPlace) {break;}
                UMPair p = entry.getValue();
                SootMethod threadMethod = p.getSootMethod();
                LambdaInfoEntry lm = findLambdasCallingMethod(threadMethod);
                if (p.getUnit() instanceof MonitorStmt)
                {
                    System.out.println("    Поток ожидает ресурс "+ ansi().fgRgb(17,255,0).a(((MonitorStmt) p.getUnit()).getOp()));
                    System.out.print(ansi().fgRgb(218,196,0).a("    Место старта потока: ").reset());
                    System.out.print(lm.getInvokeStmt().toString() + ansi().fgRgb(218,196,0).a(" в методе ").reset()+lm.getInvokeMethod().toString()+"\n");
                    System.out.println("");
                    printUnitContext(p.getSootMethod(),p.getUnit());
                    System.out.println("");
                }
            }
            i++;
        }

    }

    /**
     * Находит указанный юнит в теле метода и выводит его контекст:
     * 8 предыдущих юнитов, сам юнит (выделенный цветом) и 8 следующих юнитов.
     * Все юниты выводятся с отступом в 2 таба.
     *
     * @param method SootMethod, в котором производится поиск.
     * @param targetUnit Искомый Unit.
     */
    public static void printUnitContext(SootMethod method, Unit targetUnit) {

        String indent = "\t\t"; // Два таба для отступа



        Body body = method.getActiveBody();
        PatchingChain<Unit> units = body.getUnits(); // Получаем все юниты в порядке их следования

        // Преобразуем PatchingChain в List для удобного доступа по индексу
        List<Unit> unitList = new ArrayList<>(units);

        int targetIndex = -1;
        for (int i = 0; i < unitList.size(); i++) {
            // Сравниваем объекты Unit напрямую (по ссылке),
            // так как targetUnit должен быть одним из юнитов из body.getUnits()
            if (unitList.get(i) == targetUnit) {
                targetIndex = i;
                break;
            }
        }

        if (targetIndex == -1) {
            System.out.println(indent + "Указанный юнит не найден в методе: " + method.getSignature());
            // Можно также вывести сам targetUnit.toString() для отладки
            System.out.println(indent + "Искомый юнит: " + targetUnit.toString());
            return;
        }

        System.out.println(indent + "Контекст для юнита в методе: " + method.getSignature());
        System.out.println(indent + "--------------------------------------------------");

        int contextSize = 8;

        // Вывод предыдущих 8 юнитов
        int startIndex = Math.max(0, targetIndex - contextSize);
        for (int i = startIndex; i < targetIndex; i++) {
            System.out.println(indent + unitList.get(i).toString());
        }

        // Вывод целевого юнита с подсветкой
        // Используем статический импорт org.fusesource.jansi.Ansi.ansi
        System.out.println(indent + ansi().fgRgb(17, 255, 0).a(targetUnit.toString()).reset());


        // Вывод следующих 8 юнитов
        // +1, так как targetIndex уже выведен
        int endIndex = Math.min(unitList.size(), targetIndex + 1 + contextSize);
        for (int i = targetIndex + 1; i < endIndex; i++) {
            System.out.println(indent + unitList.get(i).toString());
        }
        System.out.println(indent + "--------------------------------------------------");

    }

    /**
     * Находит LambdaInfoEntry, чей runMethod вызывает указанный targetMethod.
     *
     * @param targetMethod Метод Soot, вызов которого мы ищем.
     * @return Список LambdaInfoEntry, удовлетворяющих условию.
     */
    public LambdaInfoEntry findLambdasCallingMethod(SootMethod targetMethod) {
        LambdaInfoEntry foundEntry = null;
        List<LambdaInfoEntry> allEntries = LambdaMethods.getAllEntries();

        if (targetMethod == null) {
            System.err.println("Предупреждение: targetMethod для поиска равен null. Поиск не будет выполнен.");
            return null; // Возвращаем пустой список, если целевой метод null
        }

        for (LambdaInfoEntry entry : allEntries) {
            SootMethod runMethod = entry.getRunMethod();

            if (runMethod == null) {
                // Пропускаем, если у записи нет runMethod
                continue;
            }

            // Важно: убедитесь, что для runMethod загружено тело (body)
            // Это может потребовать определенных настроек Soot перед анализом.
            // Если Soot не был настроен на загрузку тел методов (например, если это библиотечный метод
            // без исходников или класс не был помечен как application class), то hasActiveBody() вернет false.
            if (runMethod.hasActiveBody()) {
                Body body = runMethod.getActiveBody();
                for (Unit unit : body.getUnits()) {
                    // Юнит должен быть инструкцией (Stmt)
                    if (unit instanceof Stmt) {
                        Stmt stmt = (Stmt) unit;
                        // Проверяем, содержит ли инструкция вызов метода
                        if (stmt.containsInvokeExpr()) {
                            InvokeExpr invokeExpr = stmt.getInvokeExpr();
                            SootMethod calledMethod = invokeExpr.getMethod();

                            // Сравниваем вызванный метод с целевым методом
                            // Сравнение по ссылке (==) обычно корректно для объектов SootMethod,
                            // так как Soot стремится к уникальности этих объектов.
                            // Для большей надежности можно сравнивать по сигнатуре, если есть сомнения.
                            if (calledMethod.equals(targetMethod)) {
                                foundEntry=entry;
                                // Если нашли вызов, можно прекратить поиск в текущем runMethod
                                // и перейти к следующему LambdaInfoEntry, если нам нужен только факт вызова.
                                // Если нужно найти ВСЕ вызовы в одном runMethod или если
                                // одна LambdaInfoEntry может быть добавлена только один раз,
                                // то этот break важен.
                                break;
                            }
                        }
                    }
                }
            } else {
                // Вывести предупреждение, если тело метода отсутствует, но ожидалось
                System.err.println("Предупреждение: Метод " + runMethod.getSignature() + " не имеет активного тела (active body). Пропускается.");
            }
        }
        return foundEntry;
    }

    public void run() {
        Map<PlaceHLAPI, UMPair> ptUnits = PetriNetModeler.getPtUnits();

         //2. Имитируем вызов tedd (или вызываем реально)
         //Для реального вызова, укажите путь к вашему PNML файлу
         String pnmlFilePath = Options.INSTANCE.getStringOption("app.pnml_file","");
         String teddOutput;
         try {
             teddOutput = runTedd(pnmlFilePath);
             System.out.println("---- Tedd Output ----\n" + teddOutput);
         } catch (IOException | InterruptedException e) {
             System.err.println("Error running tedd: " + e.getMessage());
             e.printStackTrace();
             return;
         }


        // 3. Парсим вывод tedd
        List<List<String>> deadStatePlaceNames = parseDeadStatesFromTeddOutput(teddOutput);
        if(deadStatePlaceNames.isEmpty()) {return;}

        // 4. Сопоставляем имена мест с Unit'ами
        List<Map<PlaceHLAPI, UMPair>> deadlockUnits = mapPlacesToUnits(deadStatePlaceNames, ptUnits);
        outputDeadlocks(deadlockUnits);


    }
}

