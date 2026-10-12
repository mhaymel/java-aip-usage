package org.example.settings;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The least severe level the log panel shows, from the most severe to the least: the one chosen is shown, and every one
 * before it. It decides what the panel shows of the log file, never what the program writes to it.
 */
public enum LogLevel {

    ERROR("error"),
    WARNING("warning"),
    INFO("info"),
    DEBUG("debug");

    private final String json;

    LogLevel(String json) {
        this.json = json;
    }

    /** The form it has in the settings file and the API. */
    public String json() {
        return json;
    }

    public static Optional<LogLevel> fromJson(String text) {
        return Arrays.stream(values()).filter(level -> level.json.equals(text)).findFirst();
    }

    /** The names of the levels as the settings file and the API have them, for a message: {@code error, warning, info, debug}. */
    public static String names() {
        return String.join(", ", Arrays.stream(values()).map(LogLevel::json).toList());
    }

    /** Whether a line of the given level is shown with this one chosen. */
    public boolean shows(LogLevel line) {
        return line.ordinal() <= ordinal();
    }

    /**
     * The level of a line of the log file, by the word the line has for it. The words are those of the logging of Java the
     * program began with and has kept, which calls an error {@code SEVERE} and a debug line {@code FINE}. A word that is none of
     * the known ones is the least severe level, so such a line is shown only when everything is.
     */
    public static LogLevel ofLogWord(String word) {
        return switch (word.toUpperCase(Locale.ROOT)) {
            case "SEVERE", "ERROR" -> ERROR;
            case "WARNING", "WARN" -> WARNING;
            case "INFO", "CONFIG" -> INFO;
            default -> DEBUG;
        };
    }
}
