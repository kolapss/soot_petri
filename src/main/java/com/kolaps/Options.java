package com.kolaps;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Singleton класс для управления опциями/настройками приложения в виде пар ключ-значение.
 * Реализован с использованием шаблона Enum, который считается лучшим способом
 * создания потокобезопасных Singleton в Java, устойчивым к проблемам
 * сериализации и атакам через рефлексию.
 */
public enum Options {

    /** Единственный экземпляр OptionsManager. */
    INSTANCE; // Это объявляет единственный экземпляр перечисления, который является нашим Singleton

    // Внутренняя карта для хранения опций. String - ключ, Object - значение (чтобы хранить разные типы).
    private final Map<String, Object> options = new HashMap<>();

    /**
     * Приватный конструктор.
     * Вызывается один раз при первой загрузке класса Enum (что происходит при первом доступе к INSTANCE).
     */
    private Options() {
        // Здесь можно загрузить настройки по умолчанию или из файла конфигурации
        loadDefaultOptions();
        System.out.println("OptionsManager инициализирован.");
    }

    /**
     * Устанавливает значение опции.
     *
     * @param key   Ключ опции (не может быть null).
     * @param value Значение опции (не может быть null).
     */
    public void setOption(String key, Object value) {
        // Проверяем, что ключ и значение не null, чтобы избежать проблем
        Objects.requireNonNull(key, "Ключ опции не может быть null");
        Objects.requireNonNull(value, "Значение опции не может быть null");

        options.put(key, value);
        System.out.println("Установлена опция: " + key + " = " + value);
    }

    /**
     * Получает значение опции по ключу.
     *
     * @param key Ключ опции (не может быть null).
     * @return Значение, связанное с ключом, или null, если ключ не найден.
     */
    public Object getOption(String key) {
        Objects.requireNonNull(key, "Ключ опции не может быть null");
        return options.get(key);
    }

    /**
     * Получает значение опции в виде строки.
     * Удобен для получения строковых настроек с значением по умолчанию.
     *
     * @param key          Ключ опции (не может быть null).
     * @param defaultValue Значение по умолчанию, которое будет возвращено, если ключ не найден или значение не является строкой.
     * @return Значение опции как строка или defaultValue.
     */
    public String getStringOption(String key, String defaultValue) {
        Object value = getOption(key); // Используем базовый геттер
        if (value instanceof String) {
            return (String) value; // Проверяем тип и приводим
        }
        // Возвращаем defaultValue, если значение null, не строка или ключ не найден
        return defaultValue;
    }

    /**
     * Получает значение опции в виде целого числа (Integer).
     * Удобен для получения числовых настроек с значением по умолчанию.
     *
     * @param key          Ключ опции (не может быть null).
     * @param defaultValue Значение по умолчанию, которое будет возвращено, если ключ не найден или значение не является Integer.
     * @return Значение опции как int или defaultValue.
     */
    public int getIntOption(String key, int defaultValue) {
        Object value = getOption(key);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        // При желании можно добавить обработку других числовых типов (float, double),
        // например: if (value instanceof Number) { return ((Number) value).intValue(); }
        return defaultValue;
    }

    /**
     * Получает значение опции в виде булевого значения (Boolean).
     * Удобен для получения логических настроек с значением по умолчанию.
     *
     * @param key          Ключ опции (не может быть null).
     * @param defaultValue Значение по умолчанию, которое будет возвращено, если ключ не найден или значение не является Boolean.
     * @return Значение опции как boolean или defaultValue.
     */
    public boolean getBooleanOption(String key, boolean defaultValue) {
        Object value = getOption(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    // При необходимости можно добавить другие типизированные геттеры (getDoubleOption, getLongOption и т.д.)

    /**
     * Удаляет опцию по ключу.
     *
     * @param key Ключ опции для удаления (не может быть null).
     * @return Значение, которое было удалено, или null, если ключ не был найден.
     */
    public Object removeOption(String key) {
        Objects.requireNonNull(key, "Ключ опции для удаления не может быть null");
        System.out.println("Удалена опция: " + key);
        return options.remove(key);
    }


    /**
     * Возвращает неизменяемую карту всех текущих опций.
     * Полезно для просмотра или отладки.
     *
     * @return Неизменяемое представление внутренней карты опций.
     */
    public Map<String, Object> getAllOptions() {
        // Возвращаем неизменяемое представление, чтобы предотвратить внешнее изменение карты
        return Collections.unmodifiableMap(options);
    }


    /**
     * Загружает настройки по умолчанию.
     * Этот метод вызывается при инициализации Singleton.
     * Здесь следует реализовать логику установки начальных значений по умолчанию.
     */
    private void loadDefaultOptions() {
        // Пример настроек по умолчанию
        options.put("app.debug", "true");
        options.put("tina_dir", System.getProperty("user.dir")+"\\tina");

        // В реальном приложении здесь, скорее всего, вы бы загружали эти настройки
        // из файла (например, .properties, XML, JSON) или базы данных.
        System.out.println("Загружены настройки по умолчанию.");
    }

    // При необходимости можно добавить методы для сохранения/загрузки опций из файла:
    // public void loadOptionsFromFile(String filePath) { ... }
    // public void saveOptionsToFile(String filePath) { ... }
}
