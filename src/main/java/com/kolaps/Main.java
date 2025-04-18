package com.kolaps;

import fr.lip6.move.pnml.framework.utils.exception.*;

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
        try {
            builder.exportToPnml();
        } catch (OtherException | ValidationFailedException | BadFileFormatException | IOException |
                 OCLValidationFailed | UnhandledNetType e) {
            throw new RuntimeException(e);
        }
    }
}