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
    // Since 0.95: what happened, between Info and Debug.
    FINE("fine"),
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

    /** The names of the levels as the settings file and the API have them, for a message: {@code error, warning, info, fine, debug}. */
    public static String names() {
        return String.join(", ", Arrays.stream(values()).map(LogLevel::json).toList());
    }

    /** Whether a line of the given level is shown with this one chosen. */
    public boolean shows(LogLevel line) {
        return line.ordinal() <= ordinal();
    }

    /**
     * The level of a line of the log file, by the word the line has for it. The words are those of the logging of Java the
     * program began with, which calls an error {@code SEVERE}; since 0.95 a Fine line is {@code FINE} and a debug line
     * {@code DEBUG}, and the {@code FINE} lines of an older file, which were its debug lines, are read as Fine. A word that is none
     * of the known ones is the least severe level, so such a line is shown only when everything is.
     */
    public static LogLevel ofLogWord(String word) {
        return switch (word.toUpperCase(Locale.ROOT)) {
            case "SEVERE", "ERROR" -> ERROR;
            case "WARNING", "WARN" -> WARNING;
            case "INFO", "CONFIG" -> INFO;
            case "FINE" -> FINE;
            default -> DEBUG;
        };
    }
}
