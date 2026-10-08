package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real {@link ShutdownHook} in a child JVM and stops it the way the world does. What is
 * checked is what ends up in the log file, which is the whole point: the JVM runs its hooks side
 * by side, and the JDK's own logging hook closes the log handlers, so a log that is shut too early
 * silently loses the lines that say what happened.
 */
class ShutdownHookTest {

    private static final List<String> LINES = List.of("Shutting down", "Usage refresh stopped", "Frontend server stopped");

    @TempDir
    Path dir;

    private Process start(Path log, String mode) throws IOException {
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                ShutdownProbe.class.getName(), log.toString(), mode)
                .redirectErrorStream(true)
                .start();
    }

    /** Reads the child's output, its own log lines included, until it says it is ready. */
    private static void awaitReady(Process process) throws IOException {
        BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        for (int lines = 0; lines < 50; lines++) {
            String line = out.readLine();
            assertTrue(line != null, "the probe ended before it was ready");
            if (line.equals("ready")) {
                return;
            }
        }
        throw new AssertionError("the probe never said it was ready");
    }

    private static int finish(Process process) throws InterruptedException {
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "the process should end once told to stop");
        return process.exitValue();
    }

    private static void assertShutdownLoggedOnceInOrder(String log) {
        int from = 0;
        for (String line : LINES) {
            int at = log.indexOf(line, from);
            assertTrue(at >= 0, "expected \"" + line + "\" after offset " + from + " in:\n" + log);
            assertEquals(at, log.indexOf(line), "\"" + line + "\" should appear once, in:\n" + log);
            from = at + line.length();
        }
    }

    @Test
    @Timeout(120)
    void aProcessToldToTerminateStillLogsItsShutdownInOrderAndClosesTheLog() throws Exception {
        // Repeated: the JDK's own hook races ours, and a lost line would show only some of the time.
        for (int attempt = 1; attempt <= 6; attempt++) {
            Path log = dir.resolve("terminate-" + attempt + ".log");
            Process process = start(log, "wait");
            awaitReady(process);

            process.destroy();
            int exit = finish(process);

            assertEquals(143, exit, "SIGTERM, not a crash, attempt " + attempt);
            assertShutdownLoggedOnceInOrder(Files.readString(log));
            assertFalse(Files.exists(Path.of(log + ".lck")), "the log's lock file is gone, attempt " + attempt);
        }
    }

    @Test
    @Timeout(60)
    void aProcessInterruptedFromTheKeyboardDoesTheSame() throws Exception {
        Path log = dir.resolve("interrupt.log");
        Process process = start(log, "wait");
        awaitReady(process);

        new ProcessBuilder("kill", "-INT", Long.toString(process.pid())).start().waitFor();
        int exit = finish(process);

        assertEquals(130, exit);
        assertShutdownLoggedOnceInOrder(Files.readString(log));
        assertFalse(Files.exists(Path.of(log + ".lck")));
    }

    @Test
    @Timeout(60)
    void closingNormallyLogsTheShutdownOnceAndTheHookAddsNothing() throws Exception {
        Path log = dir.resolve("normal.log");
        Process process = start(log, "exit-normally");

        int exit = finish(process);

        assertEquals(0, exit);
        assertShutdownLoggedOnceInOrder(Files.readString(log));
        assertFalse(Files.exists(Path.of(log + ".lck")));
    }

    @Test
    @Timeout(60)
    void theHookLogsExactlyTheCleanupLinesAndNothingMoreOfItsOwn() throws Exception {
        Path log = dir.resolve("only-three.log");
        Process process = start(log, "wait");
        awaitReady(process);

        process.destroy();
        finish(process);

        long fromTheCleanup = Files.readAllLines(log).stream().filter(l -> l.contains("[ShutdownProbe]")).count();
        assertEquals(3, fromTheCleanup, Files.readString(log));
    }
}
