package com.kolaps;

import fr.lip6.move.pnml.framework.general.PNType;
import fr.lip6.move.pnml.framework.hlapi.HLAPIRootClass;
import fr.lip6.move.pnml.framework.utils.ModelRepository;
import fr.lip6.move.pnml.framework.utils.PNMLUtils;
import fr.lip6.move.pnml.framework.utils.exception.*;
import fr.lip6.move.pnml.ptnet.hlapi.PetriNetDocHLAPI;

import java.io.File;
import java.io.IOException;

public class Main {


    public static void main(String[] args) {

        //PetriNetDocHLAPI pt = importPNML();
        Options.INSTANCE.setOption("app.pnml_file", System.getProperty("user.dir") + "\\exporttest.pnml");
        String jarFilePath = null;
        String classPath = null;
        String jrePath = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-appJar":
                    if (i + 1 < args.length) {
                        jarFilePath = args[++i];
                    } else {
                        System.err.println("Missing value for -appJar");
                    }
                    break;
                case "-classPath":
                    if (i + 1 < args.length) {
                        Options.INSTANCE.setOption("classPath",args[++i]);
                    } else {
                        System.err.println("Missing value for -classPath");
                    }
                    break;
                case "-jrePath":
                    if (i + 1 < args.length) {
                        Options.INSTANCE.setOption("jrePath", args[++i]);
                    } else {
                        System.err.println("Missing value for -jrePath");
                    }
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
            }
        }

        // Проверяем, передан ли файл
        if (jarFilePath == null) {
            System.out.println("Ошибка: укажите путь к JAR-файлу с ключом -appJar");
            System.out.println("Пример: java -jar stal.jar -appJar path/to/file.jar");
            System.exit(1);
        }
        PetriNetBuilder builder;
        try {
            builder = new PetriNetBuilder();
        } catch (InvalidIDException | VoidRepositoryException | OtherException | ValidationFailedException |
                 BadFileFormatException | IOException | OCLValidationFailed | UnhandledNetType e) {
            throw new RuntimeException(e);
        }
        SootInitializer.parseProgram(jarFilePath, builder);
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
            // Импорт документа PNML
            HLAPIRootClass root = PNMLUtils.importPnmlDocument(pnmlFile, false);

            // Получение ID рабочей области документа
            String wsId = ModelRepository.getInstance().getCurrentDocWSId();
            System.out.println("Идентификатор импортированного документа: " + wsId);

            // Определение типа сети
            PNType type = PNMLUtils.determinePNType(root);
            System.out.println("Тип сети Петри: " + type);



            if (type == PNType.PTNET) {
                ptDoc = (fr.lip6.move.pnml.ptnet.hlapi.PetriNetDocHLAPI) root;
                System.out.println("Успешно импортирована сеть размещения/перехода.");
            } else {
                System.out.println("This example only handles PTNET documents.");
            }

        } catch (ImportException | InvalidIDException e) {
            e.printStackTrace();
        }
        return ptDoc;
    }
}