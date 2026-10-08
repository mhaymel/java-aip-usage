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
import java.util.logging.LogManager;
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

    private static final String MANAGER_PROPERTY = "java.util.logging.manager";

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
        useShutdownSafeLogManager();
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
            warnIfShutdownCouldLoseLines();
        } catch (IOException | SecurityException e) {
            System.getLogger(Logging.class.getName())
                    .log(System.Logger.Level.WARNING, "Cannot write log file " + logFile + "; logging to console only", e);
        }
        return logging;
    }

    private boolean closed;

    /** Closing twice is harmless: the shutdown hook may close what the normal exit already has. */
    /**
     * Something using logging before {@link #install} means the JDK had already chosen its
     * standard manager, which closes the log in a hook of its own and can lose the lines that say
     * how the program stopped. That is a bug in whatever came first, so it is said where it will
     * be seen: in the log file as well as on the console.
     */
    private static void warnIfShutdownCouldLoseLines() {
        if (!(LogManager.getLogManager() instanceof ShutdownSafeLogManager)) {
            System.getLogger(Logging.class.getName()).log(System.Logger.Level.WARNING,
                    "Logging was already in use, so lines logged while the process is being stopped may be lost");
        }
    }

    /**
     * Asks for {@link ShutdownSafeLogManager}. The JDK reads the property once, when logging is
     * first used, so this must come before anything logs; later it changes nothing.
     */
    private static void useShutdownSafeLogManager() {
        if (System.getProperty(MANAGER_PROPERTY) == null) {
            System.setProperty(MANAGER_PROPERTY, ShutdownSafeLogManager.class.getName());
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
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
