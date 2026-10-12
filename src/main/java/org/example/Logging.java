package org.example;

import org.example.log.Log;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;

/**
 * Sends what the program logs to both the console and an append-mode log file, for as long as it is open.
 *
 * <p>The file is never rotated or truncated: each run appends to it. Every line is written through at once, so the
 * file is complete to the last line whenever it is looked at, and the lines of the stopping are in it because this is
 * closed after them, by {@link ShutdownHook} or by {@link Main}, and by nothing else.
 */
final class Logging implements AutoCloseable {

    /** Name of the log file, created in the working directory (the project root). */
    static final String LOG_FILE_NAME = "java-aip-usage.log";

    private static final Log LOG = Log.of(Logging.class);

    private final Log.Sink console;

    /** The file, as a place for lines; {@code null} when it could not be opened. */
    private Log.Sink file;

    private Writer fileWriter;

    private boolean closed;

    private Logging() {
        // The console as it is now, as the error output of the process: a later change of it is not followed.
        PrintStream err = System.err;
        console = entry -> {
            err.print(entry.written(ZoneId.systemDefault()));
            err.flush();
        };
    }

    /**
     * Begins writing to the console and to the file.
     *
     * <p>If the file cannot be opened the application keeps running with
     * console logging only, and the failure is reported there.
     */
    static Logging install(Path logFile) {
        Logging logging = new Logging();
        Log.addSink(logging.console);
        try {
            Writer writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            logging.fileWriter = writer;
            logging.file = entry -> {
                try {
                    writer.write(entry.written(ZoneId.systemDefault()));
                    writer.flush();
                } catch (IOException e) {
                    // The disk is full or the file is gone: the console still has the line.
                }
            };
            Log.addSink(logging.file);
            LOG.info("Logging to " + logFile);
        } catch (IOException | RuntimeException e) {
            LOG.warning("Cannot write log file " + logFile + "; logging to console only", e);
        }
        return logging;
    }

    /** Closing twice is harmless: the shutdown hook may close what the normal exit already has. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        Log.removeSink(console);
        if (file != null) {
            Log.removeSink(file);
            try {
                fileWriter.close();
            } catch (IOException e) {
                // Every line was written through when it was logged; there is nothing left to lose.
            }
        }
    }

    /** See {@link Log#timestamp}. */
    static String timestamp(long epochMillis, ZoneId zone) {
        return Log.timestamp(epochMillis, zone);
    }
}
