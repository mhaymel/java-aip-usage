package org.example;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Sends application logs to both the console and an append-mode log file.
 *
 * <p>{@link System.Logger} delegates to {@code java.util.logging}, so
 * installing handlers on the root logger covers every class that logs.
 * The file is never rotated or truncated: each run appends to it.
 */
final class Logging implements AutoCloseable {

    /** Name of the log file, created in the working directory (the project root). */
    static final String LOG_FILE_NAME = "java-aip-usage.log";

    private final Logger root = Logger.getLogger("");

    private final Handler[] previousHandlers = root.getHandlers();

    private final Level previousLevel = root.getLevel();

    private Logging() {
    }

    /**
     * Replaces the root logger's handlers with console and file output.
     *
     * <p>If the file cannot be opened the application keeps running with
     * console logging only, and the failure is reported there.
     */
    static Logging install(Path logFile) {
        Logging logging = new Logging();
        Logger root = logging.root;
        for (Handler handler : logging.previousHandlers) {
            root.removeHandler(handler);
        }
        root.setLevel(Level.INFO);

        Formatter formatter = new LineFormatter();
        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(formatter);
        console.setLevel(Level.ALL);
        root.addHandler(console);

        try {
            // FileHandler treats '%' as a pattern escape, so escape it in the path.
            FileHandler file = new FileHandler(logFile.toString().replace("%", "%%"), true);
            file.setFormatter(formatter);
            file.setLevel(Level.ALL);
            file.setEncoding("UTF-8");
            root.addHandler(file);
            System.getLogger(Logging.class.getName())
                    .log(System.Logger.Level.INFO, "Logging to {0}", logFile);
        } catch (IOException | SecurityException e) {
            System.getLogger(Logging.class.getName())
                    .log(System.Logger.Level.WARNING, "Cannot write log file " + logFile + "; logging to console only", e);
        }
        return logging;
    }

    @Override
    public void close() {
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
            handler.close();
        }
        for (Handler handler : previousHandlers) {
            root.addHandler(handler);
        }
        root.setLevel(previousLevel);
    }

    /**
     * One line per record: timestamp, level, short logger name, message, then any
     * stack trace. Everything written passes through {@link Redaction}.
     */
    private static final class LineFormatter extends Formatter {

        @Override
        public String format(LogRecord record) {
            String logger = record.getLoggerName() == null ? "" : record.getLoggerName();
            StringBuilder line = new StringBuilder()
                    .append(Instant.ofEpochMilli(record.getMillis()))
                    .append(' ')
                    .append(String.format("%-7s", record.getLevel().getName()))
                    .append(" [").append(logger.substring(logger.lastIndexOf('.') + 1)).append("] ")
                    .append(formatMessage(record))
                    .append(System.lineSeparator());
            if (record.getThrown() != null) {
                StringWriter trace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(trace));
                line.append(trace);
            }
            return Redaction.redact(line.toString());
        }
    }
}
