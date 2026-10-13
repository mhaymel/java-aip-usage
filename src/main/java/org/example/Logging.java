package org.example;

import org.example.log.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sends what the program logs to both the console and an append-mode log file, for as long as it is open.
 *
 * <p>The file is never rotated or truncated by the program of its own accord: each run appends to it. Every line is
 * written through at once, so the file is complete to the last line whenever it is looked at, and the lines of the
 * stopping are in it because this is closed after them, by {@link ShutdownHook} or by {@link Main}, and by nothing else.
 *
 * <p>A person can reset the log, from the log panel: the file is kept under a name with the date and time, an old log,
 * and a new one is begun. And the reset can be taken back: the newest old log is put in front of the current one again.
 * Both are done while nothing is written, so that no line is in neither file.
 */
final class Logging implements AutoCloseable {

    /** Name of the log file, created in the working directory (the project root). */
    static final String LOG_FILE_NAME = "java-aip-usage.log";

    private static final Log LOG = Log.of(Logging.class);

    private final Log.Sink console;

    /** The file, as a place for lines; {@code null} when it could not be opened. */
    private Log.Sink file;

    private Writer fileWriter;

    /** The file the log is written to, as it was named when the log was opened. */
    private Path logFile;

    private boolean closed;

    /** The log that is open now, if one is: the one the log panel's two buttons act on. */
    private static volatile Logging current;

    /** The date and time in the name of an old log: it sorts as text in the order of time, and has no colon in it. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

    private static final String STAMP_PATTERN = "\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}";

    /** Thrown by {@link #merge()} when there is no old log to put back. */
    static final class NoOldLog extends IOException {

        NoOldLog() {
            super("There is no old log.");
        }
    }

    /** @param traces whether the console also shows where in the program a failure came from, which it does in debug mode */
    private Logging(boolean traces) {
        // The console as it is now, as the error output of the process: a later change of it is not followed.
        PrintStream err = System.err;
        console = entry -> {
            err.print(entry.written(ZoneId.systemDefault()));
            if (traces) {
                err.print(entry.trace());
            }
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
        return install(logFile, false);
    }

    /** @param traces whether the console also shows the stack trace of a failure; the file never has one */
    static Logging install(Path logFile, boolean traces) {
        Logging logging = new Logging(traces);
        logging.logFile = logFile;
        Log.addSink(logging.console);
        try {
            logging.fileWriter = open(logFile);
            // The writer is the one the log has at the moment of the line: a reset and a merge give it another.
            logging.file = entry -> {
                try {
                    logging.fileWriter.write(entry.written(ZoneId.systemDefault()));
                    logging.fileWriter.flush();
                } catch (IOException e) {
                    // The disk is full or the file is gone: the console still has the line.
                }
            };
            Log.addSink(logging.file);
            LOG.info("Logging to " + logFile);
        } catch (IOException | RuntimeException e) {
            LOG.warning("Cannot write log file " + logFile + "; logging to console only", e);
        }
        current = logging;
        return logging;
    }

    private static Writer open(Path logFile) throws IOException {
        return Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** The log that is open and written to this file, if there is one: what the log panel's two buttons act on. */
    static Optional<Logging> of(Path logFile) {
        Logging open = current;
        return open != null && open.file != null && !open.closed && open.logFile.toAbsolutePath().equals(logFile.toAbsolutePath())
                ? Optional.of(open) : Optional.empty();
    }

    /** The name of the log file without its ending {@code .log}, which the name of an old log begins with. */
    private String stem() {
        String name = logFile.getFileName().toString();
        return name.endsWith(".log") ? name.substring(0, name.length() - ".log".length()) : name;
    }

    /** The name an old log has: {@code java-aip-usage.2026-10-13_14-30-05.log}. */
    private String oldName(LocalDateTime time) {
        return stem() + "." + STAMP.format(time) + ".log";
    }

    /**
     * The newest old log beside the log file: the one whose name has the latest date and time. Only a file of exactly the
     * form of the name is one, so that no other file is ever taken for an old log, joined and deleted.
     */
    Optional<Path> newestOldLog() throws IOException {
        Pattern form = Pattern.compile(Pattern.quote(stem() + ".") + STAMP_PATTERN + Pattern.quote(".log"));
        Path folder = logFile.toAbsolutePath().getParent();
        try (Stream<Path> files = Files.list(folder)) {
            return files
                    .filter(file -> form.matcher(file.getFileName().toString()).matches() && Files.isRegularFile(file))
                    .max(Comparator.comparing(file -> file.getFileName().toString()));
        }
    }

    /** Whether there is an old log, so that a merge has something to put back; a folder that cannot be read has none. */
    boolean hasOldLog() {
        try {
            return newestOldLog().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    private void requireFile() throws IOException {
        if (file == null || closed) {
            throw new Fault("the log is not written to a file");
        }
    }

    /** Opens the log file for the lines that follow; if that fails the console still has them, as at the start. */
    private void reopen() {
        try {
            fileWriter = open(logFile);
        } catch (IOException e) {
            fileWriter = Writer.nullWriter();
        }
    }

    /**
     * Resets the log: the file gets the name of an old log, with the date and time given, and a new, empty one is begun,
     * whose first line says where the lines before are. Nothing is changed if the file cannot be renamed, or an old log of
     * that second is there already.
     *
     * @return the name of the old log, without its folder
     */
    String reset(LocalDateTime time) throws IOException {
        String name = oldName(time);
        Log.whileNothingIsWritten(() -> {
            requireFile();
            Path old = logFile.resolveSibling(name);
            if (Files.exists(old)) {
                throw new Fault("an old log of this second is there already");
            }
            // A file that is open cannot be renamed on every system, so it is closed for the moment.
            fileWriter.close();
            try {
                Files.move(logFile, old);
            } finally {
                reopen();
            }
            return null;
        });
        LOG.info("The log was reset: the lines before are in " + name);
        return name;
    }

    /**
     * Takes the last reset back: the lines of the newest old log are put in front of those of the current log, the result
     * is the log file, put in its place in one step, and the old log is deleted. Nothing is changed if the two cannot be
     * joined.
     *
     * @return the name of the old log that was put back, without its folder
     * @throws NoOldLog if there is no old log
     */
    String merge() throws IOException {
        IOException[] left = new IOException[1];
        String name = Log.whileNothingIsWritten(() -> {
            requireFile();
            Path old = newestOldLog().orElseThrow(NoOldLog::new);
            Path joined = logFile.resolveSibling(logFile.getFileName() + ".tmp");
            fileWriter.close();
            try {
                try (OutputStream out = Files.newOutputStream(joined)) {
                    Files.copy(old, out);
                    Files.copy(logFile, out);
                }
                try {
                    Files.move(joined, logFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(joined, logFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                Files.deleteIfExists(joined);
                throw e;
            } finally {
                reopen();
            }
            try {
                Files.delete(old);
            } catch (IOException e) {
                left[0] = e;
            }
            return old.getFileName().toString();
        });
        LOG.info("The log was merged with " + name);
        if (left[0] != null) {
            // Its lines are in the log now: left where it is, the next merge would put them there a second time.
            LOG.warning("Could not delete the old log " + name + ", whose lines are in the log now; delete it by hand", left[0]);
        }
        return name;
    }

    /** Closing twice is harmless: the shutdown hook may close what the normal exit already has. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (current == this) {
            current = null;
        }
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
