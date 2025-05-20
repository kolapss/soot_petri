package com.kolaps.model;

import soot.SootMethod;
import soot.Unit;

import java.util.ArrayList;
import java.util.List;

/**
 * Класс для хранения коллекции объектов MethodInfoEntry.
 */
public class LambdaMethods {

    // Статическое поле для хранения записей
    private static final List<LambdaInfoEntry> entries = new ArrayList<>();

    // Приватный конструктор, чтобы предотвратить создание экземпляров этого служебного класса
    private LambdaMethods() {
        // Этот конструктор никогда не должен вызываться.
        throw new AssertionError("Cannot instantiate static utility class");
    }

    /**
     * Добавляет новый объект MethodInfoEntry в хранилище.
     * @param entry Объект MethodInfoEntry для добавления.
     */
    public static void addEntry(LambdaInfoEntry entry) {
        if (entry != null) {
            // Если требуется потокобезопасность, здесь нужна синхронизация
            // synchronized (entries) {
            entries.add(entry);
            // }
        }
    }

    /**
     * Удобный метод для добавления новой записи путем передачи отдельных полей.
     */
    public static void addEntry(SootMethod runMethod, SootMethod invokeMethod, String lambdaVar, Unit invokeStmt) {
        LambdaInfoEntry entry = new LambdaInfoEntry(runMethod, invokeMethod, lambdaVar, invokeStmt);
        // synchronized (entries) {
        entries.add(entry);
        // }
    }

    /**
     * Возвращает список всех записей.
     * @return Неизменяемый список записей или копия списка для предотвращения внешних модификаций.
     */
    public static List<LambdaInfoEntry> getAllEntries() {
        // synchronized (entries) {
        // Возвращаем копию, чтобы внутренний список не мог быть изменен извне
        return new ArrayList<>(entries);
        // Альтернативно, для неизменяемого представления:
        // return Collections.unmodifiableList(new ArrayList<>(entries)); // копия + неизменяемость
        // }
    }

    /**
     * Возвращает запись по индексу.
     * @param index Индекс записи.
     * @return MethodInfoEntry по указанному индексу.
     * @throws IndexOutOfBoundsException если индекс выходит за пределы.
     */
    public static LambdaInfoEntry getEntry(int index) {
        // synchronized (entries) {
        return entries.get(index);
        // }
    }

    /**
     * Возвращает количество записей в хранилище.
     * @return Количество записей.
     */
    public static int getSize() {
        // synchronized (entries) {
        return entries.size();
        // }
    }

    /**
     * Очищает все записи из хранилища.
     */
    public static void clearEntries() {
        // synchronized (entries) {
        entries.clear();
        // }
    }

    /**
     * Удаляет запись по индексу.
     * @param index Индекс записи для удаления.
     * @return Удаленная запись.
     * @throws IndexOutOfBoundsException если индекс выходит за пределы.
     */
    public static LambdaInfoEntry removeEntry(int index) {
        // synchronized (entries) {
        return entries.remove(index);
        // }
    }

    /**
     * Удаляет указанную запись из хранилища.
     * @param entry Запись для удаления.
     * @return true, если запись была удалена, иначе false.
     */
    public static boolean removeEntry(LambdaInfoEntry entry) {
        // synchronized (entries) {
        return entries.remove(entry);
        // }
    }
}