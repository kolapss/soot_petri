package com.kolaps;

import fr.lip6.move.pnml.ptnet.hlapi.PlaceHLAPI;
import soot.Unit;

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
        System.out.println("\n---- Parsed Dead State Place Names ----");
        deadStatePlaceNames.forEach(marking ->
                System.out.println(marking.stream().collect(Collectors.joining(", ")))
        );

        // 4. Сопоставляем имена мест с Unit'ами
        List<Map<PlaceHLAPI, UMPair>> deadlockUnits = mapPlacesToUnits(deadStatePlaceNames, ptUnits);
        System.out.println("\n---- Deadlock Units ----");
        /*for (int i = 0; i < deadlockUnits.size(); i++) {
            System.out.println("Deadlock Marking " + (i + 1) + ":");
            for (Unit unit : deadlockUnits.get(i)) {
                System.out.println("  - " + unit.getRepresentation() + " (from place: " +
                        ptUnits.entrySet().stream()
                                .filter(entry -> entry.getKey().equals(unit))
                                .map(entry -> entry.getValue().getName())
                                .findFirst().orElse("N/A") + ")");
            }
        }*/

    }
}

