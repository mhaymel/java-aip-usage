package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingTest {

    private static final System.Logger LOG = System.getLogger(LoggingTest.class.getName());

    @TempDir
    Path dir;

    @Test
    void writesRecordsToTheFile() throws Exception {
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file)) {
            LOG.log(System.Logger.Level.INFO, "hello {0}", "world");
        }
        String content = Files.readString(file);
        assertTrue(content.contains("INFO"));
        assertTrue(content.contains("[LoggingTest] hello world"));
    }

    @Test
    void appendsAcrossRunsInsteadOfOverwriting() throws Exception {
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file)) {
            LOG.log(System.Logger.Level.INFO, "first run");
        }
        try (Logging logging = Logging.install(file)) {
            LOG.log(System.Logger.Level.INFO, "second run");
        }
        String content = Files.readString(file);
        assertTrue(content.indexOf("first run") >= 0);
        assertTrue(content.indexOf("first run") < content.indexOf("second run"));
    }

    @Test
    void includesStackTraces() throws Exception {
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file)) {
            LOG.log(System.Logger.Level.ERROR, "boom", new IllegalStateException("bad state"));
        }
        assertTrue(Files.readString(file).contains("java.lang.IllegalStateException: bad state"));
    }

    @Test
    void aCredentialInAMessageNeverReachesTheFile() throws Exception {
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file)) {
            LOG.log(System.Logger.Level.INFO, "sent Authorization: Bearer LEAK-BEARER and sk-ant-oat01-LEAK-KEY");
        }

        String content = Files.readString(file);
        assertFalse(content.contains("LEAK-BEARER"), content);
        assertFalse(content.contains("LEAK-KEY"), content);
        assertTrue(content.contains("[redacted]"), content);
        assertTrue(content.contains("sent "), "the rest of the message is kept");
    }

    @Test
    void aCredentialInAnExceptionNeverReachesTheFile() throws Exception {
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file)) {
            RuntimeException cause = new RuntimeException("cause carried sk-ant-oat01-LEAK-CAUSE");
            LOG.log(System.Logger.Level.ERROR, "failed", new IllegalStateException("x-api-key: LEAK-HEADER", cause));
        }

        String content = Files.readString(file);
        assertFalse(content.contains("LEAK-CAUSE"), content);
        assertFalse(content.contains("LEAK-HEADER"), content);
        assertTrue(content.contains("IllegalStateException"), "the stack trace itself is kept");
    }

    /** A handler that counts how often it is closed. */
    private static final class CountingHandler extends java.util.logging.Handler {

        int closes;

        @Override
        public void publish(java.util.logging.LogRecord record) {
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            closes++;
        }
    }

    @Test
    void closingTwiceHarmsNothingThatWasThereBefore() throws Exception {
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        CountingHandler existing = new CountingHandler();
        root.addHandler(existing);
        try {
            Path file = dir.resolve("app.log");
            Logging logging = Logging.install(file);

            logging.close();
            logging.close();

            // Installing set it aside and closing put it back. A second close must not reach it again.
            assertEquals(0, existing.closes, "a handler that was already there is not ours to close");
            assertTrue(java.util.List.of(root.getHandlers()).contains(existing), "and it is back in place");
            assertFalse(Files.exists(Path.of(file + ".lck")));
        } finally {
            root.removeHandler(existing);
        }
    }

    @Test
    void unwritableFileFallsBackToConsoleOnly() throws Exception {
        Path missingDir = dir.resolve("no-such-dir").resolve("app.log");
        try (Logging logging = Logging.install(missingDir)) {
            LOG.log(System.Logger.Level.INFO, "still running");
        }
        assertEquals(List.of(), Files.list(dir).toList());
    }
}
