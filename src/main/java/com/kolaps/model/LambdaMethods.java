package com.kolaps.model;

import soot.SootMethod;
import soot.Unit;

import java.util.ArrayList;
import java.util.List;

/**
 * Класс для хранения коллекции объектов, связанные с конкретным lambda-выражением.
 */
public class LambdaMethods {

    private static final List<LambdaInfoEntry> entries = new ArrayList<>();

    private LambdaMethods() {
        throw new AssertionError("Cannot instantiate static utility class");
    }

    /**
     * Добавляет новый объект MethodInfoEntry в хранилище.
     * @param entry Объект MethodInfoEntry для добавления.
     */
    public static void addEntry(LambdaInfoEntry entry) {
        if (entry != null) {
            entries.add(entry);
        }
    }

    /**
     * Метод для добавления новой записи путем передачи отдельных полей.
     */
    public static void addEntry(SootMethod runMethod, SootMethod invokeMethod, String lambdaVar, Unit invokeStmt) {
        LambdaInfoEntry entry = new LambdaInfoEntry(runMethod, invokeMethod, lambdaVar, invokeStmt);
        entries.add(entry);
    }

    /**
     * Возвращает список всех записей.
     * @return Неизменяемый список записей или копия списка для предотвращения внешних модификаций.
     */
    public static List<LambdaInfoEntry> getAllEntries() {
        return new ArrayList<>(entries);
    }

    /**
     * Возвращает запись по индексу.
     * @param index Индекс записи.
     * @return MethodInfoEntry по указанному индексу.
     * @throws IndexOutOfBoundsException если индекс выходит за пределы.
     */
    public static LambdaInfoEntry getEntry(int index) {
        return entries.get(index);
    }

    /**
     * Возвращает количество записей в хранилище.
     * @return Количество записей.
     */
    public static int getSize() {
        return entries.size();
    }

    /**
     * Очищает все записи из хранилища.
     */
    public static void clearEntries() {
        entries.clear();
    }

    /**
     * Удаляет запись по индексу.
     * @param index Индекс записи для удаления.
     * @return Удаленная запись.
     * @throws IndexOutOfBoundsException если индекс выходит за пределы.
     */
    public static LambdaInfoEntry removeEntry(int index) {
        return entries.remove(index);
    }

    /**
     * Удаляет указанную запись из хранилища.
     * @param entry Запись для удаления.
     * @return true, если запись была удалена, иначе false.
     */
    public static boolean removeEntry(LambdaInfoEntry entry) {
        return entries.remove(entry);
    }
}