package com.kolaps.utils;

import com.kolaps.BytecodeParser;
import com.kolaps.Options;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.jar.*;

public class RetroLambda {

    private static void extractJar(String jarPath, String outputDir) throws IOException {
        File outputDirFile = new File(outputDir);

        // 1. Проверяем и создаем выходную директорию, если она не существует
        if (!outputDirFile.exists()) {
            if (!outputDirFile.mkdirs()) {
                throw new IOException("Не удалось создать выходную директорию: " + outputDir);
            }
            System.out.println("Создана директория: " + outputDirFile.getAbsolutePath());
        } else if (!outputDirFile.isDirectory()) {
            throw new IOException("Указанный путь для вывода не является директорией: " + outputDir);
        }

        // --- Защита от уязвимости Zip Slip ---
        // Получаем канонический путь к выходной директории для сравнения
        String canonicalOutputDirPath = outputDirFile.getCanonicalPath();
        if (!canonicalOutputDirPath.endsWith(File.separator)) {
            canonicalOutputDirPath += File.separator;
        }
        // --- Конец защиты ---


        // 2. Используем try-with-resources для автоматического закрытия JarFile
        try (JarFile jarFile = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();

            System.out.println("Начало распаковки " + jarPath + " в " + outputDir);

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                File outputFile = new File(outputDir, entryName);

                // --- Проверка безопасности Zip Slip ---
                String canonicalEntryPath = outputFile.getCanonicalPath();
                if (!canonicalEntryPath.startsWith(canonicalOutputDirPath)) {
                    // Запись файла за пределы целевой папки! Пропускаем.
                    System.err.println("Предупреждение безопасности: Запись '" + entryName + "' пытается выйти за пределы директории '" + outputDir + "'. Пропуск.");
                    continue; // Пропускаем эту запись
                }
                // --- Конец проверки безопасности ---


                if (entry.isDirectory()) {
                    // 3. Если это директория, создаем ее (включая родительские)
                    if (!outputFile.exists()) {
                        if (!outputFile.mkdirs()) {
                            System.err.println("Предупреждение: Не удалось создать директорию: " + outputFile.getPath());
                            // Можно решить, критично ли это. Пока просто предупреждаем.
                        } else {
                            //System.out.println("Создана директория: " + outputFile.getPath());
                        }
                    }
                } else {
                    // 4. Если это файл, извлекаем его

                    // Убедимся, что родительская директория для файла существует
                    File parentDir = outputFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        if (!parentDir.mkdirs()) {
                            System.err.println("Предупреждение: Не удалось создать родительскую директорию: " + parentDir.getPath());
                            // Продолжаем, но запись файла, скорее всего, не удастся
                        }
                    }

                    // 5. Используем try-with-resources для InputStream
                    // Используем буферизацию для повышения производительности
                    try (InputStream is = jarFile.getInputStream(entry);
                         BufferedInputStream bis = new BufferedInputStream(is);
                         OutputStream os = new FileOutputStream(outputFile);
                         BufferedOutputStream bos = new BufferedOutputStream(os)) {

                        byte[] buffer = new byte[8192]; // Буфер 8KB
                        int bytesRead;
                        while ((bytesRead = bis.read(buffer)) != -1) {
                            bos.write(buffer, 0, bytesRead);
                        }
                        //System.out.println("Извлечен файл: " + outputFile.getPath());

                    } catch (IOException e) {
                        System.err.println("Ошибка при извлечении файла: " + entryName + " -> " + outputFile.getPath());
                        // Можно пробросить исключение дальше, если нужно остановить весь процесс
                        // throw new IOException("Ошибка при извлечении файла: " + entryName, e);
                        e.printStackTrace(); // Печатаем стектрейс для отладки
                    }
                }
            }
            System.out.println("Распаковка завершена.");

        } catch (IOException e) {
            System.err.println("Ошибка при открытии или чтении JAR-файла: " + jarPath);
            throw e; // Пробрасываем исключение дальше
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);  // передаем начало массива и его длину
        }
    }

    public static void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file); // Удаляем файл
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir); // Удаляем саму папку после её содержимого
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    public static void run(String path) {
        String inputDir = System.getProperty("user.dir") + "/classes";
        String outputDir = System.getProperty("user.dir") + "/retroclasses";
        String java8Home = "C:/Program Files/Java/jre1.8.0_271";
        String retrolambdaJar = "D:/MyProjects/soot_petri/retrolambda/retrolambda.jar";

        try {
            deleteDirectory(Paths.get(inputDir));
            deleteDirectory(Paths.get(outputDir));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            extractJar(path, System.getProperty("user.dir") + "/classes");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String classpath = inputDir;
        String bytecodeVersion = "52";


        try {
            extractJar(path, outputDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
                java8Home + "/bin/java",
                "-Xmx512m",
                "-Dretrolambda.inputDir=" + inputDir,
                "-Dretrolambda.outputDir=" + outputDir,
                "-Dretrolambda.classpath=" + classpath,
                "-Dretrolambda.bytecodeVersion=" + bytecodeVersion,
                "-jar", retrolambdaJar
        );

        processBuilder.redirectErrorStream(true); // объединяем вывод
        processBuilder.inheritIO(); // выводим в консоль

        try {
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            System.out.println("Retrolambda finished with code: " + exitCode);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

        String outFile = System.getProperty("user.dir") + "\\app.jar";
        Options.INSTANCE.setOption("app.jar",outFile);

        try {
            createJar(outputDir, outFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void createJar(String sourceDirectoryPath, String targetJarPath) throws IOException {
        Path sourceDir = Paths.get(sourceDirectoryPath);
        if (!Files.isDirectory(sourceDir)) {
            throw new IOException("Исходный путь не является директорией: " + sourceDirectoryPath);
        }

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        // manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.example.MyMainClass");

        try (OutputStream fos = Files.newOutputStream(Paths.get(targetJarPath));
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             JarOutputStream jos = new JarOutputStream(bos, manifest)) { // Манифест будет добавлен автоматически

            Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!sourceDir.equals(dir)) {
                        Path relativePath = sourceDir.relativize(dir);
                        String entryName = relativePath.toString().replace(File.separatorChar, '/') + "/";

                        // --- Пропускаем папку META-INF, если она существует в корне ---
                        // Хотя обычно сам MANIFEST.MF пропускается в visitFile, можно и папку пропустить
                        // if (relativePath.getNameCount() == 1 && relativePath.toString().equalsIgnoreCase("META-INF")) {
                        //     System.out.println("Skipping META-INF directory from source (will be created by JarOutputStream if needed).");
                        //     return FileVisitResult.SKIP_SUBTREE; // Пропустить эту папку и все ее содержимое
                        // }
                        // --- Конец пропуска папки ---

                        JarEntry entry = new JarEntry(entryName);
                        entry.setTime(Files.getLastModifiedTime(dir).toMillis());
                        jos.putNextEntry(entry);
                        jos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relativePath = sourceDir.relativize(file);
                    String entryName = relativePath.toString().replace(File.separatorChar, '/');

                    // --- ВОТ КЛЮЧЕВАЯ ПРОВЕРКА ---
                    // Пропускаем файл манифеста, т.к. JarOutputStream сам его добавляет/управляет им.
                    if (entryName.equalsIgnoreCase(JarFile.MANIFEST_NAME)) { // JarFile.MANIFEST_NAME это "META-INF/MANIFEST.MF"
                        System.out.println("Skipping existing META-INF/MANIFEST.MF from source directory.");
                        return FileVisitResult.CONTINUE; // Просто пропускаем этот файл
                    }
                    // --- КОНЕЦ ПРОВЕРКИ ---

                    JarEntry entry = new JarEntry(entryName);
                    entry.setTime(Files.getLastModifiedTime(file).toMillis());
                    jos.putNextEntry(entry);

                    try (InputStream fis = Files.newInputStream(file);
                         BufferedInputStream bis = new BufferedInputStream(fis)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = bis.read(buffer)) != -1) {
                            jos.write(buffer, 0, bytesRead);
                        }
                    }
                    jos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    System.err.println("Ошибка доступа к файлу: " + file + " [" + exc + "]");
                    return FileVisitResult.CONTINUE;
                }
            });

            System.out.println("JAR файл успешно создан: " + targetJarPath);

        }
    }

}
