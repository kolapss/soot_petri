package com.kolaps;

import fr.lip6.move.pnml.framework.general.PNType;
import fr.lip6.move.pnml.framework.hlapi.HLAPIRootClass;
import fr.lip6.move.pnml.framework.utils.ModelRepository;
import fr.lip6.move.pnml.framework.utils.PNMLUtils;
import fr.lip6.move.pnml.framework.utils.exception.*;
import fr.lip6.move.pnml.ptnet.hlapi.PetriNetDocHLAPI;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class Main {


    /*static {
        try {
            try {
                pnml = new PetriNetBuilder();
            } catch (OtherException | ValidationFailedException | BadFileFormatException | IOException |
                     OCLValidationFailed | UnhandledNetType e) {
                throw new RuntimeException(e);
            }
        } catch (InvalidIDException | VoidRepositoryException e) {
            throw new RuntimeException(e);
        }
    }*/
    public static void main(String[] args) {

        //PetriNetDocHLAPI pt = importPNML();
        String jarFilePath = null;
        String jdkPath = System.getProperty("java.home");
        System.out.println("JDK path: " + jdkPath);
        // Парсим аргументы
        for (int i = 0; i < args.length; i++) {
            if ("-f".equals(args[i]) && i + 1 < args.length) {
                jarFilePath = args[i + 1];
                break;
            }
        }

        // Проверяем, передан ли файл
        if (jarFilePath == null) {
            System.out.println("Ошибка: укажите путь к JAR-файлу с ключом -f");
            System.out.println("Пример: java -jar stal.jar -f path/to/file.jar");
            System.exit(1);
        }
        PetriNetBuilder builder;
        try {
            builder = new PetriNetBuilder();
        } catch (InvalidIDException | VoidRepositoryException | OtherException | ValidationFailedException |
                 BadFileFormatException | IOException | OCLValidationFailed | UnhandledNetType e) {
            throw new RuntimeException(e);
        }
        BytecodeParser.parseProgram(jarFilePath, builder);
        Options.INSTANCE.setOption("app.pnml_file", System.getenv("fpath") + "\\exporttest.pnml");
        try {
            builder.exportToPnml();
        } catch (OtherException | ValidationFailedException | BadFileFormatException | IOException |
                 OCLValidationFailed | UnhandledNetType e) {
            throw new RuntimeException(e);
        }
        try {
            PTExtension.modifyPnml();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        DeadlockDetector detector = new DeadlockDetector();
        detector.run();
    }

    private static PetriNetDocHLAPI importPNML()
    {
        File pnmlFile = new File("D:\\Programs\\portable\\tina-3.8.5\\bin\\buffer.pnml");
        fr.lip6.move.pnml.ptnet.hlapi.PetriNetDocHLAPI ptDoc=null;

        try {
            // Импорт документа PNML без fallback
            HLAPIRootClass root = PNMLUtils.importPnmlDocument(pnmlFile, false);

            // Получение ID рабочей области документа
            String wsId = ModelRepository.getInstance().getCurrentDocWSId();
            System.out.println("Imported document workspace ID: " + wsId);

            // Определение типа сети
            PNType type = PNMLUtils.determinePNType(root);
            System.out.println("Detected Petri Net Type: " + type);

            // Пример обработки P/T сети

            if (type == PNType.PTNET) {
                ptDoc = (fr.lip6.move.pnml.ptnet.hlapi.PetriNetDocHLAPI) root;
                System.out.println("Successfully imported a Place/Transition Net.");
                // Здесь можно обработать сеть далее (места, переходы и т.д.)
            } else {
                System.out.println("This example only handles PTNET documents.");
            }

        } catch (ImportException | InvalidIDException e) {
            e.printStackTrace();
        }
        return ptDoc;
    }
}