package org.example.log;

import org.example.Redaction;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The program's own log: a line is said here, by the class it is about, and goes to every place that is listening,
 * which in a run are the console and the log file. It depends on no logging library, so nothing closes it or sets it
 * up behind the program's back.
 *
 * <p>A line has four levels to be at. In the file they have the words they have had since the first version, which
 * the log panel reads: {@code SEVERE}, {@code WARNING}, {@code INFO} and {@code FINE}.
 */
public final class Log {

    /** How much a line matters, with the word it has in a written line. */
    public enum Level {
        ERROR("SEVERE"), WARNING("WARNING"), INFO("INFO"), DEBUG("FINE");

        private final String word;

        Level(String word) {
            this.word = word;
        }

        /** The word for this level in a written line. */
        public String word() {
            return word;
        }
    }

    /** One thing that was logged, before it is written as a line. */
    public record Entry(long atMillis, Level level, String name, String message, Throwable thrown) {

        /**
         * The entry as it is written: the time as in the usage history file, the level, the name of the class in
         * brackets, the message, and after it the stack trace if something was thrown. Each line ends as lines end on
         * this system, and anything shaped like a credential is masked.
         */
        public String written(ZoneId zone) {
            StringBuilder line = new StringBuilder()
                    .append(timestamp(atMillis, zone))
                    .append(' ')
                    .append(String.format("%-7s", level.word()))
                    .append(" [").append(name).append("] ")
                    .append(message)
                    .append(System.lineSeparator());
            if (thrown != null) {
                StringWriter trace = new StringWriter();
                thrown.printStackTrace(new PrintWriter(trace));
                line.append(trace);
            }
            return Redaction.redact(line.toString());
        }
    }

    /** A place that takes what is logged. It is called for one entry at a time, never for two at once. */
    public interface Sink {

        void take(Entry entry);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private static final List<Sink> SINKS = new CopyOnWriteArrayList<>();

    /** Held while an entry is handed to the sinks, so that the lines of two threads are never mixed. */
    private static final Object WRITING = new Object();

    private final String name;

    private Log(String name) {
        this.name = name;
    }

    /** The log of a class, whose lines carry its short name. */
    public static Log of(Class<?> owner) {
        return new Log(owner.getSimpleName());
    }

    /** A log whose lines carry this name. */
    public static Log named(String name) {
        return new Log(name);
    }

    /** From now on what is logged goes to this place as well. */
    public static void addSink(Sink sink) {
        SINKS.add(sink);
    }

    /** From now on nothing goes to this place; whatever is being written to it is finished first. */
    public static void removeSink(Sink sink) {
        synchronized (WRITING) {
            SINKS.remove(sink);
        }
    }

    public void error(String message) {
        log(Level.ERROR, message, null);
    }

    public void error(String message, Throwable thrown) {
        log(Level.ERROR, message, thrown);
    }

    public void warning(String message) {
        log(Level.WARNING, message, null);
    }

    public void warning(String message, Throwable thrown) {
        log(Level.WARNING, message, thrown);
    }

    public void info(String message) {
        log(Level.INFO, message, null);
    }

    public void debug(String message) {
        log(Level.DEBUG, message, null);
    }

    public void log(Level level, String message, Throwable thrown) {
        Entry entry = new Entry(System.currentTimeMillis(), level, name, message, thrown);
        synchronized (WRITING) {
            for (Sink sink : SINKS) {
                try {
                    sink.take(entry);
                } catch (RuntimeException e) {
                    // A place that cannot take a line must not stop the program, nor the other places.
                }
            }
        }
    }

    /**
     * A time written the way the usage history file writes it, {@code 2026-10-08 16:24:53}: the local date and time
     * to the second, so the two files and the window all show the same clock.
     */
    public static String timestamp(long epochMillis, ZoneId zone) {
        return TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone));
    }
}
