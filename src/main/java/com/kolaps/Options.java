package com.kolaps;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Singleton класс для управления опциями/настройками приложения в виде пар ключ-значение.
 */
public enum Options {

    /** Единственный экземпляр OptionsManager. */
    INSTANCE;

    // карта для хранения опций
    private final Map<String, Object> options = new HashMap<>();

    /**
     * Приватный конструктор.
     * Вызывается один раз при первой загрузке класса Enum (что происходит при первом доступе к INSTANCE).
     */
    Options() {
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
     * Удобен для получения строковых настроек со значением по умолчанию.
     *
     * @param key          Ключ опции (не может быть null).
     * @param defaultValue Значение по умолчанию, которое будет возвращено, если ключ не найден или значение не является строкой.
     * @return Значение опции как строка или defaultValue.
     */
    public String getStringOption(String key, String defaultValue) {
        Object value = getOption(key);
        if (value instanceof String) {
            return (String) value;
        }
        return defaultValue;
    }

    /**
     * Получает значение опции в виде целого числа (Integer).
     * Удобен для получения числовых настроек со значением по умолчанию.
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
        return defaultValue;
    }

    /**
     * Получает значение опции в виде булевого значения (Boolean).
     * Удобен для получения логических настроек со значением по умолчанию.
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
        options.put("tina_dir", System.getProperty("user.dir")+"\\tina\\");
        options.put("app.jar", "");
        options.put("app.pnml_file", "");
        options.put("jrePath", "");
        options.put("classPath", "");

        System.out.println("Загружены настройки по умолчанию.");
    }

}
