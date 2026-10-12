package org.example;

import org.example.log.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingTest {

    private static final Log LOG = Log.of(LoggingTest.class);

    @TempDir
    Path dir;

    private final PrintStream originalErr = System.err;

    @AfterEach
    void giveTheConsoleBack() {
        System.setErr(originalErr);
    }

    @Test
    void writesRecordsToTheFile() throws Exception {
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file)) {
            LOG.info("hello world");
        }
        String content = Files.readString(file);
        assertTrue(content.contains("INFO"));
        assertTrue(content.contains("[LoggingTest] hello world"));
    }

    @Test
    void aLineHasItsTimeItsLevelInSevenPlacesTheNameOfItsClassAndItsMessage() throws Exception {
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file)) {
            LOG.error("an error");
            LOG.warning("a warning");
            LOG.info("information");
            LOG.debug("a debug line");
        }
        List<String> lines = Files.readAllLines(file);
        String time = "\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d ";
        assertTrue(lines.get(0).matches(time + "INFO    \\[Logging] Logging to .*app\\.log"), lines.get(0));
        assertTrue(lines.get(1).matches(time + "SEVERE  \\[LoggingTest] an error"), lines.get(1));
        assertTrue(lines.get(2).matches(time + "WARNING \\[LoggingTest] a warning"), lines.get(2));
        assertTrue(lines.get(3).matches(time + "INFO    \\[LoggingTest] information"), lines.get(3));
        assertTrue(lines.get(4).matches(time + "FINE    \\[LoggingTest] a debug line"), lines.get(4));
        assertEquals(5, lines.size());
        assertEquals(org.example.settings.LogLevel.DEBUG, org.example.settings.LogLevel.ofLogWord("FINE"), "which the log panel shows with Debug only");
        assertTrue(Files.readString(file).endsWith("a debug line" + System.lineSeparator()), "each line ends as lines end on this system");
    }

    @Test
    void theConsoleGetsTheSameLinesAsTheFile() throws Exception {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        System.setErr(new PrintStream(console, true, StandardCharsets.UTF_8));
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file)) {
            LOG.info("to both");
            LOG.warning("and this");
        }
        assertEquals(Files.readString(file), console.toString(StandardCharsets.UTF_8));
    }

    @Test
    void appendsAcrossRunsInsteadOfOverwriting() throws Exception {
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file)) {
            LOG.info("first run");
        }
        try (Logging logging = Logging.install(file)) {
            LOG.info("second run");
        }
        String content = Files.readString(file);
        assertTrue(content.indexOf("first run") >= 0);
        assertTrue(content.indexOf("first run") < content.indexOf("second run"));
    }

    @Test
    void aLineIsInTheFileAsSoonAsItIsLogged() throws Exception {
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file)) {
            LOG.info("written through");

            assertTrue(Files.readString(file).contains("written through"), "while the log is still open");
        }
    }

    @Test
    void aFailureIsOneLineThatSaysWhatWentWrongInTheProgramsWordsAndTheFileHasNoStackTrace() throws Exception {
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file)) {
            LOG.error("Could not start", new java.nio.file.NoSuchFileException("C:\\somewhere\\settings.json"));
            LOG.warning("Could not save settings to settings.json", new Fault("the settings file is not a JSON object"));
            LOG.warning("API request failed: /api/status", new IllegalStateException("bad state"));
        }
        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.get(1).endsWith("SEVERE  [LoggingTest] Could not start: the file or its folder is not there"), lines.get(1));
        assertTrue(lines.get(2).endsWith("WARNING [LoggingTest] Could not save settings to settings.json: the settings file is not a JSON object"),
                "a failure the program names itself, in its words: " + lines.get(2));
        assertTrue(lines.get(3).endsWith("WARNING [LoggingTest] API request failed: /api/status: an unexpected fault"), lines.get(3));
        assertEquals(4, lines.size(), "one line each, and nothing after it");
        String content = Files.readString(file);
        assertFalse(content.contains("IllegalStateException") || content.contains("bad state") || content.contains("\tat "), content);
    }

    @Test
    void inDebugModeTheConsoleAlsoShowsWhereAFailureCameFromAndTheFileStillDoesNot() throws Exception {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        System.setErr(new PrintStream(console, true, StandardCharsets.UTF_8));
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file, true)) {
            LOG.error("boom", new IllegalStateException("bad state"));
            LOG.info("no failure, no trace");
        }
        String said = console.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("[LoggingTest] boom: an unexpected fault" + System.lineSeparator() + "java.lang.IllegalStateException: bad state"), said);
        assertTrue(said.contains("\tat org.example.LoggingTest."), said);
        assertFalse(Files.readString(file).contains("IllegalStateException"), "the file is the same in debug mode");

        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        System.setErr(new PrintStream(plain, true, StandardCharsets.UTF_8));
        try (Logging logging = Logging.install(dir.resolve("other.log"))) {
            LOG.error("boom", new IllegalStateException("bad state"));
        }
        assertFalse(plain.toString(StandardCharsets.UTF_8).contains("IllegalStateException"), "and without debug mode the console has none either");
    }

    @Test
    void aCredentialInAMessageNeverReachesTheFile() throws Exception {
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file)) {
            LOG.info("sent Authorization: Bearer LEAK-BEARER and sk-ant-oat01-LEAK-KEY");
        }

        String content = Files.readString(file);
        assertFalse(content.contains("LEAK-BEARER"), content);
        assertFalse(content.contains("LEAK-KEY"), content);
        assertTrue(content.contains("[redacted]"), content);
        assertTrue(content.contains("sent "), "the rest of the message is kept");
    }

    @Test
    void aCredentialInAnExceptionNeverReachesTheFileNorTheConsoleOfDebugMode() throws Exception {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        System.setErr(new PrintStream(console, true, StandardCharsets.UTF_8));
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file, true)) {
            RuntimeException cause = new RuntimeException("cause carried sk-ant-oat01-LEAK-CAUSE");
            LOG.error("failed", new IllegalStateException("x-api-key: LEAK-HEADER", cause));
            LOG.error("failed too", new Fault("the program's own words, with sk-ant-oat01-LEAK-OWN in them"));
        }

        for (String content : List.of(Files.readString(file), console.toString(StandardCharsets.UTF_8))) {
            assertFalse(content.contains("LEAK-CAUSE"), content);
            assertFalse(content.contains("LEAK-HEADER"), content);
            assertFalse(content.contains("LEAK-OWN"), content);
        }
        assertTrue(console.toString(StandardCharsets.UTF_8).contains("IllegalStateException"), "the stack trace itself is kept, on the console");
    }

    @Test
    void closingTwiceHarmsNothingAndWhatElseListensGoesOnListening() throws Exception {
        List<String> heard = new CopyOnWriteArrayList<>();
        Log.Sink other = entry -> heard.add(entry.message());
        Log.addSink(other);
        try {
            Path file = dir.resolve("app.log");
            Logging logging = Logging.install(file);

            logging.close();
            logging.close();
            LOG.info("after the log was closed");

            assertEquals("after the log was closed", heard.getLast(), "a place that was listening before is not the log's to take away");
            assertFalse(Files.readString(file).contains("after the log was closed"), "and the file has no more of it");
        } finally {
            Log.removeSink(other);
        }
    }

    @Test
    void thereIsNoLockFileBesideTheLogWhileItIsOpenOrAfter() throws Exception {
        Path file = dir.resolve("app.log");
        List<String> during = new ArrayList<>();
        try (Logging logging = Logging.install(file)) {
            LOG.info("a line");
            try (var files = Files.list(dir)) {
                files.forEach(path -> during.add(path.getFileName().toString()));
            }
        }
        assertEquals(List.of("app.log"), during);
        try (var files = Files.list(dir)) {
            assertEquals(List.of("app.log"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void unwritableFileFallsBackToConsoleOnly() throws Exception {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        System.setErr(new PrintStream(console, true, StandardCharsets.UTF_8));
        Path missingDir = dir.resolve("no-such-dir").resolve("app.log");
        try (Logging logging = Logging.install(missingDir)) {
            LOG.info("still running");
        }
        assertEquals(List.of(), Files.list(dir).toList());
        String said = console.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("WARNING [Logging] Cannot write log file " + missingDir + "; logging to console only"), said);
        assertTrue(said.contains("[LoggingTest] still running"), said);
    }

    @Test
    void whatALibraryLogsThroughJavasOwnLoggingIsNotInTheFile() throws Exception {
        Path file = dir.resolve("app.log");
        java.util.logging.Logger library = java.util.logging.Logger.getLogger("com.example.other.LoggingTest");
        library.setUseParentHandlers(false);
        try (Logging logging = Logging.install(file)) {
            library.info("an information line of a library");
            LOG.info("a line of the program");
        }

        String content = Files.readString(file);
        assertFalse(content.contains("an information line of a library"), "the file holds the program's own lines only: " + content);
        assertTrue(content.contains("a line of the program"), content);
    }

    @Test
    void theLinesOfTwoThreadsAreNeverMixed() throws Exception {
        Path file = dir.resolve("app.log");
        try (Logging logging = Logging.install(file)) {
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                String who = "thread-" + t;
                threads.add(Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < 200; i++) {
                        LOG.info(who + " line " + i + " " + "x".repeat(200));
                    }
                }));
            }
            for (Thread thread : threads) {
                thread.join();
            }
        }
        List<String> lines = Files.readAllLines(file);
        assertEquals(801, lines.size());
        assertTrue(lines.stream().skip(1).allMatch(line -> line.matches(".* INFO    \\[LoggingTest] thread-\\d line \\d+ x{200}")), "every line whole");
    }
}
