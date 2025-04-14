package com.kolaps.utils;

import com.kolaps.BytecodeParser;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public class RetroLambda {

    private static void extractJar(String jarPath, String outputDir) throws IOException {
        JarFile jarFile = new JarFile(jarPath);
        Enumeration<JarEntry> entries = jarFile.entries();

        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            File entryDestination = new File(outputDir, entry.getName());

            if (entry.isDirectory()) {
                entryDestination.mkdirs();
            } else {
                entryDestination.getParentFile().mkdirs();
                try (InputStream in = jarFile.getInputStream(entry);
                     OutputStream out = new FileOutputStream(entryDestination)) {
                    copyStream(in, out);
                }
            }
        }

        jarFile.close();
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
        try {
            extractJar(path, System.getProperty("user.dir") + "/classes");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String java8Home = "C:/Program Files/Java/jre1.8.0_271";
        String retrolambdaJar = "D:/MyProjects/soot_petri/retrolambda/retrolambda.jar";

        try {
            deleteDirectory(Paths.get(inputDir));
            deleteDirectory(Paths.get(outputDir));
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

        String outFile = System.getProperty("user.dir") + "/app.jar";
        BytecodeParser.setPath(outFile);

        try {
            createJar(outputDir, outFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void createJar(String directoryPath, String jarPath) throws IOException {
        // Создаем поток для записи в JAR файл
        try (FileOutputStream fos = new FileOutputStream(jarPath);
             JarOutputStream jos = new JarOutputStream(fos)) {

            // Рекурсивно обходим файлы в папке и добавляем их в JAR
            File dir = new File(directoryPath);
            addFilesToJar(dir, jos, directoryPath);
        }
    }

    private static void addFilesToJar(File dir, JarOutputStream jos, String baseDir) throws IOException {
        File[] files = dir.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    // Если это папка, рекурсивно добавляем ее
                    addFilesToJar(file, jos, baseDir);
                } else {
                    // Если файл, добавляем его в JAR
                    try (FileInputStream fis = new FileInputStream(file)) {
                        // Создаем новый JarEntry с относительным путем файла
                        String entryName = file.getAbsolutePath().substring(baseDir.length() + 1);
                        JarEntry entry = new JarEntry(entryName);
                        jos.putNextEntry(entry);

                        // Записываем содержимое файла в архив
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            jos.write(buffer, 0, bytesRead);
                        }

                        // Закрываем текущую запись
                        jos.closeEntry();
                    }
                }
            }
        }
    }
}
